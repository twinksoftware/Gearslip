package app.seb3thehacker.gearslip.car

import androidx.annotation.MainThread
import androidx.car.app.model.Action
import androidx.car.app.model.Alert
import androidx.car.app.model.AlertCallback
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.car.theme.GsColors
import app.seb3thehacker.gearslip.car.theme.GsIconBox
import app.seb3thehacker.gearslip.host.cancel
import app.seb3thehacker.gearslip.host.click
import app.seb3thehacker.gearslip.host.color
import app.seb3thehacker.gearslip.host.dismissed
import app.seb3thehacker.gearslip.host.text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one alert on the car screen, from whichever hosted app sent it: Waze asking whether a
 * hazard is still there, say. As in Android Auto, a newer alert replaces the one showing, and it
 * shows over any screen, since a question about the road matters while music is up too.
 */
@MainThread
internal object CarAlerts {
    class Shown(val owner: Any, val alert: Alert)

    private val current = MutableStateFlow<Shown?>(null)
    val shown: StateFlow<Shown?> = current.asStateFlow()

    fun show(owner: Any, alert: Alert) {
        val old = current.value
        current.value = Shown(owner, alert)
        GearslipLog.i("host: alert ${alert.id} \"${alert.title.text()}\" with ${alert.actions.size} buttons")
        if (old != null && !(old.owner === owner && old.alert.id == alert.id)) old.alert.callbackDelegate.dismissed()
    }

    /** The app took its alert down; a no-op unless that alert is the one showing. */
    fun dismiss(owner: Any, id: Int) {
        val shown = current.value ?: return
        if (shown.owner !== owner || shown.alert.id != id) return
        current.value = null
        shown.alert.callbackDelegate.dismissed()
    }

    /** The driver tapped one of its buttons: the app gets the tap, and the alert goes. */
    fun tapped(shown: Shown, action: Action) {
        action.onClickDelegate.click("alert button")
        if (current.value !== shown) return
        current.value = null
        shown.alert.callbackDelegate.dismissed()
    }

    fun cancelled(shown: Shown, reason: Int) {
        if (current.value !== shown) return
        current.value = null
        shown.alert.callbackDelegate.cancel(reason)
    }

    /** The app disconnected: its alert goes with it, and there is no one left to tell. */
    fun clear(owner: Any) {
        if (current.value?.owner === owner) current.value = null
    }
}

@Composable
internal fun CarAlertOverlay(modifier: Modifier = Modifier) {
    val current by CarAlerts.shown.collectAsState()
    val shown = current ?: return
    val alert = shown.alert
    val timed = alert.durationMillis in 1 until Alert.DURATION_SHOW_INDEFINITELY
    // What's left of the alert's time, drawn as the bar along its foot.
    val left = remember(shown) { Animatable(1f) }
    LaunchedEffect(shown) {
        if (!timed) return@LaunchedEffect
        left.animateTo(0f, tween(alert.durationMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), easing = LinearEasing))
        CarAlerts.cancelled(shown, AlertCallback.REASON_TIMEOUT)
    }
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminanceIsDark()
    Surface(
        modifier
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(24.dp),
        color = scheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Column {
            Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (alert.icon != null) {
                    CarGlyph(alert.icon, Modifier.size(40.dp))
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        alert.title.text(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    alert.subtitle?.text()?.takeIf { it.isNotEmpty() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // With no timer and no buttons, nothing else would ever take it away.
                if (!timed || alert.actions.isEmpty()) {
                    GsIconBox(
                        onClick = { CarAlerts.cancelled(shown, AlertCallback.REASON_USER_ACTION) },
                        modifier = Modifier.size(56.dp),
                        colors = GsColors(Color.Transparent, scheme.onSurface),
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                    }
                }
            }
            if (alert.actions.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    alert.actions.take(2).forEach { action ->
                        val custom = action.backgroundColor?.type?.let { it != androidx.car.app.model.CarColor.TYPE_DEFAULT } == true
                        GsIconBox(
                            onClick = { CarAlerts.tapped(shown, action) },
                            modifier = Modifier.weight(1f).height(64.dp),
                            colors = GsColors(
                                action.backgroundColor.color(dark, scheme.surfaceContainerHighest),
                                if (custom) Color.White else scheme.onSurface,
                            ),
                            enabled = action.isEnabled,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (action.icon != null) {
                                    CarGlyph(action.icon, Modifier.size(28.dp))
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(action.title.text(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(16.dp))
            }
            if (timed) {
                Box(Modifier.fillMaxWidth().height(4.dp).background(scheme.surfaceContainerHighest)) {
                    Box(Modifier.fillMaxWidth(left.value).height(4.dp).clip(RoundedCornerShape(2.dp)).background(scheme.primary))
                }
            }
        }
    }
}
