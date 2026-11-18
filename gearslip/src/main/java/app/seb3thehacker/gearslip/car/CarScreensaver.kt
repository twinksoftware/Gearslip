package app.seb3thehacker.gearslip.car

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.media.ArtColor
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sin

/**
 * A black resting screen for the car, after [IDLE_MS] with nothing touched on Home.
 *
 * The screen reads as one of six quiet cases, driven only by what is actually live:
 *  - music playing + a route: album-tinted wash, track bottom-start, animated dot-matrix turn bottom-centre;
 *  - music playing, no route: the same wash, track plus a small level meter;
 *  - a route, no music: pure black, the arrow and distance alone;
 *  - nothing: pure black, a dot-matrix clock, one breathing red dot, TOUCH TO WAKE;
 *  - music paused + a route: the arrow stays live, the track dims behind a PAUSED chip;
 *  - an imminent turn (parsed distance <= 100 m/ft) with music: the arrow and distance pulse red.
 *
 * Clock and vehicle readings sit in the top corners, the track bottom-start, the turn bottom-centre.
 * Every case wakes on touch (and on any key through [CarScreensaver.poke] elsewhere). A call or the
 * keyboard suppresses the whole thing.
 */
object CarScreensaver {

    var visible by mutableStateOf(false)
        private set

    private var lastInput = SystemClock.uptimeMillis()

    /** A minute is long enough not to hide a map the driver is still reading. */
    const val IDLE_MS = 60_000L

    /** Every pointer event and every knob move lands here. Cheap when already awake. */
    fun poke() {
        lastInput = SystemClock.uptimeMillis()
        if (visible) visible = false
    }

    fun dismiss() = poke()

    /**
     * Called on a timer. [allowed] is false off Home and while something needs the screen; that
     * also resets the clock, so coming back to Home starts a fresh wait instead of sleeping at once.
     */
    fun tick(enabled: Boolean, allowed: Boolean) {
        if (!enabled || !allowed) {
            if (visible) visible = false
            lastInput = SystemClock.uptimeMillis()
            return
        }
        if (!visible && SystemClock.uptimeMillis() - lastInput >= IDLE_MS) visible = true
    }
}

@Composable
internal fun CarScreensaverOverlay() {
    if (!CarScreensaver.visible) return
    val media = CarServices.media
    val phase by media.phase.collectAsStateWithLifecycle()
    val now by media.now.collectAsStateWithLifecycle()
    val art = rememberArt(now)
    val accent = rememberAccent(art)
    val turn = rememberNextTurn()
    val moment by rememberNow()
    val locale = LocalConfiguration.current.locales[0]

    val musicReady = phase == CarMedia.Phase.READY && now.isActive
    val playing = musicReady && now.playing
    val hasTurn = turn != null
    val imminent = turn != null && isImminent(turn.distance)
    val idle = !musicReady && !hasTurn

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { CarScreensaver.dismiss() } },
    ) {
        val compact = maxWidth < 840.dp || maxHeight < 520.dp
        val pad = if (compact) 24.dp else 40.dp
        val facts = rememberVehicleFacts(limit = if (compact) 3 else 5)

        // Cases A, B, E, F: only music tints the black; C and D stay pure black.
        if (musicReady) AlbumAtmosphere(accent, Modifier.fillMaxSize())

        // Clock + date, top-start. D uses the explicit dot-matrix face.
        Column(Modifier.align(Alignment.TopStart).padding(pad)) {
            if (idle) {
                DotMatrixClock(moment, locale, compact)
            } else {
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(moment)),
                    style = MaterialTheme.typography.displayLarge,
                    fontSize = if (compact) 44.sp else 68.sp,
                    color = Color.White.copy(alpha = 0.94f),
                    maxLines = 1,
                )
            }
            Text(
                SimpleDateFormat("EEEE d MMMM", locale).format(Date(moment)).uppercase(locale),
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 0.16.em,
                color = Color.White.copy(alpha = 0.34f),
                maxLines = 1,
            )
        }

        // Vehicle facts, top-end.
        if (facts.isNotEmpty()) {
            Column(
                Modifier.align(Alignment.TopEnd).padding(pad),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                facts.forEach { FactRow(it) }
            }
        }

        // Track, bottom-start: every case with live music (A, B, E, F).
        if (musicReady) {
            NowPlayingWake(
                now = now,
                art = art,
                accent = accent,
                compact = compact,
                paused = !playing,
                showLevel = playing && !hasTurn, // B only
                modifier = Modifier.align(Alignment.BottomStart).padding(pad),
            )
        }

        // Dot-matrix arrow + distance, bottom-centre: every case with a route (A, C, E, F).
        turn?.let {
            TurnCluster(
                turn = it,
                accent = accent,
                imminent = imminent,
                compact = compact,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = pad)
                    .widthIn(max = if (compact) 220.dp else 360.dp),
            )
        }

        // Case D: the single breathing red dot and the wake hint.
        if (idle) {
            BreathingDot(
                Color(0xFFFF3030),
                Modifier.align(Alignment.Center).size(if (compact) 12.dp else 16.dp),
            )
            Text(
                stringResource(R.string.screensaver_touch_to_wake),
                Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.18.em,
                color = Color.White.copy(alpha = 0.4f),
            )
        }
    }
}

/** The cover's dominant hue, held to a readable band: colour is a hint here, never a fill. */
@Composable
private fun rememberAccent(art: Bitmap?): Color {
    val tint by produceState<ArtColor.Tint?>(null, art) {
        value = art?.let { withContext(Dispatchers.Default) { ArtColor.of(it) } }
    }
    return remember(tint) {
        tint?.let {
            val hsv = floatArrayOf(it.hue, it.saturation.coerceIn(0.35f, 0.72f), 1f)
            Color(android.graphics.Color.HSVToColor(hsv))
        } ?: Color(0xFFE6E6E6)
    }
}

/** Three huge, still-soft pools of the album colour, drifting over half a minute. */
@Composable
private fun AlbumAtmosphere(accent: Color, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "screensaver")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(28_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    Canvas(modifier) {
        fun pool(colour: Color, x: Float, y: Float, reach: Float) {
            drawRect(
                Brush.radialGradient(
                    listOf(colour, Color.Transparent),
                    center = Offset(size.width * x, size.height * y),
                    radius = size.width * reach,
                ),
            )
        }
        pool(accent.copy(alpha = 0.10f + 0.035f * breathe), 0.16f + 0.10f * drift, 0.26f, 0.62f)
        pool(accent.copy(alpha = 0.075f), 0.88f - 0.09f * drift, 0.80f, 0.58f)
        pool(Color.White.copy(alpha = 0.012f), 0.5f, 0.5f, 0.75f)
    }
}

@Composable
private fun FactRow(fact: VehicleFact) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            fact.label,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.16.em,
            color = Color.White.copy(alpha = 0.30f),
            maxLines = 1,
        )
        Text(
            fact.value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (fact.alert) Color(0xFFFF3030) else Color.White.copy(alpha = 0.82f),
            maxLines = 1,
        )
    }
}

/**
 * The track, small enough to leave the screen black: square thumb, words, one hairline of progress.
 * When music is paused it dims and wears a rectangular PAUSED chip; with no route it gains a small
 * level meter so case B still reads as alive.
 */
@Composable
private fun NowPlayingWake(
    now: NowPlaying,
    art: Bitmap?,
    accent: Color,
    compact: Boolean,
    paused: Boolean,
    showLevel: Boolean,
    modifier: Modifier,
) {
    val dim = if (paused) 0.45f else 1f
    Row(
        modifier.widthIn(max = if (compact) 260.dp else 500.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
    ) {
        Box(
            Modifier
                .size(if (compact) 44.dp else 60.dp)
                .border(1.dp, Color.White.copy(alpha = 0.18f * dim)),
        ) {
            art?.let {
                Image(
                    it.asImageBitmap(), null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop, alpha = dim,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    now.title,
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.92f * dim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (paused) PausedChip()
            }
            Text(
                now.artist,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.42f * dim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .width(if (compact) 160.dp else 280.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.14f * dim)),
            ) {
                val fraction = if (now.durationMs > 0) {
                    (now.positionMs.toFloat() / now.durationMs).coerceIn(0.015f, 1f)
                } else 0f
                if (fraction > 0f) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(accent.copy(alpha = 0.85f * dim)))
                }
            }
        }
        if (showLevel) LevelMeter(accent, compact, Modifier.padding(start = 2.dp))
    }
}

/** A bordered, unrounded label for case E. */
@Composable
private fun PausedChip() {
    Box(Modifier.border(1.dp, Color.White.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(
            "PAUSED",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.12.em,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
}

/** Five small bars drifting out of phase; the only movement in case B. */
@Composable
private fun LevelMeter(color: Color, compact: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "level")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "level-phase",
    )
    val maxHeight = if (compact) 16.dp else 22.dp
    Row(
        modifier.height(maxHeight),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(5) { i ->
            val phase = (progress + i * 0.17f) % 1f
            val heightFraction = 0.22f + 0.78f * sin(phase * Math.PI.toFloat()).coerceIn(0f, 1f)
            Box(
                Modifier
                    .width(if (compact) 3.dp else 4.dp)
                    .height(maxHeight * heightFraction)
                    .background(color.copy(alpha = 0.85f)),
            )
        }
    }
}

/**
 * The next turn, bottom-centre: a self-contained 9x9 dot-matrix arrow whose lit cells carry a
 * travelling pulse, with the distance in the display face and the road in tracked mono beneath it.
 * An imminent turn swaps the album hue for red and speeds the pulse up (case F).
 */
@Composable
private fun TurnCluster(
    turn: NextTurn,
    accent: Color,
    imminent: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    rightTurn: Boolean = false,
) {
    val locale = LocalConfiguration.current.locales[0]
    val transition = rememberInfiniteTransition(label = "turn")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (imminent) 520 else 1_400, easing = LinearEasing),
        ),
        label = "travel",
    )
    val arrowColor = if (imminent) Color(0xFFFF3030) else accent
    val pattern = if (rightTurn) TURN_ARROW.map { it.reversed() } else TURN_ARROW

    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
    ) {
        Text(
            stringResource(R.string.screensaver_next_turn),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.20.em,
            color = arrowColor.copy(alpha = 0.75f),
        )
        DotMatrix(
            pattern = pattern,
            cell = if (compact) 3.dp else 4.5.dp,
            color = arrowColor,
            progress = progress,
            stagger = 1f / (9f * 9f),
        )
        if (turn.distance.isNotEmpty()) {
            Text(
                turn.distance,
                style = MaterialTheme.typography.displayMedium,
                fontSize = if (compact) 28.sp else 44.sp,
                color = if (imminent) arrowColor else Color.White.copy(alpha = 0.95f),
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
        if (turn.road.isNotEmpty()) {
            Text(
                turn.road.uppercase(locale),
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 0.12.em,
                color = Color.White.copy(alpha = 0.52f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One grid of small squares from a row-string pattern. With [progress] the lit cells carry a
 * travelling pulse, staggered by their index (a comet trail across the grid); without it every
 * lit cell holds a steady alpha. This is the arrow's and the idle clock's own matrix, not DotGrid.
 */
@Composable
private fun DotMatrix(
    pattern: List<String>,
    cell: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    stagger: Float = 0f,
    staticAlpha: Float = 0.85f,
) {
    val rows = pattern.size
    val cols = pattern.maxOf { it.length }
    val gap = cell * 0.22f
    Canvas(modifier.size(cell * cols.toFloat() + gap * (cols - 1).toFloat(), cell * rows.toFloat() + gap * (rows - 1).toFloat())) {
        val cellPx = cell.toPx()
        val gapPx = gap.toPx()
        for (r in 0 until rows) {
            val line = pattern[r]
            for (c in 0 until cols) {
                if (c >= line.length || line[c] != '#') continue
                val index = r * cols + c
                val alpha = if (progress == null) {
                    staticAlpha
                } else {
                    val phase = ((progress - index * stagger) % 1f + 1f) % 1f
                    val glow = 1f - phase
                    (0.18f + 0.82f * glow * glow) * staticAlpha
                }
                drawRect(
                    color = color.copy(alpha = alpha),
                    topLeft = Offset(c * (cellPx + gapPx), r * (cellPx + gapPx)),
                    size = Size(cellPx, cellPx),
                )
            }
        }
    }
}

/** The idle clock, drawn as an explicit 5x7 dot matrix rather than relying on the display font. */
@Composable
private fun DotMatrixClock(moment: Long, locale: Locale, compact: Boolean, modifier: Modifier = Modifier) {
    val time = SimpleDateFormat("HH:mm", locale).format(Date(moment))
    val pattern = remember(time) { clockPattern(time) }
    DotMatrix(
        pattern = pattern,
        cell = if (compact) 3.5.dp else 5.dp,
        color = Color.White.copy(alpha = 0.9f),
        modifier = modifier,
    )
}

/** The single red point of light in case D, breathing slowly. */
@Composable
private fun BreathingDot(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "breath")
    val alpha by transition.animateFloat(
        initialValue = 0.12f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dot-alpha",
    )
    Canvas(modifier) {
        drawCircle(color.copy(alpha = alpha), radius = size.minDimension / 2f)
    }
}

/**
 * True when a distance string describes 100 m/ft or less, so the turn can pulse red (case F).
 * The first number in the string is used with its unit; anything unparseable is non-imminent.
 */
private fun isImminent(distance: String): Boolean {
    val value = REGEX_NUMBER.find(distance)?.value?.toFloatOrNull() ?: return false
    val text = distance.lowercase(Locale.ROOT)
    val metres = when {
        "km" in text -> value * 1_000f
        "mi" in text -> value * 1_609f
        "ft" in text -> value * 0.3048f
        "yd" in text -> value * 0.9144f
        else -> value // 'm', or a bare number: assume metres
    }
    return metres <= 100f
}

/** The left-turn arrow: a filled triangular head on the left and a horizontal shaft to its right. */
private val TURN_ARROW = listOf(
    "....#....",
    "...##....",
    "..###....",
    ".####....",
    "#########",
    ".####....",
    "..###....",
    "...##....",
    "....#....",
)

private val REGEX_NUMBER = Regex("""\d+(?:\.\d+)?""")

/** 5x7 dot-matrix digits and the colon, for the idle clock. */
private val DIGITS: Map<Char, List<String>> = mapOf(
    '0' to listOf(".###.", "#...#", "#.##.", "#.#.#", "##..#", "#...#", ".###."),
    '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
    '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
    '3' to listOf("#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."),
    '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
    '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
    '6' to listOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
    '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
    '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
    '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
    ':' to listOf(".....", "..#..", "..#..", ".....", "..#..", "..#..", "....."),
)

/** Lay the glyphs for [text] side by side, with one blank column between them. */
private fun clockPattern(text: String): List<String> {
    val glyphs = text.map { DIGITS[it] }.filterNotNull()
    if (glyphs.isEmpty()) return listOf(".")
    return (0 until 7).map { row ->
        buildString {
            glyphs.forEachIndexed { i, glyph ->
                if (i > 0) append('.')
                append(glyph[row])
            }
        }
    }
}
