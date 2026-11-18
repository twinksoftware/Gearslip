package app.seb3thehacker.gearslip.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.seb3thehacker.gearslip.car.theme.nothingColorScheme
import app.seb3thehacker.gearslip.car.theme.nothingTypography

/**
 * Nothing OS is the only look: the phone and the car share its fixed palette and type. The car's
 * App theme setting still decides whether that palette is light or dark, so the two stay in step.
 */
@Composable
fun GearslipTheme(content: @Composable () -> Unit) {
    val dark = gearslipDark()
    MaterialTheme(
        colorScheme = nothingColorScheme(dark),
        typography = nothingTypography(),
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
