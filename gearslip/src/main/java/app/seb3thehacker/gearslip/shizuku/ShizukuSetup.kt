package app.seb3thehacker.gearslip.shizuku

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

/**
 * Shizuku does Gearslip's one-time adb steps from the phone (issue #125). Both stick once done:
 *
 * - the PROJECT_MEDIA app-op, so Android stops asking to share the screen every drive
 * - WRITE_SECURE_SETTINGS, so notifications stay readable while music is captured
 *
 * Everything works without Shizuku; this only saves the trip to a computer. Once both are in
 * place Shizuku isn't needed again, but each car connection checks, and quietly puts back
 * whatever went missing if Shizuku happens to be running.
 */
object ShizukuSetup {

    enum class State {
        /** Shizuku isn't installed. Nothing about it is shown. */
        MISSING,
        /** Installed, but its service isn't running. */
        STOPPED,
        /** Running, and Gearslip hasn't been allowed to use it yet. */
        NEEDS_PERMISSION,
        /** Running and allowed. */
        READY,
    }

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 125

    private val main = Handler(Looper.getMainLooper())
    private val log = GearslipLog.tagged("shizuku")

    private val _state = MutableStateFlow(State.MISSING)
    val state: StateFlow<State> = _state

    private val _granted = MutableStateFlow(false)
    /** True once both one-time steps are in place, by Shizuku or by adb. */
    val granted: StateFlow<Boolean> = _granted

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working

    private var appContext: Context? = null
    /** Waiting on the driver's answer to Shizuku's permission dialog. */
    private var afterPermission: (() -> Unit)? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { refresh() }
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code != REQUEST_CODE) return@addRequestPermissionResultListener
            refresh()
            val next = afterPermission
            afterPermission = null
            if (result == PackageManager.PERMISSION_GRANTED) next?.invoke()
            else log.i("the driver didn't allow Gearslip to use Shizuku")
        }
        refresh()
    }

    fun refresh() {
        val context = appContext ?: return
        _granted.value = hasProjectMedia(context) && hasSecureSettings(context)
        _state.value = when {
            !installed(context) -> State.MISSING
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> State.STOPPED
            runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) !=
                PackageManager.PERMISSION_GRANTED -> State.NEEDS_PERMISSION
            else -> State.READY
        }
    }

    /** The button: asks Shizuku for permission if needed, then does both steps. */
    fun setUp() {
        refresh()
        when (_state.value) {
            State.READY -> grant()
            State.NEEDS_PERMISSION -> {
                afterPermission = ::grant
                runCatching { Shizuku.requestPermission(REQUEST_CODE) }
                    .onFailure { log.e("could not ask Shizuku for permission", it) }
            }
            else -> Unit
        }
    }

    /** A car connected: put back anything missing, without asking, if Shizuku is up and allowed. */
    fun recheckOnConnect(context: Context) = main.post {
        init(context)
        refresh()
        if (!_granted.value && _state.value == State.READY) {
            log.i("a one-time setup step is missing, and Shizuku is running, so Gearslip is putting it back")
            grant()
        }
    }

    private fun grant() {
        val context = appContext ?: return
        if (_working.value) return
        _working.value = true
        val pkg = context.packageName
        val commands = buildList {
            if (!hasProjectMedia(context)) {
                add("appops set $pkg PROJECT_MEDIA allow")
                add("appops write-settings")
            }
            if (!hasSecureSettings(context)) add("pm grant $pkg ${Manifest.permission.WRITE_SECURE_SETTINGS}")
        }
        withShell(
            onReady = { shell ->
                for (command in commands) {
                    val code = runCatching { shell.run(command) }.getOrDefault(-1)
                    log.i("ran \"$command\" through Shizuku: exit $code")
                }
            },
            onDone = {
                _working.value = false
                refresh()
                log.i(if (_granted.value) "one-time setup done through Shizuku" else "Shizuku ran, but the setup still isn't in place")
            },
        )
    }

    /** Starts [ShellService] in Shizuku's shell process, runs [onReady] off the main thread, then stops it. */
    private fun withShell(onReady: (IShellService) -> Unit, onDone: () -> Unit) {
        val context = appContext ?: return onDone()
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShellService::class.java))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val conn = this
                val shell = binder?.let { IShellService.Stub.asInterface(it) }
                Thread {
                    if (shell != null) onReady(shell) else log.w("Shizuku started the shell service without a binder")
                    main.post {
                        runCatching { Shizuku.unbindUserService(args, conn, true) }
                        onDone()
                    }
                }.start()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure {
            log.e("could not start the shell service through Shizuku", it)
            onDone()
        }
    }

    private fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess

    /** Checked the way the platform checks it; there is no public permission for it. */
    fun hasProjectMedia(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching {
            ops.unsafeCheckOpNoThrow("android:project_media", android.os.Process.myUid(), context.packageName)
        }.getOrDefault(AppOpsManager.MODE_IGNORED) == AppOpsManager.MODE_ALLOWED
    }

    private fun hasSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
}
