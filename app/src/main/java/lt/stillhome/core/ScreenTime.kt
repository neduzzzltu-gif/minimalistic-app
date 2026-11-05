package lt.stillhome.core

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import lt.stillhome.App
import lt.stillhome.data.AppDayRow
import lt.stillhome.data.DaySavedRow
import lt.stillhome.data.Store
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** One app's use on one day. */
data class AppUse(val pkg: String, val ms: Long, val opens: Int)

/** One day: per-app use, plus per-hour ms when it came straight from events (Room keeps no hours). */
class DayUse(val day: LocalDate, val apps: List<AppUse>, val hours: LongArray?) {
    val total = apps.sumOf { it.ms }
    val opens = apps.sumOf { it.opens }
}

/**
 * Screen time engine: per-app foreground time + opens from raw resume/pause events (like Digital Wellbeing,
 * NOT queryUsageStats' daily buckets, which can start yesterday). Android keeps events only ~a week, so
 * finished days are also saved to Room (AppDayRow + DaySavedRow) by a daily worker and on every open.
 */
object ScreenTime {
    private const val LOOK = 12 * 3_600_000L  // read well before midnight so a session running at 00:00 is split right
    private const val MERGE = 5_000L          // paused then resumed again (same app, nothing between) = one visit
    private const val STALE_STOP = 3_000L     // STOPPED this long after the screen's resume = its PAUSED went missing
    private val dao get() = App.db.dao()

    fun hasAccess(ctx: Context): Boolean {
        val m = ctx.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return if (m == AppOpsManager.MODE_DEFAULT) ctx.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
            PackageManager.PERMISSION_GRANTED else m == AppOpsManager.MODE_ALLOWED
    }

    /** Not counted: this launcher, every other launcher, the system UI and the system chooser. */
    @Volatile private var skipCache: Set<String>? = null
    fun skipped(ctx: Context): Set<String> = skipCache ?: run {
        val homes = try {
            ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
                // Settings' FallbackHome (priority -1000) also answers HOME: it's not a launcher, Settings must count.
                .filter { it.priority > -1000 && it.activityInfo.packageName != "com.android.settings" }.map { it.activityInfo.packageName }
        } catch (_: Exception) { emptyList() }
        (setOf(ctx.packageName, "com.android.systemui", "android") + homes).also { skipCache = it }
    }

    /**
     * One day's use from events, up to [until] (default: end of day, or now for today). null = no usage access.
     * Each app is "on screen" while at least one of its screens is resumed; an in-app screen switch
     * (A paused, B resumed, A stopped) stays one visit. Screen off / lock / shutdown closes everything.
     */
    fun compute(ctx: Context, day: LocalDate, until: Long? = null): DayUse? {
        val start = Clock.startOfDay(day)
        val end = minOf(Clock.startOfDay(day.plusDays(1)), until ?: Long.MAX_VALUE, Clock.now())
        if (end <= start) return DayUse(day, emptyList(), LongArray(24))
        val ev = try { ctx.getSystemService(UsageStatsManager::class.java).queryEvents(start - LOOK, end) } catch (_: Exception) { null } ?: return null
        val skip = skipped(ctx)
        val ms = HashMap<String, Long>(); val opens = HashMap<String, Int>(); val hours = LongArray(24)
        fun add(p: String, a: Long, b: Long) {
            val s = maxOf(a, start); val e = minOf(b, end)
            if (e <= s || p in skip) return
            ms.merge(p, e - s, Long::plus); splitHours(s, e, hours)
        }
        val screens = HashMap<String, HashMap<String, Int>>()   // pkg -> screen class -> resumed count
        val resumedAt = HashMap<String, Long>()                 // "pkg/class" -> last resume
        val since = HashMap<String, Long>()                     // pkg -> visit start
        val paused = HashMap<String, Long>()                    // pkg -> when its last screen left (it may come right back)
        val seen = HashSet<String>()
        fun finish(p: String) { val t = paused.remove(p) ?: return; since.remove(p)?.let { add(p, it, t) } }
        fun closeAll(t: Long) {
            paused.keys.toList().forEach(::finish)
            since.forEach { (p, s) -> add(p, s, t) }; since.clear(); screens.clear()
        }
        fun left(p: String, t: Long) { if (screens[p].isNullOrEmpty() && p in since && p !in paused) paused[p] = t }
        val e = UsageEvents.Event()
        while (ev.getNextEvent(e)) {
            val p = e.packageName ?: continue; val cls = e.className ?: ""; val t = e.timeStamp
            // Only activity events mark an app as seen (a background event must not hide that it was open since before the window).
            val act = e.eventType == UsageEvents.Event.ACTIVITY_RESUMED || e.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                e.eventType == UsageEvents.Event.ACTIVITY_STOPPED
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    paused.keys.filter { it != p }.forEach(::finish)   // another app came up: the paused ones are really gone
                    paused.remove(p)?.let { pt -> if (t - pt > MERGE) since.remove(p)?.let { add(p, it, pt) } }
                    if (p !in since) { since[p] = t; if (t >= start && p !in skip) opens.merge(p, 1, Int::plus) }
                    screens.getOrPut(p) { HashMap() }.merge(cls, 1, Int::plus)
                    resumedAt["$p/$cls"] = t
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val m = screens[p]; val n = m?.get(cls)
                    if (m != null && n != null) { if (n <= 1) m.remove(cls) else m[cls] = n - 1; left(p, t) }
                    else if (p !in seen) add(p, start - LOOK, t)   // was already open before the window
                }
                // Usually follows PAUSED and changes nothing. Right after a resume of the same class it's the OLD
                // instance stopping (no instance ids in the public API), so only trust it once that resume is stale.
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val m = screens[p]
                    if (m != null && (m[cls] ?: 0) > 0 && t - (resumedAt["$p/$cls"] ?: 0) > STALE_STOP) { m.remove(cls); left(p, t) }
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> closeAll(t)
            }
            if (act) seen += p
        }
        closeAll(end)
        val apps = ms.filter { it.value > 0 }.map { (p, v) -> AppUse(p, v, opens[p] ?: 0) }.sortedByDescending { it.ms }
        return DayUse(day, apps, hours)
    }

    private fun splitHours(s: Long, e: Long, hours: LongArray) {
        val zone = ZoneId.systemDefault()
        var a = s
        while (a < e) {
            val dt = Clock.dateTime(a)
            val next = dt.withMinute(0).withSecond(0).withNano(0).plusHours(1).atZone(zone).toInstant().toEpochMilli()
            val b = minOf(e, next)
            hours[dt.hour] += b - a; a = b
        }
    }

    /** Events still cover the whole of [day]: there are events from the day before it (Android drops whole days). */
    fun covered(ctx: Context, day: LocalDate): Boolean {
        if (day >= Clock.date()) return true
        val s = Clock.startOfDay(day)
        return try {
            ctx.getSystemService(UsageStatsManager::class.java).queryEvents(s - 86_400_000L, s)?.hasNextEvent() == true
        } catch (_: Exception) { false }
    }

    private fun save(day: LocalDate, u: DayUse) {
        val d = day.toString()
        App.db.runInTransaction {
            dao.clearAppDay(d)
            dao.upsertAppDays(u.apps.map { AppDayRow(d, it.pkg, it.ms, it.opens) })
            dao.upsertDaySaved(DaySavedRow(d, Clock.now(), day < Clock.date()))
        }
    }

    /** Compute [day] from events and save it to Room. false = events no longer cover that day. */
    fun snapshot(ctx: Context, day: LocalDate): Boolean {
        if (!hasAccess(ctx) || !covered(ctx, day)) return false
        val u = compute(ctx, day) ?: return false
        save(day, u); return true
    }

    /** Save each of the last 7 days that isn't saved as final yet. */
    @Synchronized fun catchUp(ctx: Context) {
        if (!hasAccess(ctx)) return
        val today = Clock.date()
        val done = dao.daysSaved(today.minusDays(7).toString(), today.minusDays(1).toString()).filter { it.final }.map { it.day }.toSet()
        for (n in 1L..7L) today.minusDays(n).let { if (it.toString() !in done) snapshot(ctx, it) }
    }

    /** From GuardService's minute tick: catch up once per day on a background thread. */
    fun catchUpDaily(ctx: Context) {
        val today = Clock.day()
        if (Store.str("st.catchUp", "") == today) return
        Store.set("st.catchUp", today)
        val app = ctx.applicationContext
        Thread { try { catchUp(app) } catch (_: Exception) { } }.start()
    }

    private fun fromRoom(day: LocalDate): DayUse = DayUse(day,
        dao.appDays(day.toString(), day.toString()).map { AppUse(it.pkg, it.ms, it.opens) }.sortedByDescending { it.ms }, null)

    /**
     * A day for the screen: today live; finished days from Room (or live from events when [hours] are wanted
     * and events still have it). null = unknown (too old for events and never saved).
     */
    fun day(ctx: Context, day: LocalDate, hours: Boolean = false): DayUse? {
        if (day >= Clock.date()) return compute(ctx, day)
        val saved = dao.daysSaved(day.toString(), day.toString()).firstOrNull()
        if (saved?.final == true && !hours) return fromRoom(day)
        if (covered(ctx, day)) return compute(ctx, day)?.also { if (saved?.final != true) save(day, it) }
        return if (saved != null) fromRoom(day) else null
    }
}

/** Daily snapshot of finished days (Android forgets detailed events after ~a week). */
class ScreenTimeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result { ScreenTime.catchUp(applicationContext); return Result.success() }

    companion object {
        /** Called from App.onCreate: one daily run shortly after midnight + a catch-up now. */
        fun schedule(ctx: Context) = try {
            val wm = WorkManager.getInstance(ctx)
            val now = Clock.now()
            val first = Clock.startOfDay(Clock.date(now).plusDays(1)) + 20 * 60_000L - now   // ~00:20
            wm.enqueueUniquePeriodicWork("screenTime.daily", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ScreenTimeWorker>(24, TimeUnit.HOURS).setInitialDelay(first, TimeUnit.MILLISECONDS).build())
            wm.enqueueUniqueWork("screenTime.catchUp", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ScreenTimeWorker>().build())
            Unit
        } catch (_: Exception) { }
    }
}
