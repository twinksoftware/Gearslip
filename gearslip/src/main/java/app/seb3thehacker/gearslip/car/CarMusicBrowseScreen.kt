package app.seb3thehacker.gearslip.car

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.car.theme.*
import app.seb3thehacker.gearslip.media.BrowseState
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.MediaArt
import app.seb3thehacker.gearslip.media.MediaEntry
import kotlinx.coroutines.delay

private const val TAB_BROWSE = 0
private const val TAB_SEARCH = 1
private const val TAB_LIBRARY = 2
private const val SEARCH_PAUSE_MS = 600L

/**
 * First-party music browse page. Every row comes from the connected media app: there is no
 * local-file library here. Browse shows the app's tree, Search asks the app to name its matches,
 * and Library is the playable part of the same shared tree.
 */
@Composable
fun FirstPartyMusicScreen() {
    val media = CarServices.media
    val phase by media.phase.collectAsStateWithLifecycle()
    val browse by media.browse.collectAsStateWithLifecycle()
    val results by media.results.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(TAB_BROWSE) }
    var query by remember { mutableStateOf("") }
    val trimmed = query.trim()

    // Library search fills the list; submitting (button or IME) plays through CarMedia.search.
    LaunchedEffect(trimmed, tab) {
        if (tab != TAB_SEARCH || trimmed.isEmpty()) {
            media.clearResults()
            return@LaunchedEffect
        }
        delay(SEARCH_PAUSE_MS)
        media.searchLibrary(trimmed)
    }
    DisposableEffect(Unit) { onDispose { media.clearResults() } }

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
                    TAB_BROWSE -> BrowseContent(phase, browse, media, Modifier.weight(1f).fillMaxWidth())
                    TAB_SEARCH -> SearchContent(query, { query = it }, trimmed, results, media, Modifier.weight(1f).fillMaxWidth())
                    else -> LibraryContent(phase, browse, media, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
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
private fun BrowseContent(phase: CarMedia.Phase, browse: BrowseState, media: CarMedia, modifier: Modifier) {
    when {
        phase == CarMedia.Phase.CONNECTING -> EmptyState("CONNECTING", "Waiting for the music app", modifier)
        phase == CarMedia.Phase.REJECTED -> EmptyState("NO MUSIC APP", "Open a music app on the phone, then try again", modifier)
        phase == CarMedia.Phase.IDLE -> EmptyState("NO MUSIC APP", "Choose a music app to see what it shares", modifier)
        browse.loading -> EmptyState("LOADING", null, modifier)
        browse.unavailable -> EmptyState("LIBRARY NOT SHARED", "This app keeps its library to itself. Use SEARCH to play by name.", modifier)
        browse.failed -> EmptyState("COULDN'T LOAD", "Try the folder again", modifier)
        browse.entries.isEmpty() -> EmptyState("NOTHING HERE", "This folder is empty", modifier)
        else -> EntryShelves(browse.entries, media, modifier)
    }
}

@Composable
private fun LibraryContent(phase: CarMedia.Phase, browse: BrowseState, media: CarMedia, modifier: Modifier) {
    val library = remember(browse.entries) { browse.entries.filter { it.playable || it.grid }.distinctBy { it.id } }
    when {
        phase == CarMedia.Phase.CONNECTING -> EmptyState("CONNECTING", "Waiting for the music app", modifier)
        phase == CarMedia.Phase.REJECTED -> EmptyState("NO MUSIC APP", "Open a music app on the phone", modifier)
        phase == CarMedia.Phase.IDLE -> EmptyState("NO MUSIC APP", "Choose a music app to see what it shares", modifier)
        phase == CarMedia.Phase.READY && browse.unavailable ->
            EmptyState("NO LIBRARY", "The connected app doesn't share a library to browse", modifier)
        browse.loading -> EmptyState("LOADING", null, modifier)
        library.isEmpty() -> EmptyState("EMPTY LIBRARY", "Nothing shared to browse yet", modifier)
        else -> EntryShelves(library, media, modifier)
    }
}

@Composable
private fun SearchContent(
    query: String,
    onQuery: (String) -> Unit,
    trimmed: String,
    results: CarMedia.Results?,
    media: CarMedia,
    modifier: Modifier,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f).height(60.dp).border(1.dp, MaterialTheme.colorScheme.outline),
                placeholder = { Text("SONG, ARTIST OR ALBUM", style = MaterialTheme.typography.labelLarge) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                shape = RectangleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (trimmed.isNotEmpty()) media.search(trimmed) }),
            )
            GsButton(onClick = { if (trimmed.isNotEmpty()) media.search(trimmed) }, enabled = trimmed.isNotEmpty()) {
                Text("PLAY")
            }
            GsButton(onClick = { onQuery("") }, tone = GsTone.Neutral) { Text("CLEAR") }
        }
        val current = results?.takeIf { it.query == trimmed }
        val entries = current?.entries
        when {
            trimmed.isEmpty() -> EmptyState("SEARCH", "Type a name, then press PLAY", Modifier.weight(1f).fillMaxWidth())
            current == null || entries == null ->
                EmptyState("SEARCHING", "Asking the music app for matches", Modifier.weight(1f).fillMaxWidth())
            !current.supported -> EmptyState(
                "NO MATCH LIST",
                "This app can't list results. Press PLAY to let it choose.",
                Modifier.weight(1f).fillMaxWidth(),
            )
            entries.isEmpty() -> EmptyState("NO RESULTS", "Nothing matched $trimmed", Modifier.weight(1f).fillMaxWidth())
            else -> EntryShelves(entries, media, Modifier.weight(1f).fillMaxWidth())
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
private fun EntryShelves(entries: List<MediaEntry>, media: CarMedia, modifier: Modifier) {
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
                        items(section.entries, key = { "cover $i ${it.id}" }) { CoverTile(it, media) }
                    }
                }
            } else {
                items(section.entries, key = { "row $i ${it.id}" }) { RowTile(it, media) }
            }
        }
    }
}

@Composable
private fun CoverTile(entry: MediaEntry, media: CarMedia) {
    val art = rememberEntryArt(entry)
    Column(Modifier.width(152.dp).clickable { playEntry(entry, media) }) {
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
private fun RowTile(entry: MediaEntry, media: CarMedia) {
    val art = rememberEntryArt(entry)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .clickable { playEntry(entry, media) }
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
 * Cover art, or a deterministic monochrome dot-matrix stand-in when the app sends no image.
 * [seed] fixes the pattern per entry so the same folder always gets the same "cover"; it defaults
 * so the player screen can keep drawing its own art-less square without passing one.
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

/**
 * A mirrored 7x7 dot matrix standing in for missing cover art. Lit cells are drawn from the hash
 * of [seed] and mirrored across the vertical axis, so the result is a centred, repeatable motif
 * that reads as artwork rather than a grey Material folder glyph. One colour, no shadow.
 */
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

private fun playEntry(entry: MediaEntry, media: CarMedia) {
    if (entry.playable) media.playAll(entry) else media.select(entry)
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
