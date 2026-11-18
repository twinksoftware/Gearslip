package app.seb3thehacker.gearslip.car.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * How a control says it also does something when held. [GsIconBox] draws it on its own for any
 * control given an onLongClick, so callers never place it by hand.
 */
enum class HoldMarkerStyle {
    /** A plain dot in the top-right corner. It doesn't react to a hold. */
    DOT,

    /** The hold stays undiscovered. For controls that mark it some other way. */
    NONE,
}

/**
 * The hold marker for one control, drawn under its content. Round and pill-shaped controls get no
 * marker: a circle has no corner to tuck one into.
 */
private const val SHOW_HOLD_DOT = false

@Composable
internal fun BoxScope.GsHoldMarker(
    style: HoldMarkerStyle,
    @Suppress("UNUSED_PARAMETER") container: Color,
    shape: Shape,
    @Suppress("UNUSED_PARAMETER") press: InteractionSource,
) {
    if (style == HoldMarkerStyle.NONE || !SHOW_HOLD_DOT) return
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.matchParentSize()) {
        val side = min(size.width, size.height)
        val r = (shape as? CornerBasedShape)?.topEnd?.toPx(size, this) ?: 0f
        if (r < side * 0.4f) drawHoldDot(r, side, ink)
    }
}

private fun DrawScope.drawHoldDot(r: Float, side: Float, ink: Color) {
    val dot = (side * 0.06f).coerceIn(5.dp.toPx(), 7.dp.toPx())
    // Tucked into the corner, clear of its curve.
    val inset = r * (1f - 0.7071f) + (side * 0.05f).coerceIn(4.dp.toPx(), 6.dp.toPx()) + dot / 2
    drawCircle(ink.copy(alpha = 0.6f), radius = dot / 2, center = Offset(size.width - inset, inset))
}
