package app.seb3thehacker.gearslip.car

import app.seb3thehacker.gearslip.car.theme.*
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import app.seb3thehacker.gearslip.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * What the assistant hears and says, laid over the bottom of whatever screen is up: a dark shade
 * so white words read over a bright map, and a glow along the bottom edge in the phone's own
 * colours that breathes while listening. The shade passes touches through; only the buttons take them.
 */
@Composable
fun AssistantOverlay(modifier: Modifier = Modifier) {
    val state by CarAssistant.state.collectAsState()
    if (state.phase == CarAssistant.Phase.IDLE) return
    val context = LocalContext.current
    val listening = state.phase == CarAssistant.Phase.LISTENING && state.ready
    val glow = MaterialTheme.colorScheme.primary
    val glow2 = MaterialTheme.colorScheme.tertiary
    val breathe by rememberInfiniteTransition(label = "glow").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow",
    )
    val strength = when (state.phase) {
        CarAssistant.Phase.LISTENING -> if (state.ready) breathe else 0.3f
        CarAssistant.Phase.ERROR -> 0.3f
        else -> 0.6f
    }

    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                // Dark enough by the text that nothing on the screen behind reads through it.
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.3f to Color.Black.copy(alpha = 0.8f), 1f to Color.Black.copy(alpha = 0.92f)))
                // A wide, flat oval of light rising from the bottom edge.
                scale(1f, 0.22f, Offset(size.width / 2, size.height)) {
                    drawCircle(
                        Brush.radialGradient(
                            0f to glow.copy(alpha = 0.9f * strength), 0.5f to glow2.copy(alpha = 0.35f * strength), 1f to Color.Transparent,
                            center = Offset(size.width / 2, size.height), radius = size.width * 0.55f,
                        ),
                        radius = size.width * 0.55f, center = Offset(size.width / 2, size.height),
                    )
                }
            }
            .padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PulsingMic(listening)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val listeningText = stringResource(R.string.assist_listening)
                val momentText = stringResource(R.string.assist_one_moment)
                val waiting = state.heard.isBlank() &&
                    (state.phase == CarAssistant.Phase.LISTENING || state.phase == CarAssistant.Phase.THINKING)
                val (small, big) = when (state.phase) {
                    CarAssistant.Phase.LISTENING -> "" to state.heard.ifBlank { if (state.ready) listeningText else momentText }
                    CarAssistant.Phase.THINKING -> "" to state.heard.ifBlank { "…" }
                    else -> state.heard to state.reply
                }
                if (small.isNotBlank()) {
                    Text(small, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    big,
                    color = if (waiting) Color.White.copy(alpha = 0.7f) else Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // What to say, until the driver starts saying it.
                if (state.phase == CarAssistant.Phase.LISTENING && state.ready && state.heard.isBlank()) {
                    Text(
                        stringResource(R.string.assist_examples),
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (state.missingPermission) {
                GsButton(tone = GsTone.Tonal, onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                }) { Text(stringResource(R.string.assist_grant)) }
            }
            GsIconButton(Icons.Filled.Close, stringResource(R.string.msg_dismiss), CarAssistant::cancel)
        }
    }
}

/** A filled circle with a mic glyph, pulsing gently while actually listening. */
@Composable
private fun PulsingMic(listening: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.size(36.dp),
    ) {
        Row(horizontalArrangement = Arrangement.Center) {
            MicGlyph(Modifier.padding(8.dp).size(20.dp))
        }
    }
    // `listening` only changes what's drawn elsewhere in the row (the "Listening…" label);
    // the glyph itself doesn't need to animate for this to read clearly on a glance.
}

/** The assistant's mic, from `R.drawable.mic_24`, tinted with the current content colour. */
@Composable
fun MicGlyph(modifier: Modifier = Modifier) {
    Icon(ImageVector.vectorResource(R.drawable.mic_24), contentDescription = "Assistant", modifier = modifier)
}
