package app.seb3thehacker.gearslip.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.car.LauncherMode
import androidx.compose.runtime.withFrameNanos
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.seb3thehacker.gearslip.car.CarBluetooth
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.CertProvider
import app.seb3thehacker.gearslip.SessionStatus
import app.seb3thehacker.gearslip.notify.TestMessage
import app.seb3thehacker.gearslip.car.CarSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenRecommendedApps: () -> Unit,
    onReplayTutorial: () -> Unit,
    atUsageNotes: Boolean = false,
) {
    val scroll = rememberScrollState()
    // Built a section per frame after the first two, so the screen shows at once instead of
    // freezing while all of it composes; once built, it scrolls without composing anything new.
    var built by remember { mutableIntStateOf(2) }
    LaunchedEffect(Unit) {
        while (built < SECTIONS) {
            withFrameNanos { }
            built++
        }
    }
    fun ready(n: Int) = built > n
    // Opened from Home's "it worked" card: start at usage notes.
    var usageNotesY by remember { mutableIntStateOf(-1) }
    if (atUsageNotes) {
        LaunchedEffect(Unit) {
            val y = snapshotFlow { usageNotesY }.first { it >= 0 }
            snapshotFlow { scroll.maxValue }.first { it > 0 && it < Int.MAX_VALUE }
            scroll.scrollTo(y)
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            SettingsSection {
                val context = LocalContext.current
                SettingsRow(
                    stringResource(R.string.settings_whats_new),
                    Modifier.clickable { GearslipLinks.open(context, GearslipLinks.RELEASE_NOTES) },
                    subtitle = stringResource(R.string.settings_whats_new_sub, BuildConfig.VERSION_NAME),
                    icon = Icons.Filled.Star,
                    trailing = { Arrow() },
                )
                LanguageRow()
            }
            SettingsSection(
                stringResource(R.string.settings_support),
                footer = stringResource(R.string.settings_support_footer),
            ) { DonateRows() }
            if (ready(2)) ConnectionSection()
            if (SHOW_RECOMMENDED_APPS && ready(6)) AppsSection(onOpenRecommendedApps)
            if (ready(7)) SettingsSection(
                stringResource(R.string.settings_usage_notes),
                Modifier.onGloballyPositioned { usageNotesY = it.positionInParent().y.toInt() },
                header = usageStatsIntro(LocalContext.current),
            ) { UsageStatsRows() }
            if (ready(8)) SettingsSection(stringResource(R.string.settings_updates)) { UpdateRow() }
            if (ready(9)) CertificateSection()
            if (ready(10)) AboutSection(onReplayTutorial)
            if (BuildConfig.DEBUG && ready(11)) DebugSection()
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** How many sections Settings builds one frame at a time; see [ready]. */
private const val SECTIONS = 12

@Composable
private fun Arrow() = Icon(
    Icons.AutoMirrored.Filled.KeyboardArrowRight,
    contentDescription = null,
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * Android 13 and later choose an app's language in their own settings, from the languages
 * Gearslip declares; this opens that page, which few people find on their own (issue #51).
 */
@Composable
private fun LanguageRow() {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    // A new choice arrives as a configuration change, since the manifest keeps the activity.
    val chosen = remember(androidx.compose.ui.platform.LocalConfiguration.current) {
        context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales?.get(0)
    }
    SettingsDivider()
    SettingsRow(
        stringResource(R.string.settings_language),
        Modifier.clickable {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }
        },
        subtitle = chosen?.let { it.getDisplayName(it).replaceFirstChar { c -> c.titlecase(it) } }
            ?: stringResource(R.string.settings_language_phone),
        icon = androidx.compose.ui.graphics.vector.ImageVector.vectorResource(R.drawable.ic_language),
        trailing = { Arrow() },
    )
}

/** The setup guide replay, and where Gearslip lives outside the app. */
@Composable
private fun AboutSection(onReplayTutorial: () -> Unit) {
    SettingsSection(stringResource(R.string.settings_about)) {
        SettingsRow(
            stringResource(R.string.settings_replay),
            Modifier.clickable(onClick = onReplayTutorial),
            subtitle = stringResource(R.string.settings_replay_sub),
            icon = Icons.Filled.Refresh,
            trailing = { Arrow() },
        )
        SettingsDivider()
        AboutLinkRows()
    }
}

/** Off, a stock Android Auto app or head unit answers the USB connection instead of Gearslip. */
@Composable
private fun ConnectionSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AppSettings.gearslipEnabled(context)) }
    SettingsSection(stringResource(R.string.settings_connection)) {
        SettingsRow(
            stringResource(R.string.settings_use_in_car),
            Modifier.toggleable(enabled, role = Role.Switch) {
                enabled = it
                AppSettings.setGearslipEnabled(context, it)
            },
            subtitle = stringResource(if (enabled) R.string.settings_use_in_car_on else R.string.settings_use_in_car_off),
            icon = Icons.Filled.Settings,
            trailing = { Switch(checked = enabled, onCheckedChange = null) },
        )
        // For a head unit or a tablet on the dash: the car UI becomes the device's home screen.
        var launcher by remember { mutableStateOf(LauncherMode.enabled(context)) }
        SettingsRow(
            stringResource(R.string.settings_use_as_launcher),
            Modifier.toggleable(launcher, role = Role.Switch) {
                launcher = it
                LauncherMode.setEnabled(context, it)
                // Android doesn't switch home apps by itself; this is where the driver picks Gearslip.
                if (it) LauncherMode.openHomeChoice(context)
            },
            subtitle = stringResource(if (launcher) R.string.settings_use_as_launcher_on else R.string.settings_use_as_launcher_off),
            icon = Icons.Filled.Home,
            trailing = { Switch(checked = launcher, onCheckedChange = null) },
        )
        var address by remember { mutableStateOf(CarBluetooth.typedAddress(context)) }
        var editing by remember { mutableStateOf(false) }
        SettingsRow(
            stringResource(R.string.settings_bt_address),
            Modifier.clickable { editing = true },
            subtitle = address ?: stringResource(R.string.settings_bt_address_off),
            icon = Icons.Filled.Info,
        )
        if (editing) {
            AddressDialog(
                initial = address.orEmpty(),
                onDismiss = { editing = false },
                onSave = { typed ->
                    CarBluetooth.setTypedAddress(context, typed).also { saved ->
                        if (saved) {
                            address = CarBluetooth.typedAddress(context)
                            editing = false
                        }
                    }
                },
            )
        }
    }
}

/** The phone's Bluetooth address, typed in by hand: Android doesn't let apps read it. */
@Composable
private fun AddressDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Boolean) {
    var text by remember { mutableStateOf(initial) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_bt_address)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; invalid = false },
                singleLine = true,
                placeholder = { Text("12:34:56:78:9A:BC") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                isError = invalid,
                supportingText = { Text(stringResource(if (invalid) R.string.settings_bt_address_invalid else R.string.settings_bt_address_hint)) },
            )
        },
        confirmButton = { TextButton(onClick = { invalid = !onSave(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Tools for testing a debug build; release builds never show this section. */
@Composable
private fun DebugSection() {
    val context = LocalContext.current
    SettingsSection("Debug build") {
        SettingsRow(
            "Send a test message",
            Modifier.clickable { TestMessage.post(context) },
            subtitle = "Posts a message notification to read on the car",
            icon = Icons.AutoMirrored.Filled.Send,
        )
        SettingsDivider()
        val badge by CarSettings.debugBadge.collectAsState()
        SettingsRow(
            "Debug badge on the car screen",
            Modifier.clickable { CarSettings.setDebugBadge(!badge) },
            subtitle = "Turn it off for screenshots",
            trailing = { Switch(checked = badge, onCheckedChange = null) },
        )
    }
}

@Composable
private fun AppsSection(onOpenRecommendedApps: () -> Unit) {
    SettingsSection(stringResource(R.string.settings_apps)) {
        SettingsRow(
            stringResource(R.string.recommended_title),
            Modifier.clickable(onClick = onOpenRecommendedApps),
            icon = Icons.Filled.Star,
            trailing = { Arrow() },
        )
    }
}

@Composable
private fun CertificateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var refresh by remember { mutableIntStateOf(0) }
    // The cached summary shows at once; the read still runs, in case the cert changed since.
    val summary by produceState(CertSummary.cached, refresh) {
        value = withContext(Dispatchers.Default) { CertSummary.read(context) }
    }

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingUri = uri
    }

    // With the aasdk certificate loaded, the footer says what to do when a car rejects it.
    val certFooter = stringResource(
        if (summary?.kind == CertProvider.Kind.DOWNLOADED) R.string.cert_downloaded_footer else R.string.cert_next_connection,
    )
    SettingsSection(stringResource(R.string.settings_certificate), footer = notice ?: certFooter) {
        val s = summary
        val loaded = s != null && s.kind != CertProvider.Kind.SELF_SIGNED
        val hasDetails = s?.subject != null || s?.issuer != null
        SettingsRow(
            s?.headline ?: stringResource(R.string.cert_checking),
            if (hasDetails) Modifier.clickable { detailsOpen = !detailsOpen } else Modifier,
            subtitle = s?.validUntil?.let { stringResource(R.string.cert_valid_until, it) },
            icon = if (s == null || loaded) Icons.Filled.Lock else Icons.Filled.Warning,
            tint = if (s == null || loaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            trailing = if (hasDetails) {
                {
                    Icon(
                        if (detailsOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(if (detailsOpen) R.string.cert_hide_details else R.string.cert_show_details),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else null,
        )
        AnimatedVisibility(detailsOpen && hasDetails) {
            Column(
                Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                s?.subject?.let { Detail(stringResource(R.string.cert_subject), it) }
                s?.issuer?.let { Detail(stringResource(R.string.cert_issuer), it) }
            }
        }
        SettingsDivider()
        SettingsRow(
            stringResource(if (downloading) R.string.cert_downloading else R.string.cert_download),
            Modifier.clickable(enabled = !downloading) {
                downloading = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { CertProvider.downloadAndImport(context) } }
                    downloading = false
                    result.onSuccess {
                        notice = context.getString(R.string.cert_downloaded)
                        refresh++
                    }.onFailure {
                        notice = context.getString(R.string.cert_download_failed, it.message.toString())
                    }
                }
            },
            subtitle = stringResource(R.string.cert_download_sub),
            icon = Icons.Filled.Refresh,
        )
        SettingsDivider()
        SettingsRow(
            stringResource(R.string.cert_import),
            Modifier.clickable { picker.launch(arrayOf("*/*")) },
            subtitle = stringResource(R.string.cert_import_sub),
            icon = Icons.Filled.Add,
        )
        val removable = when (s?.kind) {
            CertProvider.Kind.IMPORTED -> stringResource(R.string.cert_remove_imported)
            CertProvider.Kind.DOWNLOADED -> stringResource(R.string.cert_remove_downloaded)
            else -> null
        }
        if (removable != null) {
            SettingsDivider()
            SettingsRow(
                removable,
                Modifier.clickable {
                    if (s?.kind == CertProvider.Kind.IMPORTED) CertProvider.removeImported(context) else CertProvider.removeDownloaded(context)
                    notice = context.getString(if (s?.kind == CertProvider.Kind.IMPORTED) R.string.cert_removed_imported else R.string.cert_removed_downloaded)
                    refresh++
                },
                icon = Icons.Filled.Delete,
                tint = MaterialTheme.colorScheme.error,
                titleColor = MaterialTheme.colorScheme.error,
            )
        }
    }

    pendingUri?.let { uri ->
        PasswordDialog(
            onDismiss = { pendingUri = null },
            onConfirm = { password, reportError ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { CertProvider.importFrom(context, uri, password) }
                    }
                    result.onSuccess {
                        pendingUri = null
                        notice = context.getString(R.string.cert_imported)
                        refresh++
                    }.onFailure {
                        reportError(context.getString(R.string.cert_import_failed))
                    }
                }
            },
        )
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onConfirm: (String, (String) -> Unit) -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cert_password_title)) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                singleLine = true,
                label = { Text(stringResource(R.string.cert_password)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = error != null,
                supportingText = { Text(error ?: stringResource(R.string.cert_password_hint)) },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) { error = it } }) { Text(stringResource(R.string.cert_import_button)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
