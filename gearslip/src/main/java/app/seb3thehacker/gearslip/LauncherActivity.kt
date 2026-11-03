package app.seb3thehacker.gearslip

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.car.CarEnvironment
import app.seb3thehacker.gearslip.car.CarKeys
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.car.CarUi
import app.seb3thehacker.gearslip.car.LauncherMode
import app.seb3thehacker.gearslip.car.Prefetch
import app.seb3thehacker.gearslip.ui.CarFrame
import app.seb3thehacker.gearslip.ui.GearslipTheme
import app.seb3thehacker.gearslip.ui.rememberCarProjector

/**
 * The home screen in launcher mode (see [LauncherMode]): the car UI, filling this device's own
 * screen. It runs for as long as the activity lives, not only while it's in front, so a map
 * keeps guiding and music keeps playing while another app is open over it.
 */
class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GearslipLog.init(this)
        GearslipLog.installCrashHandler()
        CarSettings.init(this)
        CarKeys.init(this)
        Prefetch.warm(this)
        CarEnvironment.setPhoneTheme(resources.configuration)

        // A dash screen is something to glance at: it stays on, with the system bars out of the way.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        // Back moves back in the car UI. A home screen has nowhere else to go back to.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                CarKeys.onKey(KeyEvent.KEYCODE_BACK, down = true, longPress = false)
            }
        })

        setContent {
            GearslipTheme {
                LauncherScreen(onOpenGearslip = { startActivity(Intent(this, GearslipActivity::class.java)) })
            }
        }
    }

    /** Home pressed while Gearslip is already the home screen: back to the car UI's Home. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        CarKeys.onKey(KeyEvent.KEYCODE_HOME, down = true, longPress = false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        CarEnvironment.setPhoneTheme(newConfig)
    }

    /** A head unit's knob, D-pad and hard keys work the car UI the way a car's do. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK && event.repeatCount == 0 &&
            CarKeys.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.isLongPress)
        ) return true
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun LauncherScreen(onOpenGearslip: () -> Unit) {
    val status by SessionStatus.state.collectAsStateWithLifecycle()
    // Plugged into a car, this device is the phone and the car UI belongs on the car's screen.
    val inCar = status.phase == SessionStatus.Phase.CONNECTING || status.phase == SessionStatus.Phase.PROJECTING
    if (inCar) {
        InCarNotice(onOpenGearslip)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val px = with(LocalDensity.current) { maxWidth.roundToPx() / 2 * 2 to maxHeight.roundToPx() / 2 * 2 }
        val (width, height) = px
        if (width <= 0 || height <= 0) return@BoxWithConstraints
        // Car-like text and buttons: 160dpi for every 480 pixels of height, as the car preview uses.
        val density = (160 * height / 480).coerceIn(120, 640)
        // Before the projector starts, so the car UI's first screen already knows what to hide.
        DisposableEffect(Unit) {
            LauncherMode.setActive(true)
            onDispose { LauncherMode.setActive(false) }
        }
        val projector = rememberCarProjector(width, height, density, "gearslip-launcher") { CarUi() }
        DisposableEffect(projector) {
            CarKeys.focusSink = { code, down -> projector.dispatchKey(code, down) }
            onDispose { CarKeys.focusSink = null }
        }
        CarFrame(projector, width, height, Modifier.fillMaxSize())
    }
}

@Composable
private fun InCarNotice(onOpenGearslip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.launcher_in_car),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenGearslip) { Text(stringResource(R.string.launcher_open_gearslip)) }
    }
}
