package app.seb3thehacker.gearslip

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.media.Image
import android.view.Surface

/**
 * Encodes a generated test card to H.264 Baseline and hands the bitstream to a callback.
 *
 * Feeds the encoder YUV420 buffers via getInputImage() rather than a Surface: a MediaCodec
 * input Surface requires an EGL/OpenGL context to render into (Surface.lockCanvas is not
 * supported on it), and for a static test card that is a lot of machinery for no benefit.
 * Byte-buffer input keeps the spike dependency-free and fully deterministic. Phase C swaps
 * this for a real Surface + GL path when the source becomes a live WebView.
 *
 * The head unit negotiated MEDIA_CODEC_VIDEO_H264_BP, so the profile is pinned to Baseline.
 */
class VideoSource(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val mode: Mode = Mode.TEST_CARD,
    /** Over Wi-Fi: a fraction of the cable bitrate, so the link keeps up and sound isn't starved. */
    private val wireless: Boolean = false,
    private val onCodecConfig: (ByteArray) -> Unit,
    private val onFrame: (data: ByteArray, presentationTimeUs: Long, keyFrame: Boolean) -> Unit,
    /**
     * SURFACE mode, nothing encoded for [STILL_MS]. The repeat setting below should cover a still
     * screen, but on a Pixel 10 it didn't: a LuChi box got 11 frames, then none, and stayed black
     * (issue #58). Whoever draws the screen can draw it again, and that is a frame.
     */
    private val onStill: () -> Unit = {},
) {
    private val log = GearslipLog.tagged("VIDEO")

    /**
     * TEST_CARD feeds generated YUV buffers; SURFACE exposes a MediaCodec input Surface for
     * something else to render into (Phase C drives it from a VirtualDisplay + Presentation,
     * which is how Android screen mirroring works and avoids hand-written EGL entirely).
     */
    enum class Mode { TEST_CARD, SURFACE }

    /** Non-null in SURFACE mode once [start] has run. */
    var inputSurface: Surface? = null
        private set

    private var codec: MediaCodec? = null

    /** The bitrate the encoder started at, the most [setBitRate] ever goes back up to. */
    var fullBitRate = 0
        private set

    /** What the encoder is asked for right now. */
    @Volatile var bitRate = 0
        private set
    private var worker: Thread? = null
    @Volatile private var running = false
    private var frameIndex = 0L

    fun start() {
        val level = levelFor(width, height, frameRate)
        val bitRate = defaultBitRate(width, height, frameRate, level).let {
            if (wireless) (it * WIRELESS_SHARE).toInt().coerceAtMost(WIRELESS_MAX_BIT_RATE) else it
        }
        fullBitRate = bitRate
        this.bitRate = bitRate
        fun format(tuned: Boolean) = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                if (mode == Mode.SURFACE) {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                } else {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                },
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            // Drops already ask for a fresh keyframe, so a long interval costs nothing in recovery
            // and spares the link a big frame every second.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
            if (mode == Mode.SURFACE) {
                // A surface encoder only makes a frame when the screen changes, and many decoders
                // show a frame only once the next one arrives. So the last change before the
                // screen goes still (a menu closing) sat in the car's decoder until something
                // else moved. Repeating the last frame after a short pause pushes it through;
                // a repeat of an unchanged screen costs a few bytes. scrcpy does the same.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
                // An app that draws nonstop (a moving map) renders at the phone's refresh rate,
                // faster than the car acks. Every extra frame queued inside the encoder, and the
                // car's screen fell 25 seconds behind. Capping the input drops the extras before
                // they're encoded, so the car always gets the newest frame.
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, frameRate.toFloat())
            }
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, level.codecLevel)
            if (tuned) {
                // Hints only: a car screen wants each frame now, not the best frame a moment later.
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }

        var encoder = MediaCodec.createEncoderByType(MIME)
        try {
            encoder.configure(format(tuned = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders refuse a hint they don't know. The plain format is what always worked.
            log.w("encoder refused the tuned format (${e.message}) - using the plain one")
            encoder.release()
            encoder = MediaCodec.createEncoderByType(MIME)
            encoder.configure(format(tuned = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        if (mode == Mode.SURFACE) inputSurface = encoder.createInputSurface()
        encoder.start()
        codec = encoder
        running = true

        log.i("encoder started: ${width}x$height @ ${frameRate}fps, ${bitRate / 1000} kbps${if (wireless) " (wireless)" else ""}, H.264 Baseline level ${level.label}, mode=$mode")

        worker = Thread {
            runCatching { pump(encoder) }
                .onFailure { if (running) log.e("encoder loop failed", it) }
        }.apply { name = "gearslip-encoder"; start() }
    }

    /**
     * Forces the next encoded frame to be a full keyframe (IDR) rather than a P-frame.
     *
     * Needed whenever a frame gets dropped between us and the head unit: every frame after
     * the drop is a P-frame that assumes the decoder still has the reference picture we
     * never delivered. Without a resync, the decoder free-runs on a corrupted reference
     * forever - visually, exactly the smeared/ghosted look of a partial e-ink refresh,
     * because nothing ever forces a clean full-frame redraw.
     */
    fun requestSyncFrame() {
        runCatching {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }.onFailure { log.w("requestSyncFrame failed: ${it.message}") }
    }

    /**
     * Changes the bitrate while streaming, without a new keyframe or a restart. Used to back off
     * when the car (or the Wi-Fi to it) can't keep up, and to climb back once it can.
     */
    fun setBitRate(bps: Int) {
        val target = bps.coerceIn(fullBitRate / 4, fullBitRate)
        if (target == bitRate) return
        runCatching {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, target) })
            bitRate = target
        }.onFailure { log.w("setBitRate failed: ${it.message}") }
    }

    fun stop() {
        running = false
        // Stop is called from threads that are themselves being shut down. An interrupt here
        // used to escape as an uncaught exception and take the whole app down with the session.
        try {
            worker?.join(1_000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private fun pump(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val frameIntervalUs = 1_000_000L / frameRate
        var lastOutputAt = 0L
        var lastNudgeAt = 0L

        while (running) {
            // SURFACE mode has no input buffers to fill - frames arrive from the display.
            val inIndex = if (mode == Mode.SURFACE) -1 else encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val image = encoder.getInputImage(inIndex)
                if (image != null) {
                    drawTestCard(image, frameIndex)
                    encoder.queueInputBuffer(
                        inIndex, 0, width * height * 3 / 2, frameIndex * frameIntervalUs, 0,
                    )
                } else {
                    encoder.queueInputBuffer(inIndex, 0, 0, frameIndex * frameIntervalUs, 0)
                }
                frameIndex++
            }

            // A surface-fed encoder has nothing to do between frames, so block until one is ready
            // instead of sleeping a fixed time: less delay on screen, fewer wakeups for the CPU.
            val wait = if (mode == Mode.SURFACE) SURFACE_TIMEOUT_US else TIMEOUT_US
            var outIndex = encoder.dequeueOutputBuffer(info, wait)
            while (outIndex >= 0) {
                val buffer = encoder.getOutputBuffer(outIndex)
                if (buffer != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(data)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        onCodecConfig(data)
                    } else {
                        onFrame(
                            data,
                            info.presentationTimeUs,
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                        )
                    }
                }
                encoder.releaseOutputBuffer(outIndex, false)
                lastOutputAt = System.currentTimeMillis()
                outIndex = encoder.dequeueOutputBuffer(info, 0)
            }
            if (mode == Mode.SURFACE && lastOutputAt > 0) {
                val now = System.currentTimeMillis()
                if (now - lastOutputAt > STILL_MS && now - lastNudgeAt > STILL_MS) {
                    lastNudgeAt = now
                    onStill()
                }
            }

            if (mode != Mode.SURFACE) Thread.sleep(maxOf(1L, frameIntervalUs / 2000))
        }
    }

    /**
     * Colour bars with a bar that advances each second, so the car screen shows something
     * unmistakably ours and obviously live rather than a frozen or stale frame.
     */
    private fun drawTestCard(image: Image, frame: Long) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val bars = BAR_COLOURS.size
        val barHeight = maxOf(1, height / bars)
        val marker = ((frame / frameRate) % bars).toInt()

        val yBuf = yPlane.buffer
        val yStride = yPlane.rowStride
        for (row in 0 until height) {
            val bar = minOf(bars - 1, row / barHeight)
            val (luma, _, _) = BAR_COLOURS[bar]
            val value = if (bar == marker) 235 else luma
            val base = row * yStride
            for (col in 0 until width) {
                yBuf.put(base + col, value.toByte())
            }
        }

        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        for (row in 0 until height / 2) {
            val bar = minOf(bars - 1, (row * 2) / barHeight)
            val (_, cb, cr) = BAR_COLOURS[bar]
            for (col in 0 until width / 2) {
                uBuf.put(row * uPlane.rowStride + col * uPlane.pixelStride, cb.toByte())
                vBuf.put(row * vPlane.rowStride + col * vPlane.pixelStride, cr.toByte())
            }
        }
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val TIMEOUT_US = 10_000L
        const val SURFACE_TIMEOUT_US = 100_000L
        private const val STILL_MS = 1_000L

        /**
         * 800x480 at 30fps gets 8 Mbps: about the most that size can show, enough that a moving
         * video in the browser stays sharp. Bigger screens get more, but less than in proportion,
         * since large frames compress better: bitrate grows with pixels^0.75. 60fps gets 1.5x,
         * because frames that close together are mostly alike.
         */
        const val BASE_BIT_RATE = 8_000_000.0
        const val BASE_PIXELS = 800.0 * 480
        /** No car's decoder is asked for more than this, whatever its level allows. */
        const val MAX_BIT_RATE = 25_000_000

        /**
         * The H.264 levels a car screen can need, smallest first: macroblocks a second each
         * decodes, and the Baseline bitrate it must handle. The encoder declares the smallest
         * that fits, and the bitrate stays within it, so a car decoder isn't asked for more than
         * the level it was told about.
         */
        enum class Level(val label: String, val codecLevel: Int, val macroblocksPerSecond: Int, val maxBitRate: Int) {
            L31("3.1", MediaCodecInfo.CodecProfileLevel.AVCLevel31, 108_000, 14_000_000),
            L32("3.2", MediaCodecInfo.CodecProfileLevel.AVCLevel32, 216_000, 20_000_000),
            L4("4", MediaCodecInfo.CodecProfileLevel.AVCLevel4, 245_760, 20_000_000),
            L42("4.2", MediaCodecInfo.CodecProfileLevel.AVCLevel42, 522_240, 50_000_000),
        }

        fun levelFor(width: Int, height: Int, frameRate: Int): Level {
            val perSecond = ((width + 15) / 16) * ((height + 15) / 16) * frameRate
            return Level.entries.firstOrNull { perSecond <= it.macroblocksPerSecond } ?: Level.L42
        }

        fun defaultBitRate(width: Int, height: Int, frameRate: Int, level: Level): Int {
            val size = Math.pow(width.toDouble() * height / BASE_PIXELS, 0.75)
            val rate = if (frameRate > 30) 1.5 else 1.0
            return (BASE_BIT_RATE * size * rate).toInt().coerceAtMost(minOf(level.maxBitRate, MAX_BIT_RATE))
        }
        /**
         * A car's Wi-Fi is shared, noisy and far slower than its nominal rate, and the sound rides
         * the same link behind the video. Real Android Auto sends a few Mbps wirelessly; at the
         * cable rate (15-20 Mbps for 720p) a laptop hotspot fell seconds behind and the music
         * cut in and out. 720p30 comes out near 5.4 Mbps.
         */
        const val WIRELESS_SHARE = 0.35
        const val WIRELESS_MAX_BIT_RATE = 8_000_000
        const val I_FRAME_INTERVAL_S = 5
        private const val REPEAT_AFTER_US = 100_000L

        /** (Y, Cb, Cr) - white, yellow, cyan, green, magenta, red, blue. */
        val BAR_COLOURS = listOf(
            Triple(180, 128, 128),
            Triple(162, 44, 142),
            Triple(131, 156, 44),
            Triple(112, 72, 58),
            Triple(84, 184, 198),
            Triple(65, 100, 212),
            Triple(35, 212, 114),
        )
    }
}
