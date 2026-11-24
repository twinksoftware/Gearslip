package app.seb3thehacker.gearslip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.shizuku.ShizukuSetup

/**
 * Under an adb command: does the same from the phone, for drivers who run Shizuku. Shows nothing
 * when Shizuku isn't installed, so everyone else sees the command alone, as before.
 */
@Composable
fun ShizukuGrantButton(modifier: Modifier = Modifier) {
    val state by ShizukuSetup.state.collectAsState()
    val granted by ShizukuSetup.granted.collectAsState()
    val working by ShizukuSetup.working.collectAsState()
    // Shizuku may have been started, or the grant done over adb, while Gearslip was away.
    LifecycleResumeEffect(Unit) {
        ShizukuSetup.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { ShizukuSetup.refresh() }
    if (state == ShizukuSetup.State.MISSING) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            // Phone apps on the car screen need Shizuku's permission even when adb did the grants.
            granted && state == ShizukuSetup.State.READY -> Text(stringResource(R.string.shizuku_done), style = MaterialTheme.typography.bodyMedium)
            state == ShizukuSetup.State.STOPPED -> Text(stringResource(R.string.shizuku_stopped), style = MaterialTheme.typography.bodyMedium)
            else -> OutlinedButton(onClick = ShizukuSetup::setUp, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (working) R.string.shizuku_working else R.string.shizuku_set_up))
            }
        }
    }
}

/**
 * Settings' own place for Shizuku, whatever the audio choice: the setup guide and Help only show
 * the adb steps when music goes through Gearslip, but phone apps on the car screen need Shizuku
 * too. Shown only when Shizuku is installed.
 */
@Composable
fun ShizukuSection() {
    val state by ShizukuSetup.state.collectAsState()
    LaunchedEffect(Unit) { ShizukuSetup.refresh() }
    if (state == ShizukuSetup.State.MISSING) return
    SettingsSection(stringResource(R.string.settings_shizuku), footer = stringResource(R.string.settings_shizuku_footer)) {
        ShizukuGrantButton(Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}
