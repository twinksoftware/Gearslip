package app.seb3thehacker.gearslip.car

import androidx.compose.runtime.Composable
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

sealed interface CarScreen {
    data object Home : CarScreen
    data object Settings : CarScreen
    data object Apps : CarScreen

    /** Opened from the clock: the day's calendar and the weather, nothing that needs a keyboard. */
    data object Dashboard : CarScreen

    /** Opened from the weather chip or the dashboard: now, the next hours and the week. */
    data object Weather : CarScreen

    /** Everything the head unit's sensor channel has advertised and sent, raw. */
    data object VehicleData : CarScreen

    /** The current media app, full size: browse, lyrics and controls. */
    data object Media : CarScreen

    /**
     * A templated app that isn't a navigation app - Spotify's own Car App Library service, among
     * others - opened on its own screen rather than the Home map slot, which only a genuine
     * navigation app can claim.
     */
    data object Browse : CarScreen

    /** A messaging app's recent conversations, read from the notifications it posts. */
    data class Messages(val packageName: String) : CarScreen

    /** [replyTo] is a notification key to open straight into its reply pane. */
    data class Notifications(val replyTo: String? = null) : CarScreen
    data class App(val id: String) : CarScreen
}

enum class SidePanel { NONE, CONTROLS, LYRICS }

/** One step on the breadcrumb trail: the screen it shows and the word for it in the trail. */
data class Crumb(val screen: CarScreen, val label: String, @param:StringRes val labelRes: Int = 0) {
    /** [labelRes] when the step is one of Gearslip's own, translated; [label] for an app's own name. */
    @Composable
    fun shown(): String = if (labelRes != 0) stringResource(labelRes) else label
}

/**
 * Where the car UI is, and how it got there. A car touchscreen has no hardware back button, so
 * every screen that isn't reached from [home] is pushed onto a trail (like a browser's history,
 * or a file manager's breadcrumb bar) rather than replacing the last one outright - that's what
 * lets the breadcrumb bar show "Home > Apps > Settings" and jump straight back to any of them,
 * and lets [back] undo exactly one step instead of always dumping you back at Home. An open app
 * can claim a back step first (a browser going back a page) through [backInterceptor].
 */
class CarNavigator(startId: String) {

    private val stack = mutableStateListOf(
        Crumb(
            if (CarApps.find(startId) != null) CarScreen.App(startId) else CarScreen.Home,
            "Home",
            R.string.crumb_home,
        ),
    )

    /** The whole trail, root first. Size 1 means "at Home" - the breadcrumb bar hides then. */
    val trail: List<Crumb> get() = stack

    val current: CarScreen get() = stack.last().screen

    var backInterceptor: (() -> Boolean)? = null

    /** Pushes a new step, unless it's the same screen already on top - tapping the button for
     * where you already are shouldn't grow the trail. */
    private fun push(screen: CarScreen, label: String, @StringRes labelRes: Int = 0) {
        if (stack.last().screen == screen) return
        stack.add(Crumb(screen, label, labelRes))
    }

    /**
     * Whether a back button on this screen leads anywhere the nav bar doesn't: false when the
     * screen under it is Home or the app grid, which the nav bar's own buttons already reach.
     * Android Auto shows no back button on an app's first screen for the same reason.
     */
    val backLeadsSomewhere: Boolean
        get() = stack.size >= 2 && stack[stack.size - 2].screen.let { it != CarScreen.Home && it != CarScreen.Apps }

    /** The one way back to a clean slate: resets the whole trail to just Home. */
    fun home() {
        stack.clear()
        stack.add(Crumb(CarScreen.Home, "Home", R.string.crumb_home))
    }

    /**
     * What the column beside the map on Home is showing, if anything. The player lives in one
     * place at a time: with [SidePanel.CONTROLS] it moves into the column and the nav bar's pill
     * steps aside; with [SidePanel.LYRICS] the column is all lyrics and the pill comes back.
     * Every other screen has no room for the column, so there the pill stays in the nav bar.
     */
    var sidePanel by mutableStateOf(SidePanel.NONE)
        private set

    /**
     * The pill's dock button, an open/close switch for the panel: moves the player beside the map,
     * going Home first if need be, or closes the panel when something is already there. Switching
     * between the player and lyrics is done inside the panel itself.
     */
    fun togglePlayerDock() {
        if (current != CarScreen.Home) {
            sidePanel = SidePanel.CONTROLS
            home()
            return
        }
        sidePanel = if (sidePanel == SidePanel.NONE) SidePanel.CONTROLS else SidePanel.NONE
    }

    fun showSidePlayer() { sidePanel = SidePanel.CONTROLS }

    fun showSideLyrics() { sidePanel = SidePanel.LYRICS }

    fun closeSidePanel() { sidePanel = SidePanel.NONE }

    /** Component id of the app whose long-press menu (pin, close) is open, or null. */
    var appMenu by mutableStateOf<String?>(null)
        private set

    fun showAppMenu(componentId: String) { appMenu = componentId }

    fun dismissAppMenu() { appMenu = null }

    fun media() { push(CarScreen.Media, "Media", R.string.crumb_media) }

    fun browse(label: String) { push(CarScreen.Browse, label) }

    fun apps() { push(CarScreen.Apps, "Apps", R.string.crumb_apps) }

    fun settings() { push(CarScreen.Settings, "Settings", R.string.settings_title) }

    fun dashboard() { push(CarScreen.Dashboard, "Dashboard", R.string.crumb_dashboard) }

    fun weather() { push(CarScreen.Weather, "Weather", R.string.crumb_weather) }

    fun vehicleData() { push(CarScreen.VehicleData, "Vehicle data") }

    fun messages(packageName: String, label: String) { push(CarScreen.Messages(packageName), label) }

    /** The list lives on the dashboard; only a reply gets a screen of its own. */
    fun notifications(replyTo: String? = null) {
        if (replyTo == null) dashboard() else push(CarScreen.Notifications(replyTo), "Reply", R.string.crumb_reply)
    }

    fun open(id: String) { push(CarScreen.App(id), CarApps.find(id)?.label ?: id, CarApps.find(id)?.labelRes ?: 0) }

    /** Jumps straight to a step already on the trail, dropping everything after it - what
     * tapping a breadcrumb does. Index 0 (Home) is always on the trail, so this always succeeds. */
    fun jumpTo(index: Int) {
        while (stack.size > index + 1) stack.removeAt(stack.lastIndex)
    }

    /** One step back, same as tapping the crumb before the current one. */
    fun back() {
        if (backInterceptor?.invoke() == true) return
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

val LocalCarNavigator = staticCompositionLocalOf<CarNavigator> { error("no CarNavigator provided") }

/** "Is it dark outside", for apps that adapt to it. See [CarEnvironment]. */
val LocalDarkOutside = staticCompositionLocalOf { false }
