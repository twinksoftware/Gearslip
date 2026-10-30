package app.seb3thehacker.gearslip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.MotionEvent
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import android.content.res.Configuration
import app.seb3thehacker.gearslip.audio.AudioCaptureService
import app.seb3thehacker.gearslip.audio.CarAudio
import app.seb3thehacker.gearslip.mirror.MirrorService
import app.seb3thehacker.gearslip.mirror.PhoneMirror
import app.seb3thehacker.gearslip.mirror.TouchRelayService
import app.seb3thehacker.gearslip.car.CarEnvironment
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.car.CarUi
import app.seb3thehacker.gearslip.car.Prefetch
import app.seb3thehacker.gearslip.stats.UsageStats
import app.seb3thehacker.gearslip.ui.GearslipApp
import app.seb3thehacker.gearslip.ui.GearslipTheme
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Hosts the phone UI and, for now, the car session itself.
 *
 * Launched by the system when the head unit switches the phone into accessory mode. The
 * session lives in this activity, so [android:configChanges] in the manifest keeps a
 * rotation or theme change from recreating it and dropping the connection.
 */
class GearslipActivity : ComponentActivity(), Projection {
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())


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

    /**
     * Screen capture always begins with a consent dialog, which only an activity can show -
     * so the car UI asks, and the request surfaces here on the phone.
     */
    private val captureConsent = registerForActivityResult(StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            MirrorService.start(this, result.resultCode, data)
        } else {
            GearslipLog.w("screen capture consent declined")
        }
    }

    /** Media audio needs the microphone permission (a platform rule for playback capture) first. */
    private val audioPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) askAudioCapture()
        else if (!shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) {
            // Refused twice: Android now says no without showing anything, so the phone must.
            GearslipLog.w("audio: record permission blocked - Android no longer asks")
            CarAudio.micBlocked()
            micBlockedNotice.value = true
        } else {
            GearslipLog.w("audio: record permission declined")
            CarAudio.denied()
        }
    }

    private val audioConsent = registerForActivityResult(StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            AudioCaptureService.start(this, result.resultCode, data)
        } else {
            GearslipLog.w("audio: capture consent declined")
            CarAudio.denied()
            if (!CarSettings.audioChoiceAsked) offerBluetooth.value = true
        }
    }

    /** Raised when the microphone permission is blocked; see [MicBlockedDialog]. */
    private val micBlockedNotice = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Raised once, after the first "no" to the capture dialog; see [AudioChoiceDialog]. */
    private val offerBluetooth = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                GearslipLog.w("accessory detached")
                // A wireless session has no accessory to lose; leave it running.
                if (wirelessSocket == null) closeAccessory()
            }
        }
    }

    /** Android's one-time "Allow" for a car to connect by itself; see [WirelessAuto]. */
    private var approvingCar: String? = null
    private val carApproval = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val address = approvingCar ?: return@registerForActivityResult
        approvingCar = null
        if (result.resultCode == RESULT_OK) app.seb3thehacker.gearslip.wireless.WirelessAuto.approved(this, address)
        else app.seb3thehacker.gearslip.wireless.WirelessAuto.declined(this, address)
    }

    /** Setting Gearslip as the phone's caller ID and spam app - what lets it decline a ringing call. */
    private val callScreeningRole = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) GearslipLog.w("calls: call screening role declined")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Location, calendar, the phone/contacts group, and the microphone are asked for one at
        // a time by PermissionsSetupScreen instead of here, each only after the driver taps
        // Allow on a screen explaining what it is for. Firing them all from onCreate, unprompted,
        // is both a bad first impression and unreliable: Android does not consistently show more
        // than one system permission dialog fired back-to-back, which was silently dropping some.
        enableEdgeToEdge()

        GearslipLog.init(this)
        GearslipLog.installCrashHandler()
        GearslipLog.i("Gearslip ready")
        app.seb3thehacker.gearslip.wireless.WirelessLink.onConnected = { socket ->
            runOnUiThread { startWireless(socket) }
        }
        app.seb3thehacker.gearslip.wireless.WirelessAuto.showApproval = { chooser, address ->
            runOnUiThread {
                approvingCar = address
                carApproval.launch(androidx.activity.result.IntentSenderRequest.Builder(chooser).build())
            }
        }
        CarSettings.init(this)
        AppSettings.applyGearslipEnabled(this)
        // A capture that died without tidying up would leave notifications exposed to every
        // later screen share. Nothing is capturing if the service is not running, so undo it.
        if (!app.seb3thehacker.gearslip.audio.AudioCaptureService.running) {
            app.seb3thehacker.gearslip.audio.ScreenShareGuard.restore(this)
        }
        Prefetch.warm(this)
        CarEnvironment.setPhoneTheme(resources.configuration)

        setContent {
            GearslipTheme {
                GearslipApp(onDisconnect = { closeAccessory() }, onRequestCallScreening = ::requestCallScreeningRole)
                val offer by offerBluetooth.collectAsState()
                if (offer) {
                    fun close() {
                        CarSettings.markAudioChoiceAsked()
                        offerBluetooth.value = false
                    }
                    app.seb3thehacker.gearslip.ui.AudioChoiceDialog(
                        onUseBluetooth = {
                            CarSettings.setPipeAudio(false)
                            close()
                        },
                        onKeepAsking = ::close,
                    )
                }
                val micNotice by micBlockedNotice.collectAsState()
                if (micNotice) {
                    app.seb3thehacker.gearslip.ui.MicBlockedDialog(
                        onOpenSettings = {
                            micBlockedNotice.value = false
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                            )
                        },
                        onDismiss = { micBlockedNotice.value = false },
                    )
                }
                val vpnCar by app.seb3thehacker.gearslip.wireless.WirelessLink.vpnWarning.collectAsState()
                vpnCar?.let { car ->
                    app.seb3thehacker.gearslip.ui.VpnDialog(
                        car,
                        onDismiss = app.seb3thehacker.gearslip.wireless.WirelessLink::dismissVpnWarning,
                    )
                }
            }
        }

        PhoneMirror.onStartRequested = { runOnUiThread { requestCapture() } }
        PhoneMirror.onTokenReady = { token -> runOnUiThread { showMirror(token) } }
        PhoneMirror.onEnded = { runOnUiThread { showCarUi() } }
        CarAudio.onConsentRequested = { runOnUiThread { requestAudioCapture() } }

        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), RECEIVER_NOT_EXPORTED)
        handleIntent(intent)
    }

    // The manifest opts out of recreation on uiMode changes, so a light/dark switch on the phone
    // arrives here, and the car UI follows it.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        CarEnvironment.setPhoneTheme(newConfig)
    }

    // Throttled inside Prefetch, so this only does work if the last run was a while ago.
    override fun onStart() {
        super.onStart()
        Prefetch.warm(this)
        UsageStats.onAppOpened(this)
        app.seb3thehacker.gearslip.update.UpdateChecker.onAppOpened(this)
        // Back from the app's settings with the microphone turned on: try car audio again.
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            CarAudio.micAllowed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        app.seb3thehacker.gearslip.wireless.WirelessLink.onConnected = null
        app.seb3thehacker.gearslip.wireless.WirelessAuto.showApproval = null
        runCatching { unregisterReceiver(detachReceiver) }
        PhoneMirror.onStartRequested = null
        PhoneMirror.onTokenReady = null
        PhoneMirror.onEnded = null
        CarAudio.onConsentRequested = null
        CarAudio.release()
        PhoneMirror.requestStop()
        closeAccessory()
        GearslipLog.flush()
    }

    /** Launches the system dialog that offers Gearslip the call-screening role, if it isn't held already. */
    private fun requestCallScreeningRole() {
        val roleManager = getSystemService(android.app.role.RoleManager::class.java)
        if (roleManager == null || !roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_SCREENING)) {
            GearslipLog.w("calls: call screening role unavailable on this phone")
            return
        }
        if (roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)) return
        runCatching {
            callScreeningRole.launch(roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING))
        }.onFailure { GearslipLog.e("could not ask for the call screening role", it) }
    }

    // --- Media audio --------------------------------------------------------------------

    private fun requestAudioCapture() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askAudioCapture()
        } else {
            audioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun askAudioCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { audioConsent.launch(manager.createScreenCaptureIntent()) }
            .onFailure {
                GearslipLog.e("could not ask for audio capture", it)
                CarAudio.denied()
            }
    }

    // --- Phone mirroring ----------------------------------------------------------------

    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { captureConsent.launch(manager.createScreenCaptureIntent()) }
            .onFailure { GearslipLog.e("could not ask for screen capture", it) }
    }

    /** A Surface has a single producer, so our own display must go before the mirror arrives. */
    private fun showMirror(token: MediaProjection) {
        val target = surface ?: return
        screenProjector?.stop()
        screenProjector = null
        PhoneMirror.begin(token, target, frameWidth, frameHeight, frameDensity)
        if (PhoneMirror.active.value) PhoneMirror.launchPending(this) else showCarUi()
    }

    private fun showCarUi() {
        val target = surface ?: return
        if (screenProjector != null) return
        val projector = ScreenProjector(this).also { screenProjector = it }
        projector.start(target, frameWidth, frameHeight, frameDensity) { CarUi() }
    }

    /**
     * Sends a head-unit touch to the phone. Anything landing on the mirror's letterbox padding
     * maps to nothing on the phone and is dropped rather than clamped onto a screen edge.
     */
    private fun relayTouch(action: Int, x: Float, y: Float) {
        val point = PhoneMirror.mapToPhone(this, x, y)
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

    private fun handleIntent(intent: Intent?) {
        // Debug builds only: opens the audio choice for a look, without turning down a real prompt.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("show_audio_choice", false) == true) offerBluetooth.value = true
        if (intent?.action == app.seb3thehacker.gearslip.wireless.WirelessAuto.ACTION_CAR_NEARBY) {
            connectToNearbyCar(intent.getStringExtra(app.seb3thehacker.gearslip.wireless.WirelessAuto.EXTRA_ADDRESS))
            return
        }
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        // Switched off in Settings: the system shouldn't route the car here at all, but if it
        // does, leave the accessory alone.
        if (!AppSettings.gearslipEnabled(this)) {
            GearslipLog.i("accessory attached, but Gearslip is switched off - not connecting")
            return
        }
        val accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        if (accessory == null) {
            GearslipLog.e("attach intent carried no accessory")
            SessionStatus.failed("Accessory missing", "The system reported a USB attach but did not include the accessory.")
            return
        }
        GearslipLog.i("accessory attached by the system - permission is implicit on this path")
        start(accessory)
    }

    /** A known car came up over Bluetooth: connect wirelessly, unless USB or another try is already on it. */
    private fun connectToNearbyCar(address: String?) {
        if (address == null) return
        if (!AppSettings.gearslipEnabled(this)) {
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
        if (runner != null) closeAccessory(stopWireless = false)
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            GearslipLog.w("auto-connect: needs the Nearby devices permission")
            return
        }
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        val device = adapter?.let { runCatching { it.getRemoteDevice(address) }.getOrNull() }
        if (device == null) {
            GearslipLog.w("auto-connect: Bluetooth couldn't hand over the car")
            return
        }
        GearslipLog.i("auto-connect: connecting")
        app.seb3thehacker.gearslip.wireless.WirelessLink.start(this, device, automatic = true)
    }

    private fun start(accessory: UsbAccessory) {
        // A fresh ATTACHED intent means the system wants a new session, whatever state the old
        // one is in - closing first guarantees openAccessory() below never fights a descriptor
        // this same process forgot to release. The old worker thread often isn't alive by this
        // point anyway (its read loop already died when the head unit dropped the transport),
        // but the accessory it opened was never explicitly closed, which is what actually blocks
        // a re-open - a dead thread does not release the file descriptor on its own.
        closeAccessory()
        describe(accessory)

        val manager = getSystemService(UsbManager::class.java)
        val pfd = manager.openAccessory(accessory)
        if (pfd == null) {
            GearslipLog.e("openAccessory returned null - the session was claimed by someone else")
            SessionStatus.failed("Accessory unavailable", "Another app claimed the USB accessory before Gearslip could open it.")
            return
        }
        descriptor = pfd

        val runner = GearslipRunner(
            input = FileInputStream(pfd.fileDescriptor),
            output = FileOutputStream(pfd.fileDescriptor),
            identityProvider = { CertProvider.load(this) },
            projection = this,
            vehicleProfileFor = { info -> VehicleProfiles.find(VehicleProfiles.load(this), info) },
            onCarGone = ::onCarGone,
        )
        this.runner = runner
        worker = Thread { runner.run() }.apply { name = "gearslip"; start() }
    }

    /** A wireless session: the same runner, on the socket [WirelessLink] opened to the car. */
    private fun startWireless(socket: java.net.Socket) {
        closeAccessory(stopWireless = false)
        if (!AppSettings.gearslipEnabled(this)) {
            GearslipLog.i("wireless: Gearslip is switched off - not connecting")
            runCatching { socket.close() }
            return
        }
        GearslipLog.i("wireless: starting a session over Wi-Fi to ${socket.inetAddress?.hostAddress}:${socket.port}")
        wirelessSocket = socket
        val runner = GearslipRunner(
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            identityProvider = { CertProvider.load(this) },
            projection = this,
            vehicleProfileFor = { info -> VehicleProfiles.find(VehicleProfiles.load(this), info) },
            wireless = true,
            onCarGone = ::onCarGone,
        )
        this.runner = runner
        worker = Thread { runner.run() }.apply { name = "gearslip"; start() }
    }

    /**
     * The car hung up on its own: a wireless car switched off or dropped Wi-Fi, or a head unit
     * closed the link without the phone seeing a USB detach. Nothing used to tear the session
     * down then, so the music played on, captured into a link nobody was reading, until the next
     * connection cleaned up. End it as an unplug does - pause, stop capture, unmute - but leave
     * the wireless link up so auto-connect can reach the car again.
     */
    private fun onCarGone() {
        val ended = Thread.currentThread() // the session's own reading thread
        runOnUiThread {
            // Already closed from the phone, or replaced by a newer session.
            if (runner == null || worker !== ended) return@runOnUiThread
            GearslipLog.w("the car ended the session - closing it on the phone too")
            closeAccessory(stopWireless = false)
        }
    }

    private fun describe(accessory: UsbAccessory) {
        GearslipLog.i("accessory: manufacturer=${accessory.manufacturer} model=${accessory.model}")
        GearslipLog.i("           version=${accessory.version} description=${accessory.description}")
    }

    private fun closeAccessory() = closeAccessory(stopWireless = true)

    /** Ends the session, wired or wireless. [stopWireless] also drops the car's Bluetooth and Wi-Fi. */
    private fun closeAccessory(stopWireless: Boolean) {
        runner?.stop()
        SessionReport.print()
        runCatching { descriptor?.close() }
        descriptor = null
        runCatching { wirelessSocket?.close() }
        wirelessSocket = null
        if (stopWireless) app.seb3thehacker.gearslip.wireless.WirelessLink.stop(this)
        runner = null
    }

    // --- Projection ---------------------------------------------------------------------

    override fun onSurfaceReady(surface: Surface, width: Int, height: Int, densityDpi: Int) {
        runOnUiThread {
            this.surface = surface
            frameWidth = width
            frameHeight = height
            frameDensity = densityDpi
            CarEnvironment.setDisplay(width, height, densityDpi)
            CarEnvironment.start(this)
            showCarUi()
            app.seb3thehacker.gearslip.car.CarKeys.focusSink = { code, down ->
                runOnUiThread { screenProjector?.dispatchKey(code, down) }
            }
        }
    }

    override fun onTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        // To the front of the main thread's queue: a touch waiting behind other work is lag the
        // driver feels, while the work it jumps ahead of can wait a frame.
        mainHandler.postAtFrontOfQueue {
            // Mirroring drives one phone touch at a time, so it follows the first finger only.
            if (PhoneMirror.active.value) points.firstOrNull()?.let { relayTouch(action, it.x, it.y) }
            else screenProjector?.dispatchTouch(action, actionIndex, points)
        }
    }

    override fun onProjectionStopped() {
        // Before the car UI goes, while the media connection is still up.
        app.seb3thehacker.gearslip.car.CarServices.pausePhoneMedia()
        runOnUiThread {
            PhoneMirror.requestStop()
            app.seb3thehacker.gearslip.car.CarKeys.focusSink = null
            screenProjector?.stop()
            screenProjector = null
            surface = null
            CarEnvironment.stop()
        }
    }
}
