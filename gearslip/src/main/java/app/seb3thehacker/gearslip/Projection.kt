package app.seb3thehacker.gearslip

import android.view.Surface

/**
 * Lets [GearslipRunner] drive on-screen content without owning any Android UI itself.
 *
 * The protocol side decides *when* a surface is needed and *where* touches landed; the
 * activity decides *what* to render and how to inject events, because both need a UI thread
 * and a Context.
 */
interface Projection {

    /** Called once the encoder's input Surface exists and content should start rendering. */
    fun onSurfaceReady(surface: Surface, width: Int, height: Int, densityDpi: Int)

    /**
     * A touch from the head unit, already scaled into projected-video coordinates.
     * [action] is an Android MotionEvent action - Android Auto's PointerAction enum uses
     * identical values (DOWN=0, UP=1, MOVED=2, POINTER_DOWN=5, POINTER_UP=6). [points] is every
     * finger currently down, and [actionIndex] says which of them [action] is about, so a pinch
     * arrives as it would on the phone's own screen.
     */
    fun onTouch(action: Int, actionIndex: Int, points: List<TouchPoint>)

    fun onProjectionStopped()

    /**
     * The screen has been still for a moment and the encoder has sent nothing since. Draw it once
     * more, so the car gets a fresh frame (see [VideoSource]'s onStill).
     */
    fun onStill() {}
}

/** One finger on the car's screen: a stable [id] for as long as it stays down, and where it is. */
class TouchPoint(val id: Int, val x: Float, val y: Float)
