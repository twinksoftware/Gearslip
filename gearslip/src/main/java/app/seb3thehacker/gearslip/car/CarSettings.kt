package app.seb3thehacker.gearslip.car

import app.seb3thehacker.gearslip.car.theme.DarkStyle
import android.content.Context
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.audio.CarAudio
import app.seb3thehacker.gearslip.car.theme.GsTheme
import app.seb3thehacker.gearslip.car.theme.GsThemes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class NightMode { AUTO, DAY, NIGHT }

/** Distances and temperatures. [AUTO] goes by the phone's region. */
enum class Units { AUTO, IMPERIAL, METRIC }

/** Where a new message shows: [NAV_BAR] turns the clock button into it, [CARD] floats a card top right. */

/** The car UI's own colours. Separate from [NightMode], which is about the light outside (the map). */
enum class AppTheme { PHONE, LIGHT, DARK }

/** A screen's width and height in pixels, as someone typed it: "1280x480". */
data class ScreenSize(val width: Int, val height: Int) {
    override fun toString() = "${width}x$height"

    companion object {
        fun parse(text: String?): ScreenSize? {
            val parts = text?.lowercase()?.split('x', '×', ' ')?.filter { it.isNotBlank() } ?: return null
            if (parts.size != 2) return null
            val w = parts[0].trim().toIntOrNull() ?: return null
            val h = parts[1].trim().toIntOrNull() ?: return null
            return if (w in 100..8000 && h in 100..8000) ScreenSize(w, h) else null
        }
    }
}

/**
 * How a head unit puts our frame on a screen of another shape. [CROP] cuts the edges off, as
 * a car's own margins do; [SQUASH] shrinks the whole frame to fit, stretching everything.
 */
enum class ScreenFit { CROP, SQUASH }

/** What size the phone's car preview draws: the last car's, this device's whole screen, or one typed in. */
enum class PreviewSize { LAST_CAR, FILL, CUSTOM }

/** When the nav bar gets out of the way: never, during a route, or whenever the map is up. */
enum class NavBarMode { SHOW, HIDE_NAVIGATING, HIDE }

/**
 * Settings changed from the car screen itself. Held as flows so the car UI reacts the moment a
 * button is tapped, and persisted through [AppSettings] so they survive the next connection.
 */
object CarSettings {

    private const val KEY_SCALE = "car_scale"
    private const val KEY_DENSITY = "car_density"
    private const val KEY_OPEN = "car_open_on_connect"
    private const val KEY_NIGHT = "car_night_mode"
    private const val KEY_PIPE_AUDIO = "car_pipe_audio"
    private const val KEY_AUDIO_CARS = "car_audio_through_gearslip_cars"
private const val KEY_SPEAK_DIRECTIONS = "car_speak_directions"
private const val KEY_DIRECTIONS_OVER_MUSIC = "car_directions_over_music"
private const val KEY_BLUETOOTH_WARNING = "car_bluetooth_warning"
private const val KEY_LOCATION_WARNING = "car_location_warning"
    private const val KEY_AUDIO_CHOICE_ASKED = "car_audio_choice_asked"
    private const val KEY_LAST_NAV = "car_last_nav"
    private const val KEY_LAST_MEDIA = "car_last_media"
    private const val KEY_AUTOPLAY = "car_autoplay"
    private const val KEY_PLAYER_TITLE = "car_player_title"
    private const val KEY_PLAYER_LEFT = "car_player_left"
    private const val KEY_THEME = "car_app_theme"
    private const val KEY_UI_THEME = "car_ui_theme"
    private const val KEY_DARK_STYLE = "car_dark_style"
    private const val KEY_PINNED = "car_pinned_apps"
    private const val KEY_APP_ORDER = "car_app_order"
    private const val KEY_HIDDEN_APPS = "car_hidden_apps"
    private const val KEY_EXPERIMENTAL_FEATURES = "car_experimental_features"
    private const val KEY_SYSTEM_SPEECH = "car_system_speech"
    private const val KEY_CAR_SENSORS = "car_sensors_enabled"
    private const val KEY_ADAPTIVE_VIDEO = "adaptive_video_enabled"
private const val KEY_CLUSTER_VIDEO = "cluster_video_enabled"
    private const val KEY_DEBUG_BADGE = "debug_badge"
    private const val KEY_SEEN_SAFETY_WARNING = "car_seen_safety_warning"
    private const val KEY_UNITS = "car_units"
    private const val KEY_NAV_BAR = "car_nav_bar"
    private const val KEY_SCREEN_SIZE = "car_screen_size"
    private const val KEY_SCREEN_FIT = "car_screen_fit"
    private const val KEY_PREVIEW_SIZE = "preview_size"
    private const val KEY_PREVIEW_CUSTOM = "preview_custom_size"
    private const val KEY_WHATS_NEW = "car_whats_new_after_updates"
    private const val KEY_WHATS_NEW_SEEN = "car_whats_new_seen_code"

    /** How many shortcuts the nav bar makes room for; a driver reaching for one needs it to
     * still be on the bar, not off the edge of a long list. */
    const val MAX_PINNED = 3

    /** Id of the app to open on connect; [HOME] opens the launcher. */
    const val HOME = "home"

    private lateinit var app: Context

    private val scaleFlow = MutableStateFlow(1f)
    private val carDensityFlow = MutableStateFlow(DENSITY_FROM_CAR)
    private val openFlow = MutableStateFlow(HOME)
    private val nightFlow = MutableStateFlow(NightMode.AUTO)
    private val pipeAudioFlow = MutableStateFlow(true)
    private val speakDirectionsFlow = MutableStateFlow(true)
    private val directionsOverMusicFlow = MutableStateFlow(false)
    private val bluetoothWarningFlow = MutableStateFlow(true)
    private val locationWarningFlow = MutableStateFlow(true)
    private val lastNavFlow = MutableStateFlow<String?>(null)
    private val lastMediaFlow = MutableStateFlow<String?>(null)
    private val autoplayFlow = MutableStateFlow(false)
    private val playerTitleFlow = MutableStateFlow(false)
    private val playerLeftFlow = MutableStateFlow(false)
    private val themeFlow = MutableStateFlow(AppTheme.PHONE)
    val appTheme: StateFlow<AppTheme> = themeFlow
    private val darkStyleFlow = MutableStateFlow(DarkStyle.BLACK)

    /** How dark the car's dark mode goes; black until the driver picks another. */
    val darkStyle: StateFlow<DarkStyle> = darkStyleFlow
    private val uiThemeFlow = MutableStateFlow(GsThemes.default.id)

    /** Id of the [GsTheme] controls are drawn in; an unknown id falls back to the default. */
    val uiTheme: StateFlow<String> = uiThemeFlow
    private val pinnedFlow = MutableStateFlow<Set<String>>(emptySet())
    private val appOrderFlow = MutableStateFlow<List<String>>(emptyList())
    private val hiddenAppsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val experimentalFeaturesFlow = MutableStateFlow(false)
    private val systemSpeechFlow = MutableStateFlow(false)
    private val carSensorsFlow = MutableStateFlow(false)
    private val adaptiveVideoFlow = MutableStateFlow(false)
    private val clusterVideoFlow = MutableStateFlow(false)
    private val debugBadgeFlow = MutableStateFlow(true)
    private val seenSafetyWarningFlow = MutableStateFlow(false)
    private val unitsFlow = MutableStateFlow(Units.AUTO)

    val units: StateFlow<Units> = unitsFlow

    private val screenSizeFlow = MutableStateFlow<ScreenSize?>(null)
    private val screenFitFlow = MutableStateFlow(ScreenFit.CROP)
    private val previewSizeFlow = MutableStateFlow(PreviewSize.LAST_CAR)
    private val previewCustomFlow = MutableStateFlow<ScreenSize?>(null)

    /** The car screen's real size, when the head unit gets it wrong; null trusts the head unit. */
    val screenSize: StateFlow<ScreenSize?> = screenSizeFlow
    val screenFit: StateFlow<ScreenFit> = screenFitFlow
    val previewSize: StateFlow<PreviewSize> = previewSizeFlow
    val previewCustom: StateFlow<ScreenSize?> = previewCustomFlow

    /** Takes effect the next time the car connects: the video size is agreed at the start. */
    fun setScreenSize(size: ScreenSize?) {
        screenSizeFlow.value = size
        AppSettings.putString(app, KEY_SCREEN_SIZE, size?.toString().orEmpty())
    }

    fun setScreenFit(fit: ScreenFit) {
        screenFitFlow.value = fit
        AppSettings.putString(app, KEY_SCREEN_FIT, fit.name)
    }

    fun setPreviewSize(size: PreviewSize, custom: ScreenSize? = previewCustomFlow.value) {
        previewSizeFlow.value = size
        previewCustomFlow.value = custom
        AppSettings.putString(app, KEY_PREVIEW_SIZE, size.name)
        AppSettings.putString(app, KEY_PREVIEW_CUSTOM, custom?.toString().orEmpty())
    }

    private val navBarFlow = MutableStateFlow(NavBarMode.SHOW)
    val navBarMode: StateFlow<NavBarMode> = navBarFlow

    fun setNavBarMode(mode: NavBarMode) {
        navBarFlow.value = mode
        AppSettings.putString(app, KEY_NAV_BAR, mode.name)
    }


    private val whatsNewFlow = MutableStateFlow(true)
    private val whatsNewSeenFlow = MutableStateFlow(0)
    private val whatsNewOpenFlow = MutableStateFlow(false)

    /** Whether the car screen shows what's new once after each update. */
    val whatsNewAfterUpdates: StateFlow<Boolean> = whatsNewFlow

    /** Version code whose notes the car screen last showed. */
    val whatsNewSeen: StateFlow<Int> = whatsNewSeenFlow

    /** Opened by hand from Settings, whatever the two above say. Not saved. */
    val whatsNewOpen: StateFlow<Boolean> = whatsNewOpenFlow

    fun setWhatsNewAfterUpdates(on: Boolean) {
        whatsNewFlow.value = on
        AppSettings.putString(app, KEY_WHATS_NEW, on.toString())
    }

    fun openWhatsNew() { whatsNewOpenFlow.value = true }

    /** Closes the notes and marks [versionCode]'s as read. */
    fun closeWhatsNew(versionCode: Int) {
        whatsNewOpenFlow.value = false
        whatsNewSeenFlow.value = versionCode
        AppSettings.putString(app, KEY_WHATS_NEW_SEEN, versionCode.toString())
    }



    /** Whether to show miles, feet and °F; [Units.AUTO] follows the countries that still use them. */
    fun imperial(): Boolean = when (unitsFlow.value) {
        Units.IMPERIAL -> true
        Units.METRIC -> false
        Units.AUTO -> java.util.Locale.getDefault().country in setOf("US", "LR", "MM")
    }

    fun setUnits(units: Units) {
        unitsFlow.value = units
        AppSettings.putString(app, KEY_UNITS, units.name)
        Weather.refresh(app, force = true)
    }

    /** Flattened component names of the apps long-pressed onto the nav bar as shortcuts. */
    val pinnedApps: StateFlow<Set<String>> = pinnedFlow

    /** The launcher's order once the driver has moved a tile; empty keeps the grouped default. */
    val appOrder: StateFlow<List<String>> = appOrderFlow

    /** The tiles left out of the car's app drawer, by [Entry.componentId]. */
    val hiddenApps: StateFlow<Set<String>> = hiddenAppsFlow

    /** Flattened component names of the map and media apps used last, brought back on connect. */
    val lastNav: StateFlow<String?> = lastNavFlow
    val lastMedia: StateFlow<String?> = lastMediaFlow

    /** Whether the media app starts playing by itself when the car connects. */
    val autoplay: StateFlow<Boolean> = autoplayFlow

    /**
     * The nav bar's player keeps the song title up, with play/pause and the dock button, instead of
     * settling to the skip buttons once a new song has announced itself.
     */
    val playerTitle: StateFlow<Boolean> = playerTitleFlow

    /** The home screen's player sits left of the map instead of right of it. */
    val playerLeft: StateFlow<Boolean> = playerLeftFlow

    /** Lyrics from BetterLyrics and KuGou as well as LRCLIB; off sends song details to LRCLIB only. */

    val scale: StateFlow<Float> = scaleFlow

    /** [carDensity] when it leaves the choice to the car. */
    const val DENSITY_FROM_CAR = 0
    val DENSITIES = listOf(160, 180, 200, 220, 240)

    /** An absolute DPI for the car UI and its apps; the video's resolution doesn't change with it. */
    val carDensity: StateFlow<Int> = carDensityFlow

    /** The density to draw at, for a car or preview that would otherwise use [carDpi]. */
    fun effectiveDensity(carDpi: Int) = carDensityFlow.value.takeIf { it != DENSITY_FROM_CAR } ?: carDpi
    val openOnConnect: StateFlow<String> = openFlow
    val nightMode: StateFlow<NightMode> = nightFlow

    /**
     * Whether a media app's sound is captured and sent over the car link. On by default, since
     * sound in the car is the point of the exercise; the platform allows the capture only behind a
     * consent dialog, which [app.seb3thehacker.gearslip.audio.CarAudio] raises once the car has
     * somewhere to play it. Turned off ("Bluetooth" in the settings), the app plays through the
     * phone's normal output instead, and Android never asks.
     */
    val pipeAudio: StateFlow<Boolean> = pipeAudioFlow

    private val thisCarPipesFlow = MutableStateFlow(false)

    /**
     * The car connected now switches Bluetooth music off while Android Auto runs, so its music
     * goes through Gearslip whatever [pipeAudio] says. Learned by [BluetoothAudioCheck] and
     * remembered per car: a Skoda, a VW, an Audi and a Bolt all did this.
     */
    val thisCarPipes: StateFlow<Boolean> = thisCarPipesFlow

    /** Whether music goes through Gearslip in this car: the driver's choice, or what the car needs. */
    val pipesNow: Boolean get() = pipeAudioFlow.value || thisCarPipesFlow.value

    /** A car connected: whether it's one that needs music through Gearslip. Null for none known. */
    fun carConnected(carAddress: String?) {
        thisCarPipesFlow.value = carAddress != null && carKey(carAddress) in audioCars()
    }

    fun carDisconnected() {
        thisCarPipesFlow.value = false
    }

    /** This car turned Bluetooth music off: from now on its music goes through Gearslip. */
    fun rememberAudioCar(carAddress: String) {
        AppSettings.putString(app, KEY_AUDIO_CARS, (audioCars() + carKey(carAddress)).distinct().joinToString(","))
        thisCarPipesFlow.value = true
    }

    private fun audioCars(): List<String> =
        AppSettings.getString(app, KEY_AUDIO_CARS, "").split(",").filter { it.isNotEmpty() }

    /** Settings keep a short hash, not the car's address. */
    private fun carKey(address: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(address.uppercase().toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

    /** Gearslip reads the map app's turns aloud on the car's directions channel; see DirectionsVoice. */
    val speakDirections: StateFlow<Boolean> = speakDirectionsFlow

    /** The turns go out on the music channel instead, for cars the directions channel upsets. */
    val directionsOverMusic: StateFlow<Boolean> = directionsOverMusicFlow

    /** With Bluetooth audio, warn when the phone isn't connected to the car; see BluetoothAudioCheck. */
    val bluetoothWarning: StateFlow<Boolean> = bluetoothWarningFlow

    /** Warn on the car when the phone's location is off; see LocationCheck. */
    val locationWarning: StateFlow<Boolean> = locationWarningFlow

    /**
     * Whether the driver has already been offered Bluetooth after turning down the capture
     * dialog. Offered once only: someone who chose to keep the USB route shouldn't be nagged.
     */
    /** False until the driver picks USB or Bluetooth, in setup or in settings. */
    val pipeAudioChosen: Boolean
        get() = AppSettings.getString(app, KEY_PIPE_AUDIO, "") != ""

    val audioChoiceAsked: Boolean
        get() = AppSettings.getString(app, KEY_AUDIO_CHOICE_ASKED, "false") == "true"

    fun markAudioChoiceAsked() = AppSettings.putString(app, KEY_AUDIO_CHOICE_ASKED, "true")

    /**
     * Off by default: a driver has to opt into experimental features as a whole before any of
     * them - the voice assistant included - show up to turn on individually.
     */
    val experimentalFeaturesEnabled: StateFlow<Boolean> = experimentalFeaturesFlow

    /** Listen with the phone's own speech service instead of Gearslip's on-device model. */
    val systemSpeech: StateFlow<Boolean> = systemSpeechFlow

    /**
     * Vehicle data is experimental and off by default: the launcher's tile for it, and the
     * voice assistant's "vehicle data" command, only show up once this and
     * [experimentalFeaturesEnabled] are both on. See [app.seb3thehacker.gearslip.car.CarSensors]
     * for why the raw sensor feed it shows is worth gating - the field labels are unverified.
     */
    val carSensorsEnabled: StateFlow<Boolean> = carSensorsFlow

    /**
     * Video bitrate that backs off when the car, or the Wi-Fi to it, falls behind. Takes effect
     * only while [experimentalFeaturesEnabled] is on too; untested in a car so far.
     */
    val adaptiveVideoEnabled: StateFlow<Boolean> = adaptiveVideoFlow

    /**
     * Gearslip's turn card on a car's instrument-cluster screen, for a car that offers one as a
     * second video sink. Applies only while [experimentalFeaturesEnabled] is on too, and from the
     * next connection; untested in a car so far.
     */
    val clusterVideoEnabled: StateFlow<Boolean> = clusterVideoFlow

    /** Debug builds only: the car screen's DEBUG BUILD badge, which screenshots for the README leave out. */
    val debugBadge: StateFlow<Boolean> = debugBadgeFlow

    fun setDebugBadge(on: Boolean) {
        debugBadgeFlow.value = on
        AppSettings.putString(app, KEY_DEBUG_BADGE, on.toString())
    }

    /**
     * Whether the car screen's own "drive safely" warning has already been shown and dismissed
     * once, from this same install. Separate from the phone side's own compat/safety dialogs -
     * a driver who only ever plugs in and never opens the phone app should still see this once,
     * on the screen they are actually about to use while driving.
     */
    val hasSeenSafetyWarning: StateFlow<Boolean> = seenSafetyWarningFlow

    fun markSafetyWarningSeen() {
        seenSafetyWarningFlow.value = true
        AppSettings.putString(app, KEY_SEEN_SAFETY_WARNING, "true")
    }

    fun init(context: Context) {
        app = context.applicationContext
        scaleFlow.value = AppSettings.getString(app, KEY_SCALE, "1.0").toFloatOrNull() ?: 1f
        carDensityFlow.value = AppSettings.getString(app, KEY_DENSITY, "").toIntOrNull()?.takeIf { it in DENSITIES } ?: DENSITY_FROM_CAR
        openFlow.value = AppSettings.getString(app, KEY_OPEN, HOME)
        lastNavFlow.value = AppSettings.getString(app, KEY_LAST_NAV, "").ifEmpty { null }
        lastMediaFlow.value = AppSettings.getString(app, KEY_LAST_MEDIA, "").ifEmpty { null }
        themeFlow.value = runCatching {
            AppTheme.valueOf(AppSettings.getString(app, KEY_THEME, AppTheme.PHONE.name))
        }.getOrDefault(AppTheme.PHONE)
        darkStyleFlow.value = runCatching {
            DarkStyle.valueOf(AppSettings.getString(app, KEY_DARK_STYLE, DarkStyle.BLACK.name))
        }.getOrDefault(DarkStyle.BLACK)
        uiThemeFlow.value = AppSettings.getString(app, KEY_UI_THEME, GsThemes.default.id)
        autoplayFlow.value = AppSettings.getString(app, KEY_AUTOPLAY, "false") == "true"
        playerTitleFlow.value = AppSettings.getString(app, KEY_PLAYER_TITLE, "false") == "true"
        playerLeftFlow.value = AppSettings.getString(app, KEY_PLAYER_LEFT, "false") == "true"
        pipeAudioFlow.value = AppSettings.getString(app, KEY_PIPE_AUDIO, "true") == "true"
        // Until the driver sets it, on for USB audio and off for Bluetooth, where the map app's
        // own voice already reaches the car.
        speakDirectionsFlow.value = AppSettings.getString(app, KEY_SPEAK_DIRECTIONS, pipeAudioFlow.value.toString()) == "true"
        directionsOverMusicFlow.value = AppSettings.getString(app, KEY_DIRECTIONS_OVER_MUSIC, "false") == "true"
        bluetoothWarningFlow.value = AppSettings.getString(app, KEY_BLUETOOTH_WARNING, "true") == "true"
        locationWarningFlow.value = AppSettings.getString(app, KEY_LOCATION_WARNING, "true") == "true"
        nightFlow.value = runCatching {
            NightMode.valueOf(AppSettings.getString(app, KEY_NIGHT, NightMode.AUTO.name))
        }.getOrDefault(NightMode.AUTO)
        pinnedFlow.value = AppSettings.getString(app, KEY_PINNED, "")
            .split(",").filter { it.isNotEmpty() }.toSet()
        appOrderFlow.value = AppSettings.getString(app, KEY_APP_ORDER, "")
            .split(",").filter { it.isNotEmpty() }
        hiddenAppsFlow.value = AppSettings.getString(app, KEY_HIDDEN_APPS, "")
            .split(",").filter { it.isNotEmpty() }.toSet()
        experimentalFeaturesFlow.value = AppSettings.getString(app, KEY_EXPERIMENTAL_FEATURES, "false") == "true"
        systemSpeechFlow.value = AppSettings.getString(app, KEY_SYSTEM_SPEECH, "false") == "true"
        carSensorsFlow.value = AppSettings.getString(app, KEY_CAR_SENSORS, "false") == "true"
        adaptiveVideoFlow.value = AppSettings.getString(app, KEY_ADAPTIVE_VIDEO, "false") == "true"
        clusterVideoFlow.value = AppSettings.getString(app, KEY_CLUSTER_VIDEO, "false") == "true"
        debugBadgeFlow.value = AppSettings.getString(app, KEY_DEBUG_BADGE, "true") == "true"
        seenSafetyWarningFlow.value = AppSettings.getString(app, KEY_SEEN_SAFETY_WARNING, "false") == "true"
        unitsFlow.value = runCatching {
            Units.valueOf(AppSettings.getString(app, KEY_UNITS, Units.AUTO.name))
        }.getOrDefault(Units.AUTO)
        screenSizeFlow.value = ScreenSize.parse(AppSettings.getString(app, KEY_SCREEN_SIZE, ""))
        screenFitFlow.value = runCatching {
            ScreenFit.valueOf(AppSettings.getString(app, KEY_SCREEN_FIT, ScreenFit.CROP.name))
        }.getOrDefault(ScreenFit.CROP)
        previewSizeFlow.value = runCatching {
            PreviewSize.valueOf(AppSettings.getString(app, KEY_PREVIEW_SIZE, PreviewSize.LAST_CAR.name))
        }.getOrDefault(PreviewSize.LAST_CAR)
        previewCustomFlow.value = ScreenSize.parse(AppSettings.getString(app, KEY_PREVIEW_CUSTOM, ""))
        navBarFlow.value = runCatching {
            NavBarMode.valueOf(AppSettings.getString(app, KEY_NAV_BAR, NavBarMode.SHOW.name))
        }.getOrDefault(NavBarMode.SHOW)
        whatsNewFlow.value = AppSettings.getString(app, KEY_WHATS_NEW, "true") == "true"
        whatsNewSeenFlow.value = AppSettings.getString(app, KEY_WHATS_NEW_SEEN, "0").toIntOrNull() ?: 0
    }

    fun setScale(value: Float) {
        scaleFlow.value = value
        AppSettings.putString(app, KEY_SCALE, value.toString())
    }

    fun setCarDensity(dpi: Int) {
        carDensityFlow.value = dpi
        AppSettings.putString(app, KEY_DENSITY, dpi.toString())
    }

    fun setOpenOnConnect(id: String) {
        openFlow.value = id
        AppSettings.putString(app, KEY_OPEN, id)
    }

    fun setLastNav(component: String) {
        lastNavFlow.value = component
        AppSettings.putString(app, KEY_LAST_NAV, component)
    }

    fun setLastMedia(component: String) {
        lastMediaFlow.value = component
        AppSettings.putString(app, KEY_LAST_MEDIA, component)
    }

    fun setAppTheme(theme: AppTheme) {
        themeFlow.value = theme
        AppSettings.putString(app, KEY_THEME, theme.name)
    }

    fun setDarkStyle(style: DarkStyle) {
        darkStyleFlow.value = style
        AppSettings.putString(app, KEY_DARK_STYLE, style.name)
    }

    fun setUiTheme(id: String) {
        uiThemeFlow.value = id
        AppSettings.putString(app, KEY_UI_THEME, id)
    }

    fun setPlayerTitle(on: Boolean) {
        playerTitleFlow.value = on
        AppSettings.putString(app, KEY_PLAYER_TITLE, on.toString())
    }

    fun setPlayerLeft(on: Boolean) {
        playerLeftFlow.value = on
        AppSettings.putString(app, KEY_PLAYER_LEFT, on.toString())
    }

    fun setAutoplay(on: Boolean) {
        autoplayFlow.value = on
        AppSettings.putString(app, KEY_AUTOPLAY, on.toString())
    }

    /**
     * Takes effect at once: switching to Bluetooth mid-drive stops the capture, and switching
     * back asks for it again (or waits for the car, if none is connected).
     */
    fun setPipeAudio(on: Boolean) {
        // Saved even when unchanged, so picking the default still counts as a choice.
        AppSettings.putString(app, KEY_PIPE_AUDIO, on.toString())
        // Choosing Bluetooth by hand overrules what Gearslip learned about each car.
        if (!on) {
            AppSettings.putString(app, KEY_AUDIO_CARS, "")
            thisCarPipesFlow.value = false
        }
        if (pipeAudioFlow.value == on) return
        pipeAudioFlow.value = on
        if (AppSettings.getString(app, KEY_SPEAK_DIRECTIONS, "") == "") speakDirectionsFlow.value = on
        if (on) CarAudio.request() else CarAudio.release()
    }

    fun setSpeakDirections(on: Boolean) {
        speakDirectionsFlow.value = on
        AppSettings.putString(app, KEY_SPEAK_DIRECTIONS, on.toString())
    }

    fun setDirectionsOverMusic(on: Boolean) {
        directionsOverMusicFlow.value = on
        AppSettings.putString(app, KEY_DIRECTIONS_OVER_MUSIC, on.toString())
    }

    fun setBluetoothWarning(on: Boolean) {
        bluetoothWarningFlow.value = on
        AppSettings.putString(app, KEY_BLUETOOTH_WARNING, on.toString())
    }

    fun setLocationWarning(on: Boolean) {
        locationWarningFlow.value = on
        AppSettings.putString(app, KEY_LOCATION_WARNING, on.toString())
    }

    fun setExperimentalFeaturesEnabled(on: Boolean) {
        experimentalFeaturesFlow.value = on
        AppSettings.putString(app, KEY_EXPERIMENTAL_FEATURES, on.toString())
    }

    fun setSystemSpeech(on: Boolean) {
        systemSpeechFlow.value = on
        AppSettings.putString(app, KEY_SYSTEM_SPEECH, on.toString())
    }

    fun setAdaptiveVideoEnabled(on: Boolean) {
        adaptiveVideoFlow.value = on
        AppSettings.putString(app, KEY_ADAPTIVE_VIDEO, on.toString())
    }

    fun setClusterVideoEnabled(on: Boolean) {
        clusterVideoFlow.value = on
        AppSettings.putString(app, KEY_CLUSTER_VIDEO, on.toString())
    }

    fun setCarSensorsEnabled(on: Boolean) {
        carSensorsFlow.value = on
        AppSettings.putString(app, KEY_CAR_SENSORS, on.toString())
    }

    fun setNightMode(mode: NightMode) {
        nightFlow.value = mode
        AppSettings.putString(app, KEY_NIGHT, mode.name)
        CarEnvironment.refresh()
    }

    /** Long-pressed onto or off the nav bar. Silently does nothing past [MAX_PINNED] rather than
     * bumping the oldest pin - a driver who filled the bar on purpose shouldn't lose one by accident. */
    fun togglePin(component: String) {
        val current = pinnedFlow.value
        val next = when {
            component in current -> current - component
            current.size >= MAX_PINNED -> return
            else -> current + component
        }
        pinnedFlow.value = next
        AppSettings.putString(app, KEY_PINNED, next.joinToString(","))
    }

    fun setAppHidden(id: String, hide: Boolean) {
        val next = if (hide) hiddenAppsFlow.value + id else hiddenAppsFlow.value - id
        hiddenAppsFlow.value = next
        AppSettings.putString(app, KEY_HIDDEN_APPS, next.joinToString(","))
    }

    fun setAppOrder(ids: List<String>) {
        appOrderFlow.value = ids
        AppSettings.putString(app, KEY_APP_ORDER, ids.joinToString(","))
    }
}
