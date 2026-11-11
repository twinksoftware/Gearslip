package app.seb3thehacker.gearslip.ui

import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.speech.SpeechModels

import androidx.compose.material3.LinearProgressIndicator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import android.content.Context
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.car.BluetoothAudioCheck
import android.provider.Settings
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.seb3thehacker.gearslip.stats.UsageStats
import kotlinx.coroutines.delay
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.SegmentedButton
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.SessionStatus
import app.seb3thehacker.gearslip.notify.TestMessage
import app.seb3thehacker.gearslip.stats.WorkedPrompt

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenUsageNotes: () -> Unit,
    onOpenCarPreview: () -> Unit,
    onOpenLogs: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenCarGroup: (CarGroup) -> Unit,
) {
    val status by SessionStatus.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val workedCar by remember { WorkedPrompt.pending(context) }.collectAsStateWithLifecycle()
    val live = status.phase == SessionStatus.Phase.CONNECTING ||
        status.phase == SessionStatus.Phase.PROJECTING

    val celebrate by WorkedPrompt.celebrate.collectAsStateWithLifecycle()
    Box {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
            HomeHeader(onOpenSettings)
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 24.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Spacer(Modifier.height(16.dp))
                    StatusCard(status)
                    Spacer(Modifier.height(16.dp))
                    AudioSwitch()
                    if (live) BluetoothMissingCard()
                    if (live) BackgroundLocationCard()
                    BluetoothAddressCard()
                    VoiceDownloadCard()
                    // The first drive that worked asks once to share it, right under the result.
                    val car = workedCar
                    if (car != null && !live) {
                        Spacer(Modifier.height(16.dp))
                        WorkedCard(car, onSeeWhatsSent = onOpenUsageNotes)
                    }
                    Spacer(Modifier.height(16.dp))
                    UpdateCard()
                    Spacer(Modifier.height(24.dp))
                    HomeCarSettings(onOpenCarGroup)
                    Spacer(Modifier.height(16.dp))
                }

                // Sits at the bottom, where a thumb reaches it.
                Column(Modifier.padding(vertical = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeShortcut(Icons.Filled.Info, stringResource(R.string.home_help), onOpenHelp, Modifier.weight(1f))
                        // The preview draws the car screen on the phone, so it waits until no car is using it.
                        if (!live) HomeShortcut(Icons.Filled.PlayArrow, stringResource(R.string.home_preview), onOpenCarPreview, Modifier.weight(1f))
                        HomeShortcut(Icons.AutoMirrored.Filled.List, stringResource(R.string.home_logs), onOpenLogs, Modifier.weight(1f))
                    }
                    if (live) {
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Text(stringResource(R.string.home_disconnect))
                        }
                    }
                }
            }
            }
        }
        // Sharing just went on from the "it worked" ask: a little thanks.
        if (celebrate) ConfettiFall(onDone = WorkedPrompt::celebrated)
        // The usage notes ask comes first; the donation ask waits until that's answered.
        if (workedCar == null) DonateDialog(live)
    }
}

/** Debug builds wear a yellow header, behind the status bar too, so they're never mistaken for a release install. */
internal val DebugYellow = Color(0xFFF5C518)
private val OnDebugYellow = Color(0xFF2A2200)

/** The app's name, version and Settings, fixed above Home's scrolling cards. */
@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    val debug = BuildConfig.DEBUG
    val content = if (debug) OnDebugYellow else MaterialTheme.colorScheme.onBackground
    Surface(color = if (debug) DebugYellow else Color.Transparent, contentColor = content) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Gearslip",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title), modifier = Modifier.size(32.dp))
                }
            }
            val version = "v${LocalContext.current.appVersionName()}"
            Text(
                if (debug) stringResource(R.string.home_debug_build, version) else version,
                style = MaterialTheme.typography.labelLarge,
                color = if (debug) content else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One of Home's shortcuts at the bottom: an icon over a one-word label, small enough that all fit in a row. */
@Composable
private fun HomeShortcut(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** Bluetooth audio is chosen, but the phone isn't connected to the car. See [BluetoothAudioCheck]. */
@Composable
private fun BluetoothMissingCard() {
    val missing by BluetoothAudioCheck.missing.collectAsStateWithLifecycle()
    val usb by CarSettings.pipeAudio.collectAsStateWithLifecycle()
    val warn by CarSettings.bluetoothWarning.collectAsStateWithLifecycle()
    if (!missing || usb || !warn) return
    val context = LocalContext.current
    // Yellow, not the theme's error red: music is missing, but nothing is broken. Material has no
    // warning colour, so these are fixed amber tones for light and dark.
    val dark = gearslipDark()
    val container = if (dark) Color(0xFF4A3B00) else Color(0xFFFFE08A)
    val content = if (dark) Color(0xFFFFE08A) else Color(0xFF3A2E00)
    Spacer(Modifier.height(16.dp))
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_bt_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.home_bt_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = content, contentColor = container),
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
                modifier = Modifier.padding(top = 4.dp),
            ) { Text(stringResource(R.string.bluetooth_settings)) }
        }
    }
}

/**
 * Asks for "Allow all the time" location, shown only while a car is connected (issue #99).
 * Without it, a car that connects with the phone locked leaves the car's map without GPS
 * until Gearslip is opened. Once granted, the location service starts at once.
 */
@Composable
private fun BackgroundLocationCard() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(app.seb3thehacker.gearslip.host.LocationKeepAlive.allowedInBackground(context)) }
    var dismissed by remember { mutableStateOf(false) }
    if (allowed || dismissed) return
    fun recheck() {
        allowed = app.seb3thehacker.gearslip.host.LocationKeepAlive.allowedInBackground(context)
        if (allowed) app.seb3thehacker.gearslip.host.LocationKeepAlive.retry(context)
    }
    // On Android 11 and later this opens the app's location settings, where "Allow all the time" is.
    val askBackground = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { recheck() }
    // Android only offers all the time once the ordinary location permission is granted.
    val askForeground = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[android.Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            askBackground.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    val dark = gearslipDark()
    val container = if (dark) Color(0xFF4A3B00) else Color(0xFFFFE08A)
    val content = if (dark) Color(0xFFFFE08A) else Color(0xFF3A2E00)
    Spacer(Modifier.height(16.dp))
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_location_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.home_location_body), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = content, contentColor = container),
                    onClick = {
                        val fine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (fine) askBackground.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        else askForeground.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                ) { Text(stringResource(R.string.home_location_allow)) }
                androidx.compose.material3.TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = content),
                    onClick = { dismissed = true },
                ) { Text(stringResource(R.string.home_location_not_now)) }
            }
        }
    }
}

/** While the voice models download, how far along they are; nothing otherwise. */
@Composable
private fun VoiceDownloadCard() {
    val progress by SpeechModels.progress.collectAsStateWithLifecycle()
    val percent = progress ?: return
    Spacer(Modifier.height(16.dp))
    SettingsCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.home_voice_downloading, percent), style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Arrow() = Icon(
    Icons.AutoMirrored.Filled.KeyboardArrowRight,
    contentDescription = null,
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * Where the car's sound comes from, switchable in one tap from Home: Bluetooth, or through
 * Gearslip over the car link. It takes effect at once when a car is connected.
 */
@Composable
private fun AudioSwitch() {
    val pipe by app.seb3thehacker.gearslip.car.CarSettings.pipeAudio.collectAsState()
    val thisCar by app.seb3thehacker.gearslip.car.CarSettings.thisCarPipes.collectAsState()
    val through = pipe || thisCar
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.car_audio),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to R.string.guide_audio_bluetooth, true to R.string.guide_audio_usb).forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = through == value,
                    onClick = { app.seb3thehacker.gearslip.car.CarSettings.setPipeAudio(value) },
                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }
    }
}

@Composable
private fun StatusCard(status: SessionStatus.Snapshot) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (status.phase) {
        SessionStatus.Phase.PROJECTING -> scheme.primaryContainer to scheme.onPrimaryContainer
        SessionStatus.Phase.CONNECTING -> scheme.secondaryContainer to scheme.onSecondaryContainer
        SessionStatus.Phase.FAILED -> scheme.errorContainer to scheme.onErrorContainer
        SessionStatus.Phase.IDLE, SessionStatus.Phase.DISCONNECTED ->
            scheme.surfaceContainerHigh to scheme.onSurface
    }

    val live = status.phase == SessionStatus.Phase.CONNECTING || status.phase == SessionStatus.Phase.PROJECTING
    val context = LocalContext.current
    val grapheneOs = remember { UsageStats.isGrapheneOs(context) }
    val noCar = chargingWithNoCar(status.phase == SessionStatus.Phase.IDLE)
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraLarge) {
      Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (status.phase == SessionStatus.Phase.CONNECTING) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = content)
            } else {
                Surface(
                    modifier = Modifier.size(14.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (status.phase == SessionStatus.Phase.IDLE ||
                        status.phase == SessionStatus.Phase.DISCONNECTED
                    ) content.copy(alpha = 0.4f) else content,
                ) {}
            }
            Column {
                // The steady states read from strings.xml; a stage or a failure comes with its own text.
                val headline = when (status.phase) {
                    SessionStatus.Phase.IDLE -> stringResource(R.string.status_idle)
                    SessionStatus.Phase.CONNECTING -> stringResource(R.string.status_connecting)
                    SessionStatus.Phase.PROJECTING -> stringResource(R.string.status_projecting)
                    SessionStatus.Phase.DISCONNECTED -> stringResource(R.string.status_disconnected)
                    SessionStatus.Phase.FAILED -> status.headline
                }
                val detail = when (status.phase) {
                    SessionStatus.Phase.IDLE -> stringResource(
                        when {
                            !noCar -> R.string.status_idle_detail
                            grapheneOs -> R.string.status_no_car_graphene
                            else -> R.string.status_no_car
                        },
                    )
                    SessionStatus.Phase.PROJECTING -> stringResource(R.string.status_projecting_detail)
                    SessionStatus.Phase.DISCONNECTED -> stringResource(R.string.status_disconnected_detail)
                    SessionStatus.Phase.CONNECTING -> when (status.stuckOn) {
                        SessionStatus.Link.CABLE -> stringResource(R.string.status_stuck_cable)
                        SessionStatus.Link.WIRELESS -> stringResource(R.string.status_stuck_wireless)
                        null -> status.detail
                    }
                    else -> status.detail
                }
                Text(headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // GrapheneOS keeps its USB-C port setting under Security & privacy.
        if (noCar && grapheneOs) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            }) { Text(stringResource(R.string.status_open_settings)) }
        }
        // The other way in: wireless, right under the cable's instructions.
        if (!live) {
            Spacer(Modifier.height(20.dp))
            WirelessButton()
        }
      }
    }
}

/**
 * True once the phone has charged over a cable for [NO_CAR_MS] while Gearslip sat idle. With
 * USB data blocked, by a charge-only cable or GrapheneOS's port setting, Android never tells
 * Gearslip a car is there, so charging with no car is the only sign there is. A wall charger
 * looks the same, which is why the hint only asks the driver to check.
 */
@Composable
private fun chargingWithNoCar(idle: Boolean): Boolean {
    val context = LocalContext.current
    var plugged by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val how = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                plugged = how == BatteryManager.BATTERY_PLUGGED_USB || how == BatteryManager.BATTERY_PLUGGED_AC
            }
        }
        // The battery broadcast is sticky, so registering hands back the current state at once.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { receiver.onReceive(context, it) }
        onDispose { context.unregisterReceiver(receiver) }
    }
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(idle && plugged) {
        waited = false
        if (idle && plugged) { delay(NO_CAR_MS); waited = true }
    }
    return idle && plugged && waited
}

private const val NO_CAR_MS = 10_000L

/** The version shown on screen, straight from the manifest - one place to bump per release. */
private fun Context.appVersionName(): String =
    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

