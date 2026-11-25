package app.seb3thehacker.gearslip.car

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Full screen for the screen that asks for it (the browser, or a video in it): no header and no
 * nav bar. A swipe in from the top or bottom edge brings the nav bar back for a few seconds,
 * with a button to leave full screen; [revealed] says when.
 */
object CarFullscreen {

    var active by mutableStateOf(false)
        private set

    var revealed by mutableStateOf(false)
        private set

    private var lastTouch = 0L

    fun enter() {
        active = true
        revealed = false
    }

    fun exit() {
        active = false
        revealed = false
    }

    /** A swipe in from an edge: show the nav bar and the way out. */
    fun reveal() {
        if (!active) return
        lastTouch = SystemClock.uptimeMillis()
        revealed = true
    }

    /** Any touch keeps a revealed bar up, so it doesn't slide away under a finger reaching for it. */
    fun touch() {
        if (revealed) lastTouch = SystemClock.uptimeMillis()
    }

    /** Hides the bar again after [REVEAL_MS] without a touch. */
    fun tick() {
        if (revealed && SystemClock.uptimeMillis() - lastTouch >= REVEAL_MS) revealed = false
    }

    /** How long until [tick] would hide the bar; null when it isn't showing. */
    fun msUntilChange(): Long? =
        if (revealed) (REVEAL_MS - (SystemClock.uptimeMillis() - lastTouch)).coerceAtLeast(0) else null

    private const val REVEAL_MS = 4_000L
}
