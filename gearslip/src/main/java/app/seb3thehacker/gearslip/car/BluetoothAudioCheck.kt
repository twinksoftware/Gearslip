package app.seb3thehacker.gearslip.car

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import app.seb3thehacker.gearslip.R
import android.os.Handler
import android.os.Looper
import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * With Bluetooth audio chosen, music only reaches the car if the phone is connected to it over
 * Bluetooth. Twenty seconds after the car shows Gearslip, long enough for a car to connect on its
 * own, this checks. If the phone isn't connected, the car and the phone's Home screen both say so,
 * until it connects or the drive ends.
 *
 * When the car named its Bluetooth address, only that device counts. Otherwise any audio
 * connection does, since there's no telling which device is the car.
 *
 * Many cars switch Bluetooth music off while Android Auto runs and want it over Android Auto
 * instead (a Skoda, a VW, an Audi, a Bolt). If the phone's Bluetooth audio to the car was up when
 * the car showed Gearslip and drops after, that's what happened: this car's music goes through
 * Gearslip from now on, and the car screen says so.
 */
object BluetoothAudioCheck {

    private val _missing = MutableStateFlow(false)
    /** True while Bluetooth audio is chosen and the phone isn't connected to the car for it. */
    val missing: StateFlow<Boolean> = _missing

    private val _carDismissed = MutableStateFlow(false)
    /** The driver tapped OK on the car's card; it stays away for the rest of this drive. */
    val carDismissed: StateFlow<Boolean> = _carDismissed

    private val log = GearslipLog.tagged("bluetooth")
    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var armed = false
    private var receiver: BroadcastReceiver? = null
    /** Whether Bluetooth audio to the car was up when the car showed Gearslip; null until known. */
    private var connectedAtStart: Boolean? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** The car shows Gearslip. Called again whenever the video restarts, so it arms once per drive. */
    fun onProjecting() = main.post {
        if (armed) return@post
        armed = true
        connectedAtStart = null
        audioToCar { connectedAtStart = it }
        main.postDelayed(::start, WAIT_MS)
    }

    fun onCarDisconnected() = main.post {
        armed = false
        connectedAtStart = null
        main.removeCallbacksAndMessages(null)
        stopWatching()
        _missing.value = false
        _carDismissed.value = false
    }

    fun dismissOnCar() {
        _carDismissed.value = true
    }

    /** "Don't show again" on the car: turns the warning off in Settings, for every car. */
    fun neverAgain() {
        CarSettings.setBluetoothWarning(false)
        log.i("Bluetooth audio warning turned off")
        _carDismissed.value = true
        _missing.value = false
    }

    private fun start() {
        if (!armed) return
        check()
        // Connecting later clears the warning, and losing the connection brings it back.
        val context = appContext ?: return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                main.postDelayed(::check, SETTLE_MS)
            }
        }
        context.registerReceiver(
            r,
            IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED).apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            },
            Context.RECEIVER_EXPORTED,
        )
        receiver = r
    }

    @SuppressLint("MissingPermission")
    private fun check() {
        if (!armed) return
        val context = appContext ?: return
        if (CarSettings.pipesNow) {
            set(false, "audio goes through Gearslip")
            return
        }
        audioToCar { ok ->
            if (ok == null) return@audioToCar
            if (!ok && connectedAtStart == true) return@audioToCar switchToGearslip()
            if (!CarSettings.bluetoothWarning.value) {
                set(false, "the warning is turned off")
                return@audioToCar
            }
            set(!ok, if (ok) "connected to the car" else "not connected to the car")
        }
    }

    /** The car dropped Bluetooth music once Gearslip was up: send this car's music through Gearslip. */
    private fun switchToGearslip() {
        val car = CarBluetooth.carAddress ?: return
        val context = appContext ?: return
        if (!app.seb3thehacker.gearslip.audio.CarAudio.sinkOffered) return
        log.i("Bluetooth audio: the car turned it off with Gearslip running, so this car's music goes through Gearslip")
        CarSettings.rememberAudioCar(car)
        set(false, "audio goes through Gearslip")
        app.seb3thehacker.gearslip.audio.CarAudio.request()
        CarToasts.show(this, context.getString(R.string.car_audio_switched), androidx.car.app.CarToast.LENGTH_LONG)
    }

    /**
     * Whether the phone's Bluetooth audio is connected to the car, on the main thread: null when
     * that can't be told (no permission). Bluetooth switched off counts as not connected.
     */
    @SuppressLint("MissingPermission")
    private fun audioToCar(result: (Boolean?) -> Unit) {
        val context = appContext ?: return result(null)
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            log.i("can't check the audio connection: the Bluetooth permission isn't granted")
            return result(null)
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return result(null)
        if (!adapter.isEnabled) return result(false)
        val car = CarBluetooth.carAddress?.uppercase()
        val asked = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val connected = runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
                adapter.closeProfileProxy(profile, proxy)
                val ok = if (car != null) connected.any { it.address.uppercase() == car } else connected.isNotEmpty()
                main.post { result(ok) }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
        if (!asked) result(null)
    }

    private fun set(missing: Boolean, why: String) {
        if (!armed || _missing.value == missing) return
        _missing.value = missing
        log.i("Bluetooth audio: $why")
    }

    private fun stopWatching() {
        val r = receiver ?: return
        receiver = null
        runCatching { appContext?.unregisterReceiver(r) }
    }

    private const val WAIT_MS = 20_000L
    private const val SETTLE_MS = 1_000L
}
