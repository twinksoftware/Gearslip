package app.seb3thehacker.gearslip.car

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.call.CallUiState
import app.seb3thehacker.gearslip.call.CarCalls
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The car's own buttons: steering wheel, rotary knob, D-pad and the hard keys around the screen.
 *
 * The head unit sends Android keycodes as they are, so the numbers here are android.view.KeyEvent's.
 * Media, voice and call keys act straight away. Keys that move around the car UI (back, home, the
 * knob) need a [CarNavigator], which only [CarUi] has, so they go out on [screenKeys]. Focus keys
 * (D-pad, knob turns, OK) become real key events on the car's display through [focusSink], where
 * Compose moves focus between buttons the same way it would with a keyboard.
 */
object CarKeys {

    /** A key that changes which screen is showing. */
    enum class ScreenKey { BACK, HOME, MAP, MEDIA, PHONE }

    private val _screenKeys = MutableSharedFlow<ScreenKey>(extraBufferCapacity = 8)
    val screenKeys: SharedFlow<ScreenKey> = _screenKeys

    private val _focusMoves = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** A knob turn or D-pad press, so controls that hide when left alone can come back. */
    val focusMoves: SharedFlow<Unit> = _focusMoves

    /** Delivers an Android key event to the car display; set while the car UI is showing. */
    @Volatile var focusSink: ((keyCode: Int, down: Boolean) -> Unit)? = null

    /** What a [KnobTaker] is offered: a turn of the knob, a focus key edge, or Back. */
    sealed interface KnobEvent {
        data class Turn(val steps: Int) : KnobEvent
        data class Key(val keyCode: Int, val down: Boolean) : KnobEvent
        data object Back : KnobEvent
    }

    /**
     * Something on screen that wants the knob before focus moves: the full-screen map, which
     * zooms and pans with it ([MapKnob]), and the app grid, which starts on the app opened last.
     * Returns true for an event it kept. Called on the thread the keys arrive on, not the main one.
     */
    fun interface KnobTaker {
        fun take(event: KnobEvent): Boolean
    }

    @Volatile var knobTaker: KnobTaker? = null

    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) { appContext = context.applicationContext }

    /** The keys that act on the media app. */
    private val MEDIA_KEYS = setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    )

    /**
     * Every key worth asking the head unit for. The binding request lists what the head unit
     * offered; when it offers nothing (some units leave the list empty and send keys anyway),
     * this is what's asked for instead.
     */
    val WANTED = listOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KEYCODE_ROTARY_CONTROLLER, KEYCODE_MEDIA, KEYCODE_NAVIGATION, KEYCODE_TEL,
    )

    /**
     * One key edge from the head unit. Most keys act on the press; focus keys pass both edges on,
     * so a held D-pad key repeats the way it would anywhere else. Returns false for a key nothing
     * here knows, for the caller to log.
     */
    fun onKey(keyCode: Int, down: Boolean, longPress: Boolean): Boolean {
        if (keyCode in FOCUS_KEYS) {
            if (knobTaker?.take(KnobEvent.Key(keyCode, down)) == true) return true
            _focusMoves.tryEmit(Unit)
            focusSink?.invoke(keyCode, down)
            return true
        }
        if (!down) return keyCode in WANTED
        // Null until the car shows Gearslip's screen. A 2020 Civic sends Play as soon as it hears
        // the phone's Bluetooth address, while still on its own screen, and reaching for media
        // then ended the session (issue #43).
        val media = CarServices.mediaOrNull()
        if (media == null && keyCode in MEDIA_KEYS) {
            GearslipLog.i("keys: $keyCode before the car shows Gearslip - no media app to send it to yet")
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> media?.togglePlay()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (media != null && !media.now.value.playing) media.togglePlay()
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> if (media != null && media.now.value.playing) media.togglePlay()
            KeyEvent.KEYCODE_MEDIA_NEXT -> media?.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> media?.previous()
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST -> voice()
            KeyEvent.KEYCODE_CALL -> call()
            KeyEvent.KEYCODE_ENDCALL -> endCall()
            KeyEvent.KEYCODE_BACK -> if (knobTaker?.take(KnobEvent.Back) != true) screen(ScreenKey.BACK)
            KeyEvent.KEYCODE_HOME -> screen(ScreenKey.HOME)
            KEYCODE_NAVIGATION -> screen(ScreenKey.MAP)
            KEYCODE_MEDIA -> screen(ScreenKey.MEDIA)
            KEYCODE_TEL -> screen(ScreenKey.PHONE)
            else -> return false
        }
        if (longPress) GearslipLog.i("keys: long press on $keyCode")
        return true
    }

    /** A turn of the rotary knob: each click moves focus one step, like Tab or Shift+Tab. */
    fun onRotate(delta: Int) {
        val sink = focusSink ?: return
        if (knobTaker?.take(KnobEvent.Turn(delta)) == true) return
        _focusMoves.tryEmit(Unit)
        val code = if (delta > 0) KeyEvent.KEYCODE_TAB else KEYCODE_SHIFT_TAB
        repeat(kotlin.math.abs(delta).coerceAtMost(MAX_ROTARY_STEPS)) {
            sink(code, true)
            sink(code, false)
        }
    }

    /**
     * The voice key: answers the message on screen if there is one, otherwise starts the
     * assistant.
     */
    private fun voice() = main.post {
        if (!VoiceReply.onVoiceKey()) CarAssistant.start()
    }

    /** Answers a ringing call; with nothing ringing, opens the phone screen. */
    private fun call() = main.post {
        val context = appContext ?: return@post
        if (CarCalls.state.value is CallUiState.Ringing) CarCalls.answer(context) else screen(ScreenKey.PHONE)
    }

    private fun endCall() = main.post {
        if (CarCalls.state.value is CallUiState.Ringing) CarCalls.decline()
        else GearslipLog.i("keys: end call pressed with no ringing call; hanging up needs the dialer")
    }

    private fun screen(key: ScreenKey) { _screenKeys.tryEmit(key) }

    // aap_protobuf's KeyCode.proto: car-only keys past the end of Android's own range.
    const val KEYCODE_ROTARY_CONTROLLER = 65536
    const val KEYCODE_MEDIA = 65537
    const val KEYCODE_NAVIGATION = 65538
    const val KEYCODE_TEL = 65540

    /** Stands in for Shift+Tab: the sink sends Tab with the shift meta state. */
    const val KEYCODE_SHIFT_TAB = -KeyEvent.KEYCODE_TAB

    private const val MAX_ROTARY_STEPS = 5

    private val FOCUS_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    )
}
