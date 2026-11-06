package app.seb3thehacker.gearslip.ui

import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.CertProvider
import app.seb3thehacker.gearslip.stats.UsageStats

private enum class Screen { HOME, LOGS, SETTINGS, CAR_PREVIEW, CERT_SETUP, SETUP_GUIDE, USAGE_NOTES, HELP, WHATS_NEW, RECOMMENDED_APPS, CAR_GROUP, SAVED_PLACES }

/** Three screens and a back stack of depth one: no navigation library needed. */
@Composable
fun GearslipApp(onDisconnect: () -> Unit, onRequestCallScreening: () -> Unit) {
    val context = LocalContext.current
    val needsCertSetup = remember {
        !AppSettings.hasSkippedCertSetup(context) && !CertProvider.hasAnyCert(context)
    }
    val needsSetupGuide = remember { !AppSettings.hasSeenPermissionsSetup(context) }
    // Where launch goes: setup until it's done, then Home.
    val start = remember {
        when {
            needsCertSetup -> Screen.CERT_SETUP
            needsSetupGuide -> Screen.SETUP_GUIDE
            // Set up before usage notes existed: ask once, on the setup guide's own page.
            !UsageStats.asked(context) -> Screen.USAGE_NOTES
            else -> Screen.HOME
        }
    }
    var screen by rememberSaveable { mutableStateOf(start) }
    // Home's "it worked" card opens Settings at usage notes rather than at the top.
    var settingsAtUsageNotes by rememberSaveable { mutableStateOf(false) }
    // What's new and Connection help open from Home and from Settings; back returns to whichever.
    var backTo by rememberSaveable { mutableStateOf(Screen.SETTINGS) }
    // Which of the car's setting groups Home opened.
    var carGroup by rememberSaveable { mutableStateOf(CarGroup.SCREEN) }
    BackHandler(enabled = screen != Screen.HOME) {
        screen = when (screen) {
            Screen.RECOMMENDED_APPS -> Screen.SETTINGS
            // The same place their own back arrows go: Home or Settings, whichever opened them.
            Screen.HELP, Screen.WHATS_NEW, Screen.LOGS, Screen.CAR_PREVIEW -> backTo
            else -> Screen.HOME
        }
    }

    // Home stays upright; every other screen turns with the phone.
    LaunchedEffect(screen) {
        (context as? Activity)?.requestedOrientation = if (screen == Screen.HOME) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // The guide asks for this now, but anyone who finished it before that never saw the page.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // Reads the certificate once in the background, so Settings has it ready when opened.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { CertSummary.read(context) } }
    }
    LaunchedEffect(Unit) {
        if (!needsSetupGuide &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // On a debug build, Home's yellow header runs up behind the status bar, whose icons then go dark.
    val debugHeader = BuildConfig.DEBUG && screen == Screen.HOME
    val view = LocalView.current
    val darkTheme = gearslipDark()
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = debugHeader || !darkTheme
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(if (debugHeader) DebugYellow else Color.Transparent),
        )

        Column(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    onOpenSettings = { settingsAtUsageNotes = false; screen = Screen.SETTINGS },
                    onOpenHelp = { backTo = Screen.HOME; screen = Screen.HELP },
                    onOpenUsageNotes = { settingsAtUsageNotes = true; screen = Screen.SETTINGS },
                    onOpenCarPreview = { backTo = Screen.HOME; screen = Screen.CAR_PREVIEW },
                    onOpenLogs = { backTo = Screen.HOME; screen = Screen.LOGS },
                    onDisconnect = onDisconnect,
                    onOpenCarGroup = {
                        if (it == CarGroup.PLACES) {
                            screen = Screen.SAVED_PLACES
                        } else {
                            carGroup = it; screen = Screen.CAR_GROUP
                        }
                    },
                )
                Screen.CAR_GROUP -> CarGroupScreen(carGroup, onBack = { screen = Screen.HOME })
                Screen.WHATS_NEW -> WhatsNewScreen(onBack = { screen = backTo })
                Screen.LOGS -> LogsScreen(onBack = { screen = backTo })
                Screen.SETTINGS -> SettingsScreen(
                    onBack = { screen = Screen.HOME },
                    onOpenRecommendedApps = { settingsAtUsageNotes = false; screen = Screen.RECOMMENDED_APPS },
                    onReplayTutorial = { screen = Screen.SETUP_GUIDE },
                    atUsageNotes = settingsAtUsageNotes,
                )
                Screen.RECOMMENDED_APPS -> RecommendedAppsScreen(onBack = { screen = Screen.SETTINGS })
                Screen.SAVED_PLACES -> SavedPlacesScreen(onBack = { screen = Screen.HOME })
                Screen.CAR_PREVIEW -> CarPreviewScreen(onBack = { screen = backTo })
                Screen.HELP -> HelpScreen(
                    onBack = { screen = backTo },
                    onRequestCallScreening = onRequestCallScreening,
                )
                Screen.CERT_SETUP -> CertSetupScreen(
                    onDone = {
                        screen = if (AppSettings.hasSeenPermissionsSetup(context)) Screen.HOME else Screen.SETUP_GUIDE
                    },
                )
                Screen.SETUP_GUIDE -> SetupGuideScreen(
                    onRequestCallScreening = onRequestCallScreening,
                    onDone = { screen = Screen.HOME },
                )
                Screen.USAGE_NOTES -> SetupGuideScreen(
                    onRequestCallScreening = onRequestCallScreening,
                    onDone = { screen = Screen.HOME },
                    onlyUsageNotes = true,
                )
            }
        }
        // A car keyboard is open: offer the phone's own, for a passenger. In the car preview it is
        // the only keyboard, since the preview draws none of its own.
        PhoneTypingBar()
    }
}
