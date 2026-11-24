package app.seb3thehacker.gearslip.shizuku

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import kotlin.system.exitProcess

/**
 * Runs in a process Shizuku starts as the shell user, so a command here has the same powers it
 * would have typed over adb. Nothing in this class may touch the rest of Gearslip: it lives in a
 * different process, with a different user, and only sees what it's handed.
 *
 * Besides commands, it puts phone apps on the car screen (issue #124). An ordinary app may only
 * show its own activities on a display it creates; the shell may create a trusted display that
 * any app can be started on, the way scrcpy's new-display mode works. The app then draws at the
 * car's own size instead of being a copy of the phone's screen.
 */
@Keep
class ShellService() : IShellService.Stub() {

    private var context: Context? = null

    /** Shizuku 13 passes a context when the class has this constructor. */
    @Keep
    constructor(context: Context) : this() {
        this.context = context
    }

    private val displays = mutableMapOf<Int, VirtualDisplay>()

    override fun destroy() {
        displays.values.forEach { runCatching { it.release() } }
        exitProcess(0)
    }

    override fun run(command: String): Int = exec(command).first

    @SuppressLint("WrongConstant")
    override fun createDisplay(surface: Surface, width: Int, height: Int, densityDpi: Int): Int {
        val base = context ?: return -1
        return runCatching {
            // The display service checks the caller's package against its uid, which is the shell's.
            val manager = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(ShellContext(base))
            val display = manager.createVirtualDisplay("gearslip-phone-app", width, height, densityDpi, surface, DISPLAY_FLAGS)
            synchronized(displays) { displays[display.display.displayId] = display }
            display.display.displayId
        }.getOrElse {
            System.err.println("gearslip shell: createDisplay failed: $it")
            -1
        }
    }

    override fun launch(component: String, displayId: Int): Int =
        exec("am start --display $displayId -f 0x10000000 -n ${quote(component)}").first

    override fun topPackage(displayId: Int): String? {
        // Each display's block starts "Display #N"; its resumed activity names the package.
        var inDisplay = false
        for (line in exec("dumpsys activity activities").second.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Display #")) {
                inDisplay = trimmed.startsWith("Display #$displayId ") || trimmed == "Display #$displayId"
                continue
            }
            if (!inDisplay) continue
            if (trimmed.startsWith("mResumedActivity") || trimmed.startsWith("topResumedActivity") || trimmed.startsWith("ResumedActivity")) {
                return Regex("""\s([\w.]+)/""").find(trimmed)?.groupValues?.get(1)
            }
        }
        return null
    }

    override fun injectMotion(event: MotionEvent, displayId: Int) {
        inject(event, displayId)
        event.recycle()
    }

    override fun injectKey(event: KeyEvent, displayId: Int) {
        inject(event, displayId)
    }

    override fun releaseDisplay(displayId: Int) {
        synchronized(displays) { displays.remove(displayId) }?.let { runCatching { it.release() } }
    }

    private fun inject(event: InputEvent, displayId: Int) {
        runCatching {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
            val input = inputManager
            input.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                .invoke(input, event, INJECT_ASYNC)
        }.onFailure { System.err.println("gearslip shell: inject failed: $it") }
    }

    /** InputManagerGlobal from Android 14, InputManager before it. */
    private val inputManager: Any by lazy {
        runCatching { Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)!! }
            .getOrElse { Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!! }
    }

    private fun exec(command: String): Pair<Int, String> {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        return process.waitFor() to out
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Answers to the shell's package, which is what the shell user's uid belongs to. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL
        override fun getOpPackageName() = SHELL
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(SHELL_UID).setPackageName(SHELL).build()
        override fun getApplicationContext(): Context = this
    }

    private companion object {
        const val SHELL = "com.android.shell"
        const val SHELL_UID = 2000
        const val INJECT_ASYNC = 0

        // DisplayManager's flags, several of them hidden: public, own content only, supports
        // touch, rotates with content, destroys its apps when released, trusted, its own display
        // group, always unlocked, and no touch feedback.
        const val DISPLAY_FLAGS = (1 shl 0) or (1 shl 3) or (1 shl 6) or (1 shl 7) or (1 shl 8) or
            (1 shl 10) or (1 shl 11) or (1 shl 12) or (1 shl 13)
    }
}
