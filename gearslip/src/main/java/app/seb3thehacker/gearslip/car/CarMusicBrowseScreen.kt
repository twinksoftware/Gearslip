package app.seb3thehacker.gearslip.car

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.car.theme.*
import app.seb3thehacker.gearslip.catalog.MusicCatalog
import app.seb3thehacker.gearslip.media.MediaArt
import app.seb3thehacker.gearslip.media.MediaEntry
import app.seb3thehacker.gearslip.music.playback.MusicPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAB_BROWSE = 0
private const val TAB_SEARCH = 1
private const val TAB_LIBRARY = 2
private const val SEARCH_PAUSE_MS = 600L

/**
 * Nothing-style music browse page backed by the connected media app. BROWSE shows that app's
 * browse tree, SEARCH runs its own library search with the car's keyboard, and LIBRARY is the
 * current play queue. Playback and transport go through [MusicPlayer], which mirrors the app's
 * session; Gearslip does not stream anything itself.
 */
@Composable
fun FirstPartyMusicScreen(onOpenPlayer: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(TAB_BROWSE) }
    var query by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(true) }
    var shelves by remember { mutableStateOf<List<MusicCatalog.Shelf>>(emptyList()) }
    var results by remember { mutableStateOf<List<MusicCatalog.Track>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val trimmed = query.trim()
    val queue by MusicPlayer.queue.collectAsStateWithLifecycle()
    // Folders descend into the connected app's browse tree; tracks play from its session.
    val onBrowse: (String) -> Unit = { name ->
        scope.launch {
            MusicCatalog.browse(name)
            shelves = MusicCatalog.home()
        }
    }

    LaunchedEffect(Unit) {
        MusicPlayer.init(context.applicationContext)
        val loaded = runCatching {
            MusicCatalog.initialize(context.applicationContext)
            MusicCatalog.home()
        }
        shelves = loaded.getOrDefault(emptyList())
        loadError = loaded.exceptionOrNull()?.message
        busy = false
    }

    // Debounced online search; a short query clears the list rather than hammering the service.
    LaunchedEffect(trimmed, tab) {
        if (tab != TAB_SEARCH || trimmed.length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(SEARCH_PAUSE_MS)
        busy = true
        val found = runCatching { MusicCatalog.search(trimmed) }
        results = found.getOrDefault(emptyList())
        loadError = found.exceptionOrNull()?.message
        busy = false
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            DotGrid(Modifier.fillMaxSize())
            Column(
                Modifier.fillMaxSize().padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = 16.dp,
                    bottom = 16.dp + LocalNavBarInset.current,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "MUSIC",
                        style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 0.08.em),
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    MusicTab("BROWSE", tab == TAB_BROWSE, Modifier.weight(1f)) { tab = TAB_BROWSE }
                    MusicTab("SEARCH", tab == TAB_SEARCH, Modifier.weight(1f)) { tab = TAB_SEARCH }
                    MusicTab("LIBRARY", tab == TAB_LIBRARY, Modifier.weight(1f)) { tab = TAB_LIBRARY }
                }
                Spacer(Modifier.height(12.dp))
                when (tab) {
                    TAB_BROWSE -> BrowseContent(busy, loadError, shelves, Modifier.weight(1f).fillMaxWidth(), onBrowse) { tracks, index ->
                        play(tracks, index, onOpenPlayer)
                    }
                    TAB_SEARCH -> SearchContent(
                        query, { query = it }, typing, { typing = it },
                        busy, loadError, results, Modifier.weight(1f).fillMaxWidth(),
                    ) { tracks, index -> play(tracks, index, onOpenPlayer) }
                    else -> LibraryContent(queue, Modifier.weight(1f).fillMaxWidth()) { tracks, index ->
                        play(tracks, index, onOpenPlayer)
                    }
                }
            }
        }
    }
}

private fun play(tracks: List<MusicCatalog.Track>, index: Int, onOpenPlayer: () -> Unit) {
    if (tracks.isEmpty() || index !in tracks.indices) return
    MusicPlayer.play(tracks, index)
    onOpenPlayer()
}

/** One large, square page tab; shared with the player's LYRICS/QUEUE switch. */
@Composable
internal fun MusicTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    GsIconBox(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        colors = if (selected) GsColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        else GsColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
        shape = RectangleShape,
        latched = selected,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.08.em), maxLines = 1)
    }
}

@Composable
private fun BrowseContent(
    busy: Boolean,
    error: String?,
    shelves: List<MusicCatalog.Shelf>,
    modifier: Modifier,
    onBrowse: (String) -> Unit,
    onPlay: (List<MusicCatalog.Track>, Int) -> Unit,
) {
    when {
        busy -> EmptyState("LOADING CATALOG", "Asking the music service for its home shelves", modifier)
        error != null && shelves.isEmpty() -> EmptyState("OFFLINE", error, modifier)
        shelves.isEmpty() -> EmptyState("NOTHING HERE", "Try SEARCH to find a song by name", modifier)
        else -> {
            val tracks = shelves.flatMap { it.tracks }
            val entries = shelves.flatMap { shelf -> shelf.tracks.map { it.toEntry(shelf.title) } }
            EntryShelves(entries, modifier) { entry ->
                if (!entry.playable) {
                    onBrowse(entry.title)
                } else {
                    val index = tracks.indexOfFirst { it.id == entry.id }
                    if (index >= 0) onPlay(tracks, index)
                }
            }
        }
    }
}

@Composable
private fun LibraryContent(
    queue: List<MusicCatalog.Track>,
    modifier: Modifier,
    onPlay: (List<MusicCatalog.Track>, Int) -> Unit,
) {
    if (queue.isEmpty()) {
        EmptyState("EMPTY LIBRARY", "Play something from BROWSE or SEARCH first", modifier)
        return
    }
    val entries = queue.map { it.toEntry("PLAY QUEUE") }
    EntryShelves(entries, modifier) { entry ->
        val index = queue.indexOfFirst { it.id == entry.id }
        if (index >= 0) onPlay(queue, index)
    }
}

/** Maps a catalog track onto the car's shared entry tile so browse, search and queue look alike. */
private fun MusicCatalog.Track.toEntry(group: String?) = MediaEntry(
    id = id,
    title = title,
    subtitle = listOfNotNull(artists.takeIf(String::isNotEmpty), album).joinToString(" · "),
    iconUri = thumbnailUrl?.let(Uri::parse),
    iconBitmap = null,
    browsable = !playable,
    playable = playable,
    grid = true,
    group = group,
)

@Composable
private fun SearchContent(
    query: String,
    onQuery: (String) -> Unit,
    typing: Boolean,
    onTyping: (Boolean) -> Unit,
    busy: Boolean,
    error: String?,
    results: List<MusicCatalog.Track>,
    modifier: Modifier,
    onPlay: (List<MusicCatalog.Track>, Int) -> Unit,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp).border(1.dp, MaterialTheme.colorScheme.outline),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, Modifier.padding(start = 12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (query.isEmpty()) "SONG, ARTIST OR ALBUM" else query,
                style = MaterialTheme.typography.labelLarge,
                color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable { onTyping(true) }.padding(12.dp),
            )
            if (query.isNotEmpty()) {
                GsButton(onClick = { onQuery(""); onTyping(true) }, tone = GsTone.Neutral) { Text("CLEAR") }
            }
        }
        if (typing) {
            CarKeyboard(
                text = query,
                onTextChange = onQuery,
                onSubmit = { if (query.trim().isNotEmpty()) onTyping(false) },
                onDismiss = { onTyping(false) },
            )
            Spacer(Modifier.height(8.dp))
        }
        when {
            query.trim().isEmpty() -> EmptyState("SEARCH", "Type a name with the keys above", Modifier.weight(1f).fillMaxWidth())
            busy && results.isEmpty() -> EmptyState("SEARCHING", "Asking the music service for matches", Modifier.weight(1f).fillMaxWidth())
            error != null && results.isEmpty() -> EmptyState("SEARCH FAILED", error, Modifier.weight(1f).fillMaxWidth())
            results.isEmpty() -> EmptyState("NO RESULTS", "Nothing matched ${query.trim()}", Modifier.weight(1f).fillMaxWidth())
            else -> {
                val entries = results.map { it.toEntry("RESULTS") }
                EntryShelves(entries, Modifier.weight(1f).fillMaxWidth()) { entry ->
                    val index = results.indexOfFirst { it.id == entry.id }
                    if (index >= 0) onPlay(results, index)
                }
            }
        }
    }
}

private class MusicSection(val heading: String?, val grid: Boolean, val entries: List<MediaEntry>)

/** Consecutive entries share a heading and a cover/row layout, the way apps file them. */
private fun sectionsOf(entries: List<MediaEntry>): List<MusicSection> {
    val out = mutableListOf<MusicSection>()
    var run = mutableListOf<MediaEntry>()
    fun flush() {
        if (run.isEmpty()) return
        out += MusicSection(run.last().group, run.last().grid, run.toList())
        run = mutableListOf()
    }
    for (entry in entries) {
        val last = run.lastOrNull()
        if (last != null && (last.group != entry.group || last.grid != entry.grid)) flush()
        run += entry
    }
    flush()
    return out
}

@Composable
private fun EntryShelves(
    entries: List<MediaEntry>,
    modifier: Modifier,
    onPlay: (MediaEntry) -> Unit,
) {
    val sections = remember(entries) { sectionsOf(entries.distinctBy { it.id }) }
    LazyColumn(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        sections.forEachIndexed { i, section ->
            if (section.heading != null) {
                item(key = "heading $i") {
                    Text(
                        section.heading.uppercase(),
                        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.08.em),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = if (i == 0) 0.dp else 6.dp),
                    )
                }
            }
            if (section.grid) {
                item(key = "shelf $i") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(section.entries, key = { "cover $i ${it.id}" }) { CoverTile(it, onPlay) }
                    }
                }
            } else {
                items(section.entries, key = { "row $i ${it.id}" }) { RowTile(it, onPlay) }
            }
        }
    }
}

@Composable
private fun CoverTile(entry: MediaEntry, onPlay: (MediaEntry) -> Unit) {
    val art = rememberEntryArt(entry)
    Column(Modifier.width(152.dp).clickable { onPlay(entry) }) {
        EntryArt(
            art, entry.browsable,
            Modifier.fillMaxWidth().aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, MaterialTheme.colorScheme.outline),
            seed = entry.id,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            entry.title.uppercase(),
            style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.08.em),
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.subtitle.isNotEmpty()) {
            Text(
                entry.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RowTile(entry: MediaEntry, onPlay: (MediaEntry) -> Unit) {
    val art = rememberEntryArt(entry)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .clickable { onPlay(entry) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryArt(
            art, entry.browsable,
            Modifier.size(52.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant),
            seed = entry.id,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.subtitle.isNotEmpty()) {
                Text(
                    entry.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            if (entry.playable) Icons.Filled.PlayArrow else MediaIcons.Folder,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Cover art, or a deterministic monochrome dot-matrix stand-in when the service sends no image.
 * [seed] fixes the pattern per entry so the same track always gets the same "cover".
 */
@Composable
internal fun EntryArt(bitmap: Bitmap?, browsable: Boolean, modifier: Modifier, seed: String? = null) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            DotMatrixCover(seed ?: if (browsable) "folder" else "track", Modifier.fillMaxSize())
        }
    }
}

/** A mirrored 7x7 dot matrix standing in for missing cover art. One colour, no shadow. */
@Composable
private fun DotMatrixCover(seed: String, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val cols = 7
        val rows = 7
        val step = size.minDimension / cols
        val radius = step * 0.26f
        val startX = (size.width - step * cols) / 2f + step / 2f
        val startY = (size.height - step * rows) / 2f + step / 2f
        var bits = (seed.hashCode().toLong() and 0x7fffffffL) xor 0x2a2a2a2aL
        for (r in 0 until rows) {
            for (c in 0..(cols / 2)) {
                val lit = (bits and 1L) == 1L
                bits = (bits ushr 1) or (bits shl 30)
                if (!lit) continue
                drawCircle(color, radius, Offset(startX + c * step, startY + r * step))
                val mirror = cols - 1 - c
                if (mirror != c) drawCircle(color, radius, Offset(startX + mirror * step, startY + r * step))
            }
        }
    }
}

@Composable
private fun rememberEntryArt(entry: MediaEntry): Bitmap? {
    val context = LocalContext.current
    val art by produceState(entry.iconBitmap, entry.iconBitmap, entry.iconUri) {
        value = entry.iconBitmap ?: MediaArt.load(context, entry.iconUri)
    }
    return art
}

/** Shared by the player screen's LYRICS/QUEUE empty states. */
@Composable
internal fun EmptyState(title: String, detail: String?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.08.em),
            fontWeight = FontWeight.Bold,
        )
        if (detail != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
