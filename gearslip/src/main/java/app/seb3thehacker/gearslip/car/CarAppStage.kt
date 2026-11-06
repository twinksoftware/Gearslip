package app.seb3thehacker.gearslip.car

import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.car.theme.*
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.hypot
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.car.app.model.Template
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.viewinterop.AndroidView
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.host.CarAppConnection

/**
 * Shows a connected templated app: its own drawing surface, with host chrome over the top.
 *
 * The surface is a plain SurfaceView. Because the whole car UI is a Presentation on a
 * VirtualDisplay wired to the encoder, a SurfaceView here is composited by SurfaceFlinger into
 * that display like any other layer - so the app's map reaches the head unit down the existing
 * H.264 pipe without Gearslip ever touching a pixel of it.
 */
@Composable
fun CarAppStage(
    connection: CarAppConnection,
    status: CarAppConnection.Status,
    frame: CarEnvironment.Frame,
    /** Inside the home screen's map pane: no title bar, the template gets the whole area. */
    embedded: Boolean = false,
    onDisconnect: () -> Unit,
) {
    val template by connection.template.collectAsStateWithLifecycle()

    val cover = remember { ChromeCover() }
    val navBar = LocalNavBarInset.current

    Box(Modifier.fillMaxSize()) {
        // The app's own drawing goes underneath; everything else is the host's rendering of
        // the template it described, laid over the top.
        androidx.compose.runtime.CompositionLocalProvider(LocalChromeCover provides cover, LocalCarAppConnection provides connection) {
            // A place list's map is the host's to draw; any other map is the app's own surface.
            val placeList = template as? androidx.car.app.model.PlaceListMapTemplate
            if (placeList != null) {
                HostPlaceMap(placeList, Modifier.fillMaxSize())
            } else if (status.surfaceRequested) {
                AppSurface(connection, frame, template, embedded, Modifier.fillMaxSize())
            }
            // The docked player floats over one side of the map: the chrome keeps clear of it,
            // and the app hears that side is covered, so its map centres in the rest.
            val panel = LocalSidePanelInset.current
            val panelAtStart = LocalCardsAtEnd.current
            TemplateChrome(
                template,
                Modifier.fillMaxSize().padding(
                    top = if (embedded) 0.dp else CHROME_BAR,
                    bottom = navBar,
                    start = if (panelAtStart) panel else 0.dp,
                    end = if (panelAtStart) 0.dp else panel,
                ),
            )
            if (panel > 0.dp) {
                Box(
                    Modifier
                        .align(if (panelAtStart) Alignment.CenterStart else Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(panel)
                        .covers("side-panel", if (panelAtStart) CoverEdge.START else CoverEdge.END),
                )
            }
            BottomCover(navBar, Modifier.align(Alignment.BottomCenter))
        }
        if (!embedded) ChromeBar(status, onDisconnect, Modifier.align(Alignment.TopCenter))
    }
}

/**
 * A SurfaceView lent to the app for as long as it is on screen.
 *
 * The app writes into this from its own process, so the surface must outlive the handoff;
 * [SurfaceHolder.Callback] is what tells us when that stops being true.
 */
@Composable
private fun AppSurface(
    connection: CarAppConnection,
    frame: CarEnvironment.Frame,
    template: Template?,
    embedded: Boolean,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(0 to 0) }
    var bounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val cover = LocalChromeCover.current

    // The app lays its map out around whatever the host covers, so a template change or a card
    // changing size moves the visible area even though the surface itself hasn't changed.
    val (width, height) = size
    // A full-screen map takes the knob: turn to zoom, nudge to pan, press for its buttons. The
    // home screen's map pane leaves it alone, so the knob still moves between the cards there.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val isMap = !embedded && !isContentTemplate(template)
    DisposableEffect(connection, isMap, width, height) {
        if (!isMap || width == 0 || height == 0) return@DisposableEffect onDispose {}
        val knob = MapKnob(connection, width, height) { focusManager.clearFocus() }
        CarKeys.knobTaker = knob
        onDispose {
            knob.release()
            if (CarKeys.knobTaker === knob) CarKeys.knobTaker = null
        }
    }
    val measured = bounds?.takeIf { width > 0 && height > 0 }?.let { cover?.visibleWithin(it, width, height) }
    // The card column moves the map's centre but isn't taken out of the stable area: apps size
    // their own overlays from it, and Vela's speedometer shrank to fit the strip right of the cards.
    val cardsAtEnd = LocalCardsAtEnd.current
    val cardEdge = cardEdge
    val measuredStable = bounds?.takeIf { width > 0 && height > 0 }?.let { cover?.visibleWithin(it, width, height, skip = cardEdge) }
    // A little room above whatever ends the map at the bottom (the nav bar, or the screen's edge):
    // apps draw their own corner pieces (Waze's speedometer) right on the area's bottom edge,
    // which left them sitting flush on the bar.
    val bottomGap = with(androidx.compose.ui.platform.LocalDensity.current) { MAP_BOTTOM_GAP.roundToPx() }
    LaunchedEffect(template, size, measured, measuredStable, cardsAtEnd, bottomGap) {
        if (width > 0 && height > 0) {
            fun android.graphics.Rect.clearOfBar() = if (isEmpty) this else android.graphics.Rect(left, top, right, maxOf(top, bottom - bottomGap))
            val visible = visibleAreaFor(template, width, height, measured, cardsAtEnd).clearOfBar()
            // Waze seems to centre its car in the stable area rather than the visible one, so it
            // gets the cards taken out of that too. Test: see whether its car moves out from under them.
            val stable = if (!isContentTemplate(template) && connection.appPackage != "com.waze") {
                visibleAreaFor(template, width, height, measuredStable, cardsAtEnd).clearOfBar()
            } else {
                visible
            }
            connection.reportAreas(visible = visible, stable = stable)
        }
    }

    // A fresh SurfaceView - and so a fresh Surface - for every size, the way Android Auto hands an
    // app a new surface rather than resizing the old one. A resized SurfaceView keeps the same
    // BufferQueue, and apps that draw through their own VirtualDisplay build the
    // new one on it before releasing the old: the old display's disconnect then takes the new one's
    // connection with it and the map freezes for good. The nav bar hiding for the search keyboard
    // and the lyrics pane opening both resize this area.
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.onGloballyPositioned { bounds = it.boundsInWindow() },
    ) {
        val epoch by connection.surfaceEpoch.collectAsState()
        androidx.compose.runtime.key(constraints.maxWidth, constraints.maxHeight, epoch) {
            SurfaceHost(connection, frame, onSize = { size = it }, Modifier.fillMaxSize())
        }
    }

    DisposableEffect(connection) { onDispose { connection.detachSurface() } }
}

@Composable
private fun SurfaceHost(
    connection: CarAppConnection,
    frame: CarEnvironment.Frame,
    onSize: (Pair<Int, Int>) -> Unit,
    modifier: Modifier,
) {
    AndroidView(
        modifier = modifier
            .pointerInput(connection) {
                detectTapGestures { connection.surfaceClick(it.x, it.y) }
            }
            .pointerInput(connection) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    // GestureDetector reports scroll as previous-minus-current; a Compose pan
                    // is the other way round.
                    if (pan != Offset.Zero) connection.surfaceScroll(-pan.x, -pan.y)
                    if (zoom != 1f) connection.surfaceScale(centroid.x, centroid.y, zoom)
                }
            }
            .pointerInput(connection) {
                // detectTransformGestures has no velocity, so a fling is measured here alongside
                // it: one finger lifting while still moving lets the map glide on, as on a car.
                // Velocity is the finger's own, which is GestureDetector's onFling convention.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var pinched = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.changes.size > 1) pinched = true
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) {
                            val v = tracker.calculateVelocity()
                            if (!pinched && hypot(v.x, v.y) >= FLING_MIN_PX_S) {
                                connection.surfaceFling(v.x, v.y)
                            }
                            break
                        }
                    }
                }
            },
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        GearslipLog.i("stage: surface ready at ${width}x$height")
                        onSize(width to height)
                        connection.attachSurface(holder.surface, width, height, frame.densityDpi)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        GearslipLog.i("stage: surface destroyed")
                        // Only this view's own surface: a replaced view's teardown can land after
                        // its successor has already lent the app a new one.
                        connection.detachSurface(holder.surface)
                    }
                })
            }
        },
    )
}

@Composable
private fun ChromeBar(
    status: CarAppConnection.Status,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The app's name and a way out, nothing else: which template is showing, or whether the
    // app has drawn yet, is for the log, not the driver.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().height(CHROME_BAR),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                status.app ?: stringResource(R.string.stage_car_app),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            GsIconButton(
                androidx.compose.material.icons.Icons.Filled.Close,
                status.app?.let { stringResource(R.string.stage_close, it) } ?: stringResource(R.string.stage_close_app),
                onDisconnect,
                size = 36.dp,
                iconSize = 20.dp,
                shape = androidx.compose.foundation.shape.CircleShape,
            )
        }
    }
}

/** How far above the bottom of the map the area an app may draw in stops. */
private val MAP_BOTTOM_GAP = 12.dp

/** Height of [ChromeBar]; the template's own chrome starts below it. */
private val CHROME_BAR = 44.dp

/** Slower than this when the finger lifts and it was a drag that stopped, not a fling. */
private const val FLING_MIN_PX_S = 400f

/** The app connection a template on screen belongs to: the map app's, or the one opened from the launcher. */
internal val LocalCarAppConnection = androidx.compose.runtime.staticCompositionLocalOf { CarServices.nav }
