package app.seb3thehacker.gearslip.ui

import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext

/** Where Gearslip lives outside the app: support, the chat room, and the code. */
internal object GearslipLinks {
    const val BUY_ME_A_COFFEE = "https://buymeacoffee.com/seb3thehacker"
    const val LIBERAPAY = "https://liberapay.com/Seb3thehacker"
    /** A card payment page for giving once, any amount. Its row stays hidden while this is empty. */
    const val PAYMENT_LINK = ""
    /** Gearslip's Monero address. Its row stays hidden while this is empty. */
    const val MONERO = "44gTVxQJY3cirBJ41J5i1bPUVnSkTMhn9esSp1kg9MHwJ3TUz8tkkigUTKwiok5zA5RZvNxyM4pqkAfPs79pPLfaNG21pCn"
    const val MATRIX = "https://matrix.to/#/!mf0_mgBd5KqKpc9FfX_wHp1jFCcwAtPrndIn_oGIyMk?via=matrix.org"
    const val SOURCE = "https://github.com/twinksoftware/gearslip"
    const val CARS = "$SOURCE/blob/main/CARS.md"
    const val PRIVACY = "$SOURCE/blob/main/PRIVACY.md"
    const val TRANSLATE = "https://hosted.weblate.org/engage/gearslip/"

    /** This version's release notes on GitHub; every release is tagged v plus its version name. */
    val RELEASE_NOTES = "$SOURCE/releases/tag/v${app.seb3thehacker.gearslip.BuildConfig.VERSION_NAME}"

    fun open(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Opens a Monero wallet app on the address; says so when none is installed. */
    fun openMoneroWallet(context: Context) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("monero:$MONERO")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(context, context.getString(R.string.about_no_wallet), Toast.LENGTH_LONG).show()
        }
    }

    fun copyMonero(context: Context) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Monero address", MONERO))
        Toast.makeText(context, context.getString(R.string.about_monero_copied), Toast.LENGTH_SHORT).show()
    }
}

/** The ways to support Gearslip: Liberapay first, Monero last, card once it's set up. */
@Composable
internal fun DonateRows() {
    val context = LocalContext.current
    val arrow = @Composable {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SettingsRow(
        "Liberapay",
        Modifier.clickable { GearslipLinks.open(context, GearslipLinks.LIBERAPAY) },
        subtitle = stringResource(R.string.about_give_regularly),
        icon = Icons.Filled.Favorite,
        trailing = arrow,
    )
    SettingsDivider()
    SettingsRow(
        "Buy Me a Coffee",
        Modifier.clickable { GearslipLinks.open(context, GearslipLinks.BUY_ME_A_COFFEE) },
        subtitle = stringResource(R.string.about_bmac_sub),
        icon = Icons.Filled.Favorite,
        trailing = arrow,
    )
    if (GearslipLinks.PAYMENT_LINK.isNotEmpty()) {
        SettingsDivider()
        SettingsRow(
            stringResource(R.string.about_give_once),
            Modifier.clickable { GearslipLinks.open(context, GearslipLinks.PAYMENT_LINK) },
            subtitle = stringResource(R.string.about_any_amount),
            icon = Icons.Filled.Favorite,
            trailing = arrow,
        )
    }
    if (GearslipLinks.MONERO.isNotEmpty()) {
        var moneroOpen by remember { mutableStateOf(false) }
        SettingsDivider()
        SettingsRow(
            "Monero",
            Modifier.clickable { moneroOpen = true },
            subtitle = stringResource(R.string.about_monero_sub),
            icon = Icons.Filled.Favorite,
            trailing = arrow,
        )
        if (moneroOpen) MoneroDialog { moneroOpen = false }
    }
}

/** The Monero address in full, with ways to pay it from this phone or another device. */
@Composable
internal fun MoneroDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
        title = { Text(stringResource(R.string.about_monero_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.about_monero_body))
                SelectionContainer {
                    Text(
                        GearslipLinks.MONERO,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { GearslipLinks.openMoneroWallet(context) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.about_open_wallet))
                }
                OutlinedButton(onClick = { GearslipLinks.copyMonero(context) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.about_copy_address))
                }
                TextButton(onClick = onDismiss, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.close))
                }
            }
        },
    )
}

/** The About section's links: the chat room, translations, the source code, and credits. */
@Composable
internal fun AboutLinkRows() {
    val context = LocalContext.current
    val arrow = @Composable {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SettingsRow(
        stringResource(R.string.about_matrix),
        Modifier.clickable { GearslipLinks.open(context, GearslipLinks.MATRIX) },
        subtitle = stringResource(R.string.about_matrix_sub),
        icon = Icons.AutoMirrored.Filled.Send,
        trailing = arrow,
    )
    SettingsDivider()
    SettingsRow(
        stringResource(R.string.about_translate),
        Modifier.clickable { GearslipLinks.open(context, GearslipLinks.TRANSLATE) },
        subtitle = stringResource(R.string.about_translate_sub),
        icon = Icons.Filled.Edit,
        trailing = arrow,
    )
    SettingsDivider()
    SettingsRow(
        stringResource(R.string.about_source),
        Modifier.clickable { GearslipLinks.open(context, GearslipLinks.SOURCE) },
        subtitle = stringResource(R.string.about_source_sub),
        icon = Icons.Filled.Build,
        trailing = arrow,
    )
    SettingsDivider()
    SettingsRow(
        stringResource(R.string.privacy_policy),
        Modifier.clickable { GearslipLinks.open(context, GearslipLinks.PRIVACY) },
        subtitle = stringResource(R.string.privacy_policy_sub),
        icon = Icons.Filled.Lock,
        trailing = arrow,
    )
    SettingsDivider()
    SettingsRow(
        stringResource(R.string.about_voice_credits),
        subtitle = stringResource(R.string.about_voice_credits_sub),
        icon = Icons.Filled.Favorite,
    )
}
