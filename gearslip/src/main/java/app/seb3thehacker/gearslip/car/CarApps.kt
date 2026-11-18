package app.seb3thehacker.gearslip.car

import androidx.annotation.StringRes
import app.seb3thehacker.gearslip.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/** One tile in the launcher and the screen it opens. */
class CarApp(
    val id: String,
    /** English, for matching what a driver says; [labelRes] is what the screen shows. */
    val label: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val content: @Composable () -> Unit,
)

/** Built-in apps that still have a screen of their own. */
object CarApps {

    val all: List<CarApp> = listOf(
        CarApp("settings", "Settings", R.string.settings_title, Icons.Filled.Settings) {
            CarSettingsScreen()
        },
        // Google Maps cannot be hosted as a car app, so this is the phone's own Maps mirrored to the
        // car. Its turn notifications still feed the rest of the UI while it guides.
        CarApp("googlemaps", "Google Maps", R.string.app_google_maps, Icons.Filled.Place) {
            GoogleMapsScreen()
        },
    )

    fun find(id: String): CarApp? = all.firstOrNull { it.id == id }

    /** The screens [BuiltInApps.entries] offers here, so a choice list never names a hidden one. */
    fun shown(launcher: Boolean, canCall: Boolean): List<CarApp> {
        val ids = BuiltInApps.entries(launcher, canCall).mapNotNull { (it.builtIn?.screen as? CarScreen.App)?.id }.toSet()
        return all.filter { it.id in ids }
    }
}
