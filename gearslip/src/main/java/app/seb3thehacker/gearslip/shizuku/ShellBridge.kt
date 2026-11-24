package app.seb3thehacker.gearslip.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.GearslipLog
import java.util.concurrent.Executors
import rikka.shizuku.Shizuku

/**
 * Keeps [ShellService] running while a phone app is on the car screen, and runs calls to it off
 * the main thread, one at a time, in the order they were made. Starting the service takes a
 * moment, so calls made before it's up wait for it.
 */
object ShellBridge {

    private val log = GearslipLog.tagged("shizuku")
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "gearslip-shell").apply { isDaemon = true } }

    @Volatile private var shell: IShellService? = null
    private var users = 0
    private var args: Shizuku.UserServiceArgs? = null
    private val waiting = mutableListOf<(IShellService) -> Unit>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = binder?.let { IShellService.Stub.asInterface(it) } ?: return
            shell = service
            val queued = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
            queued.forEach { call -> worker.execute { runCatching { call(service) }.onFailure { log.e("shell call failed", it) } } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
        }
    }

    /** A screen that needs the shell came up. Pair with [release]. */
    fun acquire(context: Context) = main.post {
        users++
        if (users > 1) return@post
        val a = Shizuku.UserServiceArgs(ComponentName(context.applicationContext, ShellService::class.java))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        args = a
        runCatching { Shizuku.bindUserService(a, connection) }.onFailure { log.e("could not start the shell service", it) }
    }

    fun release() = main.post {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return@post
        val a = args ?: return@post
        args = null
        shell = null
        synchronized(waiting) { waiting.clear() }
        runCatching { Shizuku.unbindUserService(a, connection, true) }
    }

    /** Runs [call] on the shell's thread, now or once the service is up. */
    fun call(call: (IShellService) -> Unit) {
        val service = shell
        if (service != null) {
            worker.execute { runCatching { call(service) }.onFailure { log.e("shell call failed", it) } }
        } else {
            synchronized(waiting) { waiting += call }
        }
    }

    /** For touches: dropped rather than queued when the service isn't up, since a late touch is worse than none. */
    fun now(call: (IShellService) -> Unit) {
        val service = shell ?: return
        runCatching { call(service) }
    }
}
