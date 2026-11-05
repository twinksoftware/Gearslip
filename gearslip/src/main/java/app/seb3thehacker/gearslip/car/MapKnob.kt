package app.seb3thehacker.gearslip.car

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import app.seb3thehacker.gearslip.host.CarAppConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.min
import kotlin.math.pow

/**
 * The knob on a full-screen map, as in Android Auto: turning zooms, nudging pans, and pressing
 * brings up the map's buttons for the knob to move between. Back puts them away and gives the
 * knob back to the map. Issue #74: on a Mazda 3, every turn stepped through each map button.
 *
 * [width] and [height] are the app's surface in pixels; zooms centre on its middle.
 * [clearFocus] drops focus off whatever button the knob was on.
 */
internal class MapKnob(
    private val connection: CarAppConnection,
    private val width: Int,
    private val height: Int,
    private val clearFocus: () -> Unit,
) : CarKeys.KnobTaker {

    private val main = Handler(Looper.getMainLooper())
    /** The press that opened the buttons lifts afterwards; that lift belongs to no button. */
    @Volatile private var swallowLift = false

    override fun take(event: CarKeys.KnobEvent): Boolean {
        // A card over the map with buttons of its own (an app's alert, a call, a message, the
        // assistant, Bluetooth pairing) needs the knob more than the map does.
        if (overlayUp()) return false
        if (_buttonsOpen.value) {
            if (event is CarKeys.KnobEvent.Key && !event.down && swallowLift && event.keyCode in PRESS_KEYS) {
                swallowLift = false
                return true
            }
            if (event == CarKeys.KnobEvent.Back) {
                main.post { close() }
                return true
            }
            return false
        }
        when (event) {
            is CarKeys.KnobEvent.Turn -> main.post { zoom(event.steps) }
            is CarKeys.KnobEvent.Key -> when (event.keyCode) {
                in PRESS_KEYS -> if (event.down) main.post { open() }
                else -> if (event.down) main.post { pan(event.keyCode) }
            }
            CarKeys.KnobEvent.Back -> return false
        }
        return true
    }

    private fun overlayUp(): Boolean =
        CarAlerts.shown.value != null ||
            app.seb3thehacker.gearslip.call.CarCalls.state.value is app.seb3thehacker.gearslip.call.CallUiState.Ringing ||
            app.seb3thehacker.gearslip.notify.CarNotifications.popup.value != null ||
            CarAssistant.state.value.phase != CarAssistant.Phase.IDLE ||
            CarBluetooth.prompt.value != CarBluetooth.Prompt.NONE

    private fun zoom(steps: Int) {
        val clicks = steps.coerceIn(-MAX_STEPS, MAX_STEPS)
        connection.surfaceScale(width / 2f, height / 2f, ZOOM_STEP.pow(clicks))
    }

    /** One nudge moves the map a sixth of the screen. Scroll distances run previous minus current. */
    private fun pan(keyCode: Int) {
        val step = min(width, height) / 6f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> connection.surfaceScroll(0f, -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> connection.surfaceScroll(0f, step)
            KeyEvent.KEYCODE_DPAD_LEFT -> connection.surfaceScroll(-step, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> connection.surfaceScroll(step, 0f)
        }
    }

    private fun open() {
        swallowLift = true
        _buttonsOpen.value = true
        // The map's buttons take focus when they see this. A map showing none gets a Tab, so the
        // knob still lands somewhere.
        if (controls == 0) CarKeys.focusSink?.let { it(KeyEvent.KEYCODE_TAB, true); it(KeyEvent.KEYCODE_TAB, false) }
    }

    private fun close() {
        _buttonsOpen.value = false
        clearFocus()
    }

    /** Puts the buttons away when the map leaves the screen. */
    fun release() {
        _buttonsOpen.value = false
    }

    companion object {
        private val _buttonsOpen = MutableStateFlow(false)

        /** The knob pressed on the map: its buttons show and take focus, until Back. */
        val buttonsOpen: StateFlow<Boolean> = _buttonsOpen

        /** How many stacks of map buttons are on screen, for [open] to know whether any will take focus. */
        @Volatile var controls = 0

        private const val ZOOM_STEP = 1.25f
        private const val MAX_STEPS = 5
        private val PRESS_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
    }
}
