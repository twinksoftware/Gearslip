package app.seb3thehacker.gearslip.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import app.seb3thehacker.gearslip.car.theme.GsThemes
import app.seb3thehacker.gearslip.car.theme.nothingColorScheme
import app.seb3thehacker.gearslip.car.theme.nothingTypography
import app.seb3thehacker.gearslip.car.theme.withTintedSurfaces

/**
 * Material You: the palette comes from the wallpaper (minSdk 35, so dynamic color always exists),
 * with the same surfaces as the car screen, so the two look like one app. With the Nothing skin
 * picked, both swap to its fixed scheme and type instead, so the phone and the car stay one look.
 */
@Composable
fun GearslipTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = gearslipDark()
    // The car's Dark mode setting, so the phone and the car screen stay one look.
    val darkStyle by app.seb3thehacker.gearslip.car.CarSettings.darkStyle.collectAsState()
    val uiThemeId by app.seb3thehacker.gearslip.car.CarSettings.uiTheme.collectAsState()
    val nothing = uiThemeId == GsThemes.Nothing.id
    val scheme = if (nothing) {
        nothingColorScheme(dark)
    } else {
        (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).withTintedSurfaces(dark, darkStyle)
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = if (nothing) nothingTypography() else Typography(),
        content = content,
    )
}

/** Whether the phone app is dark: the car's App theme setting, so Light means light everywhere. */
@Composable
fun gearslipDark(): Boolean {
    val theme by app.seb3thehacker.gearslip.car.CarSettings.appTheme.collectAsState()
    return when (theme) {
        app.seb3thehacker.gearslip.car.AppTheme.PHONE -> isSystemInDarkTheme()
        app.seb3thehacker.gearslip.car.AppTheme.LIGHT -> false
        app.seb3thehacker.gearslip.car.AppTheme.DARK -> true
    }
}
