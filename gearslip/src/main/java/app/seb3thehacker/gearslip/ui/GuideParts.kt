package app.seb3thehacker.gearslip.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.CompositionLocalProvider
import app.seb3thehacker.gearslip.car.theme.GsButton
import app.seb3thehacker.gearslip.car.theme.GsIconButton
import app.seb3thehacker.gearslip.car.theme.GsThemes
import app.seb3thehacker.gearslip.car.theme.GsTone
import app.seb3thehacker.gearslip.car.theme.LocalGsTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * The setup guide's tones, taken from the phone's own Material You colours like the rest of the app.
 * Every size is taken from the screen's width, so the guide scales from small phones to tablets.
 */
internal class IntroColors(
    val surface: Color, val onSurface: Color, val onVariant: Color, val outline: Color,
    val primary: Color, val onPrimary: Color, val primaryC: Color, val onPrimaryC: Color,
    val tertiaryC: Color, val onTertiaryC: Color, val secondaryC: Color, val onSecondaryC: Color,
)

@Composable
internal fun introColors(): IntroColors {
    val m = MaterialTheme.colorScheme
    return IntroColors(
        surface = m.surface, onSurface = m.onSurface, onVariant = m.onSurfaceVariant, outline = m.outline,
        primary = m.primary, onPrimary = m.onPrimary, primaryC = m.primaryContainer, onPrimaryC = m.onPrimaryContainer,
        tertiaryC = m.tertiaryContainer, onTertiaryC = m.onTertiaryContainer,
        secondaryC = m.secondaryContainer, onSecondaryC = m.onSecondaryContainer,
    )
}

private val Pop = CubicBezierEasing(0.3f, 1.6f, 0.5f, 1f)
private val Rise = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** 0 to 1 once each time [key] changes, after [delayMs]: drives the entrance of one element. */
@Composable
internal fun entrance(key: Any, delayMs: Int, durationMs: Int = 650): Float {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(delayMs.toLong())
        a.animateTo(1f, tween(durationMs, easing = LinearEasing))
    }
    return a.value
}

/** Slides up and fades in, like the mockup's `up`. */
internal fun Modifier.up(p: Float, distance: Dp) = graphicsLayer {
    val e = Rise.transform(p)
    alpha = e
    translationY = (1 - e) * distance.toPx()
}

/** Grows in with a bounce, like the mockup's `bounce`. */
internal fun Modifier.pop(p: Float) = graphicsLayer {
    val e = Pop.transform(p)
    alpha = p.coerceAtMost(1f)
    scaleX = 0.5f + 0.5f * e
    scaleY = 0.5f + 0.5f * e
}

/**
 * Nothing is the only look now, so there is no choice to make here: a row of real car controls
 * drawn in the same skin the car uses.
 */
@Composable
internal fun ThemePicker(c: IntroColors, cq: (Float) -> Dp, cqSp: (Float) -> TextUnit, key: Any) {
    Column(
        Modifier.padding(top = cq(7f)).fillMaxWidth().up(entrance(key, 500, 700), cq(7f)),
    ) {
        CompositionLocalProvider(LocalGsTheme provides GsThemes.Nothing) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(cq(3f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GsIconButton(Icons.Filled.Search, "Search", onClick = {}, size = cq(15f), iconSize = cq(7f))
                GsIconButton(Icons.Filled.PlayArrow, "Play", onClick = {}, tone = GsTone.Primary, size = cq(15f), iconSize = cq(8f))
                GsButton(
                    onClick = {},
                    modifier = Modifier.weight(1f).height(cq(15f)),
                    tone = GsTone.Tonal,
                ) { Text("Navigate", style = TextStyle(fontSize = cqSp(4.6f), fontWeight = FontWeight.Bold)) }
            }
        }
    }
}

/** Three keys of a keypad, pressing one after another. */
@Composable
internal fun ButtonsIcon(color: Color) {
    val t by rememberInfiniteTransition(label = "keys").animateFloat(
        0f, 3f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "keys",
    )
    Canvas(Modifier.fillMaxSize()) {
        inIconBox {
            listOf(6f, 19f, 32f).forEachIndexed { i, x ->
                // Each key dips for a moment while its turn comes round.
                val dip = (1f - kotlin.math.abs(t - i - 0.5f) * 2f).coerceIn(0f, 1f) * 4f
                drawRoundRect(color.copy(alpha = 0.45f), Offset(x, 16f + 4f), Size(10f, 16f),
                    androidx.compose.ui.geometry.CornerRadius(3f))
                drawRoundRect(color, Offset(x, 16f + dip), Size(10f, 16f), androidx.compose.ui.geometry.CornerRadius(3f))
            }
        }
    }
}

/** Material's scalloped "cookie": a circle whose radius waves around its edge. */
@Composable
internal fun Cookie(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 100f
        val path = Path()
        var deg = 0
        while (deg <= 360) {
            val a = deg * PI / 180
            val r = (44 + 4.5 * cos(9 * a)) * unit
            val x = center.x + (r * cos(a)).toFloat()
            val y = center.y + (r * sin(a)).toFloat()
            if (deg == 0) path.moveTo(x, y) else path.lineTo(x, y)
            deg += 3
        }
        path.close()
        drawPath(path, color)
    }
}

/** Draws in the mockup's 48-unit icon box. */
private inline fun DrawScope.inIconBox(block: DrawScope.() -> Unit) {
    val s = size.minDimension / 48f
    scale(s, s, Offset.Zero) { block() }
}

@Composable
internal fun WifiIcon(color: Color) {
    val t = rememberInfiniteTransition(label = "wifi")
    val waves = (0..2).map { i ->
        t.animateFloat(
            0.35f, 1f,
            infiniteRepeatable(tween(800, delayMillis = 0, easing = FastOutSlowInEasing), RepeatMode.Reverse,
                initialStartOffset = androidx.compose.animation.core.StartOffset(i * 200)),
            label = "wave$i",
        )
    }
    Canvas(Modifier.fillMaxSize()) {
        inIconBox {
            val stroke = Stroke(5f, cap = StrokeCap.Round)
            // The mockup's three arcs, inner to outer: radius r through (24 ± half, y).
            listOf(Triple(8f, 6f, 34f), Triple(18f, 13f, 26f), Triple(28f, 20f, 18f)).forEachIndexed { i, (r, half, y) ->
                val cy = y + sqrt(r * r - half * half)
                val start = Math.toDegrees(atan2((y - cy).toDouble(), (-half).toDouble())).toFloat()
                val end = Math.toDegrees(atan2((y - cy).toDouble(), half.toDouble())).toFloat()
                drawArc(color.copy(alpha = waves[i].value), start, (end - start + 360f) % 360f, false,
                    Offset(24f - r, cy - r), Size(2 * r, 2 * r), style = stroke)
            }
            drawCircle(color, 3.6f, Offset(24f, 41f))
        }
    }
}

@Composable
internal fun MicIcon(color: Color) {
    val t = rememberInfiniteTransition(label = "mic")
    val waves = (0..1).map { i ->
        t.animateFloat(
            0.25f, 1f,
            infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse,
                initialStartOffset = androidx.compose.animation.core.StartOffset(i * 250)),
            label = "mic$i",
        )
    }
    Canvas(Modifier.fillMaxSize()) {
        inIconBox {
            drawRoundRect(color, Offset(18f, 5f), Size(12f, 22f), androidx.compose.ui.geometry.CornerRadius(6f))
            val stroke = Stroke(4f, cap = StrokeCap.Round)
            drawArc(color, 0f, 180f, false, Offset(12f, 14f), Size(24f, 22f), style = stroke)
            drawLine(color, Offset(24f, 36f), Offset(24f, 42f), 4f, StrokeCap.Round)
            // Sound waves either side, pulsing as if someone's talking.
            listOf(1f to 0, -1f to 0, 1f to 1, -1f to 1).forEach { (side, i) ->
                val r = if (i == 0) 15f else 21f
                drawArc(color.copy(alpha = waves[i].value), if (side > 0) -35f else 145f, 70f, false,
                    Offset(24f - r, 16f - r), Size(2 * r, 2 * r), style = stroke)
            }
        }
    }
}

@Composable
internal fun StarIcon(color: Color) {
    val turn by rememberInfiniteTransition(label = "star").animateFloat(
        -8f, 8f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "star",
    )
    Canvas(Modifier.fillMaxSize()) {
        inIconBox {
            val star = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 20f else 8.5f
                val a = (-90 + i * 36) * PI / 180
                val x = 24f + (r * cos(a)).toFloat()
                val y = 25f + (r * sin(a)).toFloat()
                if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
            }
            star.close()
            rotate(turn, Offset(24f, 25f)) { drawPath(star, color) }
        }
    }
}

@Composable
internal fun HeartIcon(color: Color) {
    val beat by rememberInfiniteTransition(label = "beat").animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes {
            durationMillis = 1400
            1f at 0; 1.16f at 210; 1f at 420; 1.1f at 630; 1f at 840
        }),
        label = "beat",
    )
    Canvas(Modifier.fillMaxSize()) {
        inIconBox {
            scale(beat, beat, Offset(24f, 24f)) {
                val heart = Path().apply {
                    moveTo(24f, 41f)
                    cubicTo(10f, 31f, 5f, 23f, 5f, 16f)
                    cubicTo(5f, 10f, 10f, 6f, 15f, 6f)
                    cubicTo(19f, 6f, 22f, 8f, 24f, 12f)
                    cubicTo(26f, 8f, 29f, 6f, 33f, 6f)
                    cubicTo(38f, 6f, 43f, 10f, 43f, 16f)
                    cubicTo(43f, 23f, 38f, 31f, 24f, 41f)
                    close()
                }
                drawPath(heart, color)
            }
        }
    }
}

@Composable
internal fun Dots(c: IntroColors, count: Int, current: Int, cq: (Float) -> Dp, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(cq(2f))) {
        repeat(count) { i ->
            val on = i == current
            val width by animateDpAsState(if (on) cq(7f) else cq(2f), tween(300), label = "dot")
            Box(
                Modifier
                    .size(width, cq(2f))
                    .clip(RoundedCornerShape(50))
                    .background(if (on) c.primary else c.outline.copy(alpha = 0.45f)),
            )
        }
    }
}

