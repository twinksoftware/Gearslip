package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.car.theme.DotGrid
import app.seb3thehacker.gearslip.car.theme.GsButton
import app.seb3thehacker.gearslip.car.theme.GsColors
import app.seb3thehacker.gearslip.car.theme.GsIconButton
import app.seb3thehacker.gearslip.car.theme.GsTone
import app.seb3thehacker.gearslip.host.CarAppConnection
import app.seb3thehacker.gearslip.mirror.PhoneMirror
import app.seb3thehacker.gearslip.notify.PhoneNavigation

private enum class Category(val label: String, val chipLabel: String) {
    FUEL("FUEL", "FUEL"), FOOD("FOOD", "FOOD"), PARKING("PARKING", "PARK"),
    CHARGING("CHARGING", "CHARGE"), HOTEL("HOTEL", "HOTEL"),
}

private data class DemoPlace(val name: String, val category: Category, val metadata: String, val distance: String)

/** Fixed sample rows. Distances are illustrative only — nothing here is looked up at runtime. */
private val DEMO_INDEX = listOf(
    DemoPlace("Shell — 5th Avenue", Category.FUEL, "24h · unleaded + diesel", "0.4 km"),
    DemoPlace("Blue Bottle Coffee", Category.FOOD, "Café · open until 20:00", "0.7 km"),
    DemoPlace("Central Parking Garage", Category.PARKING, "260 spaces · paid", "0.9 km"),
    DemoPlace("Tesla Supercharger", Category.CHARGING, "12 stalls · 250 kW", "1.2 km"),
    DemoPlace("Hotel Indigo", Category.HOTEL, "4 star · rooms available", "1.6 km"),
    DemoPlace("BP Connect", Category.FUEL, "Fuel + shop · open 24h", "2.1 km"),
)

/**
 * First-party place search for the head unit. There is no geocoder: "nearby places" come from a
 * fixed OFFLINE DEMO INDEX, so the screen stays useful with no network and no map SDK. A tap only
 * hands off to a real navigation app — it never pretends to route or geocode.
 */
@Composable
fun PlaceSearchScreen() {
    val context = LocalContext.current
    val turn by PhoneNavigation.turn.collectAsStateWithLifecycle()
    val navStatus by CarServices.nav.status.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<Category?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    val saved = remember(context) { SavedPlaces.all(context) }
    var here by remember { mutableStateOf<String?>(null) }
    val hasLocation = remember(context) { SavedPlaces.hasLocationPermission(context) }
    LaunchedEffect(hasLocation) { if (hasLocation) here = SavedPlaces.here(context) }

    // Active = a nav app on the car screen or a phone map app guiding. In that case GO keeps the
    // existing route rather than disturbing it; otherwise it mirrors Google Maps from the phone.
    val navActive = navStatus.phase == CarAppConnection.Phase.RUNNING || turn != null
    val onGo: (String) -> Unit = { label ->
        selected = label
        when {
            navActive -> message = "Navigation is already active — keeping the current route"
            GoogleMaps.installed(context) -> {
                message = "Opening Google Maps on the phone"
                PhoneMirror.open(context, GoogleMaps.PACKAGE)
            }
            else -> message = "Google Maps is not installed on the phone"
        }
    }
    val results = remember(query, category) {
        DEMO_INDEX.filter { place ->
            (category == null || place.category == category) &&
                (query.isBlank() || place.name.contains(query, true) || place.metadata.contains(query, true))
        }
    }
    val content: @Composable () -> Unit = {
        SearchContent(query, { query = it }, category, { category = it }, results, saved, here, hasLocation, turn, selected, message, onGo)
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = LocalNavBarInset.current).padding(16.dp)) {
        if (maxWidth >= 720.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) { content() }
                // The one dotted zone on the page lives in the schematic map panel.
                MapPreview(Modifier.width(340.dp).fillMaxHeight(), selected)
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) { content() }
        }
    }
}

/** Search, saved places, active turn and result list — shared by the narrow and wide layouts. */
@Composable
private fun SearchContent(
    query: String,
    onQuery: (String) -> Unit,
    category: Category?,
    onCategory: (Category?) -> Unit,
    results: List<DemoPlace>,
    saved: List<SavedPlace>,
    here: String?,
    hasLocation: Boolean,
    turn: PhoneNavigation.Turn?,
    selected: String?,
    message: String?,
    onGo: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ScreenHeader("PLACE SEARCH")
    Label("FIND A PLACE")
    SearchField(query, onQuery)
    CategoryChips(category, onCategory)

    turn?.let { live ->
        Row(
            Modifier.fillMaxWidth().border(1.dp, scheme.error).background(scheme.errorContainer)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val arrow = live.arrow
            if (arrow != null) Image(arrow.asImageBitmap(), "Turn arrow", Modifier.size(40.dp))
            else Icon(Icons.Filled.Place, "Turn arrow", Modifier.size(40.dp), tint = scheme.error)
            Column(Modifier.weight(1f)) {
                Text(live.distanceText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(live.road, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            Text("NAVIGATING", style = MaterialTheme.typography.labelSmall, color = scheme.error)
        }
    }

    Label("SAVED PLACES")
    saved.forEachIndexed { index, place ->
        val name = when (index) {
            SavedPlaces.HOME -> "HOME"
            SavedPlaces.WORK -> "WORK"
            else -> place.label
        }
        if (name.isNotBlank()) SavedRow(
            icon = if (index == SavedPlaces.HOME) Icons.Filled.Home else Icons.Filled.Place,
            name = name,
            detail = place.address.ifBlank { "NOT SET" },
            onGo = { onGo(name) },
        )
    }
    LocationRow(here, hasLocation)

    Label("OFFLINE DEMO INDEX")
    Text("SAMPLE DATA — NOT A LIVE GEOCODER", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    if (results.isEmpty()) {
        Text("NO PLACES MATCH THIS DEMO INDEX", style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
    } else {
        results.forEach { place -> ResultRow(place, onGo) }
    }
    if (message != null || selected != null) {
        Text(
            listOfNotNull(message, selected?.let { "SELECTED: $it" }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.error,
        )
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).border(1.dp, scheme.outline)
            .background(scheme.surfaceContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, "Search", Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("SEARCH NEARBY PLACES", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    inner()
                },
            )
            if (query.isNotEmpty()) GsIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "Clear search",
                onClick = { onQuery("") },
                size = 40.dp,
                iconSize = 18.dp,
                colors = GsColors(Color.Transparent, scheme.onSurfaceVariant),
            )
        }
    }
}

@Composable
private fun CategoryChips(selected: Category?, onSelect: (Category?) -> Unit) {
    // Short labels keep every chip fully readable on the 800dp head unit; the full label is
    // still used in result rows and as the icon's content description.
    val chips: List<Pair<Category?, String>> = listOf(null to "ALL") + Category.entries.map { it to it.chipLabel }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { (value, label) ->
            val on = value == selected
            val scheme = MaterialTheme.colorScheme
            GsButton(
                onClick = { onSelect(value) },
                modifier = Modifier.height(52.dp).weight(1f),
                colors = if (on) GsColors(scheme.primary, scheme.onPrimary)
                else GsColors(Color.Transparent, scheme.onSurface, scheme.outline),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
            ) { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
        }
    }
}

@Composable
private fun ResultRow(place: DemoPlace, onGo: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).border(1.dp, scheme.outlineVariant)
            .background(scheme.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Place, place.category.label, Modifier.size(24.dp), tint = scheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(place.metadata, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Text(place.category.label, style = MaterialTheme.typography.labelSmall, color = scheme.error)
        }
        Text(place.distance, style = MaterialTheme.typography.titleMedium)
        GsButton(onClick = { onGo(place.name) }, tone = GsTone.Danger) { Text("GO", style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun SavedRow(icon: ImageVector, name: String, detail: String, onGo: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).border(1.dp, scheme.outlineVariant)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, name, Modifier.size(24.dp), tint = scheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GsButton(onClick = onGo) { Text("GO", style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun LocationRow(here: String?, hasLocation: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.LocationOn, "Current location", Modifier.size(20.dp), tint = scheme.error)
        Column {
            Text("CURRENT LOCATION", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text(
                when {
                    !hasLocation -> "LOCATION PERMISSION OFF"
                    here != null -> here
                    else -> "LOCATING…"
                },
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A schematic map built from Compose primitives: the dot grid crossed by a red route and pin. */
@Composable
private fun MapPreview(modifier: Modifier, selected: String?) {
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.error
    Box(modifier.border(1.dp, scheme.outline).background(scheme.surfaceContainerLow)) {
        DotGrid(Modifier.fillMaxSize(), dotColor = scheme.onSurface.copy(alpha = 0.10f))
        Canvas(Modifier.fillMaxSize()) {
            fun route(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(accent, Offset(x1, y1), Offset(x2, y2), strokeWidth = 6f, cap = StrokeCap.Round)
            val w = size.width
            val h = size.height
            route(w * 0.18f, h * 0.78f, w * 0.40f, h * 0.58f)
            route(w * 0.40f, h * 0.58f, w * 0.66f, h * 0.62f)
            route(w * 0.66f, h * 0.62f, w * 0.74f, h * 0.32f)
            drawCircle(Color.Black, radius = 18f, center = Offset(w * 0.74f, h * 0.32f))
            drawCircle(accent, radius = 13f, center = Offset(w * 0.74f, h * 0.32f))
            drawCircle(Color.White, radius = 5f, center = Offset(w * 0.74f, h * 0.32f))
            drawCircle(accent, radius = 8f, center = Offset(w * 0.18f, h * 0.78f))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text("MAP PREVIEW", style = MaterialTheme.typography.labelMedium)
            Text("SCHEMATIC · OFFLINE", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            if (selected != null) Text(selected, style = MaterialTheme.typography.bodySmall, color = accent,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 300.dp).padding(top = 4.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp))
}
