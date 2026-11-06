package app.seb3thehacker.gearslip.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.car.app.CarAppService

/** A templated app installed on the phone, and the category it asked to be treated as. */
class TemplateApp(
    val component: ComponentName,
    val label: String,
    val categories: List<String>,
) {
    val isNavigation: Boolean get() = CarAppService.CATEGORY_NAVIGATION_APP in categories

    /** A media car screen, or one that names no category (older media apps). */
    val isMediaScreen: Boolean get() = kind == "media" || kind == "app"

    /** "navigation", "media", ... - what the app told the system it is. */
    val kind: String
        get() = categories.firstOrNull { it.startsWith(CATEGORY_PREFIX) }
            ?.removePrefix(CATEGORY_PREFIX)?.lowercase()
            ?: "app"

    private companion object {
        const val CATEGORY_PREFIX = "androidx.car.app.category."
    }
}

/**
 * Finds apps that can be rendered by a host.
 *
 * A templated app exports a CarAppService and never draws anything itself - it hands the host
 * a template and lets the host decide how it looks. That is what makes hosting worth doing:
 * the app arrives already designed for a car screen, rather than a phone layout squeezed onto
 * one, which is all mirroring could ever manage.
 */
object CarAppCatalog {

    fun installed(context: Context): List<TemplateApp> {
        val manager = context.packageManager
        val intent = Intent(CarAppService.SERVICE_INTERFACE)
        return manager.queryIntentServices(intent, 0)
            .asSequence()
            .filter { it.serviceInfo.packageName != context.packageName }
            .filterNot { AndroidAuto.matches(it.serviceInfo.packageName, it.loadLabel(manager).toString()) }
            .map { resolved ->
                val info = resolved.serviceInfo
                TemplateApp(
                    component = ComponentName(info.packageName, info.name),
                    label = info.applicationInfo?.loadLabel(manager)?.toString() ?: info.packageName,
                    categories = categoriesOf(context, info.packageName, info.name),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * queryIntentServices does not report the matched filter's categories, so the categories
     * are recovered by re-querying with each one and seeing which still match.
     */
    private fun categoriesOf(context: Context, packageName: String, className: String): List<String> {
        val manager = context.packageManager
        return KNOWN_CATEGORIES.filter { category ->
            val probe = Intent(CarAppService.SERVICE_INTERFACE)
                .setPackage(packageName)
                .addCategory(category)
            manager.queryIntentServices(probe, 0).any { it.serviceInfo.name == className }
        }
    }

    private val KNOWN_CATEGORIES = listOf(
        CarAppService.CATEGORY_NAVIGATION_APP,
        CarAppService.CATEGORY_POI_APP,
        CarAppService.CATEGORY_PARKING_APP,
        CarAppService.CATEGORY_CHARGING_APP,
        CarAppService.CATEGORY_MESSAGING_APP,
        CarAppService.CATEGORY_CALLING_APP,
        CarAppService.CATEGORY_SETTINGS_APP,
        CarAppService.CATEGORY_IOT_APP,
        CarAppService.CATEGORY_WEATHER_APP,
        // Newer apps declare MEDIA, but our AndroidX 1.7 dependency has no constant for it.
        "androidx.car.app.category.MEDIA",
    )
}
