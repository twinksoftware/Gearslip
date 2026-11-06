package app.seb3thehacker.gearslip.media

import app.seb3thehacker.gearslip.R
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.Rating
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.host.KnownApps
import app.seb3thehacker.gearslip.notify.GearslipNotificationListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One row of an app's browse tree. */
class MediaEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconUri: Uri?,
    val iconBitmap: Bitmap?,
    val browsable: Boolean,
    val playable: Boolean,
    /** Shown as a cover in a grid rather than a row, as the app asked. */
    val grid: Boolean = false,
    /** The heading the app files this under, when it splits a list into sections. */
    val group: String? = null,
)

/**
 * [icon] is a drawable id inside the media app's own package, not Gearslip's; 0 for none.
 * [hasOutline] says the app also has an outline of the same icon (ic_heart beside ic_heart_outline).
 */
class CustomAction(
    val id: String,
    val name: String,
    val extras: Bundle?,
    val icon: Int = 0,
    val iconName: String = "",
    val hasOutline: Boolean = false,
) {
    /**
     * Whether a toggle like heart or shuffle is on, read from the icon's name - Tidal swaps
     * ic_favorite for ic_favorite_filled and ic_shuffle for ic_shuffle_active, and keeps the
     * button's name the same. Whole words only, so "inactive" doesn't count as "active".
     * Metrolist swaps ic_heart_outline for a plain ic_heart, so a plain icon that has an outline
     * twin counts as on too.
     */
    val on: Boolean
        get() = hasOutline || iconName.lowercase().split('_', '.', '-').any { it in ON_WORDS }

    private companion object {
        val ON_WORDS = setOf("filled", "fill", "active", "on", "selected", "checked", "enabled", "solid", "one", "all")
    }
}

/** One entry in the app's own queue - not its library, whatever it has lined up to play next. */
class QueueTrack(
    val queueId: Long,
    val title: String,
    val subtitle: String,
    val iconUri: Uri?,
    val iconBitmap: Bitmap?,
)

/** What is playing, as the car's now-playing panel needs it. */
class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val art: Bitmap? = null,
    val artUri: Uri? = null,
    val state: Int = PlaybackState.STATE_NONE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val updatedAt: Long = 0,
    val actions: Long = 0,
    val custom: List<CustomAction> = emptyList(),
    val error: String? = null,
    /** Which [QueueTrack.queueId] is playing now, so "up next" can show what follows it. */
    val activeQueueItemId: Long = MediaSession.QueueItem.UNKNOWN_ID.toLong(),
    /** The track's heart rating, for apps that take one; null when the app doesn't rate by heart. */
    val heart: Boolean? = null,
) {
    /** The app's own button for [words], matched on its id or name - how most apps offer like, shuffle and repeat. */
    fun customFor(vararg words: String) = custom.firstOrNull { a ->
        words.any { a.id.contains(it, ignoreCase = true) || a.name.contains(it, ignoreCase = true) }
    }

    /** The app says it takes searches. Spotify says so and then ignores Gearslip's, so [CarMedia.search] checks. */
    val canSearch get() = (actions and PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0L

    /** Thumbs-down buttons say "like" too ("Dislike"), so those are skipped. */
    val likeAction
        get() = custom.firstOrNull { a ->
            val text = "${a.id} ${a.name}"
            listOf("like", "favorit", "favourit", "heart", "love", "thumb").any { text.contains(it, ignoreCase = true) } &&
                listOf("dislike", "down").none { text.contains(it, ignoreCase = true) }
        }
    val shuffleAction get() = customFor("shuffle")

    /** The app's buttons that aren't like, shuffle or repeat - "Start radio" and the like. */
    val otherActions: List<CustomAction>
        get() {
            val known = listOf(likeAction, shuffleAction, repeatAction)
            return custom.filter { a -> known.none { it === a } }
        }
    val repeatAction get() = customFor("repeat", "loop")

    /**
     * Liked or not; null when the app offers no way to like at all. An app's own like button
     * only says which way it goes next by its name ("Remove from Liked Songs"), so that's read.
     */
    val liked: Boolean?
        get() = heart ?: likeAction?.let { action ->
            action.on || listOf("remove", "unlike", "unfav", "unheart").any { action.name.contains(it, ignoreCase = true) }
        }

    val playing get() = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    val hasTrack get() = title.isNotEmpty() || state != PlaybackState.STATE_NONE

    /**
     * Something is actually playing or paused mid-track. A stopped, errored or empty session, or one
     * that has not named a track yet, must not keep the player on screen.
     */
    val isActive get() = title.isNotEmpty() && when (state) {
        PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> false
        else -> true
    }

    fun canDo(action: Long) = actions and action != 0L

    /** The position now, advanced from the last report the way the platform's own UIs do. */
    fun currentPosition(): Long {
        if (!playing || state != PlaybackState.STATE_PLAYING) return positionMs
        val elapsed = SystemClock.elapsedRealtime() - updatedAt
        val moved = positionMs + (elapsed * speed).toLong()
        return if (durationMs > 0) moved.coerceAtMost(durationMs) else moved
    }
}

class BrowseState(
    /** Titles of the folders entered so far; empty at the app's root. */
    val trail: List<String> = emptyList(),
    val entries: List<MediaEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** The app connected but shares no library with us, so there is nothing to browse - only to control. */
    val unavailable: Boolean = false,
)

/**
 * Talks to one media app the way Android Auto does: connect to its MediaBrowserService, browse
 * its tree, and drive playback through the session it publishes. The app decides what its
 * tree contains and what the buttons do; this only presents it.
 */
class CarMedia(private val context: Context) {

    enum class Phase { IDLE, CONNECTING, READY, REJECTED }

    /** Why a connection was [Phase.REJECTED], so the screen can say something useful. */
    enum class Rejection { REFUSED, NEEDS_NOTIFICATION_ACCESS, NEEDS_APP_RUNNING }

    private val _rejection = MutableStateFlow(Rejection.REFUSED)
    val rejection: StateFlow<Rejection> = _rejection.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var connecting: MediaApp? = null

    /** The music app Gearslip is attached to, for the assistant to name; null when there's none. */
    val appLabel: String? get() = connecting?.label?.takeIf { controller != null }

    /** Called once an app is connected and can be played, library or not. */
    var onReady: ((MediaApp) -> Unit)? = null

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _browse = MutableStateFlow(BrowseState())
    val browse: StateFlow<BrowseState> = _browse.asStateFlow()

    private val _now = MutableStateFlow(NowPlaying())
    val now: StateFlow<NowPlaying> = _now.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueTrack>>(emptyList())
    val queue: StateFlow<List<QueueTrack>> = _queue.asStateFlow()

    /** A typed search on its way to the app; [Search.ignored] once it has played nothing for it. */
    class Search(val query: String, val ignored: Boolean = false)

    /**
     * What the app's own library search found for [query]. [entries] is null while it looks.
     * [supported] is false when the app has no library search, so the panel can fall back to
     * "play the best match" (see [search]).
     */
    class Results(val query: String, val entries: List<MediaEntry>? = null, val supported: Boolean = true)

    private val _results = MutableStateFlow<Results?>(null)
    val results: StateFlow<Results?> = _results.asStateFlow()
    private var resultsQuery: String? = null

    private val _search = MutableStateFlow<Search?>(null)
    val search: StateFlow<Search?> = _search.asStateFlow()
    private var searchBefore: NowPlaying? = null

    /** Shuffle and repeat, as [PlaybackStateCompat]'s modes; [PlaybackStateCompat.SHUFFLE_MODE_INVALID] when unknown. */
    class Modes(
        val shuffle: Int = PlaybackStateCompat.SHUFFLE_MODE_INVALID,
        val repeat: Int = PlaybackStateCompat.REPEAT_MODE_INVALID,
    )

    private val _modes = MutableStateFlow(Modes())
    val modes: StateFlow<Modes> = _modes.asStateFlow()

    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null

    /**
     * The same session through the support library's controller - the framework one has no
     * shuffle or repeat calls at all. Every app built on MediaSessionCompat or Media3 answers
     * these; one on the bare framework API never reports a mode, and the buttons stay off.
     */
    private var compat: MediaControllerCompat? = null
    private val compatCallback = object : MediaControllerCompat.Callback() {
        override fun onSessionReady() = refreshModes()
        override fun onShuffleModeChanged(shuffleMode: Int) = refreshModes()
        override fun onRepeatModeChanged(repeatMode: Int) = refreshModes()
    }

    private fun attach(c: MediaController) {
        controller = c
        c.registerCallback(callback)
        update(c.metadata, c.playbackState)
        _queue.value = queueOf(c.queue)
        compat = runCatching {
            MediaControllerCompat(context, MediaSessionCompat.Token.fromToken(c.sessionToken)).also {
                it.registerCallback(compatCallback, main)
            }
        }.onFailure { GearslipLog.w("media: no support-library view of the session: ${it.message}") }.getOrNull()
        refreshModes()
    }

    private fun refreshModes() {
        val c = compat
        val modes = if (c == null) Modes() else Modes(shuffle = c.shuffleMode, repeat = c.repeatMode)
        if (modes.shuffle != _modes.value.shuffle || modes.repeat != _modes.value.repeat) {
            GearslipLog.i("media: shuffle mode ${modes.shuffle}, repeat mode ${modes.repeat}, rating type ${controller?.ratingType}")
        }
        _modes.value = modes
    }
    private var subscribed: String? = null
    private var rootId: String? = null
    private val path = ArrayDeque<Pair<String, String>>() // id to title

    fun connect(app: MediaApp) {
        disconnect()
        connecting = app
        _phase.value = Phase.CONNECTING
        _browse.value = BrowseState()
        // Known to refuse its library (Spotify): asking only costs the seconds it takes to say no,
        // so go straight to its playback session.
        if (KnownApps.playerWorks(app.component.packageName)) {
            attachToSession(app)
            return
        }
        val mb = MediaBrowser(context, app.component, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() = this@CarMedia.onConnected(app)
            override fun onConnectionFailed() {
                GearslipLog.w("media: ${app.label} refused the connection to its library")
                attachToSession(app)
            }
            override fun onConnectionSuspended() {
                GearslipLog.w("media: ${app.label} went away")
                _phase.value = Phase.REJECTED
            }
        }, Bundle().apply {
            // Tells the app it may ask for grids and section headings, as it would of Android Auto.
            putBoolean(STYLE_SUPPORTED, true)
        })
        browser = mb
        GearslipLog.i("media: connecting to ${app.label}'s library")
        runCatching { mb.connect() }.onFailure {
            GearslipLog.e("media: connect threw", it)
            _phase.value = Phase.REJECTED
        }
        // An app that never answers would leave the player spinning, and every later tap on it
        // would only reopen that spinner. Give up on the library and drive its playback instead.
        main.postDelayed({
            if (browser !== mb || _phase.value != Phase.CONNECTING) return@postDelayed
            GearslipLog.w("media: ${app.label} didn't answer in ${CONNECT_WAIT_MS / 1000} s, using its playback session")
            runCatching { mb.disconnect() }
            browser = null
            attachToSession(app)
        }, CONNECT_WAIT_MS)
    }

    /**
     * Some apps only let Google's own host browse them. Their playback session is still public, so
     * playback can be driven from it: what is playing, play and pause, skip, seek. Only the library
     * is out of reach. Reading another app's session needs Notification access, which the driver
     * grants once on the phone.
     */
    private fun attachToSession(app: MediaApp, attempt: Int = 0, launched: Boolean = false) {
        if (connecting != app) return
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, GearslipNotificationListener::class.java)
        val session = try {
            manager.getActiveSessions(listener).firstOrNull { it.packageName == app.component.packageName }
        } catch (e: SecurityException) {
            GearslipLog.w("media: cannot read ${app.label}'s session without notification access")
            _rejection.value = Rejection.NEEDS_NOTIFICATION_ACCESS
            _phase.value = Phase.REJECTED
            return
        }
        if (session == null) {
            // A known session-only app that isn't running won't start by waiting: say so at once.
            val limit = when {
                launched -> SESSION_RETRIES_LAUNCHED
                KnownApps.playerWorks(app.component.packageName) -> 0
                else -> SESSION_RETRIES
            }
            // The app only publishes its session once its service has started; give it a moment.
            if (attempt < limit) {
                main.postDelayed({ attachToSession(app, attempt + 1, launched) }, SESSION_RETRY_MS)
            } else {
                GearslipLog.w("media: ${app.label} has no active session to control")
                // A session-only app (Spotify) just isn't running - say so and offer to open it,
                // rather than calling it unsupported.
                _rejection.value = if (KnownApps.playerWorks(app.component.packageName)) {
                    Rejection.NEEDS_APP_RUNNING
                } else {
                    Rejection.REFUSED
                }
                _phase.value = Phase.REJECTED
            }
            return
        }
        GearslipLog.i("media: controlling ${app.label} through its session; its library is not shared")
        attach(session)
        _browse.value = BrowseState(loading = false, unavailable = true)
        _phase.value = Phase.READY
        onReady?.invoke(app)
    }

    private fun onConnected(app: MediaApp) {
        val mb = browser ?: return
        searchComponent = app.component
        GearslipLog.i("media: connected to ${app.label}, root=${mb.root}")
        attach(MediaController(context, mb.sessionToken))
        rootId = mb.root
        childStyles.clear()
        mb.extras?.let { childStyles[mb.root] = it.getInt(STYLE_BROWSABLE, 0) to it.getInt(STYLE_PLAYABLE, 0) }
        path.clear()
        _phase.value = Phase.READY
        onReady?.invoke(app)
        open(mb.root)
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = update(metadata, controller?.playbackState)
        override fun onPlaybackStateChanged(state: PlaybackState?) = update(controller?.metadata, state)
        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
            _queue.value = queueOf(queue)
        }
    }

    private fun queueOf(items: List<MediaSession.QueueItem>?): List<QueueTrack> = items.orEmpty().map { item ->
        val d = item.description
        QueueTrack(
            queueId = item.queueId,
            title = d.title?.toString().orEmpty(),
            subtitle = d.subtitle?.toString().orEmpty(),
            iconUri = d.iconUri,
            iconBitmap = d.iconBitmap,
        )
    }

    private fun update(metadata: MediaMetadata?, state: PlaybackState?) {
        fun text(vararg keys: String) = keys.firstNotNullOfOrNull { metadata?.getString(it)?.takeIf(String::isNotEmpty) }.orEmpty()
        fun art(vararg keys: String) = keys.firstNotNullOfOrNull { metadata?.getBitmap(it) }
        fun uri(vararg keys: String) = keys.firstNotNullOfOrNull { metadata?.getString(it)?.takeIf(String::isNotEmpty) }?.let(Uri::parse)
        _now.value = NowPlaying(
            title = text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE),
            artist = text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST),
            album = text(MediaMetadata.METADATA_KEY_ALBUM),
            art = art(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            artUri = uri(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
            state = state?.state ?: PlaybackState.STATE_NONE,
            positionMs = state?.position ?: 0,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            speed = state?.playbackSpeed ?: 1f,
            updatedAt = state?.lastPositionUpdateTime ?: 0,
            actions = state?.actions ?: 0,
            custom = withNextChapter(state?.customActions.orEmpty().map {
                val iconName = iconName(it.icon)
                CustomAction(it.action, it.name.toString(), it.extras, it.icon, iconName, hasOutline(it.icon, iconName))
            }),
            error = state?.errorMessage?.toString(),
            activeQueueItemId = state?.activeQueueItemId ?: MediaSession.QueueItem.UNKNOWN_ID.toLong(),
            heart = if (controller?.ratingType == Rating.RATING_HEART) {
                metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)?.takeIf { it.isRated }?.hasHeart() ?: false
            } else null,
        )
        logButtons()
        checkSearch()
    }

    /** The package of the app Gearslip controls now, or null. */
    val packageName: String? get() = controller?.packageName

    private var appResources: Pair<String, android.content.res.Resources?>? = null

    /**
     * AntennaPod takes a Next chapter command whenever it's playing, but offers the button only
     * when the driver turns it on in its notification settings. Gearslip offers it either way,
     * with AntennaPod's own icon; on an episode without chapters it does nothing.
     */
    private fun withNextChapter(actions: List<CustomAction>): List<CustomAction> {
        if (controller?.packageName != ANTENNAPOD) return actions
        if (actions.any { it.id in ANTENNAPOD_NEXT_CHAPTER }) return actions
        val icon = resourcesOf(ANTENNAPOD)?.getIdentifier("ic_notification_next_chapter", "drawable", ANTENNAPOD) ?: 0
        val nextChapter = CustomAction(ANTENNAPOD_NEXT_CHAPTER.first(), context.getString(R.string.player_next_chapter), null, icon)
        // The player shows only the first two of an app's own buttons, and AntennaPod's are
        // Rewind and Fast-forward: second place keeps Rewind beside it.
        return actions.toMutableList().apply { add(minOf(1, size), nextChapter) }
    }

    private fun resourcesOf(pkg: String): android.content.res.Resources? {
        if (appResources?.first != pkg) {
            appResources = pkg to runCatching { context.packageManager.getResourcesForApplication(pkg) }.getOrNull()
            outlines.clear()
        }
        return appResources?.second
    }

    /** The icon's resource name in the app's package, or "" when it can't be read. */
    private fun iconName(icon: Int): String {
        val pkg = controller?.packageName ?: return ""
        val res = resourcesOf(pkg) ?: return ""
        return runCatching { res.getResourceEntryName(icon) }.getOrDefault("")
    }

    /** Whether each icon has an outline twin, by icon id: looked up once, since state changes often. */
    private val outlines = HashMap<Int, Boolean>()

    private fun hasOutline(icon: Int, name: String): Boolean {
        val pkg = controller?.packageName ?: return false
        val res = resourcesOf(pkg) ?: return false
        if (name.isEmpty() || OUTLINE_WORDS.any { name.contains(it, ignoreCase = true) }) return false
        return outlines.getOrPut(icon) {
            OUTLINE_WORDS.any { res.getIdentifier("${name}_$it", "drawable", pkg) != 0 }
        }
    }

    private var loggedButtons: String? = null

    /** The app's own buttons, by id, name and icon, each time they change. */
    private fun logButtons() {
        val text = _now.value.custom.joinToString { a -> "${a.id} \"${a.name}\" icon=${a.iconName.ifEmpty { a.icon.toString() }}" }
        if (text == loggedButtons) return
        loggedButtons = text
        GearslipLog.i("media: app buttons: ${text.ifEmpty { "none" }}")
    }

    // --- browsing ------------------------------------------------------------------------

    private fun open(parentId: String) {
        val mb = browser ?: return
        subscribed?.let { runCatching { mb.unsubscribe(it) } }
        subscribed = parentId
        _browse.update { BrowseState(trail = path.map { it.second }, loading = true) }
        mb.subscribe(parentId, object : MediaBrowser.SubscriptionCallback() {
            override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                if (parentId != subscribed) return
                _browse.value = BrowseState(
                    trail = path.map { it.second },
                    entries = children.map { entryOf(it, childStyles[parentId]) },
                    loading = false,
                    // An empty top level is an app keeping its library to itself, not an empty one.
                    unavailable = path.isEmpty() && children.isEmpty(),
                )
            }
            override fun onError(parentId: String) {
                if (parentId != subscribed) return
                GearslipLog.w("media: could not load $parentId")
                _browse.value = BrowseState(trail = path.map { it.second }, loading = false, failed = true)
            }
        })
    }

    /**
     * How a folder asked for its children to be shown, browsable ones and playable ones: by
     * folder id, read off the folder itself (or the root's extras) before it's opened.
     */
    private val childStyles = HashMap<String, Pair<Int, Int>>()

    private fun entryOf(item: MediaBrowser.MediaItem, parentStyle: Pair<Int, Int>?): MediaEntry {
        val d = item.description
        val extras = d.extras
        if (extras != null && item.isBrowsable) {
            childStyles[item.mediaId.orEmpty()] = extras.getInt(STYLE_BROWSABLE, 0) to extras.getInt(STYLE_PLAYABLE, 0)
        }
        // The item's own hint first, then what its folder asked for its kind of child.
        val style = extras?.getInt(STYLE_SINGLE_ITEM, 0)?.takeIf { it != 0 }
            ?: (if (item.isBrowsable) parentStyle?.first else parentStyle?.second) ?: 0
        return MediaEntry(
            id = item.mediaId.orEmpty(),
            title = d.title?.toString().orEmpty(),
            subtitle = d.subtitle?.toString().orEmpty(),
            iconUri = d.iconUri,
            iconBitmap = d.iconBitmap,
            browsable = item.isBrowsable,
            playable = item.isPlayable,
            grid = style == STYLE_GRID || style == STYLE_CATEGORY_GRID,
            group = extras?.getString(STYLE_GROUP_TITLE)?.takeIf { it.isNotBlank() },
        )
    }

    /** Folders open; tracks play. Some entries are both (an album you can browse or play whole). */
    fun select(entry: MediaEntry) {
        if (entry.browsable) {
            path.addLast(entry.id to entry.title)
            open(entry.id)
        } else if (entry.playable) {
            controller?.transportControls?.playFromMediaId(entry.id, null)
        }
    }

    fun playAll(entry: MediaEntry) {
        controller?.transportControls?.playFromMediaId(entry.id, null)
    }

    val canGoUp get() = path.isNotEmpty()

    fun up() {
        if (path.isEmpty()) return
        path.removeLast()
        open(path.lastOrNull()?.first ?: rootId ?: return)
    }

    // --- transport -----------------------------------------------------------------------

    fun togglePlay() {
        val controls = controller?.transportControls ?: return
        if (_now.value.playing) controls.pause() else controls.play()
    }
    /**
     * Pauses whatever the app thinks it's doing. Voice pauses the music to listen, so by the time
     * "pause" is heard the app already looks stopped; a toggle did nothing and the song came back
     * as soon as the microphone let go. A real pause tells the app to stay stopped.
     */
    fun pause() = controller?.transportControls?.pause()
    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()
    fun seek(ms: Long) = controller?.transportControls?.seekTo(ms)
    fun custom(action: CustomAction) {
        val controls = controller?.transportControls ?: return
        // AntennaPod's media3 service and its older one each know only their own name for it.
        if (controller?.packageName == ANTENNAPOD && action.id in ANTENNAPOD_NEXT_CHAPTER) {
            ANTENNAPOD_NEXT_CHAPTER.forEach { controls.sendCustomAction(it, null) }
        } else {
            controls.sendCustomAction(action.id, action.extras)
        }
    }
    fun playQueueItem(track: QueueTrack) = controller?.transportControls?.skipToQueueItem(track.queueId)

    /**
     * Asks the app to find and play [query]: a song, artist, album or playlist. This goes through
     * the app's playback session, so it works for apps that keep their library from Gearslip, and
     * the app stays in the background. Apps may still ignore it (Spotify only takes Google's), so
     * if nothing new plays within [SEARCH_WAIT_MS] the search is marked ignored.
     */
    fun search(query: String) {
        val c = controller ?: return
        main.removeCallbacks(searchTimeout)
        searchBefore = _now.value
        _search.value = Search(query)
        GearslipLog.i("media: -> playFromSearch")
        c.transportControls.playFromSearch(query, Bundle())
        main.postDelayed(searchTimeout, SEARCH_WAIT_MS)
    }

    /**
     * Asks the app's library for matches to [query], to list for the driver to pick from. Unlike
     * [search], nothing plays until one is chosen, so a wrong guess costs nothing. Metrolist and
     * ViVi answer with songs from the library first, then YouTube Music.
     */
    fun searchLibrary(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            clearResults()
            return
        }
        val component = searchComponent
        if (component == null) {
            noLibrarySearch(q)
            return
        }
        resultsQuery = q
        _results.value = Results(q)
        val sb = searchBrowser
        if (sb != null && sb.isConnected) {
            runSearch(sb, q)
            return
        }
        if (sb != null) return // still connecting; it searches for [resultsQuery] once connected
        // The framework MediaBrowser has no search; the support library's does, over the same
        // service. Opened on first use only, so apps nobody searches never see a second client.
        lateinit var created: MediaBrowserCompat
        created = MediaBrowserCompat(context, component, object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                resultsQuery?.let { runSearch(created, it) }
            }

            override fun onConnectionFailed() {
                GearslipLog.w("media: no library connection for search")
                searchBrowser = null
                resultsQuery?.let { noLibrarySearch(it) }
            }
        }, null)
        searchBrowser = created
        runCatching { created.connect() }.onFailure {
            searchBrowser = null
            noLibrarySearch(q)
        }
    }

    private fun runSearch(sb: MediaBrowserCompat, q: String) {
        runCatching {
            sb.search(q, Bundle(), object : MediaBrowserCompat.SearchCallback() {
                override fun onSearchResult(query: String, extras: Bundle?, items: List<MediaBrowserCompat.MediaItem>) {
                    if (query != resultsQuery) return
                    GearslipLog.i("media: library search found ${items.size}")
                    val entries = items.map(::entryOfCompat)
                    if (query == playFirstFor) {
                        playFirstFor = null
                        val first = entries.firstOrNull { it.playable } ?: entries.firstOrNull()
                        if (first == null) {
                            clearResults()
                            search(query)
                        } else {
                            GearslipLog.i("media: playing the library's best match")
                            clearResults()
                            searchBefore = _now.value
                            _search.value = Search(query)
                            main.removeCallbacks(searchTimeout)
                            controller?.transportControls?.playFromMediaId(first.id, null)
                            main.postDelayed(searchTimeout, SEARCH_WAIT_MS)
                        }
                        return
                    }
                    _results.value = Results(query, entries)
                }

                override fun onError(query: String, extras: Bundle?) {
                    if (query != resultsQuery) return
                    if (query == playFirstFor) {
                        playFirstFor = null
                        clearResults()
                        search(query)
                        return
                    }
                    GearslipLog.i("media: the app has no library search")
                    _results.value = Results(query, emptyList(), supported = false)
                }
            })
        }.onFailure {
            GearslipLog.w("media: library search failed: ${it.message}")
            noLibrarySearch(q)
        }
    }

    private fun entryOfCompat(item: MediaBrowserCompat.MediaItem): MediaEntry {
        val d = item.description
        return MediaEntry(
            id = item.mediaId.orEmpty(),
            title = d.title?.toString().orEmpty(),
            subtitle = d.subtitle?.toString().orEmpty(),
            iconUri = d.iconUri,
            iconBitmap = d.iconBitmap,
            browsable = item.isBrowsable,
            playable = item.isPlayable,
        )
    }

    /** The library can't search: a voice "play X" falls back to the app's own search; a typed one says so. */
    private fun noLibrarySearch(q: String) {
        if (q == playFirstFor) {
            playFirstFor = null
            clearResults()
            search(q)
        } else {
            _results.value = Results(q, emptyList(), supported = false)
        }
    }

    /** A voice "play X" waiting on the library search, to play its first match instead of listing them. */
    private var playFirstFor: String? = null

    /**
     * Plays the best match for [query], for voice. Asks the app's library first and plays its top
     * result, which apps like ViVi honour where they ignore [search]; without a library search,
     * falls back to [search].
     */
    fun playBestMatch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        if (!canSearchLibrary) {
            search(q)
            return
        }
        playFirstFor = q
        searchLibrary(q)
    }

    /** The connected app's service, for the search connection; null when nothing is connected. */
    private var searchComponent: android.content.ComponentName? = null
    private var searchBrowser: MediaBrowserCompat? = null

    fun clearResults() {
        resultsQuery = null
        _results.value = null
    }

    /** Whether there's a library to search through, the better of the two searches. */
    val canSearchLibrary: Boolean get() = browser?.isConnected == true && searchComponent != null

    fun dismissSearch() {
        main.removeCallbacks(searchTimeout)
        main.removeCallbacks(clearSearch)
        _search.value = null
    }

    private val searchTimeout = Runnable {
        val pending = _search.value ?: return@Runnable
        if (pending.ignored) return@Runnable
        GearslipLog.w("media: nothing new played after a search")
        _search.value = Search(pending.query, ignored = true)
        main.postDelayed(clearSearch, SEARCH_NOTICE_MS)
    }

    private val clearSearch = Runnable { _search.value = null }

    /** Something new is playing: another track, or the same one started from a pause. */
    private fun checkSearch() {
        val pending = _search.value ?: return
        val before = searchBefore ?: return
        val now = _now.value
        if (pending.ignored || !now.playing) return
        if (now.title != before.title || !before.playing) {
            GearslipLog.i("media: search started playback")
            dismissSearch()
        }
    }

    /** The app's own like button if it has one, since that's the one its library listens to; else a heart rating. */
    fun toggleLike() {
        val now = _now.value
        now.likeAction?.let { custom(it); return }
        now.heart?.let { controller?.transportControls?.setRating(Rating.newHeartRating(!it)) }
    }

    fun toggleShuffle() {
        _now.value.shuffleAction?.let { custom(it); return }
        val next = if (_modes.value.shuffle == PlaybackStateCompat.SHUFFLE_MODE_NONE) {
            PlaybackStateCompat.SHUFFLE_MODE_ALL
        } else {
            PlaybackStateCompat.SHUFFLE_MODE_NONE
        }
        compat?.transportControls?.setShuffleMode(next)
    }

    /** Off, then the whole queue, then this one track, then off again - the order every player uses. */
    fun cycleRepeat() {
        _now.value.repeatAction?.let { custom(it); return }
        val next = when (_modes.value.repeat) {
            PlaybackStateCompat.REPEAT_MODE_NONE -> PlaybackStateCompat.REPEAT_MODE_ALL
            PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> PlaybackStateCompat.REPEAT_MODE_ONE
            else -> PlaybackStateCompat.REPEAT_MODE_NONE
        }
        compat?.transportControls?.setRepeatMode(next)
    }

    fun disconnect() {
        connecting = null
        runCatching { searchBrowser?.disconnect() }
        searchBrowser = null
        searchComponent = null
        clearResults()
        main.removeCallbacksAndMessages(null)
        runCatching { compat?.unregisterCallback(compatCallback) }
        compat = null
        _modes.value = Modes()
        runCatching { controller?.unregisterCallback(callback) }
        runCatching { subscribed?.let { browser?.unsubscribe(it) } }
        runCatching { browser?.disconnect() }
        controller = null
        browser = null
        subscribed = null
        _phase.value = Phase.IDLE
        _now.value = NowPlaying()
        _queue.value = emptyList()
        dismissSearch()
    }

    /**
     * Opens [app] on the phone, then connects once it publishes its session. The driver asks for
     * this from the "Open" button a session-only app (Spotify) shows when it isn't running yet.
     */
    fun launchAndConnect(app: MediaApp) {
        if (!openApp(app)) return
        disconnect()
        connecting = app
        _phase.value = Phase.CONNECTING
        // A cold start takes a few seconds to publish a session, so wait, then watch for longer.
        main.postDelayed({ attachToSession(app, launched = true) }, LAUNCH_WAIT_MS)
    }

    /** Brings [app] to the foreground on the phone so it publishes its session. */
    private fun openApp(app: MediaApp): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.component.packageName) ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            GearslipLog.i("media: opened ${app.label} to bring up its session")
            true
        }.getOrElse {
            GearslipLog.w("media: could not open ${app.label}: ${it.message}")
            false
        }
    }

    private companion object {
        const val CONNECT_WAIT_MS = 8_000L
        const val SESSION_RETRIES = 6
        const val SESSION_RETRY_MS = 500L
        /** After opening a cold app, how long to wait before the first look for its session. */
        const val LAUNCH_WAIT_MS = 1_500L
        /** Retries once the app has been opened: a cold start can take a few seconds to publish. */
        const val SESSION_RETRIES_LAUNCHED = 16
        /** How long an app gets to start playing a search before Gearslip says it didn't. */
        const val SEARCH_WAIT_MS = 6_000L
        const val SEARCH_NOTICE_MS = 6_000L
        /** How apps name the empty version of an icon. */
        val OUTLINE_WORDS = listOf("outline", "outlined", "border", "empty")
    }
}

/** Android Auto's browse layout hints, which any app built for it already sends. */
private const val ANTENNAPOD = "de.danoeh.antennapod"
/** The media3 service's name first: that's the one offered as the button's own. */
private val ANTENNAPOD_NEXT_CHAPTER = listOf("next_chapter", "action.de.danoeh.antennapod.core.service.next_chapter")

private const val STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
private const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
private const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
private const val STYLE_SINGLE_ITEM = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT"
private const val STYLE_GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
private const val STYLE_GRID = 2
private const val STYLE_CATEGORY_GRID = 4
