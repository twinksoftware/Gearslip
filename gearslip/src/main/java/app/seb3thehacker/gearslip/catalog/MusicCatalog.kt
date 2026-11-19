package app.seb3thehacker.gearslip.catalog

import android.content.Context
import app.seb3thehacker.gearslip.car.CarServices
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.car.CarSettings.MusicSource
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.MediaCatalog
import app.seb3thehacker.gearslip.media.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The music catalogue presented by Gearslip's Nothing-style screens. There is no first-party
 * streaming any more: browse, search and playback all come from the connected media app (Vivi
 * Music, Echo Music, or any other app with a MediaBrowserService). This facade keeps the car
 * screens independent of which app is providing the music.
 */
object MusicCatalog {

    data class Track(
        val id: String,
        val title: String,
        val artists: String,
        val album: String?,
        val thumbnailUrl: String?,
        val durationSeconds: Long,
        val playable: Boolean = true,
    )

    data class Shelf(val title: String, val tracks: List<Track>)

    @Volatile
    private var appContext: Context? = null

    /** Connect to the configured music app and wait until it is usable. */
    suspend fun initialize(context: Context) {
        appContext = context.applicationContext
        val apps = withContext(Dispatchers.IO) { MediaCatalog.installed(context.applicationContext) }
        val source = CarSettings.musicSource.value
        val app = when (source) {
            MusicSource.VIVI -> apps.firstOrNull { it.component.packageName.contains("vivi", true) }
            MusicSource.ECHO -> apps.firstOrNull { it.component.packageName.startsWith("echo.music") }
            MusicSource.LAST_USED -> CarSettings.lastMedia.value?.let { saved ->
                apps.firstOrNull { it.component.flattenToString() == saved }
            }
            MusicSource.AUTO -> apps.firstOrNull {
                it.component.packageName.contains("vivi", true) ||
                    it.component.packageName.startsWith("echo.music")
            }
                ?: CarSettings.lastMedia.value?.let { saved ->
                    apps.firstOrNull { it.component.flattenToString() == saved }
                }
                ?: apps.firstOrNull()
        } ?: throw IllegalStateException("No music app found — install Vivi Music or Echo Music")

        CarServices.openMedia(app)
        withTimeoutOrNull(10_000) {
            CarServices.media.phase.first { it == CarMedia.Phase.READY || it == CarMedia.Phase.REJECTED }
        }
    }

    /** Shelves from the connected app's browse tree. Folders are non-playable and open on tap. */
    suspend fun home(): List<Shelf> {
        val media = CarServices.mediaOrNull() ?: return emptyList()
        withTimeoutOrNull(6_000) { media.browse.first { !it.loading || it.failed || it.unavailable } }
        val entries = media.browse.value.entries
        if (entries.isEmpty()) return emptyList()
        return entries.groupBy { it.group ?: it.subtitle.takeIf(String::isNotBlank) ?: "MUSIC" }
            .map { (title, items) -> Shelf(title, items.map(::toTrack)) }
    }

    /** Ask the connected app's own library for matches, then let the driver pick one. */
    suspend fun search(query: String): List<Track> {
        val media = CarServices.mediaOrNull() ?: return emptyList()
        media.searchLibrary(query)
        val found = withTimeoutOrNull(10_000) {
            media.results.first { it?.query == query.trim() && it.entries != null }
        }
        val entries = found?.entries ?: return emptyList()
        return entries.filter { it.playable }.map(::toTrack)
    }

    suspend fun suggestions(query: String): List<String> = emptyList()

    /** Descend into a browsable folder from the home shelves. */
    suspend fun browse(title: String) {
        val media = CarServices.mediaOrNull() ?: return
        val entry = media.browse.value.entries.firstOrNull { it.title.equals(title, true) } ?: return
        media.select(entry)
        delay(300)
    }

    private fun toTrack(entry: MediaEntry) = Track(
        id = entry.id,
        title = entry.title,
        artists = entry.subtitle,
        album = null,
        thumbnailUrl = entry.iconUri?.toString(),
        durationSeconds = 0L,
        playable = entry.playable,
    )
}
