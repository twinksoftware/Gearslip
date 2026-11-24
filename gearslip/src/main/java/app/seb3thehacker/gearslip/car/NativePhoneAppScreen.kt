package app.seb3thehacker.gearslip.car

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.shizuku.ShellBridge

/** What [CarApps.find] answers to for a phone app opened through Shizuku. */
internal const val NATIVE_APP_PREFIX = "native:"

/**
 * A phone app on the car screen, drawn at the car's own size on a display the shell creates
 * (issue #124). Needs Shizuku. The app draws into this screen's SurfaceView; touches on it go to
 * the app, and so does the nav bar's Back, until the app has nothing left to go back to.
 * Leaving the screen closes the display, and the app with it.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun NativePhoneAppScreen(packageName: String) {
    val context = LocalContext.current
    val navigator = LocalCarNavigator.current
    // The car UI's own pixel density, so a phone app comes out the same size as Gearslip's own
    // screens. The frame's density alone read far too small in the phone's larger preview.
    val density = (androidx.compose.ui.platform.LocalDensity.current.density * 160).toInt()
    var displayId by remember { mutableStateOf(-1) }
    var failed by remember { mutableStateOf(false) }
    val log = remember { GearslipLog.tagged("phone-app") }
    val component = remember(packageName) {
        context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToString()
    }

    DisposableEffect(packageName) {
        ShellBridge.acquire(context)
        onDispose {
            val id = displayId
            if (id >= 0) ShellBridge.call { it.releaseDisplay(id) }
            ShellBridge.release()
        }
    }

    // Back walks the app's own screens; once it has gone back past its first one, the display
    // is empty and Back leaves for the launcher like anywhere else.
    DisposableEffect(displayId) {
        val id = displayId
        if (id >= 0) {
            navigator.backInterceptor = {
                ShellBridge.call { shell ->
                    sendKey(shell, KeyEvent.KEYCODE_BACK, id)
                    Thread.sleep(BACK_SETTLE_MS)
                    if (shell.topPackage(id) == null) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            navigator.backInterceptor = null
                            navigator.back()
                        }
                    }
                }
                true
            }
        }
        onDispose { navigator.backInterceptor = null }
    }

    Box(Modifier.fillMaxSize()) {
        if (component == null || failed) {
            Text(
                stringResource(R.string.native_app_failed),
                Modifier.align(Alignment.Center).padding(32.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            return@Box
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    var requested = false
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            if (requested) return
                            requested = true
                            val surface = holder.surface
                            ShellBridge.call { shell ->
                                val id = shell.createDisplay(surface, width, height, density)
                                if (id < 0) {
                                    log.e("the shell could not create a display for $packageName")
                                    post { failed = true }
                                    return@call
                                }
                                val code = shell.launch(component, id)
                                log.i("started $packageName on display $id (${width}x$height @ ${density}dpi): exit $code")
                                post { displayId = id }
                            }
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                    })
                    setOnTouchListener { _, event ->
                        val id = displayId
                        if (id >= 0) {
                            val copy = MotionEvent.obtain(event)
                            ShellBridge.now { it.injectMotion(copy, id) }
                        }
                        true
                    }
                }
            },
        )
    }
}

private fun sendKey(shell: app.seb3thehacker.gearslip.shizuku.IShellService, code: Int, displayId: Int) {
    val now = SystemClock.uptimeMillis()
    shell.injectKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), displayId)
    shell.injectKey(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0), displayId)
}

/** Long enough for an app to finish its last activity after Back, before checking what's left. */
private const val BACK_SETTLE_MS = 400L
