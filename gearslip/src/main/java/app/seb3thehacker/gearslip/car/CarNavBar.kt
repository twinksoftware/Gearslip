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
import app.seb3thehacker.gearslip.notify.PhoneNavigation
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

// The nav bar along the bottom of the car screen: home, apps, the assistant, running apps,
// the next turn or a call, the player, and the clock. Split out of CarUi.kt.

/**
 * "Home > Apps > Settings", like a file manager's address bar: hidden at Home itself (nothing to
 * show yet), and grows by one crumb for every screen pushed from there. The back arrow undoes one
 * step; a crumb jumps straight to it, dropping everything after - this is the only on-screen way
 * back at all, since a car touchscreen has no hardware button for it.
 */
@Composable
private fun BreadcrumbBar(navigator: CarNavigator) {
    val trail = navigator.trail
    if (trail.size <= 1) return
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(start = 2.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GsIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), navigator::back, size = 32.dp, iconSize = 20.dp)
        Spacer(Modifier.width(2.dp))
        trail.forEachIndexed { index, crumb ->
            val isLast = index == trail.lastIndex
            Text(
                crumb.shown(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (isLast) Modifier else Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { navigator.jumpTo(index) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
            if (!isLast) {
                Text(
                    "›",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

/**
 * Apps and open/pinned shortcuts on the left; now-playing pill against them; clock (tap for
 * calendar and weather), phone battery and the darkness signal on the right. Icon-only: a driver
 * reads a glyph faster than a label, and it keeps the bar short enough to leave the app underneath
 * more room.
 */
@Composable
internal fun CarNavBar(navigator: CarNavigator) {
    val battery by CarEnvironment.battery.collectAsState()
    val now by rememberNow()
    val weather by Weather.state.collectAsState()
    val weatherData = (weather as? WeatherState.Ready)?.data

    val pinnedIds by CarSettings.pinnedApps.collectAsState()
    val entries = rememberAllEntries()
    val pinnedEntries = remember(entries, pinnedIds) {
        pinnedIds.mapNotNull { id -> entries.find { it.componentId == id } }
    }
    val frame by CarEnvironment.frame.collectAsState()
    val running = rememberRunningApps()

    // A full-width strip along the bottom in the screens' own background, so it reads as part of
    // the screen above rather than a panel on it. Over the player it's see-through, so the
    // cover's glow carries on behind it.
    val overPlayer = navigator.current == CarScreen.Media
    val navMic by CarSettings.navMic.collectAsState()
    Surface(color = if (overPlayer) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(NAV_BAR_HEIGHT).padding(horizontal = 10.dp)) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                // Home and Apps are two different places now, not a toggle on one button - the
                // breadcrumb trail is how you retrace your steps, but reaching for either of
                // these two from three screens deep in a media app shouldn't mean reading it
                // first.
                NavHomeButton(navigator, entries, running)
                Spacer(Modifier.width(6.dp))
                NavItem(MediaIcons.Apps, navigator.current == CarScreen.Apps) { navigator.apps() }
                if (navMic) {
                    Spacer(Modifier.width(6.dp))
                    NavAssistantIcon()
                }

                // Pinned shortcuts, then whatever else is open right now. A pinned app that's also
                // open stays in its pinned spot, marked with a dot, instead of showing up twice.
                // The map app lives on the Home button, so it never gets a second icon here.
                val runningEntries = running.keys
                    .filter { it !in pinnedIds && running[it] != RunningSlot.NAV }
                    .mapNotNull { id -> entries.find { it.componentId == id } }
                val shownPins = pinnedEntries.filter { running[it.componentId] != RunningSlot.NAV }
                if (shownPins.isNotEmpty() || runningEntries.isNotEmpty()) NavDivider()
                shownPins.forEach { entry -> NavEntryIcon(entry, running, navigator, frame) }
                if (shownPins.isNotEmpty() && runningEntries.isNotEmpty()) NavDivider()
                runningEntries.forEach { entry -> NavEntryIcon(entry, running, navigator, frame) }

                // Next turn first, off the map only; the player shares the space when it fits,
                // and gives way to the turn when it doesn't.
                // A call takes the player's place until it ends.
                // A turn from the phone has no map in the car to show it, so it shows on Home too.
                val turn = rememberNextTurn()?.takeIf { it.fromPhone || navigator.current != CarScreen.Home }
                val call = CarCalls.state.collectAsState().value as? CallUiState.Active
                BoxWithConstraints(Modifier.weight(1f).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    val roomForBoth = maxWidth >= TURN_AND_PLAYER_MIN_WIDTH
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (call == null || roomForBoth) turn?.let { NavTurnPill(it) { if (!it.fromPhone) navigator.home() } }
                        if (call != null) {
                            NavCallPill(call, Modifier.weight(1f, fill = false))
                        } else if (turn == null || roomForBoth) {
                            NavNowPlaying(navigator, selected = navigator.current == CarScreen.Media, modifier = Modifier.weight(1f, fill = false))
                        }
                    }
                }

                // Weather no longer has its own chip - the temperature alone (no icon, no room
                // for one) sits where "Night"/"Day" used to, freeing a whole chip's width for the
                // Home button above.
                // The clock is also the notifications button: the dashboard lists them under the
                // calendar and weather, and a dot here marks anything unread.
                val unread by CarNotifications.unread.collectAsState()
                NavChip(
                    navigator.current == CarScreen.Dashboard || navigator.current is CarScreen.Notifications,
                    { navigator.dashboard() },
                ) {
                    if (unread > 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 8.dp, y = (-2).dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (battery.percent >= 0) {
                                Text(
                                    "${battery.percent}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (battery.charging) {
                                    ChargingGlyph(Modifier.size(13.dp))
                                }
                            }
                            weatherData?.let { w ->
                                Text(
                                    "  ·  ${w.temp}°",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Home: the map, with the player or lyrics beside it. While a map app runs, the button wears that
 * app's icon and a long press offers to close it, so the app needs no icon of its own on the bar.
 */
@Composable
private fun NavHomeButton(navigator: CarNavigator, entries: List<Entry>, running: Map<String, RunningSlot>) {
    val selected = navigator.current == CarScreen.Home
    val navId = running.entries.firstOrNull { it.value == RunningSlot.NAV }?.key
    val navEntry = navId?.let { id -> entries.find { it.componentId == id } }
    GsIconBox(
        { navigator.home() },
        colors = navColors(selected),
        latched = selected,
        onLongClick = navId?.let { id -> { navigator.showAppMenu(id) } },
    ) {
        val icon = navEntry?.icon
        if (icon != null) {
            Box(Modifier.padding(NAV_ITEM_PADDING).size(28.dp), contentAlignment = Alignment.Center) {
                Image(icon, navEntry.label, Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)))
            }
        } else {
            Icon(
                ImageVector.vectorResource(R.drawable.navigation_24), contentDescription = stringResource(R.string.crumb_home),
                modifier = Modifier.padding(NAV_ITEM_PADDING).size(28.dp),
            )
        }
    }
}

/** Splits the nav bar's sections: Home and Apps, pinned apps, open apps. */
@Composable
private fun NavDivider() {
    Box(
        Modifier
            .padding(horizontal = 10.dp)
            .width(1.dp)
            .height(28.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * A pinned or open app on the nav bar. Tapping brings it back without reconnecting if it's
 * already open; a long press opens its menu (pin, unpin, close).
 */
@Composable
private fun NavEntryIcon(
    entry: Entry,
    running: Map<String, RunningSlot>,
    navigator: CarNavigator,
    frame: CarEnvironment.Frame,
) {
    val id = entry.componentId ?: return
    val slot = running[id]
    Box(Modifier.padding(horizontal = 3.dp)) {
        NavAppIcon(
            entry,
            open = slot != null,
            showing = slot != null && slot.isShowing(navigator.current),
            onLongClick = { navigator.showAppMenu(id) },
        ) { openApp(entry, slot, navigator, frame) }
    }
}

/** One open or pinned app's icon on the nav bar - the same fixed size and shape either way. */
@Composable
private fun NavAppIcon(
    entry: Entry,
    open: Boolean,
    showing: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    GsIconBox(
        onClick,
        Modifier.size(44.dp),
        colors = if (showing) GsColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
        else GsColors(scheme.surfaceVariant, scheme.onSurfaceVariant),
        latched = showing,
        onLongClick = onLongClick,
    ) {
        entry.icon?.let { Image(it, entry.label, Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))) }
        entry.builtIn?.let { Icon(it.glyph, entry.shownLabel(), Modifier.size(26.dp)) }
        // Only running apps get the line along the bottom edge, the usual taskbar mark for it.
        if (open) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
                    .size(width = 12.dp, height = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(scheme.primary),
            )
        }
    }
}

/** A tappable group in the bar that stays highlighted while its screen is open, like [NavItem]. */
@Composable
private fun NavChip(selected: Boolean, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    GsIconBox(onClick, colors = navColors(selected), latched = selected) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) { content() }
    }
}

/** Every nav bar item is raised, so it reads as pressable; the current screen's takes the accent colour. */
@Composable
private fun navColors(selected: Boolean): GsColors {
    val s = MaterialTheme.colorScheme
    return if (selected) GsColors(s.secondaryContainer, s.onSecondaryContainer)
    else GsColors(s.surfaceContainerHighest, s.onSurface)
}

/**
 * Between Apps and whatever's actually open, not with the clock/battery group on the right - a
 * driver reaches for it right after deciding "not a tap for this", same gesture family as the
 * two buttons beside it.
 */
@Composable
private fun NavAssistantIcon() {
    val state by CarAssistant.state.collectAsState()
    val active = state.phase != CarAssistant.Phase.IDLE
    GsIconBox({ CarAssistant.start() }, colors = navColors(active), latched = active) {
        MicGlyph(Modifier.padding(NAV_ITEM_PADDING).size(28.dp))
    }
}

/** Inner padding of every icon on the nav bar, leaving each button room inside the 56dp bar. */
private val NAV_ITEM_PADDING = 8.dp

@Composable
private fun NavItem(icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    GsIconBox(onClick, colors = navColors(selected), latched = selected) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(NAV_ITEM_PADDING).size(28.dp))
    }
}

/** Wall-clock time, ticking on the minute: no reason to redraw (and re-encode) more often. */
@Composable
internal fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(60_000 - value % 60_000)
    }
}

/** How long the pill shows a new song's title before settling to just the controls. */
private const val TITLE_SHOWN_MS = 3_000L

/** Below this, the nav bar's middle only has room for the turn, not the player beside it. */
private val TURN_AND_PLAYER_MIN_WIDTH = 520.dp

/** The next manoeuvre, as the running nav app last described it, or [fromPhone] a map guiding on the phone. */
private class NextTurn(val icon: CarIcon?, val distance: String, val road: String, val fromPhone: Boolean = false)

/**
 * Read straight off the nav app's own template: a navigation app keeps sending its routing card
 * while it's running, whether or not the map is the screen being shown.
 */
@Composable
private fun rememberNextTurn(): NextTurn? {
    val status by CarServices.nav.status.collectAsState()
    val template by CarServices.nav.template.collectAsState()
    // Read so a change of units redraws the distance straight away, not at the next update.
    CarSettings.units.collectAsState().value
    val phone by PhoneNavigation.turn.collectAsState()
    val routing = ((template as? NavigationTemplate)?.navigationInfo as? RoutingInfo)
        ?.takeIf { status.phase == CarAppConnection.Phase.RUNNING }
    val step = routing?.takeIf { !it.isLoading }?.currentStep ?: return phone?.let { phoneTurn(it) }
    return NextTurn(
        icon = step.maneuver?.icon,
        distance = routing.currentDistance.display(),
        road = step.road.text().ifEmpty { step.cue.text() },
    )
}

/** Google Maps' or Waze's own arrow, and the distance as the app wrote it. */
@Composable
private fun phoneTurn(turn: PhoneNavigation.Turn): NextTurn {
    val icon = remember(turn.arrow) {
        turn.arrow?.let { CarIcon.Builder(androidx.core.graphics.drawable.IconCompat.createWithBitmap(it)).build() }
    }
    return NextTurn(icon, turn.distance?.display() ?: turn.distanceText, turn.road, fromPhone = true)
}

/** The next turn in the nav bar: arrow, how far, onto what. Tapping goes back to the map. */
@Composable
private fun NavTurnPill(turn: NextTurn, onClick: () -> Unit) {
    // Same finish as every other button on the bar, in the accent colour; the text runs a step
    // bigger than anything else there, since it's the one thing worth a glance mid-drive.
    GsIconBox(
        onClick,
        Modifier.widthIn(max = 340.dp).height(44.dp),
        colors = gsColors(GsTone.Primary),
    ) {
        Row(
            Modifier.padding(NAV_ITEM_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CarGlyph(turn.icon, Modifier.size(28.dp))
            if (turn.distance.isNotEmpty()) {
                Text(turn.distance, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            if (turn.road.isNotEmpty()) {
                Text(
                    turn.road,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The call under way: who, how long, and Mute and End right here. Ringing has its own screen. */
@Composable
private fun NavCallPill(call: CallUiState.Active, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val muted by CarCalls.muted.collectAsState()
    val elapsed by produceState(0L, call.since) {
        while (true) {
            value = (android.os.SystemClock.uptimeMillis() - call.since) / 1000
            delay(1000 - (android.os.SystemClock.uptimeMillis() - call.since) % 1000)
        }
    }
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(14.dp),
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.fillMaxHeight().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                call.caller?.label ?: stringResource(R.string.navbar_on_call),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                "%d:%02d".format(elapsed / 60, elapsed % 60),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            Spacer(Modifier.width(2.dp))
            GsIconButton(
                if (muted) MediaIcons.MicOff else MediaIcons.Mic,
                stringResource(if (muted) R.string.navbar_unmute else R.string.navbar_mute),
                CarCalls::toggleMute,
                tone = if (muted) GsTone.Primary else GsTone.Neutral, size = 36.dp, iconSize = 22.dp,
            )
            GsIconButton(
                MediaIcons.CallEnd, stringResource(R.string.navbar_end_call), { CarCalls.end(context) },
                colors = GsColors(scheme.error, scheme.onError), size = 36.dp, iconSize = 22.dp,
            )
        }
    }
}

/** "Title - Artist" on one line. The text itself cuts it off, and only where the pill runs out of room. */
@Composable
private fun nowPlayingLabel(title: String, artist: String): String =
    listOf(title.trim(), artist.trim()).filter { it.isNotEmpty() }.joinToString(" - ")
        .ifEmpty { stringResource(R.string.media_nothing_playing) }

/**
 * The player in the nav bar, on every screen. Art and title open the full media screen;
 * play/pause and skip work right here. The dock button moves the player into the column beside
 * the map on Home (see [CarHome]), and the pill steps aside there while it's docked - unless the
 * column is showing lyrics instead, when the pill stays and its dock button closes them.
 */
@Composable
private fun NavNowPlaying(navigator: CarNavigator, selected: Boolean, modifier: Modifier = Modifier) {
    val media = CarServices.media
    val phase by media.phase.collectAsState()
    val now by media.now.collectAsState()
    if (phase != CarMedia.Phase.READY || !now.isActive) return
    // Docked beside the map, the player is already on screen - the pill steps aside for it.
    if (navigator.sidePanel == SidePanel.CONTROLS && navigator.current == CarScreen.Home) return
    // The media screen is the full player already; the pill would only repeat its controls.
    if (navigator.current == CarScreen.Media) return
    val context = LocalContext.current
    val art by produceState(now.art, now.art, now.artUri) { value = now.art ?: MediaArt.load(context, now.artUri) }
    val position by produceState(now.currentPosition(), now) {
        while (true) {
            value = now.currentPosition()
            if (!now.playing) break
            delay(1000)
        }
    }

    // A new song announces itself: title, play/pause and dock for a few seconds, then the title
    // goes and the pill settles to just the controls - unless the driver keeps the title up.
    val keepTitle by CarSettings.playerTitle.collectAsState()
    var announcing by remember { mutableStateOf(true) }
    LaunchedEffect(now.title, now.artist) {
        announcing = true
        delay(TITLE_SHOWN_MS)
        announcing = false
    }
    val showTitle = keepTitle || announcing

    val scheme = MaterialTheme.colorScheme
    val onPill = if (selected) scheme.onPrimaryContainer else scheme.onSecondaryContainer
    Surface(
        modifier = modifier
            .animateContentSize()
            // With the title up, all the room the bar has, so as much of it shows as can.
            .then(if (showTitle) Modifier.fillMaxWidth() else Modifier)
            .height(44.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) scheme.primaryContainer else scheme.secondaryContainer,
        contentColor = onPill,
    ) {
        Box {
            Row(
                Modifier.fillMaxHeight().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Art (and the title while it shows) opens the full media screen.
                Row(
                    Modifier
                        .then(if (showTitle) Modifier.weight(1f) else Modifier)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { navigator.media() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(scheme.surfaceVariant)) {
                        art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                    if (showTitle) {
                        Text(
                            nowPlayingLabel(now.title, now.artist),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!showTitle) {
                    GsIconButton(
                        MediaIcons.Previous, stringResource(R.string.player_previous), media::previous,
                        enabled = now.canDo(PlaybackState.ACTION_SKIP_TO_PREVIOUS), size = 36.dp, iconSize = 22.dp,
                    )
                }
                GsIconButton(
                    if (now.playing) MediaIcons.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (now.playing) R.string.player_pause else R.string.media_play),
                    media::togglePlay,
                    tone = GsTone.Primary, size = 36.dp, iconSize = 22.dp,
                )
                if (!showTitle) {
                    GsIconButton(
                        MediaIcons.Next, stringResource(R.string.player_next), media::next,
                        enabled = now.canDo(PlaybackState.ACTION_SKIP_TO_NEXT), size = 36.dp, iconSize = 22.dp,
                    )
                }
                // Drawn on the side the panel opens on, and filled in while it's open.
                val playerLeft by CarSettings.playerLeft.collectAsState()
                val docked = navigator.current == CarScreen.Home && navigator.sidePanel != SidePanel.NONE
                GsIconButton(
                    when {
                        playerLeft && docked -> MediaIcons.DockLeftOpen
                        playerLeft -> MediaIcons.DockLeft
                        docked -> MediaIcons.DockRightOpen
                        else -> MediaIcons.DockRight
                    },
                    stringResource(if (docked) R.string.navbar_close_panel else R.string.navbar_dock_player),
                    navigator::togglePlayerDock,
                    size = 36.dp, iconSize = 22.dp,
                )
            }
            if (now.durationMs > 0) {
                // matchParentSize, so the line follows the pill's width rather than widening it.
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomStart) {
                    Box(
                        Modifier
                            .fillMaxWidth((position.toFloat() / now.durationMs).coerceIn(0f, 1f))
                            .height(2.dp)
                            .background(onPill.copy(alpha = 0.7f)),
                    )
                }
            }
        }
    }
}

/**
 * A filled lightning bolt, next to the battery percentage instead of the word "charging" - the
 * core Material icon set this project ships (no extended pack, to keep the app small) has no
 * bolt, so this is drawn rather than a vector asset.
 */
@Composable
private fun ChargingGlyph(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val bolt = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.58f, 0f)
            lineTo(w * 0.12f, h * 0.58f)
            lineTo(w * 0.46f, h * 0.58f)
            lineTo(w * 0.42f, h)
            lineTo(w * 0.88f, h * 0.40f)
            lineTo(w * 0.54f, h * 0.40f)
            close()
        }
        drawPath(bolt, tint)
    }
}
