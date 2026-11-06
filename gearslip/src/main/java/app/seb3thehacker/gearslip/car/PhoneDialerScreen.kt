package app.seb3thehacker.gearslip.car

import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.car.theme.*
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import app.seb3thehacker.gearslip.GearslipLog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A phone contact with at least one number, as read off the device's contacts. */
internal data class Contact(val name: String, val number: String)

private val CALL_PERMISSIONS = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)

/** Also used by [AssistantCommands] for "call <name>". */
internal fun hasCallPermissions(context: Context): Boolean =
    CALL_PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

internal fun loadContacts(context: Context): List<Contact> {
    if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val out = mutableListOf<Contact>()
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
    )
    context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        projection, null, null,
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
    )?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        while (cursor.moveToNext()) {
            val name = cursor.getString(nameIndex) ?: continue
            val number = cursor.getString(numberIndex) ?: continue
            out += Contact(name, number)
        }
    }
    return out.distinctBy { it.number }
}

/**
 * Places the call through Telecom directly. Starting ACTION_CALL from here launched the system's
 * call activity onto the car's display, which Android refuses for another app's activity, and
 * from the background, which it blocks too - so the call never went out.
 */
internal fun placeCall(context: Context, number: String) {
    if (number.isBlank()) return
    if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
        GearslipLog.w("calls: can't place a call without CALL_PHONE")
        return
    }
    val telecom = context.getSystemService(android.telecom.TelecomManager::class.java)
    runCatching { telecom!!.placeCall(Uri.fromParts("tel", number, null), null) }
        .onSuccess {
            GearslipLog.i("calls: placing a call")
            app.seb3thehacker.gearslip.call.CarCalls.outgoingCall(context, number)
        }
        .onFailure { GearslipLog.w("calls: placing a call failed: ${it.message}") }
}

/** A call another car screen wants to place - a number on a place page - waiting on the driver. */
internal object CallPrompt {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Contact?>(null)

    fun ask(name: String, number: String) {
        pending.value = Contact(name, number)
    }
}

/** The dialer's own confirmation, drawn over the whole car screen for [CallPrompt]. */
@Composable
internal fun CallPromptOverlay() {
    val context = LocalContext.current
    val contact by CallPrompt.pending.collectAsState()
    val c = contact ?: return
    ConfirmCallOverlay(
        c,
        onConfirm = {
            CallPrompt.pending.value = null
            placeCall(context, c.number)
        },
        onDismiss = { CallPrompt.pending.value = null },
    )
}

private const val TAB_RECENTS = 0
private const val TAB_CONTACTS = 1
private const val TAB_KEYPAD = 2

/** One number from the call log: its latest call, whether that was missed, and when. */
private data class RecentCall(val contact: Contact, val missed: Boolean, val at: Long)

/** The latest call to or from each number, newest first, as Android Auto lists them. */
private fun loadRecents(context: Context): List<RecentCall>? {
    if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
    val out = mutableListOf<RecentCall>()
    val seen = mutableSetOf<String>()
    runCatching {
        context.contentResolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            arrayOf(
                android.provider.CallLog.Calls.NUMBER,
                android.provider.CallLog.Calls.CACHED_NAME,
                android.provider.CallLog.Calls.TYPE,
                android.provider.CallLog.Calls.DATE,
            ),
            null, null,
            "${android.provider.CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext() && out.size < RECENTS_SHOWN) {
                val number = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                if (!seen.add(number)) continue
                val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: number
                val type = cursor.getInt(2)
                val missed = type == android.provider.CallLog.Calls.MISSED_TYPE || type == android.provider.CallLog.Calls.REJECTED_TYPE
                out += RecentCall(Contact(name, number), missed, cursor.getLong(3))
            }
        }
    }.onFailure { GearslipLog.w("calls: reading the call log failed: ${it.message}") }
    return out
}

private const val RECENTS_SHOWN = 50

/**
 * Calling from the car screen, laid out as Android Auto's dialer: recent calls first, since
 * calling back is the usual reason to open it, then contacts and a keypad.
 */
@Composable
fun PhoneDialerScreen() {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(hasCallPermissions(context)) }
    var tab by remember { mutableStateOf(TAB_RECENTS) }

    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // The tabs share the title's row, so the list and keypad get the height back.
        ScreenHeader(stringResource(R.string.carset_phone)) {
            if (hasPermissions) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialerTab(stringResource(R.string.dialer_recents), tab == TAB_RECENTS, Modifier.width(140.dp)) { tab = TAB_RECENTS }
                    DialerTab(stringResource(R.string.dialer_contacts), tab == TAB_CONTACTS, Modifier.width(140.dp)) { tab = TAB_CONTACTS }
                    DialerTab(stringResource(R.string.dialer_keypad), tab == TAB_KEYPAD, Modifier.width(140.dp)) { tab = TAB_KEYPAD }
                }
            }
        }

        if (!hasPermissions) {
            MissingPermission(context) { hasPermissions = true }
            return@Column
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                TAB_RECENTS -> RecentsList(context)
                TAB_CONTACTS -> ContactsList(context)
                else -> Box(Modifier.padding(bottom = 16.dp)) { Keypad(context) }
            }
        }
    }
}

@Composable
private fun MissingPermission(context: Context, onGranted: () -> Unit) {
    // Android's dialog can't show on the car screen, so it opens on the phone; checking again
    // every second picks the answer up without the driver having to come back in.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (!hasCallPermissions(context)) kotlinx.coroutines.delay(1_000)
        onGranted()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.dialer_needs_permission),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GsButton(tone = GsTone.Tonal, onClick = {
            app.seb3thehacker.gearslip.PermissionActivity.ask(context, CALL_PERMISSIONS)
        }) { Text(stringResource(R.string.dialer_ask)) }
    }
}

@Composable
private fun DialerTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    GsIconBox(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        colors = if (selected) GsColors(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        else GsColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
        shape = MaterialTheme.shapes.large,
        latched = selected,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/**
 * A 3-column digit grid beside a fourth column holding backspace and call, stacked - four equal
 * columns in all. Every row and both side buttons take an equal weighted share of whatever height
 * is available, so this fits the car's short landscape frame without scrolling instead of
 * overflowing off the bottom the way fixed-height rows did.
 */
@Composable
private fun Keypad(context: Context) {
    var number by remember { mutableStateOf("") }
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#"),
    )

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            number.ifEmpty { stringResource(R.string.dialer_enter_number) },
            style = MaterialTheme.typography.headlineSmall,
            color = if (number.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { digit ->
                            KeypadButton(digit, Modifier.weight(1f).fillMaxHeight()) { number += digit }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GsButton(
                    onClick = { if (number.isNotEmpty()) number = number.dropLast(1) },
                    tone = GsTone.Tonal,
                    enabled = number.isNotEmpty(),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { Text("⌫", style = MaterialTheme.typography.titleLarge) }
                GsIconBox(
                    onClick = { placeCall(context, number) },
                    colors = gsColors(GsTone.Primary),
                    enabled = number.isNotEmpty(),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { Icon(Icons.Filled.Call, stringResource(R.string.dialer_call)) }
            }
        }
    }
}

@Composable
private fun KeypadButton(digit: String, modifier: Modifier, onClick: () -> Unit) {
    GsIconBox(
        onClick,
        modifier,
        colors = GsColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
        shape = MaterialTheme.shapes.large,
    ) {
        Text(digit, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun RecentsList(context: Context) {
    // Null until read, then null again only when the call log isn't allowed.
    var loaded by remember { mutableStateOf(false) }
    val recents by produceState<List<RecentCall>?>(null, context) {
        value = withContext(Dispatchers.IO) { loadRecents(context) }
        loaded = true
    }
    var pendingCall by remember { mutableStateOf<Contact?>(null) }
    val list = recents

    when {
        !loaded -> Unit
        list == null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.dialer_recents_needs_permission),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GsButton(tone = GsTone.Tonal, onClick = {
                app.seb3thehacker.gearslip.PermissionActivity.ask(context, arrayOf(Manifest.permission.READ_CALL_LOG))
            }) { Text(stringResource(R.string.dialer_ask)) }
        }
        list.isEmpty() -> Text(
            stringResource(R.string.dialer_no_recents),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list) { call ->
                val now = System.currentTimeMillis()
                val ago = android.text.format.DateUtils.getRelativeTimeSpanString(call.at, now, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
                val detail = if (call.missed) stringResource(R.string.dialer_missed, ago) else ago
                ContactRow(call.contact, detail = detail, alert = call.missed) { pendingCall = call.contact }
            }
        }
    }

    pendingCall?.let { contact ->
        ConfirmCallOverlay(
            contact = contact,
            onConfirm = { placeCall(context, contact.number); pendingCall = null },
            onDismiss = { pendingCall = null },
        )
    }
}

@Composable
private fun ContactsList(context: Context) {
    val contacts by produceState(emptyList<Contact>(), context) {
        value = withContext(Dispatchers.IO) { loadContacts(context) }
    }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var pendingCall by remember { mutableStateOf<Contact?>(null) }

    val filtered = remember(contacts, query) {
        if (query.isBlank()) contacts
        else contacts.filter { it.name.contains(query, ignoreCase = true) || it.number.contains(query) }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (contacts.isNotEmpty()) {
            ContactSearchField(query, searching, onToggle = { searching = !searching })
        }
        // The list (and its empty states) sit between the search field and the keyboard, never
        // under either - the keyboard is docked at the bottom instead of jammed in right below
        // the field, so results stay visible while typing rather than getting squeezed out.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                contacts.isEmpty() -> Text(
                    stringResource(R.string.dialer_no_contacts),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                filtered.isEmpty() -> Text(
                    stringResource(R.string.dialer_no_match, query),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered) { contact ->
                        ContactRow(contact) { pendingCall = contact }
                    }
                }
            }
        }
        if (searching) {
            CarKeyboard(
                query,
                onTextChange = { query = it },
                onSubmit = { searching = false },
                onDismiss = { searching = false },
            )
        }
    }

    pendingCall?.let { contact ->
        ConfirmCallOverlay(
            contact = contact,
            onConfirm = { placeCall(context, contact.number); pendingCall = null },
            onDismiss = { pendingCall = null },
        )
    }
}

/**
 * A plain Compose overlay, not [androidx.compose.material3.AlertDialog] - that opens a real
 * Android Dialog/window, and this UI lives in a Presentation on a virtual display where a second
 * window of the wrong type crashes outright ("Window type mismatch"). Every other popup in the
 * car UI is drawn in-composition for the same reason; this just follows suit.
 */
@Composable
private fun ConfirmCallOverlay(contact: Contact, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onDismiss,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = {},
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(0.85f).padding(16.dp),
        ) {
            Column(
                Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.dialer_call_who, contact.name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(contact.number, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GsButton(onClick = onDismiss, tone = GsTone.Outline, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text(stringResource(R.string.cancel), style = MaterialTheme.typography.titleMedium)
                    }
                    GsButton(onClick = onConfirm, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text(stringResource(R.string.dialer_call), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactSearchField(query: String, active: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = MaterialTheme.shapes.large,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            query.ifEmpty { stringResource(R.string.dialer_search) },
            style = MaterialTheme.typography.bodyLarge,
            color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun ContactRow(contact: Contact, detail: String = contact.number, alert: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    contact.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(contact.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.dialer_call), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
