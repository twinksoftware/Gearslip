package app.seb3thehacker.gearslip.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The installer's answer about an update, and the new version's first start after one. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STATUS -> UpdateChecker.onInstallStatus(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> UpdateChecker.onReplaced(context)
        }
    }

    companion object {
        const val ACTION_STATUS = "app.seb3thehacker.gearslip.update.STATUS"
    }
}
