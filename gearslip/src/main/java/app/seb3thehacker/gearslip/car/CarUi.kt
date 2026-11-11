package app.seb3thehacker.gearslip.car

import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.filled.Call
import app.seb3thehacker.gearslip.call.CarCalls
import app.seb3thehacker.gearslip.call.CallUiState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.BoxScope
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import app.seb3thehacker.gearslip.host.CarAppConnection
import app.seb3thehacker.gearslip.host.display
import app.seb3thehacker.gearslip.host.text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateContentSize
import android.media.session.PlaybackState
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.car.theme.GsColors
import app.seb3thehacker.gearslip.car.theme.withTintedSurfaces
import app.seb3thehacker.gearslip.car.theme.GsIconBox
import app.seb3thehacker.gearslip.car.theme.GsIconButton
import app.seb3thehacker.gearslip.car.theme.GsButton
import app.seb3thehacker.gearslip.car.theme.GsTone
import app.seb3thehacker.gearslip.car.theme.gsColors
import app.seb3thehacker.gearslip.car.theme.GsThemes
import app.seb3thehacker.gearslip.car.theme.LocalGsTheme
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.MediaArt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.notify.CarNotifications
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * The whole car screen: the current screen on top, the nav bar along the bottom.
 *
 * Colours follow the phone's theme (Material You). The size setting scales everything by
 * changing the density, so text, icons and touch targets grow together.
 */
@Composable
fun CarUi() {
    val scale by CarSettings.scale.collectAsState()
    val phoneDark by CarEnvironment.phoneDark.collectAsState()
    val appTheme by CarSettings.appTheme.collectAsState()
    val uiThemeId by CarSettings.uiTheme.collectAsState()
    val darkStyle by CarSettings.darkStyle.collectAsState()
    val mediaApp by CarServices.mediaApp.collectAsState()
    val darkOutside by CarEnvironment.darkOutside.collectAsState()
    val appContext = LocalContext.current.applicationContext
    val dark = when (appTheme) {
        AppTheme.PHONE -> phoneDark
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }
    val colors = (if (dark) dynamicDarkColorScheme(appContext) else dynamicLightColorScheme(appContext)).withTintedSurfaces(dark, darkStyle)
    val base = LocalDensity.current
    val insets by CarEnvironment.insets.collectAsState()
    val navigator = remember {
        // A start screen picked for the car may be one launcher mode hides, like Screen sharing.
        val start = CarSettings.openOnConnect.value
        val shown = CarApps.shown(LauncherMode.active.value, LauncherMode.canCall(appContext)).any { it.id == start }
        CarNavigator(if (shown) start else CarSettings.HOME)
    }
    val controlsWake = remember { MapControlsWake() }
    LaunchedEffect(controlsWake) {
        while (true) {
            delay(250)
            controlsWake.tick()
            CarFullscreen.tick()
        }
    }
    // The knob counts as a touch: the map's buttons can't be found while they're faded out.
    LaunchedEffect(controlsWake) { CarKeys.focusMoves.collect { controlsWake.touch() } }
    remember(appContext) { Prefetch.warm(appContext, force = true); CarServices.init(appContext) }
    DisposableEffect(Unit) { onDispose { CarServices.shutdown() } }
    // Ask for the phone's sound as soon as the car connects, not only when a media app opens:
    // a video in the web browser or a map's voice needs it too. Asks once, after the car offers
    // somewhere to play it.
    LaunchedEffect(Unit) { if (CarSettings.pipesNow) app.seb3thehacker.gearslip.audio.CarAudio.request() }
    // A new version gets one card in the top right, a few seconds in and only while parked.
    LaunchedEffect(Unit) { app.seb3thehacker.gearslip.update.UpdateChecker.onCarConnected(appContext) }
    // The map follows the light outside, whatever the app's own theme is set to.
    LaunchedEffect(darkOutside) { CarServices.nav.pushConfiguration() }

    // The one place with a navigator and a frame to act a voice command on - CarAssistant itself
    // only knows how to listen and speak, not what "open Spotify" means.
    val frameForAssistant by CarEnvironment.frame.collectAsState()
    LaunchedEffect(navigator) {
        CarAssistant.pendingCommand.collect { heard ->
            if (heard != null) {
                val reply = AssistantCommands.handle(appContext, navigator, frameForAssistant, heard)
                CarAssistant.reply(reply)
            }
        }
    }

    // The car's own back, home, map, media and phone keys.
    LaunchedEffect(navigator) {
        CarKeys.screenKeys.collect { key ->
            when (key) {
                CarKeys.ScreenKey.BACK -> when {
                    navigator.appMenu != null -> navigator.dismissAppMenu()
                    VoiceReply.state.value.phase != VoiceReply.Phase.IDLE -> VoiceReply.cancel()
                    else -> navigator.back()
                }
                // Home is the dashboard, over the map, so Back from it lands on the map.
                CarKeys.ScreenKey.HOME -> { navigator.home(); navigator.dashboard() }
                CarKeys.ScreenKey.MAP -> navigator.home()
                CarKeys.ScreenKey.MEDIA ->
                    if (CarServices.mediaApp.value != null || CarServices.reopenLastMedia()) navigator.media()
                    else navigator.apps()
                CarKeys.ScreenKey.PHONE -> BuiltInApps.Phone.open(navigator)
            }
        }
    }

    // The car screen is drawn on the phone, so every long press or text-handle drag would buzz
    // the phone in its mount. Nobody feels that while driving; it's only noise.
    val view = LocalView.current
    DisposableEffect(view) {
        view.isHapticFeedbackEnabled = false
        onDispose { }
    }

    CompositionLocalProvider(
        LocalHapticFeedback provides NoHaptics,
        LocalDensity provides Density(base.density * scale, base.fontScale),
        LocalCarNavigator provides navigator,
        LocalDarkOutside provides darkOutside,
        LocalMapControlsWake provides controlsWake,
        LocalGsTheme provides GsThemes.byId(uiThemeId),
    ) {
        MaterialTheme(colorScheme = colors) {
          CompositionLocalProvider(LocalIndication provides FocusRingIndication(MaterialTheme.colorScheme.primary)) {
            Surface(
                Modifier
                    .fillMaxSize()
                    // Watches every touch without taking any: first in line, consumes nothing.
                    .pointerInput(controlsWake) {
                        // In full screen, a swipe that starts at the top or bottom edge brings the
                        // nav bar back. Only from the very edge, so scrolling a page still scrolls.
                        val edge = 32.dp.toPx()
                        val slop = 24.dp.toPx()
                        var startY = -1f
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                controlsWake.touch()
                                CarFullscreen.touch()
                                val change = event.changes.firstOrNull() ?: continue
                                if (!CarFullscreen.active) continue
                                if (change.pressed && !change.previousPressed) {
                                    val y = change.position.y
                                    startY = if (y < edge || y > size.height - edge) y else -1f
                                } else if (startY >= 0f && change.pressed && kotlin.math.abs(change.position.y - startY) > slop) {
                                    CarFullscreen.reveal()
                                    startY = -1f
                                }
                            }
                        }
                    },
                color = MaterialTheme.colorScheme.background,
            ) {
                // Insets are video-frame pixels. Resolve them with the live (size-scaled) density,
                // which padding() also uses, so the safe-area margin stays one fixed physical size
                // at every size setting instead of growing with it and eating the screen's sides.
                val pad = with(LocalDensity.current) {
                    PaddingValues(
                        start = insets.left.toDp(), top = insets.top.toDp(),
                        end = insets.right.toDp(), bottom = insets.bottom.toDp(),
                    )
                }
                val screen = navigator.current
                val safetyWarningSeen by CarSettings.hasSeenSafetyWarning.collectAsState()
                val keyboardVisible by CarKeyboardVisibility.state
                // The bar always lies over the map and every other screen rather than taking a row
                // of its own: hiding it then leaves the map's surface the size it was.
                // Resizing it would hand the map app a new surface each time, and some go black on that.
                val navBarMode by CarSettings.navBarMode.collectAsState()
                val appTemplate by CarServices.nav.template.collectAsState()
                val barMayHide = screen == CarScreen.Home && when (navBarMode) {
                    NavBarMode.SHOW -> false
                    NavBarMode.HIDE_NAVIGATING -> appTemplate is androidx.car.app.navigation.model.NavigationTemplate
                    NavBarMode.HIDE -> true
                }
                // A full wait from when hiding becomes possible, not from whenever the last touch was.
                LaunchedEffect(barMayHide) { if (barMayHide) controlsWake.touch() }
                // A call keeps the bar up whatever the setting: End and Mute live on it.
                val onCall = CarCalls.state.collectAsState().value is CallUiState.Active
                val barShown = !keyboardVisible && (!barMayHide || controlsWake.navBarAwake || onCall)
                // Up to the bar's top edge, so the map's cards and the player end above it.
                val homeInset = if (barShown) NAV_BAR_HEIGHT else 0.dp
                // Only the screen that asked: going Home or anywhere else ends it.
                val fullscreen = CarFullscreen.active && screen != CarScreen.Home
                Box(Modifier.fillMaxSize().padding(pad)) {
                Column(Modifier.fillMaxSize()) {
                    if (!safetyWarningSeen) {
                        CarSafetyWizard(onDone = CarSettings::markSafetyWarningSeen)
                        return@Column
                    }
                    // Header navigation (breadcrumb trail) - off for now, kept to tweak later.
                    // BreadcrumbBar(navigator)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // Home never leaves the composition; other screens cover it. Dropping it
                        // would tear down the map's surface, and coming back would then show black
                        // while the nav app redrew on a new one.
                        CompositionLocalProvider(LocalNavBarInset provides homeInset) { CarHome() }
                    }
                    // The keyboard takes the nav bar's row instead of squeezing in above it -
                    // nothing on the nav bar is useful mid-type, and the screen is too small to
                    // show both.
                }
                // Every other screen, and everything that pops up, in a layer over Home rather
                // than inside its area: full screen grows this layer over the nav bar's row while
                // Home, and the map's surface in it, keeps its size.
                // Each new screen fades in while rising a little, once, over a fifth of a second.
                // Coming from Home the backdrop fades in with it, over the map; between two other
                // screens it stays put, so the map never flashes through.
                val lastScreen = remember { arrayOf<CarScreen>(CarScreen.Home) }
                val fromHome = remember(screen) { lastScreen[0] == CarScreen.Home }
                SideEffect { lastScreen[0] = screen }
                val enter = remember(screen) { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(screen) { enter.animateTo(1f, androidx.compose.animation.core.tween(180)) }
                // Going back to Home, the screen that was up fades out and sinks a little over the
                // same fifth of a second, rather than vanishing: it stays drawn until it's gone.
                val lastAway = remember { arrayOf<CarScreen>(CarScreen.Home) }
                val shown = if (screen != CarScreen.Home) screen else lastAway[0]
                SideEffect { if (screen != CarScreen.Home) lastAway[0] = screen }
                if (safetyWarningSeen) {
                    Box(Modifier.fillMaxSize()) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = screen != CarScreen.Home,
                            enter = androidx.compose.animation.EnterTransition.None,
                            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) +
                                androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(180)) { it / 40 },
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { if (fromHome) alpha = enter.value }
                                    .background(MaterialTheme.colorScheme.background)
                                    // The bar never hides here, so keep the screen clear of it. The
                                    // player runs on under the see-through bar and keeps clear itself.
                                    .padding(bottom = if (!keyboardVisible && !fullscreen && shown != CarScreen.Media) NAV_BAR_HEIGHT else 0.dp)
                                    // Keeps touches that miss this screen's controls off the map.
                                    .pointerInput(Unit) { detectTapGestures { } },
                            ) {
                                Box(
                                    Modifier.fillMaxSize().graphicsLayer {
                                        if (!fromHome) alpha = enter.value
                                        translationY = (1f - enter.value) * 16.dp.toPx()
                                    },
                                ) {
                                    when (shown) {
                                        CarScreen.Home -> Unit
                                        CarScreen.Apps -> CarLauncher()
                                        CarScreen.Media -> CompositionLocalProvider(
                                            LocalNavBarInset provides if (!keyboardVisible && !fullscreen) NAV_BAR_HEIGHT else 0.dp,
                                        ) { mediaApp?.let { MediaScreen(it) { navigator.back() } } }
                                        CarScreen.Browse -> BrowseAppScreen(navigator)
                                        CarScreen.Settings -> CarSettingsScreen()
                                        CarScreen.Dashboard -> CarDashboardScreen()
                                        CarScreen.Weather -> WeatherScreen()
                                        CarScreen.VehicleData -> VehicleDataScreen()
                                        is CarScreen.Notifications -> NotificationsScreen(shown.replyTo)
                                        is CarScreen.Messages -> MessagesScreen(shown.packageName)
                                        is CarScreen.App -> CarApps.find(shown.id)?.content?.invoke()
                                    }
                                }
                            }
                        }
                        CarToastOverlay(Modifier.align(Alignment.BottomCenter).padding(bottom = homeInset))
                        CarAlertOverlay(Modifier.align(Alignment.TopCenter))
                        CallPromptOverlay()
                        BluetoothPairOverlay()
                        PopupTimer()
                        // The dashboard is already listing them, so it needs no popup.
                        NotificationPopup(
                            showNew = screen !is CarScreen.Notifications && screen != CarScreen.Dashboard,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                        // Above the popup: a call is more urgent than any notification.
                        VoiceHelpOverlay(Modifier.align(Alignment.Center))
                        CallOverlay()
                        AssistantOverlay(Modifier.align(Alignment.BottomCenter).padding(bottom = homeInset))
                        val badge by CarSettings.debugBadge.collectAsState()
                        if (app.seb3thehacker.gearslip.BuildConfig.DEBUG && badge) {
                            // Bottom corner, not top: a screen's own header almost always puts
                            // something in the top-right (settings, search, a Liked Songs
                            // shortcut) and this badge used to sit right on top of it, hiding
                            // whatever the screen drew there.
                            DebugBuildBadge(Modifier.align(Alignment.BottomEnd).padding(bottom = homeInset))
                        }
                        run {
                            androidx.compose.animation.AnimatedVisibility(
                                if (fullscreen) CarFullscreen.revealed || onCall else barShown,
                                Modifier.align(Alignment.BottomCenter),
                                enter = androidx.compose.animation.slideInVertically { it },
                                exit = androidx.compose.animation.slideOutVertically { it },
                            ) { CarNavBar(navigator) }
                        }
                        if (fullscreen) {
                            androidx.compose.animation.AnimatedVisibility(
                                CarFullscreen.revealed,
                                Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                                enter = androidx.compose.animation.slideInVertically { -it },
                                exit = androidx.compose.animation.slideOutVertically { -it },
                            ) {
                                GsButton(onClick = CarFullscreen::exit, modifier = Modifier.height(48.dp)) {
                                    Icon(MediaIcons.FullscreenExit, contentDescription = null, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.carui_exit_fullscreen), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
                // Over everything, nav bar included: a long press on an app, anywhere.
                AppMenu(navigator, rememberRunningApps())
                if (safetyWarningSeen) CarWhatsNew()
                }
            }
          }
        }
    }
}

internal val NAV_BAR_HEIGHT = 56.dp

/**
 * How much of the bottom of Home the floating nav bar covers right now: its height while it shows,
 * zero while it's hidden or when it has a row of its own.
 */
internal val LocalNavBarInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

/**
 * A single blocking screen, shown once per install before anything else the car screen can show -
 * Home included. The phone's own setup guide carries the same warning, but a driver who plugs in
 * without ever opening the phone app would otherwise never see it, on the one screen they are
 * actually about to use while driving. [onDone] fires once, from its own button; there is no way
 * to skip past this without tapping it.
 */
@Composable
private fun CarSafetyWizard(onDone: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The card and its button share one width, capped so neither sprawls across a wide screen.
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.carui_drive_safely), style = ChromeType.headline, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.carui_unofficial),
                        style = ChromeType.body,
                    )
                    Text(
                        stringResource(R.string.carui_never_interact),
                        style = ChromeType.body,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            GsButton(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(stringResource(R.string.carui_understand), style = ChromeType.body)
            }
        }
    }
}

/**
 * A templated app that isn't navigation, full size with its own title bar and Stop button - the
 * same [CarAppStage] chrome the map uses on Home, just not embedded there. [CarServices.browse]
 * is a connection of its own, so opening one of these never bumps the map off Home.
 */
@Composable
private fun BrowseAppScreen(navigator: CarNavigator) {
    val status by CarServices.browse.status.collectAsState()
    val frame by CarEnvironment.frame.collectAsState()
    val template by CarServices.browse.template.collectAsState()
    val leave = {
        CarServices.stopBrowse()
        navigator.back()
    }
    // An app that never answers is as stuck as one that says no; don't leave the driver on "Loading".
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(template == null) {
        timedOut = false
        if (template == null) {
            delay(APP_START_TIMEOUT_MS)
            timedOut = true
        }
    }
    val failed = status.phase == CarAppConnection.Phase.REJECTED || status.phase == CarAppConnection.Phase.FAILED
    if (failed || timedOut) {
        AppWontStart(status.app ?: stringResource(R.string.carui_this_app), leave)
        return
    }
    CarAppStage(CarServices.browse, status, frame, onDisconnect = leave)
}

private const val APP_START_TIMEOUT_MS = 15_000L

/** What a car app that won't run under Gearslip gets instead of a loading screen that never ends. */
@Composable
private fun AppWontStart(app: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.carui_app_unsupported, app), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.carui_app_unsupported_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GsButton(onClick = onBack, tone = GsTone.Tonal) { Text(stringResource(R.string.back)) }
    }
}

/**
 * Marks a debug build on the car screen itself, not just the phone's Home screen - the one
 * that matters while driving, since a debug build is slower and less tested than what ships.
 */
@Composable
private fun DebugBuildBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(6.dp),
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            "DEBUG BUILD",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Stands in for the phone's vibration motor on the car screen: does nothing. */
private object NoHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {}
}
