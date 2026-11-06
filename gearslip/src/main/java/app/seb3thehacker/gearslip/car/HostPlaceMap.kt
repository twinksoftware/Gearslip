package app.seb3thehacker.gearslip.car

import android.graphics.Color as AndroidColor
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.seb3thehacker.gearslip.GearslipLog
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * The map behind a [PlaceListMapTemplate]. For this template the host draws the map, not the
 * app: Android Auto puts Google's map there, with a numbered pin for each place in the list and
 * the anchor marked. RF Sentinel lists its nearby places this way, and with nothing drawn the
 * area stayed empty.
 *
 * Tiles come from OpenFreeMap: free, no key, and no account, so nothing ties a drive to anyone.
 * The camera fits every pin into the part of the map the list card doesn't cover.
 */
@Composable
internal fun HostPlaceMap(template: PlaceListMapTemplate, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val pin = MaterialTheme.colorScheme.primary.toArgb()
    val pinText = MaterialTheme.colorScheme.onPrimary.toArgb()
    val anchorColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val cardsAtEnd = LocalCardsAtEnd.current
    val places = remember(template) { placesOf(template) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        // Keep the pins out from under the list card, the way the app's own map would.
        val card = with(density) { cardColumnWidth(maxWidth).roundToPx() }
        val margin = with(density) { 48.dp.roundToPx() }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MapLibre.getInstance(context)
                // A TextureView, so the map composites with the cards drawn over it inside the
                // car's virtual display, as any other view does.
                val options = MapLibreMapOptions.createFromAttributes(context).textureMode(true)
                MapView(context, options).apply {
                    onCreate(null)
                    onStart()
                    onResume()
                    getMapAsync { m ->
                        m.uiSettings.isLogoEnabled = false
                        m.uiSettings.isCompassEnabled = false
                        m.uiSettings.isRotateGesturesEnabled = false
                        m.uiSettings.isTiltGesturesEnabled = false
                        map = m
                    }
                }
            },
            onRelease = { view ->
                runCatching {
                    view.onPause()
                    view.onStop()
                    view.onDestroy()
                }
            },
        )

        // The style follows the car's theme; the pins go in once it's loaded.
        LaunchedEffect(map, dark) {
            val m = map ?: return@LaunchedEffect
            style = null
            m.setStyle(Style.Builder().fromUri(if (dark) DARK_STYLE else LIGHT_STYLE)) { loaded -> style = loaded }
        }
        LaunchedEffect(style, places, pin, card, cardsAtEnd) {
            val m = map ?: return@LaunchedEffect
            val s = style ?: return@LaunchedEffect
            showPins(s, places, pin, pinText, anchorColor)
            val points = places.map { it.latLng }
            when {
                points.isEmpty() -> Unit
                points.size == 1 -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(points.first(), SINGLE_PLACE_ZOOM))
                else -> {
                    val bounds = LatLngBounds.Builder().includes(points).build()
                    val left = if (cardsAtEnd) margin else card + margin
                    val right = if (cardsAtEnd) card + margin else margin
                    runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, left, margin, right, margin)) }
                        .onFailure { GearslipLog.w("host map: couldn't fit ${points.size} places: ${it.message}") }
                }
            }
        }
        DisposableEffect(Unit) { onDispose { map = null } }
    }
}

/** One pin: where, and the label on it (the list's number, or the app's own marker text). */
private class Pin(val latLng: LatLng, val label: String, val anchor: Boolean)

private fun placesOf(template: PlaceListMapTemplate): List<Pin> {
    val rows = template.itemList?.items.orEmpty().filterIsInstance<Row>()
    val pins = rows.mapIndexedNotNull { i, row ->
        val place = row.metadata?.place ?: return@mapIndexedNotNull null
        pinOf(place, (i + 1).toString(), anchor = false)
    }
    return pins + listOfNotNull(template.anchor?.let { pinOf(it, "", anchor = true) })
}

private fun pinOf(place: Place, fallback: String, anchor: Boolean): Pin? {
    val location = place.location
    if (location.latitude == 0.0 && location.longitude == 0.0) return null
    val label = place.marker?.label?.toString()?.takeIf { it.isNotBlank() } ?: fallback
    return Pin(LatLng(location.latitude, location.longitude), label, anchor)
}

private fun showPins(style: Style, pins: List<Pin>, pin: Int, pinText: Int, anchorColor: Int) {
    val features = FeatureCollection.fromFeatures(
        pins.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.latLng.longitude, p.latLng.latitude)).apply {
                addStringProperty("label", p.label)
                addBooleanProperty("anchor", p.anchor)
            }
        },
    )
    val source = style.getSourceAs<GeoJsonSource>(SOURCE)
    if (source != null) {
        source.setGeoJson(features)
        return
    }
    style.addSource(GeoJsonSource(SOURCE, features))
    style.addLayer(
        CircleLayer(PIN_LAYER, SOURCE).withProperties(
            PropertyFactory.circleRadius(Expression.switchCase(Expression.get("anchor"), Expression.literal(9f), Expression.literal(14f))),
            PropertyFactory.circleColor(Expression.switchCase(Expression.get("anchor"), Expression.color(anchorColor), Expression.color(pin))),
            PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    style.addLayer(
        SymbolLayer(LABEL_LAYER, SOURCE).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(13f),
            PropertyFactory.textColor(pinText),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
        ),
    )
}

private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/positron"
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val SOURCE = "gearslip-places"
private const val PIN_LAYER = "gearslip-place-pins"
private const val LABEL_LAYER = "gearslip-place-labels"
private const val SINGLE_PLACE_ZOOM = 14.0
