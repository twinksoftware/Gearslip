package app.seb3thehacker.gearslip

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.seb3thehacker.gearslip.car.ClusterScreen
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Gearslip's picture on the car's instrument-cluster screen: a second video sink, for a car that
 * offers one, showing the turn card ([ClusterScreen]). The same exchange as the main screen's,
 * on its own channel, with its own encoder and its own private display:
 *
 *   -> CHANNEL_OPEN_REQUEST   <- CHANNEL_OPEN_RESPONSE
 *   -> Setup { H.264 }        <- Config { status, max_unacked, configuration_indices }
 *   -> VideoFocusRequest      <- VideoFocusNotification { projected }
 *   -> Start, CodecConfig, Data...   <- Ack
 *
 * No touch: a cluster has none. Experimental, behind [car.CarSettings.clusterVideoEnabled].
 *
 * Gearslip holds the cluster only while there's a route, as the card has nothing else to show.
 * Otherwise the car keeps its own picture there: an Audi's virtual cockpit went black instead of
 * showing its map (issue #56). [watchRoute] asks for the screen when a route starts and hands it
 * back once there has been none for [NO_ROUTE_MS].
 */
internal class ClusterVideo(
    private val context: Context,
    private val service: ServiceDiscovery.VideoService,
    private val send: (messageId: Int, body: ByteArray) -> Unit,
) {
    private val log = GearslipLog.tagged("CLUSTER")
    private val main = Handler(Looper.getMainLooper())

    val channelId get() = service.serviceId

    private var configIndex = 0
    @Volatile private var started = false
    /** Configured and ready for focus requests. */
    @Volatile private var ready = false
    /** The car shows Gearslip's picture now; frames aren't sent while it shows its own. */
    @Volatile private var projected = false
    /** Gearslip has asked for the screen and not handed it back since. */
    private var wanted = false
    private var noRouteSince = 0L
    @Volatile private var running = true
    @Volatile private var source: VideoSource? = null
    private var projector: ScreenProjector? = null

    private val ackLock = Object()
    @Volatile private var inFlight = 0
    @Volatile private var maxUnacked = 1
    @Volatile private var lastAckAt = 0L
    private var frames = 0
    /** The part of the picture the car's own gauges and bars leave clear, as the car last said. */
    private val clearArea = MutableStateFlow<Insets?>(null)

    fun onMessage(messageId: Int, body: ByteArray) {
        when (messageId) {
            GearslipRunner.MSG_CHANNEL_OPEN_RESPONSE -> {
                val status = Protobuf.readInt32Field(body, 1) ?: 0
                log.i("<- ChannelOpenResponse: status=$status")
                if (status != 0) return
                send(GearslipRunner.MSG_MEDIA_SETUP, Protobuf.varintField(1, GearslipRunner.CODEC_H264_BP.toLong()))
                log.i("-> Setup(codec=VIDEO_H264_BP)")
            }

            GearslipRunner.MSG_MEDIA_CONFIG -> {
                val fields = Wire.fields(body)
                Wire.varint(fields, 2)?.toInt()?.let { maxUnacked = maxOf(1, it) }
                val offered = fields.filter { it.number == 3 && it.wireType == 0 }.map { it.varint.toInt() }
                // The cluster gets the smallest picture it offers: a turn card needs no more.
                configIndex = offered.minByOrNull { service.configs.getOrNull(it)?.pixelCount() ?: Int.MAX_VALUE } ?: 0
                log.i("<- Config status=${Wire.varint(fields, 1)} max_unacked=$maxUnacked offered=$offered - using $configIndex")
                ready = true
                main.post(watchRoute)
            }

            GearslipRunner.MSG_VIDEO_FOCUS_NOTIFICATION -> {
                val mode = Wire.varint(Wire.fields(body), 1)?.toInt() ?: GearslipRunner.VIDEO_FOCUS_PROJECTED
                log.i("<- VideoFocusNotification: mode=$mode")
                val now = mode == GearslipRunner.VIDEO_FOCUS_PROJECTED || mode == GearslipRunner.VIDEO_FOCUS_PROJECTED_NO_INPUT
                val back = now && !projected
                projected = now
                if (now && !started) startStream()
                // Back from the car's own picture: a fresh key frame, so it doesn't start on a partial one.
                else if (back) source?.requestSyncFrame()
            }

            GearslipRunner.MSG_MEDIA_ACK -> {
                val acked = Wire.varint(Wire.fields(body), 2)?.toInt() ?: 1
                synchronized(ackLock) {
                    inFlight = maxOf(0, inFlight - maxOf(1, acked))
                    lastAckAt = System.currentTimeMillis()
                    ackLock.notifyAll()
                }
            }

            // UpdateUiConfigRequest { AdditionalVideoConfig { display_insets = 1 } }: an Audi's
            // virtual cockpit sends one as the picture starts, and draws its dials over the frame.
            GearslipRunner.MSG_VIDEO_UI_CONFIG -> {
                log.i("<- UpdateUiConfigRequest (${body.size} bytes)\n${Protobuf.describe(body)}")
                clearArea.value = insetsFrom(body)
            }

            else -> log.i("<- unhandled message id=$messageId (${body.size} bytes)")
        }
    }

    /** Once a second: the cluster follows whether there's a route. */
    private val watchRoute = object : Runnable {
        override fun run() {
            if (!running || !ready) return
            val route = app.seb3thehacker.gearslip.car.NavTurn.current() != null
            val now = System.currentTimeMillis()
            if (route) noRouteSince = 0L else if (noRouteSince == 0L) noRouteSince = now
            when {
                route && !wanted -> requestFocus(GearslipRunner.VIDEO_FOCUS_PROJECTED, "a route started")
                !route && wanted && now - noRouteSince >= NO_ROUTE_MS -> requestFocus(GearslipRunner.VIDEO_FOCUS_NATIVE, "no route")
            }
            main.postDelayed(this, WATCH_MS)
        }
    }

    private fun requestFocus(mode: Int, why: String) {
        wanted = mode == GearslipRunner.VIDEO_FOCUS_PROJECTED
        // VideoFocusRequestNotification { disp_channel_id = 1; mode = 2; reason = 3 }, as the main
        // screen asks for its own picture back.
        val reason = if (wanted) 1L else GearslipRunner.VIDEO_FOCUS_REASON_LAUNCH_NATIVE.toLong()
        val body = Protobuf.varintField(1, channelId.toLong()) + Protobuf.varintField(2, mode.toLong()) + Protobuf.varintField(3, reason)
        runCatching { send(GearslipRunner.MSG_VIDEO_FOCUS_REQUEST, body) }
        log.i("-> VideoFocusRequest(mode=${if (wanted) "PROJECTED" else "NATIVE"}): $why")
    }

    private fun startStream() {
        val config = service.configs.getOrNull(configIndex) ?: service.configs.firstOrNull() ?: return
        val (width, height) = config.pixelSize() ?: run {
            log.w("unsupported cluster resolution ${config.resolutionName}")
            return
        }
        started = true
        send(
            GearslipRunner.MSG_MEDIA_START,
            Protobuf.varintField(1, GearslipRunner.SESSION_ID.toLong()) + Protobuf.varintField(2, configIndex.toLong()),
        )
        log.i("-> Start(config=$configIndex -> $config)")
        lastAckAt = System.currentTimeMillis()
        val video = VideoSource(
            width = width,
            height = height,
            frameRate = FRAME_RATE,
            mode = VideoSource.Mode.SURFACE,
            wireless = true, // a small share of the link: the main screen comes first
            onCodecConfig = { csd ->
                send(GearslipRunner.MSG_MEDIA_CODEC_CONFIG, csd)
                log.i("-> CodecConfig (${csd.size} bytes)")
            },
            onFrame = { data, pts, _ -> sendFrame(data, pts) },
        )
        source = video
        runCatching { video.start() }.onFailure {
            log.e("cluster encoder failed to start", it)
            return
        }
        val surface = video.inputSurface ?: return
        val density = config.density.takeIf { it > 0 } ?: 160
        main.post {
            if (!running) return@post
            projector = ScreenProjector(context).also {
                it.start(surface, width, height, density, name = "gearslip-cluster") {
                    // The margins the car crops off are kept clear, as on the main screen.
                    ClusterScreen(config.widthMargin, config.heightMargin, clearArea)
                }
            }
        }
    }

    /** As on the main screen: wait for the car's acks rather than drop a frame. */
    private fun sendFrame(data: ByteArray, presentationTimeUs: Long) {
        synchronized(ackLock) {
            while (running && inFlight >= maxUnacked) {
                val since = System.currentTimeMillis() - lastAckAt
                if (since > GearslipRunner.ACK_STALL_MS) {
                    inFlight = 0
                    lastAckAt = System.currentTimeMillis()
                    break
                }
                ackLock.wait(GearslipRunner.ACK_STALL_MS - since + 1)
            }
        }
        if (!running || !projected) return
        val timestamp = ByteArray(8)
        for (i in 0 until 8) timestamp[i] = ((presentationTimeUs shr ((7 - i) * 8)) and 0xFF).toByte()
        synchronized(ackLock) { inFlight++ }
        runCatching { send(GearslipRunner.MSG_MEDIA_DATA, timestamp + data) }.onFailure { running = false }
        if (++frames <= 2) log.i("-> Data frame #$frames (${data.size} bytes)")
    }

    /** VideoInsets { top = 1; bottom = 2; left = 3; right = 4 }, or null when the car sent none. */
    private fun insetsFrom(body: ByteArray): Insets? {
        val config = Wire.bytes(Wire.fields(body), 1) ?: return null
        val insets = Wire.fields(Wire.bytes(Wire.fields(config), 1) ?: return null)
        fun side(n: Int) = Wire.varint(insets, n)?.toInt() ?: 0
        return Insets(top = side(1), bottom = side(2), left = side(3), right = side(4))
            .also { log.i("the car keeps clear: $it") }
    }

    fun stop() {
        running = false
        main.removeCallbacks(watchRoute)
        synchronized(ackLock) { ackLock.notifyAll() }
        main.post {
            projector?.stop()
            projector = null
        }
        source?.stop()
        source = null
    }

    private companion object {
        // A turn card changes a few times a minute; the encoder repeats the last frame between.
        const val FRAME_RATE = 15
        const val WATCH_MS = 1_000L
        /** A gap in the turns this long is the route over, not a reroute or a moment between steps. */
        const val NO_ROUTE_MS = 10_000L
    }
}
