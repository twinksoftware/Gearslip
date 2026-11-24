package app.seb3thehacker.gearslip

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.seb3thehacker.gearslip.audio.AudioCaptureService
import app.seb3thehacker.gearslip.audio.CarAudio
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.mirror.MirrorService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A see-through screen of no content, in a task of its own, for what the car needs of the phone
 * and only an activity can do: a car that came up by itself, and Android's consent to capture the
 * music or the screen. Opening [GearslipActivity] for these showed Android's launch splash and a
 * frame of Gearslip each time; a see-through activity gets no splash, and this one draws nothing.
 * Android's own dialogs still show over it when it has to ask.
 */
class CarPromptActivity : ComponentActivity() {

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> askAudioCapture()
            !shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO) -> {
                // Refused twice: Android now says no without showing anything, so the phone must.
                GearslipLog.w("audio: record permission blocked - Android no longer asks")
                CarAudio.micBlocked()
                openGearslip(GearslipActivity.EXTRA_MIC_BLOCKED)
            }
            else -> {
                GearslipLog.w("audio: record permission declined")
                CarAudio.denied()
                done()
            }
        }
    }

    private val audioConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            AudioCaptureService.start(this, result.resultCode, data)
            // The capture service claims the microphone, which Android allows only while one of
            // Gearslip's screens is up, so this one stays until the capture has started.
            lifecycleScope.launch {
                withTimeoutOrNull(CAPTURE_WAIT_MS) { CarAudio.capture.first { it == CarAudio.Capture.ON || it == CarAudio.Capture.DENIED } }
                done()
            }
        } else {
            GearslipLog.w("audio: capture consent declined")
            CarAudio.denied()
            if (!CarSettings.audioChoiceAsked) openGearslip(GearslipActivity.EXTRA_OFFER_BLUETOOTH) else done()
        }
    }

    private val mirrorConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            MirrorService.start(this, result.resultCode, data)
        } else {
            GearslipLog.w("screen capture consent declined")
        }
        done()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarSession.init(this)
        if (savedInstanceState != null) return // a result is on its way to the launcher above
        when (intent?.action) {
            app.seb3thehacker.gearslip.wireless.WirelessAuto.ACTION_CAR_NEARBY -> {
                CarSession.connectToNearbyCar(intent.getStringExtra(app.seb3thehacker.gearslip.wireless.WirelessAuto.EXTRA_ADDRESS))
                // A screen of Gearslip's is up for this moment, which a map's location needs.
                CarSession.holdLocation(this)
                done()
            }
            ACTION_AUDIO_CONSENT -> {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    askAudioCapture()
                } else {
                    audioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }
            ACTION_MIRROR_CONSENT -> {
                val manager = getSystemService(MediaProjectionManager::class.java)
                runCatching { mirrorConsent.launch(manager.createScreenCaptureIntent()) }
                    .onFailure {
                        GearslipLog.e("could not ask for screen capture", it)
                        done()
                    }
            }
            else -> done()
        }
    }

    private fun askAudioCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // The whole screen, so the dialog offers no app to pick: sharing one app brings that app
        // in front, which put Gearslip behind it before its capture service could start.
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        runCatching { audioConsent.launch(intent) }
            .onFailure {
                GearslipLog.e("could not ask for audio capture", it)
                CarAudio.denied()
                done()
            }
    }

    /** A question only Gearslip's own screen can put to the driver. */
    private fun openGearslip(extra: String) {
        startActivity(Intent(this, GearslipActivity::class.java).putExtra(extra, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        done()
    }

    private fun done() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val ACTION_AUDIO_CONSENT = "app.seb3thehacker.gearslip.AUDIO_CONSENT"
        const val ACTION_MIRROR_CONSENT = "app.seb3thehacker.gearslip.MIRROR_CONSENT"

        /** Longest this stays up for the capture service to claim the microphone. */
        private const val CAPTURE_WAIT_MS = 5_000L

        /** Opens this for [action], with no animation. */
        fun ask(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            runCatching {
                context.startActivity(
                    Intent(context, CarPromptActivity::class.java).setAction(action).apply(extras)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    android.app.ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle(),
                )
            }.onFailure { GearslipLog.w("could not open the car's prompt ($action): ${it.message}") }
        }
    }
}
