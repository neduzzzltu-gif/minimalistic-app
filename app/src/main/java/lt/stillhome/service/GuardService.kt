package lt.stillhome.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import lt.stillhome.App
import lt.stillhome.R
import lt.stillhome.cards.Geo
import lt.stillhome.core.Apps
import lt.stillhome.core.Cats
import lt.stillhome.core.Clock
import lt.stillhome.core.Guard
import lt.stillhome.core.Limits
import lt.stillhome.core.Rules
import lt.stillhome.core.fmtDur
import lt.stillhome.core.fmtHm
import lt.stillhome.data.ChangeRow
import lt.stillhome.data.Store
import lt.stillhome.detect.Kind
import lt.stillhome.ui.BarcodeActivity
import lt.stillhome.ui.DigestActivity
import lt.stillhome.ui.GateActivity
import lt.stillhome.ui.LabelActivity
import lt.stillhome.ui.MainActivity
import lt.stillhome.ui.ReviewActivity
import java.time.DayOfWeek

/**
 * The always-on part: its foreground notification IS the lock-screen status line.
 * A 1-second ticker (no exact alarms) drives session expiry, warnings, metering, screen time,
 * digest times, the Sunday review, baseline end, weekly auto-tighten and the 24 h queue.
 * HyperOS may still kill it; BOOT_COMPLETED, the accessibility service and the launcher all restart it.
 */
class GuardService : Service() {
    companion object {
        const val NOTIF_ID = 1
        const val WARN_ID = 2
        const val DIGEST_ID = 3
        @Volatile var running = false

        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, GuardService::class.java)) }
            catch (e: Exception) { Log.w("GuardService", "start refused: $e") }   // background-start limits
        }

        /** Ask the running service to rebuild the lock-screen notification now. */
        fun refresh(ctx: Context) = start(ctx)
    }

    private val main = Handler(Looper.getMainLooper())
    private var screenOn = true
    private var tickCount = 0L
    private val meterBuf = HashMap<String, Long>()
    private var screenBuf = 0L
    private val warned = HashSet<Long>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; flush() }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> screenOn = true
            }
        }
    }

    /** PACKAGE_ADDED is not delivered to manifest receivers any more, so listen here. */
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Apps.invalidate()
            val pkg = i.data?.schemeSpecificPart ?: return
            if (i.action == Intent.ACTION_PACKAGE_ADDED && !i.getBooleanExtra(Intent.EXTRA_REPLACING, false) &&
                Cats.needsLabel(c, pkg)) {
                // Ask for a label right away; until then the app stays gated.
                try { startActivity(Intent(c, LabelActivity::class.java).putExtra("pkg", pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) {}
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        })
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addDataScheme("package")
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, buildStatus(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) { Log.w("GuardService", "startForeground failed: $e"); return START_STICKY }
        if (!running) { running = true; main.post(ticker); Geo.refresh(this) }
        else notifyStatus()
        return START_STICKY   // restart after HyperOS kills us
    }

    override fun onDestroy() {
        running = false; main.removeCallbacks(ticker); flush()
        try { unregisterReceiver(screenReceiver); unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private val ticker = object : Runnable {
        override fun run() {
            try { tick() } catch (e: Exception) { Log.e("GuardService", "tick", e) }
            main.postDelayed(this, 1000)
        }
    }

    private fun tick() {
        tickCount++
        val now = Clock.now()

        // 1. sessions that just ran out -> Home + "Time's up" (enforced here, not by the launcher UI)
        for (s in Guard.expireDue(now)) {
            Store.setAll(mapOf("intentCheck" to s.id.toString(), "intentCheckAt" to now.toString()))
            val a11y = GuardA11yService.instance
            if (a11y != null && GuardA11yService.fg?.target == s.target) a11y.timesUp(s.target, s.pkg, s.id)
            else a11y?.let { a -> main.post { a.checkPip() } }   // e.g. YouTube still playing in picture-in-picture
            alert("Time's up", "${label(s.target)} session ended. Did you do what you wrote?",
                Intent(this, GateActivity::class.java).putExtra("mode", "timesup").putExtra("target", s.target)
                    .putExtra("pkg", s.pkg).putExtra("session", s.id))
            notifyStatus()
        }

        // 2. warning before the end: 1 min (Games: 5 min)
        for (s in Guard.openSessions()) {
            val warnMs = if (s.category == Cats.GAMES) 5 * 60_000L else 60_000L
            if (s.plannedEnd - now in 1..warnMs && warned.add(s.id))
                alert("${(warnMs / 60_000)} min left", "${label(s.target)} session ends at ${fmtHm(Clock.minuteOfDay(s.plannedEnd))}", null)
        }

        // 3. metering (metered/unknown screen in front, screen on) + own screen-on time
        if (screenOn) {
            screenBuf++
            GuardA11yService.fg?.let { f ->
                if (f.screen.kind == Kind.METERED || f.screen.kind == Kind.UNKNOWN) {
                    val c = Cats.budgetCat(f.cat).ifEmpty { Cats.SOCIAL }
                    meterBuf[c] = (meterBuf[c] ?: 0) + 1
                }
            }
        }
        if (tickCount % 30 == 0L) flush()

        // 4. once a minute: status notification + slow jobs
        if (tickCount % 60 == 1L) minute(now)
    }

    private fun minute(now: Long) {
        Rules.applyDue()
        notifyStatus()
        digestTimes(now)
        weeklyReview(now)
        baselineEnd(now)
        autoTighten(now)
        passExpiry(now)
        if (tickCount % 3600 == 1L) { App.db.dao().pruneDigest(now - 14 * 86400_000L); App.db.dao().pruneNotifLog(now - 60 * 86400_000L) }
    }

    private fun flush() {
        val dao = App.db.dao(); val day = Clock.day()
        if (screenBuf > 0) { dao.ensureScreen(day); dao.addScreen(day, screenBuf); screenBuf = 0 }
        meterBuf.forEach { (c, s) -> dao.ensureMeter(day, c); dao.addMeter(day, c, s) }
        meterBuf.clear()
    }

    // ---------- scheduled things ----------
    private fun digestTimes(now: Long) {
        val m = Clock.minuteOfDay(now); val day = Clock.day(now)
        for (t in Store.list("digestTimes").ifEmpty { listOf("1200") }.mapNotNull { it.toIntOrNull() }) {
            val key = "$day@$t"
            if (m >= t && m < t + 30 && Store.get("digest.last.$t") != key) {
                Store.set("digest.last.$t", key)
                val n = App.db.dao().unseenDigest()
                if (n > 0) post(DIGEST_ID, App.CH_DIGEST, "Digest · $n new", "Tap to read everything that was held back",
                    Intent(this, DigestActivity::class.java))
            }
        }
    }

    private fun weeklyReview(now: Long) {
        if (Clock.dayOfWeek(now) != DayOfWeek.SUNDAY || Clock.minuteOfDay(now) < 20 * 60) return
        if (Guard.phase() == "setup") return
        val week = Clock.day(now)
        if (Store.get("review.shown") == week) return
        Store.set("review.shown", week); Store.set("review.pending", week)
        openActivity(Intent(this, ReviewActivity::class.java))
    }

    private fun baselineEnd(now: Long) {
        if (!Guard.inBaseline()) return
        val start = Store.long("baselineStart", now)
        if (now < start + 7 * 86400_000L) return
        // Budgets = 70% of each category's baseline daily average (section 8).
        val dao = App.db.dao()
        val fromDay = Clock.day(start)
        val meters = dao.metersSince(fromDay)
        val days = 7.0
        for (cat in Cats.budgeted) {
            val avg = meters.filter { it.category == cat }.sumOf { it.seconds } / 60.0 / days
            val b = Limits.fromBaseline(cat, avg)
            Store.set("budget.$cat", b.toString())
            dao.insertChange(ChangeRow(time = now, description = "Baseline: $cat budget = $b min (avg ${avg.toInt()} min/day)",
                loosening = false, status = "applied", key = "budget.$cat", value = b.toString()))
        }
        Store.setAll(mapOf("phase" to "active", "lockdownAt" to now.toString(), "baselineSummary" to "1",
            "baselineAvgScreen" to (dao.screenSince(fromDay).sumOf { it.seconds } / 60 / 7).toString()))
        Guard.log("phase", "Baseline finished; rules active; lockdown armed")
        openActivity(Intent(this, ReviewActivity::class.java).putExtra("baseline", true))
    }

    private fun autoTighten(now: Long) {
        if (!Guard.rulesActive()) return
        val last = Store.long("tighten.last", 0)
        if (last == 0L) { Store.set("tighten.last", now.toString()); return }
        if (now - last < 7 * 86400_000L) return
        Store.set("tighten.last", now.toString())
        val step = Store.int("autoTighten", 5); val floor = Store.int("tightenFloor", 20)
        val cur = Limits.budget(Cats.SOCIAL)
        val avg7 = App.db.dao().screenSince(Clock.daysAgo(7)).sumOf { it.seconds } / 60 / 7
        if (step <= 0 || cur <= floor || avg7 < Store.int("targetMin", 120)) return   // stop conditions
        val nb = maxOf(floor, cur - step)
        Rules.change("budget.${Cats.SOCIAL}", nb.toString(), "Weekly auto-tighten: Social $cur → $nb min")
    }

    private fun passExpiry(now: Long) {
        for (k in Store.keys("pass.")) {
            val until = Store.long(k, 0); if (until == 0L || until > now) continue
            Store.set(k, null)
            val pkg = k.removePrefix("pass.")
            if (GuardA11yService.fg?.pkg == pkg || GuardA11yService.instance?.rootInActiveWindow?.packageName == pkg) {
                GuardA11yService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            }
            Guard.log("pass", "Checkout pass ended for $pkg")
        }
    }

    // ---------- notifications ----------
    private fun label(target: String) =
        if (target.startsWith("chrome:")) target.removePrefix("chrome:") else Apps.label(this, target)

    fun notifyStatus() = getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildStatus())

    private fun buildStatus(): Notification {
        val now = Clock.now()
        val b = Notification.Builder(this, App.CH_GUARD)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))

        val session = Guard.openSessions().filter { now < it.plannedEnd }.minByOrNull { it.plannedEnd }
        if (session != null) {
            // "[App] · mm:ss left" — the chronometer counts down by itself between our minute updates.
            b.setContentTitle("${label(session.target)} · time left")
                .setUsesChronometer(true).setChronometerCountDown(true).setWhen(session.plannedEnd).setShowWhen(true)
        } else {
            val today = App.db.dao().screenSince(Clock.day()).sumOf { it.seconds } * 1000 + screenBuf * 1000
            val target = Store.int("targetMin", 120)
            var line = "${fmtDur(today)} today · target ${fmtDur(target * 60_000L)}"
            if (Guard.focusActive(now)) line = "Focus until ${fmtHm(Clock.minuteOfDay(Guard.focusUntil()))} · $line"
            b.setContentTitle(line)
        }

        // Up to 3 quick-app buttons; in a store with a card, the first becomes the card.
        val actions = ArrayList<Notification.Action>()
        Store.get("inStoreCard")?.toLongOrNull()?.let { id ->
            App.db.dao().card(id)?.let { card ->
                val pi = PendingIntent.getActivity(this, 100, Intent(this, BarcodeActivity::class.java).putExtra("card", id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                actions.add(Notification.Action.Builder(null, "${card.name} card", pi).build())
            }
        }
        for ((i, pkg) in Store.list("quickApps").filter { !Cats.isGated(this, it) }.withIndex()) {
            if (actions.size >= 3) break
            val li = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            val pi = PendingIntent.getActivity(this, 200 + i, li, PendingIntent.FLAG_IMMUTABLE)
            actions.add(Notification.Action.Builder(null, Apps.label(this, pkg), pi).build())
        }
        actions.forEach { b.addAction(it) }
        return b.build()
    }

    private fun alert(title: String, text: String, open: Intent?) =
        post(WARN_ID, App.CH_ALERT, title, text, open)

    private fun post(id: Int, ch: String, title: String, text: String, open: Intent?) {
        val b = Notification.Builder(this, ch).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setVisibility(Notification.VISIBILITY_PUBLIC)
        open?.let { b.setContentIntent(PendingIntent.getActivity(this, id, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)) }
        getSystemService(NotificationManager::class.java).notify(id, b.build())
    }

    private fun openActivity(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Log.w("GuardService", "open: $e") }
    }
}
