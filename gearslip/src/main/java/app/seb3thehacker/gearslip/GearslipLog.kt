package app.seb3thehacker.gearslip

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Logs to logcat and to a file at once.
 *
 * The file copy is the safety net: entering USB accessory mode tears down adb-over-USB, so
 * if wireless adb drops in the car the file is the only surviving record of the run. It
 * lives under getExternalFilesDir, which `adb pull` can reach without root.
 */
object GearslipLog {

    const val TAG = "Gearslip"

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val io = Executors.newSingleThreadExecutor()

    private const val MAX_LIVE_LINES = 3000

    private var file: File? = null

    /**
     * Off drops the noisy trace output (hex dumps, and logcat copies of info lines); warnings,
     * errors and the file copy are always kept, so a crash report is never empty. Always on for
     * now, while the project is pre-release.
     */
    @Volatile var debug = true

    // What the live log screen shows. Bounded, and separate from the file, which always keeps
    // everything. The flow carries only a version number so a burst of lines costs the UI one
    // snapshot per frame rather than one list copy per line.
    private val live = ArrayDeque<String>()
    private val liveVersion = MutableStateFlow(0)

    val version: StateFlow<Int> = liveVersion

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: return
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        file = File(dir, "gearslip-$name.log")
        i("log file: ${file?.absolutePath}")
        SessionReport.init(context)
        // One file per launch adds up; the newest are the ones anyone asks for.
        io.execute { logFiles(dir).drop(KEEP_FILES).forEach { it.delete() } }
        lastExit(context, dir)
    }

    private const val KEEP_FILES = 30
    private const val KEY_EXIT_SEEN = "log_exit_seen"

    /**
     * Why Android ended the last run, when the crash handler can't have seen it: a native crash,
     * a freeze (ANR), or the memory killer. Written here and onto the end of that run's own log,
     * which is the one a driver shares after a drive that went wrong.
     */
    private fun lastExit(context: Context, dir: File) {
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return
        val seen = AppSettings.getString(context, KEY_EXIT_SEEN, "0").toLongOrNull() ?: 0L
        val exits = runCatching { am.getHistoricalProcessExitReasons(null, 0, 5) }.getOrDefault(emptyList())
            .filter { it.timestamp > seen }
        exits.firstOrNull()?.let { AppSettings.putString(context, KEY_EXIT_SEEN, it.timestamp.toString()) }
        val parse = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        for (exit in exits.reversed()) {
            val why = EXIT_REASONS[exit.reason] ?: continue
            val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(exit.timestamp))
            val detail = buildString {
                append("$at, $why")
                exit.description?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
                append(", memory ${exit.pss / 1024} MB")
                if (exit.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                    anrMainThread(exit)?.let { append("\n").append(it) }
                }
            }
            w("Android ended an earlier run at $detail")
            // The run that died: the newest log that started before it ended.
            val run = logFiles(dir).filter { it != file }.firstOrNull { f ->
                val started = runCatching { parse.parse(f.name.removePrefix("gearslip-").removeSuffix(".log")) }.getOrNull()
                started != null && started.time <= exit.timestamp
            } ?: continue
            io.execute { runCatching { run.appendText("--- $EXIT_MARK $detail\n") } }
        }
    }

    /** The main thread's stack from an ANR trace: what Gearslip was stuck doing. */
    private fun anrMainThread(exit: android.app.ApplicationExitInfo): String? = runCatching {
        exit.traceInputStream?.bufferedReader()?.useLines { lines ->
            lines.dropWhile { !it.startsWith("\"main\"") }.take(ANR_LINES).joinToString("\n")
        }?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private const val ANR_LINES = 25
    private const val EXIT_MARK = "Android ended this run:"

    /** Exit reasons worth a line; leaving, updating and Java crashes (already logged) aren't. */
    private val EXIT_REASONS = mapOf(
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE to "a native crash",
        android.app.ApplicationExitInfo.REASON_ANR to "the app stopped responding (ANR)",
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY to "the phone ran low on memory",
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "it used too much of the phone's resources",
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to "it failed to start",
        android.app.ApplicationExitInfo.REASON_SIGNALED to "the system killed it",
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED to "something it depends on died",
    )

    private fun logFiles(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith("gearslip-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.name }.orEmpty()

    /** A log from an earlier launch: when it started, and what a glance at it tells. */
    class PastLog(val file: File, val started: Date?, val crashed: Boolean, val car: String?)

    /**
     * Earlier launches' logs, newest first. Each launch starts a fresh file, so after a crash the
     * one that saw it is here rather than on the live screen.
     */
    fun pastLogs(context: Context): List<PastLog> {
        val dir = context.getExternalFilesDir(null) ?: return emptyList()
        val parse = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return logFiles(dir).filter { it != file }.map { f ->
            val text = runCatching { f.readText() }.getOrDefault("")
            PastLog(
                file = f,
                started = runCatching { parse.parse(f.name.removePrefix("gearslip-").removeSuffix(".log")) }.getOrNull(),
                crashed = "FATAL uncaught" in text || EXIT_MARK in text,
                car = Regex("""car="([^"]+)"""").findAll(text).lastOrNull()?.groupValues?.get(1),
            )
        }
    }

    /** An earlier launch's log, copied to the cache dir for sharing like [shareableFile]. */
    fun shareableFile(context: Context, past: File): android.net.Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "gearslip-log-${past.name.removePrefix("gearslip-").removeSuffix(".log")}.txt")
        past.copyTo(out, overwrite = true)
        return androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.logshare", out,
        )
    }

    fun snapshot(): List<String> = synchronized(live) { live.toList() }

    /** Clears the live view only. The log file on disk is left alone. */
    fun clearLive() {
        synchronized(live) { live.clear() }
        liveVersion.update { it + 1 }
    }

    /** The whole current session's file, for sharing. Falls back to the live buffer. */
    fun fullText(): String {
        flush()
        val f = file
        return if (f != null && f.isFile) f.readText() else snapshot().joinToString("\n")
    }

    /**
     * The current session's log, with the session report on top, copied to the cache dir for
     * sharing as an attachment (a whole log can exceed what an Intent may carry as text).
     */
    fun shareableFile(context: Context): android.net.Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "gearslip-log.txt")
        out.writeText(SessionReport.render() + "\n\n" + fullText())
        return androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.logshare", out,
        )
    }

    fun i(message: String, tag: String = "APP") = write("I", message, tag)

    fun w(message: String, tag: String = "APP") = write("W", message, tag)

    /** Logs the message, then the exception with its cause chain and top stack frames. */
    fun e(message: String, t: Throwable? = null, tag: String = "APP") {
        write("E", if (t == null) message else "$message: ${rootCause(t)}", tag)
        if (t != null) {
            Log.e(TAG, message, t)
            stackTrace(t).lines().forEach { write("E", "    $it", tag) }
        }
    }

    /** A logger that stamps every line with [tag], so the log can be scanned by subsystem. */
    fun tagged(tag: String) = Tagged(tag)

    class Tagged(private val tag: String) {
        fun i(message: String) = GearslipLog.i(message, tag)
        fun w(message: String) = GearslipLog.w(message, tag)
        fun e(message: String, t: Throwable? = null) = GearslipLog.e(message, t, tag)
        fun hex(label: String, data: ByteArray, limit: Int = 256) = GearslipLog.hex(label, data, limit, tag)
        fun verdict(headline: String, detail: String) = GearslipLog.verdict(headline, detail)
        fun flush() = GearslipLog.flush()
    }

    /** "SSLHandshakeException: Received fatal alert: bad_certificate" - the deepest cause wins. */
    fun rootCause(t: Throwable): String {
        var cause: Throwable = t
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        val top = "${t.javaClass.simpleName}: ${t.message}"
        return if (cause === t) top else "$top <- ${cause.javaClass.simpleName}: ${cause.message}"
    }

    private fun stackTrace(t: Throwable, frames: Int = 12): String {
        val out = StringBuilder()
        var cause: Throwable? = t
        var depth = 0
        while (cause != null && depth < 4) {
            if (depth > 0) out.append("caused by ${cause.javaClass.name}: ${cause.message}\n")
            cause.stackTrace.take(frames).forEach { out.append("at $it\n") }
            cause = cause.cause?.takeIf { it !== cause }
            depth++
        }
        return out.toString().trimEnd()
    }

    /**
     * Records an uncaught exception in the log file before the process dies, then lets the
     * system's own handler run. Without it the file ends mid-sentence and the reason is lost.
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                SessionReport.fail(SessionReport.Category.CRASH, "uncaught in thread ${thread.name}", throwable)
                e("FATAL uncaught exception in thread ${thread.name}", throwable)
                SessionReport.print()
                flush()
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Unmissable in a scrolling logcat - this is what the whole spike exists to print. */
    fun verdict(headline: String, detail: String) {
        val bar = "=".repeat(60)
        i(bar)
        i("VERDICT: $headline")
        i(detail)
        i(bar)
        // The verdict is the one line the run exists to produce, and the app may be killed
        // the moment the accessory detaches. Do not leave it sitting in the write queue.
        flush()
    }

    /** Blocks until everything queued has actually reached the file. */
    fun flush(timeoutMs: Long = 2_000) {
        val latch = CountDownLatch(1)
        runCatching { io.execute { latch.countDown() } }.onFailure { return }
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    fun hex(label: String, data: ByteArray, limit: Int = 256, tag: String = "APP") {
        if (!debug) return
        val shown = minOf(data.size, limit)
        val sb = StringBuilder()
        for (idx in 0 until shown) {
            sb.append(String.format("%02X", data[idx]))
            if (idx % 16 == 15) sb.append('\n') else sb.append(' ')
        }
        val suffix = if (data.size > limit) "\n... (${data.size - limit} more bytes)" else ""
        i("$label (${data.size} bytes)\n$sb$suffix", tag)
    }

    private fun write(level: String, message: String, tag: String = "APP") {
        when (level) {
            "W" -> Log.w(TAG, message)
            "E" -> Log.e(TAG, message)
            else -> if (debug) Log.i(TAG, message)
        }
        val line = "${stamp.format(Date())} $level ${tag.padEnd(5)} $message"
        synchronized(live) {
            live.addLast(line)
            while (live.size > MAX_LIVE_LINES) live.removeFirst()
        }
        liveVersion.update { it + 1 }
        val target = file ?: return
        io.execute {
            runCatching { target.appendText(line + "\n") }
        }
    }
}
