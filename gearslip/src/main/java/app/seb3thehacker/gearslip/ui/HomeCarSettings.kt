package app.seb3thehacker.gearslip.ui

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.car.theme.DarkStyle
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.car.AppTheme
import app.seb3thehacker.gearslip.car.CarApps
import app.seb3thehacker.gearslip.car.LauncherMode
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.car.LauncherCache
import app.seb3thehacker.gearslip.car.NavBarMode
import app.seb3thehacker.gearslip.car.NightMode
import app.seb3thehacker.gearslip.car.Units
import app.seb3thehacker.gearslip.car.theme.GsThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The car's setting groups, each opening its own screen from the phone's home screen. */
enum class CarGroup(@param:StringRes val title: Int) {
    APPS(R.string.crumb_apps),
    SCREEN(R.string.car_group_screen),
    NAV_BAR(R.string.car_group_nav_bar),
    SOUND(R.string.car_group_sound),
    PLACES(R.string.places_title),
    WARNINGS(R.string.car_group_warnings),
    MORE(R.string.car_group_more),
}

@Composable
private fun sizeChoices() = listOf(1f to stringResource(R.string.choice_size_normal), 1.25f to stringResource(R.string.choice_size_large), 1.5f to stringResource(R.string.choice_size_xlarge))
@Composable
private fun themeChoices() = listOf(AppTheme.PHONE to stringResource(R.string.choice_theme_phone), AppTheme.LIGHT to stringResource(R.string.choice_theme_light), AppTheme.DARK to stringResource(R.string.choice_theme_dark))
@Composable
private fun darkChoices() = listOf(
    DarkStyle.BLACK to stringResource(R.string.choice_dark_black),
    DarkStyle.BLACK_COLOUR to stringResource(R.string.choice_dark_black_colour),
    DarkStyle.DEEP_COLOUR to stringResource(R.string.choice_dark_deep_colour),
    DarkStyle.GREY to stringResource(R.string.choice_dark_grey),
)
@Composable
private fun nightChoices() = listOf(NightMode.AUTO to stringResource(R.string.choice_auto), NightMode.DAY to stringResource(R.string.choice_night_day), NightMode.NIGHT to stringResource(R.string.choice_night_night))
@Composable
private fun unitChoices() = listOf(Units.AUTO to stringResource(R.string.choice_auto), Units.IMPERIAL to stringResource(R.string.choice_units_imperial), Units.METRIC to stringResource(R.string.choice_units_metric))
@Composable
private fun navBarChoices() = listOf(
    NavBarMode.SHOW to stringResource(R.string.choice_nav_show),
    NavBarMode.HIDE_NAVIGATING to stringResource(R.string.choice_nav_hide_navigating),
    NavBarMode.HIDE to stringResource(R.string.choice_nav_hide),
)
@Composable
private fun openChoices(): List<Pair<String, String>> {
    val context = LocalContext.current
    val apps = remember(context) { CarApps.shown(LauncherMode.enabled(context), LauncherMode.canCall(context)) }
    return listOf(CarSettings.HOME to stringResource(R.string.choice_open_home)) + apps.map { it.id to stringResource(it.labelRes) }
}

/**
 * The car's settings on the phone's home screen: one card per group with a summary of where it
 * stands, each opening a screen of its own. They are the same settings as the car's own Settings
 * app, so a change in either shows in both; the phone is just the easier place when parked.
 */
@Composable
internal fun HomeCarSettings(onOpen: (CarGroup) -> Unit, modifier: Modifier = Modifier) {
    val scale by CarSettings.scale.collectAsState()
    val open by CarSettings.openOnConnect.collectAsState()
    val navBar by CarSettings.navBarMode.collectAsState()
    val pipeAudio by CarSettings.pipeAudio.collectAsState()
    val speakDirections by CarSettings.speakDirections.collectAsState()
    val bluetoothWarning by CarSettings.bluetoothWarning.collectAsState()
    val locationWarning by CarSettings.locationWarning.collectAsState()
    val playerLeft by CarSettings.playerLeft.collectAsState()
    val theme by CarSettings.appTheme.collectAsState()
    val experimental by CarSettings.experimentalFeaturesEnabled.collectAsState()

    val opens = openChoices()
    val sizes = sizeChoices()
    val themes = themeChoices()
    val navBars = navBarChoices()
    val context = androidx.compose.ui.platform.LocalContext.current
    fun str(id: Int) = context.getString(id)
    // Read again each time Home shows, so a place saved a moment ago is listed.
    val places = androidx.compose.runtime.remember { app.seb3thehacker.gearslip.car.SavedPlaces.all(context) }
    fun summary(group: CarGroup) = when (group) {
        CarGroup.APPS -> listOf(str(R.string.car_apps_sub))
        CarGroup.SCREEN -> listOf(opens.label(open), sizes.label(scale), themes.label(theme))
        CarGroup.NAV_BAR -> listOf(navBars.label(navBar), str(if (playerLeft) R.string.summary_player_left else R.string.summary_player_right))
        CarGroup.SOUND -> listOf(
            str(if (pipeAudio) R.string.summary_usb_audio else R.string.summary_bluetooth_audio),
            str(if (speakDirections) R.string.summary_directions_on else R.string.summary_directions_off),
        )
        CarGroup.PLACES -> places.mapIndexedNotNull { i, place ->
            when {
                place.address.isBlank() -> null
                i == app.seb3thehacker.gearslip.car.SavedPlaces.HOME -> str(R.string.places_home)
                i == app.seb3thehacker.gearslip.car.SavedPlaces.WORK -> str(R.string.places_work)
                else -> place.label.ifBlank { context.getString(R.string.places_other, i - app.seb3thehacker.gearslip.car.SavedPlaces.WORK) }
            }
        }.ifEmpty { listOf(str(R.string.places_sub)) }
        CarGroup.WARNINGS -> listOf(
            str(if (bluetoothWarning && !pipeAudio) R.string.summary_bluetooth_on else R.string.summary_bluetooth_off),
            str(if (locationWarning) R.string.summary_location_on else R.string.summary_location_off),
        )
        CarGroup.MORE -> listOf(
            str(R.string.web_page),
            str(if (experimental) R.string.summary_experimental_shown else R.string.summary_experimental_hidden),
        )
    }.filter { it.isNotEmpty() }.joinToString(" · ")

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.home_car),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 4.dp),
        )
        SettingsCard {
            CarGroup.entries.forEachIndexed { i, group ->
                if (i > 0) SettingsDivider()
                SettingsRow(
                    stringResource(group.title),
                    Modifier.clickable { onOpen(group) },
                    subtitle = summary(group),
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                )
            }
        }
    }
}

/** One group of the car's settings, on a screen of its own. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun CarGroupScreen(group: CarGroup, onBack: () -> Unit) {
    androidx.compose.material3.Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(group.title)) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsCard { GroupRows(group) }
            // The bigger pieces each keep their own card: the car screen's size and the preview's,
            // and the two voice models with their downloads.
            when (group) {
                CarGroup.SCREEN -> CarScreenSection()
                CarGroup.SOUND -> {
                    CarAudioSection()
                    VoiceModelSection()
                }
                else -> Unit
            }
            // The car's own Settings has no list of apps.
            if (group != CarGroup.APPS) Text(
                stringResource(R.string.car_group_same),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun GroupRows(group: CarGroup) {
    val scale by CarSettings.scale.collectAsState()
    val open by CarSettings.openOnConnect.collectAsState()
    val night by CarSettings.nightMode.collectAsState()
    val units by CarSettings.units.collectAsState()
    val navBar by CarSettings.navBarMode.collectAsState()
    val pipeAudio by CarSettings.pipeAudio.collectAsState()
    val directionsOverMusic by CarSettings.directionsOverMusic.collectAsState()
    val speakDirections by CarSettings.speakDirections.collectAsState()
    val systemSpeech by CarSettings.systemSpeech.collectAsState()
    val bluetoothWarning by CarSettings.bluetoothWarning.collectAsState()
    val locationWarning by CarSettings.locationWarning.collectAsState()
    val autoplay by CarSettings.autoplay.collectAsState()
    val playerTitle by CarSettings.playerTitle.collectAsState()
    val playerLeft by CarSettings.playerLeft.collectAsState()
    val moreLyrics by CarSettings.moreLyrics.collectAsState()
    val theme by CarSettings.appTheme.collectAsState()
    val uiTheme by CarSettings.uiTheme.collectAsState()
    val darkStyle by CarSettings.darkStyle.collectAsState()
    val experimental by CarSettings.experimentalFeaturesEnabled.collectAsState()
    val carSensors by CarSettings.carSensorsEnabled.collectAsState()
    val adaptiveVideo by CarSettings.adaptiveVideoEnabled.collectAsState()
    val clusterVideo by CarSettings.clusterVideoEnabled.collectAsState()

    when (group) {
        CarGroup.APPS -> AppRows()
        CarGroup.SCREEN -> {
            Choice(stringResource(R.string.car_open_on_connect), openChoices(), open, CarSettings::setOpenOnConnect)
            SettingsDivider()
            Choice(stringResource(R.string.car_size), sizeChoices(), scale, CarSettings::setScale)
            SettingsDivider()
            Choice(stringResource(R.string.car_app_theme), themeChoices(), theme, CarSettings::setAppTheme)
            SettingsDivider()
            if (theme != AppTheme.LIGHT) {
                Choice(stringResource(R.string.car_dark_mode), darkChoices(), darkStyle, CarSettings::setDarkStyle)
                SettingsDivider()
            }
            Choice(stringResource(R.string.car_buttons), GsThemes.builtIn.map { it.id to it.name }, uiTheme, CarSettings::setUiTheme)
            SettingsDivider()
            Choice(stringResource(R.string.car_map_day_night), nightChoices(), night, CarSettings::setNightMode)
            SettingsDivider()
            Choice(stringResource(R.string.car_units), unitChoices(), units, CarSettings::setUnits)
        }
        CarGroup.NAV_BAR -> {
            Choice(stringResource(R.string.car_nav_bar), navBarChoices(), navBar, CarSettings::setNavBarMode)
            SettingsDivider()
            Choice(stringResource(R.string.car_player_nav_bar), listOf(false to stringResource(R.string.car_skip_buttons), true to stringResource(R.string.car_song_title)), playerTitle, CarSettings::setPlayerTitle)
            SettingsDivider()
            Choice(stringResource(R.string.car_player_beside_map), listOf(false to stringResource(R.string.car_right), true to stringResource(R.string.car_left)), playerLeft, CarSettings::setPlayerLeft)
            SettingsDivider()
            Toggle(stringResource(R.string.car_autoplay), autoplay, CarSettings::setAutoplay)
            SettingsDivider()
            Toggle(
                stringResource(R.string.car_more_lyrics),
                moreLyrics,
                CarSettings::setMoreLyrics,
                note = stringResource(R.string.car_more_lyrics_note),
            )
        }
        CarGroup.SOUND -> {
            Toggle(
                stringResource(R.string.car_spoken_directions),
                speakDirections,
                CarSettings::setSpeakDirections,
                note = stringResource(R.string.car_spoken_directions_note),
            )
            if (speakDirections) {
                SettingsDivider()
                Toggle(
                    stringResource(R.string.car_directions_over_music),
                    directionsOverMusic,
                    CarSettings::setDirectionsOverMusic,
                    note = stringResource(R.string.car_directions_over_music_note),
                )
            }
            SettingsDivider()
            Toggle(
                stringResource(R.string.car_phone_speech),
                systemSpeech,
                CarSettings::setSystemSpeech,
                note = stringResource(R.string.car_phone_speech_note),
            )
        }
        CarGroup.PLACES -> Unit
        CarGroup.WARNINGS -> {
            if (!pipeAudio) {
                Toggle(stringResource(R.string.car_warn_bluetooth), bluetoothWarning, CarSettings::setBluetoothWarning)
                SettingsDivider()
            }
            Toggle(stringResource(R.string.car_warn_location), locationWarning, CarSettings::setLocationWarning)
        }
        CarGroup.MORE -> {
            WebPageRow()
            SettingsDivider()
            Toggle(stringResource(R.string.car_experimental), experimental, CarSettings::setExperimentalFeaturesEnabled)
            if (experimental) {
                SettingsDivider()
                Toggle(
                    stringResource(R.string.car_vehicle_data),
                    carSensors,
                    CarSettings::setCarSensorsEnabled,
                    note = stringResource(R.string.car_vehicle_data_note),
                )
                SettingsDivider()
                Toggle(
                    stringResource(R.string.car_adaptive_video),
                    adaptiveVideo,
                    CarSettings::setAdaptiveVideoEnabled,
                    note = stringResource(R.string.car_adaptive_video_note),
                )
                SettingsDivider()
                Toggle(
                    stringResource(R.string.car_cluster_video),
                    clusterVideo,
                    CarSettings::setClusterVideoEnabled,
                    note = stringResource(R.string.car_cluster_video_note),
                )
            }
        }
    }
}

/** One row per tile the installed apps put in the car's drawer, ticked while it shows there. */
@Composable
private fun AppRows() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val apps by LauncherCache.state.collectAsState()
    val hidden by CarSettings.hiddenApps.collectAsState()
    // Its own scan, so an app installed since the last drive is here to choose.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { LauncherCache.load(context, force = true) } }
    val rows = apps.orEmpty().mapNotNull { entry -> entry.componentId?.let { it to entry } }
    if (rows.isEmpty()) SettingsRow(stringResource(if (apps == null) R.string.car_apps_finding else R.string.car_apps_none))
    rows.forEachIndexed { i, (id, entry) ->
        val shown = id !in hidden
        if (i > 0) SettingsDivider()
        SettingsRow(
            entry.label,
            Modifier.toggleable(shown, role = Role.Checkbox) { CarSettings.setAppHidden(id, !it) },
            leading = { entry.icon?.let { Image(it, contentDescription = null, Modifier.size(40.dp)) } },
            trailing = { Checkbox(checked = shown, onCheckedChange = null) },
        )
    }
}

private fun <T> List<Pair<T, String>>.label(value: T): String = firstOrNull { it.first == value }?.second.orEmpty()

/** A setting with a few named choices: its current one shown, the rest a tap away. */
@Composable
private fun <T> Choice(title: String, choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    SettingsRow(
        title,
        Modifier.clickable { menu = true },
        trailing = {
            // The menu hangs from the value it changes, at the row's end.
            Box {
                Text(choices.label(selected), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    choices.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label, fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal) },
                            onClick = {
                                onSelect(value)
                                menu = false
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun Toggle(title: String, on: Boolean, onChange: (Boolean) -> Unit, note: String? = null) {
    SettingsRow(
        title,
        Modifier.clickable { onChange(!on) },
        subtitle = note,
        trailing = { Switch(checked = on, onCheckedChange = null) },
    )
}

/** The page the car's Browser app opens, typed here instead of on the car's keyboard. */
@Composable
private fun WebPageRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var url by remember { mutableStateOf(app.seb3thehacker.gearslip.AppSettings.startupUrl(context)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        androidx.compose.material3.OutlinedTextField(
            url,
            {
                url = it
                app.seb3thehacker.gearslip.AppSettings.setStartupUrl(context, it)
            },
            label = { Text(stringResource(R.string.web_page)) },
            placeholder = { Text("DuckDuckGo") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.web_page_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** How music reaches the car: by Bluetooth as usual, or through Gearslip over USB. Same choice as setup. */
@Composable
internal fun CarAudioSection() {
    val usb by CarSettings.pipeAudio.collectAsState()
    SettingsSection(
        stringResource(R.string.car_audio),
        footer = stringResource(if (usb) R.string.car_audio_usb_footer else R.string.car_audio_bt_footer),
    ) {
        SettingsRow(
            stringResource(R.string.car_audio_bt),
            Modifier.selectable(!usb, role = androidx.compose.ui.semantics.Role.RadioButton) { CarSettings.setPipeAudio(false) },
            subtitle = stringResource(R.string.car_audio_bt_sub),
            trailing = { androidx.compose.material3.RadioButton(selected = !usb, onClick = null) },
        )
        SettingsDivider()
        SettingsRow(
            stringResource(R.string.guide_audio_usb),
            Modifier.selectable(usb, role = androidx.compose.ui.semantics.Role.RadioButton) { CarSettings.setPipeAudio(true) },
            subtitle = stringResource(R.string.car_audio_usb_sub),
            trailing = { androidx.compose.material3.RadioButton(selected = usb, onClick = null) },
        )
    }
}
