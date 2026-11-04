package app.seb3thehacker.gearslip.car

import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.car.theme.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import app.seb3thehacker.gearslip.host.KnownApps
import app.seb3thehacker.gearslip.host.CarAppCatalog
import app.seb3thehacker.gearslip.host.TemplateApp
import app.seb3thehacker.gearslip.media.MediaApp
import app.seb3thehacker.gearslip.media.MediaCatalog
import app.seb3thehacker.gearslip.notify.MessagingApp
import app.seb3thehacker.gearslip.notify.MessagingCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One tile on the launcher: either a phone app's own icon or a built-in glyph. */
private class Tile(
    val label: String,
    val icon: ImageBitmap? = null,
    val glyph: ImageVector? = null,
    /** Known not to work; drawn with a red X instead of being left off the launcher. */
    val broken: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** A launcher app's data, kept apart from [Tile] so it can be cached without capturing click state. */
internal class Entry(
    val label: String,
    val icon: ImageBitmap?,
    val template: TemplateApp? = null,
    val media: MediaApp? = null,
    val builtIn: BuiltInApp? = null,
    val messaging: MessagingApp? = null,
) {
    /** What a pin or a nav-bar shortcut remembers this entry as; null for nothing pinnable. */
    val componentId: String?
        get() = builtIn?.let { BUILT_IN_PREFIX + it.id }
            ?: messaging?.let { MESSAGING_PREFIX + it.packageName }
            ?: (template?.component ?: media?.component)?.flattenToString()

    val packageName: String?
        get() = template?.component?.packageName ?: media?.component?.packageName ?: messaging?.packageName
}

/** A messaging app has no component of its own to open, so its pin carries the package instead. */
internal const val MESSAGING_PREFIX = "messages:"

/**
 * The installed car and media apps with their icons. Scanning the package manager and decoding
 * icons is slow, so [Prefetch] does it ahead of time and again at the start of each car session,
 * which is when new installs are picked up.
 */
internal object LauncherCache {
    /** Observed, not read once: the nav bar is drawn before the car session's rescan finishes,
     * and an app installed since the last scan has to show up there when it does. */
    private val flow = MutableStateFlow<List<Entry>?>(null)
    val state: StateFlow<List<Entry>?> = flow.asStateFlow()

    val entries: List<Entry>? get() = flow.value

    /** Returns the scan if there is one; [force] rescans. Synchronized so two callers share one scan. */
    @Synchronized
    fun load(context: Context, force: Boolean = false): List<Entry> {
        if (!force) entries?.let { return it }
        fun iconOf(pkg: String) = runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap(128, 128).asImageBitmap()
        }.getOrNull()

        val carApps = CarAppCatalog.installed(context).associateBy { it.component.packageName }
        val mediaApps = MediaCatalog.installed(context).associateBy { it.component.packageName }
        val messagingApps = MessagingCatalog.installed(context).associateBy { it.packageName }

        // One decision per app over everything it offers a car - a car screen, a player, car
        // notifications - rather than a filter per kind that has to know about the others.
        val tiles = (carApps.keys + mediaApps.keys + messagingApps.keys).flatMap { pkg ->
            val icon by lazy { iconOf(pkg) }
            tilesFor(carApps[pkg], mediaApps[pkg], messagingApps[pkg], context.getString(R.string.media_browse)).map { tile ->
                when (tile) {
                    is AppTile.Screen -> Entry(tile.label, icon, template = tile.app)
                    is AppTile.Player -> Entry(tile.app.label, icon, media = tile.app)
                    is AppTile.Messages -> Entry(tile.app.label, icon, messaging = tile.app)
                }
            }
        }
        return tiles.sortedBy { it.label.lowercase() }.also { flow.value = it }
    }
}

/** What one app gets on the launcher. */
private sealed interface AppTile {
    class Screen(val app: TemplateApp, val label: String) : AppTile
    class Player(val app: app.seb3thehacker.gearslip.media.MediaApp) : AppTile
    class Messages(val app: app.seb3thehacker.gearslip.notify.MessagingApp) : AppTile
}

/**
 * The tiles for one app, from what it offers.
 *
 * - A car screen that is something other than media or messaging (RF Sentinel's POI screen) is
 *   the app: its media service only makes sound, and its notifications are alerts, so it gets the
 *   one tile under its own name.
 * - A media app with both a car screen and a player (Spotify) gets both, the screen labelled
 *   "· Browse", since they really are different screens - unless the screen is known to refuse
 *   Gearslip while the player works, when the Browse tile would only report that.
 * - A messenger's own car screen (Google Messages) won't start under Gearslip, and Gearslip's
 *   Messages screen does the job, so it gets only that - and a messenger that is also a media
 *   app keeps only its player.
 */
private fun tilesFor(
    car: TemplateApp?,
    media: app.seb3thehacker.gearslip.media.MediaApp?,
    messaging: app.seb3thehacker.gearslip.notify.MessagingApp?,
    browse: String,
): List<AppTile> {
    if (car != null && !car.isMediaScreen && car.kind != "messaging") return listOf(AppTile.Screen(car, car.label))
    val pkg = car?.component?.packageName ?: media?.component?.packageName ?: messaging?.packageName ?: return emptyList()
    return buildList {
        val screenRefused = KnownApps.screenBroken(pkg) && KnownApps.playerWorks(pkg)
        val messengerScreen = messaging != null && (car?.kind == "messaging" || car?.kind == "app")
        if (car != null && !messengerScreen && !(media != null && screenRefused)) {
            add(AppTile.Screen(car, if (media != null) "${car.label} · $browse" else car.label))
        }
        if (media != null) add(AppTile.Player(media))
        else if (messaging != null) add(AppTile.Messages(messaging))
    }
}

/** Every app the launcher knows: Gearslip's own and the installed ones, rescans included. */
@Composable
internal fun rememberAllEntries(): List<Entry> {
    val context = LocalContext.current
    val installed by LauncherCache.state.collectAsState()
    val launcher by LauncherMode.active.collectAsState()
    LaunchedEffect(context) {
        if (LauncherCache.entries == null) withContext(Dispatchers.IO) { LauncherCache.load(context) }
    }
    return remember(installed, launcher) {
        BuiltInApps.entries(launcher, LauncherMode.canCall(context)) + installed.orEmpty()
    }
}

/** The launcher's groups, in the order they're laid out. */
private enum class AppGroup { PHONE, MAPS, MUSIC, MESSAGING, WEB, SCREEN_SHARING, OTHER, SETTINGS, EXIT }

private fun groupOf(entry: Entry, mediaPackages: Set<String>): AppGroup {
    entry.builtIn?.let {
        return when (it) {
            BuiltInApps.Phone -> AppGroup.PHONE
            BuiltInApps.Web -> AppGroup.WEB
            BuiltInApps.ScreenSharing -> AppGroup.SCREEN_SHARING
            BuiltInApps.Exit -> AppGroup.EXIT
            else -> AppGroup.SETTINGS
        }
    }
    if (entry.media != null) return AppGroup.MUSIC
    if (entry.messaging != null) return AppGroup.MESSAGING
    val app = entry.template ?: return AppGroup.OTHER
    // A media app's "· Browse" tile sits next to its player.
    if (app.component.packageName in mediaPackages) return AppGroup.MUSIC
    return when (app.kind) {
        "calling" -> AppGroup.PHONE
        "navigation", "poi", "parking", "charging" -> AppGroup.MAPS
        // Template-only media apps may have no legacy browser/player tile to identify them.
        "media" -> AppGroup.MUSIC
        "messaging" -> AppGroup.MESSAGING
        "settings" -> AppGroup.SETTINGS
        else -> AppGroup.OTHER
    }
}

/**
 * The launcher's tiles in the order they're drawn: grouped by [AppGroup], alphabetical inside a
 * group with Settings last of all, then with the driver's own moves from [custom] laid over it.
 * Moved apps only trade places among themselves, so an app installed later still lands in its group.
 * [hidden] leaves tiles out of the drawer only: a pinned app stays on the nav bar.
 */
internal fun launcherOrder(all: List<Entry>, showVehicleData: Boolean, custom: List<String>, hidden: Set<String>): List<Entry> {
    val shown = all.filter { (it.builtIn !== BuiltInApps.VehicleData || showVehicleData) && it.componentId !in hidden }
    val mediaPackages = shown.mapNotNull { it.media?.component?.packageName }.toSet()
    val grouped = shown.sortedWith(
        compareBy<Entry>({ groupOf(it, mediaPackages) }, { it.builtIn === BuiltInApps.Settings }, { it.label.lowercase() }),
    )
    if (custom.isEmpty()) return grouped
    val rank = custom.withIndex().associate { (i, id) -> id to i }
    fun Entry.rank() = componentId?.let { rank[it] }
    val moved = grouped.filter { it.rank() != null }.sortedBy { it.rank() }.iterator()
    return grouped.map { if (it.rank() != null) moved.next() else it }
}

/** An app by the id a pin or the running list knows it by, built-in or installed. */
internal fun findEntry(id: String): Entry? =
    BuiltInApps.everyEntry.find { it.componentId == id } ?: LauncherCache.entries?.find { it.componentId == id }

/** Rescans the car and media apps now, replacing the cached list; the launcher keeps showing the old one meanwhile. */
fun warmLauncherApps(context: Context) { LauncherCache.load(context, force = true) }

/**
 * Connects or opens [entry] - what tapping its tile does, shared with the nav bar's pinned
 * shortcuts and its open-app icon so there is exactly one place that knows how to launch either
 * kind of app.
 */
internal fun launchEntry(entry: Entry, navigator: CarNavigator, frame: CarEnvironment.Frame) {
    entry.builtIn?.let { it.open(navigator); return }
    entry.template?.let {
        if (it.isNavigation) {
            CarServices.connectNav(it, frame)
            navigator.home()
        } else {
            CarServices.connectBrowse(it, frame)
            navigator.browse(entry.label)
        }
    }
    entry.media?.let { CarServices.openMedia(it); navigator.media() }
    entry.messaging?.let { navigator.messages(it.packageName, it.label) }
}

/** The app launcher: every app as an icon grid, grouped Phone, Maps, Music, Messaging, Web, Screen sharing, Settings. */
@Composable
fun CarLauncher() {
    val context = LocalContext.current
    val navigator = LocalCarNavigator.current
    val frame by CarEnvironment.frame.collectAsState()

    // Painted from the cache at once; scanned only if [Prefetch] has not got there first.
    val all = rememberAllEntries()
    val running = rememberRunningApps()
    val showVehicleData = rememberVehicleDataShown()

    fun tileOf(entry: Entry): Tile {
        // Only apps that don't work get a mark. The X rates an app's own car screens; a messenger
        // shown on Gearslip's own Messages screen has none, so it never gets one.
        val pkg = entry.packageName?.takeIf { entry.messaging == null }
        val id = entry.componentId
        return Tile(
            entry.builtIn?.let { context.getString(it.labelRes) } ?: entry.label, entry.icon, entry.builtIn?.glyph,
            broken = pkg != null && (KnownApps.isBroken(context, pkg) || (entry.template != null && KnownApps.screenBroken(pkg))),
            onLongClick = id?.let { { navigator.showAppMenu(it) } },
        ) { openApp(entry, id?.let { running[it] }, navigator, frame) }
    }

    val appOrder by CarSettings.appOrder.collectAsState()
    val hidden by CarSettings.hiddenApps.collectAsState()
    val tiles = launcherOrder(all, showVehicleData, appOrder, hidden).map(::tileOf)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(tiles) { AppTile(it) }
    }
}

/** A red disc with a white X: marks a tile known not to work with Gearslip yet. */
@Composable
private fun BrokenBadge(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(Color(0xFFD32F2F), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.launcher_broken), tint = Color.White, modifier = Modifier.size(size * 0.66f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(tile: Tile) {
    val shape = MaterialTheme.shapes.extraLarge
    // A long press opens the app's menu (pin, close) - the same gesture everywhere apps are
    // shown, here and on the nav bar. GsIconBox marks it in the theme's style.
    GsIconBox(
        onClick = tile.onClick,
        onLongClick = tile.onLongClick,
        modifier = Modifier.height(120.dp),
        colors = GsColors(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant),
        shape = shape,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box {
                    TileIcon(tile)
                    if (tile.broken) {
                        // Pinned to the icon's lower corner, ringed in the tile's colour so it
                        // reads as sitting on top of the icon.
                        BrokenBadge(
                            22.dp,
                            Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 4.dp)
                                .border(2.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                TileLabel(tile)
            }
        }
    }
}

@Composable
private fun TileIcon(tile: Tile) {
    when {
        tile.icon != null -> Image(tile.icon, contentDescription = null, modifier = Modifier.size(52.dp))
        tile.glyph != null -> Icon(tile.glyph, contentDescription = null, modifier = Modifier.size(52.dp))
    }
}

@Composable
private fun TileLabel(tile: Tile, maxLines: Int = 2, modifier: Modifier = Modifier) {
    Text(
        tile.label,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
