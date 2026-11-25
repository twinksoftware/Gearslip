package app.seb3thehacker.gearslip.speech

import app.seb3thehacker.gearslip.OwnServer
import app.seb3thehacker.gearslip.car.CarSettings
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/**
 * Speech-to-text on the driver's own server: the phrase goes out as a WAV file to OpenAI's
 * transcription call, which whisper.cpp's server, faster-whisper-server, Speaches and LocalAI all
 * answer, and the words come back. The recording is held in memory only for the request.
 */
internal object ServerSpeech {

    typealias Failed = OwnServer.Failed

    const val UNREACHABLE = OwnServer.UNREACHABLE

    /** The model name most of these servers accept when the driver leaves it blank. */
    private const val DEFAULT_MODEL = "whisper-1"

    fun transcribe(server: CarSettings.Server, wav: ByteArray): String {
        val url = OwnServer.endpoint(server.url, "/v1/audio/transcriptions", listOf("/transcriptions", "/inference"))
        val boundary = "gearslip" + System.nanoTime()
        val body = ByteArrayOutputStream().apply {
            fun field(name: String, value: String) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("model", server.model.ifBlank { DEFAULT_MODEL })
            field("response_format", "json")
            // Commands are matched in English, so a guessed language would only lose words.
            field("language", "en")
            write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"phrase.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n").toByteArray(),
            )
            write(wav)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val reply = OwnServer.post(server, url, "multipart/form-data; boundary=$boundary", body)
        return runCatching { JSONObject(reply).optString("text") }
            .getOrElse { reply.takeIf { !it.trimStart().startsWith("{") } ?: "" }
            .trim()
    }

    /** 16-bit mono little-endian [pcm] with a WAV header. */
    fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return header + pcm
    }
}
