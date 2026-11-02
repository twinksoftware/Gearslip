package app.seb3thehacker.gearslip.car

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.car.theme.GsButton
import app.seb3thehacker.gearslip.car.theme.GsTone
import app.seb3thehacker.gearslip.host.CarAppConnection
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.CarFocus
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.PathParser

/** What a built-in app's id carries in a pin, so it can't collide with an installed app's component. */
internal const val BUILT_IN_PREFIX = "gearslip:"

/** Material's "directions car", drawn here because only the core icon set is bundled. */
private val CarGlyph: ImageVector = ImageVector.Builder("Car", 24.dp, 24.dp, 24f, 24f).addPath(
    PathParser().parsePathString(
        "M18.92,6.01C18.72,5.42 18.16,5 17.5,5h-11c-0.66,0 -1.21,0.42 -1.42,1.01L3,12v8c0,0.55 0.45,1 1,1h1" +
            "c0.55,0 1,-0.45 1,-1v-1h12v1c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-8l-2.08,-5.99z" +
            "M6.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,13 6.5,13s1.5,0.67 1.5,1.5S7.33,16 6.5,16z" +
            "M17.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z" +
            "M5,11l1.5,-4.5h11L19,11L5,11z",
    ).toNodes(),
    fill = SolidColor(Color.Black),
).build()

/**
 * One of Gearslip's own apps: a screen, with nothing running behind it once you leave. [screen] is
 * null for an action like Exit, which has no screen and so never shows as open.
 */
internal class BuiltInApp(
    val id: String,
    /** English, for matching what a driver says; [labelRes] is what the screen shows. */
    val label: String,
    @param:StringRes val labelRes: Int,
    val glyph: ImageVector,
    val screen: CarScreen?,
    val open: (CarNavigator) -> Unit,
)

internal object BuiltInApps {
    val Web = BuiltInApp("web", "Browser", R.string.app_browser, Icons.Filled.Search, CarScreen.App("web")) { it.open("web") }
    val ScreenSharing = BuiltInApp("phone", "Screen sharing", R.string.app_screen_sharing, Icons.Filled.Share, CarScreen.App("phone")) { it.open("phone") }
    val Phone = BuiltInApp("dialer", "Phone", R.string.carset_phone, Icons.Filled.Call, CarScreen.App("dialer")) { it.open("dialer") }
    val VehicleData = BuiltInApp("vehicle", "Vehicle data", R.string.car_vehicle_data, Icons.Filled.Info, CarScreen.VehicleData) { it.vehicleData() }
    val Settings = BuiltInApp("settings", "Settings", R.string.settings_title, Icons.Filled.Settings, CarScreen.Settings) { it.settings() }

    /** Hands the screen back to the car's own interface, like Android Auto's Exit. */
    val Exit = BuiltInApp("exit", "Exit", R.string.app_exit, CarGlyph, screen = null) { CarFocus.exitToCar() }

    val all = listOf(Web, ScreenSharing, Phone, VehicleData, Settings, Exit)
    val entries: List<Entry> = all.map { Entry(it.label, icon = null, builtIn = it) }
}

/** The name a driver sees: translated for Gearslip's own apps, the app's own name otherwise. */
@Composable
internal fun Entry.shownLabel(): String = builtIn?.let { stringResource(it.labelRes) } ?: label

/** Vehicle data is experimental: raw, unverified sensor labels (see [CarSensors]). */
@Composable
internal fun rememberVehicleDataShown(): Boolean {
    val experimental by CarSettings.experimentalFeaturesEnabled.collectAsState()
    val sensors by CarSettings.carSensorsEnabled.collectAsState()
    return experimental && sensors
}

/**
 * Where an open app lives - decides where tapping it goes and how it closes. Gearslip's own
 * apps have no connection behind them, so [SCREEN] is simply "on screen right now".
 */
internal enum class RunningSlot { NAV, BROWSE, MEDIA, SCREEN }

/**
 * Apps open right now, as component id to where they live, in the order the nav bar shows them:
 * the map, then a browsed templated app, then the media app, then whichever built-in app is on
 * screen. The map and a browsed app count from the moment they start connecting until they're
 * closed. The media app counts while it has something loaded to play - the same rule the nav
 * bar's player follows, so the two always show up together - or while its own screen is up.
 */
@Composable
internal fun rememberRunningApps(): Map<String, RunningSlot> {
    val navStatus by CarServices.nav.status.collectAsState()
    val browseStatus by CarServices.browse.status.collectAsState()
    val mediaApp by CarServices.mediaApp.collectAsState()
    val mediaPhase by CarServices.media.phase.collectAsState()
    val now by CarServices.media.now.collectAsState()
    val current = LocalCarNavigator.current.current

    fun CarAppConnection.liveId(phase: CarAppConnection.Phase) =
        connectedComponent?.flattenToString()?.takeIf {
            phase == CarAppConnection.Phase.BINDING ||
                phase == CarAppConnection.Phase.HANDSHAKE ||
                phase == CarAppConnection.Phase.RUNNING
        }

    return buildMap {
        CarServices.nav.liveId(navStatus.phase)?.let { put(it, RunningSlot.NAV) }
        CarServices.browse.liveId(browseStatus.phase)?.let { put(it, RunningSlot.BROWSE) }
        val mediaLive = when (mediaPhase) {
            CarMedia.Phase.CONNECTING -> true
            CarMedia.Phase.READY -> now.isActive || current == CarScreen.Media
            else -> false
        }
        mediaApp?.component?.flattenToString()?.takeIf { mediaLive }?.let { put(it, RunningSlot.MEDIA) }
        BuiltInApps.all.firstOrNull { it.screen != null && it.screen == current }?.let { put(BUILT_IN_PREFIX + it.id, RunningSlot.SCREEN) }
        (current as? CarScreen.Messages)?.let { put(MESSAGING_PREFIX + it.packageName, RunningSlot.SCREEN) }
    }
}

/** The screen an open app is showing on, so its nav bar icon can stay pressed in while it's up. */
internal fun RunningSlot.isShowing(current: CarScreen): Boolean = when (this) {
    RunningSlot.NAV -> current == CarScreen.Home
    RunningSlot.BROWSE -> current == CarScreen.Browse
    RunningSlot.MEDIA -> current == CarScreen.Media
    RunningSlot.SCREEN -> true
}

/**
 * Brings an app up: back to its screen if it's already open - never a reconnect, which would
 * throw away the route or restart the session - otherwise a fresh launch.
 */
internal fun openApp(entry: Entry, slot: RunningSlot?, navigator: CarNavigator, frame: CarEnvironment.Frame) {
    when (slot) {
        RunningSlot.NAV -> navigator.home()
        RunningSlot.BROWSE -> navigator.browse(entry.label)
        RunningSlot.MEDIA -> navigator.media()
        RunningSlot.SCREEN -> Unit
        null -> launchEntry(entry, navigator, frame)
    }
}

internal fun closeApp(slot: RunningSlot, navigator: CarNavigator) {
    when (slot) {
        RunningSlot.NAV -> CarServices.stopNav()
        RunningSlot.BROWSE -> CarServices.stopBrowse()
        RunningSlot.MEDIA -> CarServices.closeMedia()
        RunningSlot.SCREEN -> Unit
    }
    if (slot != RunningSlot.NAV && slot.isShowing(navigator.current)) navigator.back()
}

/**
 * What a long press on an app offers - pin it to or unpin it from the nav bar, move it along the
 * launcher, and close it if it's open - as a card in the middle of the screen with car-sized buttons, over a scrim that
 * dismisses it. Drawn in the car UI's own layout rather than a system popup window, which the
 * car's virtual display has no reason to host well.
 */
@Composable
internal fun AppMenu(navigator: CarNavigator, running: Map<String, RunningSlot>) {
    val id = navigator.appMenu ?: return
    val entry = findEntry(id)
    if (entry == null) {
        navigator.dismissAppMenu()
        return
    }
    val pinned by CarSettings.pinnedApps.collectAsState()
    val isPinned = id in pinned
    val barFull = !isPinned && pinned.size >= CarSettings.MAX_PINNED
    val slot = running[id]

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = navigator::dismissAppMenu,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            // Swallows taps on the card itself, so only the scrim around it dismisses.
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    entry.icon?.let { Image(it, null, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))) }
                    entry.builtIn?.let { Icon(it.glyph, null, Modifier.size(36.dp)) }
                    Text(
                        entry.shownLabel(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 14.dp),
                    )
                }
                GsButton(
                    onClick = {
                        CarSettings.togglePin(id)
                        navigator.dismissAppMenu()
                    },
                    tone = GsTone.Tonal,
                    enabled = !barFull,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(
                        when {
                            isPinned -> stringResource(R.string.navapps_unpin)
                            barFull -> stringResource(R.string.navapps_full, CarSettings.MAX_PINNED)
                            else -> stringResource(R.string.navapps_pin)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (navigator.current == CarScreen.Apps) MoveButtons(id)
                if (slot != null) {
                    GsButton(
                        onClick = {
                            closeApp(slot, navigator)
                            navigator.dismissAppMenu()
                        },
                        tone = GsTone.Danger,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text(stringResource(R.string.navapps_close), style = MaterialTheme.typography.titleMedium) }
                }
                GsButton(
                    onClick = navigator::dismissAppMenu,
                    tone = GsTone.Neutral,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text(stringResource(R.string.cancel), style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

/**
 * Moves the app one place left or right on the launcher. The menu stays open so a tile can be
 * walked several places, and the grid behind the scrim shows where it went.
 */
@Composable
private fun MoveButtons(id: String) {
    val custom by CarSettings.appOrder.collectAsState()
    val hidden by CarSettings.hiddenApps.collectAsState()
    val order = launcherOrder(rememberAllEntries(), rememberVehicleDataShown(), custom, hidden).mapNotNull { it.componentId }
    val index = order.indexOf(id)
    if (index < 0) return
    fun move(by: Int) {
        val next = order.toMutableList()
        java.util.Collections.swap(next, index, index + by)
        CarSettings.setAppOrder(next)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GsButton(
            onClick = { move(-1) },
            tone = GsTone.Tonal,
            enabled = index > 0,
            modifier = Modifier.weight(1f).height(56.dp),
        ) { Text(stringResource(R.string.navapps_move_left), style = MaterialTheme.typography.titleMedium) }
        GsButton(
            onClick = { move(1) },
            tone = GsTone.Tonal,
            enabled = index < order.lastIndex,
            modifier = Modifier.weight(1f).height(56.dp),
        ) { Text(stringResource(R.string.navapps_move_right), style = MaterialTheme.typography.titleMedium) }
    }
    if (custom.isNotEmpty()) {
        GsButton(
            onClick = { CarSettings.setAppOrder(emptyList()) },
            tone = GsTone.Neutral,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(stringResource(R.string.navapps_reset), style = MaterialTheme.typography.titleMedium) }
    }
}
