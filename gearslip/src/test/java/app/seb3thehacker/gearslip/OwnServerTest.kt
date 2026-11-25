package app.seb3thehacker.gearslip

import app.seb3thehacker.gearslip.car.ServerAssistant
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnServerTest {
    private val speech = listOf("/transcriptions", "/inference")

    @Test fun `a bare address gets the call's path and http`() {
        assertEquals(
            "http://192.168.1.20:8080/v1/audio/transcriptions",
            OwnServer.endpoint("192.168.1.20:8080/", "/v1/audio/transcriptions", speech).toString(),
        )
    }

    @Test fun `an address ending in v1 doesn't get a second one`() {
        assertEquals(
            "https://ai.example.org/v1/chat/completions",
            OwnServer.endpoint("https://ai.example.org/v1", "/v1/chat/completions", listOf("/chat/completions")).toString(),
        )
    }

    @Test fun `a full path the driver typed is kept`() {
        assertEquals(
            "http://pc:8080/inference",
            OwnServer.endpoint("http://pc:8080/inference", "/v1/audio/transcriptions", speech).toString(),
        )
    }

    @Test fun `a DO line is a command and anything else is said`() {
        assertEquals(ServerAssistant.Answer.Run("navigate to the beach"), ServerAssistant.parse("DO: navigate to the beach."))
        assertEquals(
            ServerAssistant.Answer.Run("play jazz"),
            ServerAssistant.parse("<think>they want music</think>\nSure.\ndo: play jazz"),
        )
        assertEquals(ServerAssistant.Answer.Say("Paris is the capital."), ServerAssistant.parse("**Paris** is the capital."))
    }
}
