package app.seb3thehacker.gearslip.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.browser.BrowserSettings
import app.seb3thehacker.gearslip.browser.SearchEngine
import app.seb3thehacker.gearslip.browser.adblock.AdBlockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's settings for the car's browser (issue #85): its bookmarks, where searches go, and
 * the ad blocker with its filter lists. Typing addresses is easier here than on the car screen.
 */
@Composable
internal fun BrowserSettingsRows(webPageRow: @Composable () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AdBlockManager.ensureInitialized(context) }
    val bookmarks by BrowserSettings.bookmarks.collectAsState()
    val engine by BrowserSettings.searchEngine.collectAsState()
    val adBlock by BrowserSettings.adBlock.collectAsState()
    val youTube by BrowserSettings.youTubeAdBlock.collectAsState()
    val lists by BrowserSettings.filterLists.collectAsState()
    var addingBookmark by remember { mutableStateOf(false) }
    var addingList by remember { mutableStateOf(false) }

    webPageRow()
    SettingsDivider()
    SearchEngineRow(engine)

    SettingsDivider()
    Text(
        stringResource(R.string.browser_bookmarks),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
    bookmarks.forEach { bookmark ->
        SettingsRow(
            bookmark.title,
            subtitle = bookmark.host,
            trailing = {
                IconButton({ BrowserSettings.removeBookmark(bookmark.url) }) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.web_remove_bookmark, bookmark.title))
                }
            },
        )
    }
    SettingsRow(
        stringResource(R.string.browser_add_bookmark),
        Modifier.clickable { addingBookmark = true },
        subtitle = stringResource(R.string.browser_bookmarks_note),
        icon = Icons.Filled.Add,
    )

    SettingsDivider()
    SwitchRow(stringResource(R.string.browser_adblock), adBlock, BrowserSettings::setAdBlock, stringResource(R.string.browser_adblock_note))
    if (adBlock) {
        SettingsDivider()
        SwitchRow(stringResource(R.string.browser_youtube), youTube, BrowserSettings::setYouTubeAdBlock, stringResource(R.string.browser_youtube_note))
        SettingsDivider()
        Text(
            stringResource(R.string.browser_filter_lists),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        lists.forEach { list ->
            SettingsRow(
                list.title,
                Modifier.clickable {
                    BrowserSettings.setListEnabled(list.id, !list.enabled)
                    AdBlockManager.reload(context)
                },
                subtitle = if (list.builtIn) null else list.url,
                leading = { Checkbox(list.enabled, onCheckedChange = null) },
                trailing = if (list.builtIn) null else {
                    {
                        IconButton({
                            AdBlockManager.forget(context, list)
                            BrowserSettings.removeCustomList(list.id)
                            AdBlockManager.reload(context)
                        }) { Icon(Icons.Filled.Delete, stringResource(R.string.browser_remove)) }
                    }
                },
            )
        }
        SettingsRow(stringResource(R.string.browser_add_list), Modifier.clickable { addingList = true }, icon = Icons.Filled.Add)
        UpdateListsRow()
    }

    if (addingBookmark) {
        BookmarkDialog(onDismiss = { addingBookmark = false }) { title, url ->
            BrowserSettings.addBookmark(title, url).also { if (it) addingBookmark = false }
        }
    }
    if (addingList) {
        AddressDialog(
            title = stringResource(R.string.browser_add_list),
            label = stringResource(R.string.browser_list_address),
            note = stringResource(R.string.browser_list_note),
            onDismiss = { addingList = false },
        ) { url ->
            BrowserSettings.addCustomList(url).also { if (it) addingList = false }
        }
    }
}

@Composable
private fun SearchEngineRow(engine: SearchEngine) {
    var menu by remember { mutableStateOf(false) }
    SettingsRow(
        stringResource(R.string.browser_search_engine),
        Modifier.clickable { menu = true },
        trailing = {
            Box {
                Text(engine.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    SearchEngine.entries.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice.label, fontWeight = if (choice == engine) FontWeight.Bold else FontWeight.Normal) },
                            onClick = {
                                BrowserSettings.setSearchEngine(choice)
                                menu = false
                            },
                        )
                    }
                }
            }
        },
    )
}

/** How many rules are loaded and when the lists last came down, with a button to fetch them now. */
@Composable
private fun UpdateListsRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped after an update, so the counts and the date are read again.
    var refreshed by remember { mutableIntStateOf(0) }
    val rules = remember(refreshed, updating) { AdBlockManager.networkRuleCount + AdBlockManager.cosmeticRuleCount }
    val updatedAt = remember(refreshed, updating) { AdBlockManager.lastUpdatedAt(context) }
    val status = when {
        updating -> stringResource(R.string.browser_updating)
        error != null -> stringResource(R.string.browser_update_failed, error.orEmpty())
        updatedAt > 0 -> stringResource(R.string.browser_lists_status, rules, DateUtils.getRelativeTimeSpanString(updatedAt).toString())
        else -> stringResource(R.string.browser_lists_never, rules)
    }
    SettingsRow(
        stringResource(R.string.browser_update_lists),
        Modifier.clickable(enabled = !updating) {
            updating = true
            error = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { AdBlockManager.refreshLists(context) }
                error = (result as? AdBlockManager.RefreshResult.Failure)?.message
                updating = false
                refreshed++
            }
        },
        subtitle = status,
        icon = Icons.Filled.Refresh,
    )
}

@Composable
private fun SwitchRow(title: String, on: Boolean, onChange: (Boolean) -> Unit, note: String) {
    SettingsRow(title, Modifier.clickable { onChange(!on) }, subtitle = note, trailing = { Switch(checked = on, onCheckedChange = null) })
}

@Composable
private fun BookmarkDialog(onDismiss: () -> Unit, onSave: (String, String) -> Boolean) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_add_bookmark)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.browser_bookmark_name)) }, singleLine = true)
                OutlinedTextField(
                    url, { url = it; invalid = false },
                    label = { Text(stringResource(R.string.browser_bookmark_address)) },
                    singleLine = true,
                    isError = invalid,
                    supportingText = if (invalid) ({ Text(stringResource(R.string.browser_shared_not_url)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = { TextButton({ invalid = !onSave(title, withScheme(url)) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AddressDialog(title: String, label: String, note: String, onDismiss: () -> Unit, onSave: (String) -> Boolean) {
    var url by remember { mutableStateOf("https://") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(note)
                OutlinedTextField(
                    url, { url = it; invalid = false },
                    label = { Text(label) },
                    singleLine = true,
                    isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = { TextButton({ invalid = !onSave(withScheme(url)) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "example.com" is what someone typing an address on a phone meant. */
private fun withScheme(text: String): String {
    val t = text.trim()
    return if ("://" in t || t.isEmpty()) t else "https://$t"
}
