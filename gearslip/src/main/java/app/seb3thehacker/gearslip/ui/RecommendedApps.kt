package app.seb3thehacker.gearslip.ui

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/** A free app that runs on Gearslip's car screen, and where to get it. */
private class RecommendedApp(val name: String, val packageName: String, val url: String, @param:StringRes val about: Int)

private val recommended = listOf(
    R.string.recommended_maps to listOf(
        RecommendedApp("Organic Maps", "app.organicmaps", "https://github.com/organicmaps/organicmaps", R.string.recommended_about_organic),
        RecommendedApp("Vela Maps", "app.vela", "https://github.com/PimpinPumpkin/Vela", R.string.recommended_about_vela),
    ),
    R.string.recommended_music to listOf(
        RecommendedApp("ViVi Music", "com.vivi.vivimusic", "https://github.com/vivizzz007/vivi-music", R.string.recommended_about_ytm),
        RecommendedApp("Metrolist", "com.metrolist.music", "https://github.com/MetrolistGroup/Metrolist", R.string.recommended_about_ytm),
    ),
    R.string.recommended_podcasts to listOf(
        RecommendedApp("AntennaPod", "de.danoeh.antennapod", "https://github.com/AntennaPod/AntennaPod", R.string.recommended_about_antennapod),
    ),
    R.string.recommended_other to listOf(
        RecommendedApp("RF Sentinel", "com.rfsentinel.app", "https://github.com/CIS-C0/RFSentinel", R.string.recommended_about_rfsentinel),
    ),
)

/** Opened from Settings: the same list the setup guide ends with. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendedAppsScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recommended_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
        ) {
            Text(
                stringResource(R.string.recommended_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            RecommendedAppsList()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The list's entry in Settings, hidden for now. The setup guide always ends on the list. */
internal const val SHOW_RECOMMENDED_APPS = false

/** The same green as the launcher's "works" check. */
internal val INSTALLED_GREEN = Color(0xFF43A047)

/**
 * The apps by section, each with a GitHub button; tapping the rest of a row opens the app's page.
 * Installed ones say so in green. Installed apps that declare the Works with Gearslip flag come
 * first, under their own heading, unless the list already has them.
 */
@Composable
internal fun RecommendedAppsList(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var page by remember { mutableStateOf<PageApp?>(null) }
    val declared = remember {
        val listed = recommended.flatMap { (_, apps) -> apps.map { it.packageName } }.toSet()
        WorksWith.installed(context).filter { it.packageName !in listed }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (declared.isNotEmpty()) {
            Section(stringResource(R.string.works_with_gearslip)) {
                for (app in declared) {
                    AppRow(app.label, app.packageName, app.about, onPage = { page = PageApp(app.label, app.packageName, null, app.about) }, onGitHub = null)
                }
            }
        }
        for ((section, apps) in recommended) {
            Section(stringResource(section)) {
                for (app in apps) {
                    val text = stringResource(app.about)
                    AppRow(app.name, app.packageName, text, onPage = { page = PageApp(app.name, app.packageName, app.url, text) }, onGitHub = { openPage(context, app.url) })
                }
            }
        }
    }
    page?.let { AppPage(it, onDismiss = { page = null }) }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun AppRow(name: String, packageName: String, about: String?, onPage: () -> Unit, onGitHub: (() -> Unit)?) {
    val context = LocalContext.current
    val icon = remember(packageName) { installedIcon(context, packageName) }
    val declares = remember(packageName) { WorksWith.declares(context.packageManager, packageName) }
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onPage), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(icon.toBitmap(96, 96).asImageBitmap(), contentDescription = null, Modifier.fillMaxSize())
            } else {
                Text(name.take(1), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            // Colours set outright: the setup guide draws this on its own background, with no
            // Surface to hand down a content colour, and unset text came out dark on dark.
            Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (about != null) Text(about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (declares) WorksWithMark(Modifier.padding(top = 4.dp))
            if (icon != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = INSTALLED_GREEN, modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.recommended_installed),
                        style = MaterialTheme.typography.labelLarge,
                        color = INSTALLED_GREEN,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        if (onGitHub != null) OutlinedButton(onClick = onGitHub) { Text("GitHub") }
    }
}

private fun installedIcon(context: Context, packageName: String): Drawable? =
    runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()

internal fun openPage(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
