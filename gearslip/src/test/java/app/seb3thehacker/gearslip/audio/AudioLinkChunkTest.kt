package app.seb3thehacker.gearslip.audio

import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.ServiceDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * A 2020 Civic acks a music chunk about every 83 ms, whatever the chunk holds (issue #43).
 * Capture reads of 4096 bytes hold 21 ms at 48 kHz stereo, so the music reached the car at a
 * quarter of real time. [AudioLink] should notice the car's pace and send chunks that last as
 * long as its gap between acks; a car that keeps up should get the capture as it comes.
 */
class AudioLinkChunkTest {

    private val sink = ServiceDiscovery.AudioService(
        serviceId = 4,
        streamType = 3,
        codecType = ServiceDiscovery.CODEC_PCM,
        configs = listOf(ServiceDiscovery.AudioConfig(48_000, 16, 2)),
    )

    /** A started link to a car that takes [window] chunks before it acks, and what it was sent. */
    private fun startedLink(window: Int): Pair<AudioLink, MutableList<Int>> {
        val sent = Collections.synchronizedList(mutableListOf<Int>())
        val link = AudioLink(
            sink,
            sendOnChannel = { id, body -> if (id == MSG_DATA) sent += body.size - TIMESTAMP_BYTES },
            sendControl = { _, _ -> },
            asksFocus = false,
            onReady = {},
            onClose = {},
        )
        // Config { status = READY; max_unacked = window; configuration_indices = 0 }
        link.onMessage(
            MSG_CONFIG,
            Protobuf.varintField(1, 2) + Protobuf.varintField(2, window.toLong()) + Protobuf.varintField(3, 0),
        )
        link.start()
        return link to sent
    }

    private fun ack(link: AudioLink) =
        link.onMessage(MSG_ACK, Protobuf.varintField(1, 2) + Protobuf.varintField(2, 1))

    @Test
    fun aCarThatAcksSlowlyGetsChunksAsLongAsItsGap() {
        val (link, sent) = startedLink(window = 16)
        val capture = Thread {
            val read = ByteArray(CAPTURE_BYTES)
            while (!Thread.currentThread().isInterrupted) link.write(read, read.size)
        }.apply { isDaemon = true; start() }
        try {
            // The car acks one chunk every 60 ms. The capture thread fills the window at once
            // and then waits on each ack, so each gap is the car's own pace.
            Thread.sleep(200)
            repeat(30) {
                Thread.sleep(60)
                ack(link)
            }
            Thread.sleep(100)
        } finally {
            capture.interrupt()
            link.stop()
        }
        // 60 ms is 2880 frames at 48 kHz, rounded up to 4096 frames of 4 bytes.
        assertTrue("first chunks go out as captured: $sent", sent.take(16).all { it == CAPTURE_BYTES })
        assertEquals(16_384, sent.last())
    }

    @Test
    fun aCarThatKeepsUpGetsTheCaptureAsItComes() {
        val (link, sent) = startedLink(window = 16)
        val read = ByteArray(CAPTURE_BYTES)
        // Every chunk is acked before the next, so the window never fills.
        repeat(100) {
            link.write(read, read.size)
            ack(link)
        }
        link.stop()
        assertEquals(100, sent.size)
        assertTrue(sent.all { it == CAPTURE_BYTES })
    }

    private companion object {
        const val MSG_DATA = 0
        const val MSG_CONFIG = 32771
        const val MSG_ACK = 32772
        const val TIMESTAMP_BYTES = 8
        const val CAPTURE_BYTES = 4096
    }
}
