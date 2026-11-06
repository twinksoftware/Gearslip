package app.seb3thehacker.gearslip.audio

import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.ServiceDiscovery
import app.seb3thehacker.gearslip.Wire
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The phone end of the head unit's media audio sink, the audio twin of the video channel:
 *
 *   -> CHANNEL_OPEN_REQUEST   <- CHANNEL_OPEN_RESPONSE
 *   -> Setup { PCM }          <- Config { status, max_unacked, configuration_indices }
 *   -> AudioFocusRequest      <- AudioFocusResponse           (control channel)
 *   -> Start { session_id, configuration_index }
 *   -> Data { 8-byte timestamp, PCM }...   <- Ack { session_id, ack }
 *   -> Stop
 *
 * Inverted from aasdk's AudioMediaSinkService. Bench-verified only as far as capture goes:
 * nothing on this side of the wire has met a head unit yet.
 */
class AudioLink(
    private val service: ServiceDiscovery.AudioService,
    private val sendOnChannel: (messageId: Int, body: ByteArray) -> Unit,
    private val sendControl: (messageId: Int, body: ByteArray) -> Unit,
    /** Log name: "audio" for the media sink, "guidance" for the directions sink. */
    private val tag: String = "audio",
    private val sessionId: Int = SESSION_ID,
    /**
     * Media asks the car for focus when it starts. Directions ask only around each announcement,
     * through [askForSpeakers], and only when the music isn't already ours.
     */
    private val asksFocus: Boolean = true,
    private val onReady: (AudioLink) -> Unit = { CarAudio.attach(it) },
    private val onClose: (AudioLink) -> Unit = { CarAudio.detach(it) },
    /** The car took the sound back after giving it, as when the driver switches to the radio. */
    private val onTakenBack: () -> Unit = {},
    /** The car wants the speakers for a moment (a call, its own voice), and then [onGivenBack]. */
    private val onPausedForNow: () -> Unit = {},
    private val onGivenBack: () -> Unit = {},
) : CarAudio.Link {

    private var configIndex = 0
    @Volatile private var ready = false
    @Volatile private var started = false
    @Volatile private var focusGranted = false
    @Volatile private var focusRequested = false
    /** The car took the speakers for a moment, and we paused the phone's music until it's done. */
    @Volatile private var pausedForNow = false
    @Volatile private var inFlight = 0
    // The Uconnect asks for 4 on its video channel; assume the same until its Config says otherwise.
    @Volatile private var maxUnacked = 4
    @Volatile private var lastAckAt = 0L
    /**
     * Bytes of PCM per Data message, or 0 to send each capture read as it comes. Raised by
     * [adaptChunkSize] for a car that acks fewer chunks than it plays.
     */
    @Volatile private var chunkBytes = 0
    /** Capture reads gathered until they fill [chunkBytes]. Only the capture thread touches it. */
    private val pending = java.io.ByteArrayOutputStream()
    /** Set by a new stream, so the capture thread drops what an older one left in [pending]. */
    @Volatile private var pendingStale = false
    /** The size of the last chunk sent, for [adaptChunkSize] to know what each one holds. */
    @Volatile private var lastPayloadBytes = 0
    /** Acks that came while the window was full, and the time they took: the car's own pace. */
    private var slowAcks = 0
    private var slowAckMs = 0L
    private var lastAckFull = false
    private var stalls = 0
    private var chunks = 0
    private var bytes = 0L
    private var dropped = 0
    @Volatile private var configTimer: Thread? = null
    /** Underflows the car reported this drive, and the silence still to slip in ahead of the next chunk. */
    private var underflows = 0
    @Volatile private var padMs = 0
    @Volatile private var pendingPadMs = 0
    private val ackLock = Object()
    /** Only for a sink that takes AAC and nothing else; PCM sinks get the capture as it is. */
    @Volatile private var aac: AacEncoder? = null
    private val aacSink get() = service.codecType == ServiceDiscovery.CODEC_AAC_LC || service.codecType == ServiceDiscovery.CODEC_AAC_LC_ADTS

    private val config get() = service.configs.getOrNull(configIndex) ?: service.configs.firstOrNull()
    override val sampleRate get() = config?.sampleRate ?: 48_000
    override val channels get() = config?.channels ?: 2

    val channelId get() = service.serviceId

    /** Sending audio now, which for media means the car has handed us the speakers. */
    val streaming get() = started

    /** The car has set the sink up, so a stream can start. */
    val isReady get() = ready

    @Volatile private var holdsSpeakers = false
    @Volatile private var focusAnswer: CountDownLatch? = null
    @Volatile private var answerDueBy = 0L

    /** True just after [askForSpeakers] or [handBackSpeakers], when the car's next focus answer is ours. */
    fun expectsFocusAnswer() = System.currentTimeMillis() < answerDueBy

    /**
     * For directions when nothing of ours is playing, so the car's speakers belong to the radio
     * or a Bluetooth phone. Android Auto asks for them before each announcement, letting the
     * other source duck, and hands them back afterwards. Speaking without asking left a 2025
     * Nissan's Bosch head unit rebooting mid-drive (issue #35).
     *
     * Waits briefly for the car's answer and speaks anyway without one, as some cars never answer.
     */
    fun askForSpeakers() {
        if (holdsSpeakers) return
        val answer = CountDownLatch(1)
        focusAnswer = answer
        answerDueBy = System.currentTimeMillis() + FOCUS_WAIT_MS
        holdsSpeakers = true
        sendControl(MSG_FOCUS_REQUEST, Protobuf.varintField(1, FOCUS_GAIN_TRANSIENT_MAY_DUCK.toLong()))
        GearslipLog.i("$tag: -> AudioFocusRequest(GAIN_TRANSIENT_MAY_DUCK)")
        if (!answer.await(FOCUS_WAIT_MS, TimeUnit.MILLISECONDS)) {
            GearslipLog.i("$tag: no focus answer after ${FOCUS_WAIT_MS}ms - speaking anyway")
        }
        focusAnswer = null
    }

    /** Gives back what [askForSpeakers] took, so the other source comes back to full volume. */
    fun handBackSpeakers() {
        if (!holdsSpeakers) return
        holdsSpeakers = false
        answerDueBy = System.currentTimeMillis() + FOCUS_WAIT_MS
        runCatching { sendControl(MSG_FOCUS_REQUEST, Protobuf.varintField(1, FOCUS_RELEASE.toLong())) }
        GearslipLog.i("$tag: -> AudioFocusRequest(RELEASE)")
    }

    /**
     * A spoken turn on the music channel while no music plays (see DirectionsVoice): the stream
     * runs for the turn only, under ducking focus like the directions channel's, so the radio
     * comes back after it rather than giving way to Android Auto for good.
     */
    fun startForTurn() {
        askForSpeakers()
        startStream()
    }

    fun stopForTurn() {
        synchronized(startLock) { stopLocked(release = false) }
        handBackSpeakers()
    }

    fun opened(status: Int) {
        GearslipLog.i("$tag: <- ChannelOpenResponse: status=$status")
        if (status != 0) {
            GearslipLog.w("$tag: the head unit refused the $tag channel (status $status)")
            return
        }
        // Setup { required MediaCodecType type = 1 }: the codec the car advertised for this sink
        // (aasdk's current protos, and LIVI reads it as the codec). Gearslip used to send 0 here,
        // read from an older proto as a config index; 0 is no codec at all, and the 2024 VW
        // answered UNEXPECTED_MESSAGE (id 255), never acked a chunk and played nothing.
        sendOnChannel(MSG_SETUP, Protobuf.varintField(1, service.codecType.toLong()))
        GearslipLog.i("$tag: -> Setup(codec=${service.codecName}) on channel ${service.serviceId}")
        armConfigFallback()
    }

    /**
     * The 2018 Uconnect, a Dacia ULC 4.5 and the 2024 VW answered the old Setup (codec 0) with
     * UNEXPECTED_MESSAGE (id 255, no body) rather than a Config, so the sink never reached READY.
     * [MSG_SETUP_ANSWER] still takes that as the go-ahead, in case a car rejects the real codec too.
     *
     * Service discovery already told us everything a Config would have: the sink's rate, channels
     * and its one configuration. So for a car that answers nothing at all, wait a moment and then
     * assume the advertised configuration. If that guess is wrong the head unit simply will not
     * ack, which the write path already reports.
     */
    private fun armConfigFallback() {
        val timer = Thread({
            try {
                Thread.sleep(CONFIG_WAIT_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            useAdvertisedSink("no usable Config after ${CONFIG_WAIT_MS}ms")
        }, "gearslip-audio-config").apply { isDaemon = true }
        configTimer = timer
        timer.start()
    }

    @Synchronized
    private fun useAdvertisedSink(why: String) {
        if (ready) return
        configTimer?.interrupt()
        GearslipLog.i(
            "$tag: $why - using the sink the head unit advertised " +
                "(${sampleRate}Hz x$channels, config_index=0, max_unacked=$maxUnacked)",
        )
        configIndex = 0
        ready = true
        onReady(this)
    }

    fun onMessage(messageId: Int, body: ByteArray) {
        when (messageId) {
            MSG_CHANNEL_OPEN_RESPONSE -> opened(Protobuf.readInt32Field(body, 1) ?: 0)

            MSG_CONFIG -> {
                val fields = Wire.fields(body)
                val status = Wire.varint(fields, 1)?.toInt()
                val window = Wire.varint(fields, 2)?.toInt()
                val indices = fields.filter { it.number == 3 && it.wireType == 0 }.map { it.varint.toInt() }
                GearslipLog.i("$tag: <- Config status=$status max_unacked=$window indices=$indices")
                if (window != null) maxUnacked = maxOf(1, window)
                configIndex = indices.firstOrNull() ?: 0
                if (status == STATUS_READY) {
                    configTimer?.interrupt()
                    ready = true
                    GearslipLog.i("$tag: sink ready - ${sampleRate}Hz x$channels ${config?.bits}bit")
                    onReady(this)
                } else {
                    GearslipLog.w(
                        "$tag: the sink is not ready (status=$status, 1=WAIT 2=READY) - " +
                            "nothing will be sent until it is",
                    )
                }
            }

            MSG_UNDERFLOW -> onUnderflow()

            MSG_ACK -> {
                val acked = Wire.varint(Wire.fields(body), 2)?.toInt() ?: 1
                val now = System.currentTimeMillis()
                synchronized(ackLock) {
                    // A full window means the sender is waiting on this ack. When it was waiting on
                    // the one before too, the gap between them is the car's own pace.
                    val full = inFlight >= maxUnacked
                    if (full && lastAckFull) {
                        slowAcks++
                        slowAckMs += now - lastAckAt
                    }
                    lastAckFull = full
                    inFlight = maxOf(0, inFlight - maxOf(1, acked))
                    lastAckAt = now
                    ackLock.notifyAll()
                }
                adaptChunkSize()
            }

            // Whatever else the head unit answers Setup with, we need its bytes to identify it.
            else -> if (messageId == MSG_SETUP_ANSWER && body.isEmpty()) {
                useAdvertisedSink("<- empty Setup answer (id $messageId)")
            } else {
                GearslipLog.i("$tag: <- unhandled message id=$messageId (${body.size} bytes)")
                GearslipLog.hex("   audio msg", body, limit = 64)
            }
        }
    }

    /**
     * The head unit's answer to our AudioFocusRequest. Until it grants focus the sink is wired to
     * whatever else the car is playing - the radio, usually - and anything we send is discarded,
     * so [start] waits here rather than streaming into nothing.
     */
    fun onFocus(state: Int) {
        val name = FOCUS_STATES[state] ?: "unknown($state)"
        GearslipLog.i("$tag: <- AudioFocusResponse state=$name")
        if (!asksFocus || expectsFocusAnswer()) {
            // Directions speak whatever the answer: a lost turn is worse than a loud one.
            answerDueBy = 0L
            focusAnswer?.countDown()
            return
        }
        val had = focusGranted
        focusGranted = state == FOCUS_STATE_GAIN || state == FOCUS_STATE_GAIN_TRANSIENT ||
            state == FOCUS_STATE_GAIN_MEDIA_ONLY
        when {
            focusGranted -> {
                startStream()
                if (pausedForNow) {
                    pausedForNow = false
                    onGivenBack()
                }
            }
            // A call or the car's own voice. Releasing focus here meant the car never handed it
            // back, so the music stayed paused after the call: keep the claim and wait for GAIN.
            state == FOCUS_STATE_LOSS_TRANSIENT -> {
                synchronized(startLock) { stopLocked(release = false) }
                if (had) {
                    pausedForNow = true
                    onPausedForNow()
                }
            }
            else -> {
                stop()
                // A ducking request leaves the music playing; a loss means another source has the speakers.
                if (state == FOCUS_STATE_LOSS && (had || pausedForNow)) {
                    pausedForNow = false
                    onTakenBack()
                }
            }
        }
    }

    /**
     * The car ran out of sound to play (AudioUnderflowNotification, no body): its buffer emptied
     * before the next chunk arrived, which the driver hears as a gap. Capture runs in real time, so
     * the car's buffer only ever holds what the window lets through. A little silence slipped in
     * ahead of the next chunk puts it that far ahead, so the next slow patch is soaked up rather
     * than heard. Capped, as each step adds a little delay to the music.
     */
    private fun onUnderflow() {
        underflows++
        val step = if (started && padMs < MAX_PAD_MS) PAD_STEP_MS else 0
        padMs += step
        pendingPadMs += step
        if (underflows <= 3 || underflows % 20 == 0) {
            GearslipLog.w("$tag: <- AudioUnderflow ($underflows this drive) - running ${padMs}ms ahead now")
        }
    }

    /**
     * A 2020 Civic acks a chunk about every 83 ms whatever the chunk holds, so 21 ms chunks
     * reached it at a quarter of real time: a second of music, then silence, over and over
     * (issue #43). When the car keeps the sender waiting and its acks come much further apart
     * than the audio in each chunk, gather the capture into chunks that last at least that gap,
     * rounded up to a power of two in frames: 83 ms is about 4096 frames at 48 kHz, which looks
     * like the car's own buffer. A car that keeps up never fills the window, so it never gets here.
     */
    private fun adaptChunkSize() {
        if (aac != null) return
        val interval = synchronized(ackLock) {
            if (slowAcks < ADAPT_ACKS) return
            (slowAckMs.toDouble() / slowAcks).also {
                slowAcks = 0
                slowAckMs = 0
            }
        }
        val frame = channels * 2
        val bytesPerMs = sampleRate * frame / 1000.0
        val current = if (chunkBytes > 0) chunkBytes else lastPayloadBytes
        if (current <= 0 || current >= MAX_CHUNK_BYTES) return
        val heldMs = current / bytesPerMs
        if (interval < heldMs * SLOW_ACK_RATIO) return
        val gapFrames = Math.ceil(interval * sampleRate / 1000.0).toInt().coerceAtLeast(1)
        val frames = Integer.highestOneBit(gapFrames).let { if (it < gapFrames) it shl 1 else it }
        val target = minOf(MAX_CHUNK_BYTES / frame * frame, frames * frame)
        if (target <= current) return
        chunkBytes = target
        GearslipLog.i(
            "$tag: the car acks a chunk every ${interval.toInt()}ms, but each holds ${heldMs.toInt()}ms - " +
                "sending $target-byte chunks (${(target / bytesPerMs).toInt()}ms) from now on",
        )
    }

    /** Silence for [ms], whole frames of this sink's format. */
    private fun silence(ms: Int): ByteArray = ByteArray(sampleRate * ms / 1000 * channels * 2)

    override fun start() {
        if (!ready || started) return
        if (asksFocus && !focusRequested) {
            focusRequested = true
            sendControl(MSG_FOCUS_REQUEST, Protobuf.varintField(1, FOCUS_GAIN.toLong()))
            GearslipLog.i("$tag: -> AudioFocusRequest(GAIN); waiting for the head unit to hand the sink over")
        }
        // Some head units grant the sink silently and never answer. Do not let that be the end of
        // it: start anyway, and let the acks say whether anything is being taken.
        startStream()
    }

    /**
     * Guards starting and stopping. [start] starts the stream at once for head units that never
     * answer the focus request, and the car's answer arrives on another thread and starts it
     * too: without this both got through, so the car got Start twice and two AAC encoders, and a
     * 2014 head unit dropped the connection on the first audio.
     */
    private val startLock = Any()

    private fun startStream() {
        synchronized(startLock) { startStreamLocked() }
    }

    private fun startStreamLocked() {
        if (!ready || started) return
        sendOnChannel(
            MSG_START,
            Protobuf.varintField(1, sessionId.toLong()) + Protobuf.varintField(2, configIndex.toLong()),
        )
        // Raw AAC-LC has no header of its own: the car needs the codec config before the first frame.
        if (service.codecType == ServiceDiscovery.CODEC_AAC_LC) {
            val asc = AacEncoder.audioSpecificConfig(sampleRate, channels)
            sendOnChannel(MSG_CODEC_CONFIG, asc)
            GearslipLog.hex("$tag: -> CodecConfig (AudioSpecificConfig)", asc)
        }
        inFlight = 0
        lastAckAt = System.currentTimeMillis()
        pendingStale = true
        synchronized(ackLock) {
            slowAcks = 0
            slowAckMs = 0
            lastAckFull = false
        }
        if (aacSink && aac == null) {
            aac = runCatching { AacEncoder(sampleRate, channels, adts = service.codecType == ServiceDiscovery.CODEC_AAC_LC_ADTS) }
                .onSuccess { GearslipLog.i("$tag: the car takes ${service.codecName} only - encoding to AAC") }
                .onFailure { GearslipLog.e("$tag: couldn't start the AAC encoder", it) }
                .getOrNull()
        }
        started = true
        GearslipLog.i("$tag: -> Start(session=$sessionId, config=$configIndex) - streaming ${sampleRate}Hz x$channels")
    }

    override fun stop() {
        synchronized(startLock) { stopLocked() }
    }

    /** [release] false keeps our claim on the speakers, so the car hands them back on its own. */
    private fun stopLocked(release: Boolean = true) {
        if (!started) return
        started = false
        synchronized(ackLock) { ackLock.notifyAll() }
        runCatching {
            sendOnChannel(MSG_STOP, Protobuf.varintField(1, sessionId.toLong()))
            if (release && focusRequested) sendControl(MSG_FOCUS_REQUEST, Protobuf.varintField(1, FOCUS_RELEASE.toLong()))
        }
        if (release) focusRequested = false
        focusGranted = false
        aac?.release()
        aac = null
        GearslipLog.i("$tag: -> Stop${if (release) ", AudioFocusRequest(RELEASE)" else ""} after $chunks chunks / $bytes bytes")
        chunks = 0
        bytes = 0
        // A new stream starts with the car's buffer empty again: earn the lead back if it's needed.
        padMs = 0
        pendingPadMs = 0
    }

    override fun write(pcm: ByteArray, size: Int) {
        if (!started) return
        val pad = pendingPadMs
        if (pad > 0) {
            pendingPadMs = 0
            val quiet = silence(pad)
            writeNow(quiet, quiet.size)
        }
        writeNow(pcm, size)
    }

    private fun writeNow(pcm: ByteArray, size: Int) {
        val encoder = aac
        if (encoder == null) {
            if (pendingStale) {
                pendingStale = false
                pending.reset()
            }
            val want = chunkBytes
            if (want <= 0 && pending.size() == 0) {
                send(pcm.copyOf(size))
                return
            }
            pending.write(pcm, 0, size)
            if (pending.size() >= want) {
                send(pending.toByteArray())
                pending.reset()
            }
            return
        }
        val frames = try {
            encoder.encode(pcm, size)
        } catch (e: Exception) {
            // A codec that dies mid-drive leaves the car silent, as it was before AAC; log it once.
            GearslipLog.e("$tag: AAC encoding failed - no more audio this drive", e)
            aac = null
            started = false
            return
        }
        frames.forEach(::send)
    }

    /** One Data message, once the car has room for it. */
    private fun send(payload: ByteArray) {
        // Wait for the car to catch up rather than throw the chunk away: a dropped chunk is a gap
        // the driver hears, while a short wait is soaked up by the capture buffer behind us.
        synchronized(ackLock) {
            while (started && inFlight >= maxUnacked) {
                val waited = System.currentTimeMillis() - lastAckAt
                if (waited > STALL_MS) {
                    // A lost ack must not silence the stream for good; after a stall assume the window cleared.
                    // A car that never acks (the 2024 VW, the Bolt) hits this every chunk: say so
                    // once, then now and then, so the rest of the log stays readable.
                    if (stalls++ % 500 == 0) {
                        GearslipLog.w("$tag: no ack for ${STALL_MS}ms with $inFlight in flight - reopening the window ($stalls times)")
                    }
                    inFlight = 0
                    break
                }
                ackLock.wait(STALL_MS - waited + 1)
            }
        }
        if (!started) {
            dropped++
            return
        }
        val timestamp = ByteArray(8)
        val micros = System.nanoTime() / 1_000
        for (i in 0 until 8) timestamp[i] = ((micros shr ((7 - i) * 8)) and 0xFF).toByte()
        try {
            sendOnChannel(MSG_DATA, timestamp + payload)
        } catch (e: java.io.IOException) {
            GearslipLog.e("$tag: the link died mid-stream", e)
            started = false
            return
        }
        synchronized(ackLock) { inFlight++ }
        lastPayloadBytes = payload.size
        chunks++
        bytes += payload.size
        // The first chunk proves the whole path; after that, an occasional line is enough to show
        // the car is still taking audio and to catch a window that has quietly closed.
        if (chunks == 1 || chunks % CHUNK_LOG_EVERY == 0) {
            GearslipLog.i(
                "$tag: -> $chunks chunks / $bytes bytes, inFlight=$inFlight/$maxUnacked, " +
                    "dropped=$dropped, focus=${if (focusGranted) "granted" else "not granted"}",
            )
        }
    }

    fun close() {
        configTimer?.interrupt()
        configTimer = null
        stop()
        handBackSpeakers()
        ready = false
        onClose(this)
    }

    companion object {
        const val MSG_FOCUS_REQUEST = 18
        const val MSG_FOCUS_RESPONSE = 19
        private const val MSG_CHANNEL_OPEN_RESPONSE = 8
        private const val MSG_DATA = 0
        private const val MSG_CODEC_CONFIG = 1
        private const val MSG_SETUP = 32768
        private const val MSG_START = 32769
        private const val MSG_STOP = 32770
        private const val MSG_CONFIG = 32771
        private const val MSG_ACK = 32772
        private const val MSG_SETUP_ANSWER = 255
        private const val MSG_UNDERFLOW = 32779 // 0x800B, car to phone, no body
        private const val PAD_STEP_MS = 60
        private const val MAX_PAD_MS = 240
        private const val STATUS_READY = 2
        private const val FOCUS_GAIN = 1
        private const val FOCUS_GAIN_TRANSIENT_MAY_DUCK = 3
        private const val FOCUS_RELEASE = 4
        private const val FOCUS_WAIT_MS = 500L
        private const val FOCUS_STATE_GAIN = 1
        private const val FOCUS_STATE_GAIN_TRANSIENT = 2
        private const val FOCUS_STATE_GAIN_MEDIA_ONLY = 6
        private const val FOCUS_STATE_LOSS = 3
        private const val FOCUS_STATE_LOSS_TRANSIENT = 5
        private const val SESSION_ID = 2
        private const val STALL_MS = 2_000L
        /** Acks measured with the window full before the chunk size is judged. */
        private const val ADAPT_ACKS = 20
        /** Acks this much further apart than a chunk lasts mean the car is the one holding back. */
        private const val SLOW_ACK_RATIO = 1.5
        /** About 170 ms at 48 kHz stereo: the most delay a bigger chunk may add. */
        private const val MAX_CHUNK_BYTES = 32_768
        private const val CHUNK_LOG_EVERY = 200 // about every four seconds
        private const val CONFIG_WAIT_MS = 1_000L

        // AudioFocusStateNotification.AudioFocusState in aasdk.
        private val FOCUS_STATES = mapOf(
            1 to "GAIN", 2 to "GAIN_TRANSIENT", 3 to "LOSS", 4 to "LOSS_TRANSIENT_CAN_DUCK",
            5 to "LOSS_TRANSIENT", 6 to "GAIN_MEDIA_ONLY", 7 to "GAIN_TRANSIENT_GUIDANCE_ONLY",
        )
    }
}
