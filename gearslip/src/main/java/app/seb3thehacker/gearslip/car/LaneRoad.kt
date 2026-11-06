package app.seb3thehacker.gearslip.car

import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The dark band the lanes sit on under the turn card's header, tinted toward the theme. */
@Composable
internal fun laneBandColor(): Color = lerp(Color(0xFF0E1417), MaterialTheme.colorScheme.primary, 0.14f)

/**
 * The lanes before a manoeuvre as a dark band under the turn card's header: the road ahead in
 * perspective, with the lane or lanes to be in filled with the header's colour.
 */
@Composable
internal fun LaneBand(lanes: List<Lane>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val band = laneBandColor()
    LaneRoad(
        lanes,
        modifier.background(band).padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(52.dp),
        scheme.primaryContainer,
        scheme.onPrimaryContainer,
        band,
    )
}

/** Which lanes to use, in words, on the same band: "Use the left lane". Nothing when it can't say. */
@Composable
internal fun LaneHintLine(lanes: List<Lane>, modifier: Modifier = Modifier) {
    val hint = laneHint(lanes) ?: return
    Text(
        hint,
        modifier.background(laneBandColor()).padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        style = ChromeType.label,
        color = Color.White,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The road ahead, narrowing toward the top: solid edges, dashed lines between lanes, the
 * recommended lanes filled with [fill] and every lane's arrows drawn from its directions, the
 * recommended ones in [onFill] and the rest faded toward [ground], the colour behind the road.
 * The arrows are painted on the road, so they narrow with it. Lanes run left to right, as the app
 * sends them.
 */
@Composable
internal fun LaneRoad(lanes: List<Lane>, modifier: Modifier, fill: Color, onFill: Color, ground: Color, line: Color = Color.White) {
    val named = lanes.map {
        stringResource(if (it.recommended()) R.string.nav_lane_recommended else R.string.nav_lane)
    }.joinToString(", ")
    Canvas(modifier.semantics { contentDescription = named }) {
        val n = lanes.size
        if (n == 0) return@Canvas
        val h = size.height
        // Each lane at most a little wider than the road is tall, so two lanes don't fill a whole card.
        val bottomWidth = minOf(size.width, h * 1.6f * n)
        val topWidth = bottomWidth * 0.86f
        fun width(y: Float) = topWidth + (bottomWidth - topWidth) * (y / h)
        // The x of lane edge [edge] (0 to n, fractions for the middle of a lane) at height [y].
        fun x(edge: Float, y: Float): Float = size.width / 2 - width(y) / 2 + width(y) * edge / n
        lanes.forEachIndexed { i, lane ->
            if (!lane.recommended()) return@forEachIndexed
            drawPath(
                Path().apply {
                    moveTo(x(i.toFloat(), 0f), 0f)
                    lineTo(x(i + 1f, 0f), 0f)
                    lineTo(x(i + 1f, h), h)
                    lineTo(x(i.toFloat(), h), h)
                    close()
                },
                fill,
            )
        }
        val edge = 2.dp.toPx()
        drawLine(line, Offset(x(0f, 0f), 0f), Offset(x(0f, h), h), edge)
        drawLine(line, Offset(x(n.toFloat(), 0f), 0f), Offset(x(n.toFloat(), h), h), edge)
        val dashes = PathEffect.dashPathEffect(floatArrayOf(h * 0.22f, h * 0.14f))
        for (i in 1 until n) {
            drawLine(lerp(ground, line, 0.7f), Offset(x(i.toFloat(), 0f), 0f), Offset(x(i.toFloat(), h), h), edge, pathEffect = dashes)
        }

        val top = h * 0.1f
        val bottom = h * 0.9f
        val half = minOf(bottomWidth / n * 0.34f, h * 0.4f)
        lanes.forEachIndexed { i, lane ->
            // A point [u] across from the lane's middle, measured as at the bottom, at height [y].
            val onRoad = { u: Float, y: Float -> Offset(x(i + 0.5f, y) + u * width(y) / bottomWidth, y) }
            val stroke = half * 0.24f * width((top + bottom) / 2) / bottomWidth
            val directions = lane.directions.orEmpty().ifEmpty { listOf(null) }
            val behind = if (lane.recommended()) fill else ground
            val on = if (lane.recommended()) onFill else line
            // Solid colours, faded by mixing rather than transparency, so where a stem and a head
            // overlap nothing shows through twice. Faded first, so the one to take draws on top.
            directions.sortedBy { it?.isRecommended == true }.forEach { d ->
                val color = if (d?.isRecommended == true) on else lerp(behind, on, 0.38f)
                laneArrow(d?.shape ?: LaneDirection.SHAPE_STRAIGHT, onRoad, top, bottom, half, stroke, color)
            }
        }
    }
}

/**
 * One direction's arrow inside a lane: a stem up from [bottom] that carries on straight, bends
 * off to a side, or loops back for a U-turn, with a head at the end. Laid out flat, [half] being
 * half the room across the lane, then put on the road by [onRoad]. A lane's arrows share a stem.
 */
private fun DrawScope.laneArrow(
    shape: Int,
    onRoad: (Float, Float) -> Offset,
    top: Float,
    bottom: Float,
    half: Float,
    stroke: Float,
    color: Color,
) {
    val head = half * 0.24f * 1.9f
    val path = Path()
    fun Path.to(p: Offset) = onRoad(p.x, p.y).let { lineTo(it.x, it.y) }
    fun Path.curve(c: Offset, p: Offset) {
        val a = onRoad(c.x, c.y)
        val b = onRoad(p.x, p.y)
        quadraticTo(a.x, a.y, b.x, b.y)
    }
    onRoad(0f, bottom).let { path.moveTo(it.x, it.y) }
    val tip: Offset
    val dir: Offset
    val degrees = when (shape) {
        LaneDirection.SHAPE_SLIGHT_LEFT -> -40f
        LaneDirection.SHAPE_SLIGHT_RIGHT -> 40f
        LaneDirection.SHAPE_NORMAL_LEFT -> -90f
        LaneDirection.SHAPE_NORMAL_RIGHT -> 90f
        LaneDirection.SHAPE_SHARP_LEFT -> -135f
        LaneDirection.SHAPE_SHARP_RIGHT -> 135f
        LaneDirection.SHAPE_U_TURN_LEFT -> -180f
        LaneDirection.SHAPE_U_TURN_RIGHT -> 180f
        else -> 0f
    }
    val side = if (degrees < 0) -1f else 1f
    when {
        degrees == 0f -> {
            path.to(Offset(0f, top + head))
            tip = Offset(0f, top)
            dir = Offset(0f, -1f)
        }
        abs(degrees) == 180f -> {
            // Up, over the top and back down beside the stem.
            val r = half * 0.45f
            val back = side * 2 * r
            path.to(Offset(0f, top + r))
            path.curve(Offset(0f, top), Offset(side * r, top))
            path.curve(Offset(back, top), Offset(back, top + r))
            val end = top + (bottom - top) * 0.55f
            path.to(Offset(back, end))
            tip = Offset(back, end + head)
            dir = Offset(0f, 1f)
        }
        else -> {
            val rad = degrees / 180f * PI.toFloat()
            val d = Offset(sin(rad), -cos(rad))
            val fork = top + (bottom - top) * 0.5f
            val length = minOf((bottom - top) * 0.42f, (half - head) / abs(d.x))
            val bend = Offset(0f, fork - length * 0.45f)
            val end = bend + d * length
            path.to(Offset(0f, fork))
            path.curve(bend, end)
            tip = end + d * head
            dir = d
        }
    }
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val across = Offset(-dir.y, dir.x) * (head * 0.85f)
    val base = tip - dir * (head * 1.6f)
    val corners = listOf(tip, base + across, base - across).map { onRoad(it.x, it.y) }
    drawPath(
        Path().apply {
            moveTo(corners[0].x, corners[0].y)
            lineTo(corners[1].x, corners[1].y)
            lineTo(corners[2].x, corners[2].y)
            close()
        },
        color,
    )
}

/**
 * Which lanes to be in, in words, when the recommended ones sit together: the left or right
 * lanes, the middle one, a lane counted from the nearer side, or any. Null when nothing is
 * recommended or the lanes to use aren't next to each other.
 */
@Composable
internal fun laneHint(lanes: List<Lane>): String? {
    val n = lanes.size
    val use = lanes.indices.filter { lanes[it].recommended() }
    if (n < 2 || use.isEmpty()) return null
    if (use.size == n) return stringResource(R.string.nav_lane_any)
    if (use.last() - use.first() + 1 != use.size) return null
    val resources = LocalContext.current.resources
    val k = use.size
    val only = use.first()
    return when {
        only == 0 -> resources.getQuantityString(R.plurals.nav_lane_left, k, k)
        use.last() == n - 1 -> resources.getQuantityString(R.plurals.nav_lane_right, k, k)
        k == 1 && n % 2 == 1 && only == n / 2 -> stringResource(R.string.nav_lane_middle)
        k == 1 && only < n - 1 - only -> stringResource(R.string.nav_lane_from_left, only + 1)
        k == 1 -> stringResource(R.string.nav_lane_from_right, n - only)
        else -> stringResource(R.string.nav_lane_middle_lanes)
    }
}

private fun Lane.recommended(): Boolean = directions.orEmpty().any { it.isRecommended }
