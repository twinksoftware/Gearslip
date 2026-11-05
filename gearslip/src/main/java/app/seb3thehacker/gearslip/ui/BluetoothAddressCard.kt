package app.seb3thehacker.gearslip.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.car.CarBluetooth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Asks for the phone's Bluetooth address on Home, once per car, after a car paired over
 * Bluetooth connected without it. Android keeps the address from apps, so the card opens About
 * phone for the driver to copy it, and when they come back offers what's on the clipboard.
 */
@Composable
internal fun BluetoothAddressCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val car by CarBluetooth.addressWanted.collectAsState()
    // Off to About phone; on the way back, the clipboard is looked at once.
    var waiting by rememberSaveable { mutableStateOf(false) }
    var found by rememberSaveable { mutableStateOf<String?>(null) }
    var typing by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!waiting) return@LifecycleEventEffect
        waiting = false
        // Android hands an app the clipboard only once its window has focus, a moment after resume.
        scope.launch {
            delay(CLIPBOARD_WAIT_MS)
            val copied = copiedAddress(context)
            if (copied != null) found = copied else typing = ""
        }
    }

    found?.let { address ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text(stringResource(R.string.bt_address_use_title)) },
            text = {
                Column {
                    Text(address, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
                    Text(stringResource(R.string.bt_address_use_body))
                }
            },
            confirmButton = {
                TextButton(onClick = { CarBluetooth.setTypedAddress(context, address); found = null }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { typing = address; found = null }) { Text(stringResource(R.string.bt_address_type)) } },
        )
    }
    typing?.let { initial ->
        AddressDialog(initial, onDismiss = { typing = null }) { typed ->
            CarBluetooth.setTypedAddress(context, typed).also { if (it) typing = null }
        }
    }

    val name = car ?: return
    if (CarBluetooth.typedAddress(context) != null) return
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        Spacer(Modifier.height(16.dp))
        Surface(
            color = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, contentDescription = null)
                    Text(
                        stringResource(R.string.bt_address_card_title, name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                Text(
                    stringResource(R.string.bt_address_card_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = { CarBluetooth.addressAsked(context) }) { Text(stringResource(R.string.bt_address_not_now)) }
                    Button(onClick = {
                        waiting = true
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { waiting = false; typing = "" }
                    }) { Text(stringResource(R.string.bt_address_open)) }
                }
            }
        }
    }
}

/** A Bluetooth address on the clipboard, tidied up; null when there's none. */
private fun copiedAddress(context: Context): String? {
    val clip = runCatching { context.getSystemService(ClipboardManager::class.java)?.primaryClip }.getOrNull() ?: return null
    if (clip.itemCount == 0) return null
    val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return null
    return ADDRESS.find(text)?.value?.uppercase()?.replace('-', ':')
}

private val ADDRESS = Regex("""[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}""")
private const val CLIPBOARD_WAIT_MS = 400L
