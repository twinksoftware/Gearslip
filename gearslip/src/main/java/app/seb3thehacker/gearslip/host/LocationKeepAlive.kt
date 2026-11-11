package app.seb3thehacker.gearslip.host

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.seb3thehacker.gearslip.GearslipLog

/**
 * Lets a hosted navigation app keep receiving the phone's location while Gearslip is not on screen.
 *
 * Android only gives an app location in the background if something visible to the user is
 * running for it. A nav app bound as a car app has nothing of its own showing, so it goes
 * without a fix as soon as the phone screen leaves it. Binding with BIND_INCLUDE_CAPABILITIES
 * lends it this service's location capability, which is why this foreground service exists.
 * It needs the ordinary location permission, granted on the phone.
 */
class LocationKeepAlive : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Navigation location", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Gearslip is sharing location with the car map")
            .setOngoing(true)
            .build()
        // The system can refuse a location-type foreground service if Gearslip is no longer in
        // an eligible state by the time this runs - the phone screen locking mid-session is the
        // common case. That refusal throws, and left uncaught it takes the whole app down with
        // it; a nav app losing its background location fix is a small loss next to that.
        runCatching {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }.onSuccess {
            settle(State.ON)
        }.onFailure {
            val background = if (allowedInBackground(this)) "allowed all the time" else "not allowed all the time"
            GearslipLog.w("location keep-alive: could not start the foreground service ($background) - ${it.message}")
            settle(State.FAILED)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (state == State.ON) state = State.OFF
        super.onDestroy()
    }

    enum class State { OFF, STARTING, ON, FAILED }

    companion object {
        private const val CHANNEL = "gearslip_location"
        private const val NOTIFICATION_ID = 44

        /** Public flag value; the constant itself is hidden from the SDK. */
        const val BIND_INCLUDE_CAPABILITIES = 0x1000

        private val main = Handler(Looper.getMainLooper())

        @Volatile var state = State.OFF
            private set

        /** Each connected car app holds one; the service stops only when the last lets go. */
        private var holders = 0
        private val waiting = mutableListOf<() -> Unit>()

        /**
         * Takes a hold on the service, starting it if it isn't running, and calls [ready] (on the
         * main thread) once it's running, has failed, or [timeoutMs] has passed - whichever comes
         * first. A nav app bound before this has settled has no location capability to borrow,
         * and some (MapQuest) crash outright when their own location service is refused.
         */
        fun acquire(context: Context, timeoutMs: Long = 3_000, ready: () -> Unit) {
            main.post {
                holders++
                if (state == State.OFF || state == State.FAILED) begin(context.applicationContext)
                if (state != State.STARTING) {
                    ready()
                    return@post
                }
                var done = false
                val once = { if (!done) { done = true; ready() } }
                waiting += once
                main.postDelayed({
                    if (!done) GearslipLog.w("location keep-alive: still not running after ${timeoutMs}ms, binding anyway")
                    waiting.remove(once)
                    once()
                }, timeoutMs)
            }
        }

        /**
         * Starts the service again if a car still holds it but it was refused, as it is when a car
         * connects with the phone locked and "Allow all the time" isn't granted. Called once the
         * driver grants it, so the map gets its fix without reconnecting.
         */
        fun retry(context: Context) {
            main.post {
                if (holders > 0 && state == State.FAILED) begin(context.applicationContext)
            }
        }

        /** Whether Android lets this service start with the phone locked (issue #99). */
        fun allowedInBackground(context: Context) =
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Lets go of one hold; the last one stops the service. */
        fun release(context: Context) {
            main.post {
                holders = (holders - 1).coerceAtLeast(0)
                if (holders > 0) return@post
                context.stopService(Intent(context, LocationKeepAlive::class.java))
                state = State.OFF
            }
        }

        private fun begin(context: Context) {
            val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                GearslipLog.w("location keep-alive: no location permission, car maps won't get a fix in the background")
                settle(State.FAILED)
                return
            }
            state = State.STARTING
            runCatching { context.startForegroundService(Intent(context, LocationKeepAlive::class.java)) }
                .onFailure {
                    GearslipLog.w("location keep-alive: start refused - ${it.message}")
                    settle(State.FAILED)
                }
        }

        private fun settle(to: State) {
            main.post {
                state = to
                val run = waiting.toList()
                waiting.clear()
                run.forEach { it() }
            }
        }
    }
}
