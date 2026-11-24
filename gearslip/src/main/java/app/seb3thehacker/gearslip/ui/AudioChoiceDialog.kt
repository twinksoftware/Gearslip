package app.seb3thehacker.gearslip.ui

import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Modifier
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.BuildConfig

/**
 * Shown once, the first time the driver turns down Android's screen-sharing dialog: that dialog
 * is the price of sending music over USB, and most people who refuse it would rather not see it
 * again. Offers the two ways out: Bluetooth, or the one-time adb command.
 */
@Composable
fun AudioChoiceDialog(onUseBluetooth: () -> Unit, onKeepAsking: () -> Unit) {
    var showCommand by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onKeepAsking,
        title = { Text(stringResource(R.string.audio_bt_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.audio_bt_body))
                if (showCommand) {
                    Text(
                        stringResource(R.string.audio_keep_usb),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    CopyableCommand("adb shell appops set ${BuildConfig.APPLICATION_ID} PROJECT_MEDIA allow")
                    ShizukuGrantButton()
                }
                // Stacked full width: three labels this long never fit the dialog's one button row.
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onUseBluetooth, Modifier.fillMaxWidth()) { Text(stringResource(R.string.audio_use_bt)) }
                    OutlinedButton(onClick = onKeepAsking, Modifier.fillMaxWidth()) { Text(stringResource(R.string.audio_keep_asking)) }
                    if (!showCommand) {
                        TextButton(onClick = { showCommand = true }, Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.audio_never_ask))
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}

/**
 * Android lets an app capture other apps' audio only while it holds the microphone permission.
 * Once the driver has refused that twice, Android stops asking, and music silently stays on the
 * phone; this says why and where to turn it back on.
 */
@Composable
fun MicBlockedDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_mic_title)) },
        text = {
            Text(
                stringResource(R.string.audio_mic_body),
            )
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.audio_open_settings)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.worked_not_now)) } },
    )
}
