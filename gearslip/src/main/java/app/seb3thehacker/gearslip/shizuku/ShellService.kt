package app.seb3thehacker.gearslip.shizuku

import androidx.annotation.Keep
import kotlin.system.exitProcess

/**
 * Runs in a process Shizuku starts as the shell user, so a command here has the same powers it
 * would have typed over adb. Nothing in this class may touch the rest of Gearslip: it lives in a
 * different process, with a different user, and only sees what [run] is handed.
 */
@Keep
class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun run(command: String): Int {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        return process.waitFor()
    }
}
