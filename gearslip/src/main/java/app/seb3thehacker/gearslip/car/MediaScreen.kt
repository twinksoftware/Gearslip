package app.seb3thehacker.gearslip.car

import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import app.seb3thehacker.gearslip.car.theme.*
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import android.provider.Settings
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
            Column(Modifier.fillMaxSize().padding(bottom = LocalNavBarInset.current)) {
                AppIdentity(app, onExit, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                when (phase) {
                    CarMedia.Phase.REJECTED -> when (rejection) {
                        CarMedia.Rejection.NEEDS_NOTIFICATION_ACCESS -> Notice(
                            stringResource(R.string.media_keeps_library, app.label),
                            stringResource(R.string.media_needs_access),
                            action = stringResource(R.string.allow_on_phone) to {
                                val app = context.applicationContext
                                runCatching {
                                    app.startActivity(
                                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        )
                        CarMedia.Rejection.NEEDS_APP_RUNNING -> Notice(
                            stringResource(R.string.media_not_running, app.label),
                            stringResource(R.string.media_not_running_body, app.label),
                            action = stringResource(R.string.media_open_app, app.label) to { media.launchAndConnect(app) },
                        )
                        CarMedia.Rejection.REFUSED -> Notice(
                            stringResource(R.string.media_refused, app.label),
                            stringResource(R.string.media_refused_body),
                        )
                    }
                    else -> Notice(stringResource(R.string.media_connecting, app.label), null)
                }
            }
            return@Surface
        }

        if (searching) {
            Box(Modifier.padding(bottom = LocalNavBarInset.current)) { SearchPanel(
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
            ) }
            return@Surface
        }

        // The page runs down under the see-through nav bar, so the cover's glow carries on behind
        // it; the content stops at the bar's top edge. Lists run right to it; the rest keeps a gap.
        val barTop = LocalNavBarInset.current
        val barInset = barTop + 8.dp
        val tabs: @Composable (Modifier) -> Unit = { tabModifier ->
            MediaTab(stringResource(R.string.media_now_playing), tab == Tab.NOW_PLAYING, tabModifier) { tab = Tab.NOW_PLAYING }
            MediaTab(stringResource(R.string.media_up_next), tab == Tab.QUEUE, tabModifier) { tab = Tab.QUEUE }
            MediaTab(stringResource(R.string.media_browse), tab == Tab.BROWSE, tabModifier) { tab = Tab.BROWSE }
            MediaTab(stringResource(R.string.storage_lyrics), tab == Tab.LYRICS, tabModifier) { tab = Tab.LYRICS }
        }
        val searchButton: @Composable () -> Unit = {
            if (now.canSearch || media.canSearchLibrary) {
                GsIconButton(
                    Icons.Filled.Search,
                    stringResource(R.string.media_search, app.label),
                    { searching = true },
                    colors = GsColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
                    size = 44.dp,
                    shape = MaterialTheme.shapes.large,
                )
            }
        }
        val tabContent: @Composable (Modifier) -> Unit = { contentModifier ->
            Box(contentModifier) {
                when (tab) {
                    Tab.NOW_PLAYING -> StageControls(now, media, Modifier.fillMaxSize().padding(bottom = barInset))
                    Tab.BROWSE -> BrowsePanel(browse, media, app.label, Modifier.fillMaxSize().padding(bottom = barTop))
                    Tab.QUEUE -> QueuePanel(queue, now, media, Modifier.fillMaxSize().padding(bottom = barTop))
                    Tab.LYRICS -> LyricsPanel(now, Modifier.fillMaxSize().padding(bottom = barInset))
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
            // A small screen, or a big one at Extra large size, has no room for two columns: the
            // app and the tabs share one bar, and a list takes the whole width. The nav bar keeps
            // the controls, so nothing is lost.
            if (maxWidth < NARROW_WIDTH || maxHeight - barInset < NARROW_HEIGHT) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIdentity(app, onExit, Modifier.widthIn(max = 180.dp))
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) { tabs(Modifier) }
                        searchButton()
                    }
                    search?.let { SearchStatus(it, app.label, media::dismissSearch) }
                    if (tab == Tab.NOW_PLAYING) NowPlayingPage(now, media, Modifier.weight(1f).fillMaxWidth().padding(bottom = barInset), padded = false)
                    else tabContent(Modifier.weight(1f).fillMaxWidth())
                }
                return@BoxWithConstraints
            }

            // Two columns, split the same way on every tab so nothing jumps when switching: the app
            // and the art (or, beside a list, the compact player) on the left; the tabs across the
            // top of the right column, over whatever the tab shows.
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(0.8f).fillMaxHeight().padding(bottom = barInset)) {
                    AppIdentity(app, onExit)
                    Spacer(Modifier.height(12.dp))
                    if (tab == Tab.NOW_PLAYING) {
                        CoverArt(art, Modifier.weight(1f).fillMaxWidth())
                    } else {
                        CompactPlayer(now, media, art, Modifier.weight(1f).fillMaxWidth())
                    }
                }
                Column(Modifier.weight(1.2f).fillMaxHeight()) {
                    // The tabs share the row evenly; a long word (a translation, a big text size)
                    // shrinks to fit its tab instead.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tabs(Modifier.weight(1f))
                        searchButton()
                    }
                    search?.let { SearchStatus(it, app.label, media::dismissSearch) }
                    Spacer(Modifier.height(12.dp))
                    tabContent(Modifier.weight(1f).fillMaxWidth())
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
    val navigator = LocalCarNavigator.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // Shown on the same terms as every other screen's header: only when it leads somewhere
        // the nav bar doesn't.
        if (navigator.backLeadsSomewhere) {
            GsIconButton(Icons.Filled.ArrowBack, stringResource(R.string.back), onExit)
            Spacer(Modifier.width(12.dp))
        }
        icon?.let {
            Image(it, null, Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            // As the app writes it: recasing turned NewPipe into "Newpipe" and TIDAL into "Tidal".
            app.label,
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
            GsIconButton(Icons.Filled.ArrowBack, stringResource(R.string.back), onClose, size = ICON_BUTTON_SIZE)
            SearchField(
                query,
                stringResource(R.string.media_search, appLabel),
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
                trimmed.isEmpty() -> SearchNote(stringResource(R.string.media_search_hint))
                unsupported && canPickForYou ->
                    SearchNote(stringResource(R.string.media_search_no_list, appLabel))
                unsupported -> SearchNote(stringResource(R.string.media_search_unsupported, appLabel))
                entries == null -> SearchNote(stringResource(R.string.media_searching, appLabel))
                entries.isEmpty() -> SearchNote(stringResource(R.string.media_search_nothing, appLabel, trimmed))
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
            if (search.ignored) stringResource(R.string.media_search_ignored, appLabel, search.query)
            else stringResource(R.string.media_searching_for, appLabel, search.query),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** A bigger, easier-to-hit tab than a plain [TextButton] - this row is the only navigation on the screen. */
@Composable
private fun MediaTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    GsIconBox(
        onClick = onClick,
        modifier = modifier.height(44.dp),
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
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = MaterialTheme.typography.titleSmall.fontSize),
            modifier = Modifier.padding(horizontal = 8.dp),
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
        LyricsState.Loading -> Notice(stringResource(R.string.media_looking_lyrics), null)
        LyricsState.Missing -> Notice(
            stringResource(if (now.title.isEmpty()) R.string.media_nothing_playing_dot else R.string.media_no_lyrics),
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
 * The now-playing view on its own: an app's own now-playing template, or the Now playing tab on a
 * small screen. The cover on the left and [NowPlayingControls] on the right where there's room;
 * narrower, the cover sits above the controls, and only when they still fit under it.
 */
@Composable
internal fun NowPlayingPage(now: NowPlaying, media: CarMedia, modifier: Modifier, padded: Boolean = true) {
    BoxWithConstraints(if (padded) modifier.padding(horizontal = 24.dp, vertical = 8.dp) else modifier) {
        val art = rememberArt(now)
        if (maxWidth >= 520.dp && maxHeight >= 260.dp) {
            val artWidth = minOf(maxHeight, (maxWidth - 24.dp) * 0.4f)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                CoverArt(art, Modifier.width(artWidth).fillMaxHeight())
                NowPlayingControls(now, media, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            val artHeight = if (maxHeight >= 400.dp) minOf(maxHeight * 0.25f, 160.dp) else 0.dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (artHeight > 0.dp) CoverArt(art, Modifier.fillMaxWidth().height(artHeight))
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
 * Now playing beside the big cover: the title, artist and seek bar along the top, and the
 * controls in two rows under them, as big as the height left over allows - the transport, then
 * like, shuffle, repeat and the app's own buttons.
 */
@Composable
private fun StageControls(now: NowPlaying, media: CarMedia, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        // Short of height (a small screen, or Extra large size), the title steps down a size.
        val compact = maxHeight < 360.dp
        val showTimes = maxHeight >= 260.dp
        val titleRoom = maxHeight * 0.4f
        Column(Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp, top = if (compact) 6.dp else 16.dp, bottom = 4.dp)) {
            // The title gives up its height first and scrolls, so the controls keep theirs.
            Column(Modifier.heightIn(max = titleRoom).verticalScroll(rememberScrollState())) {
                Text(
                    now.title.ifEmpty { stringResource(R.string.media_nothing_playing) },
                    style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = if (compact) 20.sp else 22.sp,
                        maxFontSize = MaterialTheme.typography.displaySmall.fontSize,
                        stepSize = 2.sp,
                    ),
                )
                Text(
                    listOf(now.artist, now.album).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                now.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                if (now.title.isEmpty() && now.error == null) {
                    Text(
                        stringResource(R.string.media_press_play),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PlayerProgress(now, media, Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 12.dp), showTimes = showTimes)
            // What's left goes to the buttons: play takes about half of it, the row below a third,
            // and neither grows past what a row of them fits across.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val gap = (maxHeight * 0.08f).coerceIn(6.dp, 20.dp)
                val play = minOf(maxHeight * 0.5f, maxWidth / 3.2f, 128.dp).coerceAtLeast(56.dp)
                val mode = minOf(maxHeight * 0.32f, maxWidth / 6.5f, 80.dp).coerceAtLeast(44.dp)
                Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
                    TransportRow(now, media, skip = play / 1.5f, playScale = 1.5f, modifier = Modifier.fillMaxWidth())
                    PlayModeRow(now, media, size = mode, modifier = Modifier.fillMaxWidth(), maxOther = 2)
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
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp, Alignment.CenterVertically),
        ) {
            // The title gives up its height first and scrolls, so the controls never leave the screen.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(
                    now.title.ifEmpty { stringResource(R.string.media_nothing_playing) },
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(now.artist, now.album).filter { it.isNotEmpty() }.joinToString(" - "),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                now.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                if (now.title.isEmpty() && now.error == null) {
                    Text(
                        stringResource(R.string.media_press_play),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PlayerProgress(now, media, Modifier.fillMaxWidth(), showTimes = showTimes)
            TransportRow(now, media, skip = if (compact) 48.dp else 60.dp, modifier = Modifier.fillMaxWidth())
            PlayModeRow(now, media, size = if (compact) 46.dp else 56.dp, modifier = Modifier.fillMaxWidth(), maxOther = 2)
        }
    }
}

/** Below these, the media app drops to one column: see [MediaScreen]. */
private val NARROW_WIDTH = 600.dp
private val NARROW_HEIGHT = 300.dp

/**
 * The player beside the browse list, the queue and the lyrics: the cover as big as the controls
 * leave room for, with the title and artist beside it rather than under it, so the cover gets the
 * height; then the seek bar and the controls along the bottom.
 */
@Composable
private fun CompactPlayer(now: NowPlaying, media: CarMedia, art: android.graphics.Bitmap?, modifier: Modifier) {
    Column(modifier) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cover = minOf(maxHeight, maxWidth * 0.62f).coerceAtLeast(0.dp)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                CoverArt(art, Modifier.size(cover))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                    Text(
                        now.title.ifEmpty { stringResource(R.string.media_nothing_playing) },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        now.artist,
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        PlayerProgress(now, media, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        TransportRow(now, media, skip = 54.dp, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        PlayModeRow(now, media, size = 48.dp, modifier = Modifier.fillMaxWidth())
    }
}

// --- browsing --------------------------------------------------------------------------------

