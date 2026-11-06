package app.seb3thehacker.gearslip.notify

import android.content.Context
import android.content.Intent
import app.seb3thehacker.gearslip.host.AndroidAuto
import app.seb3thehacker.gearslip.host.CarDescriptor
import app.seb3thehacker.gearslip.host.KnownApps

/** A messaging app that has told the system it belongs in a car. */
class MessagingApp(val packageName: String, val label: String)

/**
 * Finds messaging apps that opted in to Android Auto: those whose car descriptor lists
 * `notification` (Signal, Molly, WhatsApp, Telegram, Google Messages). Android Auto has no
 * screen of their own to open; it shows their conversations from the notifications they post,
 * and so does Gearslip.
 */
object MessagingCatalog {

    fun installed(context: Context): List<MessagingApp> {
        val manager = context.packageManager
        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return manager.queryIntentActivities(launchable, 0)
            .asSequence()
            .map { it.activityInfo.packageName to it.loadLabel(manager).toString() }
            .distinctBy { it.first }
            .filter { (pkg, _) -> pkg != context.packageName }
            .filterNot { (pkg, label) -> AndroidAuto.matches(pkg, label) }
            .filter { (pkg, _) -> CarDescriptor.uses(manager, pkg)?.contains("notification") == true }
            .map { (pkg, _) ->
                val label = runCatching {
                    manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                MessagingApp(pkg, label)
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
