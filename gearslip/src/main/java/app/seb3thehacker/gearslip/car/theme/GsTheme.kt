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
    val corner: Dp = 12.dp,
    /** How far the top edge is lifted towards white, 0..1, fading evenly to the base colour at the centre. */
    val topLight: Float = 0f,
    /** How far the bottom edge is pushed towards black, 0..1. */
    val bottomShade: Float = 0f,
    /**
     * [bottomShade] for containers that are already light, where lifting the top towards white
     * barely shows: those get their depth from a darker bottom edge instead.
     */
    val bottomShadeOnLight: Float = 0f,
    /** Alpha of the thin highlight along the top rim, fading out down the sides; 0 for none. */
    val rimAlpha: Float = 0f,
    /** A solid darker base under the button, like a keycap; 0 for none. */
    val lip: Dp = 0.dp,
    /** How far a soft drop shadow falls below the button; 0 for none. Its blur is twice this. */
    val shadow: Dp = 0.dp,
    val shadowAlpha: Float = 0f,
    /**
     * A 1px bevel on the edge only - light along the top, dark along the bottom - so depth reads
     * at any size without shading the face itself. 0 for none.
     */
    val bevelTop: Float = 0f,
    val bevelBottom: Float = 0f,
    /** How far the face drops while pressed; 0 for none. */
    val sink: Dp = 0.dp,
    /**
     * Alpha of the shadow cast inside the top edge of a latched control (a selected option, the
     * current screen's nav button), so it reads as pushed in. 0 for none.
     */
    val insetAlpha: Float = 0f,
    /**
     * Alpha of the dark overlay while pressed. Heavier than a ripple on purpose: the car screen
     * shows a video stream a frame or two behind the finger, so subtle feedback gets lost.
     */
    val pressedDim: Float = 0.22f,
    /** Scale while pressed, 1 for none. */
    val pressedScale: Float = 0.95f,
    /**
     * How a control with a press-and-hold action says so. The peel needs a raised face to lift
     * from; a flat look takes the dot.
     */
    val holdMarker: HoldMarkerStyle = HoldMarkerStyle.PEEL,
    /**
     * Renders [GsButton] as a pill (radius 50% of its height) instead of the theme's corner.
     * Tiles and nav-bar buttons keep their own [gsShape] corners either way.
     */
    val pill: Boolean = false,
    /**
     * The shadow a panel floating over the map casts onto it (the docked player, the lyrics), and
     * the nav bar casts up. 0 for none, so a flat look stays flat.
     */
    val panelShadow: Dp = 0.dp,
    val panelShadowAlpha: Float = 0f,
    /** Replaces this look when the UI is light, for effects that only work on one background. */
    val light: ControlLook? = null,
)

object GsThemes {
    val Keycap = GsTheme(
        id = "keycap",
        name = "Keycap",
        // Short and dark, like the keys' own solid base.
        control = ControlLook(
            lip = 4.dp, pressedScale = 1f, holdMarker = HoldMarkerStyle.PEEL,
            panelShadow = 4.dp, panelShadowAlpha = 0.45f,
        ),
    )

    val Tactile = GsTheme(
        id = "tactile",
        name = "Tactile",
        control = ControlLook(
            topLight = 0.05f,
            shadow = 3.dp,
            shadowAlpha = 0.32f,
            bevelTop = 0.16f,
            bevelBottom = 0.22f,
            sink = 2.dp,
            insetAlpha = 0.30f,
            pressedDim = 0.12f,
            pressedScale = 1f,
            holdMarker = HoldMarkerStyle.PEEL,
            panelShadow = 12.dp,
            panelShadowAlpha = 0.42f,
            // A wide soft shadow bands once the car's video stream compresses it on a light
            // background, so light mode gets a tight one and leans on the bevel instead.
            light = ControlLook(
                topLight = 0.05f,
                shadow = 1.5.dp,
                shadowAlpha = 0.38f,
                bevelTop = 0.24f,
                bevelBottom = 0.30f,
                sink = 2.dp,
                insetAlpha = 0.30f,
                pressedDim = 0.12f,
                pressedScale = 1f,
                holdMarker = HoldMarkerStyle.PEEL,
                panelShadow = 4.dp,
                panelShadowAlpha = 0.22f,
            ),
        ),
    )

    val Flat = GsTheme(
        id = "flat",
        name = "Flat",
        control = ControlLook(holdMarker = HoldMarkerStyle.DOT),
    )

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

    val default = Flat
    val builtIn = listOf(Tactile, Keycap, Flat, Nothing)

    fun byId(id: String?): GsTheme = builtIn.firstOrNull { it.id == id } ?: default
}

val LocalGsTheme = staticCompositionLocalOf { GsThemes.default }
