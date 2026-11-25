package app.seb3thehacker.gearslip.car

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import android.os.SystemClock

/**
 * Whether the map's buttons (settings, pan, zoom, locate) are showing.
 *
 * They fade out after [IDLE_MS] with no touch anywhere on the car screen, to leave the map clear,
 * and any touch brings them back. [touch] is called for every pointer event and is cheap: it only
 * writes state when the buttons were actually hidden. The nav bar, when set to hide, goes the
 * same way but waits longer ([NAV_BAR_IDLE_MS]).
 */
class MapControlsWake {

    var awake by mutableStateOf(true)
        private set

    var navBarAwake by mutableStateOf(true)
        private set

    private var lastTouch = SystemClock.uptimeMillis()

    fun touch() {
        lastTouch = SystemClock.uptimeMillis()
        if (!awake) awake = true
        if (!navBarAwake) navBarAwake = true
    }

    /** Hides the buttons once nothing has touched the screen for [IDLE_MS]. */
    fun tick() {
        val idle = SystemClock.uptimeMillis() - lastTouch
        if (awake && idle >= IDLE_MS) awake = false
        if (navBarAwake && idle >= NAV_BAR_IDLE_MS) navBarAwake = false
    }

    /** How long until [tick] would hide something; null when everything's already hidden. */
    fun msUntilChange(): Long? {
        val idle = SystemClock.uptimeMillis() - lastTouch
        return listOfNotNull(
            (IDLE_MS - idle).takeIf { awake },
            (NAV_BAR_IDLE_MS - idle).takeIf { navBarAwake },
        ).minOrNull()?.coerceAtLeast(0)
    }

    companion object {
        const val IDLE_MS = 4_000L
        const val NAV_BAR_IDLE_MS = 8_000L
    }
}

val LocalMapControlsWake = staticCompositionLocalOf { MapControlsWake() }
