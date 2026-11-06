package app.seb3thehacker.gearslip.audio

import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Joins the two ends of the audio path: [AudioCaptureService] on the phone, which hears what the
 * phone is playing, and the car's audio channel, which is where it has to end up.
 *
 * Capture belongs to the car session, not to any one app. Android grants it through a consent
 * dialog that cannot be answered in advance or remembered, so Gearslip asks once, when a head unit
 * first offers a sink to play through, and then holds that grant for as long as the car is
 * connected. Switching between media apps, or starting playback from the phone, costs nothing
 * further: the capture is by audio usage rather than by app, so whatever the phone plays as media
 * goes to the car.
 */
object CarAudio {

    /** What the head unit's media sink accepts, and how to hand it audio. */
    interface Link {
        val sampleRate: Int
        val channels: Int
        fun start()
        fun stop()
        fun write(pcm: ByteArray, size: Int)
    }

    enum class Capture { OFF, ASKING, ON, DENIED }

    private val _capture = MutableStateFlow(Capture.OFF)
    val capture: StateFlow<Capture> = _capture.asStateFlow()

    /** "-12 dB" style summary of what was last heard, for the media screen. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Set by the activity: shows the permission and consent dialogs, which only it can. */
    var onConsentRequested: (() -> Unit)? = null

    /** Whether the driver has asked for the car to be fed at all; see CarSettings.pipeAudio. */
    @Volatile private var wanted = false

    @Volatile private var link: Link? = null

    /** Opens the car's media channel, left closed while music went over Bluetooth; null once open. */
    @Volatile var openSink: (() -> Unit)? = null

    /** The car offers a music channel, open or waiting to be. */
    val sinkOffered: Boolean get() = link != null || openSink != null

    /** Rate and channel count the capture should use: the car's, or a sensible default. */
    val sampleRate get() = link?.sampleRate ?: 48_000
    val channels get() = link?.channels ?: 2

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * The car left and none came back: stop capturing, which ends the screen sharing Android
     * shows for it and gives the phone its speaker back. Nothing else stopped it once the drive
     * was over; with a wireless dongle Gearslip's screen stays open, so it ran on (issue #43).
     */
    private val releaseWhenGone = Runnable {
        if (link != null || _capture.value == Capture.OFF) return@Runnable
        GearslipLog.i("audio: no car for ${CAR_GONE_MS / 1000}s - stopping the capture and screen sharing")
        release()
    }

    fun attach(link: Link) {
        main.removeCallbacks(releaseWhenGone)
        this.link = link
        GearslipLog.i("audio: the car offered a sink at ${link.sampleRate}Hz x${link.channels}")
        when {
            _capture.value == Capture.ON -> link.start()
            wanted -> ask()
        }
    }

    /**
     * The car's music channel closed. A car that comes straight back (a cable that slips) keeps
     * the capture it had, so the driver isn't asked again mid-drive.
     */
    fun detach(link: Link) {
        if (this.link !== link) return
        this.link = null
        main.removeCallbacks(releaseWhenGone)
        main.postDelayed(releaseWhenGone, CAR_GONE_MS)
    }

    /**
     * How long after the car leaves the capture waits for it to come back: a cable that slips, or a
     * wireless link that drops and reconnects, which can take well over 3 seconds. Stopping sooner
     * would have Android ask the driver to share the screen again in the middle of a drive.
     */
    private const val CAR_GONE_MS = 30_000L

    /**
     * The car wants sound. Safe to call as often as the session likes: the consent dialog appears
     * at most once, and only once there is a sink to play through - asking on the bench, where the
     * sound has nowhere to go, would put a system dialog in front of the driver for nothing.
     */
    fun request() {
        wanted = true
        when (_capture.value) {
            Capture.ON, Capture.ASKING, Capture.DENIED -> return
            Capture.OFF -> if (link == null) {
                openSink?.invoke()
                GearslipLog.i("audio: waiting for the car to offer an audio sink")
            } else {
                ask()
            }
        }
    }

    private fun ask() {
        _capture.value = Capture.ASKING
        val request = onConsentRequested
        if (request == null) {
            GearslipLog.w("audio: nothing on the phone can ask for capture consent")
            _capture.value = Capture.DENIED
        } else {
            request()
        }
    }

    /**
     * Capture has heard nothing for a while. If a media app that blocks capture is the one
     * playing, the car screen says why the music is silent, once a drive: Spotify does this.
     */
    internal fun silent(context: android.content.Context) = android.os.Handler(android.os.Looper.getMainLooper()).post {
        if (toldBlocked) return@post
        val media = app.seb3thehacker.gearslip.car.CarServices.mediaOrNull() ?: return@post
        if (!media.now.value.playing) return@post
        val name = BLOCKS_CAPTURE[media.packageName] ?: return@post
        toldBlocked = true
        GearslipLog.i("audio: $name is playing and capture hears nothing: it blocks other apps from playing its sound")
        app.seb3thehacker.gearslip.car.CarToasts.show(this, context.getString(app.seb3thehacker.gearslip.R.string.car_audio_blocked, name), androidx.car.app.CarToast.LENGTH_LONG)
    }

    @Volatile private var toldBlocked = false

    /** Media apps known to keep other apps from capturing their sound, by package. */
    private val BLOCKS_CAPTURE = mapOf("com.spotify.music" to "Spotify")

    /** The car went away. Everything is torn down; the next connection asks again. */
    fun release() {
        main.removeCallbacks(releaseWhenGone)
        wanted = false
        toldBlocked = false
        AudioCaptureService.stop()
        _capture.value = Capture.OFF
    }

    fun denied() {
        _capture.value = Capture.DENIED
    }

    private val _micBlocked = MutableStateFlow(false)

    /** The microphone permission is off for good: Android refuses without asking, so say why. */
    val micBlocked: StateFlow<Boolean> = _micBlocked.asStateFlow()

    fun micBlocked() {
        _micBlocked.value = true
        denied()
    }

    /** The driver turned the permission back on mid-drive: ask for capture again. */
    fun micAllowed() {
        if (!_micBlocked.value) return
        _micBlocked.value = false
        if (_capture.value == Capture.DENIED) {
            _capture.value = Capture.OFF
            if (wanted) request()
        }
    }

    internal fun captureStarted() {
        _capture.value = Capture.ON
        link?.start()
    }

    internal fun captureStopped() {
        link?.stop()
        _level.value = 0f
        if (_capture.value == Capture.ON) _capture.value = Capture.OFF
    }

    internal fun deliver(pcm: ByteArray, size: Int, peak: Float) {
        _level.value = peak
        val voice = synchronized(voiceLock) { spoken }
        if (voice == null) link?.write(pcm, size) else link?.write(mixVoice(pcm, size), size)
    }

    private val voiceLock = Any()
    private var spoken: ByteArray? = null
    private var spokenAt = 0
    private var spokenDone: java.util.concurrent.CountDownLatch? = null

    /**
     * Mixes a spoken turn into the music from the next chunk on, with the music turned down
     * under it, and waits until it has all gone out. [pcm] is 16-bit, in the sink's own format.
     * False when no music is flowing to carry it, so the caller sends it some other way.
     */
    fun speakOverMusic(pcm: ByteArray): Boolean {
        if (_capture.value != Capture.ON || (link as? AudioLink)?.streaming != true) return false
        val done = java.util.concurrent.CountDownLatch(1)
        synchronized(voiceLock) {
            spokenDone?.countDown()
            spoken = pcm
            spokenAt = 0
            spokenDone = done
        }
        val ms = pcm.size * 1000L / (2 * channels * sampleRate)
        if (!done.await(ms + VOICE_SLACK_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            // Capture stopped mid-turn; the rest of it is dropped rather than played late.
            synchronized(voiceLock) { if (spokenDone === done) spoken = null }
        }
        return true
    }

    private fun mixVoice(pcm: ByteArray, size: Int): ByteArray = synchronized(voiceLock) {
        val voice = spoken ?: return pcm
        val out = pcm.copyOf(size)
        var i = 0
        while (i + 1 < size) {
            val music = (out[i].toInt() and 0xFF) or (out[i + 1].toInt() shl 8)
            val word = if (spokenAt + 1 < voice.size) (voice[spokenAt].toInt() and 0xFF) or (voice[spokenAt + 1].toInt() shl 8) else 0
            val mixed = (music * DUCK_PERCENT / 100 + word).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = mixed.toByte()
            out[i + 1] = (mixed shr 8).toByte()
            i += 2
            spokenAt += 2
        }
        if (spokenAt >= voice.size) {
            spoken = null
            spokenDone?.countDown()
            spokenDone = null
        }
        out
    }

    /** How loud the music stays under a spoken turn. */
    private const val DUCK_PERCENT = 30
    private const val VOICE_SLACK_MS = 2_000L
}
