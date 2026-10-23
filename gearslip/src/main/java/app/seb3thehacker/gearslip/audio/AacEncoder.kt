package app.seb3thehacker.gearslip.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * Turns captured PCM into AAC-LC for a car whose music channel takes nothing else. Cars that
 * offer PCM never get here: AAC costs a little delay and quality, and every car that works today
 * is a PCM car.
 *
 * Android's encoder hands back raw access units, which is what AUDIO_AAC_LC expects. For
 * AUDIO_AAC_LC_ADTS each unit gets the 7-byte ADTS header in front, so the car can find the
 * frame edges itself. The encoder's first output is its configuration, not audio, and is skipped:
 * [AudioLink] sends the car the same thing, built by [audioSpecificConfig], right after Start.
 */
internal class AacEncoder(private val sampleRate: Int, private val channels: Int, private val adts: Boolean) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 96_000 else 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    private val info = MediaCodec.BufferInfo()
    private var samplesIn = 0L

    /** Encodes [size] bytes of 16-bit PCM and returns the AAC frames that came out, oldest first. */
    fun encode(pcm: ByteArray, size: Int): List<ByteArray> {
        var offset = 0
        val out = ArrayList<ByteArray>()
        while (offset < size) {
            val index = codec.dequeueInputBuffer(INPUT_WAIT_US)
            if (index < 0) {
                drain(out)
                continue
            }
            val buffer = codec.getInputBuffer(index) ?: continue
            val n = minOf(buffer.remaining(), size - offset)
            buffer.put(pcm, offset, n)
            val presentationUs = samplesIn * 1_000_000L / sampleRate
            codec.queueInputBuffer(index, 0, n, presentationUs, 0)
            samplesIn += n / (2 * channels)
            offset += n
            drain(out)
        }
        return out
    }

    private fun drain(out: MutableList<ByteArray>) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null) {
                    val frame = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(frame)
                    out.add(if (adts) adtsHeader(frame.size) + frame else frame)
                }
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    /** ADTS for one AAC-LC frame: sync word, no CRC, and the frame's length including this header. */
    private fun adtsHeader(payload: Int): ByteArray {
        val length = payload + 7
        val freq = FREQUENCIES.indexOf(sampleRate).let { if (it < 0) 3 else it }
        val profile = 1 // AAC-LC is object type 2, written as type minus one
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            ((profile shl 6) or (freq shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (length shr 11)).toByte(),
            ((length shr 3) and 0xFF).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    companion object {
        private const val INPUT_WAIT_US = 10_000L
        private val FREQUENCIES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        /** The two-byte AudioSpecificConfig for AAC-LC: object type 2, rate index, channel count. */
        fun audioSpecificConfig(sampleRate: Int, channels: Int): ByteArray {
            val freq = FREQUENCIES.indexOf(sampleRate).let { if (it < 0) 3 else it }
            return byteArrayOf(
                ((2 shl 3) or (freq shr 1)).toByte(),
                (((freq and 1) shl 7) or (channels shl 3)).toByte(),
            )
        }
    }
}
