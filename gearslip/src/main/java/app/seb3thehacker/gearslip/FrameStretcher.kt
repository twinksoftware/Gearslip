package app.seb3thehacker.gearslip

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Draws a [width] x [height] picture over the whole of an [outWidth] x [outHeight] encoder frame,
 * only as fast as the car takes frames.
 *
 * Stretching, for a head unit that squashes the frame onto a screen of another shape: Gearslip
 * draws for the screen's real shape into [input], this stretches it the other way, and the car's
 * squash brings it back. It works on the composited picture, so a map app's own surface is
 * stretched with everything else - scaling Gearslip's UI alone would leave the map squashed.
 * When the sizes match, nothing is stretched.
 *
 * Pacing: each picture the display draws is taken at once, so the display never waits, but only
 * the newest goes to the encoder, and only when [hasRoom] says the car will soon take another
 * frame. Fed every picture, the encoder queued the ones the car had no room for: a SOOEMAKA that
 * acked about 25 frames a second showed taps up to a second late.
 */
class FrameStretcher(
    private val output: Surface,
    val width: Int,
    val height: Int,
    private val outWidth: Int,
    private val outHeight: Int,
    /** The most frames a second the encoder is given. */
    frameRate: Int,
    /**
     * Whether another frame may go to the encoder; for a [repeat] of the last one, whether nothing
     * is in the car's way. Asked on this class's own thread.
     */
    private val hasRoom: (repeat: Boolean) -> Boolean,
    /** A frame is going to the encoder. */
    private val onDrawn: () -> Unit,
) {

    private val thread = HandlerThread("gearslip-stretch").apply { start() }
    private val handler = Handler(thread.looper)

    private val frameGapNs = 1_000_000_000L / frameRate
    /** A picture taken from the display that the encoder hasn't had yet. */
    private var fresh = false
    /** The last picture is owed to the encoder again: see [REPEAT_AFTER_MS]. */
    private var repeatOwed = false
    /** Repeats still to come while the screen stays still: see [REPEATS]. */
    private var repeatsLeft = 0
    /** When the display drew the picture taken last. */
    private var takenNs = 0L
    /** The timestamp the encoder got last, and when it got it. */
    private var lastPtsNs = 0L
    private var lastDrawNs = 0L
    /** Since when a picture has waited for the car to have room, or 0. */
    private var heldSinceNs = 0L
    private val heldNs = AtomicLong()

    private val drawWhenDue = Runnable { drawIfRoom() }
    private val repeatLast = Runnable {
        repeatOwed = true
        drawIfRoom()
    }

    /** The car acked a frame, so one that was waiting can go. Safe from any thread. */
    fun wake() {
        handler.post(drawWhenDue)
    }

    /** Sends the last picture again, for a car whose decoder starts over. Safe from any thread. */
    fun redraw() {
        handler.post {
            repeatsLeft = REPEATS
            repeatLast.run()
        }
    }

    /** How long pictures waited for the car to have room since this was last asked. */
    fun takeHeldMs(): Long = heldNs.getAndSet(0) / 1_000_000

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture: SurfaceTexture? = null
    private val transform = FloatArray(16)

    /** Where to draw. Valid once [start] returns true. */
    lateinit var input: Surface
        private set

    /** Sets up on its own thread and waits for it; false if the GPU wouldn't play along. */
    fun start(): Boolean {
        val done = CountDownLatch(1)
        var ok = false
        handler.post {
            ok = runCatching { setUp() }
                .onFailure { GearslipLog.e("stretch: could not set up", it) }
                .isSuccess
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        if (!ok) release()
        return ok
    }

    fun release() {
        handler.post {
            handler.removeCallbacks(drawWhenDue)
            handler.removeCallbacks(repeatLast)
            runCatching { if (::input.isInitialized) input.release() }
            runCatching { texture?.release() }
            texture = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            thread.quitSafely()
        }
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "no EGL config"
        }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }

        program = link(VERTEX, FRAGMENT)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        texture = SurfaceTexture(ids[0]).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ take() }, handler)
        }
        input = Surface(texture)
        GearslipLog.i("stretch: drawing ${width}x$height, stretched to fill ${outWidth}x$outHeight")
    }

    /** The display drew: take its picture now, so it never waits for the encoder. */
    private fun take() {
        val tex = texture ?: return
        runCatching { tex.updateTexImage() }.onFailure {
            GearslipLog.e("stretch: could not take a picture", it)
            return
        }
        takenNs = tex.timestamp
        fresh = true
        drawIfRoom()
    }

    private fun drawIfRoom() {
        if (texture == null || !fresh && !repeatOwed) return
        val now = System.nanoTime()
        // A little early is fine: the display's frames come a frame apart give or take, and
        // holding one that came a hair early until the gap had passed, every time, slipped a
        // little further each frame until whole frames were skipped.
        val due = lastDrawNs + frameGapNs - GAP_SLACK_NS
        if (now < due) {
            handler.removeCallbacks(drawWhenDue)
            handler.postDelayed(drawWhenDue, (due - now) / 1_000_000 + 1)
            return
        }
        val repeat = !fresh
        if (!hasRoom(repeat)) {
            if (heldSinceNs == 0L && !repeat) heldSinceNs = now
            return
        }
        if (heldSinceNs != 0L) {
            heldNs.addAndGet(now - heldSinceNs)
            heldSinceNs = 0L
        }
        fresh = false
        repeatOwed = false
        // Always later than the last: the encoder throws away a frame timed before the one before
        // it. A repeat is timed now, as the picture it repeats is old.
        val pts = maxOf(if (repeat) now else takenNs, lastPtsNs + 1_000)
        // Counted before it's drawn, so the encoder can't hand it on before it's counted.
        onDrawn()
        draw(pts)
        lastPtsNs = pts
        lastDrawNs = now
        handler.removeCallbacks(repeatLast)
        repeatsLeft = if (repeat) repeatsLeft - 1 else REPEATS
        if (repeatsLeft > 0) handler.postDelayed(repeatLast, REPEAT_AFTER_MS)
    }

    private fun draw(ptsNs: Long) {
        val tex = texture ?: return
        runCatching {
            tex.getTransformMatrix(transform)
            GLES20.glViewport(0, 0, outWidth, outHeight)
            GLES20.glUseProgram(program)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val coord = GLES20.glGetAttribLocation(program, "aCoord")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, QUAD)
            GLES20.glEnableVertexAttribArray(coord)
            GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 0, COORDS)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
            EGL14.eglSwapBuffers(display, eglSurface)
        }.onFailure { GearslipLog.e("stretch: draw failed", it) }
    }

    private fun link(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
            val status = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "shader: ${GLES20.glGetShaderInfoLog(it)}" }
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(it)
            val status = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "program: ${GLES20.glGetProgramInfoLog(it)}" }
        }
    }

    private companion object {
        /** EGL_RECORDABLE_ANDROID: the config must be able to feed a video encoder. */
        const val EGL_RECORDABLE_ANDROID = 0x3142

        /**
         * A screen that goes still gets its last picture sent again this often, [REPEATS] times.
         * Many decoders show a frame only once the next one arrives, so the last change before
         * the screen went still (a menu closing) would sit in the car's decoder until something
         * else moved. scrcpy repeats the same way.
         */
        const val REPEAT_AFTER_MS = 100L

        /**
         * As many as Android's own encoder input sends (GraphicBufferSource): its source says one
         * repeat may not clear the encoder's ghosting from a still scene, and each later one
         * sharpens the picture. A car that holds back more than one frame gets them out too. A
         * repeat goes only when the car has nothing else to take in, so a slow car never shows a
         * new frame late for one.
         */
        const val REPEATS = 10

        /** How early a frame may go before the frame rate's gap has passed. */
        const val GAP_SLACK_NS = 4_000_000L


        val QUAD = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val COORDS = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun floats(vararg values: Float) = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }

        const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aCoord;
            uniform mat4 uTransform;
            varying vec2 vCoord;
            void main() {
                gl_Position = aPosition;
                vCoord = (uTransform * aCoord).xy;
            }
        """

        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vCoord);
            }
        """
    }
}
