package app.seb3thehacker.gearslip.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Apps that say they work with Gearslip, by a line in their manifest's `<application>`:
 *
 * ```xml
 * <meta-data android:name="app.seb3thehacker.gearslip.works_with" android:value="true" />
 * <meta-data android:name="app.seb3thehacker.gearslip.about" android:resource="@string/gearslip_about" />
 * ```
 *
 * The second line is optional: one sentence for the app's page in Gearslip. Gearslip already sees
 * every app with a launcher icon (the manifest's MAIN/LAUNCHER query), so reading these needs
 * nothing more.
 */
object WorksWith {

    const val FLAG = "app.seb3thehacker.gearslip.works_with"
    const val ABOUT = "app.seb3thehacker.gearslip.about"

    class Declared(val packageName: String, val label: String, val about: String?)

    private fun meta(manager: PackageManager, pkg: String): Bundle? =
        runCatching { manager.getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData }.getOrNull()

    fun declares(manager: PackageManager, pkg: String): Boolean = meta(manager, pkg)?.getBoolean(FLAG) == true

    /** The app's own sentence about itself, from a string resource or written in place. */
    fun about(manager: PackageManager, pkg: String): String? {
        val meta = meta(manager, pkg) ?: return null
        val res = meta.getInt(ABOUT)
        if (res != 0) return runCatching { manager.getResourcesForApplication(pkg).getString(res) }.getOrNull()
        return meta.getString(ABOUT)?.takeIf { it.isNotBlank() }
    }

    /** Every installed app that declares the flag, by name. */
    fun installed(context: Context): List<Declared> {
        val manager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return manager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName && declares(manager, it) }
            .map { pkg ->
                val label = runCatching { manager.getApplicationInfo(pkg, 0).loadLabel(manager).toString() }.getOrDefault(pkg)
                Declared(pkg, label, about(manager, pkg))
            }
            .sortedBy { it.label.lowercase() }
    }
}
