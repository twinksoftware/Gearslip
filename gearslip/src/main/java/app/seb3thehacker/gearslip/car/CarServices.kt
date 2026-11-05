package app.seb3thehacker.gearslip.car

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import app.seb3thehacker.gearslip.audio.CarAudio
import app.seb3thehacker.gearslip.host.CarAppCatalog
import app.seb3thehacker.gearslip.host.CarAppConnection
import app.seb3thehacker.gearslip.host.KnownApps
import app.seb3thehacker.gearslip.host.TemplateApp
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.MediaApp
import app.seb3thehacker.gearslip.media.MediaCatalog
import app.seb3thehacker.gearslip.notify.GearslipNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The two long-lived things the car keeps running: the map app and the media app.
 *
 * They belong to the car session rather than to whichever screen is showing, so the home
 * screen, the Car apps list and the media screen all look at the same connection. Leaving a
 * screen must not drop the route being followed or the song being played.
 */
object CarServices {

    private var context: Context? = null

    /** Buffering counts as playing: an app between songs is about to make sound again. */
    private val PLAYING_STATES = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING)
    /** How long after pausing for the car leaving to look again for an app that started by itself. */
    private const val REPAUSE_MS = 2_000L

    val nav: CarAppConnection by lazy { CarAppConnection(requireNotNull(context)) }
    val media: CarMedia by lazy { CarMedia(requireNotNull(context)) }

    /** [nav], or null before the car screen has set things up. */
    fun navOrNull(): CarAppConnection? = if (context != null) nav else null

    /** [media], or null before the car screen has set things up: for code outside the car UI. */
    fun mediaOrNull(): CarMedia? = if (context != null) media else null

    /**
     * A second, independent connection for templated apps that aren't navigation apps - Spotify's
     * own Car App Library service is the first of these. Kept apart from [nav] so opening one
     * can never bump the map off Home: this one gets its own screen ([CarScreen.Browse]) instead.
     */
    val browse: CarAppConnection by lazy { CarAppConnection(requireNotNull(context)) }

    private val _mediaApp = MutableStateFlow<MediaApp?>(null)
    val mediaApp: StateFlow<MediaApp?> = _mediaApp.asStateFlow()

    /**
     * Whether the phone has any navigation app installed at all. True until [autostart] has
     * actually checked, so the home screen never flashes "none found" before it knows.
     */
    private val _hasNavApps = MutableStateFlow(true)
    val hasNavApps: StateFlow<Boolean> = _hasNavApps.asStateFlow()

    /** Set when the driver stops the map by hand, so going Home doesn't immediately restart it. */
    private var navStopped = false
    private var autoplayPending = false

    /**
     * The app that was playing when the driver opened another one. Opening an app is only a look
     * at its library: until something plays there, the music carries on in this one, so leaving
     * the new app's screen goes back to it, as Android Auto does.
     */
    private var stillPlaying: MediaApp? = null

    fun init(appContext: Context) {
        context = appContext.applicationContext
        app.seb3thehacker.gearslip.notify.CarMotion.start(appContext)
        app.seb3thehacker.gearslip.call.CarCalls.start(appContext)
        CarAssistant.init(appContext)
        DirectionsVoice.init(appContext)
        CarBluetooth.init(appContext)
        BluetoothAudioCheck.init(appContext)
        LocationCheck.init(appContext)
        CarKeys.init(appContext)
        VoiceReply.init(appContext)
        // Loaded now, while nothing's waiting on it, so the first reply isn't slower than the rest.
        app.seb3thehacker.gearslip.speech.SpeechEngine.warm(appContext)
    }

    // --- map -----------------------------------------------------------------------------

    /**
     * [destination] is a search query to start navigating to right away - "navigate to X with Y"
     * from the voice assistant - left null for a plain app launch. An app that's already the one
     * running gets the destination sent to its live session instead of being torn down and
     * rebuilt, same as [CarAppConnection.navigateTo] documents.
     */
    fun connectNav(app: TemplateApp, frame: CarEnvironment.Frame, destination: String? = null) {
        navStopped = false
        CarSettings.setLastNav(app.component.flattenToString())
        if (destination != null && nav.connectedComponent == app.component) {
            nav.navigateTo(destination)
        } else {
            // Another map app taking over ends the old one's route, or it keeps guiding unseen.
            if (nav.connectedComponent != app.component) endRoute()
            nav.connect(app, frame.width, frame.height, frame.densityDpi, destination)
        }
    }

    fun stopNav() {
        navStopped = true
        endRoute()
        nav.disconnect()
    }

    private fun endRoute() {
        if (nav.navigating) nav.stopNavigating()
    }

    /** Undoes [stopNav]'s hold so the last map app comes back, same as it would after a restart. */
    suspend fun reconnectNav(frame: CarEnvironment.Frame) {
        navStopped = false
        autostart(frame)
    }

    // --- browse (non-navigation templated apps) -------------------------------------------

    /** Opens a templated app that isn't navigation, on its own screen - never on Home. */
    fun connectBrowse(app: TemplateApp, frame: CarEnvironment.Frame) {
        browse.connect(app, frame.width, frame.height, frame.densityDpi)
    }

    fun stopBrowse() {
        browse.disconnect()
    }

    // --- media ---------------------------------------------------------------------------

    fun openMedia(app: MediaApp, autoplay: Boolean = false) {
        CarSettings.setLastMedia(app.component.flattenToString())
        val phase = media.phase.value
        val live = phase == CarMedia.Phase.READY || phase == CarMedia.Phase.CONNECTING
        if (_mediaApp.value?.component == app.component && live) return

        val previous = _mediaApp.value
        stillPlaying = previous?.takeIf { it.component != app.component && media.now.value.playing && !autoplay }
        _mediaApp.value = app
        autoplayPending = autoplay
        media.connect(app)
        // Not torn down and rebuilt per app: the capture covers whatever the phone plays, so
        // switching media apps never costs the driver another consent dialog.
        if (CarSettings.pipesNow) CarAudio.request()
    }

    /**
     * Reopens the media app used last, for the car's media button when nothing is open. False
     * when there's none on record or it's been uninstalled since.
     */
    suspend fun reopenLastMedia(): Boolean {
        val ctx = context ?: return false
        val last = CarSettings.lastMedia.value?.let(ComponentName::unflattenFromString) ?: return false
        val app = withContext(Dispatchers.IO) { MediaCatalog.installed(ctx) }
            .firstOrNull { it.component == last } ?: return false
        openMedia(app)
        return true
    }

    /**
     * The media screen closed. If nothing started in the app it showed, go back to the one that
     * kept playing meanwhile, so the player and its buttons follow the music you can hear.
     */
    fun mediaScreenClosed() {
        val back = stillPlaying ?: return
        stillPlaying = null
        // A track loaded here, even one paused since, means the driver used this app.
        if (media.now.value.playing || media.now.value.title.isNotEmpty()) return
        app.seb3thehacker.gearslip.GearslipLog.i("media: nothing played in ${_mediaApp.value?.label}, back to ${back.label}")
        openMedia(back)
    }

    /** Closes the media app from the car: stops what it's playing and drops the connection. */
    fun closeMedia() {
        if (media.now.value.playing) media.togglePlay()
        media.disconnect()
        _mediaApp.value = null
        autoplayPending = false
        stillPlaying = null
    }

    /** Called once the media app is connected: starts playback if that was asked for. */
    fun autoplayIfDue() {
        if (!autoplayPending) return
        autoplayPending = false
        if (!media.now.value.playing) media.togglePlay()
    }

    // --- start and stop ------------------------------------------------------------------

    /** Brings back whatever was in use last time. Safe to call repeatedly. */
    suspend fun autostart(frame: CarEnvironment.Frame) {
        val ctx = context ?: return

        if (!navStopped && nav.status.value.phase == CarAppConnection.Phase.IDLE) {
            val navApps = withContext(Dispatchers.IO) { CarAppCatalog.installed(ctx) }
                .filter { it.isNavigation }
            _hasNavApps.value = navApps.isNotEmpty()
            // Never auto-pick one already known to fail outright - a driver who plugs in should
            // land on a map, not on the one tile marked with a red X.
            val candidates = navApps.filterNot { KnownApps.isBroken(ctx, it.component.packageName) }
            val last = CarSettings.lastNav.value
            // The one used last, or - first run, nothing on record yet - whichever comes first:
            // a driver who plugs in for the first time shouldn't have to go pick one by hand
            // before the map shows up at all.
            val lastComponent = last?.let(ComponentName::unflattenFromString)
            val app = candidates.firstOrNull { it.component == lastComponent } ?: candidates.firstOrNull()
            if (app != null) connectNav(app, frame)
        }

        if (_mediaApp.value == null) {
            val catalog = withContext(Dispatchers.IO) { MediaCatalog.installed(ctx) }
            // Something already playing on the phone wins over whatever was last used in the car -
            // a driver who started a song before plugging in shouldn't have to go open its tile by
            // hand just to see what's already going.
            val playing = withContext(Dispatchers.IO) { currentlyPlaying(ctx, catalog) }
            if (playing != null) {
                openMedia(playing)
            } else {
                val last = CarSettings.lastMedia.value
                val app = last?.let { id -> catalog.firstOrNull { it.component == ComponentName.unflattenFromString(id) } }
                if (app != null) openMedia(app, autoplay = CarSettings.autoplay.value)
            }
        }
    }

    /**
     * Whichever known car media app has an actively playing session right now, system-wide - not
     * just the one Gearslip itself last connected to. Reading another app's session needs
     * notification access, the same permission [app.seb3thehacker.gearslip.media.CarMedia]
     * already asks for to control apps that won't share their library.
     */
    private fun currentlyPlaying(ctx: Context, catalog: List<MediaApp>): MediaApp? {
        val manager = ctx.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(ctx, GearslipNotificationListener::class.java)
        val sessions = try {
            manager.getActiveSessions(listener)
        } catch (e: SecurityException) {
            return null
        }
        val playingPackages = sessions
            .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            .map { it.packageName }
        return playingPackages.firstNotNullOfOrNull { pkg -> catalog.firstOrNull { it.component.packageName == pkg } }
    }

    /**
     * The car went away mid-song: pause it, as Android Auto does, so it doesn't carry on out of
     * the phone's speaker. Unplugging a USB accessory isn't "audio becoming noisy" the way
     * headphones are, so the apps won't pause themselves. Every playing session is paused, not
     * just the one Gearslip opened: anything the car was playing was going through the car.
     * An app can start again on its own once the speaker is given back a moment later, which a
     * Deezer driver heard after turning the car off (issue #39), so the ones it paused are
     * looked at once more and paused again if they're playing.
     */
    fun pausePhoneMedia(why: String = "the car left") {
        pausedForNow = null
        pausedOwnForNow = false
        val paused = pause(why)
        if (paused.isEmpty()) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val again = paused.filter { it.playbackState?.state in PLAYING_STATES }
            again.forEach { runCatching { it.transportControls.pause() } }
            if (again.isNotEmpty()) {
                app.seb3thehacker.gearslip.GearslipLog.i("media: paused ${again.joinToString { it.packageName }} again: it started by itself after $why")
            }
        }, REPAUSE_MS)
    }

    /** What [pausePhoneMediaForNow] paused, for [resumePhoneMedia] to play again. */
    @Volatile private var pausedForNow: List<MediaController>? = null
    /** Without notification access, whether it paused the app Gearslip drives itself. */
    @Volatile private var pausedOwnForNow = false

    /** The car wants the speakers for a moment: pause, and remember what to play after. */
    fun pausePhoneMediaForNow() {
        pausedOwnForNow = false
        pausedForNow = pause("the car needs the speakers for a moment")
    }

    /** The car handed the speakers back: play what it paused, unless the driver has since. */
    fun resumePhoneMedia() {
        val paused = pausedForNow ?: return
        pausedForNow = null
        if (pausedOwnForNow) {
            pausedOwnForNow = false
            mediaOrNull()?.let { if (!it.now.value.playing) it.togglePlay() }
            return
        }
        val waiting = paused.filter { it.playbackState?.state == PlaybackState.STATE_PAUSED }
        waiting.forEach { runCatching { it.transportControls.play() } }
        if (waiting.isNotEmpty()) {
            app.seb3thehacker.gearslip.GearslipLog.i("media: playing ${waiting.joinToString { it.packageName }} again: the car gave the speakers back")
        }
    }

    /** What it paused. Without notification access it can only reach the app Gearslip drives itself. */
    private fun pause(why: String): List<MediaController> {
        val ctx = context ?: return emptyList()
        val manager = ctx.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(ctx, GearslipNotificationListener::class.java)
        val playing = try {
            manager.getActiveSessions(listener).filter { it.playbackState?.state in PLAYING_STATES }
        } catch (e: SecurityException) {
            null
        }
        if (playing == null) {
            // No notification access: the app Gearslip itself is driving is all it can reach.
            mediaOrNull()?.let {
                if (it.now.value.playing) {
                    it.togglePlay()
                    pausedOwnForNow = true
                }
            }
            return emptyList()
        }
        playing.forEach { runCatching { it.transportControls.pause() } }
        if (playing.isNotEmpty()) {
            app.seb3thehacker.gearslip.GearslipLog.i("media: paused ${playing.joinToString { it.packageName }}: $why")
        }
        return playing
    }

    /** The car went away: nothing should keep running for a screen that no longer exists. */
    fun shutdown() {
        context?.let { app.seb3thehacker.gearslip.notify.CarMotion.stop(it) }
        app.seb3thehacker.gearslip.call.CarCalls.stop()
        runCatching { nav.disconnect() }
        runCatching { media.disconnect() }
        runCatching { browse.disconnect() }
        CarAssistant.cancel()
        // About 900 MB that nothing needs once the car's gone.
        app.seb3thehacker.gearslip.speech.SpeechEngine.unload()
        CarAudio.release()
        _mediaApp.value = null
        navStopped = false
    }
}
