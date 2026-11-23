package app.seb3thehacker.gearslip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.MotionEvent
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import android.content.res.Configuration
import app.seb3thehacker.gearslip.audio.AudioCaptureService
import app.seb3thehacker.gearslip.audio.CarAudio
import app.seb3thehacker.gearslip.mirror.MirrorService
import app.seb3thehacker.gearslip.mirror.PhoneMirror
import app.seb3thehacker.gearslip.mirror.TouchRelayService
import app.seb3thehacker.gearslip.car.CarEnvironment
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.car.CarUi
import app.seb3thehacker.gearslip.car.Prefetch
import app.seb3thehacker.gearslip.stats.UsageStats
import app.seb3thehacker.gearslip.ui.GearslipApp
import app.seb3thehacker.gearslip.ui.GearslipTheme
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Hosts the phone UI and, for now, the car session itself.
 *
 * Launched by the system when the head unit switches the phone into accessory mode. The
 * session lives in this activity, so [android:configChanges] in the manifest keeps a
 * rotation or theme change from recreating it and dropping the connection.
 */
class GearslipActivity : ComponentActivity() {

    /** Raised when the microphone permission is blocked; see [MicBlockedDialog]. */
    private val micBlockedNotice = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Raised once, after the first "no" to the capture dialog; see [AudioChoiceDialog]. */
    private val offerBluetooth = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Android's one-time "Allow" for a car to connect by itself; see [WirelessAuto]. */
    private var approvingCar: String? = null

    /** Whether the phone showed Gearslip when the car's intent arrived; see [stepAside]. */
    private var shownOnPhone = false
    private var stepAsideOnVideo = false
    private val carApproval = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val address = approvingCar ?: return@registerForActivityResult
        approvingCar = null
        if (result.resultCode == RESULT_OK) app.seb3thehacker.gearslip.wireless.WirelessAuto.approved(this, address)
        else app.seb3thehacker.gearslip.wireless.WirelessAuto.declined(this, address)
    }

    /** Setting Gearslip as the phone's caller ID and spam app - what lets it decline a ringing call. */
    private val callScreeningRole = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) GearslipLog.w("calls: call screening role declined")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Location, calendar, the phone/contacts group, and the microphone are asked for one at
        // a time by PermissionsSetupScreen instead of here, each only after the driver taps
        // Allow on a screen explaining what it is for. Firing them all from onCreate, unprompted,
        // is both a bad first impression and unreliable: Android does not consistently show more
        // than one system permission dialog fired back-to-back, which was silently dropping some.
        enableEdgeToEdge()

        // The car session itself lives in CarSession, which outlives this screen.
        CarSession.init(this)
        CarSession.onCarScreenUp = { if (stepAsideOnVideo) stepAside() }
        app.seb3thehacker.gearslip.wireless.WirelessAuto.showApproval = { chooser, address ->
            runOnUiThread {
                approvingCar = address
                carApproval.launch(androidx.activity.result.IntentSenderRequest.Builder(chooser).build())
            }
        }
        Prefetch.warm(this)

        setContent {
            GearslipTheme {
                GearslipApp(onDisconnect = { CarSession.close() }, onRequestCallScreening = ::requestCallScreeningRole)
                val offer by offerBluetooth.collectAsState()
                if (offer) {
                    fun close() {
                        CarSettings.markAudioChoiceAsked()
                        offerBluetooth.value = false
                    }
                    app.seb3thehacker.gearslip.ui.AudioChoiceDialog(
                        onUseBluetooth = {
                            CarSettings.setPipeAudio(false)
                            close()
                        },
                        onKeepAsking = ::close,
                    )
                }
                val micNotice by micBlockedNotice.collectAsState()
                if (micNotice) {
                    app.seb3thehacker.gearslip.ui.MicBlockedDialog(
                        onOpenSettings = {
                            micBlockedNotice.value = false
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                            )
                        },
                        onDismiss = { micBlockedNotice.value = false },
                    )
                }
            }
        }

        handleIntent(intent)
    }

    // The manifest opts out of recreation on uiMode changes, so a light/dark switch on the phone
    // arrives here, and the car UI follows it.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        CarEnvironment.setPhoneTheme(newConfig)
    }

    // Throttled inside Prefetch, so this only does work if the last run was a while ago.
    override fun onStart() {
        super.onStart()
        Prefetch.warm(this)
        UsageStats.onAppOpened(this)
        app.seb3thehacker.gearslip.update.UpdateChecker.onAppOpened(this)
        // Back from the app's settings with the microphone turned on: try car audio again.
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            CarAudio.micAllowed()
        }
    }

    override fun onResume() {
        super.onResume()
        shownOnPhone = true
    }

    override fun onStop() {
        super.onStop()
        shownOnPhone = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // The session carries on without this screen, as it does with the phone locked.
        app.seb3thehacker.gearslip.wireless.WirelessAuto.showApproval = null
        CarSession.onCarScreenUp = null
        GearslipLog.flush()
    }

    /** Launches the system dialog that offers Gearslip the call-screening role, if it isn't held already. */
    private fun requestCallScreeningRole() {
        val roleManager = getSystemService(android.app.role.RoleManager::class.java)
        if (roleManager == null || !roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_SCREENING)) {
            GearslipLog.w("calls: call screening role unavailable on this phone")
            return
        }
        if (roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)) return
        runCatching {
            callScreeningRole.launch(roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING))
        }.onFailure { GearslipLog.e("could not ask for the call screening role", it) }
    }

    private fun handleIntent(intent: Intent?) {
        // Debug builds only: opens the audio choice for a look, without turning down a real prompt.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("show_audio_choice", false) == true) offerBluetooth.value = true
        // Questions CarPromptActivity can't put without a screen of Gearslip's own.
        if (intent?.getBooleanExtra(EXTRA_OFFER_BLUETOOTH, false) == true) offerBluetooth.value = true
        if (intent?.getBooleanExtra(EXTRA_MIC_BLOCKED, false) == true) micBlockedNotice.value = true
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        // Switched off in Settings: the system shouldn't route the car here at all, but if it
        // does, leave the accessory alone.
        if (!AppSettings.gearslipEnabled(this)) {
            GearslipLog.i("accessory attached, but Gearslip is switched off - not connecting")
            return
        }
        val accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        if (accessory == null) {
            GearslipLog.e("attach intent carried no accessory")
            SessionStatus.failed("Accessory missing", "The system reported a USB attach but did not include the accessory.")
            return
        }
        GearslipLog.i("accessory attached by the system - permission is implicit on this path")
        stepAsideOnVideo = !shownOnPhone
        CarSession.startAccessory(accessory)
        // The car just opened this screen, which a map's location needs; see CarSession.holdLocation.
        CarSession.holdLocation(this)
    }

    /**
     * Android hands a USB car only to an activity, so the car opens Gearslip on the phone. Once
     * the car's screen is up, go back to whatever the phone showed, as Home would; the session
     * carries on as it does with the phone locked. A question for the driver keeps Gearslip in
     * front, and so does the phone already showing it.
     */
    private fun stepAside() {
        stepAsideOnVideo = false
        if (offerBluetooth.value || micBlockedNotice.value || approvingCar != null) return
        GearslipLog.i("the car's screen is up - moving Gearslip off the phone's screen")
        moveTaskToBack(true)
    }

    companion object {
        /** From [CarPromptActivity]: offer Bluetooth audio after the first "no" to the capture dialog. */
        const val EXTRA_OFFER_BLUETOOTH = "offer_bluetooth"
        /** From [CarPromptActivity]: the microphone permission is blocked; say how to turn it on. */
        const val EXTRA_MIC_BLOCKED = "mic_blocked"
    }
}
