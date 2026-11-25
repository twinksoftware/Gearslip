package app.seb3thehacker.gearslip

import app.seb3thehacker.gearslip.car.CarSettings
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to a server the driver runs themselves, for speech and the assistant, so the phone needn't
 * hold a model in memory while it also streams video, plays music and runs a map. Both use the
 * OpenAI-style calls that whisper.cpp, faster-whisper, LocalAI, llama.cpp and Ollama all answer.
 *
 * Only the driver's own address is ever called, and nothing said is written to the log.
 */
object OwnServer {

    /** Something went wrong in a way worth saying on the car screen. */
    class Failed(message: String) : IOException(message)

    const val UNREACHABLE = "Couldn't reach your server. Check its address in Gearslip's settings."

    private const val CONNECT_MS = 5_000
    private const val READ_MS = 30_000

    /**
     * Where to send a call: the address as typed when it already names a path for it (whisper.cpp's
     * server answers on /inference, say), otherwise [path] under it. "http://" is added when the
     * driver left it off.
     */
    fun endpoint(address: String, path: String, alreadyThere: List<String>): URL {
        var base = address.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "http://$base"
        if (alreadyThere.any { base.endsWith(it) }) return URL(base)
        // "http://host:8080/v1" and "http://host:8080" both mean the same server.
        val tail = if (base.endsWith("/v1")) path.removePrefix("/v1") else path
        return URL(base + tail)
    }

    /** POSTs [body] and returns the reply's text; [Failed] for a refusal or no answer. */
    fun post(server: CarSettings.Server, url: URL, contentType: String, body: ByteArray): String {
        val connection = try {
            url.openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw Failed(UNREACHABLE)
        }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_MS
            connection.readTimeout = READ_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            if (server.key.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${server.key}")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code == 401 || code == 403) throw Failed("Your server refused the key. Check it in Gearslip's settings.")
            if (code == 404) throw Failed("Your server doesn't answer at that address. Check it in Gearslip's settings.")
            if (code !in 200..299) {
                GearslipLog.w("own server: $url answered HTTP $code")
                throw Failed("Your server had a problem (HTTP $code).")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Failed) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw Failed("Your server took too long to answer.")
        } catch (e: IOException) {
            GearslipLog.w("own server: $url failed: ${e.javaClass.simpleName}")
            throw Failed(UNREACHABLE)
        } finally {
            connection.disconnect()
        }
    }
}
