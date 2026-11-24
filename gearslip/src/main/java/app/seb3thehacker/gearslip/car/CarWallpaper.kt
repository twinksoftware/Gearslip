package app.seb3thehacker.gearslip.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.GearslipLog
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the car's Apps page draws behind its icons: nothing, a photo from the phone (one for the
 * car's light mode and one for its dark mode), or the weather outside, drawn live.
 *
 * Photos are copied into Gearslip's own files, scaled down to what a car screen can show, so the
 * original can be moved or deleted from the gallery without the car losing it.
 */
object CarWallpaper {

    /** [DEMO] steps through every weather scene, for checking them; debug builds only. */
    enum class Mode { OFF, PHOTO, WEATHER, DEMO }

    private const val KEY_MODE = "car_wallpaper"
    private const val LIGHT = "wallpaper-light.jpg"
    private const val DARK = "wallpaper-dark.jpg"

    /** Wide enough for the biggest car screens Gearslip has seen, without keeping a 50 MP photo. */
    private const val MAX_SIDE = 1920

    private val modeFlow = MutableStateFlow(Mode.OFF)
    val mode: StateFlow<Mode> = modeFlow

    /** Bumped whenever a photo changes, so whatever shows it reloads. */
    private val photosFlow = MutableStateFlow(0)
    val photos: StateFlow<Int> = photosFlow

    @Volatile private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        modeFlow.value = runCatching { Mode.valueOf(AppSettings.getString(context, KEY_MODE, Mode.OFF.name)) }.getOrDefault(Mode.OFF)
    }

    fun setMode(context: Context, mode: Mode) {
        modeFlow.value = mode
        AppSettings.putString(context, KEY_MODE, mode.name)
    }

    fun file(context: Context, dark: Boolean): File = File(context.filesDir, if (dark) DARK else LIGHT)

    fun hasPhoto(context: Context, dark: Boolean): Boolean = file(context, dark).isFile

    /** Copies the picked photo in, scaled down. Runs off the main thread; false when it couldn't be read. */
    fun savePhoto(context: Context, uri: Uri, dark: Boolean): Boolean = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = info.size.width to info.size.height
            val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
            decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        file(context, dark).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        photosFlow.value++
        true
    }.onFailure { GearslipLog.e("could not save the wallpaper photo", it) }.getOrDefault(false)

    fun removePhoto(context: Context, dark: Boolean) {
        file(context, dark).delete()
        photosFlow.value++
    }
}
