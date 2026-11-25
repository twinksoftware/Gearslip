package app.seb3thehacker.gearslip.car

import app.seb3thehacker.gearslip.OwnServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The driver's own chat model (Ollama, llama.cpp's server, LocalAI, or anything with OpenAI's chat
 * call), asked only about what [AssistantCommands] doesn't already understand. It either answers
 * in a sentence or two, or rewrites what was said as one of Gearslip's own commands, which then
 * runs like any other. Only this one exchange is sent: no history, nothing about the drive.
 */
object ServerAssistant {

    /** What came back: words to say, or a command to run. */
    sealed interface Answer {
        data class Say(val text: String) : Answer
        data class Run(val command: String) : Answer
    }

    private const val DEFAULT_MODEL = "llama3.2"
    private const val COMMAND_PREFIX = "DO:"

    private val PROMPT = """
        You are the voice assistant in a car's dashboard, heard over the car's speakers while the
        driver is driving. Keep every answer to one or two short spoken sentences, with no lists,
        markdown or emoji.

        If the driver wants something the car can do, reply with only one line, "$COMMAND_PREFIX "
        followed by the command, in one of these forms:
        navigate to <place>
        play <song, artist, album or playlist>
        pause / resume / next / previous
        call <contact name>
        open <app name>
        go home
        what time is it
        what's the weather

        Otherwise, answer the question yourself.
    """.trimIndent()

    val enabled: Boolean get() = CarSettings.aiServer.value.on

    suspend fun ask(heard: String): Answer = withContext(Dispatchers.IO) {
        val server = CarSettings.aiServer.value
        val url = OwnServer.endpoint(server.url, "/v1/chat/completions", listOf("/chat/completions"))
        val request = JSONObject()
            .put("model", server.model.ifBlank { DEFAULT_MODEL })
            .put("stream", false)
            .put("temperature", 0.2)
            .put("max_tokens", 120)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", PROMPT))
                    .put(JSONObject().put("role", "user").put("content", heard)),
            )
        val reply = OwnServer.post(server, url, "application/json", request.toString().toByteArray())
        val text = runCatching {
            JSONObject(reply).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }.getOrElse { throw OwnServer.Failed("Your AI server sent back something Gearslip can't read.") }
        parse(text)
    }

    /** A "DO:" line anywhere in the reply is a command; anything else is said as it is. */
    internal fun parse(text: String): Answer {
        // Reasoning models put their thinking in <think> tags first.
        val clean = text.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        clean.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(COMMAND_PREFIX, ignoreCase = true) }?.let {
            return Answer.Run(it.substring(COMMAND_PREFIX.length).trim().trimEnd('.'))
        }
        return Answer.Say(clean.replace(Regex("[*_#`]"), "").ifBlank { "I don't know how to do that yet." })
    }
}
