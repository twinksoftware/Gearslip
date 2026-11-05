package app.seb3thehacker.gearslip

/**
 * Pulls the video service out of a ServiceDiscoveryResponse.
 *
 * Shape from aasdk (`service/Service.proto`, `service/media/sink/MediaSinkService.proto`,
 * `.../message/VideoConfiguration.proto`):
 *
 *   ServiceDiscoveryResponse { repeated Service channels = 1; ... }
 *   Service { required int32 id = 1; optional MediaSinkService media_sink_service = 3; ... }
 *   MediaSinkService { optional MediaCodecType available_type = 1;
 *                      repeated VideoConfiguration video_configs = 4; ... }
 *   VideoConfiguration { codec_resolution = 1; frame_rate = 2; density = 5; ... }
 *
 * The channel id used in the frame header is this service's `id` - real Android Auto assigns
 * channel ids dynamically (aasdk's static ChannelId enum is an approximation; see the TODO
 * in include/aasdk/Messenger/ChannelId.hpp).
 */
object ServiceDiscovery {

    /**
     * [widthMargin]/[heightMargin] are the head unit's own numbers (VideoConfiguration fields 3
     * and 4): pixels of the frame it crops away, split evenly between the two edges, to fit the
     * frame to a screen of a different shape. A matched vehicle profile's insets override them.
     */
    class VideoConfig(
        val resolution: Int,
        val frameRate: Int,
        val density: Int,
        val widthMargin: Int = 0,
        val heightMargin: Int = 0,
    ) {
        val resolutionName: String get() = RESOLUTIONS[resolution] ?: "unknown($resolution)"
        val frameRateName: String get() = if (frameRate == 1) "60fps" else "30fps"

        /** Pixel dimensions for this resolution enum, or null if we don't know it. */
        fun pixelSize(): Pair<Int, Int>? = PIXEL_SIZES[resolution]

        fun pixelCount(): Int = pixelSize()?.let { it.first * it.second } ?: Int.MAX_VALUE

        /** What's left on the car's screen once the margins are cropped off. */
        fun visibleSize(): Pair<Int, Int>? = pixelSize()?.let { (w, h) -> (w - widthMargin) to (h - heightMargin) }

        /** The margins as the strips of the frame the car UI keeps clear. */
        fun marginInsets(): Insets = Insets(
            top = heightMargin / 2, bottom = heightMargin - heightMargin / 2,
            left = widthMargin / 2, right = widthMargin - widthMargin / 2,
        )

        override fun toString() =
            "$resolutionName @ $frameRateName, density=$density, margin=${widthMargin}x$heightMargin"
    }

    /**
     * [displayId] is MediaSinkService field 6: 0, the default, is the main screen. [displayType]
     * is field 7 (0 main, 1 instrument cluster, 2 auxiliary), or null when the car leaves it out.
     */
    class VideoService(
        val serviceId: Int,
        val codecType: Int,
        val configs: List<VideoConfig>,
        val displayId: Int = 0,
        val displayType: Int? = null,
    ) {
        val codecName: String get() = CODECS[codecType] ?: "unknown($codecType)"
    }

    class AudioConfig(val sampleRate: Int, val bits: Int, val channels: Int)

    class AudioService(
        val serviceId: Int,
        val streamType: Int,
        val codecType: Int,
        val configs: List<AudioConfig>,
    ) {
        val codecName: String get() = CODECS[codecType] ?: "unknown($codecType)"
        val streamName: String get() = STREAMS[streamType] ?: "unknown($streamType)"
    }

    /**
     * Every audio sink the head unit offers (media, guidance, system, telephony). Same
     * MediaSinkService as video, told apart by carrying `audio_type = 2` and
     * `repeated AudioConfiguration audio_configs = 3 { sampling_rate = 1; bits = 2; channels = 3 }`.
     */
    fun findAudioServices(response: ByteArray): List<AudioService> = buildList {
        for (channel in Wire.allBytes(Wire.fields(response), 1)) {
            val service = Wire.fields(channel)
            val id = serviceId(service) ?: continue
            val sink = Wire.bytes(service, 3) ?: continue
            val sinkFields = Wire.fields(sink)
            val audioConfigs = Wire.allBytes(sinkFields, 3)
            if (audioConfigs.isEmpty()) continue
            add(
                AudioService(
                    serviceId = id,
                    streamType = Wire.varint(sinkFields, 2)?.toInt() ?: 0,
                    codecType = Wire.varint(sinkFields, 1)?.toInt() ?: 1,
                    configs = audioConfigs.map {
                        val f = Wire.fields(it)
                        AudioConfig(
                            sampleRate = Wire.varint(f, 1)?.toInt() ?: 48_000,
                            bits = Wire.varint(f, 2)?.toInt() ?: 16,
                            channels = Wire.varint(f, 3)?.toInt() ?: 2,
                        )
                    },
                ),
            )
        }
    }

    /**
     * The media sink to stream to. Gearslip sends raw PCM, so a PCM sink wins: the 2022 Dacia
     * Jogger (LGE ULC 4.5) lists an AAC media sink on channel 3 before a PCM one on channel 5,
     * and PCM pushed into the AAC sink was acked but played as silence. With no PCM sink, the
     * first media sink is the best there is.
     */
    fun pickMediaSink(sinks: List<AudioService>): AudioService? {
        val media = sinks.filter { it.streamType == STREAM_MEDIA }
        return media.firstOrNull { it.codecType == CODEC_PCM } ?: media.firstOrNull()
    }

    /**
     * The channel the car takes "now playing" on (MediaPlaybackStatusService, Service field 9),
     * which feeds its cluster and media screens. It carries no settings. Null when the car has none.
     */
    fun findMediaStatusChannel(response: ByteArray): Int? = findChannelWith(response, 9)

    /**
     * The channel the car takes call status on (PhoneStatusService, Service field 10), for its
     * cluster and phone screens. It carries no settings. Null when the car has none.
     */
    fun findPhoneStatusChannel(response: ByteArray): Int? = findChannelWith(response, 10)

    class NavStatusService(val serviceId: Int, val minimumIntervalMs: Int)

    /** The cluster advertises its minimum update interval in NavigationStatusService field 1. */
    fun findNavStatusService(response: ByteArray): NavStatusService? =
        Wire.allBytes(Wire.fields(response), 1).firstNotNullOfOrNull { channel ->
            val service = Wire.fields(channel)
            val id = serviceId(service) ?: return@firstNotNullOfOrNull null
            val navigation = Wire.bytes(service, 8) ?: return@firstNotNullOfOrNull null
            val interval = Wire.varint(Wire.fields(navigation), 1) ?: return@firstNotNullOfOrNull null
            if (interval !in 0L..Int.MAX_VALUE.toLong()) return@firstNotNullOfOrNull null
            NavStatusService(id, interval.toInt())
        }

    private fun serviceId(fields: List<Wire.Field>): Int? =
        Wire.varint(fields, 1)?.takeIf { it in 1L..255L }?.toInt()

    private fun findChannelWith(response: ByteArray, serviceField: Int): Int? =
        Wire.allBytes(Wire.fields(response), 1).firstNotNullOfOrNull { channel ->
            val service = Wire.fields(channel)
            if (Wire.bytes(service, serviceField) != null) serviceId(service) else null
        }

    /**
     * The car's microphone, from MediaSourceService (Service field 5):
     *   MediaSourceService { MediaCodecType available_type = 1; AudioConfiguration audio_config = 2;
     *                        bool available_while_in_call = 3; }
     * Null when the head unit has none.
     */
    fun findMicService(response: ByteArray): AudioService? {
        for (channel in Wire.allBytes(Wire.fields(response), 1)) {
            val service = Wire.fields(channel)
            val id = serviceId(service) ?: continue
            val source = Wire.fields(Wire.bytes(service, 5) ?: continue)
            val f = Wire.fields(Wire.bytes(source, 2) ?: continue)
            return AudioService(
                serviceId = id,
                streamType = 0,
                codecType = Wire.varint(source, 1)?.toInt() ?: CODEC_PCM,
                configs = listOf(
                    AudioConfig(
                        sampleRate = Wire.varint(f, 1)?.toInt() ?: 16_000,
                        bits = Wire.varint(f, 2)?.toInt() ?: 16,
                        channels = Wire.varint(f, 3)?.toInt() ?: 1,
                    ),
                ),
            )
        }
        return null
    }

    /**
     * The car's Bluetooth, from BluetoothService (Service field 6):
     *   BluetoothService { string car_address = 1; repeated BluetoothPairingMethod methods = 2 [packed]; }
     * Null when the car offers no pairing over the link.
     */
    class BluetoothService(val serviceId: Int, val carAddress: String, val pairingMethods: List<Int>)

    /** A carmaker's own channel (service field 12), for its apps on the phone: GM's is "com.gm.vip". */
    class VendorService(val serviceId: Int, val name: String)

    fun findVendorServices(response: ByteArray): List<VendorService> =
        Wire.allBytes(Wire.fields(response), 1).mapNotNull { channel ->
            val service = Wire.fields(channel)
            val id = Wire.varint(service, 1)?.toInt() ?: return@mapNotNull null
            val fields = Wire.fields(Wire.bytes(service, 12) ?: return@mapNotNull null)
            val name = Wire.bytes(fields, 1)?.let { String(it, Charsets.UTF_8) } ?: return@mapNotNull null
            VendorService(id, name)
        }

    /** The channel of the car's Wi-Fi projection service (Service field 14), or null. */
    fun findWifiProjectionService(response: ByteArray): Int? =
        Wire.allBytes(Wire.fields(response), 1).firstNotNullOfOrNull { channel ->
            val service = Wire.fields(channel)
            if (Wire.bytes(service, 14) == null) null else Wire.varint(service, 1)?.toInt()
        }

    fun findBluetoothService(response: ByteArray): BluetoothService? {
        for (channel in Wire.allBytes(Wire.fields(response), 1)) {
            val service = Wire.fields(channel)
            val id = Wire.varint(service, 1)?.toInt() ?: continue
            val fields = Wire.fields(Wire.bytes(service, 6) ?: continue)
            val address = Wire.bytes(fields, 1)?.let { String(it, Charsets.UTF_8) } ?: continue
            // Packed as declared, though a car may send each method as its own varint.
            val methods = Wire.repeatedInts(fields, 2)
            return BluetoothService(id, address, methods)
        }
        return null
    }

    const val STREAM_GUIDANCE = 1
    const val STREAM_MEDIA = 3
    const val CODEC_PCM = 1
    const val CODEC_AAC_LC = 2
    const val CODEC_AAC_LC_ADTS = 4

    private val STREAMS = mapOf(0 to "NONE", 1 to "GUIDANCE", 2 to "SYSTEM_AUDIO", 3 to "MEDIA", 4 to "TELEPHONY")

    private val RESOLUTIONS = mapOf(
        1 to "800x480", 2 to "1280x720", 3 to "1920x1080", 4 to "2560x1440",
        5 to "3840x2160", 6 to "720x1280", 7 to "1080x1920", 8 to "1440x2560", 9 to "2160x3840",
    )
    private val PIXEL_SIZES = mapOf(
        1 to (800 to 480), 2 to (1280 to 720), 3 to (1920 to 1080), 4 to (2560 to 1440),
        5 to (3840 to 2160), 6 to (720 to 1280), 7 to (1080 to 1920), 8 to (1440 to 2560),
        9 to (2160 to 3840),
    )
    private val CODECS = mapOf(
        1 to "AUDIO_PCM", 2 to "AUDIO_AAC_LC", 3 to "VIDEO_H264_BP",
        4 to "AUDIO_AAC_LC_ADTS", 5 to "VIDEO_VP9", 6 to "VIDEO_AV1", 7 to "VIDEO_H265",
    )

    /**
     * The head unit's touchscreen and button set, from InputSourceService (Service field 4):
     *   InputSourceService { repeated int32 keycodes_supported = 1 [packed=true];
     *                         repeated TouchScreen touchscreen = 2; ... }
     *   TouchScreen { required int32 width = 1; required int32 height = 2; type = 3; }
     *
     * Touch reports use projected video coordinates; these dimensions describe the input
     * hardware and help select a video configuration. [keycodesSupported] is what the
     * head unit says it will actually send over the Input channel as key events (steering-wheel
     * buttons, etc.) - not every unit reports the same set, so it's kept for diagnostics and to
     * know ahead of time whether a button like voice/PTT is even wired up on this hardware.
     */
    class InputService(
        val serviceId: Int,
        val touchWidth: Int,
        val touchHeight: Int,
        val keycodesSupported: List<Int> = emptyList(),
        /** InputSourceService field 5: which screen this input belongs to, 0 for the main one. */
        val displayId: Int = 0,
    )

    /**
     * The input for the screen Gearslip projects to ([displayId]). A head unit with two screens
     * offers an input service for each: LIVI lists an empty one for its second screen first,
     * and taking that one left every touch on the real touchscreen ignored.
     */
    fun findInputService(response: ByteArray, displayId: Int = 0): InputService? {
        val inputs = inputServices(response)
        return inputs.firstOrNull { it.displayId == displayId }
            ?: inputs.firstOrNull { it.touchWidth > 0 && it.touchHeight > 0 }
            ?: inputs.firstOrNull()
    }

    private fun inputServices(response: ByteArray): List<InputService> = buildList {
        for (channel in Wire.allBytes(Wire.fields(response), 1)) {
            val service = Wire.fields(channel)
            val id = serviceId(service) ?: continue
            val input = Wire.bytes(service, 4) ?: continue
            val inputFields = Wire.fields(input)
            // A unit with only a knob and buttons has no touchscreen entry; it still has keys.
            val touchFields = Wire.bytes(inputFields, 2)?.let { Wire.fields(it) }.orEmpty()
            val width = Wire.varint(touchFields, 1)?.toInt() ?: 0
            val height = Wire.varint(touchFields, 2)?.toInt() ?: 0
            val keycodes = Wire.repeatedInts(inputFields, 1)
            add(InputService(id, width, height, keycodes, Wire.varint(inputFields, 5)?.toInt() ?: 0))
        }
    }

    /**
     * The main screen's video sink. A head unit with a second screen, such as LIVI with a
     * cluster display, offers a sink for each; the one without a display id (0) is the main one.
     */
    fun findVideoService(response: ByteArray): VideoService? {
        val sinks = videoServices(response)
        return sinks.firstOrNull { it.displayId == 0 } ?: sinks.firstOrNull()
    }

    /**
     * The instrument cluster's video sink: one marked as a cluster, or, from a car that marks
     * none, a second sink beside the main screen's. Null when the car has no cluster screen.
     */
    fun findClusterVideoService(response: ByteArray): VideoService? {
        val sinks = videoServices(response)
        val main = findVideoService(response) ?: return null
        return sinks.firstOrNull { it.displayType == DISPLAY_TYPE_CLUSTER && it.serviceId != main.serviceId }
            ?: sinks.firstOrNull { it.displayType == null && it.serviceId != main.serviceId && it.displayId != main.displayId }
    }

    private const val DISPLAY_TYPE_CLUSTER = 1

    private fun videoServices(response: ByteArray): List<VideoService> = buildList {
        for (channel in Wire.allBytes(Wire.fields(response), 1)) {
            val service = Wire.fields(channel)
            val id = serviceId(service) ?: continue
            val sink = Wire.bytes(service, 3) ?: continue

            val sinkFields = Wire.fields(sink)
            val videoConfigs = Wire.allBytes(sinkFields, 4)
            if (videoConfigs.isEmpty()) continue // audio sink, not video

            val codecType = Wire.varint(sinkFields, 1)?.toInt() ?: 0
            val configs = videoConfigs.map {
                val f = Wire.fields(it)
                VideoConfig(
                    resolution = Wire.varint(f, 1)?.toInt() ?: 0,
                    frameRate = Wire.varint(f, 2)?.toInt() ?: 0,
                    density = Wire.varint(f, 5)?.toInt() ?: 0,
                    widthMargin = Wire.varint(f, 3)?.toInt() ?: 0,
                    heightMargin = Wire.varint(f, 4)?.toInt() ?: 0,
                )
            }
            add(VideoService(id, codecType, configs, Wire.varint(sinkFields, 6)?.toInt() ?: 0, Wire.varint(sinkFields, 7)?.toInt()))
        }
    }

    /**
     * The head unit's sensor feed, from SensorSourceService (Service field 2; field 5 is the car's mic):
     *   SensorSourceService { repeated Sensor sensors = 1; }
     *   Sensor { required SensorType sensor_type = 1; }
     *
     * SensorBatch contains repeated messages in fields 1 through 22 matching these type ids.
     * The vehicle data screen keeps their raw contents for diagnostics.
     */
    class SensorService(val serviceId: Int, val types: List<Int>)

    fun findSensorService(response: ByteArray): SensorService? {
        for (channel in Wire.allBytes(Wire.fields(response), 1)) {
            val service = Wire.fields(channel)
            val id = serviceId(service) ?: continue
            val sensorSource = Wire.bytes(service, 2) ?: continue
            val types = Wire.allBytes(Wire.fields(sensorSource), 1).mapNotNull {
                Wire.varint(Wire.fields(it), 1)?.toInt()
            }
            if (types.isEmpty()) continue
            return SensorService(id, types)
        }
        return null
    }

    /**
     * Who the head unit says it is, from the top-level strings of ServiceDiscoveryResponse.
     * Field numbers as seen from a 2018 Dodge Challenger: 2="Uconnect", 3="Dodge Challenger",
     * 4="18", 7="Delphi", 8="VP2_R Uconnect 7".
     */
    class HeadUnitInfo(
        val headUnitName: String,
        val carModel: String,
        val carYear: String,
        val headUnitMake: String,
        val headUnitModel: String,
    ) {
        override fun toString() =
            "name=\"$headUnitName\" car=\"$carModel\" year=\"$carYear\" " +
                "make=\"$headUnitMake\" model=\"$headUnitModel\""
    }

    fun findHeadUnitInfo(response: ByteArray): HeadUnitInfo {
        val fields = Wire.fields(response)
        fun text(number: Int) = Wire.bytes(fields, number)?.toString(Charsets.UTF_8).orEmpty()
        return HeadUnitInfo(text(2), text(3), text(4), text(7), text(8))
    }
}
