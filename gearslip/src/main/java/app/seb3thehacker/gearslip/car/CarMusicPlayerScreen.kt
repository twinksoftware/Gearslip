package app.seb3thehacker.gearslip.car

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.car.theme.*
import app.seb3thehacker.gearslip.catalog.MusicCatalog
import app.seb3thehacker.gearslip.media.Lyrics
import app.seb3thehacker.gearslip.media.LyricsResult
import app.seb3thehacker.gearslip.media.MediaArt
import app.seb3thehacker.gearslip.music.playback.MusicPlayer
import kotlinx.coroutines.delay

private const val PANEL_LYRICS = 0
private const val PANEL_QUEUE = 1

/**
 * The full-screen first-party player, laid out for the car display (800x480 @160dpi). Album art
 * sits on the left as a coloured dot-matrix halftone; the transport is dot-matrix too, and the
 * secondary actions live as small glyphs in the header. All state comes from [MusicPlayer].
 */
@Composable
fun FirstPartyPlayerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by MusicPlayer.state.collectAsStateWithLifecycle()
    val queue by MusicPlayer.queue.collectAsStateWithLifecycle()
    var panel by remember { mutableIntStateOf(PANEL_LYRICS) }
    val track = state.track
    val art by produceState<Bitmap?>(state.art, state.art, track?.thumbnailUrl) {
        value = state.art ?: track?.thumbnailUrl?.let { MediaArt.load(context, Uri.parse(it)) }
    }
    val scheme = MaterialTheme.colorScheme

    Surface(Modifier.fillMaxSize(), color = scheme.background) {
        Box(Modifier.fillMaxSize()) {
            DotGrid(Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp)) {

                // ---- header: back, title, then secondary actions as dot glyphs ----
                Row(
                    Modifier.fillMaxWidth().height(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp).clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        DotIcon(DotGlyphs.Back, scheme.onSurface, Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "NOW PLAYING",
                        style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.08.em),
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    DotAction(DotGlyphs.Shuffle, state.shuffle, scheme.error, scheme.onSurfaceVariant, Modifier.size(28.dp)) { MusicPlayer.toggleShuffle() }
                    Spacer(Modifier.width(8.dp))
                    DotAction(DotGlyphs.Repeat, state.repeat != 0, scheme.error, scheme.onSurfaceVariant, Modifier.size(28.dp)) { MusicPlayer.cycleRepeat() }
                    Spacer(Modifier.width(8.dp))
                    DotAction(DotGlyphs.Lyrics, panel == PANEL_LYRICS, scheme.error, scheme.onSurfaceVariant, Modifier.size(28.dp)) { panel = PANEL_LYRICS }
                    Spacer(Modifier.width(8.dp))
                    DotAction(DotGlyphs.Queue, panel == PANEL_QUEUE, scheme.error, scheme.onSurfaceVariant, Modifier.size(28.dp)) { panel = PANEL_QUEUE }
                    Spacer(Modifier.width(8.dp))
                    DotAction(
                        DotGlyphs.More, false, scheme.error, scheme.onSurfaceVariant, Modifier.size(28.dp),
                    ) { panel = if (panel == PANEL_LYRICS) PANEL_QUEUE else PANEL_LYRICS }
                }

                // ---- art left, track info right, transport below ----
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(186.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DotMatrixArt(
                            art,
                            Modifier.size(186.dp).border(1.dp, scheme.outline),
                        )
                        Column(Modifier.weight(1f).height(186.dp)) {
                            Text(
                                if (queue.isNotEmpty()) {
                                    val n = (state.activeIndex + 1).coerceAtLeast(1)
                                    "TRACK ${n.toString().padStart(2, '0')} / ${queue.size.toString().padStart(2, '0')}"
                                } else "NOW PLAYING",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.error,
                            )
                            Text(
                                (track?.title ?: "NOTHING PLAYING").uppercase(),
                                style = MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(track?.artists?.takeIf(String::isNotEmpty), track?.album).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.weight(1f))
                            val duration = state.durationMs.coerceAtLeast(0)
                            val position = state.positionMs
                            DotSeekBar(
                                fraction = if (duration > 0) position.toFloat() / duration else 0f,
                                onSeek = { f -> if (duration > 0) MusicPlayer.seek((f * duration).toLong()) },
                                modifier = Modifier.fillMaxWidth().height(20.dp),
                                enabled = duration > 0,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                val style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
                                Text(formatDuration(position), style = style, color = scheme.onSurfaceVariant)
                                Text(formatDuration(duration), style = style, color = scheme.onSurfaceVariant)
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // The seek bar belongs to the info column to the right of the artwork, so the
                    // transport is centred within that same column rather than the whole screen.
                    Row(
                        Modifier.fillMaxWidth().height(58.dp).padding(start = 210.dp), // 186.dp art + 24.dp gap
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(56.dp).clickable { MusicPlayer.previous() }, contentAlignment = Alignment.Center) {
                            DotIcon(DotGlyphs.Prev, scheme.onSurface, Modifier.size(40.dp))
                        }
                        Spacer(Modifier.width(54.dp))
                        Box(Modifier.size(64.dp).clickable { MusicPlayer.togglePlay() }, contentAlignment = Alignment.Center) {
                            DotIcon(
                                if (state.playing) DotGlyphs.Pause else DotGlyphs.Play,
                                scheme.onSurface,
                                Modifier.size(56.dp),
                            )
                        }
                        Spacer(Modifier.width(54.dp))
                        Box(Modifier.size(56.dp).clickable { MusicPlayer.next() }, contentAlignment = Alignment.Center) {
                            DotIcon(DotGlyphs.Next, scheme.onSurface, Modifier.size(40.dp))
                        }
                    }
                }

                // ---- single-line lyrics / queue, toggled from the header ----
                val panelModifier = Modifier.fillMaxWidth().height(28.dp)
                when (panel) {
                    PANEL_LYRICS -> LyricsView(
                        track?.title.orEmpty(), track?.artists.orEmpty(), track?.album.orEmpty(),
                        state.durationMs.coerceAtLeast(0), state.playing, panelModifier,
                    )
                    else -> QueueView(queue, state.activeIndex, panelModifier)
                }
                if (state.error != null) {
                    Text(
                        state.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DotAction(
    glyph: Array<String>,
    active: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    idleColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(modifier.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        DotIcon(glyph, if (active) activeColor else idleColor, modifier)
    }
}

/**
 * Lyrics from Gearslip's existing multi-provider lookup. Synced lyrics highlight and scroll the
 * active line; in this single-line slot that means the current line stays in view.
 */
@Composable
private fun LyricsView(
    title: String,
    artist: String,
    album: String,
    durationMs: Long,
    playing: Boolean,
    modifier: Modifier,
) {
    if (title.isEmpty()) {
        EmptyState("NO LYRICS", "Nothing is playing", modifier)
        return
    }
    val context = LocalContext.current
    val result by produceState<LyricsResult?>(null, title, artist, album, durationMs) {
        value = Lyrics.find(context, title, artist, album, durationMs)
    }
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(title, playing) {
        while (true) {
            position = MusicPlayer.state.value.positionMs
            if (!playing) break
            delay(250)
        }
    }
    val lyrics = result
    val active = if (lyrics?.synced == true) lyrics.lines.indexOfLast { it.timeMs <= position }.coerceAtLeast(0) else -1
    val list = rememberLazyListState()
    LaunchedEffect(active) { if (active >= 0) list.animateScrollToItem(active) }

    when {
        lyrics == null -> EmptyState("LOADING LYRICS", null, modifier)
        lyrics.lines.isEmpty() -> EmptyState("NO LYRICS", "Nothing found for this track", modifier)
        else -> LazyColumn(
            modifier,
            state = list,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            itemsIndexed(lyrics.lines) { index, line ->
                val current = index == active
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (current) {
                        Box(Modifier.size(width = 3.dp, height = 16.dp).background(MaterialTheme.colorScheme.error))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        line.text.ifEmpty { " " },
                        style = if (current) MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                        else MaterialTheme.typography.bodySmall,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueView(queue: List<MusicCatalog.Track>, activeIndex: Int, modifier: Modifier) {
    if (queue.isEmpty()) {
        EmptyState("NOTHING QUEUED", "Play something from BROWSE or SEARCH", modifier)
        return
    }
    val list = rememberLazyListState()
    LaunchedEffect(activeIndex) { if (activeIndex in queue.indices) list.animateScrollToItem(activeIndex) }
    LazyColumn(
        modifier,
        state = list,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 2.dp),
    ) {
        itemsIndexed(queue, key = { i, track -> "${i}-${track.id}" }) { index, track ->
            val active = index == activeIndex
            Row(
                Modifier.fillMaxWidth().heightIn(min = 24.dp)
                    .border(1.dp, if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline, RectangleShape)
                    .clickable { MusicPlayer.playAt(index) }
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.08.em),
                    color = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (track.artists.isNotEmpty()) {
                        Text(
                            track.artists,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
