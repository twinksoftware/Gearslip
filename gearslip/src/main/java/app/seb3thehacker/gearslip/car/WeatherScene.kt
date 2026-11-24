package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** What the sky is doing, from Open-Meteo's WMO weather code. */
enum class SkyScene { CLEAR, PARTLY_CLOUDY, OVERCAST, FOG, DRIZZLE, RAIN, SNOW, STORM }

fun skySceneOf(code: Int): SkyScene = when (code) {
    0, 1 -> SkyScene.CLEAR
    2 -> SkyScene.PARTLY_CLOUDY
    3 -> SkyScene.OVERCAST
    45, 48 -> SkyScene.FOG
    in 51..57 -> SkyScene.DRIZZLE
    in 61..67, in 80..82 -> SkyScene.RAIN
    in 71..77, 85, 86 -> SkyScene.SNOW
    in 95..99 -> SkyScene.STORM
    else -> SkyScene.PARTLY_CLOUDY
}

/**
 * The weather outside, drawn live behind the Apps page: a sky for the time of day and the
 * conditions, with sun rays, stars, clouds, rain, snow, fog or lightning moving over it.
 *
 * Every particle's place is worked out from the clock and its own fixed seed, so nothing is
 * stored or allocated per frame, and only the drawing reruns each frame, not the composition.
 * [dark] follows the car's dark mode: the same scene, dimmer, so it doesn't glare at night.
 */
@Composable
fun WeatherScene(scene: SkyScene, isDay: Boolean, dark: Boolean, modifier: Modifier = Modifier) {
    if (shaderSkyAvailable) ShaderWeatherScene(scene, isDay, dark, modifier)
    else CanvasWeatherScene(scene, isDay, dark, modifier)
}

/** A WMO code that stands for [scene], for the demo's weather icon. */
fun codeOf(scene: SkyScene): Int = when (scene) {
    SkyScene.CLEAR -> 0
    SkyScene.PARTLY_CLOUDY -> 2
    SkyScene.OVERCAST -> 3
    SkyScene.FOG -> 45
    SkyScene.DRIZZLE -> 53
    SkyScene.RAIN -> 63
    SkyScene.SNOW -> 73
    SkyScene.STORM -> 95
}

/** Android 12's version: the same scenes drawn shape by shape, without the graphics chip's help. */
@Composable
private fun CanvasWeatherScene(scene: SkyScene, isDay: Boolean, dark: Boolean, modifier: Modifier = Modifier) {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time.floatValue = (it - start) / 1_000_000_000f }
    }
    val seeds = remember { Seeds() }
    Canvas(modifier) {
        val t = time.floatValue
        drawSky(scene, isDay, dark)
        when (scene) {
            SkyScene.CLEAR -> if (isDay) drawSun(t, 1f) else drawNight(t, seeds, 1f, dark)
            SkyScene.PARTLY_CLOUDY -> {
                if (isDay) drawSun(t, 0.7f) else drawNight(t, seeds, 0.6f, dark)
                drawClouds(t, seeds, count = 4, opacity = cloudAlpha(isDay, dark) * 0.8f)
            }
            SkyScene.OVERCAST -> drawClouds(t, seeds, count = 7, opacity = cloudAlpha(isDay, dark))
            SkyScene.FOG -> drawFog(t, isDay, dark)
            SkyScene.DRIZZLE -> {
                drawClouds(t, seeds, count = 6, opacity = cloudAlpha(isDay, dark))
                drawRain(t, seeds, count = 45, speed = 0.55f, length = 0.025f, dark = dark)
            }
            SkyScene.RAIN -> {
                drawClouds(t, seeds, count = 7, opacity = cloudAlpha(isDay, dark))
                drawRain(t, seeds, count = 110, speed = 1f, length = 0.05f, dark = dark)
            }
            SkyScene.SNOW -> {
                drawClouds(t, seeds, count = 5, opacity = cloudAlpha(isDay, dark) * 0.8f)
                drawSnow(t, seeds)
            }
            SkyScene.STORM -> {
                drawClouds(t, seeds, count = 8, opacity = cloudAlpha(isDay, dark) * 1.2f)
                drawRain(t, seeds, count = 140, speed = 1.3f, length = 0.06f, dark = dark)
                drawLightning(t)
            }
        }
    }
}

/** How far dark mode takes the sky toward black. */
private const val SKY_DIM = 0.62f

/** Fixed random starting points for every particle, made once. */
private class Seeds {
    private val random = Random(124)
    val x = FloatArray(MAX) { random.nextFloat() }
    val y = FloatArray(MAX) { random.nextFloat() }
    val speed = FloatArray(MAX) { 0.6f + random.nextFloat() * 0.8f }
    val size = FloatArray(MAX) { random.nextFloat() }
    val phase = FloatArray(MAX) { random.nextFloat() * 2f * PI.toFloat() }

    companion object { const val MAX = 160 }
}

/** The fractional part, always in 0..1, so particles wrap around the screen. */
private fun wrap(v: Float): Float = v - kotlin.math.floor(v)

private fun cloudAlpha(isDay: Boolean, dark: Boolean): Float = when {
    dark -> 0.10f
    isDay -> 0.35f
    else -> 0.14f
}

private fun DrawScope.drawSky(scene: SkyScene, isDay: Boolean, dark: Boolean) {
    val (top, bottom) = when {
        !isDay -> Color(0xFF0B1230) to Color(0xFF1E2A4A)
        scene == SkyScene.CLEAR -> Color(0xFF2F74D0) to Color(0xFF8CC4F5)
        scene == SkyScene.PARTLY_CLOUDY -> Color(0xFF3F7CC4) to Color(0xFF9BC2E6)
        scene == SkyScene.STORM -> Color(0xFF2C3440) to Color(0xFF4A5563)
        scene == SkyScene.SNOW || scene == SkyScene.FOG -> Color(0xFF8E9AA8) to Color(0xFFC9D1DA)
        else -> Color(0xFF5B6E84) to Color(0xFF93A3B5)
    }
    // Dark mode keeps the scene's colours but takes them most of the way to black.
    val dim = if (dark) SKY_DIM else 0f
    drawRect(Brush.verticalGradient(listOf(lerp(top, Color.Black, dim), lerp(bottom, Color.Black, dim))))
}

private fun DrawScope.drawSun(t: Float, strength: Float) {
    val center = Offset(size.width * 0.82f, size.height * 0.2f)
    val radius = size.minDimension * 0.11f
    drawCircle(
        Brush.radialGradient(listOf(Color(0x66FFE9A8), Color(0x00FFE9A8)), center, radius * 4.5f),
        radius * 4.5f, center, alpha = strength,
    )
    // Long soft rays, turning about once a minute.
    rotate(t * 6f, center) {
        for (i in 0 until 12) {
            rotate(i * 30f, center) {
                drawLine(
                    Color(0x22FFF4D0), center, Offset(center.x, center.y - radius * 4f),
                    strokeWidth = radius * 0.5f, cap = StrokeCap.Round, alpha = strength,
                )
            }
        }
    }
    drawCircle(Color(0xFFFFE59A), radius, center, alpha = strength)
}

private fun DrawScope.drawNight(t: Float, seeds: Seeds, strength: Float, dark: Boolean) {
    for (i in 0 until 70) {
        val twinkle = 0.45f + 0.55f * ((sin(t * (0.6f + seeds.speed[i]) + seeds.phase[i]) + 1f) / 2f)
        drawCircle(
            Color.White,
            radius = 0.6f + seeds.size[i] * 1.6f,
            center = Offset(seeds.x[i] * size.width, seeds.y[i] * size.height * 0.75f),
            alpha = twinkle * 0.8f * strength,
        )
    }
    // A crescent: a pale disc with the sky's colour taken out of one side.
    val moon = Offset(size.width * 0.8f, size.height * 0.2f)
    val r = size.minDimension * 0.07f
    drawCircle(Color(0x33E8ECFF), r * 3f, moon, alpha = strength)
    drawCircle(Color(0xFFEDEFF7), r, moon, alpha = strength)
    drawCircle(lerp(Color(0xFF14204A), Color.Black, if (dark) SKY_DIM else 0f), r * 0.92f, Offset(moon.x + r * 0.45f, moon.y - r * 0.2f), alpha = strength)
}

private fun DrawScope.drawClouds(t: Float, seeds: Seeds, count: Int, opacity: Float) {
    for (i in 0 until count) {
        val drift = t * 0.008f * seeds.speed[i]
        val w = size.width * (0.35f + seeds.size[i] * 0.3f)
        val x = wrap(seeds.x[i] + drift) * (size.width + w) - w / 2
        val y = size.height * (0.08f + seeds.y[i] * 0.55f)
        drawCloud(Offset(x, y), w, opacity.coerceAtMost(1f))
    }
}

/** A cloud from overlapping circles, flatter at the bottom. */
private fun DrawScope.drawCloud(at: Offset, width: Float, alpha: Float) {
    val c = Color.White
    val r = width * 0.22f
    drawCircle(c, r, Offset(at.x - width * 0.22f, at.y + r * 0.2f), alpha = alpha)
    drawCircle(c, r * 1.35f, Offset(at.x, at.y - r * 0.15f), alpha = alpha)
    drawCircle(c, r * 1.05f, Offset(at.x + width * 0.24f, at.y + r * 0.1f), alpha = alpha)
    drawRect(c, Offset(at.x - width * 0.22f, at.y + r * 0.2f), Size(width * 0.46f, r * 1.0f), alpha = alpha)
}

private fun DrawScope.drawRain(t: Float, seeds: Seeds, count: Int, speed: Float, length: Float, dark: Boolean) {
    val slant = size.height * 0.12f
    val color = if (dark) Color(0x88B8C8E0) else Color(0xAAE6F0FF)
    for (i in 0 until count.coerceAtMost(Seeds.MAX)) {
        val fall = wrap(seeds.y[i] + t * 0.9f * speed * seeds.speed[i])
        val y = fall * (size.height * (1f + length)) - size.height * length
        val x = wrap(seeds.x[i] + fall * slant / size.width) * size.width
        val len = size.height * length * (0.6f + seeds.size[i] * 0.6f)
        drawLine(color, Offset(x, y), Offset(x - slant * len / size.height, y + len), strokeWidth = 1.6f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawSnow(t: Float, seeds: Seeds) {
    for (i in 0 until 90) {
        val fall = wrap(seeds.y[i] + t * 0.06f * seeds.speed[i])
        val sway = sin(t * 0.8f * seeds.speed[i] + seeds.phase[i]) * size.width * 0.015f
        val x = wrap(seeds.x[i]) * size.width + sway
        drawCircle(Color.White, 1.5f + seeds.size[i] * 3f, Offset(x, fall * size.height), alpha = 0.55f + seeds.size[i] * 0.35f)
    }
}

private fun DrawScope.drawFog(t: Float, isDay: Boolean, dark: Boolean) {
    val alpha = when {
        dark -> 0.08f
        isDay -> 0.28f
        else -> 0.12f
    }
    for (i in 0 until 6) {
        val y = size.height * (0.15f + i * 0.14f)
        val drift = sin(t * 0.05f + i) * size.width * 0.08f
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White, Color.White, Color.Transparent),
                startX = -size.width * 0.2f + drift, endX = size.width * 1.2f + drift,
            ),
            Offset(0f, y), Size(size.width, size.height * 0.07f), alpha = alpha,
        )
    }
}

/** A flash every eight seconds or so, sharp and then fading, with a weaker second flicker. */
private fun DrawScope.drawLightning(t: Float) {
    val cycle = t % 8f
    val flash = when {
        cycle < 0.12f -> 1f - cycle / 0.12f
        cycle in 0.25f..0.33f -> 0.5f * (1f - (cycle - 0.25f) / 0.08f)
        else -> 0f
    }
    if (flash > 0f) drawRect(Color.White, alpha = flash * 0.35f)
}
