package app.seb3thehacker.gearslip.wireless

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.seb3thehacker.gearslip.GearslipLog

/**
 * Keeps Gearslip in the foreground while a wireless car is connected, from the moment its
 * Bluetooth channel opens until the drive ends. Android treats an app in the background as
 * unwanted and may refuse its Wi-Fi request or kill it, which is what happens to a start-up with
 * the phone locked in a pocket. It does no work of its own; the notification is the cost.
 */
class WirelessKeepAlive : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Wireless car connection", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Gearslip is connected to the car wirelessly")
            .setOngoing(true)
            .build()
        // Refused if Android thinks Gearslip may not start one now; the start-up carries on
        // without it, as it did before this service existed.
        runCatching {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }.onFailure {
            GearslipLog.w("wireless keep-alive: could not start the foreground service - ${it.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "gearslip_wireless"
        private const val NOTIFICATION_ID = 45

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, WirelessKeepAlive::class.java)) }
                .onFailure { GearslipLog.w("wireless keep-alive: start refused - ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WirelessKeepAlive::class.java)) }
        }
    }
}
