package app.seb3thehacker.gearslip.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow

internal data class Contact(val name: String, val number: String)

/** A call another car screen wants to place - a number on a place page - waiting on the driver. */
internal object CallPrompt {
    val pending = MutableStateFlow<Contact?>(null)

    fun ask(name: String, number: String) {
        pending.value = Contact(name, number)
    }
}

/** Places the call through Telecom, the only path that works from the car side. */
internal fun placeCall(context: Context, number: String) {
    if (number.isBlank()) return
    if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
        GearslipLog.w("calls: can't place a call without CALL_PHONE")
        return
    }
    val telecom = context.getSystemService(android.telecom.TelecomManager::class.java)
    runCatching { telecom!!.placeCall(Uri.fromParts("tel", number, null), null) }
        .onSuccess { app.seb3thehacker.gearslip.call.CarCalls.outgoingCall(context, number) }
        .onFailure { GearslipLog.w("calls: placing a call failed: ${it.message}") }
}

/** Confirmation for a call a hosted app asked to place. Sharp edges, Nothing red only on Confirm. */
@Composable
internal fun CallPromptOverlay() {
    val context = LocalContext.current
    val contact by CallPrompt.pending.collectAsState()
    val c = contact ?: return
    AlertDialog(
        onDismissRequest = { CallPrompt.pending.value = null },
        title = { Text(c.name.ifBlank { c.number }) },
        text = { Text(c.number, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = {
                CallPrompt.pending.value = null
                placeCall(context, c.number)
            }) { Text("Call") }
        },
        dismissButton = {
            TextButton(onClick = { CallPrompt.pending.value = null }) { Text("Cancel") }
        },
    )
}
