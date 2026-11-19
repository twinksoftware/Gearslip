package app.seb3thehacker.gearslip.car.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** A seekable row of dots: played dots are Nothing Red, the head glows, the rest are dim. */
@Composable
fun DotSeekBar(
    fraction: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier,
    count: Int = 46,
    enabled: Boolean = true,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)
    Canvas(
        modifier
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = { dragging?.let(onSeek); dragging = null },
                            onDragCancel = { dragging = null },
                        ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
                    }
            ),
    ) {
        val r = 3.dp.toPx()
        val step = size.width / count
        val filled = (shown * count).toInt()
        val cy = size.height / 2
        for (i in 0 until count) {
            val cx = step * (i + 0.5f)
            when {
                i < filled - 1 -> drawCircle(Color(0xFFFF3030), r, Offset(cx, cy))
                i == filled - 1 -> drawCircle(Color(0xFFFF3030), r * 1.5f, Offset(cx, cy))
                else -> drawCircle(Color(0xFF2E2E2E), r, Offset(cx, cy))
            }
        }
    }
}
