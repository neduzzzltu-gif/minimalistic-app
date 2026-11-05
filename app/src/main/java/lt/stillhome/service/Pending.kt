package lt.stillhome.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import lt.stillhome.App
import lt.stillhome.R
import lt.stillhome.core.Clock
import lt.stillhome.core.Guard
import lt.stillhome.core.Rules
import lt.stillhome.data.ChangeRow
import lt.stillhome.data.Store
import lt.stillhome.ui.SettingsActivity

/**
 * The "still want it?" reminder for the one pending (loosening) change.
 * Alarm at applyAt -> notification with Apply / Cancel. The minute tick in GuardService is the safety net.
 * Posted state = Store "pendingNotified.<id>", so the tick never posts the same change twice.
 */
object Pending {
    const val NOTIF_ID = 40
    const val DUE = "lt.stillhome.PENDING_DUE"
    const val APPLY = "lt.stillhome.PENDING_APPLY"
    const val CANCEL = "lt.stillhome.PENDING_CANCEL"

    /** For the permissions screen: can we post the reminder at all? (POST_NOTIFICATIONS is a runtime permission.) */
    fun canNotify(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
        ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /** For the permissions screen: exact alarms allowed? (else the reminder may come a few minutes late) */
    fun canExact(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun bootCount(ctx: Context) = android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)

    /** At request time: remember the monotonic deadline + boot, so a wall-clock jump forward can't skip the hour. */
    fun stamp(ctx: Context, id: Long) = Store.setAll(mapOf(
        "pendingEl.$id" to (SystemClock.elapsedRealtime() + Rules.DELAY).toString(), "pendingBoot.$id" to bootCount(ctx).toString()))

    /**
     * Monotonic ms still to wait. Same boot: until the stored deadline; after a reboot: until 1 h of uptime.
     * Old rows and rows whose reminder was already posted (the hour was proven over before): 0.
     */
    fun monoLeft(ctx: Context, id: Long): Long {
        if (Store.get("pendingNotified.$id") != null) return 0
        val el = Store.get("pendingEl.$id")?.toLongOrNull() ?: return 0
        val sameBoot = Store.get("pendingBoot.$id")?.toIntOrNull() == bootCount(ctx)
        return (if (sameBoot) el else Rules.DELAY) - SystemClock.elapsedRealtime()
    }

    /** Startup: the old system allowed several pending changes; keep only the newest. */
    fun migrate() {
        val p = App.db.dao().pending().sortedBy { it.time }
        p.dropLast(1).forEach { App.db.dao().setChangeStatus(it.id, "cancelled") }
    }

    private fun broadcast(ctx: Context, action: String, id: Long, req: Int) = PendingIntent.getBroadcast(ctx, req,
        Intent(ctx, PendingReceiver::class.java).setAction(action).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(ctx: Context, id: Long, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        // Clock.now() may differ from the wall clock (tamper guard); alarms run on the wall clock.
        val wall = System.currentTimeMillis() + (at - Clock.now())
        val pi = broadcast(ctx, DUE, id, 0)
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wall, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wall, pi)
        } catch (e: SecurityException) { Log.w("Pending", "alarm: $e") }   // the minute tick still catches it
    }

    /** Cancel a pending change (status) + its alarm and notification. */
    fun drop(ctx: Context, id: Long) {
        val dao = App.db.dao()
        if (dao.pending().any { it.id == id }) dao.setChangeStatus(id, "cancelled")   // never "cancel" an applied row
        clear(ctx, id)
    }

    /** Alarm + notification + posted flag gone (after apply or cancel). */
    fun clear(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(broadcast(ctx, DUE, id, 0))
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        Store.setAll(mapOf("pendingNotified.$id" to null, "pendingEl.$id" to null, "pendingBoot.$id" to null))
    }

    /** Alarm + minute tick: post the reminder once the hour is over, once per change. */
    fun remindDue(ctx: Context) {
        val now = Clock.now()
        App.db.dao().pending().filter { Rules.isDue(it, now) && Store.get("pendingNotified.${it.id}") == null }.forEach { post(ctx, it) }
    }

    /**
     * After a reboot / app update the alarm is gone: re-arm it, or post at once if the hour is over.
     * This re-posts an unanswered reminder on purpose (a reboot clears the shade; same id, so never two at once).
     */
    fun restore(ctx: Context) {
        val now = Clock.now()
        App.db.dao().pending().forEach {
            if (Rules.isDue(it, now)) post(ctx, it)
            else {
                // Not due = never really posted: no stale flag may stop the tick from posting it later.
                Store.set("pendingNotified.${it.id}", null)
                // After a reboot the monotonic wait can outlast applyAt: arm the alarm for whichever ends last.
                schedule(ctx, it.id, maxOf(it.applyAt, now + monoLeft(ctx, it.id)))
            }
        }
    }

    private fun post(ctx: Context, c: ChangeRow) {
        // Without permission nothing is shown; leave it unmarked so the tick posts it once notifications are allowed.
        if (!canNotify(ctx)) return
        val text = "You asked to change ${c.description} an hour ago. Still want it?"
        val open = PendingIntent.getActivity(ctx, NOTIF_ID, Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, App.CH_PENDING).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Still want this change?").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER).setOnlyAlertOnce(true).setAutoCancel(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Apply", broadcast(ctx, APPLY, c.id, 1)).build())
            .addAction(Notification.Action.Builder(null, "Cancel", broadcast(ctx, CANCEL, c.id, 2)).build())
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        Store.set("pendingNotified.${c.id}", "1")
    }
}

/** Alarm "the hour is over" + the notification's Apply / Cancel buttons. */
class PendingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getLongExtra("id", -1)
        when (i.action) {
            Pending.DUE -> Pending.remindDue(ctx)
            Pending.APPLY -> {
                // goAsync + durable write: the value must be on disk before the row says "applied".
                val done = goAsync()
                Thread {
                    try {
                        val c = App.db.dao().pending().firstOrNull { it.id == id }
                        if (c != null && Rules.apply(id, durable = true)) { Guard.log("rules", "Applied: ${c.description}"); toast(ctx, "Applied: ${c.description}") }
                        else toast(ctx, if (c != null) "Not yet: the hour isn't over" else "Nothing to apply")
                    } finally { done.finish() }
                }.start()
            }
            Pending.CANCEL -> { Rules.cancel(id); toast(ctx, "Change cancelled") }
        }
    }

    private fun toast(ctx: Context, s: String) =
        android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show() }
}
