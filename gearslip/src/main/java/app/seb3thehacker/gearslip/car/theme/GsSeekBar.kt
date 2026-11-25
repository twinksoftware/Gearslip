package app.seb3thehacker.gearslip.car.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * A capsule progress bar: a tinted track with a glossy fill set inside it, lighter along the
 * top and darker along the bottom. Below its own height the fill narrows into a smaller oval, so
 * a countdown runs smoothly all the way to empty. The flat theme draws Material's wavy bar instead.
 */
@Composable
fun GsProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 22.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    /** Brightens the fill, for a bar that's being dragged. */
    active: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val shown = fraction.coerceIn(0f, 1f)
    val inset = 3.dp
    val fill = if (active) lerp(color, Color.White, 0.12f) else color
    if (LocalGsTheme.current.id == GsThemes.Flat.id) {
        WavyBar(shown, fill, lerp(scheme.surfaceContainerHighest, color, 0.12f), modifier.fillMaxWidth().height(height))
        return
    }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(lerp(scheme.surfaceContainerHighest, color, 0.12f))
            .padding(inset),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (shown <= 0f) return@BoxWithConstraints
        Box(
            Modifier
                .width(maxWidth * shown)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        0f to lerp(fill, Color.White, 0.30f),
                        0.55f to fill,
                        1f to lerp(fill, Color.Black, 0.22f),
                    ),
                )
                // The gloss: a soft white band across the top half.
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.35f),
                            0.5f to Color.White.copy(alpha = 0.05f),
                            0.5f to Color.Transparent,
                        ),
                    )
                },
        )
    }
}

/**
 * Material 3's wavy progress bar, drawn here because the stable library doesn't offer it yet: the
 * filled part is a sine wave, then a gap, then a straight track and a stop dot at the end.
 *
 * The wave holds still. Drifting, it changed the car screen every frame for as long as music
 * played, so the phone drew, encoded, encrypted and sent 30 to 60 frames a second where a still
 * screen needs about 10 small ones, and phones ran warm.
 */
@Composable
private fun WavyBar(fraction: Float, color: Color, track: Color, modifier: Modifier) {
    val phase = 0f
    Canvas(modifier) {
        val stroke = 4.dp.toPx()
        val amplitude = (size.height / 2 - stroke).coerceIn(0f, 3.dp.toPx())
        val wavelength = 32.dp.toPx()
        val gap = 6.dp.toPx()
        val mid = size.height / 2
        val start = stroke / 2
        val end = size.width - stroke / 2
        val split = start + (end - start) * fraction

        if (split > start) {
            val wave = Path()
            var x = start
            val shift = phase * wavelength
            while (x <= split) {
                val y = mid + amplitude * sin(2 * Math.PI * (x - shift) / wavelength).toFloat()
                if (x == start) wave.moveTo(x, y) else wave.lineTo(x, y)
                x += 1f
            }
            drawPath(wave, color, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        val trackStart = if (split > start) split + gap + stroke else start
        if (trackStart < end) {
            drawLine(track, Offset(trackStart, mid), Offset(end, mid), stroke, StrokeCap.Round)
        }
        drawCircle(color, stroke / 2, Offset(end, mid))
    }
}

/**
 * [GsProgressBar] that seeks: tapping or dragging moves it. The position is committed on release
 * rather than on every pixel of a drag, because each commit is a request to the media app.
 */
@Composable
fun GsSeekBar(
    fraction: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    // A taller touch area than the bar itself, so it's easy to hit on a car screen.
    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = { dragging?.let(onSeek); dragging = null },
                            onDragCancel = { dragging = null },
                        ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
                    },
            ),
        contentAlignment = Alignment.Center,
    ) {
        GsProgressBar(dragging ?: fraction, active = dragging != null)
    }
}
