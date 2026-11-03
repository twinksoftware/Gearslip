package app.seb3thehacker.gearslip.host

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.InputCallbackDelegate
import androidx.car.app.model.OnClickDelegate
import androidx.car.app.model.OnContentRefreshDelegate
import androidx.car.app.model.SearchCallbackDelegate
import androidx.car.app.model.TabCallbackDelegate
import androidx.car.app.OnDoneCallback
import androidx.car.app.serialization.Bundleable
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.seb3thehacker.gearslip.GearslipLog
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Turns the Car App Library's model objects into things Compose can draw.
 *
 * A templated app describes its screen in these types and says nothing about how they should
 * look - that is the host's job, and every host looks different. These helpers are the seam
 * between the app's description and Gearslip's rendering of it.
 */

/** The app's text, preferring the longest variant that will fit; variants are ordered longest first. */
fun CarText?.text(): String {
    val chars = this?.takeIf { !it.isEmpty }?.toCharSequence() ?: return ""
    return chars.withSpansFilledIn()
}

/**
 * Apps write a route's time and length as spans over placeholder characters ("\u0000 · \u0000"
 * with a DurationSpan and a DistanceSpan), leaving the host to format them. Plain toString()
 * keeps only the placeholders, which is how a route option ended up reading "·".
 */
private fun CharSequence.withSpansFilledIn(): String {
    if (this !is android.text.Spanned) return toString()
    val spans = getSpans(0, length, androidx.car.app.model.CarSpan::class.java)
        .filter { it is androidx.car.app.model.DurationSpan || it is androidx.car.app.model.DistanceSpan }
        .sortedBy { getSpanStart(it) }
    if (spans.isEmpty()) return toString()
    val out = StringBuilder()
    var at = 0
    spans.forEach { span ->
        val start = getSpanStart(span)
        val end = getSpanEnd(span)
        if (start < at || end > length) return@forEach
        out.append(this, at, start)
        out.append(
            when (span) {
                is androidx.car.app.model.DurationSpan -> remainingTime(span.durationSeconds)
                is androidx.car.app.model.DistanceSpan -> span.distance.display()
                else -> ""
            },
        )
        at = end
    }
    out.append(this, at, length)
    return out.toString().replace("\u0000", "").trim()
}

/**
 * Icons keep coming back under fresh [CarIcon]/IconCompat objects - every template refetch
 * deserializes its own copies over the binder - so caching by object identity would never hit.
 * [describe] is stable across those copies for the same underlying icon (same URI or resource),
 * which is what makes it usable as a cache key. Capacity is in entries, not bytes: cover art at
 * car-screen size is small and a shelf plus its sections stays well under a hundred distinct
 * icons on screen at once.
 */
private val iconCache = android.util.LruCache<String, ImageBitmap>(200)

/**
 * Loads an icon the app described.
 *
 * A [CarIcon] is either a standard type the host is expected to draw itself, or an IconCompat
 * pointing into the app's own resources - which is why this needs a Context that can see the
 * app, and why a failure here is a missing glyph rather than an error worth stopping for.
 */
fun CarIcon?.image(context: Context): ImageBitmap? {
    val icon = this?.icon ?: return null
    val key = icon.cacheKey(context)
    key?.let { k -> iconCache.get(k)?.let { return it } }
    val drawable: Drawable = runCatching { icon.loadDrawable(context) }
        .onFailure { GearslipLog.w("icon: loadDrawable threw for ${icon.describe()}: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrNull()
        ?: run {
            GearslipLog.w("icon: loadDrawable returned null for ${icon.describe()}")
            return null
        }
    val rawWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: ICON_FALLBACK_PX
    val rawHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: ICON_FALLBACK_PX
    // A handful of apps (Spotify's search glyph, 12x12) hand over an icon so small it turns to
    // mush once Compose stretches it up to button size. Rasterizing it larger here instead lets
    // Android's own bitmap scaler do that smoothing while the source is still a Drawable, rather
    // than Compose scaling an already-tiny texture at draw time.
    val scale = (ICON_FALLBACK_PX.toFloat() / maxOf(rawWidth, rawHeight)).coerceAtLeast(1f)
    val width = (rawWidth * scale).toInt()
    val height = (rawHeight * scale).toInt()
    val bitmap = runCatching { drawable.toBitmap(width, height).asImageBitmap() }
        .onFailure { GearslipLog.w("icon: toBitmap threw for ${icon.describe()}: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrNull() ?: return null
    key?.let { iconCache.put(it, bitmap) }
    return bitmap
}

/**
 * [describe], plus when the app was last installed for a resource icon. Resource ids are only
 * numbers inside one build of the app: an update that adds a drawable renumbers the ones after it,
 * so a cache keyed on the bare id kept drawing the old build's picture on the wrong button (Vela's
 * new Saved button came up as its search glyph).
 */
/**
 * Null for an icon that carries its own pixels: two different bitmaps describe the same, so a
 * key would hand one app's turn arrow the picture of whatever bitmap was cached first.
 */
private fun androidx.core.graphics.drawable.IconCompat.cacheKey(context: Context): String? {
    if (type == androidx.core.graphics.drawable.IconCompat.TYPE_BITMAP ||
        type == androidx.core.graphics.drawable.IconCompat.TYPE_ADAPTIVE_BITMAP ||
        type == androidx.core.graphics.drawable.IconCompat.TYPE_DATA
    ) return null
    if (type != androidx.core.graphics.drawable.IconCompat.TYPE_RESOURCE) return describe()
    val installed = runCatching { context.packageManager.getPackageInfo(resPackage, 0).lastUpdateTime }.getOrDefault(0L)
    return "${describe()}@$installed"
}

/** Enough of an [androidx.core.graphics.drawable.IconCompat] to tell log lines apart without dumping the whole object. */
private fun androidx.core.graphics.drawable.IconCompat.describe(): String = when (type) {
    androidx.core.graphics.drawable.IconCompat.TYPE_URI,
    androidx.core.graphics.drawable.IconCompat.TYPE_URI_ADAPTIVE_BITMAP,
    -> "uri=$uri"
    androidx.core.graphics.drawable.IconCompat.TYPE_RESOURCE -> "resource=$resPackage:$resId"
    else -> "type=$type"
}

/** Standard icons carry no bitmap - the host draws its own, so callers need to know which. */
val CarIcon?.standardType: Int? get() = this?.takeIf { it.icon == null }?.type

/**
 * Resolves a colour the app asked for. The named colours deliberately stay close to what a
 * driver expects them to mean rather than matching the app's brand.
 */
fun CarColor?.color(dark: Boolean, default: Color): Color = when (this?.type) {
    null, CarColor.TYPE_DEFAULT -> default
    CarColor.TYPE_CUSTOM -> Color(if (dark) colorDark else color)
    CarColor.TYPE_RED -> Color(0xFFE53935)
    CarColor.TYPE_GREEN -> Color(0xFF43A047)
    CarColor.TYPE_BLUE -> Color(0xFF1E88E5)
    CarColor.TYPE_YELLOW -> Color(0xFFFDD835)
    else -> default
}

/**
 * "500 ft", "2.4 mi". The app picks the unit and its rounding; when that's the other system from
 * the one chosen in Gearslip's settings, the distance is converted and rounded here instead.
 */
fun Distance?.display(): String {
    val distance = this ?: return ""
    val appImperial = displayUnit in setOf(Distance.UNIT_MILES, Distance.UNIT_MILES_P1, Distance.UNIT_FEET, Distance.UNIT_YARDS)
    val appMetric = displayUnit in setOf(Distance.UNIT_METERS, Distance.UNIT_KILOMETERS, Distance.UNIT_KILOMETERS_P1)
    val imperial = app.seb3thehacker.gearslip.car.CarSettings.imperial()
    if ((imperial && appMetric) || (!imperial && appImperial)) return convertDistance(distance.meters(), imperial)
    val unit = when (displayUnit) {
        Distance.UNIT_METERS -> "m"
        Distance.UNIT_KILOMETERS, Distance.UNIT_KILOMETERS_P1 -> "km"
        Distance.UNIT_MILES, Distance.UNIT_MILES_P1 -> "mi"
        Distance.UNIT_FEET -> "ft"
        Distance.UNIT_YARDS -> "yd"
        else -> ""
    }
    val decimals = when (displayUnit) {
        Distance.UNIT_KILOMETERS_P1, Distance.UNIT_MILES_P1 -> 1
        Distance.UNIT_KILOMETERS, Distance.UNIT_MILES -> if (displayDistance < 10) 1 else 0
        else -> 0
    }
    return "%.${decimals}f $unit".format(distance.displayDistance)
}

internal fun Distance.meters(): Double = displayDistance * when (displayUnit) {
    Distance.UNIT_KILOMETERS, Distance.UNIT_KILOMETERS_P1 -> 1000.0
    Distance.UNIT_MILES, Distance.UNIT_MILES_P1 -> 1609.344
    Distance.UNIT_FEET -> 0.3048
    Distance.UNIT_YARDS -> 0.9144
    else -> 1.0
}

/**
 * Rounded the way a nav app would: feet in steps of 50 under a tenth of a mile, metres in steps
 * of 10 (50 past 300) under a kilometre, one decimal under 10 miles or km, whole numbers above.
 */
private fun convertDistance(meters: Double, imperial: Boolean): String {
    if (imperial) {
        val miles = meters / 1609.344
        if (miles < 0.1) return "${(meters / 0.3048 / 50).roundToInt() * 50} ft"
        return if (miles < 10) "%.1f mi".format(miles) else "%.0f mi".format(miles)
    }
    if (meters < 1000) {
        val step = if (meters < 300) 10 else 50
        return "${(meters / step).roundToInt() * step} m"
    }
    val km = meters / 1000
    return if (km < 10) "%.1f km".format(km) else "%.0f km".format(km)
}

/** "18 min" from the app's remaining-time estimate. */
fun remainingTime(seconds: Long): String {
    if (seconds <= 0) return ""
    val hours = TimeUnit.SECONDS.toHours(seconds)
    val minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60
    return if (hours > 0) "$hours hr $minutes min" else "$minutes min"
}

/** "1:10 AM" - the clock at the destination, not the phone's own, in case a route crosses a zone. */
fun androidx.car.app.model.DateTimeWithZone?.clockTime(): String {
    val at = this ?: return ""
    val shifted = java.util.Date(at.timeSinceEpochMillis + at.zoneOffsetSeconds * 1000L)
    return runCatching {
        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(shifted)
    }.getOrDefault("")
}

/**
 * Tells the app something was tapped.
 *
 * [OnClickDelegate.isParkedOnly] marks an action the app itself considers unsafe while moving.
 * Gearslip has no speed signal yet, so these are allowed through and the decision is recorded
 * here as the place to gate them once the sensor channel exists.
 */
fun OnClickDelegate?.click(label: String = "action") {
    val delegate = this ?: return
    if (label != AutoRoute.AUTO_LABEL) AutoRoute.driverTappedAt = android.os.SystemClock.elapsedRealtime()
    runCatching { delegate.sendClick(doneCallback("a $label tap")) }
        .onFailure { GearslipLog.w("host: could not send a $label tap: ${it.message}") }
}

/**
 * Search is a conversation: the host owns the text and the keyboard, and tells the app about
 * every keystroke so it can update its results as you type. [submitted] is the explicit "go".
 */
fun SearchCallbackDelegate?.textChanged(text: String) {
    val delegate = this ?: return
    runCatching { delegate.sendSearchTextChanged(text, doneCallback("search text")) }
        .onFailure { GearslipLog.w("host: could not send search text: ${it.message}") }
}

fun SearchCallbackDelegate?.submitted(text: String) {
    val delegate = this ?: return
    runCatching { delegate.sendSearchSubmitted(text, doneCallback("search submit")) }
        .onFailure { GearslipLog.w("host: could not submit a search: ${it.message}") }
}

/** Tells the app which tab the driver picked; the app answers with a new template. */
fun TabCallbackDelegate?.tabSelected(contentId: String) {
    val delegate = this ?: return
    runCatching { delegate.sendTabSelected(contentId, doneCallback("tab")) }
        .onFailure { GearslipLog.w("host: could not select a tab: ${it.message}") }
}

/** Free text typed into a sign-in field, reported the same way search text is. */
fun InputCallbackDelegate?.inputChanged(text: String) {
    val delegate = this ?: return
    runCatching { delegate.sendInputTextChanged(text, doneCallback("input text")) }
        .onFailure { GearslipLog.w("host: could not send input text: ${it.message}") }
}

fun InputCallbackDelegate?.inputSubmitted(text: String) {
    val delegate = this ?: return
    runCatching { delegate.sendInputSubmitted(text, doneCallback("input submit")) }
        .onFailure { GearslipLog.w("host: could not submit input: ${it.message}") }
}

/** Tells the app the driver entered or left pan mode, so it can hide its own map controls. */
fun androidx.car.app.navigation.model.PanModeDelegate?.panModeChanged(active: Boolean) {
    val delegate = this ?: return
    runCatching { delegate.sendPanModeChanged(active, doneCallback("pan mode")) }
        .onFailure { GearslipLog.w("host: could not send pan mode: ${it.message}") }
}

/** Tells the app a toggle row was switched; it answers with a template showing the new state. */
fun androidx.car.app.model.OnCheckedChangeDelegate?.checkedChanged(checked: Boolean) {
    val delegate = this ?: return
    runCatching { delegate.sendCheckedChange(checked, doneCallback("toggle")) }
        .onFailure { GearslipLog.w("host: could not send a toggle: ${it.message}") }
}

/** Tells the app which option of a single-choice list was picked. */
fun androidx.car.app.model.OnSelectedDelegate?.selected(index: Int) {
    val delegate = this ?: return
    runCatching { delegate.sendSelected(index, doneCallback("selection")) }
        .onFailure { GearslipLog.w("host: could not send a selection: ${it.message}") }
}

/** The driver asked for fresher content, e.g. by pulling a place list. */
fun OnContentRefreshDelegate?.refresh() {
    val delegate = this ?: return
    runCatching { delegate.sendContentRefreshRequested(doneCallback("refresh")) }
        .onFailure { GearslipLog.w("host: could not request a refresh: ${it.message}") }
}

/** An alert left the screen without the app taking it down: timed out, or closed by the driver. */
fun androidx.car.app.model.AlertCallbackDelegate?.cancel(reason: Int) {
    val delegate = this ?: return
    runCatching { delegate.sendCancel(reason, doneCallback("an alert cancel")) }
        .onFailure { GearslipLog.w("host: could not cancel an alert: ${it.message}") }
}

/** An alert left the screen another way: a newer one replaced it, the app took it down, or a tap. */
fun androidx.car.app.model.AlertCallbackDelegate?.dismissed() {
    val delegate = this ?: return
    runCatching { delegate.sendDismiss(doneCallback("an alert dismiss")) }
        .onFailure { GearslipLog.w("host: could not dismiss an alert: ${it.message}") }
}

private fun doneCallback(label: String) = object : OnDoneCallback {
    // onSuccess is annotated nullable and onFailure isn't - the asymmetry is real.
    override fun onSuccess(response: Bundleable?) = Unit
    override fun onFailure(response: Bundleable) = GearslipLog.w("host: the app rejected $label")
}

/** An action with nothing to show is one the host should skip rather than draw as a blank. */
fun Action.isDrawable(): Boolean =
    icon != null || title.text().isNotEmpty() || type != Action.TYPE_CUSTOM

private const val ICON_FALLBACK_PX = 48
