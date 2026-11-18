package app.seb3thehacker.gearslip.car

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * A black resting screen for the car, after [IDLE_MS] with nothing touched on Home.
 *
 * It keeps the design's pure black canvas and only lets the playing cover tint it: three very
 * faint pools of the album's dominant hue, drifting slowly, plus one moving line of that hue
 * along the bottom edge. Nothing is filled in, so an OLED or a dimmed head unit still reads as
 * black. Clock, the car's own readings, the song and the next turn live in the four corners,
 * each one a glance rather than a panel.
 *
 * Any touch or key wakes it; leaving Home hides it. A call or the keyboard suppresses it.
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

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { CarScreensaver.dismiss() } },
    ) {
        val compact = maxWidth < 840.dp || maxHeight < 520.dp
        val pad = if (compact) 24.dp else 40.dp
        val facts = rememberVehicleFacts(limit = if (compact) 3 else 5)
        AlbumAtmosphere(accent, Modifier.fillMaxSize())
        HorizonLight(accent, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp))

        Column(Modifier.align(Alignment.TopStart).padding(pad)) {
            Text(
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(moment)),
                style = MaterialTheme.typography.displayLarge,
                fontSize = if (compact) 44.sp else 68.sp,
                color = Color.White.copy(alpha = 0.94f),
                maxLines = 1,
            )
            Text(
                SimpleDateFormat("EEEE d MMMM", locale).format(Date(moment))
                    .uppercase(locale),
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 0.16.em,
                color = Color.White.copy(alpha = 0.34f),
                maxLines = 1,
            )
        }

        if (facts.isNotEmpty()) {
            Column(
                Modifier.align(Alignment.TopEnd).padding(pad),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                facts.forEach { FactRow(it) }
            }
        }

        if (phase == CarMedia.Phase.READY && now.isActive) {
            NowPlayingWake(now, art, accent, compact, Modifier.align(Alignment.BottomStart).padding(pad))
        }
        turn?.let {
            DirectionWake(it, accent, compact, Modifier.align(Alignment.BottomEnd).padding(pad))
        }

        Text(
            stringResource(R.string.screensaver_touch_to_wake),
            Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.18.em,
            color = Color.White.copy(alpha = 0.18f),
        )
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

/** One line of the album colour travelling the bottom edge, OnePlus' horizon light at rest. */
@Composable
private fun HorizonLight(accent: Color, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "horizon")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7_500, easing = LinearEasing)),
        label = "sweep",
    )
    Canvas(modifier) {
        drawRect(Color.White.copy(alpha = 0.035f), size = Size(size.width, 1.dp.toPx()))
        val band = size.width * 0.24f
        val at = -band + (size.width + band * 2f) * sweep
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, accent.copy(alpha = 0.55f), Color.Transparent),
                startX = at - band / 2f,
                endX = at + band / 2f,
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, size.height),
        )
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

/** The song, small enough to leave the screen black: thumb, words, and one hairline of progress. */
@Composable
private fun NowPlayingWake(now: NowPlaying, art: Bitmap?, accent: Color, compact: Boolean, modifier: Modifier) {
    Row(
        modifier.widthIn(max = if (compact) 280.dp else 540.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
    ) {
        Box(
            Modifier.size(if (compact) 46.dp else 62.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.06f)),
        ) {
            art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                now.title,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.90f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                now.artist,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.42f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Box(Modifier.width(if (compact) 180.dp else 300.dp).height(1.dp).background(Color.White.copy(alpha = 0.14f))) {
                val fraction = if (now.durationMs > 0) {
                    (now.positionMs.toFloat() / now.durationMs).coerceIn(0.015f, 1f)
                } else 0f
                if (fraction > 0f) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(accent.copy(alpha = 0.85f)))
                }
            }
        }
    }
}

/**
 * The next turn, OnePlus-quiet: an arrow with a slow breathing halo, the distance in the display
 * face, the road in tracked mono, and a short horizon line sweeping under it. A new road slides
 * in from the edge once, while distance updates keep ticking in place.
 */
@Composable
private fun DirectionWake(turn: NextTurn, accent: Color, compact: Boolean, modifier: Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val transition = rememberInfiniteTransition(label = "turn")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val enter = remember { Animatable(0f) }
    LaunchedEffect(turn.road) {
        if (turn.road.isNotEmpty()) {
            enter.snapTo(0f)
            enter.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
        } else {
            enter.snapTo(1f)
        }
    }

    Column(
        modifier.widthIn(max = if (compact) 250.dp else 460.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp),
    ) {
        Text(
            stringResource(R.string.screensaver_next_turn),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.20.em,
            color = accent.copy(alpha = 0.75f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 20.dp),
        ) {
            Box(Modifier.size(if (compact) 44.dp else 66.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(accent.copy(alpha = 0.10f * pulse), radius = size.minDimension / 2f)
                }
                if (turn.icon != null) {
                    CarGlyph(turn.icon, Modifier.size(if (compact) 36.dp else 54.dp))
                } else {
                    DefaultChevron(Modifier.size(if (compact) 36.dp else 54.dp))
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.graphicsLayer {
                    alpha = enter.value
                    translationX = (1f - enter.value) * 20.dp.toPx()
                },
            ) {
                if (turn.distance.isNotEmpty()) {
                    Text(
                        turn.distance,
                        style = MaterialTheme.typography.displayMedium,
                        fontSize = if (compact) 30.sp else 48.sp,
                        color = Color.White.copy(alpha = 0.95f),
                        maxLines = 1,
                    )
                }
                Text(
                    turn.road.ifEmpty { " " }.uppercase(locale),
                    style = MaterialTheme.typography.labelLarge,
                    letterSpacing = 0.12.em,
                    color = Color.White.copy(alpha = 0.52f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
            }
        }
        HorizonSweep(accent, Modifier.width(if (compact) 130.dp else 210.dp).height(1.5.dp))
    }
}

@Composable
private fun HorizonSweep(accent: Color, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "turn-line")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_600, easing = LinearEasing)),
        label = "sweep",
    )
    Canvas(modifier) {
        val band = size.width * 0.55f
        val at = -band + (size.width + band * 2f) * sweep
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, accent.copy(alpha = 0.7f), Color.Transparent),
                startX = at - band / 2f,
                endX = at + band / 2f,
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, size.height),
        )
    }
}

/** A squared double chevron for a turn with no icon of its own, in the design's stroke language. */
@Composable
private fun DefaultChevron(modifier: Modifier) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.22f, size.height * 0.52f)
            lineTo(size.width * 0.50f, size.height * 0.24f)
            lineTo(size.width * 0.78f, size.height * 0.52f)
            moveTo(size.width * 0.22f, size.height * 0.80f)
            lineTo(size.width * 0.50f, size.height * 0.52f)
            lineTo(size.width * 0.78f, size.height * 0.80f)
        }
        drawPath(
            path,
            color = Color.White.copy(alpha = 0.90f),
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter),
        )
    }
}
