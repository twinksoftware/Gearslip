package app.seb3thehacker.gearslip.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.shizuku.ShellBridge
import app.seb3thehacker.gearslip.shizuku.ShizukuSetup
import kotlin.math.abs

/**
 * Music for the car recorded through Shizuku, as adb records it, instead of through
 * [AudioCaptureService]. The shell may record the phone's whole output: there's no consent
 * dialog, apps that refuse playback capture (Spotify) are heard anyway, and the phone goes quiet
 * by itself while it runs. Optional, and only with Shizuku running and allowed; anything that
 * goes wrong falls back to the usual capture.
 */
internal object ShellAudio {

    private val log = GearslipLog.tagged("audio")
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var reading = false
    @Volatile private var acquired = false

    /** The driver wants it and Shizuku can do it now. */
    fun available(context: Context): Boolean {
        if (!CarSettings.shizukuAudio.value) return false
        ShizukuSetup.init(context)
        ShizukuSetup.refresh()
        return ShizukuSetup.state.value == ShizukuSetup.State.READY
    }

    /** Starts recording; [onFailed] runs on the main thread if the shell can't. */
    fun start(context: Context, onFailed: () -> Unit) {
        if (reading) return
        reading = true
        acquired = true
        ShellBridge.acquire(context)
        val rate = CarAudio.sampleRate
        val channels = CarAudio.channels
        ShellBridge.call { shell ->
            val pipe = runCatching { shell.startAudio(rate, channels) }.getOrNull()
            if (pipe == null || !reading) {
                pipe?.close()
                if (reading) {
                    log.w("Shizuku couldn't record the phone's sound - asking for the usual capture instead")
                    stop()
                    main.post(onFailed)
                }
                return@call
            }
            pump(pipe, rate, channels)
        }
    }

    private fun pump(pipe: ParcelFileDescriptor, rate: Int, channels: Int) {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ByteArray(CHUNK_BYTES)
            var peak = 0
            var loggedAt = System.currentTimeMillis()
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pipe).use { input ->
                    log.i("recording the phone's sound through Shizuku at ${rate}Hz x$channels")
                    CarAudio.captureStarted()
                    while (reading) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        var i = 0
                        while (i + 1 < n) {
                            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                            peak = maxOf(peak, abs(sample.toShort().toInt()))
                            i += 2
                        }
                        CarAudio.deliver(buffer, n, peak / 32768f)
                        val now = System.currentTimeMillis()
                        if (now - loggedAt >= LEVEL_LOG_MS) {
                            log.i("peak %.3f".format(peak / 32768f))
                            loggedAt = now
                            peak = 0
                        }
                    }
                }
            } catch (t: Throwable) {
                log.e("the Shizuku recording ended", t)
            } finally {
                CarAudio.captureStopped()
                if (reading) {
                    log.w("the Shizuku recording stopped by itself")
                    stop()
                }
            }
        }, "gearslip-shell-audio").apply { isDaemon = true; start() }
    }

    fun stop() {
        reading = false
        if (!acquired) return
        acquired = false
        ShellBridge.call { runCatching { it.stopAudio() } }
        ShellBridge.release()
    }

    private const val CHUNK_BYTES = 4096
    private const val LEVEL_LOG_MS = 3_000L
}
