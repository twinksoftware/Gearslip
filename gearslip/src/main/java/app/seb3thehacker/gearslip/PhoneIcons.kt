package app.seb3thehacker.gearslip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.ByteArrayOutputStream

/**
 * Gearslip's own icon as the PNGs a phone hands the car in its ServiceDiscoveryRequest, at the
 * 32, 64 and 128 px a real phone sends. Cars draw the phone's tile on their home screen from
 * them, and older head units may wait for them before answering at all.
 */
object PhoneIcons {

    private val SIZES = intArrayOf(32, 64, 128)

    @Volatile private var cached: List<ByteArray>? = null

    /** The three PNGs, smallest first; empty if the icon can't be drawn. */
    fun pngs(context: Context): List<ByteArray> = cached ?: runCatching {
        val icon = context.packageManager.getApplicationIcon(context.applicationInfo).mutate()
        SIZES.map { size ->
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, size, size)
            icon.draw(Canvas(bitmap))
            val png = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
            bitmap.recycle()
            png.toByteArray()
        }
    }.onFailure { GearslipLog.w("phone icons: couldn't draw them (${it.message})") }
        .getOrDefault(emptyList())
        .also { if (it.isNotEmpty()) cached = it }
}
