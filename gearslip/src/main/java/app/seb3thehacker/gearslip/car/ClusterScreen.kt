package app.seb3thehacker.gearslip.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.car.app.CarAppService
import androidx.car.app.SessionInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.seb3thehacker.gearslip.Insets
import app.seb3thehacker.gearslip.host.CarAppConnection
import app.seb3thehacker.gearslip.host.TemplateApp
import app.seb3thehacker.gearslip.host.clockTime
import app.seb3thehacker.gearslip.host.display
import app.seb3thehacker.gearslip.host.remainingTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * The instrument-cluster screen: the next turn, big enough to read in a glance past the wheel.
 * The arrow and the distance to it, what to do there, and the time, distance and arrival left
 * along the bottom. With no route it shows what's playing, quietly, or nothing.
 *
 * Always dark, like the dials around it. [marginX] and [marginY] are the pixels the car crops off
 * the frame, kept clear. Many cars also draw their own dials and bars over what's left, so the
 * card keeps to [clearArea] when the car says where that is, and otherwise to the middle of a
 * wide strip: on an Audi's virtual cockpit it ran under the rev counter and the bottom bar (#56).
 *
 * When the main screen's navigation app has a session for clusters, it draws its map to the
 * right of the card, on a surface Gearslip lends that session as it does the main one.
 */
@Composable
internal fun ClusterScreen(marginX: Int, marginY: Int, densityDpi: Int, clearArea: StateFlow<Insets?>) {
    val carInsets by clearArea.collectAsState()
    val context = LocalContext.current
    // Which app draws the map: whatever navigation app the main screen runs, if it can.
    val navApp by produceState<ComponentName?>(null) {
        while (true) {
            value = CarServices.navOrNull()?.connectedComponent
            delay(2_000)
        }
    }
    val mapApp = navApp?.let { app -> remember(app) { app.takeIf { drawsOnCluster(context, it) } } }
    // The map app's turn changes a few times a minute: read it once a second.
    val turn by produceState<ClusterTurn?>(null) {
        while (true) {
            value = ClusterTurn.now()
            delay(1_000)
        }
    }
    val density = LocalDensity.current
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            BoxWithConstraints(
                Modifier.fillMaxSize().background(Color.Black).padding(
                    horizontal = with(density) { (marginX / 2).toDp() },
                    vertical = with(density) { (marginY / 2).toDp() },
                ),
            ) {
                val clear = clearPadding(maxWidth, maxHeight, carInsets?.let { with(density) { toDp(it) } })
                BoxWithConstraints(
                    Modifier.fillMaxSize().padding(start = clear[0], top = clear[1], end = clear[2], bottom = clear[3]),
                    contentAlignment = Alignment.Center,
                ) {
                    val t = turn
                    val map = mapApp?.let { clusterMap(it, densityDpi, constraints.maxWidth, constraints.maxHeight) }
                    if (map != null && mapShowing(map)) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            if (t != null) {
                                BoxWithConstraints(Modifier.weight(0.42f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                                    // Narrower than alone: the arrow and distance alone are about 12 units wide.
                                    TurnCard(t, minOf(maxHeight / 8.5f, maxWidth / 12))
                                }
                                Spacer(Modifier.width(12.dp))
                            }
                            MapSurface(map, densityDpi, Modifier.weight(0.58f).fillMaxHeight())
                        }
                        return@BoxWithConstraints
                    }
                    // The card is about 8.5 units tall with two lines of directions, and 14 wide: fit both ways.
                    val unit = minOf(maxHeight / 8.5f, maxWidth / 14)
                    if (t == null) {
                        NowPlayingLine(Modifier.align(Alignment.Center))
                        return@BoxWithConstraints
                    }
                    TurnCard(t, unit)
                }
            }
        }
    }
}

/**
 * The navigation app's cluster session, open while this is on screen. Sized for the clear area
 * it shares with the card, so the app lays its map out for a cluster-shaped screen.
 */
@Composable
private fun clusterMap(app: ComponentName, densityDpi: Int, width: Int, height: Int): CarAppConnection {
    val context = LocalContext.current.applicationContext
    val connection = remember(app) {
        CarAppConnection(context, SessionInfo(SessionInfo.DISPLAY_TYPE_CLUSTER, CLUSTER_SESSION))
    }
    DisposableEffect(connection) {
        connection.connect(TemplateApp(app, app.packageName, emptyList()), (width * 0.58f).toInt(), height, densityDpi)
        onDispose { connection.disconnect() }
    }
    return connection
}

/**
 * Whether the map's place is kept: while the session comes up, and once running only if the app
 * asked for a surface. An app whose cluster session draws nothing leaves the card on its own.
 */
@Composable
private fun mapShowing(map: CarAppConnection): Boolean {
    val status by map.status.collectAsState()
    return when (status.phase) {
        CarAppConnection.Phase.BINDING, CarAppConnection.Phase.HANDSHAKE -> true
        CarAppConnection.Phase.RUNNING -> status.surfaceRequested || status.template == null
        else -> false
    }
}

/** A surface lent to the cluster session; a fresh one for every size, as on the main screen. */
@Composable
private fun MapSurface(map: CarAppConnection, densityDpi: Int, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        key(constraints.maxWidth, constraints.maxHeight) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = Unit
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                                map.attachSurface(holder.surface, width, height, densityDpi)
                            override fun surfaceDestroyed(holder: SurfaceHolder) = map.detachSurface(holder.surface)
                        })
                    }
                },
            )
        }
    }
    DisposableEffect(map) { onDispose { map.detachSurface() } }
}

/** Whether [component] says, in its manifest, that it has a session for instrument clusters. */
private fun drawsOnCluster(context: Context, component: ComponentName): Boolean = runCatching {
    val intent = Intent(CarAppService.SERVICE_INTERFACE).addCategory(FEATURE_CLUSTER).setPackage(component.packageName)
    context.packageManager.queryIntentServices(intent, 0).any { it.serviceInfo.name == component.className }
}.getOrDefault(false)

private const val FEATURE_CLUSTER = "androidx.car.app.category.FEATURE_CLUSTER"
private const val CLUSTER_SESSION = "gearslip-cluster"

/**
 * Start, top, end and bottom padding around the card inside the visible picture. The car's own
 * insets win when they leave a usable area; a wide strip, where dials sit at both ends and a bar
 * along the bottom, keeps to the upper middle; anything squarer just keeps off the edges.
 */
private fun clearPadding(width: Dp, height: Dp, car: List<Dp>?): List<Dp> {
    if (car != null && width - car[0] - car[2] >= width * 0.3f && height - car[1] - car[3] >= height * 0.3f) return car
    if (width >= height * 2) return listOf(width * 0.22f, height * 0.08f, width * 0.22f, height * 0.33f)
    return listOf(24.dp, 16.dp, 24.dp, 16.dp)
}

/** Insets in frame pixels as start, top, end and bottom padding. */
private fun androidx.compose.ui.unit.Density.toDp(insets: Insets): List<Dp> =
    listOf(insets.left.toDp(), insets.top.toDp(), insets.right.toDp(), insets.bottom.toDp())

@Composable
private fun TurnCard(t: ClusterTurn, unit: Dp) {
    val density = LocalDensity.current
    Column(verticalArrangement = Arrangement.spacedBy(unit * 0.4f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CarGlyph(t.icon, Modifier.size(unit * 3.4f))
            Spacer(Modifier.width(unit))
            Text(
                t.distance,
                fontSize = with(density) { (unit * 2.2f).toSp() },
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        if (t.what.isNotEmpty()) {
            Text(
                t.what,
                fontSize = with(density) { (unit * 1.1f).toSp() },
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (t.trip.isNotEmpty()) {
            Text(
                t.trip,
                fontSize = with(density) { (unit * 0.85f).toSp() },
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun NowPlayingLine(modifier: Modifier) {
    val now = CarServices.mediaOrNull()?.now?.value
    if (now == null || now.title.isEmpty()) return
    Text(
        listOf(now.title, now.artist).filter { it.isNotEmpty() }.joinToString(" · "),
        modifier,
        style = MaterialTheme.typography.titleLarge,
        color = Color.White.copy(alpha = 0.7f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** What the cluster shows of a route, read from [NavTurn] and the trip's estimate to the destination. */
private class ClusterTurn(
    val icon: androidx.car.app.model.CarIcon?,
    val distance: String,
    val what: String,
    val trip: String,
) {
    companion object {
        fun now(): ClusterTurn? {
            val turn = NavTurn.current() ?: return null
            if (turn.rerouting) return null
            val step = turn.step
            val cue = step.cue?.toString().orEmpty()
            val what = cue.ifEmpty { step.road?.toString().orEmpty() }
            val estimate = CarServices.navOrNull()?.trip?.value?.destinationTravelEstimates?.firstOrNull()
            val trip = estimate?.let { e ->
                listOf(
                    e.remainingTimeSeconds.takeIf { it >= 0 }?.let { remainingTime(it) }.orEmpty(),
                    e.remainingDistance.display(),
                    e.arrivalTimeAtDestination.clockTime(),
                ).filter { it.isNotEmpty() }.joinToString(" · ")
            }.orEmpty()
            return ClusterTurn(step.maneuver?.icon, turn.distance.display(), what, trip)
        }
    }
}
