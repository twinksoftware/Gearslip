package app.seb3thehacker.gearslip.car

import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.car.theme.*
import android.graphics.Rect
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.navigation.model.MapTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import app.seb3thehacker.gearslip.host.clockTime
import app.seb3thehacker.gearslip.host.color
import app.seb3thehacker.gearslip.host.image
import app.seb3thehacker.gearslip.host.display
import app.seb3thehacker.gearslip.host.remainingTime
import app.seb3thehacker.gearslip.host.text

/**
 * Draws the template an app handed us, over the surface it is drawing its map into.
 *
 * A templated app never draws its own chrome: it describes a screen in the Car App Library's
 * model types and every host renders those to suit its own screen. This is Gearslip's
 * rendering. It is deliberately laid out for a wide, short car display - controls to the
 * right where a hand reaches from the driver's seat, information to the left.
 *
 * Templates split into two kinds. The six map-backed ones here leave room for the app's own
 * drawing and lay chrome around it; everything else fills the screen and is drawn by
 * [ContentTemplate].
 */
@Composable
fun TemplateChrome(template: Template?, modifier: Modifier = Modifier) {
    when (template) {
        null -> Unit
        is NavigationTemplate -> NavigationChrome(template, modifier)
        is MapWithContentTemplate -> MapWithContentChrome(template, modifier)
        is MapTemplate -> MapChrome(template, modifier)
        is PlaceListNavigationTemplate -> PlaceListNavigationChrome(template, modifier)
        is RoutePreviewNavigationTemplate -> RoutePreviewChrome(template, modifier)
        is PlaceListMapTemplate -> PlaceListMapChrome(template, modifier)
        else -> ContentTemplate(template, modifier)
    }
}

/**
 * How much of the surface Gearslip's chrome covers, so the app can keep what matters clear of
 * it. Expressed as the visible rectangle within a [width] x [height] surface.
 *
 * Navigation's cards are measured where they land ([measured], from [ChromeCover]); an app
 * centres its position marker in this rectangle, so a guess puts the marker under the card.
 * A template that isn't map-backed covers the surface completely, and reporting an empty
 * rectangle is how the app is told to stop bothering.
 */
fun visibleAreaFor(template: Template?, width: Int, height: Int, measured: Rect? = null, cardsAtEnd: Boolean = false): Rect = when (template) {
    is NavigationTemplate -> measured ?: Rect(0, 0, width, height)
    is MapWithContentTemplate, is MapTemplate, is PlaceListNavigationTemplate,
    is RoutePreviewNavigationTemplate, is PlaceListMapTemplate,
    -> measured ?: if (cardsAtEnd) Rect(0, 0, (width * (1 - CARD_COLUMN_FRACTION)).toInt(), height)
    else Rect((width * CARD_COLUMN_FRACTION).toInt(), 0, width, height)
    null -> Rect(0, 0, width, height)
    else -> Rect(0, 0, 0, 0)
}

/** The side of the map a piece of chrome sits against, and so the side it shrinks the visible area from. */
internal enum class CoverEdge { TOP, BOTTOM, START, END }

/**
 * Where the chrome over the map actually landed, in window pixels. The map buttons count even
 * while faded out, or whatever an app draws in a corner (Waze's speedometer) is under them the
 * moment they come back.
 */
internal class ChromeCover {
    private val pieces = mutableStateMapOf<String, Pair<CoverEdge, ComposeRect>>()

    fun put(key: String, edge: CoverEdge, bounds: ComposeRect) {
        val piece = edge to bounds
        if (pieces[key] != piece) pieces[key] = piece
    }

    fun remove(key: String) {
        pieces.remove(key)
    }

    /** What is left of a [width] x [height] surface at [surface] (window pixels), in surface pixels. */
    fun visibleWithin(surface: ComposeRect, width: Int, height: Int, skip: CoverEdge? = null): Rect {
        var left = 0f
        var top = 0f
        var right = width.toFloat()
        var bottom = height.toFloat()
        pieces.values.forEach { (edge, b) ->
            if (edge == skip) return@forEach
            when (edge) {
                CoverEdge.TOP -> top = maxOf(top, b.bottom - surface.top)
                CoverEdge.BOTTOM -> bottom = minOf(bottom, b.top - surface.top)
                CoverEdge.START -> left = maxOf(left, b.right - surface.left)
                CoverEdge.END -> right = minOf(right, b.left - surface.left)
            }
        }
        // Never hand an app nothing to draw in, however the cards end up.
        if (right - left < width / 4f) { left = 0f; right = width.toFloat() }
        if (bottom - top < height / 4f) { top = 0f; bottom = height.toFloat() }
        return Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }
}

internal val LocalChromeCover = compositionLocalOf<ChromeCover?> { null }

/**
 * The app's cards go to the far edge of the map and its buttons to the near one, set while the
 * player is docked on the left: the player, the map and the cards each keep their own space
 * instead of the cards crowding up against the player.
 */
internal val LocalCardsAtEnd = compositionLocalOf { false }

/**
 * How much of the map's width the docked player floats over, on the side opposite the cards. The
 * map itself runs underneath at full size; the app is told this part is covered, and the chrome
 * keeps clear of it.
 */
internal val LocalSidePanelInset = compositionLocalOf { 0.dp }

/** The edge the app's cards cover, and the one its map buttons do. */
internal val cardEdge @Composable get() = if (LocalCardsAtEnd.current) CoverEdge.END else CoverEdge.START
internal val buttonEdge @Composable get() = if (LocalCardsAtEnd.current) CoverEdge.START else CoverEdge.END

/**
 * The floating nav bar, as far as the app's map is concerned. Its full [height] at once rather
 * than following the slide, so the app is told once instead of every frame.
 */
@Composable
internal fun BottomCover(height: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(height).covers("nav-bar", CoverEdge.BOTTOM))
}

/** Records where this lands, padding included, until it leaves the screen. */
@Composable
internal fun Modifier.covers(key: String, edge: CoverEdge): Modifier {
    val cover = LocalChromeCover.current ?: return this
    DisposableEffect(cover, key) { onDispose { cover.remove(key) } }
    return onGloballyPositioned { cover.put(key, edge, it.boundsInWindow()) }
}

// --- navigation ------------------------------------------------------------------------------

@Composable
private fun NavigationChrome(template: NavigationTemplate, modifier: Modifier) {
    val connection = LocalCarAppConnection.current
    val pan by connection.panMode.collectAsState()
    // Pan mode clears the map for looking around: only the buttons stay.
    val routing = (template.navigationInfo as? RoutingInfo)?.takeUnless { pan }
    val estimate = template.destinationTravelEstimate?.takeUnless { pan }
    NavigationBars(template, routing, estimate, modifier)
}

/**
 * The drive's card down the left, map on the right, the map buttons at the right edge. The whole column counts as covered, so the app centres its map in
 * what is left over and the car sits right of the cards rather than under them. Pan mode drops
 * the cards and gives the map the whole width.
 */
@Composable
private fun NavigationBars(
    template: NavigationTemplate,
    routing: RoutingInfo?,
    estimate: TravelEstimate?,
    modifier: Modifier,
) {
    val connection = LocalCarAppConnection.current
    val pan by connection.panMode.collectAsState()
    val atEnd = LocalCardsAtEnd.current
    val cardEdge = cardEdge
    val buttonEdge = buttonEdge
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        val column = cardColumnWidth(maxWidth)
        val cards = @Composable {
            if (routing != null || estimate != null) {
                Column(Modifier.width(column).fillMaxHeight().covers("nav-cards", cardEdge).padding(12.dp)) {
                    // As tall as what it holds, up to the column's height.
                    NavigationCard(
                        routing,
                        estimate,
                        showStop = !template.actionStrip.hasStop(),
                        Modifier.fillMaxWidth().weight(1f, fill = false),
                    )
                }
            }
        }
        val buttons = @Composable {
            RightEdgeControls(
                if (pan) null else template.actionStrip,
                template.mapActionStrip,
                Modifier.padding(12.dp),
                mapActionsModifier = Modifier.covers("nav-buttons", buttonEdge),
                alignment = if (atEnd) Alignment.Start else Alignment.End,
            )
        }
        Row(Modifier.fillMaxSize()) {
            if (atEnd) buttons() else cards()
            Spacer(Modifier.weight(1f))
            if (atEnd) cards() else buttons()
        }
    }
}

/**
 * The drive in one card, top to bottom: the manoeuvre big enough to read at a glance, the lanes
 * to be in, the turn after it, and the trip. On a short screen parts drop out whole, the lanes'
 * words first, then the next turn, then the lanes; the manoeuvre and the trip always stay.
 */
@Composable
private fun NavigationCard(info: RoutingInfo?, estimate: TravelEstimate?, showStop: Boolean, modifier: Modifier) {
    val step = info?.currentStep?.takeUnless { info.isLoading }
    ChromeSurface(modifier) {
        FitColumn {
            if (info?.isLoading == true) {
                Text(
                    stringResource(R.string.nav_finding_route),
                    Modifier.layoutId(0).padding(horizontal = 18.dp, vertical = 16.dp),
                    style = ChromeType.title,
                )
            }
            if (info != null && step != null) {
                ManeuverHeader(info, step, Modifier.layoutId(0).fillMaxWidth())
                // Drawn from the lane data whenever there is some, even beside the app's own picture.
                step.lanes.orEmpty().takeIf { it.isNotEmpty() }?.let {
                    LaneBand(it, Modifier.layoutId(2).fillMaxWidth())
                    LaneHintLine(it, Modifier.layoutId(4).fillMaxWidth())
                }
                info.nextStep?.let { NextTurn(it, Modifier.layoutId(3).fillMaxWidth()) }
            }
            estimate?.let { TripRow(it, showStop, Modifier.layoutId(1).fillMaxWidth()) }
        }
    }
}

/** The manoeuvre on the theme's colour: the arrow and the distance to it, what to do, onto which road. */
@Composable
private fun ManeuverHeader(info: RoutingInfo, step: Step, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.background(scheme.primaryContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides scheme.onPrimaryContainer,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CarGlyph(step.maneuver?.icon, Modifier.size(64.dp))
                Spacer(Modifier.width(14.dp))
                val distance = info.currentDistance.display()
                if (distance.isNotEmpty()) {
                    Text(
                        distance,
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                }
            }
            val cue = step.cue.text()
            if (cue.isNotEmpty()) {
                Text(cue, style = ChromeType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val road = step.road.text()
            // "Turn left onto Queensberry Drive" already says the road; saying it twice is noise.
            if (road.isNotEmpty() && !cue.contains(road, ignoreCase = true)) {
                Text(
                    road,
                    style = ChromeType.body,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // An app that sends only its own picture of the lanes gets it shown as it is.
            if (step.lanes.orEmpty().isEmpty()) {
                step.lanesImage?.let { JunctionImage(it, Modifier.heightIn(max = 56.dp)) }
            }
            // Rare, and only where an arrow can't explain the junction: worth the card growing for.
            info.junctionImage?.let { JunctionImage(it) }
        }
    }
}

/**
 * The turn after this one, as large as the instruction above it and wrapping rather than cut
 * short: leaving a motorway, the next move comes quickly and has to read in a glance.
 */
@Composable
private fun NextTurn(next: Step, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    // A shade apart from the trip below, so the two never read as one block of text.
    Row(
        modifier.background(scheme.surfaceContainerHighest).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CarGlyph(next.maneuver?.icon, Modifier.size(40.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                stringResource(R.string.nav_then).uppercase(java.util.Locale.getDefault()),
                style = ChromeType.label.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                color = scheme.primary,
            )
            val text = next.cue.text().ifEmpty { next.road.text() }
            if (text.isNotEmpty()) {
                Text(
                    text,
                    style = ChromeType.body.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A column that leaves whole parts out when they don't all fit, rather than cutting the last one
 * in half. Each part's layoutId is its rank: the lowest is kept first and always shown, then each
 * next one if there's still room for it. What's kept stays in its written order.
 */
@Composable
private fun FitColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        val parts = measurables.map { it.measure(loose) }
        val ranks = measurables.map { it.layoutId as? Int ?: 0 }
        val kept = BooleanArray(parts.size)
        var room = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        parts.indices.sortedBy { ranks[it] }.forEachIndexed { order, i ->
            if (order == 0 || parts[i].height <= room) {
                kept[i] = true
                room -= parts[i].height
            }
        }
        val width = (parts.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = parts.indices.filter { kept[it] }.sumOf { parts[it].height }
        layout(width, height.coerceAtLeast(constraints.minHeight)) {
            var y = 0
            parts.forEachIndexed { i, part ->
                if (kept[i]) {
                    part.place(0, y)
                    y += part.height
                }
            }
        }
    }
}

/** The zoomed-in intersection an app draws for a junction too complex for an arrow to explain. */
@Composable
private fun JunctionImage(icon: CarIcon, modifier: Modifier = Modifier) {
    val bitmap = icon.image(LocalContext.current) ?: return
    Image(
        bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.fillMaxWidth().heightIn(max = 120.dp),
    )
}

/**
 * The trip along the bottom of the card: time left, coloured as the app colours it (by traffic,
 * in Vela), then the arrival and the distance, each with what it is under it. Gearslip's stop
 * button sits at the end unless the app shows its own ([hasStop]). It ends navigation the way
 * going Home does: the connection stays up and the app decides its own next screen.
 */
@Composable
private fun TripRow(estimate: TravelEstimate, showStop: Boolean, modifier: Modifier) {
    val connection = LocalCarAppConnection.current
    val scheme = MaterialTheme.colorScheme
    val time = remainingTime(estimate.remainingTimeSeconds)
    val distance = estimate.remainingDistance.display()
    val arrival = estimate.arrivalTimeAtDestination.clockTime()
    val trip = estimate.tripText.text()
    if (time.isEmpty() && distance.isEmpty() && arrival.isEmpty() && trip.isEmpty()) return
    val dark = scheme.surface.luminanceIsDark()
    Column(modifier.padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            // The time leads, large and coloured; arrival and distance are smaller, each in its
            // own column behind a thin rule, with what it is under it.
            TripFigure(time, null, estimate.remainingTimeColor.color(dark, scheme.onSurface), ChromeType.headline, Modifier.weight(1.1f))
            TripRule()
            TripFigure(arrival, stringResource(R.string.nav_arrival), scheme.onSurface, ChromeType.body, Modifier.weight(1f))
            TripRule()
            TripFigure(distance, stringResource(R.string.nav_to_go), scheme.onSurface, ChromeType.body, Modifier.weight(1f))
            if (showStop) {
                GsIconButton(
                    Icons.Filled.Close,
                    stringResource(R.string.nav_stop),
                    { connection.stopNavigating() },
                    colors = GsColors(scheme.surfaceContainerHighest, scheme.onSurface),
                    size = 52.dp,
                    iconSize = 26.dp,
                    shape = CircleShape,
                )
            }
        }
        if (trip.isNotEmpty()) {
            Text(
                trip,
                Modifier.padding(top = 6.dp),
                style = ChromeType.body,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TripRule() {
    Box(
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp).width(1.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** One of the trip's figures with its label under it, shrinking to stay on one line in a narrow column. */
@Composable
private fun TripFigure(value: String, label: String?, color: Color, style: androidx.compose.ui.text.TextStyle, modifier: Modifier) {
    Column(modifier) {
        if (value.isEmpty()) return@Column
        var size by androidx.compose.runtime.remember(value, style) { androidx.compose.runtime.mutableStateOf(style.fontSize) }
        Text(
            value,
            style = style.copy(fontSize = size, fontWeight = FontWeight.Bold),
            color = color,
            maxLines = 1,
            softWrap = false,
            onTextLayout = { if (it.hasVisualOverflow && size.value > 12f) size *= 0.9f },
        )
        if (label != null) {
            Text(label, style = ChromeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Whether the app's own buttons include one that ends the drive: a red primary button, as
 * Vela's is, or one named for it. Actions carry no flag that says so, so this is the best sign.
 */
private fun androidx.car.app.model.ActionStrip?.hasStop(): Boolean = this?.actions.orEmpty().any { action ->
    val red = action.flags and androidx.car.app.model.Action.FLAG_PRIMARY != 0 &&
        action.backgroundColor?.type == androidx.car.app.model.CarColor.TYPE_RED
    red || action.title?.toString()?.let { STOP_WORDS.containsMatchIn(it) } == true
}

private val STOP_WORDS = Regex("\\b(stop|end|exit|quit|cancel)\\b", RegexOption.IGNORE_CASE)

// --- map with a side pane ----------------------------------------------------------------------

/**
 * The shape every remaining map-backed template shares: the app's map across the whole surface,
 * the template's content in a card floating over its left side, and both action strips stacked at
 * the right edge. The card is as tall as its content, up to the height of the screen, so a
 * handful of buttons no longer claim a whole column of the map. [fullHeight] is for content that
 * needs the room, such as a search field with its keyboard.
 */
@Composable
private fun MapPaneLayout(
    modifier: Modifier,
    actionStrip: androidx.car.app.model.ActionStrip?,
    mapActionStrip: androidx.car.app.model.ActionStrip?,
    fullHeight: Boolean = false,
    pane: @Composable () -> Unit,
) {
    val connection = LocalCarAppConnection.current
    // Pan mode hands the whole screen to the map, leaving only its own controls.
    val pan by connection.panMode.collectAsState()
    val edgeActions = (actionStrip?.actions.orEmpty() + mapActionStrip?.actions.orEmpty()).map { it.identity() }.toSet()
    val atEnd = LocalCardsAtEnd.current
    val cardEdge = cardEdge
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        if (!pan) {
            ChromeSurface(
                Modifier
                    .align(if (atEnd) Alignment.TopEnd else Alignment.TopStart)
                    .covers("map-pane", cardEdge)
                    .padding(12.dp)
                    .width(cardColumnWidth(maxWidth) - 24.dp)
                    .let { if (fullHeight) it.height(maxHeight - 24.dp) else it.heightIn(max = maxHeight - 24.dp) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                shadowElevation = 8.dp,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(LocalMapEdgeActions provides edgeActions) { pane() }
            }
        }
        RightEdgeControls(
            if (pan) null else actionStrip,
            mapActionStrip,
            Modifier.align(if (atEnd) Alignment.TopStart else Alignment.TopEnd).padding(12.dp),
            alignment = if (atEnd) Alignment.Start else Alignment.End,
        )
    }
}

@Composable
private fun MapWithContentChrome(template: MapWithContentTemplate, modifier: Modifier) {
    MapPaneLayout(
        modifier,
        template.actionStrip,
        template.mapController?.mapActionStrip,
        fullHeight = template.contentTemplate.needsFullHeight(),
    ) {
        // A pane floating over the map is a place or a route the app is proposing, so its one
        // action - Start - counts itself down rather than waiting to be pressed; the same pane
        // filling a whole screen elsewhere gets no such push.
        ContentTemplate(template.contentTemplate, Modifier, autoStartPane = true)
    }
}

@Composable
private fun MapChrome(template: MapTemplate, modifier: Modifier) {
    MapPaneLayout(
        modifier,
        template.actionStrip,
        template.mapController?.mapActionStrip,
    ) {
        ChromeSurface(Modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)) {
            Column {
                HeaderBar(
                    headerTitle(template.header, ""),
                    template.header?.startHeaderAction,
                    template.header?.endHeaderActions.orEmpty(),
                )
                if (template.pane != null) PaneRows(template.pane, autoStart = true)
                else ItemListColumn(template.itemList)
            }
        }
    }
}

@Composable
private fun PlaceListNavigationChrome(template: PlaceListNavigationTemplate, modifier: Modifier) {
    MapPaneLayout(modifier, template.actionStrip, template.mapActionStrip) {
        ListPane(
            headerTitle(template.header, template.title.text()),
            template.header?.startHeaderAction ?: template.headerAction,
            template.isLoading,
            template.itemList,
            Modifier,
        )
    }
}

@Composable
private fun PlaceListMapChrome(template: PlaceListMapTemplate, modifier: Modifier) {
    MapPaneLayout(modifier, template.actionStrip, null) {
        ListPane(
            template.title.text(),
            template.headerAction,
            template.isLoading,
            template.itemList,
            Modifier,
        )
    }
}

/** Route choices down the side, with the app's "start" action pinned under them. */
@Composable
private fun RoutePreviewChrome(template: RoutePreviewNavigationTemplate, modifier: Modifier) {
    MapPaneLayout(modifier, template.actionStrip, template.mapActionStrip) {
        ChromeSurface(Modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)) {
            Column {
                HeaderBar(
                    headerTitle(template.header, template.title.text()),
                    template.header?.startHeaderAction ?: template.headerAction,
                    template.header?.endHeaderActions.orEmpty(),
                )
                Box(Modifier.weight(1f, fill = false)) {
                    if (template.isLoading) {
                        Text(stringResource(R.string.nav_finding_routes), Modifier.padding(14.dp), style = ChromeType.title)
                    } else {
                        ItemListColumn(template.itemList)
                    }
                }
                template.navigateAction?.let {
                    AutoStartButton(it, Modifier.padding(12.dp))
                }
            }
        }
    }
}

@Composable
private fun ListPane(
    title: String,
    startAction: androidx.car.app.model.Action?,
    loading: Boolean,
    itemList: androidx.car.app.model.ItemList?,
    modifier: Modifier,
) {
    ChromeSurface(modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)) {
        Column {
            HeaderBar(title, startAction)
            if (loading) Text(stringResource(R.string.media_loading), Modifier.padding(14.dp), style = ChromeType.title)
            else ItemListColumn(itemList)
        }
    }
}



/**
 * The width of the cards beside the map, the same for a place card and for navigation so the
 * map doesn't jump sideways when a route starts.
 */
internal fun cardColumnWidth(maxWidth: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    (maxWidth * CARD_COLUMN_FRACTION).coerceIn(280.dp, 440.dp).coerceAtMost(maxWidth)

private const val CARD_COLUMN_FRACTION = 0.4f

/** Search, sign-in and the like are laid out for a whole column, so they keep one. */
private fun Template?.needsFullHeight() =
    this is androidx.car.app.model.SearchTemplate || this is androidx.car.app.model.signin.SignInTemplate ||
        this is androidx.car.app.model.TabTemplate || this is androidx.car.app.model.LongMessageTemplate
