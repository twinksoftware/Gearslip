package app.seb3thehacker.gearslip.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import java.util.Calendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the car UI and the apps inside it can know about the world outside the UI.
 *
 * [phoneDark] is the phone's own theme and picks the car UI's colours. [darkOutside] is a
 * separate signal, "is it dark out", for apps that want to adapt (a map, a web page).
 *
 * Where [darkOutside] comes from is deliberately swappable. Today it is a time-of-day guess.
 * The right source is the head unit's own night-mode sensor, which arrives on the AA sensor
 * channel once that exists; feed it in through [setSensorNight] and it takes over.
 */
object CarEnvironment {

    data class Battery(val percent: Int, val charging: Boolean)

    private val phoneDarkFlow = MutableStateFlow(false)
    private val darkOutsideFlow = MutableStateFlow(false)
    private val batteryFlow = MutableStateFlow(Battery(-1, false))
    private val displayFlow = MutableStateFlow("")

    /** The projected frame's real geometry, for anything that has to lay out against it. */
    data class Frame(val width: Int, val height: Int, val densityDpi: Int)

    private val frameFlow = MutableStateFlow(Frame(800, 480, 160))
    val frame: StateFlow<Frame> = frameFlow

    /** The density the car reported; [frame] carries the one the car UI and its apps draw at. */
    private var carDensityDpi = 160

    val phoneDark: StateFlow<Boolean> = phoneDarkFlow
    val darkOutside: StateFlow<Boolean> = darkOutsideFlow
    val battery: StateFlow<Battery> = batteryFlow

    /** Human-readable projected resolution, for the connection info in car Settings. */
    val display: StateFlow<String> = displayFlow

    private val vehicleFlow = MutableStateFlow("")
    private val insetsFlow = MutableStateFlow(app.seb3thehacker.gearslip.Insets.NONE)

    /** Name of the matched vehicle profile, for the connection info in car Settings. */
    val vehicle: StateFlow<String> = vehicleFlow

    /** How much of the video frame the car UI keeps clear, from the matched vehicle profile. */
    val insets: StateFlow<app.seb3thehacker.gearslip.Insets> = insetsFlow

    /** Live adjustment from car Settings; not persisted until saved to the vehicle profile. */
    fun setInsets(insets: app.seb3thehacker.gearslip.Insets) {
        insetsFlow.value = insets
    }

    fun setVehicle(name: String, insets: app.seb3thehacker.gearslip.Insets) {
        vehicleFlow.value = name
        insetsFlow.value = insets
    }

    @Volatile private var sensorNight: Boolean? = null
    /** Set once the car UI starts; the cluster screen draws with it too. */
    var appContext: Context? = null
        private set
    private var running = false

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 60_000)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let(::readBattery)
        }
    }

    fun setPhoneTheme(configuration: Configuration) {
        phoneDarkFlow.value =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** For the AA sensor channel: null goes back to the time-of-day guess. */
    fun setSensorNight(night: Boolean?) {
        sensorNight = night
        refresh()
    }

    fun setDisplay(width: Int, height: Int, densityDpi: Int) {
        carDensityDpi = densityDpi
        val dpi = CarSettings.effectiveDensity(densityDpi)
        displayFlow.value = "${width}x$height @ ${dpi}dpi"
        frameFlow.value = Frame(width, height, dpi)
    }

    /**
     * Takes up the density setting. It's read only as a session starts, so the display and the
     * apps on it can't disagree mid-drive; a session with no car behind it, the preview, asks here.
     */
    fun applyDensity() {
        frameFlow.value = frameFlow.value.copy(densityDpi = CarSettings.effectiveDensity(carDensityDpi))
    }

    fun start(context: Context) {
        if (running) return
        running = true
        appContext = context.applicationContext
        setPhoneTheme(context.resources.configuration)
        val sticky = context.applicationContext.registerReceiver(
            batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let(::readBattery)
        handler.post(tick)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        runCatching { appContext?.unregisterReceiver(batteryReceiver) }
    }

    fun refresh() {
        darkOutsideFlow.value = when (CarSettings.nightMode.value) {
            NightMode.DAY -> false
            NightMode.NIGHT -> true
            NightMode.AUTO -> sensorNight ?: guessNightFromClock()
        }
    }

    private fun guessNightFromClock(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 20 || hour < 6
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        batteryFlow.value = Battery(if (level >= 0 && scale > 0) level * 100 / scale else -1, charging)
    }
}
