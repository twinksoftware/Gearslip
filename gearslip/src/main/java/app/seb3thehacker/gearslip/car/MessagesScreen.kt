package app.seb3thehacker.gearslip.car

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.car.theme.*
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.notify.CarNotification
import app.seb3thehacker.gearslip.notify.CarNotifications
import app.seb3thehacker.gearslip.notify.ChatLine
import java.text.DateFormat
import java.util.Date

/** Lines a collapsed conversation shows; tapping it shows the rest the app sent. */
private const val COLLAPSED_LINES = 2

/**
 * A messaging app's recent conversations, the way Android Auto shows them: the app has no car
 * screen of its own, so each thread comes from the notification it has on the phone, newest
 * first, with reply and mark-as-read when the app offers them.
 */
@Composable
fun MessagesScreen(packageName: String) {
    val history by CarNotifications.history.collectAsState()
    val listening by CarNotifications.listening.collectAsState()
    val navigator = LocalCarNavigator.current
    val context = LocalContext.current.applicationContext
    val entry = remember(packageName) { findEntry(MESSAGING_PREFIX + packageName) }
    val label = entry?.label ?: history.firstOrNull { it.packageName == packageName }?.appLabel ?: stringResource(R.string.msg_messages)
    val threads = history.filter { it.packageName == packageName }

    LaunchedEffect(history) { CarNotifications.dismissPopupFrom(packageName) }

    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (navigator.backLeadsSomewhere) GsIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), navigator::back)
            entry?.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))) }
            Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        if (!listening) {
            AccessBanner {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        if (threads.isEmpty()) {
            Text(
                stringResource(R.string.msg_none_recent, label),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(threads, key = { it.key }) { n ->
                    ConversationCard(
                        n,
                        onRead = { VoiceReply.read(n) },
                        onReply = { VoiceReply.reply(n) },
                        onMarkRead = { CarNotifications.markThreadRead(context, n) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConversationCard(n: CarNotification, onRead: () -> Unit, onReply: () -> Unit, onMarkRead: () -> Unit) {
    var expanded by rememberSaveable(n.key) { mutableStateOf(false) }
    // An app that posts plain notifications instead of a conversation still gets one line.
    val lines = n.lines.ifEmpty { listOf(ChatLine(n.title.ifBlank { n.appLabel }, n.text.ifBlank { n.title }, n.postedAt)) }
    val shown = if (expanded) lines else lines.takeLast(COLLAPSED_LINES)
    val dim = n.read || n.replied
    var quick by rememberSaveable(n.key) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(enabled = lines.size > COLLAPSED_LINES) { expanded = !expanded },
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NotificationIcon(n, size = 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        n.title.ifBlank { n.appLabel },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = if (dim) FontWeight.Medium else FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        "  ·  ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(n.postedAt))}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    if (n.replied) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.notif_replied),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp).size(20.dp),
                        )
                    }
                }
                if (!expanded && lines.size > COLLAPSED_LINES) {
                    Text(
                        pluralStringResource(R.plurals.msg_earlier, lines.size - COLLAPSED_LINES, lines.size - COLLAPSED_LINES),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                shown.forEach { MessageLine(it, showSender = n.isGroup) }
                // Below the messages and side by side, so the text keeps the card's whole width.
                FlowRow(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GsButton(onClick = onRead, tone = GsTone.Neutral) {
                        ReadGlyph(Modifier.size(20.dp))
                        Text(stringResource(R.string.msg_read), Modifier.padding(start = 8.dp))
                    }
                    if (n.reply != null) {
                        GsButton(onClick = onReply, tone = GsTone.Tonal) {
                            ReplyGlyph(Modifier.size(20.dp))
                            Text(stringResource(R.string.crumb_reply), Modifier.padding(start = 8.dp))
                        }
                        GsButton(onClick = { quick = !quick }, tone = GsTone.Neutral) {
                            Text(stringResource(if (quick) R.string.msg_hide_quick else R.string.msg_quick))
                        }
                    }
                    if (n.markRead != null) {
                        GsButton(onClick = onMarkRead, tone = GsTone.Neutral) { Text(stringResource(R.string.msg_mark_read)) }
                    }
                }
                if (quick) QuickReplies(n, Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun MessageLine(line: ChatLine, showSender: Boolean) {
    val who = when {
        line.sender == null -> stringResource(R.string.msg_you)
        showSender -> stringResource(R.string.msg_sender, line.sender)
        else -> ""
    }
    Text(
        who + line.text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (line.sender == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}
