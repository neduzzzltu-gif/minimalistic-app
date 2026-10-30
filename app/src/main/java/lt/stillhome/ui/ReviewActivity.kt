package lt.stillhome.ui

import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.stillhome.App
import lt.stillhome.core.Apps
import lt.stillhome.core.Cats
import lt.stillhome.core.Clock
import lt.stillhome.core.Limits
import lt.stillhome.core.fmtDur
import lt.stillhome.data.EventRow
import lt.stillhome.data.Store

/**
 * Sunday 20:00 review (section 14) and the end-of-baseline summary (section 13).
 * Back is disabled; the Done button is at the very end, so it can only be dismissed after scrolling through.
 */
class ReviewActivity : BlackActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val baseline = intent.getBooleanExtra("baseline", false)
        setContent { if (baseline) BaselineSummary() else Weekly() }
    }

    @androidx.compose.runtime.Composable
    private fun BaselineSummary() {
        BackHandler { }
        val avgScreen = Store.int("baselineAvgScreen", 0)
        val guess = Store.str("quiz.guessH", "?")
        Page {
            Title("Baseline week done")
            T("You guessed ${guess} h/day. Real: ${fmtDur(avgScreen * 60_000L)}/day.", 18.sp, White)
            Section("Budgets set (70% of your baseline)")
            Cats.budgeted.forEach { T("$it: ${Limits.budget(it)} min/day", 16.sp) }
            Section("Now active")
            T("Budgets, cooldowns and blocked hours are on. Lockdown is armed: accessibility, notification access, usage access, " +
                "this app's App info and its battery/autostart pages now send you home.", 15.sp, Dim)
            Spacer(Modifier.height(24.dp))
            Btn("Done") { Store.set("baselineSummary", null); goHome() }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Weekly() {
        BackHandler { }
        val dao = App.db.dao()
        val from = Clock.startOfDay(Clock.date().minusDays(6))
        val fromDay = Clock.daysAgo(6)
        val screen = remember { dao.screenSince(fromDay) }
        val meters = remember { dao.metersSince(fromDay) }
        val sessions = remember { dao.sessionsSince(from) }
        val changes = remember { dao.changesSince(from).filter { it.status != "cancelled" } }
        val notifs = remember { dao.notifLogSince(from) }
        val sas = remember { dao.events("sas", 20).reversed() }
        val perApp = remember { usagePerApp(from) }
        val goals = Store.list("goals")
        val ratings = remember { mutableStateMapOf<String, Int>() }
        var sasDue by remember { mutableStateOf(sasDue()) }

        Page {
            Title("Week review")
            Section("Phone time per day")
            screen.forEach { T("${it.day}: ${fmtDur(it.seconds * 1000)}", 15.sp) }
            val avg = if (screen.isEmpty()) 0 else screen.sumOf { it.seconds } / 60 / screen.size
            T("Average ${fmtDur(avg * 60_000)} · guess ${Store.str("quiz.guessH", "?")} h · target ${fmtDur(Store.int("targetMin", 120) * 60_000L)}", 15.sp, White)

            Section("Gated minutes per category per day")
            meters.groupBy { it.day }.toSortedMap().forEach { (d, l) ->
                T("$d: " + l.joinToString(" · ") { "${it.category} ${it.seconds / 60}" }, 14.sp)
            }
            Section("Sessions")
            sessions.groupBy { it.category }.forEach { (c, l) -> T("$c: ${l.size} sessions, ${l.sumOf { it.plannedMin }} min booked", 15.sp) }

            if (perApp.isNotEmpty()) {
                Section("Per app (Usage access)")
                perApp.take(15).forEach { (pkg, ms) -> T("${Apps.label(this@ReviewActivity, pkg)} (${Cats.of(pkg).ifEmpty { "?" }}): ${fmtDur(ms)}", 14.sp) }
            }

            Section("Your reasons")
            sessions.groupBy { it.reasonCat }.forEach { (rc, l) ->
                T(rc.ifEmpty { "-" }, 15.sp, White)
                l.forEach { s -> T("“${s.reason}” → ${s.intent.ifEmpty { "no answer" }}", 14.sp, Dim) }
            }
            val answers = sessions.groupingBy { it.intent.ifEmpty { "-" } }.eachCount()
            T("Did what I wrote: " + answers.entries.joinToString(" · ") { "${it.key} ${it.value}" }, 14.sp)

            Section("Rule changes this week")
            if (changes.isEmpty()) T("none", 14.sp, Dim)
            changes.forEach { T((if (it.status == "pending") "⏳ " else "") + it.description, 14.sp) }

            Section("Reels/posts sent to you")
            notifs.filter { it.kind == "share" }.groupBy { it.pkg }.forEach { (p, l) -> T("${Apps.label(this@ReviewActivity, p)}: ${l.size}", 14.sp) }

            Section("Self-check trend (personal trend, not a diagnosis)")
            if (sas.isEmpty()) T("no self-checks yet", 14.sp, Dim)
            sas.forEach { T("${Clock.day(it.time)}: ${it.text}/60", 14.sp) }
            if (sasDue) {
                T("Your 4-weekly self-check is due.", 15.sp, White)
                Btn("Do the self-check") { startActivity(Intent(this@ReviewActivity, OnboardingActivity::class.java).putExtra("page", "sas")) }
            }

            Section("Goals: rate this week 1–5")
            goals.forEach { g ->
                T(g, 16.sp, White)
                Row { (1..5).forEach { r -> Chip("$r", ratings[g] == r) { ratings[g] = r }; Spacer(Modifier.width(6.dp)) } }
            }
            Spacer(Modifier.height(30.dp))
            val ok = goals.all { it in ratings } && !sasDue()
            if (!ok) T(if (sasDue()) "Finish the self-check first." else "Rate every goal to finish.", 13.sp, Dim)
            Btn("Done", enabled = ok) {
                val now = Clock.now()
                ratings.forEach { (g, r) -> dao.insertEvent(EventRow(time = now, type = "goal", text = "$g=$r")) }
                Store.set("review.pending", null); goHome()
            }
            Spacer(Modifier.height(40.dp))
        }
        sasDue = sasDue()
    }

    private fun sasDue(): Boolean {
        val last = App.db.dao().events("sas", 1).firstOrNull()?.time ?: Store.long("setupAt", Clock.now())
        return Clock.now() - last > 28 * 86400_000L
    }

    private fun usagePerApp(from: Long): List<Pair<String, Long>> = try {
        val usm = getSystemService(UsageStatsManager::class.java)
        usm.queryAndAggregateUsageStats(from, Clock.now()).values
            .filter { it.totalTimeInForeground > 60_000 && it.packageName != packageName }
            .map { it.packageName to it.totalTimeInForeground }.sortedByDescending { it.second }
    } catch (_: Exception) { emptyList() }

    private fun goHome() { startActivity(Intent(this, MainActivity::class.java)); finish() }
}
