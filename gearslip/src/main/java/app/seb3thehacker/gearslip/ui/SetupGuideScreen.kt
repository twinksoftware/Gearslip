package app.seb3thehacker.gearslip.ui

import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.speech.SpeechModels

import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.LinearProgressIndicator

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import app.seb3thehacker.gearslip.BuildConfig
import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.car.CarSettings
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import app.seb3thehacker.gearslip.call.CarCalls
import app.seb3thehacker.gearslip.car.Prefetch
import app.seb3thehacker.gearslip.host.AndroidAuto
import app.seb3thehacker.gearslip.notify.GearslipNotificationListener
import app.seb3thehacker.gearslip.stats.UsageStats
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.offset

/**
 * One page of the guide. [permissions] drives a system permission dialog; [command] is an adb
 * command to copy; [actionLabel] with [onAction] is a button that does something else (opens a
 * settings screen) without by itself finishing the page. Everything is skippable: none of this
 * can be verified as done, only offered.
 */
/** The guide's colours and its one-percent-of-the-width scale, for content that sizes itself the same way. */
internal class GuideScale(val c: IntroColors, val cq: (Float) -> Dp, val cqSp: (Float) -> TextUnit)

private class GuidePage(
    val title: String,
    /** Plain text, or an [AnnotatedString] when part of it needs to stand out. */
    val explanation: CharSequence,
    /** Drawn inside the page's badge, in the badge's content colour. */
    val icon: @Composable (Color) -> Unit,
    /** Marked "Optional" above the title: everything works without it. */
    val optional: Boolean = false,
    val permissions: Array<String>? = null,
    val command: String? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    /** Drawn under [explanation], sized off the same screen-width scale as the page. */
    val content: (@Composable (GuideScale) -> Unit)? = null,
    /** Only shown when the driver picked USB audio on the car audio page. */
    val usbOnly: Boolean = false,
)

/** A plain Material icon, sized to fill the badge's icon box. */
private fun glyph(icon: ImageVector): @Composable (Color) -> Unit = { tint ->
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize())
}

/** True once every permission in [permissions] is already granted - nothing left for this page to do. */
private fun granted(context: Context, permissions: Array<String>): Boolean =
    permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/** Whether Gearslip already holds the hidden `PROJECT_MEDIA` app-op an adb command grants -
 * checked the same way the platform checks it, since there is no public permission for it. */
private fun hasProjectMediaOp(context: Context): Boolean {
    val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
    val mode = runCatching {
        ops.unsafeCheckOpNoThrow("android:project_media", android.os.Process.myUid(), context.packageName)
    }.getOrDefault(AppOpsManager.MODE_IGNORED)
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun hasCallScreeningRole(context: Context): Boolean {
    val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
    return runCatching { roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) }.getOrDefault(false)
}

/** Whether the driver has already granted Gearslip's [GearslipNotificationListener] access. */
private fun hasNotificationAccess(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    val component = ComponentName(context, GearslipNotificationListener::class.java)
    return runCatching { manager.isNotificationListenerAccessGranted(component) }.getOrDefault(false)
}

/**
 * The usage notes question: the setup guide's last page, and on its own the one screen people
 * who set up before the notes existed see once. Leaving it without turning them on is a no.
 */
private fun usageNotesPage(context: Context) = GuidePage(
    title = context.getString(R.string.guide_usage_title),
    explanation = usageStatsIntro(context),
    icon = { HeartIcon(it) },
    content = {
        UsageStatsChoices()
        DisposableEffect(Unit) {
            onDispose { if (!UsageStats.asked(context)) UsageStats.set(context, false) }
        }
    },
)

private fun pages(context: Context, onRequestCallScreening: () -> Unit): List<GuidePage> = buildList {
    // --- Safety: always first, and never skipped by an already-granted check ---------
    add(
        GuidePage(
            title = context.getString(R.string.guide_safety_title),
            explanation = context.getString(R.string.guide_safety_body),
            icon = glyph(Icons.Filled.Warning),
        ),
    )

    // --- Permissions: skipped outright once already granted, nothing left to ask -----
    // Asked whether or not car audio is on: voice reply and the assistant need it too.
    if (!granted(context, arrayOf(Manifest.permission.RECORD_AUDIO))) {
        add(
            GuidePage(
                title = context.getString(R.string.guide_mic_title),
                explanation = context.getString(R.string.guide_mic_body),
                icon = { MicIcon(it) },
                permissions = arrayOf(Manifest.permission.RECORD_AUDIO),
            ),
        )
    }
    if (!granted(context, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))) {
        add(
            GuidePage(
                title = context.getString(R.string.guide_location_title),
                explanation = context.getString(R.string.guide_location_body),
                icon = glyph(Icons.Filled.LocationOn),
                permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            ),
        )
    }
    if (!granted(context, arrayOf(Manifest.permission.READ_CALENDAR))) {
        add(
            GuidePage(
                title = context.getString(R.string.guide_calendar_title),
                explanation = context.getString(R.string.guide_calendar_body),
                icon = glyph(Icons.Filled.DateRange),
                permissions = arrayOf(Manifest.permission.READ_CALENDAR),
            ),
        )
    }
    val callPermissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CALL_PHONE,
    )
    if (!granted(context, callPermissions)) {
        add(
            GuidePage(
                title = context.getString(R.string.guide_calls_title),
                explanation = context.getString(R.string.guide_calls_body),
                icon = glyph(Icons.Filled.Phone),
                permissions = callPermissions,
            ),
        )
    }
    if (!granted(context, arrayOf(Manifest.permission.BLUETOOTH_CONNECT))) {
        add(
            GuidePage(
                title = context.getString(R.string.guide_bluetooth_title),
                explanation = context.getString(R.string.guide_bluetooth_body),
                icon = glyph(Icons.Filled.Call),
                permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
            ),
        )
    }

    if (!granted(context, arrayOf(Manifest.permission.POST_NOTIFICATIONS))) {
        add(
            GuidePage(
                title = context.getString(R.string.guide_notifications_title),
                explanation = context.getString(R.string.guide_notifications_body),
                icon = glyph(Icons.Filled.Notifications),
                permissions = arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            ),
        )
    }
    if (!hasNotificationAccess(context)) {
        add(
            GuidePage(
                title = context.getString(R.string.guide_messages_title),
                explanation = context.getString(R.string.guide_messages_body),
                icon = glyph(Icons.Filled.Email),
                actionLabel = context.getString(R.string.guide_open_notification_access),
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
            ),
        )
    }

    // --- Android Auto ------------------------------------------------------------------
    val androidAutoPackage = AndroidAuto.installedPackage(context)
        ?.takeIf { AndroidAuto.isActive(context, it) }
    if (androidAutoPackage != null) {
        add(
            GuidePage(
                title = context.getString(R.string.guide_android_auto_title),
                explanation = context.getString(R.string.guide_android_auto_body),
                icon = glyph(Icons.Filled.Close),
                actionLabel = context.getString(R.string.guide_open_android_auto),
                onAction = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", androidAutoPackage, null)),
                    )
                },
            ),
        )
    }

    // --- Default handler ---------------------------------------------------------------
    add(
        GuidePage(
            title = context.getString(R.string.guide_default_title),
            explanation = context.getString(R.string.guide_default_body),
            icon = glyph(Icons.Filled.Check),
        ),
    )

    // --- Car audio: always asked, and it decides whether the adb pages below show ---------
    add(
        GuidePage(
            title = context.getString(R.string.guide_audio_title),
            explanation = context.getString(R.string.guide_audio_body),
            icon = glyph(Icons.Filled.PlayArrow),
            content = { AudioChoice() },
        ),
    )

    // --- Voice: both recommended models in one tap, skipped once they're on the phone ---
    if (!SpeechModels.RECOMMENDED.all { SpeechModels.isReady(context, it) }) {
        add(
            GuidePage(
                title = context.getString(R.string.guide_voice_title),
                explanation = context.getString(R.string.guide_voice_body, SpeechModels.RECOMMENDED.sumOf { it.downloadMb }),
                icon = { MicIcon(it) },
                optional = true,
                actionLabel = context.getString(R.string.guide_download_voice),
                onAction = { SpeechModels.downloadRecommended(context) },
                content = { VoiceDownloadStatus() },
            ),
        )
    }

    // --- Optional: each is skipped once its actual effect is already in place --------
    if (!hasProjectMediaOp(context)) {
        add(
            GuidePage(
                title = context.getString(R.string.guide_audio_prompt_title),
                explanation = context.getString(R.string.guide_audio_prompt_body),
                icon = glyph(Icons.Filled.Build),
                optional = true,
                usbOnly = true,
                command = "adb shell appops set ${BuildConfig.APPLICATION_ID} PROJECT_MEDIA allow",
            ),
        )
    }
    if (!granted(context, arrayOf(Manifest.permission.WRITE_SECURE_SETTINGS))) {
        add(
            GuidePage(
                title = context.getString(R.string.guide_readable_title),
                explanation = context.getString(R.string.guide_readable_body),
                icon = glyph(Icons.Filled.Build),
                optional = true,
                usbOnly = true,
                command = "adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS",
            ),
        )
    }
    if (!hasCallScreeningRole(context)) {
        add(
            GuidePage(
                title = context.getString(R.string.guide_answer_title),
                explanation = context.getString(R.string.guide_answer_body),
                icon = glyph(Icons.Filled.Call),
                optional = true,
                actionLabel = context.getString(R.string.guide_set_up),
                onAction = onRequestCallScreening,
            ),
        )
    }

    // --- At the end, every time: apps worth installing, how the car's buttons look, and last
    // the usage notes. On a replay, the switch shows what was chosen before.
    add(
        GuidePage(
            title = context.getString(R.string.guide_apps_title),
            explanation = context.getString(R.string.recommended_intro),
            icon = { StarIcon(it) },
            content = { RecommendedAppsList() },
        ),
    )
    add(
        GuidePage(
            title = context.getString(R.string.guide_buttons_title),
            explanation = context.getString(R.string.guide_buttons_body),
            icon = { ButtonsIcon(it) },
            content = { ThemePicker(it.c, it.cq, it.cqSp, key = "guide-buttons") },
        ),
    )
    add(usageNotesPage(context))
}

/**
 * Bluetooth or USB for the car's music, as two cards. Bluetooth comes first and is picked for a
 * driver who hasn't chosen yet: it never asks for consent, and the map app's voice reaches the car.
 */
/** How the voice download is going: a bar while it runs, a line once it's done or failed. */
@Composable
private fun VoiceDownloadStatus() {
    val context = LocalContext.current
    val progress by SpeechModels.progress.collectAsState()
    val states = SpeechModels.RECOMMENDED.map { SpeechModels.state(it).collectAsState().value }
    val line = when {
        progress != null -> stringResource(R.string.guide_voice_downloading, progress ?: 0)
        states.all { it == SpeechModels.State.Ready } -> stringResource(R.string.guide_voice_ready)
        states.any { it is SpeechModels.State.Failed } -> stringResource(R.string.guide_voice_failed)
        else -> null
    }
    LaunchedEffect(Unit) { SpeechModels.init(context) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        progress?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
        line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun AudioChoice() {
    val usb by CarSettings.pipeAudio.collectAsState()
    LaunchedEffect(Unit) { if (!CarSettings.pipeAudioChosen) CarSettings.setPipeAudio(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AudioCard(
            stringResource(R.string.guide_audio_bluetooth), stringResource(R.string.guide_recommended),
            stringResource(R.string.guide_audio_bluetooth_body),
            selected = !usb,
        ) { CarSettings.setPipeAudio(false) }
        AudioCard(
            stringResource(R.string.guide_audio_usb), null,
            stringResource(R.string.guide_audio_usb_body),
            selected = usb,
        ) { CarSettings.setPipeAudio(true) }
    }
}

@Composable
private fun AudioCard(title: String, tag: String?, body: String, selected: Boolean, onClick: () -> Unit) {
    val c = introColors()
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) c.primaryC else Color.Transparent)
            .border(BorderStroke(2.dp, if (selected) c.primary else c.outline), shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, color = if (selected) c.onPrimaryC else c.onSurface, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold))
            tag?.let { Text(it, color = c.primary, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)) }
            Text(
                body, color = if (selected) c.onPrimaryC else c.onVariant,
                style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The first-run guide, one page at a time: permissions, then removing Android Auto if it is still installed, then the optional extras. Nothing
 * here can be verified as done except a permission grant, so every other page is a "Next" a
 * driver takes on trust, with "Skip" sitting right next to it.
 */
@Composable
fun SetupGuideScreen(onRequestCallScreening: () -> Unit, onDone: () -> Unit, onlyUsageNotes: Boolean = false) {
    val context = LocalContext.current
    val allPages = remember { if (onlyUsageNotes) listOf(usageNotesPage(context)) else pages(context, onRequestCallScreening) }
    // The USB-only pages all come after the car audio page, so dropping them never moves the
    // page the driver is on.
    val usbAudio by CarSettings.pipeAudio.collectAsState()
    val pages = remember(usbAudio) { allPages.filter { usbAudio || !it.usbOnly } }
    var index by remember { mutableIntStateOf(0) }

    // Only ours to handle once there is a previous page to return to; at the first page, the
    // system back press falls through to whatever the screen beneath this one does with it.
    BackHandler(enabled = index > 0) { index -= 1 }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            Prefetch.warm(context, force = true)
            CarCalls.start(context)
        }
        index += 1
    }

    val page = pages.getOrNull(index)
    if (page == null) {
        AppSettings.setSeenPermissionsSetup(context)
        onDone()
        return
    }

    val alreadyGranted = page.permissions?.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    } ?: false

    val c = introColors()
    // The badges take turns through the phone's three accent colours, as the release intro does.
    val (badge, onBadge) = listOf(c.primaryC to c.onPrimaryC, c.secondaryC to c.onSecondaryC, c.tertiaryC to c.onTertiaryC)[index % 3]
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "spin",
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(c.surface).systemBarsPadding()) {
        val w = maxWidth
        // One percent of the screen's width, as in the intro, so it scales the same way.
        fun cq(n: Float): Dp = w * (n / 100f)
        val density = LocalDensity.current
        fun cqSp(n: Float): TextUnit = with(density) { cq(n).toSp() }

        Column(Modifier.fillMaxSize().padding(horizontal = cq(8f))) {
            // The page scrolls above the buttons, so a tall one (the usage notes' choices) never
            // pushes them off the screen. Short pages sit in the middle.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                key(index) {
                    val scroll = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(scroll)
                            .heightIn(min = maxHeight)
                            .padding(vertical = cq(6f)),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.size(cq(28f)).pop(entrance(index, 50, 800)), contentAlignment = Alignment.Center) {
                            Cookie(badge, Modifier.fillMaxSize().graphicsLayer { rotationZ = spin })
                            Box(Modifier.size(cq(12f))) { page.icon(onBadge) }
                        }
                        Spacer(Modifier.height(cq(7f)))
                        if (page.optional) {
                            Text(
                                stringResource(R.string.guide_optional), color = c.primary,
                                style = TextStyle(fontSize = cqSp(3.4f), fontWeight = FontWeight.Bold, letterSpacing = cqSp(0.4f)),
                                modifier = Modifier.up(entrance(index, 150, 700), cq(7f)),
                            )
                            Spacer(Modifier.height(cq(1.5f)))
                        }
                        Text(
                            page.title, color = c.onSurface,
                            // Long titles step down a size so they stay on two lines.
                            style = TextStyle(
                                fontSize = cqSp(if (page.title.length > 16) 8f else 10f), fontWeight = FontWeight.Black,
                                letterSpacing = cqSp(-0.4f), lineHeight = cqSp(if (page.title.length > 16) 8.6f else 10.5f),
                            ),
                            modifier = Modifier.up(entrance(index, 200, 700), cq(7f)),
                        )
                        Spacer(Modifier.height(cq(4f)))
                        Column(
                            Modifier.up(entrance(index, 350, 700), cq(7f)),
                            verticalArrangement = Arrangement.spacedBy(cq(3f)),
                        ) {
                            Text(
                                page.explanation as? AnnotatedString ?: AnnotatedString(page.explanation.toString()),
                                color = c.onVariant,
                                style = TextStyle(fontSize = cqSp(4.5f), lineHeight = cqSp(6.5f)),
                            )
                            page.command?.let {
                                CopyableCommand(it)
                                ShizukuGrantButton()
                            }
                            page.content?.invoke(GuideScale(c, ::cq, ::cqSp))
                            if (page.permissions != null && alreadyGranted) {
                                Text(
                                    stringResource(R.string.guide_already_allowed), color = c.primary,
                                    style = TextStyle(fontSize = cqSp(4.2f), fontWeight = FontWeight.SemiBold),
                                )
                            }
                        }
                    }
                    // A long page (the app list, the usage notes) says so: a fade over the
                    // last lines and a nudge to keep going, until the bottom is in view.
                    if (scroll.canScrollForward) ScrollHint(c, ::cq, ::cqSp, Modifier.align(Alignment.BottomCenter))
                }
            }

            // A single page has nothing to count.
            if (pages.size > 1) {
                Dots(c, pages.size, index, ::cq, Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(cq(5f)))
            }
            val back = if (index > 0) ({ index -= 1 }) else null
            val next = { index += 1 }
            when {
                page.permissions != null && !alreadyGranted -> {
                    PillRow(c, ::cq, ::cqSp, back, stringResource(R.string.guide_allow)) { launcher.launch(page.permissions) }
                    Pill(c, ::cqSp, stringResource(R.string.guide_skip), PillStyle.TEXT, Modifier.align(Alignment.CenterHorizontally), next)
                }
                page.onAction != null -> {
                    Pill(c, ::cqSp, page.actionLabel ?: stringResource(R.string.guide_open), PillStyle.OUTLINED, Modifier.fillMaxWidth().padding(bottom = cq(3f)), page.onAction)
                    PillRow(c, ::cq, ::cqSp, back, stringResource(R.string.guide_next), next)
                }
                else -> PillRow(c, ::cq, ::cqSp, back, stringResource(if (index == pages.lastIndex) R.string.guide_done else R.string.guide_next), next)
            }
            Spacer(Modifier.height(cq(6f)))
        }
    }
}

/** A fade into the page's background with "Scroll for more" over it, at the bottom of a page that goes on. */
@Composable
private fun ScrollHint(c: IntroColors, cq: (Float) -> Dp, cqSp: (Float) -> TextUnit, modifier: Modifier = Modifier) {
    val bob by rememberInfiniteTransition(label = "hint").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(cq(22f))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.surface.copy(alpha = 0f), c.surface))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            stringResource(R.string.guide_scroll_more),
            color = c.primary,
            style = TextStyle(fontSize = cqSp(4f), fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(bottom = cq(2f)).offset(y = cq(1.2f) * bob),
        )
    }
}

internal enum class PillStyle { FILLED, OUTLINED, TEXT }

/** The intro's rounded buttons: filled for the step's main action, outlined beside it, bare text to skip. */
@Composable
internal fun Pill(c: IntroColors, cqSp: (Float) -> TextUnit, label: String, style: PillStyle, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .then(
                when (style) {
                    PillStyle.FILLED -> Modifier.background(c.primary)
                    PillStyle.OUTLINED -> Modifier.border(BorderStroke(2.dp, c.outline), shape)
                    PillStyle.TEXT -> Modifier
                },
            )
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (style == PillStyle.FILLED) c.onPrimary else c.primary,
            style = TextStyle(fontSize = cqSp(4.4f), fontWeight = FontWeight.Bold),
        )
    }
}

/** Back on the left once there's a page to go back to, and the page's main action filling the rest. */
@Composable
internal fun PillRow(
    c: IntroColors, cq: (Float) -> Dp, cqSp: (Float) -> TextUnit,
    onBack: (() -> Unit)?, label: String, onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(cq(3f))) {
        if (onBack != null) Pill(c, cqSp, stringResource(R.string.guide_back), PillStyle.OUTLINED, Modifier.weight(1f), onBack)
        Pill(c, cqSp, label, PillStyle.FILLED, Modifier.weight(if (onBack != null) 1.4f else 1f), onClick)
    }
}
