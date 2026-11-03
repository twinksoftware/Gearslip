package app.seb3thehacker.gearslip.car

import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R

import app.seb3thehacker.gearslip.car.theme.DarkStyle
import app.seb3thehacker.gearslip.car.theme.*
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.car.theme.GsColors
import app.seb3thehacker.gearslip.car.theme.GsIconBox
import app.seb3thehacker.gearslip.car.theme.GsThemes
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.ui.CertSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings that make sense while sitting in the car. The web page URL is edited here, with the
 * car's own on-screen keyboard ([CarKeyboard]); certificate management still needs a real
 * keyboard and file picker, so it stays on the phone's Settings screen.
 */
@Composable
fun CarSettingsScreen() {
    val scale by CarSettings.scale.collectAsState()
    val open by CarSettings.openOnConnect.collectAsState()
    val launcher by LauncherMode.active.collectAsState()
    val night by CarSettings.nightMode.collectAsState()
    val units by CarSettings.units.collectAsState()
    val navBar by CarSettings.navBarMode.collectAsState()
    val screenSize by CarSettings.screenSize.collectAsState()
    val screenFit by CarSettings.screenFit.collectAsState()
    val whatsNew by CarSettings.whatsNewAfterUpdates.collectAsState()
    val pipeAudio by CarSettings.pipeAudio.collectAsState()
    val speakDirections by CarSettings.speakDirections.collectAsState()
    val bluetoothWarning by CarSettings.bluetoothWarning.collectAsState()
    val locationWarning by CarSettings.locationWarning.collectAsState()
    val autoplay by CarSettings.autoplay.collectAsState()
    val playerTitle by CarSettings.playerTitle.collectAsState()
    val playerLeft by CarSettings.playerLeft.collectAsState()
    val moreLyrics by CarSettings.moreLyrics.collectAsState()
    val theme by CarSettings.appTheme.collectAsState()
    val uiTheme by CarSettings.uiTheme.collectAsState()
    val darkStyle by CarSettings.darkStyle.collectAsState()
    val experimentalFeatures by CarSettings.experimentalFeaturesEnabled.collectAsState()
    val carSensors by CarSettings.carSensorsEnabled.collectAsState()
    val adaptiveVideo by CarSettings.adaptiveVideoEnabled.collectAsState()
    val clusterVideo by CarSettings.clusterVideoEnabled.collectAsState()
    val systemSpeech by CarSettings.systemSpeech.collectAsState()
    val display by CarEnvironment.display.collectAsState()
    val vehicle by CarEnvironment.vehicle.collectAsState()
    val context = LocalContext.current
    val cert by produceState<CertSummary?>(null) {
        value = withContext(Dispatchers.Default) { CertSummary.read(context) }
    }

    // A rail of the groups down the left, and the chosen group's settings beside it: five short
    // lists instead of one long scroll.
    var chosen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(chosen) { listState.scrollToItem(0) }
    Row(Modifier.fillMaxSize().padding(start = 16.dp, top = 12.dp)) {
    SettingsRail(listOf(stringResource(R.string.car_group_screen), stringResource(R.string.car_group_nav_bar), stringResource(R.string.car_group_sound), stringResource(R.string.car_group_warnings), stringResource(R.string.car_group_more)), chosen) { chosen = it }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            state = listState,
            // Built as it scrolls into view rather than all at once: every row here is a set of
            // buttons, and building the whole screen made opening Settings visibly slow.
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            var section: Int
            section = 0
            if (section == chosen) item { Group(stringResource(R.string.car_group_screen)) }

            if (section == chosen) item {
                ChoiceRow(
                    stringResource(R.string.car_open_on_connect),
                    listOf(CarSettings.HOME to stringResource(R.string.choice_open_home)) + CarApps.shown(launcher, LauncherMode.canCall(context)).map { it.id to stringResource(it.labelRes) },
                    open,
                ) { CarSettings.setOpenOnConnect(it) }
            }

            if (section == chosen) item {
                ChoiceRow(
                    stringResource(R.string.car_size),
                    listOf(1f to stringResource(R.string.choice_size_normal), 1.25f to stringResource(R.string.choice_size_large), 1.5f to stringResource(R.string.choice_size_xlarge)),
                    scale,
                ) { CarSettings.setScale(it) }
            }

            if (section == chosen) item {
                ChoiceRow(
                    stringResource(R.string.car_app_theme),
                    listOf(AppTheme.PHONE to stringResource(R.string.choice_theme_phone), AppTheme.LIGHT to stringResource(R.string.choice_theme_light), AppTheme.DARK to stringResource(R.string.choice_theme_dark)),
                    theme,
                ) { CarSettings.setAppTheme(it) }
            }

            if (section == chosen && theme != AppTheme.LIGHT) item {
                ChoiceRow(
                    stringResource(R.string.car_dark_mode),
                    listOf(
                        DarkStyle.BLACK to stringResource(R.string.choice_dark_black),
                        DarkStyle.BLACK_COLOUR to stringResource(R.string.choice_dark_black_colour),
                        DarkStyle.DEEP_COLOUR to stringResource(R.string.choice_dark_deep_colour),
                        DarkStyle.GREY to stringResource(R.string.choice_dark_grey),
                    ),
                    darkStyle,
                ) { CarSettings.setDarkStyle(it) }
            }

            if (section == chosen) item {
                ChoiceRow(
                    stringResource(R.string.carset_theme),
                    GsThemes.builtIn.map { it.id to it.name },
                    uiTheme,
                ) { CarSettings.setUiTheme(it) }
            }

            if (section == chosen) item {
                ChoiceRow(
                    stringResource(R.string.car_map_day_night),
                    listOf(NightMode.AUTO to stringResource(R.string.choice_auto), NightMode.DAY to stringResource(R.string.choice_night_day), NightMode.NIGHT to stringResource(R.string.choice_night_night)),
                    night,
                ) { CarSettings.setNightMode(it) }
            }

            if (section == chosen) item {
                ChoiceRow(
                    stringResource(R.string.car_units),
                    listOf(Units.AUTO to stringResource(R.string.choice_auto), Units.IMPERIAL to stringResource(R.string.carset_imperial), Units.METRIC to stringResource(R.string.carset_metric)),
                    units,
                ) { CarSettings.setUnits(it) }
            }

            // For a head unit that reports the wrong size and stretches the picture (issue #12).
            if (section == chosen) item {
                ChoiceRow(
                    stringResource(R.string.carset_screen_size),
                    listOf<ScreenSize?>(null).plus(SCREEN_PRESETS).plus(screenSize).distinct()
                        .map { it to (it?.toString() ?: stringResource(R.string.screen_from_car)) },
                    screenSize,
                ) { CarSettings.setScreenSize(it) }
            }

            if (section == chosen && screenSize != null) item {
                ChoiceRow(
                    stringResource(R.string.carset_fit),
                    listOf(ScreenFit.CROP to stringResource(R.string.screen_crop), ScreenFit.SQUASH to stringResource(R.string.screen_squash)),
                    screenFit,
                ) { CarSettings.setScreenFit(it) }
            }

            section = 1
            if (section == chosen) item { Group(stringResource(R.string.car_group_nav_bar)) }

            if (section == chosen) item {
                ChoiceRow(
                    stringResource(R.string.car_nav_bar),
                    listOf(
                        NavBarMode.SHOW to stringResource(R.string.choice_nav_show),
                        NavBarMode.HIDE_NAVIGATING to stringResource(R.string.choice_nav_hide_navigating),
                        NavBarMode.HIDE to stringResource(R.string.choice_nav_hide),
                    ),
                    navBar,
                ) { CarSettings.setNavBarMode(it) }
            }

            if (section == chosen) item {
                ChoiceRow(
                    stringResource(R.string.car_player_nav_bar),
                    listOf(false to stringResource(R.string.car_skip_buttons), true to stringResource(R.string.car_song_title)),
                    playerTitle,
                ) { CarSettings.setPlayerTitle(it) }
            }

            if (section == chosen) item {
                ChoiceRow(
                    stringResource(R.string.car_player_beside_map),
                    listOf(false to stringResource(R.string.car_right), true to stringResource(R.string.car_left)),
                    playerLeft,
                ) { CarSettings.setPlayerLeft(it) }
            }

            if (section == chosen) item {
                ChoiceRow(
                    stringResource(R.string.car_autoplay),
                    listOf(false to stringResource(R.string.off), true to stringResource(R.string.on)),
                    autoplay,
                ) { CarSettings.setAutoplay(it) }
            }

            if (section == chosen) item {
                ChoiceRow(
                    stringResource(R.string.carset_lyrics_from),
                    listOf(false to "LRCLIB", true to stringResource(R.string.carset_more_sources)),
                    moreLyrics,
                ) { CarSettings.setMoreLyrics(it) }
            }

            section = 2
            if (section == chosen) item { Group(stringResource(R.string.car_group_sound)) }

            if (section == chosen) item {
                ChoiceRow(
                    stringResource(R.string.car_audio),
                    listOf(false to stringResource(R.string.guide_audio_bluetooth), true to stringResource(R.string.guide_audio_usb)),
                    pipeAudio,
                ) { CarSettings.setPipeAudio(it) }
            }

            if (section == chosen) item {
                Text(
                    stringResource(R.string.carset_audio_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            section = 3
            if (section == chosen) item { Group(stringResource(R.string.car_group_warnings)) }

            if (section == chosen && !pipeAudio) item {
                ChoiceRow(
                    stringResource(R.string.car_warn_bluetooth),
                    listOf(false to stringResource(R.string.off), true to stringResource(R.string.on)),
                    bluetoothWarning,
                ) { CarSettings.setBluetoothWarning(it) }
            }

            if (section == chosen) item {
                ChoiceRow(
                    stringResource(R.string.car_warn_location),
                    listOf(false to stringResource(R.string.off), true to stringResource(R.string.on)),
                    locationWarning,
                ) { CarSettings.setLocationWarning(it) }
            }

            if (section == chosen) item {
                ChoiceRow(
                    stringResource(R.string.car_spoken_directions),
                    listOf(false to stringResource(R.string.off), true to stringResource(R.string.on)),
                    speakDirections,
                ) { CarSettings.setSpeakDirections(it) }
            }

            if (section == chosen) item {
                Text(
                    stringResource(R.string.carset_directions_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (section == chosen) item {
                ExperimentalFeatureRow(
                    title = stringResource(R.string.car_phone_speech),
                    description = stringResource(R.string.carset_phone_speech_note),
                    checked = systemSpeech,
                ) { CarSettings.setSystemSpeech(it) }
            }

            section = 4
            if (section == chosen) item { Group(stringResource(R.string.car_group_more)) }

            if (section == chosen) item {
                WebPageSetting()
            }

            if (section == chosen) item {
                ScreenFit(vehicle)
            }

            if (section == chosen) item {
                ChoiceRow(
                    stringResource(R.string.car_experimental),
                    listOf(false to stringResource(R.string.carset_hide), true to stringResource(R.string.carset_show)),
                    experimentalFeatures,
                ) { CarSettings.setExperimentalFeaturesEnabled(it) }
            }

            if (section == chosen && experimentalFeatures) item {
                ExperimentalFeaturesList(carSensors, adaptiveVideo, clusterVideo)
            }

            if (section == chosen) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_connection), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Info(stringResource(R.string.carset_phone), "${Build.MANUFACTURER} ${Build.MODEL}")
                    Info(stringResource(R.string.carset_vehicle), vehicle.ifEmpty { "-" })
                    Info(stringResource(R.string.carset_video), display.ifEmpty { "-" })
                    Info(stringResource(R.string.settings_certificate), cert?.headline ?: stringResource(R.string.cert_checking))
                }
            }

            if (section == chosen) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Info(stringResource(R.string.carset_version), app.seb3thehacker.gearslip.BuildConfig.VERSION_NAME)
                    Info(stringResource(R.string.carset_build), app.seb3thehacker.gearslip.BuildConfig.VERSION_CODE.toString())
                    ExperimentalFeatureRow(
                        stringResource(R.string.carset_whats_new_after),
                        stringResource(R.string.carset_whats_new_after_sub),
                        whatsNew,
                    ) { CarSettings.setWhatsNewAfterUpdates(it) }
                    GsButton(onClick = CarSettings::openWhatsNew, tone = GsTone.Tonal, modifier = Modifier.height(56.dp)) {
                        Text(stringResource(R.string.settings_whats_new), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    // On a wide screen the name sits beside its choices, which halves how far there is to scroll.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 720.dp && options.size <= 4) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(0.3f))
                Box(Modifier.weight(0.7f)) { ChoiceButtons(options, selected, onSelect) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                ChoiceButtons(options, selected, onSelect)
            }
        }
    }
}

/** The groups, with the chosen one lit: a card down the left of Settings. */
@Composable
private fun SettingsRail(groups: List<String>, chosen: Int, onChoose: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Surface(
        Modifier.width(250.dp).padding(bottom = 12.dp).fillMaxHeight().gsPanelShadow(shape),
        shape = shape,
        color = scheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(10.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 10.dp),
            )
            groups.forEachIndexed { i, name ->
                val on = i == chosen
                GsIconBox(
                    onClick = { onChoose(i) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = if (on) GsColors(scheme.primaryContainer, scheme.onPrimaryContainer)
                    else GsColors(Color.Transparent, scheme.onSurface),
                    latched = on,
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

/** A group of settings: a heading, so a long list reads as a few short ones. */
@Composable
private fun Group(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun <T> ChoiceButtons(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    run {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { (value, label) ->
                val on = value == selected
                val scheme = MaterialTheme.colorScheme
                GsIconBox(
                    onClick = { onSelect(value) },
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                    colors = if (on) GsColors(scheme.primaryContainer, scheme.onPrimaryContainer)
                    else GsColors(scheme.surfaceContainerHigh, scheme.onSurface),
                    latched = on,
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

/**
 * Individually-switched experimental features, shown once the driver has opted into
 * experimental features as a whole. Each is its own row - a title, what it does, and a switch -
 * so the list reads fine whether there's one entry or several.
 */
@Composable
private fun ExperimentalFeaturesList(carSensors: Boolean, adaptiveVideo: Boolean, clusterVideo: Boolean) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ExperimentalFeatureRow(
            title = stringResource(R.string.car_vehicle_data),
            description = stringResource(R.string.carset_vehicle_data_note),
            checked = carSensors,
        ) { CarSettings.setCarSensorsEnabled(it) }
        ExperimentalFeatureRow(
            title = stringResource(R.string.car_adaptive_video),
            description = stringResource(R.string.carset_adaptive_note),
            checked = adaptiveVideo,
        ) { CarSettings.setAdaptiveVideoEnabled(it) }
        ExperimentalFeatureRow(
            title = stringResource(R.string.car_cluster_video),
            description = stringResource(R.string.car_cluster_video_note),
            checked = clusterVideo,
        ) { CarSettings.setClusterVideoEnabled(it) }
    }
}

@Composable
private fun ExperimentalFeatureRow(
    title: String,
    description: String,
    checked: Boolean,
    warning: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            GsSwitch(checked, onCheckedChange)
        }
    }
}

/** The URL (or raw HTML) the Browser app shows. Edited with [CarKeyboard] - no phone in hand needed. */
@Composable
private fun WebPageSetting() {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(AppSettings.startupUrl(context)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.web_page), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Surface(
            onClick = { editing = !editing },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                url.ifEmpty { stringResource(R.string.carset_web_default) },
                style = MaterialTheme.typography.bodyLarge,
                color = if (url.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        Text(
            stringResource(R.string.carset_web_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (editing) {
            CarKeyboard(
                url,
                onTextChange = {
                    url = it
                    AppSettings.setStartupUrl(context, it)
                },
                onSubmit = { editing = false },
                onDismiss = { editing = false },
            )
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Nudges how much of the frame the UI keeps clear at the top and bottom, live, so a car's
 * display can be fitted from the driver's seat. Save writes it to this vehicle's profile.
 */
@Composable
private fun ScreenFit(vehicle: String) {
    val insets by CarEnvironment.insets.collectAsState()
    val context = LocalContext.current.applicationContext
    var saved by remember { mutableStateOf<String?>(null) }
    val known = vehicle.isNotEmpty() && vehicle != "Unknown vehicle"

    fun change(new: app.seb3thehacker.gearslip.Insets) {
        saved = null
        CarEnvironment.setInsets(new)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.carset_screen_fit), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Stepper(stringResource(R.string.carset_top), insets.top) { change(insets.copy(top = (insets.top + it).coerceAtLeast(0))) }
        Stepper(stringResource(R.string.carset_bottom), insets.bottom) { change(insets.copy(bottom = (insets.bottom + it).coerceAtLeast(0))) }
        Text(
            stringResource(R.string.carset_usable_height, 480 - insets.top - insets.bottom),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GsButton(
            onClick = {
                val ok = app.seb3thehacker.gearslip.VehicleProfiles.saveInsets(context, vehicle, insets)
                saved = if (ok) context.getString(R.string.carset_saved_to, vehicle) else context.getString(R.string.carset_save_failed)
            },
            enabled = known,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(if (known) stringResource(R.string.carset_save_vehicle) else stringResource(R.string.carset_no_profile)) }
        saved?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1.1f))
        StepButton("−5", Modifier.weight(1f)) { onStep(-5) }
        StepButton("−1", Modifier.weight(1f)) { onStep(-1) }
        Text(
            stringResource(R.string.carset_px, value),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1.3f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepButton("+1", Modifier.weight(1f)) { onStep(1) }
        StepButton("+5", Modifier.weight(1f)) { onStep(5) }
    }
}

@Composable
private fun StepButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    GsIconBox(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        colors = GsColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
        shape = MaterialTheme.shapes.large,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

/** Common car screen sizes; Settings on the phone takes any other. */
private val SCREEN_PRESETS = listOf(
    ScreenSize(800, 480), ScreenSize(1024, 600), ScreenSize(1280, 480), ScreenSize(1280, 720),
    ScreenSize(1440, 540), ScreenSize(1920, 720), ScreenSize(1920, 1080),
)
