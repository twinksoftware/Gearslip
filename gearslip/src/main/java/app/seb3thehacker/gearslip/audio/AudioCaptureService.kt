package app.seb3thehacker.gearslip.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Process
import app.seb3thehacker.gearslip.GearslipLog
import kotlin.math.abs

/**
 * Hears one app's audio so it can be played on the car.
 *
 * Playback capture is the one public route to another app's sound, and it hangs off a
 * MediaProjection - so, as with [app.seb3thehacker.gearslip.mirror.MirrorService], the consent
 * result is carried into a foreground service of type mediaProjection before it can be redeemed.
 * Capture is scoped to media playback: notification pings, calls and alarms stay on the phone.
 * The grant lasts as long as this service does, which is as long as the car is connected, so the
 * driver answers the consent dialog once per drive rather than once per app.
 */
class AudioCaptureService : Service() {

    @Volatile private var reading = false
    private var projection: MediaProjection? = null
    private var thread: Thread? = null
    private var mutedPhone = false
    private var muteGuard: BroadcastReceiver? = null

    /**
     * Silence the phone's own speaker while the car is being fed.
     *
     * Playback capture copies the stream instead of moving it, so without this the music plays in
     * two places at once. Muting the stream does not affect what is captured - the tap sits before
     * the volume the speaker gets - so the car still receives full-level audio.
     */
    private fun mutePhone() {
        if (mutedPhone) return
        runCatching {
            getSystemService(android.media.AudioManager::class.java)
                .adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE, 0)
            mutedPhone = true
            GearslipLog.i("audio: muted the phone's speaker for the drive")
        }.onFailure { GearslipLog.w("audio: could not mute the phone: ${it.message}") }
        keepPhoneMuted()
    }

    /**
     * Pressing volume up on a muted phone unmutes it, and the music came out of the phone as well
     * as the car. Mute it again whenever that happens. The keys still move the phone's volume, for
     * after the drive, but the car's level is the head unit's: capture is always full level.
     */
    private fun keepPhoneMuted() {
        if (muteGuard != null) return
        val audio = getSystemService(AudioManager::class.java)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!mutedPhone || audio.isStreamMute(AudioManager.STREAM_MUSIC)) return
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                GearslipLog.i("audio: the phone's speaker came back on - muted it again")
            }
        }
        runCatching {
            registerReceiver(
                receiver,
                IntentFilter(ACTION_VOLUME_CHANGED).apply { addAction(ACTION_MUTE_CHANGED) },
                RECEIVER_NOT_EXPORTED,
            )
            muteGuard = receiver
        }.onFailure { GearslipLog.w("audio: can't keep the phone muted: ${it.message}") }
    }

    private fun unmutePhone() {
        if (!mutedPhone) return
        mutedPhone = false
        muteGuard?.let { runCatching { unregisterReceiver(it) } }
        muteGuard = null
        runCatching {
            getSystemService(android.media.AudioManager::class.java)
                .adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0)
            GearslipLog.i("audio: gave the phone's speaker back")
        }.onFailure { GearslipLog.w("audio: could not unmute the phone: ${it.message}") }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        if (data == null) {
            GearslipLog.e("audio: started without a consent result")
            CarAudio.denied()
            stopSelf()
            return START_NOT_STICKY
        }

        // Android silences the microphone for apps in the background unless a running service
        // claims it. Claiming it here lets voice replies hear the driver with the phone locked.
        // Without the permission, claiming it would crash, so it's left off.
        val micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            (if (micGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        startForeground(NOTIFICATION_ID, notification(), type)
        ScreenShareGuard.lift(this)
        projection = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            GearslipLog.e("audio: getMediaProjection failed", t)
            null
        }
        val token = projection
        if (token == null) {
            CarAudio.denied()
            stopSelf()
            return START_NOT_STICKY
        }
        token.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                GearslipLog.w("audio: projection stopped")
                stopSelf()
            }
        }, null)
        beginCapture(token)
        return START_NOT_STICKY
    }

    private fun beginCapture(token: MediaProjection) {
        val rate = CarAudio.sampleRate
        val channels = CarAudio.channels
        val record = try {
            // Matched by usage, not by app. A uid filter would have to be rebuilt every time the
            // driver changed media app, and rebuilding means a fresh consent dialog - so the
            // filter is what the sound is for, and any app playing media reaches the car.
            val config = AudioPlaybackCaptureConfiguration.Builder(token)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val mask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(mask)
                .build()
            val minimum = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum, CHUNK_BYTES * BUFFER_CHUNKS))
                .build()
        } catch (t: Throwable) {
            GearslipLog.e("audio: could not set up playback capture", t)
            CarAudio.denied()
            stopSelf()
            return
        }

        reading = true
        thread = Thread({
            // A busy phone (a map app drawing, the video encoder) would otherwise starve this
            // loop, and the car hears every late read as a skip.
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ByteArray(CHUNK_BYTES)
            var peak = 0
            var heardAt = System.currentTimeMillis()
            val startedAt = heardAt
            var everHeard = false
            var warnedSilent = false
            try {
                record.startRecording()
                GearslipLog.i("audio: capturing media playback at ${rate}Hz x$channels")
                mutePhone()
                CarAudio.captureStarted()
                while (reading) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) {
                        GearslipLog.e("audio: AudioRecord.read returned $n - capture has stopped")
                        break
                    }
                    for (i in 0 until n - 1 step 2) {
                        val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                        peak = maxOf(peak, abs(sample.toShort().toInt()))
                    }
                    if (peak > 0) everHeard = true
                    val now = System.currentTimeMillis()
                    val level = peak / 32768f
                    if (now - heardAt >= LEVEL_LOG_MS) {
                        GearslipLog.i("audio: peak %.3f".format(level))
                        heardAt = now
                        peak = 0
                    }
                    // Capture never fails for an app that opts out; it just hands back digital
                    // silence. Say so, because the symptom is identical to a broken car link.
                    if (!everHeard && !warnedSilent && now - startedAt >= SILENCE_MS) {
                        warnedSilent = true
                        GearslipLog.w(
                            "audio: ${SILENCE_MS / 1000}s of capture and every sample is zero. " +
                                "Either nothing is playing, or the app does not allow its audio " +
                                "to be captured.",
                        )
                    }
                    CarAudio.deliver(buffer, n, level)
                }
            } catch (t: Throwable) {
                GearslipLog.e("audio: capture loop ended", t)
            } finally {
                runCatching { record.stop() }
                record.release()
                unmutePhone()
                CarAudio.captureStopped()
            }
        }, "gearslip-audio").apply { isDaemon = true; start() }
    }

    override fun onDestroy() {
        super.onDestroy()
        reading = false
        runCatching { thread?.join(500) }
        // Belt and braces: a phone left muted after the drive is a bug the driver blames on their
        // phone, not on Gearslip, so unmute on every way out of here.
        unmutePhone()
        runCatching { projection?.stop() }
        projection = null
        ScreenShareGuard.restore(this)
        running = false
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Car audio", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, AudioCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Playing to the car")
            .setContentText("Gearslip is sending a media app's audio to the car.")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "gearslip_audio"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_STOP = "app.seb3thehacker.gearslip.STOP_AUDIO"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

        /** About 21 ms of 48 kHz stereo: small enough for lip-sync, big enough not to flood the link. */
        private const val CHUNK_BYTES = 4096
        /** About half a second: room to wait out a slow ack from the car without losing sound. */
        private const val BUFFER_CHUNKS = 24
        private const val LEVEL_LOG_MS = 3_000L
        private const val SILENCE_MS = 5_000L

        // AudioManager's own volume broadcasts: hidden from the SDK, but sent on every Android
        // version Gearslip runs on.
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val ACTION_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"

        private var appContext: Context? = null

        /** True between onCreate and onDestroy; lets the activity tell a live capture from a crashed one. */
        @Volatile var running = false
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            appContext = context.applicationContext
            context.startForegroundService(
                Intent(context, AudioCaptureService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data),
            )
        }

        fun stop() {
            val context = appContext ?: return
            context.startService(Intent(context, AudioCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
