package app.seb3thehacker.gearslip.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.seb3thehacker.gearslip.GearslipLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Each site's icon for the start page's tiles, kept on disk by host. The browser saves the icon
 * a page hands it while loading ([save]); a bookmark that hasn't been opened yet asks its own
 * site once ([fetch]), for its touch icon and then its favicon. Nothing goes to a third-party
 * icon service, so no list of the driver's bookmarks leaves the phone.
 */
object SiteIcons {

    private const val DIRECTORY = "site-icons"
    /** Smaller than this is a 16px favicon that would look blurry on a tile; a bigger one wins. */
    private const val GOOD_ENOUGH_PX = 96

    private val memory = ConcurrentHashMap<String, Bitmap>()
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val changed = MutableStateFlow(0)

    /** Bumps whenever an icon is saved, so tiles look again. */
    val version: StateFlow<Int> = changed

    fun icon(context: Context, host: String): Bitmap? {
        if (host.isEmpty()) return null
        memory[host]?.let { return it }
        val file = file(context, host)
        if (!file.isFile) return null
        return runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()?.also { memory[host] = it }
    }

    /** An icon the page gave the browser. Kept when it's bigger than the one already saved. */
    fun save(context: Context, host: String, bitmap: Bitmap) {
        if (host.isEmpty()) return
        val current = icon(context, host)
        if (current != null && current.width >= bitmap.width) return
        store(context, host, bitmap)
    }

    /** Asks [host] for its icon in the background, once per run, when none is saved or it's small. */
    fun fetch(context: Context, host: String) {
        if (host.isEmpty() || !asked.add(host)) return
        val current = icon(context, host)
        if (current != null && current.width >= GOOD_ENOUGH_PX) return
        val app = context.applicationContext
        Thread({
            for (path in listOf("/apple-touch-icon.png", "/favicon.ico")) {
                val bitmap = runCatching { download("https://$host$path") }.getOrNull() ?: continue
                if (current == null || bitmap.width > current.width) store(app, host, bitmap)
                return@Thread
            }
        }, "site-icon").apply { isDaemon = true }.start()
    }

    private fun store(context: Context, host: String, bitmap: Bitmap) {
        memory[host] = bitmap
        runCatching {
            val file = file(context, host)
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { GearslipLog.w("browser: couldn't save the icon for $host: ${it.message}") }
        changed.value++
    }

    private fun download(url: String): Bitmap? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            connection.disconnect()
        }
    }

    private fun file(context: Context, host: String) =
        File(File(context.filesDir, DIRECTORY), host.replace(Regex("[^A-Za-z0-9.-]"), "_") + ".png")
}
