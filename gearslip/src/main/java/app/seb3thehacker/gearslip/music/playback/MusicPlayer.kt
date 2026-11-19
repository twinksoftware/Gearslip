package app.seb3thehacker.gearslip.music.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.media.session.PlaybackState
import app.seb3thehacker.gearslip.catalog.MusicCatalog
import app.seb3thehacker.gearslip.car.CarServices
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.MediaEntry
import app.seb3thehacker.gearslip.media.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The car's music player state, mirrored from whichever external media app is connected (Vivi,
 * Echo, or any other app with a MediaBrowserService). Playback happens in that app — Gearslip only
 * presents its state in the Nothing style and sends transport commands back to its session.
 */
object MusicPlayer {

    data class State(
        val track: MusicCatalog.Track? = null,
        val art: Bitmap? = null,
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val activeIndex: Int = -1,
        val shuffle: Boolean = false,
        /** 0 = off, 1 = repeat all, 2 = repeat one. */
        val repeat: Int = 0,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _queue = MutableStateFlow<List<MusicCatalog.Track>>(emptyList())
    val queue: StateFlow<List<MusicCatalog.Track>> = _queue.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val media = CarServices.media
        scope.launch {
            launch {
                media.now.collect { now ->
                    val track = if (now.title.isBlank()) null else MusicCatalog.Track(
                        id = "",
                        title = now.title,
                        artists = now.artist,
                        album = now.album.takeIf(String::isNotBlank),
                        thumbnailUrl = now.artUri?.toString(),
                        durationSeconds = now.durationMs / 1000,
                    )
                    _state.value = _state.value.copy(
                        track = track,
                        art = now.art,
                        playing = now.playing,
                        buffering = now.state == PlaybackState.STATE_BUFFERING,
                        positionMs = now.currentPosition(),
                        durationMs = now.durationMs,
                        error = now.error,
                        activeIndex = activeIndexFor(now),
                    )
                }
            }
            launch {
                media.queue.collect { items ->
                    _queue.value = items.map { item ->
                        MusicCatalog.Track(
                            id = item.queueId.toString(),
                            title = item.title,
                            artists = item.subtitle,
                            album = null,
                            thumbnailUrl = item.iconUri?.toString(),
                            durationSeconds = 0L,
                        )
                    }
                }
            }
            launch {
                media.modes.collect { modes ->
                    _state.value = _state.value.copy(
                        shuffle = modes.shuffle == android.support.v4.media.session.PlaybackStateCompat.SHUFFLE_MODE_ALL ||
                            modes.shuffle == android.support.v4.media.session.PlaybackStateCompat.SHUFFLE_MODE_GROUP,
                        repeat = when (modes.repeat) {
                            android.support.v4.media.session.PlaybackStateCompat.REPEAT_MODE_ONE -> 2
                            android.support.v4.media.session.PlaybackStateCompat.REPEAT_MODE_ALL,
                            android.support.v4.media.session.PlaybackStateCompat.REPEAT_MODE_GROUP -> 1
                            else -> 0
                        },
                    )
                }
            }
        }
    }

    private fun activeIndexFor(now: NowPlaying): Int {
        if (now.title.isBlank()) return -1
        val byId = _queue.value.indexOfFirst { it.id == now.activeQueueItemId.toString() }
        if (byId >= 0) return byId
        return _queue.value.indexOfFirst { it.title.equals(now.title, true) }
    }

    /** Keeps the displayed playhead moving between session callbacks. */
    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (true) {
                val now = CarServices.mediaOrNull()?.now?.value
                if (now != null && now.playing) {
                    _state.value = _state.value.copy(positionMs = now.currentPosition())
                }
                delay(500)
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    fun play(tracks: List<MusicCatalog.Track>, startIndex: Int = 0) {
        val track = tracks.getOrNull(startIndex) ?: return
        val media = CarServices.mediaOrNull() ?: return
        media.select(
            MediaEntry(
                id = track.id,
                title = track.title,
                subtitle = track.artists,
                iconUri = track.thumbnailUrl?.let(Uri::parse),
                iconBitmap = null,
                browsable = !track.playable,
                playable = track.playable,
            )
        )
    }

    fun playAt(index: Int) = play(_queue.value, index)

    fun next() = CarServices.mediaOrNull()?.next()
    fun previous() = CarServices.mediaOrNull()?.previous()

    fun togglePlay() {
        val media = CarServices.mediaOrNull() ?: return
        media.togglePlay()
        if (media.now.value.playing) startTicking() else stopTicking()
    }

    fun seek(ms: Long) = CarServices.mediaOrNull()?.seek(ms)
    fun toggleShuffle() = CarServices.mediaOrNull()?.toggleShuffle()
    fun cycleRepeat() = CarServices.mediaOrNull()?.cycleRepeat()

    fun release() {
        stopTicking()
        initialized = false
    }
}
