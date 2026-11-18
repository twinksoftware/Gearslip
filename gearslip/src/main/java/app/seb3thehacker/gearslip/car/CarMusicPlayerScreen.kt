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
 * The full-screen first-party player: a large cover, the track and transport, then lyrics that
 * follow the playing line or the connected app's queue. All playback flows through CarMedia.
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
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    GsIconButton(
                        Icons.AutoMirrored.Filled.ArrowBack, "BACK", onBack,
                        size = 56.dp, iconSize = 30.dp, shape = RectangleShape,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "NOW PLAYING",
                        style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 0.08.em),
                        fontWeight = FontWeight.Bold,
                    )
                }
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val cover = minOf(maxWidth, 260.dp)
                    EntryArt(art, false, Modifier.size(cover).border(1.dp, MaterialTheme.colorScheme.outline))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    now.title.ifEmpty { "NOTHING PLAYING" }.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(now.artist, now.album).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PlayerProgress(
                    now, media,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    showTimes = true,
                    timeStyle = MaterialTheme.typography.titleMedium,
                )
                TransportRow(
                    now, media,
                    skip = 64.dp,
                    playScale = 1.4f,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MusicTab("LYRICS", tab == TAB_LYRICS, Modifier.weight(1f)) { tab = TAB_LYRICS }
                    MusicTab("QUEUE", tab == TAB_QUEUE, Modifier.weight(1f)) { tab = TAB_QUEUE }
                }
                when (tab) {
                    TAB_LYRICS -> LyricsView(now, Modifier.weight(1f).fillMaxWidth())
                    else -> QueueView(queue, now, media, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

/**
 * Lyrics are fetched by [Lyrics.find] through [produceState]; no network code lives here. For
 * synced lyrics the line at [NowPlaying.currentPosition] is highlighted and scrolled to.
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 24.dp),
        ) {
            itemsIndexed(lyrics.lines) { index, line ->
                val current = index == active
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (current) {
                        Box(Modifier.size(width = 4.dp, height = 32.dp).background(MaterialTheme.colorScheme.error))
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        line.text.ifEmpty { " " },
                        style = if (current) MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        else MaterialTheme.typography.titleLarge,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        itemsIndexed(queue, key = { i, track -> "$i-${track.queueId}" }) { index, track ->
            val active = track.queueId == now.activeQueueItemId
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    .border(1.dp, if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                    .clickable { media.playQueueItem(track) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.08.em),
                    color = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (track.subtitle.isNotEmpty()) {
                        Text(
                            track.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
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
