package app.seb3thehacker.gearslip.car.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * An on/off switch in the current theme's finish: a flat track that fills with the primary colour
 * while on and a flat knob that slides along it. The whole switch is one tap target, as big as a
 * nav bar button.
 */
@Composable
fun GsSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val knob = 26.dp
    val travel = 64.dp - knob - 8.dp
    val x by animateDpAsState(if (checked) travel else 0.dp, tween(140), label = "switch")
    GsIconBox(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier.size(width = 64.dp, height = 36.dp),
        colors = if (checked) GsColors(scheme.primary, scheme.onPrimary) else GsColors(scheme.surfaceContainerHighest, scheme.onSurfaceVariant),
        shape = CircleShape,
        latched = checked,
    ) {
        Box(Modifier.matchParentSize().padding(4.dp), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .offset(x = x)
                    .size(knob)
                    .gsSurface(if (checked) scheme.onPrimary else scheme.outline, CircleShape),
            )
        }
    }
}
