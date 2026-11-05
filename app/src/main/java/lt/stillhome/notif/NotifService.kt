package lt.stillhome.notif

import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import lt.stillhome.App
import lt.stillhome.R
import lt.stillhome.core.Apps
import lt.stillhome.core.Clock
import lt.stillhome.core.Guard
import lt.stillhome.data.ChannelRow
import lt.stillhome.data.DigestRow
import lt.stillhome.data.NotifLogRow
import lt.stillhome.detect.Detectors

/**
 * Section 10. Every notification is Ping / Digest / Drop.
 * Filtered apps' channels are muted in system settings during onboarding (the ping would happen
 * before we could dismiss it), so for those we re-post allowed notifications on our own sounding
 * channel, keeping the original tap intent and inline-reply actions.
 */
class NotifService : NotificationListenerService() {
    private val lastText = HashMap<String, Int>()   // de-duplicate re-posts per original key

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        val pkg = sbn.packageName
        if (pkg == packageName || Guard.paused()) return   // guard off: leave every notification alone
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (Notifs.isSystem(this, pkg)) return

        val r = Ranking(); rankingMap.getRanking(sbn.key, r)
        val channel = r.channel
        val chId = channel?.id ?: n.channelId ?: "default"
        val app = Apps.label(this, pkg)
        val dao = App.db.dao()
        // Auto-discover channels from real notifications (nothing hard-coded).
        if (dao.channel(pkg, chId) == null) dao.upsertChannel(ChannelRow(pkg, chId, channel?.name?.toString() ?: chId, app))

        val (title, text, fromSelf) = content(n.extras)
        val kind = Notifs.kind(pkg, chId, channel?.name?.toString() ?: "", title, text)
        val decision = Notifs.decide(this, pkg, chId, n, title, text, kind)

        // Baseline week: keep Instagram/Snapchat texts on-device to tune the message/snap/reel split.
        val logText = Guard.inBaseline() && pkg in Detectors.withDetector
        dao.insertNotifLog(NotifLogRow(pkg = pkg, channel = chId, title = if (logText) title else "",
            text = if (logText) text else "", time = Clock.now(), kind = kind, decision = decision))

        when (decision) {
            DROP -> cancelNotification(sbn.key)
            DIGEST -> {
                cancelNotification(sbn.key)
                dao.insertDigest(DigestRow(pkg = pkg, app = app, title = title, text = text, time = Clock.now(), kind = kind))
            }
            PING -> {
                // Channel muted by us during onboarding (or by the user): re-post with sound.
                val muted = (channel?.importance ?: NotificationManager.IMPORTANCE_DEFAULT) < NotificationManager.IMPORTANCE_DEFAULT
                if (muted) repost(sbn, app, title, text, silent = fromSelf)
            }
        }
    }

    /** Title, text, and whether the newest message was written by me (my own inline reply). */
    private fun content(e: Bundle): Triple<String, String, Boolean> {
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val msgs = e.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
        if (!msgs.isNullOrEmpty()) {
            val last = msgs.last()
            val sender = last.getCharSequence("sender")?.toString()
            val txt = last.getCharSequence("text")?.toString() ?: ""
            val self = sender == null && last.getParcelable("sender_person", android.app.Person::class.java) == null
            return Triple(title, if (sender != null) "$sender: $txt" else txt, self)
        }
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        return Triple(title, text, false)
    }

    private fun repost(sbn: StatusBarNotification, app: String, title: String, text: String, silent: Boolean) {
        val h = (title + text).hashCode()
        if (lastText[sbn.key] == h) return
        lastText[sbn.key] = h
        val o = sbn.notification
        val b = Notification.Builder(this, App.CH_PING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (title.isNotEmpty()) "$app · $title" else app)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(o.contentIntent)          // tapping still opens the app (and the Gate, if gated)
            .setDeleteIntent(o.deleteIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .setCategory(o.category)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        o.actions?.forEach { b.addAction(it) }          // keeps RemoteInput (inline Reply) working
        getSystemService(NotificationManager::class.java).notify(sbn.key, 7, b.build())
        cancelNotification(sbn.key)                      // the silent original would only duplicate it
    }
}
