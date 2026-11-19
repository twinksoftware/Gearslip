package app.seb3thehacker.gearslip.media

import android.graphics.Bitmap
import android.media.session.PlaybackState
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.car.CarServices
import java.io.ByteArrayOutputStream

/**
 * Tells the car what's playing, so its own screens show it: the instrument cluster, the media
 * source screen, the steering wheel display. Android Auto does the same over the car's
 * MediaPlaybackStatusService (Service field 9):
 *
 *   -> MediaPlaybackMetadata { song = 1; artist = 2; album = 3; album_art = 4; duration_seconds = 6 }
 *   -> MediaPlaybackStatus   { state = 1 (1 stopped, 2 playing, 3 paused); media_source = 2;
 *                              playback_seconds = 3; shuffle = 4; repeat = 5; repeat_one = 6 }
 *
 * Read once a second from the media app Gearslip is driving. The track goes only when it changes;
 * the status goes every second while playing, so the car's position counts along.
 */
class CarMediaStatus(
    val channelId: Int,
    private val sendOnChannel: (messageId: Int, body: ByteArray) -> Unit,
) {
    @Volatile private var running = false
    private var sentTrack: String? = null
    private var sentState = -1
    private var inputsLogged = 0

    fun onMessage(messageId: Int, body: ByteArray) {
        when (messageId) {
            MSG_CHANNEL_OPEN_RESPONSE -> {
                val status = Protobuf.readInt32Field(body, 1)
                GearslipLog.i("media status: <- ChannelOpenResponse: status=$status")
                if (status == 0) start()
            }
            // The car's own buttons for the media source (play, next...). Logged to learn what
            // this car sends; the steering wheel keys already arrive on the input channel.
            MSG_INPUT -> if (inputsLogged++ < 10) GearslipLog.i("media status: <- input from the car:\n" + Protobuf.describe(body))
            else -> GearslipLog.i("media status: <- unhandled message id=$messageId (${body.size} bytes)")
        }
    }

    private fun start() {
        if (running) return
        running = true
        Thread({
            while (running) {
                runCatching { tick() }.onFailure { GearslipLog.w("media status: ${it.message}") }
                Thread.sleep(TICK_MS)
            }
        }, "gearslip-media-status").start()
    }

    fun stop() {
        running = false
    }

    private class TrackInfo(val title: String, val artist: String, val album: String, val art: Bitmap?, val durationMs: Long)

    private fun tick() {
        // Gearslip no longer streams itself: the connected media app is the only source of truth.
        val remote = CarServices.mediaOrNull()?.now?.value
        val active = remote != null && remote.isActive
        val state = when {
            !active -> STATE_STOPPED
            remote!!.state == PlaybackState.STATE_PAUSED -> STATE_PAUSED
            else -> STATE_PLAYING
        }
        if (active) {
            val now = remote!!
            sendTrack(TrackInfo(now.title, now.artist, now.album, now.art, now.durationMs))
        }
        // Nothing to say while stopped, beyond saying so once.
        if (state == STATE_STOPPED && sentState == STATE_STOPPED) return
        if (state == STATE_PAUSED && sentState == STATE_PAUSED) return
        val source = CarServices.mediaApp.value?.label ?: "Gearslip"
        val seconds = (remote?.currentPosition() ?: 0L) / 1000
        sendOnChannel(
            MSG_STATUS,
            Protobuf.varintField(1, state.toLong()) +
                Protobuf.stringField(2, source) +
                Protobuf.varintField(3, seconds.coerceAtLeast(0)) +
                Protobuf.varintField(4, 0L) +
                Protobuf.varintField(5, 0L) +
                Protobuf.varintField(6, 0L),
        )
        if (state != sentState) GearslipLog.i("media status: -> ${STATE_NAMES[state]} ($source)")
        sentState = state
    }

    private fun sendTrack(now: TrackInfo) {
        // Whether there's a cover, not which bitmap: a fresh copy of the same cover every second
        // would waste the link.
        val key = "${now.title}\u0000${now.artist}\u0000${now.album}\u0000${now.durationMs}\u0000${now.art != null}"
        if (key == sentTrack) return
        sentTrack = key
        var body = Protobuf.stringField(1, now.title) +
            Protobuf.stringField(2, now.artist) +
            Protobuf.stringField(3, now.album)
        now.art?.let { art -> artBytes(art)?.let { body += Protobuf.bytesField(4, it) } }
        if (now.durationMs > 0) body += Protobuf.varintField(6, now.durationMs / 1000)
        sendOnChannel(MSG_METADATA, body)
        GearslipLog.i("media status: -> track \"${now.title}\" by ${now.artist.ifBlank { "?" }}")
    }

    /** Album art as a small PNG: car screens show it at thumbnail size, and the link is shared with video. */
    private fun artBytes(art: Bitmap): ByteArray? = runCatching {
        val scale = minOf(1f, ART_PX.toFloat() / maxOf(art.width, art.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(art, (art.width * scale).toInt(), (art.height * scale).toInt(), true) else art
        ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull()

    companion object {
        private const val TICK_MS = 1_000L
        private const val ART_PX = 256
        private const val MSG_CHANNEL_OPEN_RESPONSE = 8
        private const val MSG_STATUS = 32769
        private const val MSG_INPUT = 32770
        private const val MSG_METADATA = 32771
        private const val STATE_STOPPED = 1
        private const val STATE_PLAYING = 2
        private const val STATE_PAUSED = 3
        private val STATE_NAMES = mapOf(1 to "stopped", 2 to "playing", 3 to "paused")
    }
}
