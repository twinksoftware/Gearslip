package app.seb3thehacker.gearslip.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.CableLink
import app.seb3thehacker.gearslip.R

/**
 * Wired Android Auto by hand, under the wireless button: opens the car on the cable, for when
 * wireless came up first or the car's first try was dismissed. Hidden with the cable switched off.
 */
@Composable
internal fun CableButton() {
    val context = LocalContext.current
    if (!remember { AppSettings.cableEnabled(context) }) return
    var nothingPlugged by remember { mutableStateOf(false) }
    FilledTonalButton(
        onClick = {
            if (CableLink.attached(context) == null) nothingPlugged = true else CableLink.connect?.invoke()
        },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(56.dp),
    ) {
        Text(stringResource(R.string.cable_connect), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
    if (nothingPlugged) {
        AlertDialog(
            onDismissRequest = { nothingPlugged = false },
            title = { Text(stringResource(R.string.cable_none_title)) },
            text = { Text(stringResource(R.string.cable_none_body)) },
            confirmButton = { TextButton(onClick = { nothingPlugged = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
