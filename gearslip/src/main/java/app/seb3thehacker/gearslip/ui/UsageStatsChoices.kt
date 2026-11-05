package app.seb3thehacker.gearslip.ui

import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import androidx.compose.animation.AnimatedVisibility
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import app.seb3thehacker.gearslip.stats.WorkedPrompt
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.stats.UsageStats

/** The pitch for the usage notes, in setup and Settings, with the privacy promise in bold so it isn't missed. */
internal fun usageStatsIntro(context: Context): AnnotatedString =
    AnnotatedString.fromHtml(context.getString(R.string.usage_intro))

/** The usage notes card for the setup guide: the same rows Settings shows. */
@Composable
internal fun UsageStatsChoices(modifier: Modifier = Modifier) {
    SettingsCard(modifier) { UsageStatsRows() }
}

/**
 * The on switch, what to share, and the note itself, saved as they change.
 * Rows only: the caller puts them in a card.
 */
@Composable
internal fun ColumnScope.UsageStatsRows() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(UsageStats.enabled(context)) }
    var choices by remember { mutableStateOf(UsageStats.choices(context)) }
    var noteOpen by remember { mutableStateOf(false) }
    fun save() = UsageStats.set(context, on, choices)
    // Nothing goes out while the choices are on screen; leaving them sends the first note.
    DisposableEffect(Unit) { onDispose { UsageStats.choicesClosed(context) } }

    SettingsRow(
        stringResource(R.string.usage_send),
        Modifier.toggleable(on, role = Role.Switch) { on = it; save() },
        subtitle = buildNote(context),
        icon = Icons.Filled.Send,
        trailing = { Switch(checked = on, onCheckedChange = null) },
    )
    AnimatedVisibility(on) {
        Column {
            SettingsDivider()
            Text(
                stringResource(R.string.usage_also_share),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            Choice(stringResource(R.string.usage_share_android), choices.android) { choices = choices.copy(android = it); save() }
            Choice(stringResource(R.string.usage_share_phone), choices.phone) { choices = choices.copy(phone = it); save() }
            Choice(stringResource(R.string.usage_share_cars), choices.cars) {
                choices = choices.copy(cars = it)
                save()
            }
            Choice(stringResource(R.string.usage_share_drives), choices.failures) {
                choices = choices.copy(failures = it)
                save()
            }
            SettingsDivider()
            SettingsRow(
                stringResource(R.string.usage_what_sent),
                Modifier.clickable { noteOpen = !noteOpen },
                subtitle = stringResource(R.string.usage_what_sent_sub),
                icon = Icons.Filled.Info,
                trailing = {
                    Icon(
                        if (noteOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(if (noteOpen) R.string.usage_hide_note else R.string.usage_show_note),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            AnimatedVisibility(noteOpen) {
                // Rebuilt from the saved choices each time one changes, so it always matches.
                val note = remember(choices) { notePreview(context) }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
    // Shown whether the notes are on or off, in setup and in Settings alike.
    SettingsDivider()
    SettingsRow(
        stringResource(R.string.privacy_policy),
        Modifier.clickable { GearslipLinks.open(context, GearslipLinks.PRIVACY) },
        subtitle = stringResource(R.string.privacy_policy_sub),
        icon = Icons.Filled.Lock,
    )
}

/** A share choice: the label lines up with the rows' titles, the checkbox sits at the end. */
@Composable
private fun Choice(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(start = 56.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(vertical = 8.dp))
    }
}

/** The note as it would go out now, with an example drive until a real one is in it. */
private fun notePreview(context: Context): String {
    val preview = UsageStats.preview(context, everything = true)
    // Until the first drive the list is empty, so show what one entry looks like.
    val example = if (preview.optJSONArray("sessions")?.length() == 0) {
        "\n\n" + context.getString(R.string.usage_example) + "\n" + UsageStats.exampleSession.toString(2)
    } else ""
    return preview.toString(2) + example
}

/** What's different about where this build's notes go; null for a normal release. */
private fun buildNote(context: Context): String? = when {
    BuildConfig.STATS_URL.isBlank() -> context.getString(R.string.usage_no_server)
    BuildConfig.DEBUG -> context.getString(R.string.usage_debug_build)
    else -> null
}

/**
 * The one-time ask after the first drive that worked, in the status card's place on Home. Share
 * turns on usage notes like the switch does; the link shows exactly what that sends first.
 */
@Composable
internal fun WorkedCard(car: String, onSeeWhatsSent: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier,
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Text(
                    if (car.isEmpty()) stringResource(R.string.worked_title_unknown) else stringResource(R.string.worked_title, car),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                stringResource(R.string.worked_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            buildNote(context)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onSeeWhatsSent, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(stringResource(R.string.usage_what_sent))
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { WorkedPrompt.answer(context, share = false) }) { Text(stringResource(R.string.worked_not_now)) }
                Button(onClick = { WorkedPrompt.answer(context, share = true) }) { Text(stringResource(R.string.worked_share)) }
            }
        }
    }
}
