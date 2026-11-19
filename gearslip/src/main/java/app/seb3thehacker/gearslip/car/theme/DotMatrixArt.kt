package app.seb3thehacker.gearslip.car.theme

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Album art as a coloured dot-matrix halftone, plus a radial dot pulse while the bitmap loads.
 *
 * The halftone follows udaign/matrices' "Value Aliasing": the image is sampled on a grid and each
 * cell becomes a dot whose radius tracks luminance. Dots keep the artwork's own colour, dimmed by
 * that luminance — quantising to a fixed palette made covers read as bright white/blue blocks
 * instead of their real colours. A gamma curve darkens midtones so the art stays calm on the
 * black car canvas. The loader follows lportals/dot_matrix_loader's CustomDotAnimation: a per-dot
 * state f(row, col, t) = {opacity, scale}.
 *
 * See NOTICE.md for attribution.
 */
@Composable
fun DotMatrixArt(
    bitmap: Bitmap?,
    modifier: Modifier,
    grid: Int = 30,
) {
    // Sample once per bitmap, not per recomposition/frame.
    val sampled = remember(bitmap, grid) {
        val source = bitmap ?: return@remember null
        val small = Bitmap.createScaledBitmap(source, grid, grid, true)
        val pixels = IntArray(grid * grid)
        small.getPixels(pixels, 0, grid, 0, 0, grid, grid)
        small.recycle()
        pixels
    }
    val infinite = rememberInfiniteTransition()
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
    )

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF050505))
        val side = minOf(w, h)
        if (sampled == null) {
            val n = 11
            val cell = side / n
            val base = cell * 0.34f
            val cx = (n - 1) / 2f
            val cy = (n - 1) / 2f
            for (row in 0 until n) {
                for (col in 0 until n) {
                    val dist = hypot(col - cx, row - cy)
                    val wave = (sin(dist * 1.5 - phase * 2 * Math.PI) + 1) / 2
                    drawCircle(
                        color = Color.White.copy(alpha = 0.12f + 0.88f * wave.toFloat()),
                        radius = base * (0.25f + 0.75f * wave).toFloat(),
                        center = Offset(col * cell + cell / 2, row * cell + cell / 2),
                    )
                }
            }
        } else {
            val cell = side / grid
            val base = cell * 0.52f
            for (y in 0 until grid) {
                for (x in 0 until grid) {
                    val p = sampled[y * grid + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val luminance = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                    // Gamma > 1 pushes midtones down, so pale artwork doesn't turn into a wall of
                    // bright dots. The dot keeps the source hue and is dimmed by its own brightness.
                    val v = Math.pow(luminance.toDouble(), 1.3).toFloat().coerceIn(0f, 1f)
                    if (v < 0.05f) continue
                    val shade = 0.30f + 0.60f * v
                    val color = Color(
                        red = r / 255f * shade,
                        green = g / 255f * shade,
                        blue = b / 255f * shade,
                    )
                    drawCircle(
                        color = color,
                        radius = base * v,
                        center = Offset(x * cell + cell / 2, y * cell + cell / 2),
                    )
                }
            }
        }
    }
}


