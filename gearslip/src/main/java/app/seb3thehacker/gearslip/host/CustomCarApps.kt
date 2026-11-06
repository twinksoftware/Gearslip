package app.seb3thehacker.gearslip.host

import android.content.Context
import app.seb3thehacker.gearslip.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Apps the driver added to the car by hand, for apps that never declared Android Auto support
 * (issue #84). A music app is driven through its playback session, the way Spotify's player is;
 * a messenger's notifications show on Gearslip's Messages screen, as any car messenger's do.
 * Neither needs anything from the app's developer.
 */
object CustomCarApps {

    enum class Kind(val id: String) { MEDIA("media"), MESSAGING("messaging") }

    data class App(val packageName: String, val kind: Kind)

    private const val KEY = "custom_car_apps"

    private val flow = MutableStateFlow<List<App>?>(null)

    /** Null until [all] has read the settings once. */
    val state: StateFlow<List<App>?> = flow

    fun all(context: Context): List<App> = flow.value ?: read(context).also { flow.value = it }

    fun of(context: Context, kind: Kind): List<App> = all(context).filter { it.kind == kind }

    fun isCustom(context: Context, packageName: String): Boolean = all(context).any { it.packageName == packageName }

    fun add(context: Context, packageName: String, kind: Kind) = save(context, all(context).filterNot { it.packageName == packageName } + App(packageName, kind))

    fun remove(context: Context, packageName: String) = save(context, all(context).filterNot { it.packageName == packageName })

    private fun save(context: Context, apps: List<App>) {
        flow.value = apps
        AppSettings.putString(context, KEY, apps.joinToString(",") { "${it.packageName}:${it.kind.id}" })
    }

    private fun read(context: Context): List<App> =
        AppSettings.getString(context, KEY, "").split(",").mapNotNull { item ->
            val pkg = item.substringBefore(':').takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val kind = Kind.entries.firstOrNull { it.id == item.substringAfter(':') } ?: return@mapNotNull null
            App(pkg, kind)
        }
}
