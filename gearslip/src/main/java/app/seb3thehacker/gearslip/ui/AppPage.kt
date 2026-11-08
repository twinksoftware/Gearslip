package app.seb3thehacker.gearslip.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import app.seb3thehacker.gearslip.R

/** One app as its page shows it. [url] is where to get it; apps found by their flag have none. */
class PageApp(val name: String, val packageName: String, val url: String?, val about: String?)

/**
 * One app's page, over whatever opened it: its icon, its own sentence, the Works with Gearslip
 * mark when it declares the flag, and one button, to open it or to get it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPage(app: PageApp, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(app.packageName) { runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull() }
    val declares = remember(app.packageName) { WorksWith.declares(context.packageManager, app.packageName) }
    val about = remember(app.packageName) { app.about ?: WorksWith.about(context.packageManager, app.packageName) }
    val installed = icon != null
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null) {
                        Image(icon.toBitmap(192, 192).asImageBitmap(), contentDescription = null, Modifier.fillMaxSize())
                    } else {
                        Text(app.name.take(1), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(app.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                if (declares) {
                    Spacer(Modifier.height(8.dp))
                    WorksWithMark()
                }
                if (about != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(about, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(24.dp))
                if (installed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = INSTALLED_GREEN, modifier = Modifier.size(18.dp))
                        Text(
                            stringResource(R.string.recommended_installed),
                            style = MaterialTheme.typography.labelLarge,
                            color = INSTALLED_GREEN,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.app_page_on_car),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    val launch = remember(app.packageName) { context.packageManager.getLaunchIntentForPackage(app.packageName) }
                    if (launch != null) {
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.app_page_open, app.name))
                        }
                    }
                    if (app.url != null) {
                        TextButton(onClick = { openPage(context, app.url) }) { Text(stringResource(R.string.app_page_github)) }
                    }
                } else if (app.url != null) {
                    Text(
                        stringResource(R.string.app_page_get_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { openPage(context, app.url) }, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.app_page_get, app.name))
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The Works with Gearslip mark: the logo's gear and the words, on a tinted pill. */
@Composable
fun WorksWithMark(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(painterResource(R.drawable.works_with_gear), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.works_with_gearslip), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
