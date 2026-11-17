package app.seb3thehacker.gearslip

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** User-facing preferences. Read fresh on every session, so changes apply on the next connect. */
object AppSettings {

    private const val PREFS = "gearslip_settings"
    private const val KEY_STARTUP_URL = "startup_url"
    private const val KEY_SKIPPED_CERT_SETUP = "skipped_cert_setup"
    private const val KEY_SEEN_PERMISSIONS_SETUP = "seen_permissions_setup"
    private const val KEY_GEARSLIP_ENABLED = "gearslip_enabled"
    private const val KEY_CABLE_ENABLED = "cable_enabled"
    private const val KEY_WIRELESS_AUTO = "wireless_auto_connect"
    private const val KEY_CONNECTION_ORDER = "connection_order"

    /** Which link Gearslip reaches for first when the car offers both. */
    enum class ConnectionOrder { CABLE_FIRST, WIRELESS_FIRST }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getString(context: Context, key: String, default: String): String =
        prefs(context).getString(key, default) ?: default

    fun putString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    /** Blank means "use the built-in test page". */
    fun startupUrl(context: Context): String =
        prefs(context).getString(KEY_STARTUP_URL, "").orEmpty()

    fun setStartupUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_STARTUP_URL, value.trim()).apply()
    }

    /** Whether the certificate setup screen has been deliberately skipped. */
    fun hasSkippedCertSetup(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SKIPPED_CERT_SETUP, false)

    fun setSkippedCertSetup(context: Context) {
        prefs(context).edit().putBoolean(KEY_SKIPPED_CERT_SETUP, true).apply()
    }

    /** Whether the one-by-one permissions wizard has already run once. */
    fun hasSeenPermissionsSetup(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEEN_PERMISSIONS_SETUP, false)

    fun setSeenPermissionsSetup(context: Context) {
        prefs(context).edit().putBoolean(KEY_SEEN_PERMISSIONS_SETUP, true).apply()
    }

    /**
     * Whether Gearslip is allowed to claim the USB accessory connection. Off leaves the app
     * installed but disables the GearslipUsbAccessory alias that answers
     * USB_ACCESSORY_ATTACHED, so a real Android Auto head unit (or Google's own app) gets the
     * connection instead - the two fight over it otherwise, since only one app can win the
     * accessory handoff. The alias is separate from GearslipActivity itself - the activity that
     * shows this very switch - so turning it off can't disable the screen you're looking at.
     * On by default.
     */
    fun gearslipEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GEARSLIP_ENABLED, true)

    fun setGearslipEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_GEARSLIP_ENABLED, value).apply()
        applyGearslipEnabled(context)
    }

    /**
     * Makes the USB alias match the saved switch. Called at every launch as well, so a switch
     * saved without the alias following it (an older build crashed right there) heals itself.
     * Only the alias changes: the driver's "always open with Gearslip" choice for the car is kept.
     */
    fun applyGearslipEnabled(context: Context) {
        val value = gearslipEnabled(context) && cableEnabled(context)
        val appContext = context.applicationContext
        // The class name comes from the code's namespace, not the package: a debug build installs
        // as ".dev", but the alias is still app.seb3thehacker.gearslip.GearslipUsbAccessory.
        val component = ComponentName(appContext.packageName, "${GearslipActivity::class.java.`package`!!.name}.GearslipUsbAccessory")
        val state = if (value) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val pm = appContext.packageManager
        if (pm.getComponentEnabledSetting(component) == state) return
        runCatching { pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP) }
            .onFailure { GearslipLog.e("could not switch the USB connection ${if (value) "on" else "off"}", it) }
    }

    /**
     * Whether Gearslip answers the car's USB connection. Off, the cable is left to stock Android
     * Auto (or just charges), while wireless keeps working. On by default.
     */
    fun cableEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CABLE_ENABLED, true)

    fun setCableEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CABLE_ENABLED, value).apply()
        applyGearslipEnabled(context)
    }

    /**
     * Whether a known car coming up over Bluetooth starts wireless by itself. Off, the car's own
     * Bluetooth (calls, music) is left alone until the driver taps Connect wirelessly. On by default.
     */
    fun wirelessAutoConnect(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIRELESS_AUTO, true)

    fun setWirelessAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIRELESS_AUTO, value).apply()
    }

    /** Cable first by default, which is how Gearslip has always behaved. */
    fun connectionOrder(context: Context): ConnectionOrder =
        runCatching { ConnectionOrder.valueOf(prefs(context).getString(KEY_CONNECTION_ORDER, null)!!) }
            .getOrDefault(ConnectionOrder.CABLE_FIRST)

    fun setConnectionOrder(context: Context, value: ConnectionOrder) {
        prefs(context).edit().putString(KEY_CONNECTION_ORDER, value.name).apply()
    }
}
