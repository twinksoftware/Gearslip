package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import app.seb3thehacker.gearslip.car.theme.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import android.provider.Settings
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.withStyle
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
import androidx.compose.ui.unit.sp
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

/**
 * A media app on the car screen: what it is playing and its controls on the left, the tree it
 * offers to browse on the right. The app owns the content; this only draws it.
 */
@Composable
fun MediaScreen(app: MediaApp, onExit: () -> Unit) {
    val context = LocalContext.current
    val media = CarServices.media
    val phase by media.phase.collectAsStateWithLifecycle()
    val now by media.now.collectAsStateWithLifecycle()
    val queue by media.queue.collectAsStateWithLifecycle()
    val browse by media.browse.collectAsStateWithLifecycle()
    val rejection by media.rejection.collectAsStateWithLifecycle()
    val search by media.search.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.NOW_PLAYING) }
    var searching by remember { mutableStateOf(false) }

    // The connection outlives this screen: the home screen's player is the same one.
    LaunchedEffect(app) { CarServices.openMedia(app) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { CarServices.mediaScreenClosed() } }

    val art = rememberArt(now)

    // The theme's background, with the cover's colours glowing faintly behind the cover on the left.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (phase == CarMedia.Phase.READY && !searching) AlbumGlow(art, Modifier.fillMaxSize(), wide = true)
        if (phase != CarMedia.Phase.READY) {
            Column(Modifier.fillMaxSize()) {
                AppIdentity(app, onExit, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                when (phase) {
                    CarMedia.Phase.REJECTED -> when (rejection) {
                        CarMedia.Rejection.NEEDS_NOTIFICATION_ACCESS -> Notice(
                            "${app.label} keeps its library to itself.",
                            "Gearslip can still control it once it has Notification access on the phone. " +
                                "Allow it under Settings > Notifications > Notification access.",
                            action = "Open on phone" to {
                                val app = context.applicationContext
                                runCatching {
                                    app.startActivity(
                                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        )
                        CarMedia.Rejection.NEEDS_APP_RUNNING -> Notice(
                            "${app.label} isn't running.",
                            "${app.label} plays through Gearslip's own player, but only while it's " +
                                "open on the phone. Open it once and it keeps playing here in the car.",
                            action = "Open ${app.label}" to { media.launchAndConnect(app) },
                        )
                        CarMedia.Rejection.REFUSED -> Notice(
                            "${app.label} would not let Gearslip browse it.",
                            "Some media apps only accept Google's own host.",
                        )
                    }
                    else -> Notice("Connecting to ${app.label}…", null)
                }
            }
            return@Surface
        }

        if (searching) {
            SearchPanel(
                appLabel = app.label,
                media = media,
                canPickForYou = now.canSearch,
                onPlayed = {
                    searching = false
                    tab = Tab.NOW_PLAYING
                },
                onOpened = {
                    searching = false
                    tab = Tab.BROWSE
                },
                onPickForYou = { query ->
                    media.search(query)
                    searching = false
                    tab = Tab.NOW_PLAYING
                },
                onClose = { searching = false },
            )
            return@Surface
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIdentity(app, onExit, Modifier.widthIn(max = 180.dp))
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    MediaTab("Now playing", tab == Tab.NOW_PLAYING) { tab = Tab.NOW_PLAYING }
                    MediaTab("Up next", tab == Tab.QUEUE) { tab = Tab.QUEUE }
                    MediaTab("Browse", tab == Tab.BROWSE) { tab = Tab.BROWSE }
                    MediaTab("Lyrics", tab == Tab.LYRICS) { tab = Tab.LYRICS }
                }
                if (now.canSearch || media.canSearchLibrary) {
                    GsIconButton(
                        Icons.Filled.Search,
                        "Search ${app.label}",
                        { searching = true },
                        colors = GsColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
                        size = 44.dp,
                        shape = MaterialTheme.shapes.large,
                    )
                }
            }
            search?.let { SearchStatus(it, app.label, media::dismissSearch) }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val showSidePlayer = maxWidth >= 520.dp && maxHeight >= 300.dp
                val sidePlayerWidth = maxOf(208.dp, maxWidth * 0.32f)
                if (tab == Tab.NOW_PLAYING) {
                    NowPlayingPage(now, media, Modifier.fillMaxSize())
                } else {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Narrow displays keep the list usable; transport remains in the nav bar.
                        if (showSidePlayer) {
                            CompactPlayer(now, media, art, Modifier.width(sidePlayerWidth).fillMaxHeight())
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            when (tab) {
                                Tab.BROWSE -> BrowsePanel(browse, media, app.label, Modifier.fillMaxSize())
                                Tab.QUEUE -> QueuePanel(queue, now, media, Modifier.fillMaxSize())
                                Tab.LYRICS -> LyricsPanel(now, Modifier.fillMaxSize())
                                Tab.NOW_PLAYING -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Back, then which app this is: its own icon and its name, quieter than the song title - the
 * name alone in big bold type read as one more heading fighting the track for attention.
 */
@Composable
private fun AppIdentity(app: MediaApp, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val icon = remember(app) { findEntry(app.component.flattenToString())?.icon }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GsIconButton(Icons.Filled.ArrowBack, "Back", onExit, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        icon?.let {
            Image(it, null, Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            app.label.lowercase().replaceFirstChar { it.titlecase() },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private enum class Tab { NOW_PLAYING, BROWSE, QUEUE, LYRICS }

// --- search ----------------------------------------------------------------------------------

/**
 * Types a search for the app to play. The app picks the result and plays it, the way it would
 * for "play X" from a voice assistant, so there's no list to choose from.
 */
/**
 * Search inside the media app. It asks the app's library for matches half a second after the
 * driver stops typing and lists them, so the driver picks exactly what plays: a song plays, an
 * album or artist opens in Browse. The search key hides the keyboard to show more results; the
 * field brings it back. An app with no library search can still be told to play its own best
 * match, which is all the panel could do before.
 */
@Composable
private fun SearchPanel(
    appLabel: String,
    media: CarMedia,
    canPickForYou: Boolean,
    onPlayed: () -> Unit,
    onOpened: () -> Unit,
    onPickForYou: (String) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(true) }
    val results by media.results.collectAsStateWithLifecycle()
    LaunchedEffect(query) {
        delay(SEARCH_PAUSE_MS)
        media.searchLibrary(query)
    }
    DisposableEffect(Unit) { onDispose { media.clearResults() } }

    val trimmed = query.trim()
    val current = results?.takeIf { it.query == trimmed }
    val unsupported = current?.supported == false

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GsIconButton(Icons.Filled.ArrowBack, "Back", onClose, size = ICON_BUTTON_SIZE)
            SearchField(
                query,
                "Search $appLabel",
                Modifier.weight(1f).clickable { typing = true },
                onClear = {
                    query = ""
                    typing = true
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            val entries = current?.entries
            when {
                trimmed.isEmpty() -> SearchNote("Type a song, artist, album, or playlist.")
                unsupported && canPickForYou ->
                    SearchNote("$appLabel can't list results. Press search, and $appLabel picks what to play.")
                unsupported -> SearchNote("$appLabel can't search from the car.")
                entries == null -> SearchNote("Searching $appLabel…")
                entries.isEmpty() -> SearchNote("$appLabel found nothing for \"$trimmed\".")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                    items(entries) { entry ->
                        EntryRow(entry, media) {
                            if (entry.browsable) {
                                media.select(entry)
                                onOpened()
                            } else {
                                media.playAll(entry)
                                onPlayed()
                            }
                        }
                    }
                }
            }
        }
        if (typing) {
            CarKeyboard(
                text = query,
                onTextChange = { query = it },
                onSubmit = {
                    when {
                        trimmed.isEmpty() -> Unit
                        unsupported && canPickForYou -> onPickForYou(trimmed)
                        else -> typing = false
                    }
                },
                onDismiss = { if (trimmed.isEmpty()) onClose() else typing = false },
            )
        }
    }
}

@Composable
private fun SearchNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** How long typing has to pause before the library is searched. */
private const val SEARCH_PAUSE_MS = 1_000L

/** A line under the tabs while the app works on a search, and if it plays nothing for it. */
@Composable
private fun SearchStatus(search: CarMedia.Search, appLabel: String, onDismiss: () -> Unit) {
    Surface(
        onClick = onDismiss,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Text(
            if (search.ignored) "$appLabel didn't play anything for \"${search.query}\"."
            else "Searching $appLabel for \"${search.query}\"…",
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** A bigger, easier-to-hit tab than a plain [TextButton] - this row is the only navigation on the screen. */
@Composable
private fun MediaTab(label: String, selected: Boolean, onClick: () -> Unit) {
    GsIconBox(
        onClick = onClick,
        modifier = Modifier.height(44.dp),
        colors = if (selected) GsColors(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        else GsColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
        shape = MaterialTheme.shapes.large,
        latched = selected,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/**
 * The song's lyrics, fetched when this tab opens. Synced lyrics follow playback: the line being
 * sung sits a little above the middle in bold, lines already sung dim, and the list fades out at
 * both edges instead of cutting a line in half. Word-timed lyrics also light up each word as it's
 * sung. Unsynced ones are plain scrolling text.
 */
@Composable
internal fun LyricsPanel(
    now: NowPlaying,
    modifier: Modifier,
    lineStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleLarge,
) {
    val context = LocalContext.current
    val lyrics by produceState<LyricsState>(LyricsState.Loading, now.title, now.artist, now.album, now.durationMs) {
        value = LyricsState.Loading
        val found = Lyrics.find(context, now.title, now.artist, now.album, now.durationMs)
        value = found?.let { LyricsState.Found(it) } ?: LyricsState.Missing
        // Cached lyrics show at once; if a source now has better ones, they replace them.
        if (found != null) {
            Lyrics.upgrade(context, now.title, now.artist, now.album, now.durationMs, found)
                ?.let { value = LyricsState.Found(it) }
        }
    }

    when (val state = lyrics) {
        LyricsState.Loading -> Notice("Looking for lyrics…", null)
        LyricsState.Missing -> Notice(
            if (now.title.isEmpty()) "Nothing playing." else "No lyrics found for this track.",
            null,
        )
        is LyricsState.Found -> Column(modifier) {
            if (state.result.synced) SyncedLyrics(now, state.result, lineStyle, Modifier.weight(1f).fillMaxWidth())
            else PlainLyrics(state.result, lineStyle, Modifier.weight(1f).fillMaxWidth())
            // Debug builds name the service the lyrics came from, to see which one answers.
            if (app.seb3thehacker.gearslip.BuildConfig.DEBUG) {
                val from = state.result.source.ifEmpty { "an older cache" }
                val kind = when {
                    state.result.lines.any { it.words.isNotEmpty() } -> "word-timed"
                    state.result.synced -> "synced"
                    else -> "plain"
                }
                Text(
                    "From $from · $kind",
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun SyncedLyrics(
    now: NowPlaying,
    result: LyricsResult,
    lineStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier,
) {
    val lines = result.lines
    val wordTimed = lines.any { it.words.isNotEmpty() }
    // Every frame while words are filling in, so a word doesn't light up in steps; otherwise a
    // few times a second is enough to move the line along.
    var position by remember { mutableLongStateOf(now.currentPosition()) }
    LaunchedEffect(now, wordTimed) {
        while (true) {
            position = now.currentPosition()
            if (wordTimed && now.state == PlaybackState.STATE_PLAYING) androidx.compose.runtime.withFrameMillis { }
            else delay(300)
        }
    }
    // Only the line changing recomposes the list; the moving position is read by the one line.
    val current by remember(lines) {
        androidx.compose.runtime.derivedStateOf { lines.indexOfLast { it.timeMs <= position } }
    }
    val list = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) list.animateScrollToItem(current)
    }

    val scheme = MaterialTheme.colorScheme
    val sung = scheme.onSurfaceVariant.copy(alpha = 0.55f)
    BoxWithConstraints(modifier.fadingEdges()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            // The padding lets the first line start, and the last line end, near the middle.
            contentPadding = PaddingValues(top = maxHeight * 0.32f, bottom = maxHeight * 0.6f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(lines) { index, line ->
                if (index == current) {
                    CurrentLine(line, { position }, lineStyle)
                } else {
                    Text(
                        line.text.ifEmpty { " " },
                        style = lineStyle,
                        color = if (index < current) sung else scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The line being sung, in bold and a size up. With word timings, words already sung are in the
 * accent colour, the one being sung fills in letter by letter, and the rest wait dimmed.
 */
@Composable
private fun CurrentLine(line: app.seb3thehacker.gearslip.media.LyricLine, position: () -> Long, lineStyle: androidx.compose.ui.text.TextStyle) {
    val scheme = MaterialTheme.colorScheme
    val style = lineStyle.copy(fontWeight = FontWeight.Bold, fontSize = lineStyle.fontSize * 1.15f)
    if (line.words.isEmpty()) {
        Text(line.text.ifEmpty { " " }, style = style, color = scheme.primary)
        return
    }
    val lit = scheme.primary
    val waiting = scheme.onSurfaceVariant.copy(alpha = 0.7f)
    val now = position()
    val text = androidx.compose.ui.text.buildAnnotatedString {
        line.words.forEachIndexed { i, word ->
            // The line's first word has no leading space to trim, the last no trailing one.
            val shown = if (i == line.words.lastIndex) word.text.trimEnd() else word.text
            val progress = when {
                now >= word.endMs -> 1f
                now <= word.startMs -> 0f
                else -> (now - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1)
            }
            val letters = shown.trimEnd().length
            val filled = progress * letters
            shown.forEachIndexed { c, ch ->
                val amount = (filled - c).coerceIn(0f, 1f)
                withStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.lerp(waiting, lit, amount))) {
                    append(ch)
                }
            }
        }
    }
    Text(text, style = style)
}

@Composable
private fun PlainLyrics(result: LyricsResult, lineStyle: androidx.compose.ui.text.TextStyle, modifier: Modifier) {
    LazyColumn(
        modifier.fadingEdges(),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(result.lines) { line ->
            Text(line.text.ifEmpty { " " }, style = lineStyle, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Fades the top and bottom edges out, so a line scrolling past doesn't end in a hard cut. */
private fun Modifier.fadingEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to androidx.compose.ui.graphics.Color.Transparent,
                0.14f to androidx.compose.ui.graphics.Color.Black,
                0.86f to androidx.compose.ui.graphics.Color.Black,
                1f to androidx.compose.ui.graphics.Color.Transparent,
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }

private sealed interface LyricsState {
    data object Loading : LyricsState
    data object Missing : LyricsState
    class Found(val result: LyricsResult) : LyricsState
}

@Composable
internal fun Notice(title: String, detail: String?, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        detail?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        action?.let { (label, run) ->
            Spacer(Modifier.height(12.dp))
            GsButton(onClick = run, tone = GsTone.Tonal) { Text(label) }
        }
    }
}

// --- now playing -----------------------------------------------------------------------------

/**
 * The now-playing view on its own, for places with no tabs around it (an app's own
 * now-playing template). Landscape art uses the original 40/60 split with the controls;
 * narrow screens put it above the controls when there is enough height.
 */
@Composable
internal fun NowPlayingPage(now: NowPlaying, media: CarMedia, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val art = rememberArt(now)
        if (maxWidth >= 520.dp && maxHeight >= 260.dp) {
            // Keep the original artwork width; the shared header leaves more height than before.
            val artWidth = minOf(maxHeight, (maxWidth - 24.dp) * 0.4f)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                CoverArt(art, Modifier.width(artWidth).fillMaxHeight())
                NowPlayingControls(now, media, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            val artHeight = if (maxHeight >= 400.dp) minOf(maxHeight * 0.25f, 160.dp) else 0.dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Only spend vertical space on art when the controls have room below it.
                if (artHeight > 0.dp) {
                    CoverArt(art, Modifier.fillMaxWidth().height(artHeight))
                }
                NowPlayingControls(now, media, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** The cover, as big a square as its space allows, centred in it. */
@Composable
private fun CoverArt(art: android.graphics.Bitmap?, modifier: Modifier, alignment: Alignment = Alignment.Center) {
    BoxWithConstraints(modifier, contentAlignment = alignment) {
        val shape = RoundedCornerShape(22.dp)
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(minOf(maxWidth, maxHeight)).gsPanelShadow(shape),
        ) {
            if (art != null) {
                Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                // No cover (or nothing playing): a note, so the square reads as a cover, not a hole.
                Box(contentAlignment = Alignment.Center) {
                    Icon(MediaIcons.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxSize(0.35f))
                }
            }
        }
    }
}

/**
 * Everything about the track and every control for it, read top to bottom: title and artist,
 * the seek bar, the transport, then like, shuffle and repeat as big icon-only buttons.
 */
@Composable
private fun NowPlayingControls(now: NowPlaying, media: CarMedia, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val compact = maxHeight < 360.dp || maxWidth < 420.dp
        val showTimes = maxHeight >= 260.dp
        val inlineControls = (maxHeight < 260.dp && maxWidth >= 420.dp) || maxWidth >= 820.dp
        val buttonWidth = if (inlineControls) (maxWidth - 16.dp) / 2 else maxWidth
        val spacing = if (compact) 8.dp else 14.dp
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            // Metadata yields height first: the playback buttons stay above the navigation bar.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(
                    now.title.ifEmpty { "Nothing playing" },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 22.sp,
                        maxFontSize = MaterialTheme.typography.headlineMedium.fontSize,
                        stepSize = 1.sp,
                    ),
                )
                Text(
                    listOf(now.artist, now.album).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                now.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                if (now.title.isEmpty() && now.error == null) {
                    Text(
                        "Press play to pick up where you left off, or find something in Browse.",
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(spacing - 4.dp))
            PlayerProgress(now, media, Modifier.fillMaxWidth(), showTimes = showTimes)
            Spacer(Modifier.height(spacing))
            // A single button band leaves room for the title even at Extra large UI size.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(spacing + 4.dp),
                maxItemsInEachRow = if (inlineControls) 2 else 1,
            ) {
                TransportRow(now, media, skip = if (compact) 48.dp else 60.dp, modifier = Modifier.width(buttonWidth))
                PlayModeRow(
                    now, media,
                    size = if (compact) 48.dp else 56.dp,
                    modifier = Modifier.width(buttonWidth).horizontalScroll(rememberScrollState()).widthIn(min = buttonWidth),
                    maxOther = 2,
                )
            }
        }
    }
}

/**
 * The player beside the browse list, the queue and the lyrics: the same card as beside the map on
 * Home, the cover taking whatever the title and controls leave, so every tab keeps the song in
 * sight and the controls in one place.
 */
@Composable
private fun CompactPlayer(now: NowPlaying, media: CarMedia, art: android.graphics.Bitmap?, modifier: Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Surface(modifier.gsPanelShadow(shape), shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) {
        AlbumGlow(art, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CoverArt(art, Modifier.weight(1f).fillMaxWidth(), Alignment.BottomCenter)
            Spacer(Modifier.height(10.dp))
            Text(
                now.title.ifEmpty { "Nothing playing" },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                now.artist,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            PlayerProgress(now, media, Modifier.fillMaxWidth().padding(vertical = 2.dp), showTimes = false)
            TransportRow(now, media, skip = 52.dp, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            PlayModeRow(now, media, size = 46.dp, modifier = Modifier.fillMaxWidth())
        }
    }
}

// --- browsing --------------------------------------------------------------------------------

