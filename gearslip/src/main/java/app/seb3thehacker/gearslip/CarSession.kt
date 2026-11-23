package app.seb3thehacker.gearslip

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.view.Surface
import app.seb3thehacker.gearslip.audio.CarAudio
import app.seb3thehacker.gearslip.car.CarEnvironment
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.car.CarUi
import app.seb3thehacker.gearslip.mirror.PhoneMirror
import app.seb3thehacker.gearslip.mirror.TouchRelayService
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The car session, for as long as the appContext's process lives: the link to the car (USB or Wi-Fi),
 * the protocol runner, and the car UI drawn on its own display.
 *
 * It used to live in [GearslipActivity], so every connection opened Gearslip's screen on the phone,
 * even when the car came up by itself and the screen stepped aside at once: the phone flashed
 * Android's launch splash and a frame of Gearslip on every drive. Nothing here needs a screen on
 * the phone. What does (Android's consent to capture the music, a permission) opens
 * [CarPromptActivity], which shows nothing of its own.
 *
 * Main thread only, unless a function says otherwise.
 */
object CarSession : Projection {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context

    private var descriptor: ParcelFileDescriptor? = null
    /** The wireless session's socket, when the car came over Wi-Fi instead of USB. */
    private var wirelessSocket: java.net.Socket? = null
    private var runner: GearslipRunner? = null
    private var screenProjector: ScreenProjector? = null
    private var worker: Thread? = null

    /** The encoder's input Surface, kept so the car's picture can be switched to the mirror. */
    private var surface: Surface? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var frameDensity = 0
    private var frameRate = 0

    /** Told when the car's screen comes up, for a phone screen that opened for the car to step aside. */
    var onCarScreenUp: (() -> Unit)? = null

    /** Whether a USB car's session is open. */
    val onUsb: Boolean get() = descriptor != null

    private var initialized = false

    /**
     * What every way into the appContext needs before a car can connect: settings, the log, and the
     * hooks that bring a car's connection and its prompts here. Safe to call again.
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        GearslipLog.init(appContext)
        GearslipLog.installCrashHandler()
        CarSettings.init(appContext)
        // Before any car connects: CarServices only starts once the car shows Gearslip's screen,
        // and a car that waits to know the phone before it shows it would never hear (issue #43).
        app.seb3thehacker.gearslip.car.CarBluetooth.init(appContext)
        app.seb3thehacker.gearslip.browser.BrowserSettings.init(appContext)
        AppSettings.applyGearslipEnabled(appContext)
        // A capture that died without tidying up would leave notifications exposed to every
        // later screen share. Nothing is capturing if the service is not running, so undo it.
        if (!app.seb3thehacker.gearslip.audio.AudioCaptureService.running) {
            app.seb3thehacker.gearslip.audio.ScreenShareGuard.restore(appContext)
        }
        CarEnvironment.setPhoneTheme(appContext.resources.configuration)
        // A light/dark switch on the phone reaches the car UI with no screen of Gearslip's open.
        appContext.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = CarEnvironment.setPhoneTheme(newConfig)

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
        app.seb3thehacker.gearslip.wireless.WirelessLink.onConnected = { socket -> main.post { startWireless(socket) } }
        app.seb3thehacker.gearslip.wireless.WirelessLink.sessionWaiting = { runner?.waitingForVersion == true }
        PhoneMirror.onStartRequested = { main.post { CarPromptActivity.ask(appContext, CarPromptActivity.ACTION_MIRROR_CONSENT) } }
        PhoneMirror.onTokenReady = { token -> main.post { showMirror(token) } }
        PhoneMirror.onEnded = { main.post { showCarUi() } }
        CarAudio.onConsentRequested = { main.post { CarPromptActivity.ask(appContext, CarPromptActivity.ACTION_AUDIO_CONSENT) } }
        appContext.registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), Context.RECEIVER_NOT_EXPORTED)
        GearslipLog.i("Gearslip ready")
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                GearslipLog.w("accessory detached")
                // A wireless session has no accessory to lose; leave it running.
                if (wirelessSocket == null) close()
            }
        }
    }

    // --- starting and ending --------------------------------------------------------------

    /** A known car came up over Bluetooth: connect wirelessly, unless USB or another try is already on it. */
    fun connectToNearbyCar(address: String?) {
        if (address == null) return
        if (!AppSettings.gearslipEnabled(appContext)) {
            GearslipLog.i("auto-connect: Gearslip is switched off - not connecting")
            return
        }
        if (descriptor != null) {
            GearslipLog.i("auto-connect: the car is on USB already - staying on USB")
            return
        }
        // Only a session that's still running counts. One the car ended (a ByeBye, a dropped
        // network) leaves its runner behind, since only Gearslip ending a session clears it, and
        // that used to make every later auto-connect quietly do nothing.
        if (worker?.isAlive == true || app.seb3thehacker.gearslip.wireless.WirelessLink.busy) {
            GearslipLog.i("auto-connect: a session is already running")
            return
        }
        if (runner != null) close(stopWireless = false)
        if (appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            GearslipLog.w("auto-connect: needs the Nearby devices permission")
            return
        }
        val adapter = appContext.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        val device = adapter?.let { runCatching { it.getRemoteDevice(address) }.getOrNull() }
        if (device == null) {
            GearslipLog.w("auto-connect: Bluetooth couldn't hand over the car")
            return
        }
        GearslipLog.i("auto-connect: connecting")
        app.seb3thehacker.gearslip.wireless.WirelessLink.start(appContext, device)
    }

    fun startAccessory(accessory: UsbAccessory) {
        // A fresh ATTACHED intent means the system wants a new session, whatever state the old
        // one is in - closing first guarantees openAccessory() below never fights a descriptor
        // this same process forgot to release. The old worker thread often isn't alive by this
        // point anyway (its read loop already died when the head unit dropped the transport),
        // but the accessory it opened was never explicitly closed, which is what actually blocks
        // a re-open - a dead thread does not release the file descriptor on its own.
        close()
        GearslipLog.i("accessory: manufacturer=${accessory.manufacturer} model=${accessory.model}")
        GearslipLog.i("           version=${accessory.version} description=${accessory.description}")

        val manager = appContext.getSystemService(UsbManager::class.java)
        val pfd = manager.openAccessory(accessory)
        if (pfd == null) {
            GearslipLog.e("openAccessory returned null - the session was claimed by someone else")
            SessionStatus.failed("Accessory unavailable", "Another appContext claimed the USB accessory before Gearslip could open it.")
            return
        }
        descriptor = pfd
        run(GearslipRunner(
            input = FileInputStream(pfd.fileDescriptor),
            output = FileOutputStream(pfd.fileDescriptor),
            identityProvider = { CertProvider.load(appContext) },
            projection = this,
            vehicleProfileFor = { info -> VehicleProfiles.find(VehicleProfiles.load(appContext), info) },
            phoneIcons = { PhoneIcons.pngs(appContext) },
        ))
    }

    /** A wireless session: the same runner, on the socket [app.seb3thehacker.gearslip.wireless.WirelessLink] opened to the car. */
    private fun startWireless(socket: java.net.Socket) {
        close(stopWireless = false)
        if (!AppSettings.gearslipEnabled(appContext)) {
            GearslipLog.i("wireless: Gearslip is switched off - not connecting")
            runCatching { socket.close() }
            return
        }
        GearslipLog.i("wireless: starting a session over Wi-Fi to ${socket.inetAddress?.hostAddress}:${socket.port}")
        wirelessSocket = socket
        run(GearslipRunner(
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            identityProvider = { CertProvider.load(appContext) },
            projection = this,
            vehicleProfileFor = { info -> VehicleProfiles.find(VehicleProfiles.load(appContext), info) },
            wireless = true,
            phoneIcons = { PhoneIcons.pngs(appContext) },
        ))
    }

    private fun run(runner: GearslipRunner) {
        this.runner = runner
        worker = Thread { runner.run() }.apply { name = "gearslip"; start() }
    }

    /** Whether this session holds [app.seb3thehacker.gearslip.host.LocationKeepAlive]. */
    private var holdingLocation = false

    /**
     * Android lets a foreground service started from the background use location only in the few
     * seconds after one of the appContext's screens opens. A screen opened for the car calls this, so a
     * map bound later, with the phone already locked, still gets a position.
     */
    fun holdLocation(context: Context) {
        if (holdingLocation) return
        holdingLocation = true
        app.seb3thehacker.gearslip.host.LocationKeepAlive.acquire(context) {}
    }

    /** Ends the session, wired or wireless. [stopWireless] also drops the car's Bluetooth and Wi-Fi. */
    fun close(stopWireless: Boolean = true) {
        runner?.stop()
        SessionReport.print()
        runCatching { descriptor?.close() }
        descriptor = null
        runCatching { wirelessSocket?.close() }
        wirelessSocket = null
        if (stopWireless) app.seb3thehacker.gearslip.wireless.WirelessLink.stop(appContext)
        runner = null
        if (holdingLocation) {
            holdingLocation = false
            app.seb3thehacker.gearslip.host.LocationKeepAlive.release(appContext)
        }
    }

    // --- the car's screen -----------------------------------------------------------------

    /** A Surface has a single producer, so our own display must go before the mirror arrives. */
    private fun showMirror(token: MediaProjection) {
        val target = surface ?: return
        screenProjector?.stop()
        screenProjector = null
        PhoneMirror.begin(token, target, frameWidth, frameHeight, frameDensity)
        if (PhoneMirror.active.value) PhoneMirror.launchPending(appContext) else showCarUi()
    }

    private fun showCarUi() {
        val target = surface ?: return
        if (screenProjector != null) return
        val projector = ScreenProjector(appContext).also { screenProjector = it }
        // The frame's density, not frameDensity: that stays the car's own, for the phone mirror.
        projector.start(target, frameWidth, frameHeight, CarEnvironment.frame.value.densityDpi, frameRate) { CarUi() }
    }

    /**
     * Sends a head-unit touch to the phone. Anything landing on the mirror's letterbox padding
     * maps to nothing on the phone and is dropped rather than clamped onto a screen edge.
     */
    private fun relayTouch(action: Int, x: Float, y: Float) {
        val point = PhoneMirror.mapToPhone(appContext, x, y)
        if (point == null) {
            // The letterbox bars belong to no window, which makes them a safe way back: a tap
            // on the padding leaves the mirror and returns the car to Gearslip's own UI.
            if (action == MotionEvent.ACTION_UP) PhoneMirror.requestStop()
            return
        }
        val relay = TouchRelayService.instance ?: return
        when (action) {
            MotionEvent.ACTION_DOWN -> relay.down(point.x, point.y)
            MotionEvent.ACTION_MOVE -> relay.move(point.x, point.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> relay.up(point.x, point.y)
        }
    }

    // --- Projection, from the runner's thread -----------------------------------------------

    override fun onSurfaceReady(surface: Surface, width: Int, height: Int, densityDpi: Int, frameRate: Int) {
        main.post {
            this.surface = surface
            frameWidth = width
            frameHeight = height
            frameDensity = densityDpi
            this.frameRate = frameRate
            CarEnvironment.setDisplay(width, height, densityDpi)
            CarEnvironment.start(appContext)
            showCarUi()
            onCarScreenUp?.invoke()
            app.seb3thehacker.gearslip.car.CarKeys.focusSink = { code, down ->
                main.post { screenProjector?.dispatchKey(code, down) }
            }
        }
    }

    override fun onTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        // To the front of the main thread's queue: a touch waiting behind other work is lag the
        // driver feels, while the work it jumps ahead of can wait a frame.
        main.postAtFrontOfQueue {
            // Mirroring drives one phone touch at a time, so it follows the first finger only.
            if (PhoneMirror.active.value) points.firstOrNull()?.let { relayTouch(action, it.x, it.y) }
            else screenProjector?.dispatchTouch(action, actionIndex, points)
        }
    }

    override fun onStill() {
        main.post { screenProjector?.redraw() }
    }

    override fun onProjectionStopped() {
        // Before the car UI goes, while the media connection is still up.
        app.seb3thehacker.gearslip.car.CarServices.pausePhoneMedia()
        main.post {
            PhoneMirror.requestStop()
            app.seb3thehacker.gearslip.car.CarKeys.focusSink = null
            screenProjector?.stop()
            screenProjector = null
            surface = null
            CarEnvironment.stop()
        }
    }
}
