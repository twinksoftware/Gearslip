package app.seb3thehacker.gearslip.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap

/**
 * Feeds the phone's notifications to the car.
 *
 * Reading other apps' notifications is gated by the user granting "Notification access" to
 * Gearslip in the phone's settings - the same on-device consent as any notification mirror,
 * and the only one needed. Until then the car UI says so and offers to open that page.
 */
class GearslipNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        CarNotifications.setListening(true)
        // Seed the history with what is already showing, oldest first so the newest ends up on top.
        runCatching { activeNotifications.orEmpty().sortedBy { it.postTime } }
            .getOrDefault(emptyList())
            .forEach { ingest(it, quiet = true) }
    }

    override fun onListenerDisconnected() = CarNotifications.setListening(false)

    override fun onNotificationPosted(sbn: StatusBarNotification) = ingest(sbn, quiet = false)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        PhoneNavigation.removed(sbn.key)
        CarNotifications.gone(sbn.key)
    }

    private fun ingest(sbn: StatusBarNotification, quiet: Boolean) {
        // Gearslip's own notifications are skipped, except a debug build's test message.
        if (sbn.packageName == packageName &&
            !(app.seb3thehacker.gearslip.BuildConfig.DEBUG && sbn.notification.channelId == TestMessage.CHANNEL)
        ) return
        val n = sbn.notification
        // Google Maps or Waze guiding on the phone: the turn goes to the nav bar and the cluster.
        if (n.category == Notification.CATEGORY_NAVIGATION && sbn.packageName in PhoneNavigation.APPS) {
            PhoneNavigation.posted(this, sbn)
            return
        }
        // Ongoing entries (media, navigation, downloads) and group headers are status, not news.
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category in QUIET_CATEGORIES) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        // The player and the map already show these.
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || n.category == Notification.CATEGORY_NAVIGATION) return

        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        // In MessagingStyle a message with no sender is the phone owner's own.
        val me = style?.user?.name?.toString()
        val lines = style?.messages.orEmpty().takeLast(MAX_LINES).mapNotNull { m ->
            val body = m.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val sender = m.person?.name?.toString()?.takeIf { it.isNotBlank() && it != me }
            ChatLine(sender, body, m.timestamp)
        }

        CarNotifications.post(
            CarNotification(
                key = sbn.key,
                id = CarNotifications.nextId(),
                packageName = sbn.packageName,
                appLabel = appLabel(sbn.packageName),
                title = style?.conversationTitle?.toString()?.takeIf { it.isNotBlank() } ?: title,
                text = lines.lastOrNull()?.text ?: text,
                postedAt = sbn.postTime,
                icon = icon(sbn),
                reply = replyOf(n),
                lines = lines,
                isGroup = style?.isGroupConversation == true,
                markRead = markReadOf(n),
            ),
            quiet || !alerts(sbn, style != null),
        )
    }

    /**
     * Whether a notification is worth a popup in the car: messages, missed calls, alarms and
     * reminders. Everything else still lands in the dashboard's list, just without interrupting.
     * An incoming call is left out too, since the call card already covers it.
     */
    private fun alerts(sbn: StatusBarNotification, conversation: Boolean): Boolean {
        if (sbn.packageName in SYSTEM_PACKAGES) return false
        val ranking = Ranking()
        if (currentRanking?.getRanking(sbn.key, ranking) == true &&
            ranking.importance < android.app.NotificationManager.IMPORTANCE_DEFAULT
        ) return false
        val n = sbn.notification
        return conversation || replyOf(n) != null || n.category in ALERT_CATEGORIES
    }

    private fun markReadOf(n: Notification): Notification.Action? =
        (n.actions.orEmpty().toList() + Notification.WearableExtender(n).actions)
            .firstOrNull { it.actionIntent != null && it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName.substringAfterLast('.'))

    private fun icon(sbn: StatusBarNotification) = runCatching {
        val drawable = sbn.notification.getLargeIcon()?.loadDrawable(this)
            ?: packageManager.getApplicationIcon(sbn.packageName)
        drawable.toBitmap(ICON_PX, ICON_PX)
    }.getOrNull()

    /** Messaging apps often attach the reply only for wearables, so both places are searched. */
    private fun replyOf(n: Notification): ReplyTarget? {
        val actions = n.actions.orEmpty().toList() + Notification.WearableExtender(n).actions
        for (action in actions) {
            if (action.actionIntent == null) continue
            val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: continue
            return ReplyTarget(action, input)
        }
        return null
    }

    private companion object {
        const val ICON_PX = 96
        const val MAX_LINES = 8
        val ALERT_CATEGORIES = setOf(
            Notification.CATEGORY_MESSAGE,
            Notification.CATEGORY_MISSED_CALL,
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_REMINDER,
            Notification.CATEGORY_EVENT,
        )
        val SYSTEM_PACKAGES = setOf("android", "com.android.systemui", "com.android.vending", "com.google.android.gms")
        val QUIET_CATEGORIES = setOf(
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM,
        )
    }
}
