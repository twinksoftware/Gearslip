package app.seb3thehacker.gearslip.car

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One thing the car told us, already worded for a glance: the label is short and uppercase, the
 * value carries its own unit. [alert] marks the readings worth a colour (a low tank), never a
 * whole panel.
 */
internal class VehicleFact(val label: String, val value: String, val alert: Boolean = false)

/**
 * The car's own readings, in the order a driver would look for them. Only what the head unit has
 * actually sent is listed - an unsupported sensor simply leaves its row out rather than showing
 * a dash - and [limit] keeps the corners of a screen from filling with data nobody asked for.
 */
@Composable
internal fun rememberVehicleFacts(limit: Int = 5): List<VehicleFact> {
    // Sampled rather than collected: sensors can arrive many times a second, and a corner of a
    // resting screen has no reason to redraw (and re-encode) on every speed sample. Null until
    // the first sample, so the flow is only read from the coroutine, never during composition.
    val snapshot by produceState<CarData.Snapshot?>(null) {
        while (true) {
            value = CarData.state.value
            delay(1_000)
        }
    }
    val s = snapshot ?: return emptyList()
    val imperial = CarSettings.imperial()
    val facts = buildList {
        val low = s.fuelLow == true || (s.fuelPercent ?: 100) <= 15
        when {
            s.fuelPercent != null -> add(VehicleFact("FUEL", "${s.fuelPercent}%", low))
            s.fuelLow == true -> add(VehicleFact("FUEL", "LOW", true))
        }
        s.rangeKm?.let { add(VehicleFact("RANGE", distance(it, imperial))) }
        s.speedMps?.let { mps ->
            val value = if (imperial) mps * 2.23694f else mps * 3.6f
            add(VehicleFact("SPEED", "${value.roundToInt()} ${if (imperial) "MPH" else "KM/H"}"))
        }
        s.odometerKm?.let { add(VehicleFact("ODOMETER", distance(it.roundToInt(), imperial))) }
        s.compassDegrees?.let {
            add(VehicleFact("HEADING", "${cardinal(it)} ${((it % 360f + 360f) % 360f).roundToInt()}°"))
        }
    }
    return if (limit >= facts.size) facts else facts.take(limit)
}

private fun distance(km: Int, imperial: Boolean): String {
    val value = if (imperial) (km * 0.621371f).roundToInt() else km
    return "${String.format(Locale.getDefault(), "%,d", value)} ${if (imperial) "MI" else "KM"}"
}

private fun cardinal(degrees: Float): String {
    val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val at = (((degrees % 360f + 360f) % 360f) / 45f).roundToInt() % 8
    return points[at]
}
