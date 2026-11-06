package app.seb3thehacker.gearslip.media

import app.seb3thehacker.gearslip.host.CustomCarApps

import app.seb3thehacker.gearslip.host.AndroidAuto
import app.seb3thehacker.gearslip.host.CarDescriptor
import app.seb3thehacker.gearslip.host.KnownApps
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.media.MediaBrowserService

/** A media app that has told the system it belongs in a car. */
class MediaApp(
    val component: ComponentName,
    val label: String,
    /** Whose audio to capture: playback capture is scoped to one uid so nothing else leaks in. */
    val uid: Int,
    /**
     * Added by the driver rather than found (issue #84): there's no library to connect to, so
     * the player goes straight to the app's playback session, as it does for Spotify.
     */
    val sessionOnly: Boolean = false,
)

/**
 * Finds media apps that opted in to Android Auto.
 *
 * An app is a car media app when it exports a MediaBrowserService *and* its car descriptor (see
 * [CarDescriptor]) lists `media`. The descriptor is what separates Deezer from a phone-only
 * player that merely exposes a browser service to Bluetooth and Wear - and from messengers like
 * Signal and Molly, which declare only `notification` but keep a browser service for voice notes.
 */
object MediaCatalog {

    fun installed(context: Context): List<MediaApp> {
        val manager = context.packageManager
        return manager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.serviceInfo.packageName != context.packageName }
            .filterNot { AndroidAuto.matches(it.serviceInfo.packageName, it.loadLabel(manager).toString()) }
            .filter { declaresMedia(manager, it.serviceInfo) }
            .map {
                val info = it.serviceInfo
                MediaApp(
                    component = ComponentName(info.packageName, info.name),
                    label = info.applicationInfo?.loadLabel(manager)?.toString() ?: info.packageName,
                    uid = info.applicationInfo?.uid ?: -1,
                )
            }
            .distinctBy { it.component.packageName }
            .toList()
            .let { found -> found + added(context, found.map { it.component.packageName }.toSet()) }
            .sortedBy { it.label.lowercase() }
    }

    /** The music apps the driver added by hand, unless the app has since become a car app itself. */
    private fun added(context: Context, found: Set<String>): List<MediaApp> {
        val manager = context.packageManager
        return CustomCarApps.of(context, CustomCarApps.Kind.MEDIA).filter { it.packageName !in found }.mapNotNull { custom ->
            val info = runCatching { manager.getApplicationInfo(custom.packageName, 0) }.getOrNull() ?: return@mapNotNull null
            MediaApp(
                // No service to bind: the class name only keeps the component unique and readable.
                component = ComponentName(custom.packageName, SESSION_ONLY_CLASS),
                label = info.loadLabel(manager).toString(),
                uid = info.uid,
                sessionOnly = true,
            )
        }
    }

    /** What an added app's component names, since it has no browser service. */
    const val SESSION_ONLY_CLASS = "gearslip.session-only"

    /** A descriptor that can't be read keeps the app, rather than dropping a real player over an odd manifest. */
    private fun declaresMedia(manager: PackageManager, service: android.content.pm.ServiceInfo): Boolean {
        if (CarDescriptor.meta(manager, service.packageName, service) == null) return false
        val uses = CarDescriptor.uses(manager, service.packageName, service)
        return uses.isNullOrEmpty() || "media" in uses
    }
}
