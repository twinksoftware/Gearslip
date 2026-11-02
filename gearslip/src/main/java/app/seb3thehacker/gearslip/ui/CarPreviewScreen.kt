package app.seb3thehacker.gearslip.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.ScreenProjector
import app.seb3thehacker.gearslip.TouchPoint
import app.seb3thehacker.gearslip.car.CarEnvironment
import app.seb3thehacker.gearslip.car.CarUi

/**
 * Runs the real car UI on the bench and shows it on the phone.
 *
 * This is the same pipeline the car gets - a VirtualDisplay hosting a Presentation, driven by
 * the same [ScreenProjector] and fed the same touches - with a SurfaceView standing in for the
 * H.264 encoder. That matters for anything composited rather than drawn by Compose, such as the
 * SurfaceView a templated app draws its map into: if it appears here, it will reach the car.
 *
 * Debug affordance only. Nothing in the car session path goes through this screen.
 */
@Composable
fun CarPreviewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val frame by remember { CarEnvironment.frame.also { CarEnvironment.applyDensity() } }.collectAsStateWithLifecycle()
    var viewSize by remember { mutableStateOf(0 to 0) }
    // Tied to how the phone itself is held, not a button: turn it sideways and the preview goes
    // fullscreen, turn it back and the phone's own chrome (the Back/title above the frame) returns.
    val fullscreen = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val previewSize by app.seb3thehacker.gearslip.car.CarSettings.previewSize.collectAsStateWithLifecycle()
    val custom by app.seb3thehacker.gearslip.car.CarSettings.previewCustom.collectAsStateWithLifecycle()
    val (width, height, density) = previewFrame(context, frame, previewSize, custom)

    val projector = remember { ScreenProjector(context) }

    // The preview is something to watch, like a map on a dash mount, so the phone stays awake on
    // it. Only here: the flag goes with this screen, and every other screen sleeps as usual.
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(width, height, density) {
        CarEnvironment.start(context)
        // No surface yet: the SurfaceView below hands its own over once it exists, and the
        // display draws straight into it. Copying each frame into a Bitmap instead cost two
        // full-size allocations per frame on the main thread, and taps lagged with the size.
        projector.start(null, width, height, density, name = "gearslip-preview") {
            androidx.compose.runtime.CompositionLocalProvider(app.seb3thehacker.gearslip.car.LocalCarPreview provides true) { CarUi() }
        }
        onDispose { projector.stop() }
    }

    // The frame is drawn the same way in both layouts; only what surrounds it differs.
    val frameContent: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier
                .aspectRatio(width.toFloat() / height)
                .background(Color.Black)
                .onSizeChanged { viewSize = it.width to it.height }
                .pointerInteropFilter { event ->
                    val (viewWidth, viewHeight) = viewSize
                    if (viewWidth > 0 && viewHeight > 0) {
                        projector.dispatchTouch(
                            event.actionMasked.toCarAction(),
                            event.actionIndex,
                            List(event.pointerCount) { i ->
                                TouchPoint(
                                    event.getPointerId(i),
                                    event.getX(i) * width / viewWidth,
                                    event.getY(i) * height / viewHeight,
                                )
                            },
                        )
                    }
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.runtime.key(width, height) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { viewContext ->
                        android.view.SurfaceView(viewContext).apply {
                            // The car's own size; the compositor scales it to the view.
                            holder.setFixedSize(width, height)
                            holder.addCallback(object : android.view.SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: android.view.SurfaceHolder) =
                                    projector.setSurface(holder.surface)

                                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) =
                                    projector.setSurface(holder.surface)

                                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) =
                                    projector.setSurface(null)
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (fullscreen) {
        Fullscreen(width.toFloat() / height, onBack) {
            // No fillMax here: left to the bounds alone, aspectRatio takes the whole width or the
            // whole height, whichever the car's shape allows, and the rest stays black.
            frameContent(Modifier)
        }
        return
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(
                "Car preview",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Text(
                "${width}x$height @ ${density}dpi - touches go to the car UI",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            frameContent(Modifier.padding(vertical = 12.dp).fillMaxWidth())

            Row {
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
    }
}

/**
 * Fills the phone screen with [content], centred and letterboxed.
 *
 * A car screen is wider than it is tall, so a portrait phone would waste most of its height on
 * one. This only shows once the phone is already turned sideways (the caller drives it off the
 * phone's own orientation), so there is no rotation left to force here - just the system bars to
 * hide while it's up and restore on the way out.
 */
@Composable
private fun Fullscreen(
    aspect: Float,
    onBack: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val activity = LocalContext.current.activity()

    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose { }

        val insets = WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose { insets.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Turning the phone back upright is what normally leaves fullscreen; back still leaves the
    // preview screen entirely rather than doing nothing, in case the phone's own rotation lock
    // is on and turning it sideways/back isn't an option.
    BackHandler(onBack = onBack)

    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        content()

        // The frame swallows every touch inside it, so leaving needs either the back gesture or
        // a button of its own. The button goes in the black beside the frame, never on it: over
        // the frame it covered the car's own buttons. A frame the screen's own shape leaves no
        // room, and then the back gesture is the way out.
        val frameWidth = minOf(maxWidth, maxHeight * aspect)
        val frameHeight = minOf(maxHeight, maxWidth / aspect)
        val room = 56.dp
        val corner = when {
            maxWidth - frameWidth >= room * 2 -> Alignment.BottomStart
            maxHeight - frameHeight >= room * 2 -> Alignment.TopStart
            else -> null
        }
        if (corner != null) {
            FilledTonalIconButton(
                onClick = onBack,
                modifier = Modifier.align(corner).padding(8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Leave the car preview")
            }
        }
    }
}

/** Compose hands out a themed wrapper, so the activity is a few unwraps down. */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** The actions [ScreenProjector.dispatchTouch] understands reach the car UI, second fingers included. */
private fun Int.toCarAction(): Int = when (this) {
    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP,
    -> this
    else -> MotionEvent.ACTION_CANCEL
}

/**
 * Copies one frame out of the reader. The reader's rows are padded to a hardware-friendly
 * stride, so the copy is made at the padded width and then cropped back.
 */
/**
 * The size the preview draws at. The last car's frame by default; this device's whole screen,
 * for a head unit running Gearslip on its own (issue #12); or a size typed into Settings. The
 * two new ones get a car-like density: 160dpi for every 480 pixels of height.
 */
private fun previewFrame(
    context: android.content.Context,
    lastCar: CarEnvironment.Frame,
    size: app.seb3thehacker.gearslip.car.PreviewSize,
    custom: app.seb3thehacker.gearslip.car.ScreenSize?,
): Triple<Int, Int, Int> {
    fun carLike(w: Int, h: Int) = Triple(w, h, app.seb3thehacker.gearslip.car.CarSettings.effectiveDensity((160 * h / 480).coerceIn(120, 640)))
    return when (size) {
        app.seb3thehacker.gearslip.car.PreviewSize.LAST_CAR -> Triple(lastCar.width, lastCar.height, lastCar.densityDpi)
        app.seb3thehacker.gearslip.car.PreviewSize.CUSTOM ->
            custom?.let { carLike(it.width, it.height) } ?: Triple(lastCar.width, lastCar.height, lastCar.densityDpi)
        app.seb3thehacker.gearslip.car.PreviewSize.FILL -> {
            val bounds = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
            // Landscape, whichever way the device is held right now.
            carLike(maxOf(bounds.width(), bounds.height()) / 2 * 2, minOf(bounds.width(), bounds.height()) / 2 * 2)
        }
    }
}
