package app.seb3thehacker.gearslip.car.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the car UI's controls look, separate from its colours: [MaterialTheme]'s colour scheme still
 * decides what colour a button is, and this decides how that colour is drawn. A theme is plain
 * data, so a user-supplied theme later is just another instance - nothing about the built-in ones
 * is special beyond being listed in [GsThemes.builtIn].
 */
@Immutable
data class GsTheme(
    /** Stable key persisted in settings; never reuse one for a different look. */
    val id: String,
    val name: String,
    val control: ControlLook,
)

/** The finish on anything tappable that has a container: buttons, toggles, keypad keys. */
@Immutable
data class ControlLook(
    /** Corner radius of tiles and nav buttons; buttons themselves use [pill]. */
    val corner: Dp = 0.dp,
    /**
     * Alpha of the dark overlay while pressed. Heavier than a ripple on purpose: the car screen
     * shows a video stream a frame or two behind the finger, so subtle feedback gets lost.
     */
    val pressedDim: Float = 0.22f,
    /** Scale while pressed, 1 for none. */
    val pressedScale: Float = 1f,
    /** How a control with a press-and-hold action says so. */
    val holdMarker: HoldMarkerStyle = HoldMarkerStyle.DOT,
    /**
     * Renders [GsButton] as a pill (radius 50% of its height) instead of [corner].
     * Tiles and nav-bar buttons keep their own [gsShape] corners either way.
     */
    val pill: Boolean = true,
)

object GsThemes {
    // Nothing OS skin: sharp edges, no depth, a soft press, and pill buttons; the palette and
    // typography swap is done by CarUi / GearslipTheme on the id.
    val Nothing = GsTheme(
        id = "nothing",
        name = "Nothing",
        control = ControlLook(
            corner = 0.dp, pressedDim = 0.06f, pressedScale = 1f,
            pill = true, holdMarker = HoldMarkerStyle.DOT,
        ),
    )

    val default = Nothing
    val builtIn = listOf(Nothing)

    fun byId(id: String?): GsTheme = builtIn.firstOrNull { it.id == id } ?: default
}

val LocalGsTheme = staticCompositionLocalOf { GsThemes.default }
