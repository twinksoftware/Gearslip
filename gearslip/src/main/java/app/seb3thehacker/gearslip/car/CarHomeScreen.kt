package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.layout.offset
import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.car.theme.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import app.seb3thehacker.gearslip.car.theme.gsPanelShadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import android.support.v4.media.session.PlaybackStateCompat
import app.seb3thehacker.gearslip.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.media.session.PlaybackState
import app.seb3thehacker.gearslip.media.MediaArt
import kotlinx.coroutines.delay
import androidx.compose.material3.Icon
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.seb3thehacker.gearslip.media.ArtColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.host.CarAppConnection
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.NowPlaying
import kotlinx.coroutines.launch

/**
 * The home screen: the map, with the docked player floating over one side of it when asked for.
 * With the player on the left, the map app's cards move to the map's far edge, so the player, the
 * map and the cards each keep their own space.
 *
 * The map is whatever navigation app was used last, brought back on its own. The player sits in
 * the nav bar's pill until its dock button moves it here; the column closes itself back to a
 * full-width map once nothing is playing.
 */
@Composable
fun CarHome() {
    val frame by CarEnvironment.frame.collectAsStateWithLifecycle()
    val navStatus by CarServices.nav.status.collectAsStateWithLifecycle()
    val phase by CarServices.media.phase.collectAsStateWithLifecycle()
    val now by CarServices.media.now.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { CarServices.autostart(frame) }
    LaunchedEffect(phase) { if (phase == CarMedia.Phase.READY) CarServices.autoplayIfDue() }

    val navigator = LocalCarNavigator.current
    val panel = navigator.sidePanel

    // Coming back to the map from another screen: the map stayed loaded underneath, so its surface
    // is only replaced if it died meanwhile. The first composition is skipped, since the surface
    // is only just being lent.
    val onHome = navigator.current == CarScreen.Home
    val seenHome = remember { booleanArrayOf(false) }
    LaunchedEffect(onHome) {
        if (onHome && seenHome[0]) CarServices.nav.replaceDeadSurface()
        if (onHome) seenHome[0] = true
    }
    val splitScreen = phase == CarMedia.Phase.READY && now.isActive && panel != SidePanel.NONE
    val playerLeft by CarSettings.playerLeft.collectAsStateWithLifecycle()
    // What the card showed last, so it still has something to show while it slides away.
    val shown = remember { androidx.compose.runtime.mutableStateOf(SidePanel.CONTROLS) }
    if (panel != SidePanel.NONE) shown.value = panel

    // The map runs edge to edge underneath; the player floats over one side of it as a card, with
    // a gap all round. The map's surface keeps its size when the card comes and goes, so the app
    // only re-centres its map instead of drawing onto a new surface.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val column = (maxWidth / PLAYER_SHARE).coerceIn(PLAYER_MIN_WIDTH, PLAYER_MAX_WIDTH)
        androidx.compose.runtime.CompositionLocalProvider(
            LocalCardsAtEnd provides (splitScreen && playerLeft),
            LocalSidePanelInset provides if (splitScreen) column else 0.dp,
        ) {
            MapPane(navStatus, frame)
        }
        androidx.compose.animation.AnimatedVisibility(
            splitScreen,
            Modifier.align(if (playerLeft) Alignment.CenterStart else Alignment.CenterEnd),
            enter = androidx.compose.animation.slideInHorizontally(tween(220)) { if (playerLeft) -it / 3 else it / 3 } +
                androidx.compose.animation.fadeIn(tween(180)),
            exit = androidx.compose.animation.slideOutHorizontally(tween(180)) { if (playerLeft) -it / 3 else it / 3 } +
                androidx.compose.animation.fadeOut(tween(140)),
        ) {
            val card = Modifier
                .width(column)
                .fillMaxHeight()
                .padding(
                    start = if (playerLeft) CARD_GAP else 0.dp,
                    end = if (playerLeft) 0.dp else CARD_GAP,
                    top = CARD_GAP,
                    bottom = LocalNavBarInset.current + CARD_GAP,
                )
            if (shown.value == SidePanel.LYRICS) SideLyrics(now, card) else DockedPlayer(now, card)
        }
    }
}

/** The player takes one part in this many of the screen's width. */
private const val PLAYER_SHARE = 3.6f

/** Narrow enough to leave the map most of a small screen; on a wide one the map gets the extra room. */
private val PLAYER_MIN_WIDTH = 240.dp
private val PLAYER_MAX_WIDTH = 340.dp

/** Between the player card and the screen's edges, and the map's own cards beside it. */
private val CARD_GAP = 12.dp

/** The card's corners: rounder than a button's, as the map app's own cards are. */
private val CARD_SHAPE = RoundedCornerShape(24.dp)

@Composable
private fun MapPane(status: CarAppConnection.Status, frame: CarEnvironment.Frame) {
    val navigator = LocalCarNavigator.current
    if (status.phase == CarAppConnection.Phase.RUNNING) {
        CarAppStage(CarServices.nav, status, frame, embedded = true) { CarServices.stopNav() }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(
            start = if (LocalCardsAtEnd.current) LocalSidePanelInset.current else 0.dp,
            end = if (LocalCardsAtEnd.current) 0.dp else LocalSidePanelInset.current,
        ).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val hasLast = CarSettings.lastNav.value != null
        val hasNavApps by CarServices.hasNavApps.collectAsStateWithLifecycle()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        when (status.phase) {
            CarAppConnection.Phase.BINDING, CarAppConnection.Phase.HANDSHAKE ->
                Text(status.app?.let { stringResource(R.string.home_starting, it) } ?: stringResource(R.string.home_starting_map), style = MaterialTheme.typography.titleMedium)
            CarAppConnection.Phase.REJECTED, CarAppConnection.Phase.FAILED -> {
                Text(status.app?.let { stringResource(R.string.home_failed, it) } ?: stringResource(R.string.home_failed_map), style = MaterialTheme.typography.titleMedium)
                status.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(8.dp))
                StatusButton(stringResource(R.string.home_choose_app), GsTone.Primary, navigator::apps)
            }
            else -> if (!hasNavApps) {
                // Nothing to auto-select and nothing to pick from Car apps either - this is the
                // only case worth a distinct screen, since every other IDLE case resolves itself.
                Text(stringResource(R.string.home_no_maps), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.home_no_maps_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (hasLast) {
                // IDLE with a last app on record means the driver stopped it themselves - it will
                // not come back on its own, so the screen must offer a way to bring it back rather
                // than sit on a "starting" message that never resolves.
                Text(stringResource(R.string.home_map_stopped), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.home_map_stopped_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // Side by side, the same size, the likely one lit: one clear choice, not a stack.
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatusButton(stringResource(R.string.home_reopen_map), GsTone.Primary) { scope.launch { CarServices.reconnectNav(frame) } }
                    StatusButton(stringResource(R.string.home_car_apps), GsTone.Tonal) { navigator.apps() }
                }
            } else {
                // Autostart is still picking one - a navigation app is installed, so this resolves
                // to a "Starting..." state itself, in the next frame or two.
            }
        }
    }
}

/** A big, even button for the map pane's few choices. */
@Composable
private fun StatusButton(label: String, tone: GsTone, onClick: () -> Unit) {
    GsButton(onClick = onClick, tone = tone, modifier = Modifier.width(220.dp).height(64.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The player, docked beside the map: the art as large as it fits, then title, progress, transport,
 * and like, shuffle, repeat and lyrics along the bottom. While it's here the nav bar's pill steps aside on Home, so the controls never show
 * twice. The X sends it back to the nav bar.
 */
@Composable
private fun DockedPlayer(now: NowPlaying, modifier: Modifier) {
    val navigator = LocalCarNavigator.current
    val media = CarServices.media
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val art by produceState(now.art, now.art, now.artUri) { value = now.art ?: MediaArt.load(context, now.artUri) }

    // The theme's surface, with a faint wash of the cover's colours behind the top of it. A solid
    // slab of album colour beside the map fought it for attention; this only hints at the song.
    Surface(modifier = modifier.gsPanelShadow(CARD_SHAPE), shape = CARD_SHAPE, color = scheme.surfaceContainer) {
        AlbumGlow(art, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                // The cover gets everything the controls leave, as big a square as fits, sitting
                // right on top of the title; the controls stay pinned to the bottom.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    val artSize = minOf(maxWidth, maxHeight).coerceAtLeast(0.dp)
                    // Art opens the full media screen, same as on the pill.
                    val coverShape = RoundedCornerShape(16.dp)
                    Box(
                        Modifier
                            .size(artSize)
                            .gsPanelShadow(coverShape)
                            .clip(coverShape)
                            .background(scheme.surface)
                            .clickable { navigator.media() },
                    ) {
                        // A new song's cover fades in over the old one instead of cutting to it.
                        androidx.compose.animation.Crossfade(art, animationSpec = tween(350), label = "cover") { shown ->
                            shown?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    now.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    now.artist, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (now.durationMs > 0) {
                    PlayerProgress(now, media, Modifier.fillMaxWidth().padding(vertical = 2.dp), showTimes = false)
                } else {
                    Spacer(Modifier.height(10.dp))
                }
                // Sized from the column's width rather than fixed: fixed sizes filled a narrow
                // column edge to edge, so the buttons sat squeezed against each other. Each row
                // keeps a clear gap between buttons, and none grows past a comfortable size.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val w = maxWidth
                    // Two skips, play at 1.25 times a skip, and two gaps of at least 20dp.
                    val skip = ((w - 40.dp) / 3.25f).coerceIn(36.dp, 48.dp)
                    // Four buttons and three gaps of at least 12dp.
                    val mode = ((w - 36.dp) / 4).coerceIn(34.dp, 42.dp)
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        TransportRow(now, media, skip = skip, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        PlayModeRow(now, media, size = mode, modifier = Modifier.fillMaxWidth()) {
                            GsIconButton(
                                ImageVector.vectorResource(R.drawable.lyrics_24), stringResource(R.string.storage_lyrics), navigator::showSideLyrics,
                                size = mode, iconSize = mode * 0.52f,
                            )
                        }
                    }
                }
            }
            // Round like the player's other buttons, and tucked into the card's corner so it
            // covers as little of the cover as it can.
            GsIconButton(
                Icons.Filled.Close, stringResource(R.string.home_back_nav), navigator::closeSidePanel,
                Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp), size = 32.dp, iconSize = 18.dp,
                shape = CircleShape,
            )
        }
    }
}

/**
 * Lyrics alone beside the map, big enough to read at a glance. The header is slim, since the
 * player goes back to the nav bar meanwhile and names the song there. Its note button swaps the
 * lyrics back for the player; the X closes the panel.
 */
@Composable
private fun SideLyrics(now: NowPlaying, modifier: Modifier) {
    val navigator = LocalCarNavigator.current
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val art by produceState(now.art, now.art, now.artUri) { value = now.art ?: MediaArt.load(context, now.artUri) }
    Surface(modifier = modifier.gsPanelShadow(CARD_SHAPE), shape = CARD_SHAPE, color = scheme.surfaceContainer) {
        Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(scheme.surface)) {
                    art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Spacer(Modifier.size(10.dp))
                Text(
                    listOf(now.title, now.artist).filter { it.isNotEmpty() }.joinToString(" · "),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // The way back to the player, as the player's own Lyrics button is the way here.
                GsIconButton(MediaIcons.MusicNote, stringResource(R.string.home_show_player), navigator::showSidePlayer, size = 36.dp, iconSize = 20.dp)
                Spacer(Modifier.size(6.dp))
                GsIconButton(Icons.Filled.Close, stringResource(R.string.home_hide_lyrics), navigator::closeSidePanel, size = 36.dp, iconSize = 20.dp)
            }
            LyricsPanel(now, Modifier.weight(1f).fillMaxWidth(), lineStyle = MaterialTheme.typography.headlineSmall)
        }
    }
}
