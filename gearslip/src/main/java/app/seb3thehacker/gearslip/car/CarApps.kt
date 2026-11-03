package app.seb3thehacker.gearslip.car

import androidx.annotation.StringRes
import app.seb3thehacker.gearslip.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import app.seb3thehacker.gearslip.ProjectedContent

/** One tile in the launcher and the screen it opens. */
class CarApp(
    val id: String,
    /** English, for matching what a driver says; [labelRes] is what the screen shows. */
    val label: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val content: @Composable () -> Unit,
)

/** Built-in apps. Third-party (Android Auto) apps are a later step and will register here too. */
object CarApps {

    val all: List<CarApp> = listOf(
        CarApp("web", "Browser", R.string.app_browser, Icons.Filled.Search) {
            WebApp(content = ProjectedContent.resolve(LocalContext.current))
        },
        CarApp("phone", "Screen sharing", R.string.app_screen_sharing, Icons.Filled.Share) {
            PhoneAppsScreen()
        },
        CarApp("dialer", "Phone", R.string.carset_phone, Icons.Filled.Call) {
            PhoneDialerScreen()
        },
        CarApp("settings", "Settings", R.string.settings_title, Icons.Filled.Settings) {
            CarSettingsScreen()
        },
        CarApp("allapps", "All apps", R.string.app_all_apps, Icons.Filled.List) {
            AllAppsScreen()
        },
    )

    fun find(id: String): CarApp? = all.firstOrNull { it.id == id }

    /** The screens [BuiltInApps.entries] offers here, so a choice list never names a hidden one. */
    fun shown(launcher: Boolean, canCall: Boolean): List<CarApp> {
        val ids = BuiltInApps.entries(launcher, canCall).mapNotNull { (it.builtIn?.screen as? CarScreen.App)?.id }.toSet()
        return all.filter { it.id in ids }
    }
}
