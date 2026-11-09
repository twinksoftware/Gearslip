package app.seb3thehacker.gearslip.car

import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import app.seb3thehacker.gearslip.car.theme.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import android.provider.Settings
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.MediaApp
import app.seb3thehacker.gearslip.media.MediaArt
import app.seb3thehacker.gearslip.media.LyricsResult
import app.seb3thehacker.gearslip.media.Lyrics
import app.seb3thehacker.gearslip.media.MediaEntry
import app.seb3thehacker.gearslip.media.NowPlaying
import app.seb3thehacker.gearslip.media.QueueTrack
import android.media.session.PlaybackState
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues

// The media screen's browse list, up-next queue, rows and icons. Split out of MediaScreen.kt.

@Composable
internal fun BrowsePanel(browse: app.seb3thehacker.gearslip.media.BrowseState, media: CarMedia, appLabel: String, modifier: Modifier) {
    Column(modifier) {
        // At the library root there's no trail and nowhere to go up to, so there's nothing this
        // row could say that the app's name in the top-left corner hasn't already - showing it
        // again here (as "Browse", or as the app's own name) was just the same label twice.
        if (media.canGoUp || browse.trail.isNotEmpty()) {
            Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (media.canGoUp) {
                    GsIconButton(Icons.Filled.ArrowBack, stringResource(R.string.media_up), media::up, size = 44.dp, shape = CircleShape)
                    Spacer(Modifier.width(14.dp))
                }
                browse.trail.lastOrNull()?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        when {
            browse.loading -> Notice(stringResource(R.string.media_loading), null)
            browse.unavailable -> Notice(
                stringResource(R.string.media_keeps_library, appLabel),
                stringResource(R.string.media_browse_start),
            )
            browse.failed -> Notice(stringResource(R.string.media_load_failed), null)
            browse.entries.isEmpty() -> Notice(stringResource(R.string.media_nothing_here), null)
            else -> BrowseList(browse.entries, media)
        }
    }
}

/**
 * A list laid out the way the app asked: under its section headings, covers where it asked for a
 * grid and rows everywhere else. A grid section among others is a shelf that scrolls sideways; a
 * list that is one grid and nothing else wraps into rows of covers instead.
 */
@Composable
private fun BrowseList(entries: List<MediaEntry>, media: CarMedia) {
    // A list's keys must be unique, and some apps list the same item twice (Music Assistant's
    // playlists, issue #90): the repeat would do the same thing anyway, so it's dropped.
    val sections = remember(entries) { browseSections(entries.distinctBy { it.id }) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val onlyGrid = sections.size == 1 && sections[0].grid && sections[0].heading == null
        val columns = ((maxWidth + SHELF_GAP) / (SHELF_COVER + SHELF_GAP)).toInt().coerceAtLeast(2)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            sections.forEachIndexed { i, section ->
                val heading = section.heading
                if (heading != null && sections.getOrNull(i - 1)?.heading != heading) {
                    item(key = "heading $i") {
                        Text(
                            heading,
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp),
                        )
                    }
                }
                when {
                    !section.grid -> items(section.entries, key = { it.id }) { entry -> EntryRow(entry, media) }
                    onlyGrid -> items(section.entries.chunked(columns)) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(SHELF_GAP)) {
                            row.forEach { CoverTile(it, media, Modifier.weight(1f)) }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    else -> item(key = "shelf $i") {
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(SHELF_GAP)) {
                            items(section.entries, key = { it.id }) { CoverTile(it, media, Modifier.width(SHELF_COVER)) }
                        }
                    }
                }
            }
        }
    }
}

private val SHELF_COVER = 132.dp
private val SHELF_GAP = 14.dp

/** A run of entries under one heading that are all covers or all rows. */
private class BrowseSection(val heading: String?, val grid: Boolean, val entries: List<MediaEntry>)

private fun browseSections(entries: List<MediaEntry>): List<BrowseSection> {
    val out = mutableListOf<BrowseSection>()
    var run = mutableListOf<MediaEntry>()
    for (entry in entries) {
        val last = run.lastOrNull()
        if (last != null && (last.group != entry.group || last.grid != entry.grid)) {
            out += BrowseSection(last.group, last.grid, run)
            run = mutableListOf()
        }
        run += entry
    }
    run.lastOrNull()?.let { out += BrowseSection(it.group, it.grid, run) }
    return out
}

/** One entry as a cover with its name under it: what the app's grids are made of. */
@Composable
private fun CoverTile(entry: MediaEntry, media: CarMedia, modifier: Modifier) {
    val context = LocalContext.current
    val icon by produceState(entry.iconBitmap, entry.iconUri) {
        value = entry.iconBitmap ?: MediaArt.load(context, entry.iconUri)
    }
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).clickable { media.select(entry) }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .gsPanelShadow(shape)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            icon?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Icon(
                    if (entry.browsable) MediaIcons.Folder else MediaIcons.MusicNote, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxSize(0.35f),
                )
        }
        Text(
            entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp),
        )
        if (entry.subtitle.isNotEmpty()) {
            Text(
                entry.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
            )
        }
    }
}

// --- up next -----------------------------------------------------------------------------------

/**
 * Whatever the app has queued up, starting right after the track that's playing now - not the
 * whole queue from the top, which would just repeat what the player beside it already shows. Apps
 * that never publish a queue (many don't) show an empty notice rather than a permanently blank tab.
 */
@Composable
internal fun QueuePanel(queue: List<QueueTrack>, now: NowPlaying, media: CarMedia, modifier: Modifier) {
    val upNext = remember(queue, now.activeQueueItemId) {
        val current = queue.indexOfFirst { it.queueId == now.activeQueueItemId }
        if (current >= 0) queue.drop(current + 1) else queue
    }
    Column(modifier) {
        if (upNext.isEmpty()) {
            Notice(
                stringResource(R.string.media_nothing_queued),
                stringResource(if (now.title.isEmpty()) R.string.media_queue_empty_idle else R.string.media_queue_empty_playing),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(upNext.distinctBy { it.queueId }, key = { it.queueId }) { track -> QueueRow(track, media) }
            }
        }
    }
}

@Composable
private fun QueueRow(track: QueueTrack, media: CarMedia) {
    val context = LocalContext.current
    val icon by produceState(track.iconBitmap, track.iconUri) {
        value = track.iconBitmap ?: MediaArt.load(context, track.iconUri)
    }
    Surface(
        onClick = { media.playQueueItem(track) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RowArt(icon, MediaIcons.MusicNote)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (track.subtitle.isNotEmpty()) {
                    Text(track.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.Filled.PlayArrow, stringResource(R.string.media_play), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun EntryRow(entry: MediaEntry, media: CarMedia, onClick: () -> Unit = { media.select(entry) }) {
    val context = LocalContext.current
    val icon by produceState(entry.iconBitmap, entry.iconUri) {
        value = entry.iconBitmap ?: MediaArt.load(context, entry.iconUri)
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RowArt(icon, if (entry.browsable) MediaIcons.Folder else MediaIcons.MusicNote)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.subtitle.isNotEmpty()) {
                    Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (entry.browsable && entry.playable) {
                GsIconButton(Icons.Filled.PlayArrow, stringResource(R.string.media_play), { media.playAll(entry) })
            }
            if (entry.browsable) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

/** A row's artwork, or a folder or note in its place when the app sends none. */
@Composable
private fun RowArt(icon: android.graphics.Bitmap?, fallback: ImageVector) {
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Image(icon.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(fallback, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
    }
}

/** Material's transport icons, from their published path data (they live in the extended set). */
internal object MediaIcons {
    val MusicNote = icon("MusicNote", "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z")
    val Folder = icon("Folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    val Pause = icon("Pause", "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z")
    val Next = icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    /** The 3x3 app grid; not in material-icons-core. */
    val Apps = icon("Apps", "M4,8h4L8,4L4,4v4zM10,20h4v-4h-4v4zM4,20h4v-4L4,16v4zM4,14h4v-4L4,10v4zM10,14h4v-4h-4v4zM16,4v4h4L20,4h-4zM10,8h4L14,4h-4v4zM16,14h4v-4h-4v4zM16,20h4v-4h-4v4z")
    val Previous = icon("SkipPrevious", "M6,6h2v12L6,18zM9.5,12l8.5,6V6z")
    val Equalizer = icon("Equalizer", "M10,20h4L14,4h-4v16zM4,20h4v-8L4,12v8zM16,9v11h4L20,9h-4z")
    val Shuffle = icon("Shuffle", "M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5L20,4h-5.5zM14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20L20,20v-5.5l-2.04,2.04 -3.13,-3.13z")
    val Repeat = icon("Repeat", "M7,7h10v3l4,-4 -4,-4v3L5,5v6h2L7,7zM17,17L7,17v-3l-4,4 4,4v-3h12v-6h-2v4z")
    val RepeatOne = icon("RepeatOne", "M7,7h10v3l4,-4 -4,-4v3L5,5v6h2L7,7zM17,17L7,17v-3l-4,4 4,4v-3h12v-6h-2v4zM13,15L13,9h-1l-2,1v1h1.5v4L13,15z")
    /**
     * A screen split into the map and a side panel: outlined while the panel is closed ("put the
     * player beside the map"), filled in while it's open ("close it"), on the side it opens on.
     */
    val DockRight = icon("DockRight", "M2,4h20v16H2zM4,6h10v12H4zM16,6h4v12h-4z", PathFillType.EvenOdd)
    val DockRightOpen = icon("DockRightOpen", "M2,4h20v16H2zM4,6h10v12H4z", PathFillType.EvenOdd)
    val DockLeft = icon("DockLeft", "M2,4h20v16H2zM4,6h4v12H4zM10,6h10v12H10z", PathFillType.EvenOdd)
    val DockLeftOpen = icon("DockLeftOpen", "M2,4h20v16H2zM10,6h10v12H10z", PathFillType.EvenOdd)
    val Fullscreen = icon("Fullscreen", "M7,14L5,14v5h5v-2L7,17v-3zM5,10h2L7,7h3L10,5L5,5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2L19,5h-5z")
    val FullscreenExit = icon("FullscreenExit", "M5,16h3v3h2v-5L5,14v2zM8,8L5,8v2h5L10,5L8,5v3zM14,19h2v-3h3v-2h-5v5zM16,8L16,5h-2v5h5L19,8h-3z")

    val CallEnd = icon("CallEnd", "M12,9c-1.6,0 -3.15,0.25 -4.6,0.72v3.1c0,0.39 -0.23,0.74 -0.56,0.9 -0.98,0.49 -1.87,1.12 -2.66,1.85 -0.18,0.18 -0.43,0.28 -0.7,0.28 -0.28,0 -0.53,-0.11 -0.71,-0.29L0.29,13.08c-0.18,-0.17 -0.29,-0.42 -0.29,-0.7 0,-0.28 0.11,-0.53 0.29,-0.71C3.34,8.78 7.46,7 12,7s8.66,1.78 11.71,4.67c0.18,0.18 0.29,0.43 0.29,0.71 0,0.28 -0.11,0.53 -0.29,0.71l-2.48,2.48c-0.18,0.18 -0.43,0.29 -0.71,0.29 -0.27,0 -0.52,-0.11 -0.7,-0.28 -0.79,-0.74 -1.69,-1.36 -2.67,-1.85 -0.33,-0.16 -0.56,-0.5 -0.56,-0.9v-3.1C15.15,9.25 13.6,9 12,9z")
    val Mic = icon("Mic", "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
    val MicOff = icon("MicOff", "M19,11h-1.7c0,0.74 -0.16,1.43 -0.43,2.05l1.23,1.23c0.56,-0.98 0.9,-2.09 0.9,-3.28zM14.98,11.17c0,-0.06 0.02,-0.11 0.02,-0.17L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v0.18l5.98,5.99zM4.27,3L3,4.27l6.01,6.01L9,11c0,1.66 1.33,3 2.99,3 0.22,0 0.44,-0.03 0.65,-0.08l1.66,1.66c-0.71,0.33 -1.5,0.52 -2.31,0.52 -2.76,0 -5.3,-2.1 -5.3,-5.1L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c0.91,-0.13 1.77,-0.45 2.54,-0.9L19.73,21 21,19.73 4.27,3z")

    private fun icon(name: String, path: String, fillType: PathFillType = PathFillType.NonZero) = ImageVector.Builder(
        name, 24.dp, 24.dp, 24f, 24f,
    ).addPath(addPathNodes(path), pathFillType = fillType, fill = SolidColor(androidx.compose.ui.graphics.Color.Black)).build()
}
