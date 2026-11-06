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

        val top = h * 0.12f
        val bottom = h * 0.9f
        // Half the room across a lane the arrows may use, as at the bottom: well inside the lines
        // even where the lane narrows at the top.
        val room = bottomWidth / n * 0.31f
        lanes.forEachIndexed { i, lane ->
            val directions = lane.directions.orEmpty().ifEmpty { listOf(null) }
            // Faded first, so the one to take draws on top where they share a stem.
            val arrows = directions.sortedBy { it?.isRecommended == true }
                .map { it to laneArrow(it?.shape ?: LaneDirection.SHAPE_STRAIGHT, top, bottom) }
            // The lane's arrows as one shape: centred in the lane, and shrunk toward the stem's
            // foot when they'd reach past [room].
            val xs = arrows.flatMap { (_, a) -> a.line.map { it.x } + a.head.map { it.x } }
            val shift = -(xs.min() + xs.max()) / 2
            val scale = minOf(1f, room / ((xs.max() - xs.min()) / 2))
            val lift = (bottom - top) * (1 - scale) / 2
            // Flat to the road: centred, scaled, then narrowed with the lane toward the top.
            val onRoad = { p: Offset ->
                val y = bottom - (bottom - p.y) * scale - lift
                Offset(x(i + 0.5f, y) + (p.x + shift) * scale * width(y) / bottomWidth, y)
            }
            val stroke = (bottom - top) * 0.085f * scale * width((top + bottom) / 2) / bottomWidth
            val behind = if (lane.recommended()) fill else ground
            val on = if (lane.recommended()) onFill else line
            arrows.forEach { (d, arrow) ->
                // Solid colours, faded by mixing rather than transparency, so where a stem runs
                // into its head nothing shows through twice.
                val color = if (d?.isRecommended == true) on else lerp(behind, on, 0.38f)
                val points = arrow.line.map(onRoad)
                drawPath(
                    Path().apply {
                        moveTo(points[0].x, points[0].y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                    },
                    color,
                    style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                val corners = arrow.head.map(onRoad)
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
        }
    }
}

/** An arrow laid out flat: the stem as points, and the head's tip and two back corners. */
private class LaneArrow(val line: List<Offset>, val head: List<Offset>)

/**
 * One direction's arrow, flat, its stem rising from x 0 at [bottom]: on straight to [top], bending
 * off to a side from a little above the middle, or looping back for a U-turn. A lane's arrows
 * share the stem, so a straight-and-right lane reads as one fork.
 */
private fun laneArrow(shape: Int, top: Float, bottom: Float): LaneArrow {
    val tall = bottom - top
    val head = tall * 0.17f
    val start = Offset(0f, bottom)
    val degrees = when (shape) {
        LaneDirection.SHAPE_SLIGHT_LEFT -> -45f
        LaneDirection.SHAPE_SLIGHT_RIGHT -> 45f
        LaneDirection.SHAPE_NORMAL_LEFT -> -90f
        LaneDirection.SHAPE_NORMAL_RIGHT -> 90f
        LaneDirection.SHAPE_SHARP_LEFT -> -135f
        LaneDirection.SHAPE_SHARP_RIGHT -> 135f
        LaneDirection.SHAPE_U_TURN_LEFT -> -180f
        LaneDirection.SHAPE_U_TURN_RIGHT -> 180f
        else -> 0f
    }
    val side = if (degrees < 0) -1f else 1f
    val line = mutableListOf(start)
    val end: Offset
    val dir: Offset
    when {
        degrees == 0f -> {
            end = Offset(0f, top + head)
            line += end
            dir = Offset(0f, -1f)
        }
        abs(degrees) == 180f -> {
            // Up, over the top and back down beside the stem.
            val r = tall * 0.2f
            line += Offset(0f, top + r)
            line += curve(Offset(0f, top + r), Offset(0f, top), Offset(side * r, top))
            line += curve(Offset(side * r, top), Offset(side * 2 * r, top), Offset(side * 2 * r, top + r))
            end = Offset(side * 2 * r, top + tall * 0.5f)
            line += end
            dir = Offset(0f, 1f)
        }
        else -> {
            val rad = degrees / 180f * PI.toFloat()
            val d = Offset(sin(rad), -cos(rad))
            val length = tall * 0.32f
            val fork = Offset(0f, top + tall * 0.6f)
            val bend = Offset(0f, fork.y - length * 0.45f)
            end = bend + d * length
            line += fork
            line += curve(fork, bend, end)
            dir = d
        }
    }
    val tip = end + dir * head
    val base = tip - dir * (head * 1.5f)
    val across = Offset(-dir.y, dir.x) * (head * 0.9f)
    return LaneArrow(line, listOf(tip, base + across, base - across))
}

/** Points along a quadratic curve from [a] to [c] bending toward [b], [a] itself left out. */
private fun curve(a: Offset, b: Offset, c: Offset): List<Offset> = (1..10).map { k ->
    val t = k / 10f
    a * ((1 - t) * (1 - t)) + b * (2 * (1 - t) * t) + c * (t * t)
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
