package app.seb3thehacker.gearslip.car

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.car.theme.GsButton
import app.seb3thehacker.gearslip.host.display
import app.seb3thehacker.gearslip.mirror.PhoneMirror
import app.seb3thehacker.gearslip.notify.PhoneNavigation

/**
 * Google Maps, the way a car can actually have it: the phone runs the app and Gearslip mirrors it
 * to the head unit. Its turn notifications keep feeding the nav bar, the cluster and the
 * screensaver while it guides, so leaving the full map only costs the picture, not the route.
 */
@Composable
fun GoogleMapsScreen() {
    val context = LocalContext.current
    val turn by PhoneNavigation.turn.collectAsState()
    if (!GoogleMaps.installed(context)) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenHeader(stringResource(R.string.app_google_maps))
            Text(stringResource(R.string.gmaps_missing), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(stringResource(R.string.app_google_maps))
        Text(
            stringResource(R.string.gmaps_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 620.dp),
        )
        turn?.let { live ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        live.distance?.display() ?: live.distanceText,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        live.road,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        GsButton(onClick = { PhoneMirror.open(context, GoogleMaps.PACKAGE) }) {
            Text(stringResource(R.string.gmaps_open), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.gmaps_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 620.dp),
        )
    }
}

/** Whether the phone has Google Maps at all; the mirror cannot start what is not installed. */
internal object GoogleMaps {
    const val PACKAGE = "com.google.android.apps.maps"

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getApplicationInfo(PACKAGE, 0) }.isSuccess
}
