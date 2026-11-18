package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.car.theme.*
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.Lyrics
import app.seb3thehacker.gearslip.media.LyricsResult
import app.seb3thehacker.gearslip.media.NowPlaying
import app.seb3thehacker.gearslip.media.QueueTrack
import kotlinx.coroutines.delay

private const val TAB_LYRICS = 0
private const val TAB_QUEUE = 1

/**
 * The full-screen first-party player, laid out for a short car display (800x480 @160dpi with the
 * ~56.dp nav bar still reserved by the caller). A [BoxWithConstraints] reads the real content
 * height so the cover can be capped instead of growing into a full-width square, and a [Column]
 * gives the cover and the lyrics/queue the leftover space through weights. Everything — header,
 * cover, title, progress, transport, tabs and a few lyric/queue lines — is visible without
 * scrolling. All playback still flows through CarMedia.
 */
@Composable
fun FirstPartyPlayerScreen(onBack: () -> Unit) {
    val media = CarServices.media
    val now by media.now.collectAsStateWithLifecycle()
    val queue by media.queue.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(TAB_LYRICS) }
    val art = rememberArt(now)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            DotGrid(Modifier.fillMaxSize())
            // The nav bar inset is applied by the host, so maxHeight here is the space this screen
            // really has. Cap the art to a slice of it so it never swallows the controls below.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val coverMax = (maxHeight * 0.40f).coerceIn(120.dp, 180.dp)
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    // Compact header: back + NOW PLAYING in ~40.dp.
                    Row(
                        Modifier.fillMaxWidth().height(40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GsIconButton(
                            Icons.AutoMirrored.Filled.ArrowBack, "BACK", onBack,
                            size = 40.dp, iconSize = 22.dp, shape = RectangleShape,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "NOW PLAYING",
                            style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.08.em),
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    // Cover: a weighted slice of the leftover height, drawn as a square and capped
                    // by BoxWithConstraints. The larger weight keeps it the visual anchor without
                    // pushing the transport and tabs off a short screen.
                    Box(
                        Modifier.fillMaxWidth().weight(2f),
                        contentAlignment = Alignment.Center,
                    ) {
                        EntryArt(
                            art, false,
                            Modifier.fillMaxHeight().heightIn(max = coverMax).aspectRatio(1f)
                                .border(1.dp, MaterialTheme.colorScheme.outline),
                        )
                    }

                    // Track identity: one line each, small enough to leave room for everything else.
                    Text(
                        now.title.ifEmpty { "NOTHING PLAYING" }.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(now.artist, now.album).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Progress with times in a small type size.
                    PlayerProgress(
                        now, media,
                        modifier = Modifier.fillMaxWidth(),
                        showTimes = true,
                        timeStyle = MaterialTheme.typography.bodySmall,
                    )

                    // Transport, compact but still comfortably tappable.
                    TransportRow(
                        now, media,
                        skip = 40.dp,
                        playScale = 1.25f,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    )

                    // LYRICS / QUEUE switch, sharing the car UI's icon-box treatment at a shorter
                    // height so it fits above the lyrics on a 480dp-tall display.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CompactMusicTab("LYRICS", tab == TAB_LYRICS, Modifier.weight(1f)) { tab = TAB_LYRICS }
                        CompactMusicTab("QUEUE", tab == TAB_QUEUE, Modifier.weight(1f)) { tab = TAB_QUEUE }
                    }

                    // Lyrics or queue take whatever height is left — enough for several compact
                    // lines or a couple of queue rows at 800x480.
                    when (tab) {
                        TAB_LYRICS -> LyricsView(now, Modifier.weight(1f).fillMaxWidth())
                        else -> QueueView(queue, now, media, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }
}

/**
 * A shorter sibling of [MusicTab]: same latched icon-box look, but 38.dp tall with a smaller label
 * so the tab strip fits on a short car screen.
 */
@Composable
private fun CompactMusicTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    GsIconBox(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        colors = if (selected) GsColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        else GsColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
        shape = RectangleShape,
        latched = selected,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.08.em), maxLines = 1)
    }
}

/**
 * Lyrics are fetched by [Lyrics.find] through [produceState]; no network code lives here. For
 * synced lyrics the line at [NowPlaying.currentPosition] is highlighted and scrolled to. Lines are
 * deliberately compact so several fit in the player's short lyrics pane.
 */
@Composable
private fun LyricsView(now: NowPlaying, modifier: Modifier) {
    val context = LocalContext.current
    val result by produceState<LyricsResult?>(null, now.title, now.artist, now.album, now.durationMs) {
        value = Lyrics.find(context, now.title, now.artist, now.album, now.durationMs)
    }
    var position by remember { mutableLongStateOf(now.currentPosition()) }
    LaunchedEffect(now) {
        while (true) {
            position = now.currentPosition()
            if (!now.playing) break
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
            modifier.fillMaxSize(),
            state = list,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            itemsIndexed(lyrics.lines) { index, line ->
                val current = index == active
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (current) {
                        // The one sparing red accent: a thin marker on the playing line.
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
private fun QueueView(queue: List<QueueTrack>, now: NowPlaying, media: CarMedia, modifier: Modifier) {
    if (queue.isEmpty()) {
        EmptyState("NOTHING QUEUED", "The music app hasn't shared a queue", modifier)
        return
    }
    LazyColumn(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 2.dp),
    ) {
        itemsIndexed(queue, key = { i, track -> "$i-${track.queueId}" }) { index, track ->
            val active = track.queueId == now.activeQueueItemId
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp)
                    .border(1.dp, if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                    .clickable { media.playQueueItem(track) }
                    .padding(8.dp),
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
                    if (track.subtitle.isNotEmpty()) {
                        Text(
                            track.subtitle,
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
