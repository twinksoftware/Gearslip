package app.seb3thehacker.gearslip.car.theme

import androidx.compose.foundation.Canvas
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.R

// Nothing OS's palette, per design.md: an almost achromatic band from pure black to pure white,
// with Nothing Red as the one accent. Applied as a fixed scheme instead of Material You's
// dynamic colours, which is the point of the skin - the accent comes from the palette, never
// from the wallpaper, and the dark/light choice comes from the App theme setting alone.

private val NothingBlack = Color(0xFF000000)
private val NothingSurface1 = Color(0xFF0D0D0D)
private val NothingSurface2 = Color(0xFF1A1A1A)
private val NothingSurface3 = Color(0xFF242424)
private val NothingBorder = Color(0xFF2E2E2E)
private val NothingBorderSubtle = Color(0xFF1C1C1C)
private val NothingWhite = Color(0xFFFFFFFF)
private val NothingSecondary = Color(0xFF999999)
private val NothingMuted = Color(0xFF555555)
private val NothingRed = Color(0xFFFF3030)
private val NothingRedDim = Color(0xFF3A0A0A)
private val NothingRedTint = Color(0xFFFFE5E5)
private val NothingDarkRed = Color(0xFF8C0000)
private val NothingContainerLight = Color(0xFFE8E8E8)

/** The fixed Nothing scheme. Primary stays white (dark) / black (light), so the knob focus ring
 * and filled buttons follow the canvas instead of turning red; red is reserved for alerts. */
fun nothingColorScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = NothingWhite,
        onPrimary = NothingBlack,
        primaryContainer = NothingSurface2,
        onPrimaryContainer = NothingWhite,
        secondary = NothingSecondary,
        onSecondary = NothingBlack,
        secondaryContainer = NothingSurface2,
        onSecondaryContainer = NothingWhite,
        tertiary = NothingRed,
        onTertiary = NothingWhite,
        tertiaryContainer = NothingRedDim,
        onTertiaryContainer = NothingRed,
        background = NothingBlack,
        onBackground = NothingWhite,
        surface = NothingSurface1,
        onSurface = NothingWhite,
        surfaceVariant = NothingSurface2,
        onSurfaceVariant = NothingSecondary,
        inverseSurface = NothingSurface3,
        inverseOnSurface = NothingWhite,
        error = NothingRed,
        onError = NothingWhite,
        errorContainer = NothingRedDim,
        onErrorContainer = NothingRed,
        outline = NothingBorder,
        outlineVariant = NothingBorderSubtle,
        surfaceBright = NothingSurface2,
        surfaceContainer = NothingSurface2,
        surfaceContainerHigh = NothingSurface3,
        surfaceContainerHighest = NothingSurface3,
        surfaceContainerLow = NothingSurface1,
        surfaceContainerLowest = NothingBlack,
        surfaceDim = NothingBlack,
    )
} else {
    lightColorScheme(
        primary = NothingBlack,
        onPrimary = NothingWhite,
        primaryContainer = NothingContainerLight,
        onPrimaryContainer = NothingBlack,
        secondary = NothingMuted,
        onSecondary = NothingWhite,
        secondaryContainer = NothingContainerLight,
        onSecondaryContainer = NothingBlack,
        tertiary = NothingRed,
        onTertiary = NothingWhite,
        tertiaryContainer = NothingRedTint,
        onTertiaryContainer = NothingDarkRed,
        background = NothingWhite,
        onBackground = NothingBlack,
        surface = NothingWhite,
        onSurface = NothingBlack,
        surfaceVariant = NothingWhite,
        onSurfaceVariant = NothingMuted,
        inverseSurface = NothingSurface3,
        inverseOnSurface = NothingWhite,
        error = NothingRed,
        onError = NothingWhite,
        errorContainer = NothingRedTint,
        onErrorContainer = NothingDarkRed,
        outline = NothingBorderSubtle,
        outlineVariant = NothingBorder,
        surfaceBright = NothingWhite,
        surfaceContainer = NothingWhite,
        surfaceContainerHigh = NothingWhite,
        surfaceContainerHighest = NothingWhite,
        surfaceContainerLow = NothingWhite,
        surfaceContainerLowest = NothingWhite,
        surfaceDim = NothingWhite,
    )
}

/**
 * Nothing's type, standing in for the proprietary NDot/NType: Doto (OFL-1.1, dot matrix) for
 * display text, the system monospace family for labels and titles with the design's 0.08em
 * tracking. Nothing OS sets labels uppercase; a Typography has no text-transform, so call sites
 * uppercase only where the Nothing branch is active.
 */
fun nothingTypography(): Typography {
    val base = Typography()
    val doto = FontFamily(Font(R.font.doto))
    val mono = FontFamily.Monospace
    fun display(own: TextStyle) = own.copy(fontFamily = doto, fontWeight = FontWeight.Normal, letterSpacing = 0.02.em)
    fun mono(own: TextStyle) = own.copy(fontFamily = mono, letterSpacing = 0.08.em)
    return base.copy(
        displayLarge = display(base.displayLarge),
        displayMedium = display(base.displayMedium),
        displaySmall = display(base.displaySmall),
        labelLarge = mono(base.labelLarge),
        labelMedium = mono(base.labelMedium),
        labelSmall = mono(base.labelSmall),
        titleMedium = mono(base.titleMedium),
        titleLarge = mono(base.titleLarge),
    )
}

/** A dot-matrix texture: the design's "one zone per page maximum" - the launcher header only. */
@Composable
fun DotGrid(modifier: Modifier, dotColor: Color = NothingWhite.copy(alpha = 0.06f)) {
    Canvas(modifier) {
        val pitch = 16.dp.toPx()
        val dot = 2.dp.toPx()
        var x = 0f
        while (x < size.width) {
            var y = 0f
            while (y < size.height) {
                drawCircle(dotColor, radius = dot / 2, center = Offset(x + dot / 2, y + dot / 2))
                y += pitch
            }
            x += pitch
        }
    }
}