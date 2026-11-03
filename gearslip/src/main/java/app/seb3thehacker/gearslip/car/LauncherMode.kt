package app.seb3thehacker.gearslip.car

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.LauncherActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Gearslip as the home screen of the device it runs on: a head unit that runs Android itself, or
 * a spare phone or tablet on the dash. The car UI fills the device's own screen, with no car in
 * between. Off until it's turned on in Settings, so a phone never lists Gearslip as a home app.
 */
object LauncherMode {

    private val _active = MutableStateFlow(false)

    /** The car UI is up on this device's own screen, as its home screen. */
    val active: StateFlow<Boolean> = _active

    internal fun setActive(on: Boolean) {
        _active.value = on
    }

    private fun component(context: Context) = ComponentName(context, LauncherActivity::class.java)

    /** Whether Android may offer Gearslip as the home app. */
    fun enabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(context: Context, on: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        GearslipLog.i("launcher mode ${if (on) "on" else "off"}")
    }

    /** Android's own "Default home app" choice, where Gearslip is picked once it's offered. */
    fun openHomeChoice(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { GearslipLog.w("launcher mode: no home app settings on this device (${it.message})") }
    }

    /** Head units and most tablets have no phone of their own to call with. */
    fun canCall(context: Context): Boolean {
        val feature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PackageManager.FEATURE_TELEPHONY_CALLING
        } else {
            @Suppress("DEPRECATION")
            PackageManager.FEATURE_TELEPHONY
        }
        return context.packageManager.hasSystemFeature(feature)
    }

    /**
     * Opens an ordinary app full screen, Gearslip's own phone screens included. The car UI lives
     * on a display of its own, and a context from it would start the app there, which Android
     * refuses; the application context and the device's own display put it on the real screen.
     */
    fun launch(context: Context, packageName: String) {
        val app = context.applicationContext
        val intent = app.packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
        runCatching { app.startActivity(intent, options.toBundle()) }
            .onFailure { GearslipLog.w("launcher mode: couldn't open $packageName (${it.message})") }
    }
}
