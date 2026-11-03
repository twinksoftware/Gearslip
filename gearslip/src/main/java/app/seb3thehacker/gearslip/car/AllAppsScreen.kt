package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.mirror.PhoneApp
import app.seb3thehacker.gearslip.mirror.PhoneApps
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Launcher mode's app drawer: every app on the device, A to Z, each opened full screen. Gearslip
 * comes first, for its own settings, which the car UI only partly covers.
 */
@Composable
fun AllAppsScreen() {
    val context = LocalContext.current
    val apps by produceState(initialValue = PhoneApps.cached() ?: emptyList(), context) {
        value = PhoneApps.cached() ?: withContext(Dispatchers.IO) { PhoneApps.installed(context).also { PhoneApps.warm(context) } }
    }
    val gearslip = remember(context) {
        val label = context.packageManager.getApplicationLabel(context.applicationInfo).toString()
        val icon = runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }
        PhoneApp(context.packageName, label, icon.getOrNull())
    }

    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
        ScreenHeader(stringResource(R.string.app_all_apps))
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PhoneAppTile(gearslip) { LauncherMode.launch(context, context.packageName) }
            }
            items(apps.sortedBy { it.label.lowercase() }) { app ->
                PhoneAppTile(app) { LauncherMode.launch(context, app.packageName) }
            }
        }
    }
}

private const val ICON_PX = 128
