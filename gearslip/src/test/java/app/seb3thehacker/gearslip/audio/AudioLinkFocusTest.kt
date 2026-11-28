package app.seb3thehacker.gearslip.audio

import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.ServiceDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * A head unit answered LOSS within 2 ms whenever the music asked for its speakers before it showed
 * Gearslip's screen, and GAIN every time the ask came after. Gearslip took the LOSS as final, so a
 * cable plugged back in quickly, with the capture still running, played nothing until the driver
 * flipped the audio setting. The music should hold its ask until the screen is up.
 */
class AudioLinkFocusTest {

    private val sink = ServiceDiscovery.AudioService(
        serviceId = 4,
        streamType = 3,
        codecType = ServiceDiscovery.CODEC_PCM,
        configs = listOf(ServiceDiscovery.AudioConfig(48_000, 16, 2)),
    )

    /** A media link whose car has set the sink up, and the control and channel messages it sent. */
    private fun readyLink(screenUp: () -> Boolean): Triple<AudioLink, MutableList<Int>, MutableList<Int>> {
        val control = Collections.synchronizedList(mutableListOf<Int>())
        val channel = Collections.synchronizedList(mutableListOf<Int>())
        val link = AudioLink(
            sink,
            sendOnChannel = { id, _ -> channel += id },
            sendControl = { id, _ -> control += id },
            screenUp = screenUp,
            onReady = {},
            onClose = {},
        )
        link.onMessage(
            MSG_CONFIG,
            Protobuf.varintField(1, 2) + Protobuf.varintField(2, 4) + Protobuf.varintField(3, 0),
        )
        return Triple(link, control, channel)
    }

    @Test
    fun beforeTheScreenIsUpTheMusicDoesNotAskForTheSpeakers() {
        val (link, control, channel) = readyLink(screenUp = { false })
        link.start()
        assertTrue("no focus request yet: $control", AudioLink.MSG_FOCUS_REQUEST !in control)
        assertTrue("no Start yet: $channel", MSG_START !in channel)
        assertEquals(false, link.streaming)
    }

    @Test
    fun onceTheScreenIsUpTheHeldAskGoesOut() {
        var up = false
        val (link, control, channel) = readyLink(screenUp = { up })
        link.start()
        up = true
        link.mayAskForSpeakers()
        assertEquals(listOf(AudioLink.MSG_FOCUS_REQUEST), control)
        assertTrue("Start sent: $channel", MSG_START in channel)
        assertTrue(link.streaming)
        link.stop()
    }

    @Test
    fun aCarKeepingItsScreenStillGetsAskedWhenTheWaitIsOver() {
        // A car that keeps its own screen never shows Gearslip's; the runner gives up waiting
        // and lets the music ask anyway, as it always did.
        val (link, control, _) = readyLink(screenUp = { false })
        link.start()
        link.mayAskForSpeakers(screenOrNot = true)
        assertEquals(listOf(AudioLink.MSG_FOCUS_REQUEST), control)
        link.stop()
    }

    @Test
    fun withTheScreenAlreadyUpTheMusicAsksAtOnce() {
        val (link, control, _) = readyLink(screenUp = { true })
        link.start()
        assertEquals(listOf(AudioLink.MSG_FOCUS_REQUEST), control)
        link.stop()
    }

    @Test
    fun nothingHeldMeansNothingToSend() {
        val (link, control, _) = readyLink(screenUp = { true })
        link.mayAskForSpeakers()
        assertTrue("never started, so nothing asked: $control", control.isEmpty())
    }

    private companion object {
        const val MSG_START = 32769
        const val MSG_CONFIG = 32771
    }
}
