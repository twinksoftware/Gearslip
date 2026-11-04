package app.seb3thehacker.gearslip

import android.app.ActivityOptions
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Renders the car UI into the encoder's input Surface.
 *
 * Uses the same mechanism as Android screen mirroring: a VirtualDisplay backed by the
 * MediaCodec input Surface, with a Presentation hosting a Compose view on that display.
 * Anything drawn there is encoded and projected - no hand-written EGL/GL.
 *
 * Everything here must run on the main thread: Presentation is a Dialog.
 */
class ScreenProjector(private val context: Context) {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var owner: ViewTreeOwner? = null
    private var root: View? = null

    /** Tracks the synthetic gesture so injected MotionEvents have a coherent downTime. */
    private var downTime = 0L

    /**
     * [name] names the virtual display so the two callers are told apart from outside the app
     * (`dumpsys display`): the real car session keeps "gearslip-projection", the on-phone bench
     * preview passes "gearslip-preview".
     */
    fun start(
        surface: Surface?,
        width: Int,
        height: Int,
        densityDpi: Int,
        name: String = "gearslip-projection",
        content: @Composable () -> Unit,
    ) {
        stop()

        val displayManager = context.getSystemService(DisplayManager::class.java)

        // OWN_CONTENT_ONLY, not PUBLIC: we render our own Presentation rather than mirroring
        // the phone screen. PUBLIC marks the display as screen sharing, which the platform
        // refuses without ADD_MIRROR_DISPLAY/CAPTURE_VIDEO_OUTPUT or a MediaProjection token -
        // and going the MediaProjection route would put a consent dialog in front of the
        // driver on every connect.
        val display = try {
            displayManager.createVirtualDisplay(
                name, width, height, densityDpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
        } catch (t: Throwable) {
            GearslipLog.e("createVirtualDisplay failed", t)
            null
        }
        if (display == null) {
            GearslipLog.e("createVirtualDisplay returned null - cannot project")
            return
        }
        virtualDisplay = display

        val show = Presentation(context, display.display)
        val window = checkNotNull(show.window) { "presentation has no window" }
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // A ComposeView outside an Activity has no lifecycle, saved-state or view-model owner
        // to find up its view tree, and refuses to compose without them. Hand it ours.
        val treeOwner = ViewTreeOwner()
        val decor = window.decorView
        decor.setViewTreeLifecycleOwner(treeOwner)
        decor.setViewTreeSavedStateRegistryOwner(treeOwner)
        decor.setViewTreeViewModelStoreOwner(treeOwner)
        decor.setBackgroundColor(Color.BLACK)

        show.setContentView(ComposeView(show.context).apply { setContent(content) })
        show.show()

        presentation = show
        owner = treeOwner
        root = decor

        GearslipLog.i("projecting ${width}x$height @ ${densityDpi}dpi")
    }

    /** The projected display's id, for placing other apps' activities on it. */
    val displayId: Int? get() = virtualDisplay?.display?.displayId

    /**
     * Shows or hides our own Compose UI. A Presentation is a TYPE_PRESENTATION window, so it
     * draws above any activity on the same display and must be hidden to reveal one.
     */
    fun setOverlayVisible(visible: Boolean) {
        if (visible) presentation?.show() else presentation?.hide()
    }

    /**
     * Launches another app's activity onto the projected display. The Presentation is hidden
     * first, since it would otherwise draw over the app.
     *
     * Does not work yet for third-party apps, and measuring why is recorded here so the next
     * attempt doesn't repeat it. Two separate platform gates, both confirmed on Android 17:
     *  1. `setLaunchDisplayId` requires INTERNAL_SYSTEM_WINDOW in the *caller*. We don't hold
     *     it, so this throws SecurityException. Launching the same activity from a shell-uid
     *     caller places the task on this display fine.
     *  2. Even once placed, the task stays visible=false, because this display is created
     *     FLAG_PRIVATE - a private display only ever shows its owner's windows.
     * Fixing (2) needs a display created with VIRTUAL_DISPLAY_FLAG_TRUSTED, which needs the
     * signature permission ADD_TRUSTED_DISPLAY. The route that avoids shell privilege entirely
     * is VirtualDeviceManager (a companion-device app-streaming association), which hands out
     * a display built for hosting other apps plus its own virtual touchscreen.
     */
    fun launchApp(intent: Intent): Boolean {
        val display = virtualDisplay ?: return false
        return try {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(display.display.displayId)
            presentation?.hide()
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
            GearslipLog.i("launched ${intent.component ?: intent.`package`} on display ${display.display.displayId}")
            true
        } catch (t: Throwable) {
            GearslipLog.e("launchApp failed", t)
            presentation?.show()
            false
        }
    }

    /**
     * [action] is already an Android MotionEvent action; [points] are in projected coordinates and
     * hold every finger down, with [actionIndex] naming the one the action is about. Two fingers
     * arrive as one gesture, so pinch and two-finger drags work as they do on the phone itself.
     */
    fun dispatchTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        val target = root ?: return
        if (points.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN || downTime == 0L) downTime = now

        val properties = Array(points.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = points[i].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = points[i].x
                y = points[i].y
                pressure = 1f
                size = 1f
            }
        }
        // A second finger going down or up carries which finger it was in the action itself.
        val encoded = when (action) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP ->
                action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else -> action
        }
        val event = MotionEvent.obtain(
            downTime, now, encoded, points.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            target.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) downTime = 0L
    }

    /**
     * A key from the car's D-pad or knob, as a hardware key event on the car display. Compose
     * moves focus between buttons on arrows and Tab, and clicks the focused one on OK/Enter.
     * [keyCode] of [CarKeys.KEYCODE_SHIFT_TAB] is Tab with Shift held.
     */
    fun dispatchKey(keyCode: Int, down: Boolean) {
        val target = root ?: return
        // Keys handed straight to the view skip the step where Android leaves touch mode, and in
        // touch mode Compose's buttons refuse focus, so the knob had nothing to move between.
        if (target.isInTouchMode) {
            target.requestFocusFromTouch()
            GearslipLog.i("keys: left touch mode for the knob/D-pad (now in touch mode: ${target.isInTouchMode})")
        }
        // Nothing on the display takes keys until something in it holds focus.
        if (target.findFocus() == null) target.requestFocus()
        val shift = keyCode == app.seb3thehacker.gearslip.car.CarKeys.KEYCODE_SHIFT_TAB
        val code = if (shift) android.view.KeyEvent.KEYCODE_TAB else keyCode
        val now = SystemClock.uptimeMillis()
        val event = android.view.KeyEvent(
            now, now,
            if (down) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP,
            code, 0,
            if (shift) android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON else 0,
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_DPAD,
        )
        target.dispatchKeyEvent(event)
    }

    /**
     * Points the display at another surface, or none, without restarting what it shows. The
     * preview's SurfaceView comes and goes as the phone turns; the car UI on it carries on.
     */
    /** Draws the screen again as it is, which hands the display's consumer a new frame. */
    fun redraw() {
        root?.let { it.post { it.invalidate() } }
    }

    fun setSurface(surface: Surface?) {
        virtualDisplay?.surface = surface
    }

    fun stop() {
        runCatching { presentation?.dismiss() }
        runCatching { owner?.destroy() }
        runCatching { virtualDisplay?.release() }
        presentation = null
        owner = null
        root = null
        virtualDisplay = null
        downTime = 0L
    }

    private class ViewTreeOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        init {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
