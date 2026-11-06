package app.seb3thehacker.gearslip.notify

import app.seb3thehacker.gearslip.host.CustomCarApps

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
            .toList()
            .let { found -> found + added(context, found.map { it.packageName }.toSet()) }
            .sortedBy { it.label.lowercase() }
    }

    /** The messengers the driver added by hand (issue #84); their notifications are all it takes. */
    private fun added(context: Context, found: Set<String>): List<MessagingApp> {
        val manager = context.packageManager
        return CustomCarApps.of(context, CustomCarApps.Kind.MESSAGING).filter { it.packageName !in found }.mapNotNull { custom ->
            val info = runCatching { manager.getApplicationInfo(custom.packageName, 0) }.getOrNull() ?: return@mapNotNull null
            MessagingApp(custom.packageName, info.loadLabel(manager).toString())
        }
    }
}
