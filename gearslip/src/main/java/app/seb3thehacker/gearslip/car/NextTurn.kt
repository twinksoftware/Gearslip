package app.seb3thehacker.gearslip.car

import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import app.seb3thehacker.gearslip.host.CarAppConnection
import app.seb3thehacker.gearslip.host.display
import app.seb3thehacker.gearslip.host.text
import app.seb3thehacker.gearslip.notify.PhoneNavigation

/**
 * The next manoeuvre, as the running nav app last described it, or [fromPhone] a map guiding on
 * the phone. Lifted out of the retired nav bar so the screensaver can still show the turn.
 */
internal class NextTurn(val icon: CarIcon?, val distance: String, val road: String, val fromPhone: Boolean = false)

/**
 * Read straight off the nav app's own template: a navigation app keeps sending its routing card
 * while it's running, whether or not the map is the screen being shown.
 */
@Composable
internal fun rememberNextTurn(): NextTurn? {
    val status by CarServices.nav.status.collectAsState()
    val template by CarServices.nav.template.collectAsState()
    CarSettings.units.collectAsState().value
    val phone by PhoneNavigation.turn.collectAsState()
    phone?.let { return phoneTurn(it) }
    val routing = ((template as? NavigationTemplate)?.navigationInfo as? RoutingInfo)
        ?.takeIf { status.phase == CarAppConnection.Phase.RUNNING }
    val step = routing?.takeIf { !it.isLoading }?.currentStep ?: return null
    return NextTurn(
        icon = step.maneuver?.icon,
        distance = routing.currentDistance.display(),
        road = step.road.text().ifEmpty { step.cue.text() },
    )
}

/** Wall-clock time, ticking on the minute: no reason to redraw (and re-encode) more often. */
@Composable
internal fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(60_000 - value % 60_000)
    }
}

@Composable
private fun phoneTurn(turn: PhoneNavigation.Turn): NextTurn {
    val icon = remember(turn.arrow) {
        turn.arrow?.let {
            CarIcon.Builder(androidx.core.graphics.drawable.IconCompat.createWithBitmap(it)).build()
        }
    }
    return NextTurn(icon, turn.distance?.display() ?: turn.distanceText, turn.road, fromPhone = true)
}
