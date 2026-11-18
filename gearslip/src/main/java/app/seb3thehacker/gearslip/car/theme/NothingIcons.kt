/*
 * The Nothing-style monochrome icon pipeline, ported from
 * https://github.com/linuxct/nothing-icongen "PortableMonochrome.kt" (AGPL-3.0, © linuxct).
 * Both projects are AGPL-3.0, so the port is license-compatible; it stays under AGPL-3.0 like
 * the rest of Gearslip. See NOTICE.md.
 *
 * Changes from the source: SIZE is 192 instead of 432 (same math, faster); the lightMask
 * variant is dropped - Gearslip tints the one ALPHA_8 mask with onSurface at draw time, so a
 * single mask serves dark and light.
 */

package app.seb3thehacker.gearslip.car.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.min

/**
 * Draws an app's launcher icon as a white-on-black Nothing glyph: an ALPHA_8 mask, opaque where
 * the glyph is and transparent elsewhere. The launcher tints it with onSurface at draw time, so
 * the one mask works on the black and the white canvas alike. Renders run on IO and are
 * serialized - the pixel scratch buffers are instance state, like the source's.
 */
internal object NothingIconRenderer {
    private const val SIZE = 192
    private const val GLYPH_SCALE = 0.3888889f
    private const val ADAPTIVE_SCALE = 0.93f
    private const val SAMPLE_SIZE = 50

    private val pixels = IntArray(SIZE * SIZE)
    private val gray = IntArray(SIZE * SIZE)
    private val samplePixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
    private val grayBins = IntArray(32)
    private val colorBins = BooleanArray(512)
    private val filtering = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val analysisMatrix = Matrix().apply {
        setScale(SAMPLE_SIZE.toFloat() / SIZE, SAMPLE_SIZE.toFloat() / SIZE)
    }
    private val adaptiveDrawFilter = PaintFlagsDrawFilter(Paint.DITHER_FLAG, Paint.FILTER_BITMAP_FLAG)

    /** The mask for [source], or null when nothing usable can be made of it. */
    @Synchronized
    fun render(source: Drawable): Bitmap? {
        if (Build.VERSION.SDK_INT >= 33 && source is AdaptiveIconDrawable) {
            source.monochrome?.let { mono ->
                runCatching { declared(mono) }.getOrNull()?.let { return it }
            }
        }
        if (hasSeparateForeground(source)) {
            generate(source, includeBackdrop = false, acceptRejectedArtwork = false)?.let { return it }
            return generate(source, includeBackdrop = true, acceptRejectedArtwork = true)
        }
        return generate(source, includeBackdrop = false, acceptRejectedArtwork = true)
    }

    private fun generate(
        source: Drawable,
        includeBackdrop: Boolean,
        acceptRejectedArtwork: Boolean,
    ): Bitmap? {
        val raster = runCatching { rasterizeSource(source, includeBackdrop = includeBackdrop) }.getOrNull()
            ?: return null
        try {
            val complex = classifySource(source, includeBackdrop)
            val analysis = analyze(raster)
            if (!acceptRejectedArtwork && complex && analysis.coverage < 0.6f) return null
            raster.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            val modal = analysis.modalGray
            var minimum = 255
            var offset = 255 - modal
            if (complex && offset > 80) offset = 0
            for (i in pixels.indices) {
                val color = pixels[i]
                if (Color.alpha(color) == 0) continue
                val shifted = luminance(color) + offset
                val value = if (shifted > 255) 510 - shifted else shifted
                gray[i] = value
                if (Color.alpha(color) > 110) minimum = min(minimum, value)
            }
            // Preserve low-alpha and sparse legacy artwork even when no reference source is
            // accepted. Adaptive foreground attempts still follow the normal retry rules.
            val factor = if (minimum < 248) 210f / (248 * 248 - minimum * minimum) else 0f
            var maximum = 0
            for (i in pixels.indices) {
                val alpha = Color.alpha(pixels[i])
                val converted = if (alpha > 110 && minimum < 248) {
                    min(255, ((gray[i] * gray[i] - 248 * 248) * factor).toInt() + 255)
                } else alpha
                maximum = max(maximum, converted)
                pixels[i] = converted shl 24
            }
            if (!acceptRejectedArtwork && maximum <= 40) return null
            val crop = symmetricBounds(pixels, SIZE, 40)
                ?: if (acceptRejectedArtwork) symmetricBounds(pixels, SIZE, 0) else null
            if (crop == null || crop.isEmpty) {
                return if (acceptRejectedArtwork) emptyMask() else null
            }
            raster.setPixels(pixels, crop.top * SIZE + crop.left, SIZE,
                crop.left, crop.top, crop.width(), crop.height())
            return fit(raster, crop, GLYPH_SCALE)
        } finally {
            raster.recycle()
        }
    }

    private fun declared(mono: Drawable): Bitmap {
        if (mono is VectorDrawable || mono is InsetDrawable) {
            val drawable = opticalInsets(mono)
            val sample = rasterize(drawable, 100, alphaOnly = true)
            val extent: Float
            try {
                sample.getPixels(pixels, 0, 100, 0, 0, 100, 100)
                val bounds = contentBounds(pixels, 100, 40)
                extent = bounds?.let { max(it.width(), it.height()) / 100f } ?: 0f
            } finally {
                sample.recycle()
            }
            // Declared artwork already near the target size is intentionally left alone.
            // Re-normalizing that band also applies .93 and visibly shrinks existing glyphs.
            val needsNormalization = extent > 0f &&
                (extent.toDouble() < 0.3488888955116272 || extent.toDouble() > 0.4288888955116272)
            if (needsNormalization) {
                // Measure at 100px before final bounds, matching fractional optical inset behavior.
                return normalizeDeclared(drawable, extent)
            }
            return rasterize(mono, SIZE, alphaOnly = true)
        }
        val clipped = ClippedMonochromeDrawable(mono)
        val mask = rasterize(clipped, SIZE, alphaOnly = true)
        var cropped: Bitmap? = null
        try {
            mask.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            val bounds = contentBounds(pixels, SIZE, 40)
            val artwork = if (bounds != null && !bounds.isEmpty) {
                Bitmap.createBitmap(mask, bounds.left, bounds.top, bounds.width(), bounds.height()).also {
                    if (it !== mask) cropped = it
                }
            } else mask
            return fitDeclared(artwork)
        } finally {
            cropped?.recycle()
            mask.recycle()
        }
    }

    private fun normalizeDeclared(drawable: Drawable, extent: Float): Bitmap {
        var width = (SIZE * (GLYPH_SCALE / extent)).toInt()
        val height: Int
        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        if (iw > ih) {
            height = ih * width / iw
        } else {
            height = width
            width = iw * height / ih
        }
        val center = SIZE / 2
        val result = emptyMask()
        try {
            val canvas = Canvas(result)
            canvas.scale(ADAPTIVE_SCALE, ADAPTIVE_SCALE, center.toFloat(), center.toFloat())
            drawable.setBounds(center - width / 2, center - height / 2,
                center + width / 2, center + height / 2)
            drawable.draw(canvas)
            return result
        } catch (failure: Throwable) {
            result.recycle()
            throw failure
        }
    }

    private class ClippedMonochromeDrawable(mono: Drawable) :
        InsetDrawable(mono, -AdaptiveIconDrawable.getExtraInsetFraction()) {
        private val crop = AdaptiveIconDrawable(ColorDrawable(Color.BLACK), null)

        override fun draw(canvas: Canvas) {
            crop.bounds = bounds
            val save = canvas.save()
            canvas.clipPath(crop.iconMask)
            super.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    private fun fitDeclared(bitmap: Bitmap): Bitmap {
        val result = emptyMask()
        var width = (SIZE * GLYPH_SCALE).toInt()
        val height: Int
        if (bitmap.width > bitmap.height) {
            height = bitmap.height * width / bitmap.width
        } else {
            height = width
            width = bitmap.width * height / bitmap.height
        }
        val center = SIZE / 2
        val destination = Rect(center - width / 2, center - height / 2,
            center + width / 2, center + height / 2)
        try {
            val canvas = Canvas(result)
            canvas.scale(ADAPTIVE_SCALE, ADAPTIVE_SCALE, center.toFloat(), center.toFloat())
            canvas.drawBitmap(bitmap, null, destination, filtering)
            return result
        } catch (failure: Throwable) {
            result.recycle()
            throw failure
        }
    }

    private fun opticalInsets(mono: Drawable): Drawable {
        if (Build.VERSION.SDK_INT < 29 || mono !is InsetDrawable) return mono
        val width = mono.intrinsicWidth
        val height = mono.intrinsicHeight
        if (width <= 0 || height <= 0) return mono
        val insets = mono.opticalInsets
        val child = mono.drawable ?: return mono
        if (width - insets.left - insets.right == child.intrinsicWidth ||
            height - insets.top - insets.bottom == child.intrinsicHeight
        ) {
            return InsetDrawable(child, insets.left / width.toFloat(), insets.top / height.toFloat(),
                insets.right / width.toFloat(), insets.bottom / height.toFloat())
        }
        return mono
    }

    private fun hasSeparateForeground(source: Drawable): Boolean {
        if (Build.VERSION.SDK_INT < 26 || source !is AdaptiveIconDrawable || source.foreground == null) return false
        val background = source.background ?: return false
        return background !is ColorDrawable || background.color != Color.TRANSPARENT
    }

    private fun rasterizeSource(source: Drawable, size: Int = SIZE, includeBackdrop: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            if (Build.VERSION.SDK_INT >= 26 && source is AdaptiveIconDrawable) {
                canvas.drawFilter = adaptiveDrawFilter
                val selected = if (!includeBackdrop && hasSeparateForeground(source)) {
                    AdaptiveIconDrawable(null, cloneDrawable(requireNotNull(source.foreground)))
                } else cloneDrawable(source) as AdaptiveIconDrawable
                val background = selected.background
                val normalized = if (background == null ||
                    background is ColorDrawable && background.color == Color.TRANSPARENT
                ) {
                    AdaptiveIconDrawable(ClearingBackground(), selected.foreground)
                } else selected
                normalized.setBounds(0, 0, size, size)
                normalized.draw(canvas)
            } else {
                drawCentered(canvas, cloneDrawable(source), size)
                val radius = size / 2f
                val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = kotlin.math.sqrt(2f * radius * radius) - radius
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
                canvas.drawCircle(radius, radius, radius + clear.strokeWidth / 2f, clear)
            }
            return bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    private fun cloneDrawable(source: Drawable): Drawable = source.constantState?.newDrawable() ?: source

    /** Clear the adaptive drawable's internal black layer before its foreground is drawn. */
    private class ClearingBackground : Drawable() {
        private val clear = Paint().apply {
            color = Color.WHITE
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }

        override fun draw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            canvas.drawRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), clear)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSPARENT
    }

    private fun rasterize(
        drawable: Drawable,
        size: Int,
        alphaOnly: Boolean = false,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size,
            if (alphaOnly) Bitmap.Config.ALPHA_8 else Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            drawCentered(canvas, drawable, size)
            return bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    private fun drawCentered(canvas: Canvas, drawable: Drawable, size: Int) {
        var width = size
        var height = width
        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        if (iw > 0 && ih > 0) {
            if (iw > ih) height = ih * width / iw else width = iw * height / ih
        }
        val center = size / 2
        drawable.setBounds(center - width / 2, center - height / 2, center + width / 2, center + height / 2)
        drawable.draw(canvas)
    }

    private fun fit(bitmap: Bitmap, crop: Rect, scale: Float): Bitmap {
        val result = emptyMask()
        val longest = (SIZE * scale).toInt()
        val width: Int
        val height: Int
        if (bitmap.width == bitmap.height) {
            width = longest
            height = longest
        } else if (bitmap.width > bitmap.height) {
            width = longest
            height = bitmap.height * longest / bitmap.width
        } else {
            height = longest
            width = bitmap.width * longest / bitmap.height
        }
        val left = (SIZE - width) / 2f
        val top = (SIZE - height) / 2f
        try {
            Canvas(result).drawBitmap(bitmap, crop, RectF(left, top, SIZE - left, SIZE - top), filtering)
            return result
        } catch (failure: Throwable) {
            result.recycle()
            throw failure
        }
    }

    private data class TonalSample(val modalGray: Int, val coverage: Float)

    private fun analyze(source: Bitmap): TonalSample {
        val sample = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888)
        try {
            Canvas(sample).drawBitmap(source, analysisMatrix, filtering)
            sample.getPixels(samplePixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
            grayBins.fill(0)
            var covered = 0
            var area = 0
            val radius = SAMPLE_SIZE / 2
            for (y in 0 until SAMPLE_SIZE) for (x in 0 until SAMPLE_SIZE) {
                val pixel = samplePixels[y * SAMPLE_SIZE + x]
                if (Color.alpha(pixel) > 110) grayBins[luminance(pixel) / 8]++
                if ((x - radius) * (x - radius) + (y - radius) * (y - radius) <= radius * radius) {
                    area++
                    if (Color.alpha(pixel) > 0) covered++
                }
            }
            var mode = 0
            for (i in 1 until grayBins.size) if (grayBins[i] > grayBins[mode]) mode = i
            return TonalSample((mode + 1) * 8 - 1, covered.toFloat() / area)
        } finally {
            sample.recycle()
        }
    }

    private fun classifySource(source: Drawable, includeBackdrop: Boolean = false): Boolean {
        // Complexity is measured by drawing the drawable directly at 50px. Downsampling
        // the 192px raster instead adds interpolated RGB buckets and can invert polarity.
        val sample = rasterizeSource(source, SAMPLE_SIZE, includeBackdrop)
        try {
            sample.getPixels(samplePixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
            var edges = 0
            colorBins.fill(false)
            var colors = 0
            for (y in 0 until SAMPLE_SIZE) for (x in 0 until SAMPLE_SIZE) {
                val pixel = samplePixels[y * SAMPLE_SIZE + x]
                val bin = Color.red(pixel) / 32 * 64 + Color.green(pixel) / 32 * 8 + Color.blue(pixel) / 32
                if (!colorBins[bin]) { colorBins[bin] = true; colors++ }
                if (x + 1 < SAMPLE_SIZE && y + 1 < SAMPLE_SIZE) {
                    val right = samplePixels[y * SAMPLE_SIZE + x + 1]
                    val below = samplePixels[(y + 1) * SAMPLE_SIZE + x]
                    if (colorDistance(pixel, right) + colorDistance(pixel, below) > 10) edges++
                }
            }
            return colors > 34 || edges > samplePixels.size * 0.3f
        } finally {
            sample.recycle()
        }
    }

    private fun colorDistance(a: Int, b: Int): Int =
        kotlin.math.abs(Color.red(a) - Color.red(b)) + kotlin.math.abs(Color.green(a) - Color.green(b)) +
            kotlin.math.abs(Color.blue(a) - Color.blue(b))

    private fun luminance(color: Int): Int =
        (Color.red(color) * 0.30 + Color.green(color) * 0.59 + Color.blue(color) * 0.11).toInt()

    private fun contentBounds(pixels: IntArray, size: Int, threshold: Int, margin: Int = 2): Rect? {
        var left = size
        var right = -1
        var top = size
        var bottom = -1
        for (y in 0 until size) for (x in 0 until size) {
            if (Color.alpha(pixels[y * size + x]) > threshold) {
                left = min(left, x); right = max(right, x)
                top = min(top, y); bottom = max(bottom, y)
            }
        }
        return if (right < left) null else Rect(max(0, left - margin), max(0, top - margin),
            min(size, right + margin), min(size, bottom + margin))
    }

    private fun symmetricBounds(pixels: IntArray, size: Int, threshold: Int, margin: Int = 2): Rect? {
        val bounds = contentBounds(pixels, size, threshold, margin) ?: return null
        val inset = min(min(bounds.left, bounds.top), min(size - bounds.right, size - bounds.bottom))
        return Rect(inset, inset, size - inset, size - inset)
    }

    private fun emptyMask(): Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8)
}

/**
 * The launcher's Nothing glyphs, one ALPHA_8 mask per package. Computed on the caller's thread -
 * the launcher prefetch and the warm-up effects call it from Dispatchers.IO - and cached so the
 * theme can flip between Nothing and the classics without re-rendering anything.
 */
object NothingIconCache {
    private const val MAX_ENTRIES = 96
    private val cache = LruCache<String, ImageBitmap>(MAX_ENTRIES)
    private val refreshedFlow = MutableStateFlow(0)

    /** Fires once more each time a glyph lands, so the launcher's tiles redraw. */
    val refreshed: StateFlow<Int> = refreshedFlow

    /** The cached mask, or null if the package's glyph has not been computed yet. */
    fun peek(packageName: String): ImageBitmap? = cache.get(packageName)

    /** The Nothing glyph for [packageName], computed on first request, then cached. Null when the
     * app has no icon or the pipeline cannot make a mask of it; the tile then shows the label only. */
    fun glyph(context: Context, packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        val drawable = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
            ?: return null
        val mask = NothingIconRenderer.render(drawable) ?: return null
        val image = mask.asImageBitmap()
        cache.put(packageName, image)
        refreshedFlow.value = refreshedFlow.value + 1
        return image
    }
}