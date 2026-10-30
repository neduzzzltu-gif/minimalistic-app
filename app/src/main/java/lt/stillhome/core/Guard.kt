package lt.stillhome.core

import lt.stillhome.App
import lt.stillhome.data.EventRow
import lt.stillhome.data.SessionRow
import lt.stillhome.data.Store
import kotlin.math.pow

/** What should happen when a gated target (app or Chrome site) comes to the foreground. */
sealed class Decision {
    data object Allow : Decision()
    data class Gate(val options: List<Int>, val remainingMin: Int) : Decision()
    data class Locked(val why: String, val until: Long) : Decision()
}

/**
 * Sessions, budgets, cooldowns, blocked hours, focus mode. Pure policy + the session table;
 * enforcement (going home, opening the Gate) lives in the accessibility service.
 */
object Guard {
    private val dao get() = App.db.dao()

    // ---------- phases ----------
    /** "setup" (onboarding not finished), "baseline" (7 tracking days) or "active". */
    fun phase(): String = Store.str("phase", "setup")
    fun inBaseline() = phase() == "baseline"
    fun rulesActive() = phase() == "active"

    fun focusUntil() = Store.long("focusUntil", 0)
    fun focusActive(now: Long = Clock.now()) = focusUntil() > now

    fun lockdownArmed(now: Long = Clock.now()): Boolean {
        val at = Store.long("lockdownAt", 0)
        return at > 0 && now >= at && Store.str("lockdown", "1") != "0"
    }

    // ---------- sessions ----------
    @Volatile private var open: List<SessionRow>? = null
    fun openSessions(): List<SessionRow> = open ?: dao.openSessions().also { open = it }
    private fun invalidate() { open = null }

    /** A running, unexpired session for this target. */
    fun session(target: String, now: Long = Clock.now()) = openSessions().firstOrNull { it.target == target && now < it.plannedEnd }

    fun startSession(target: String, pkg: String, cat: String, minutes: Int, reasonCat: String, reason: String): SessionRow {
        val s = SessionRow(target = target, pkg = pkg, category = Cats.budgetCat(cat), start = Clock.now(),
            plannedMin = minutes, reasonCat = reasonCat, reason = reason, baseline = inBaseline())
        val id = dao.insertSession(s); invalidate()
        return s.copy(id = id)
    }

    fun endSession(s: SessionRow, at: Long = Clock.now()) {
        dao.endSession(s.id, minOf(at, s.plannedEnd).coerceAtLeast(s.start + 1)); invalidate()
    }

    /** Ends every session whose time is up; returns them so the caller can enforce "time's up". */
    fun expireDue(now: Long = Clock.now()): List<SessionRow> {
        val due = openSessions().filter { now >= it.plannedEnd }
        due.forEach { endSession(it, it.plannedEnd) }
        return due
    }

    // ---------- budget ----------
    private fun todaySessions(cat: String): List<SessionRow> =
        dao.sessionsSince(Clock.startOfDay()).filter { it.category == Cats.budgetCat(cat) }

    /** Minutes of this category's budget already spent today (running sessions count elapsed time). */
    fun usedMin(cat: String, now: Long = Clock.now()): Int = (todaySessions(cat).sumOf {
        (if (it.end > 0) it.end else minOf(now, it.plannedEnd)) - it.start } / 60_000).toInt()

    /** Minutes still free for a NEW session (running sessions reserve their remaining time). */
    fun remainingMin(cat: String, now: Long = Clock.now()): Int {
        val reserved = todaySessions(cat).filter { it.end == 0L }.sumOf { (it.plannedEnd - now).coerceAtLeast(0) } / 60_000
        return (Limits.budget(cat) - usedMin(cat, now) - reserved.toInt()).coerceAtLeast(0)
    }

    /** End of the cooldown that follows the last session in this budget category, or null. */
    fun cooldownUntil(cat: String, now: Long = Clock.now()): Long? {
        val bc = Cats.budgetCat(cat)
        if (openSessions().any { it.category == bc && now < it.plannedEnd }) return null
        val last = dao.lastEnded(bc) ?: return null
        val nToday = todaySessions(bc).count { it.end > 0 && it.start <= last.start }.coerceAtLeast(1)
        val esc = (Store.int("escalation", 100) / 100.0).pow(nToday - 1)
        val mins = maxOf(30.0, last.plannedMin * Store.int("cooldownMult", 2) * esc)
        val until = last.end + (mins * 60_000).toLong()
        return until.takeIf { it > now }
    }

    // ---------- checkout pass (app-only loyalty cards) ----------
    fun passActive(pkg: String, now: Long = Clock.now()) = Store.long("pass.$pkg", 0) > now
    fun startPass(pkg: String, store: String) {
        Store.set("pass.$pkg", (Clock.now() + 3 * 60_000).toString())
        log("pass", "Checkout pass: $pkg at $store")
    }

    // ---------- the decision ----------
    fun decide(target: String, pkg: String, cat: String, now: Long = Clock.now()): Decision {
        if (passActive(pkg, now)) return Decision.Allow
        if (focusActive(now)) return Decision.Locked("Focus mode", focusUntil())   // focus beats running sessions
        if (session(target, now) != null) return Decision.Allow
        val opts = Limits.sessionOptions(cat)
        if (!rulesActive()) return Decision.Gate(opts, Int.MAX_VALUE)   // setup + baseline: track only
        Blocked.blockedUntil(now)?.let { return Decision.Locked("Blocked hours", it) }
        val remaining = remainingMin(cat, now)
        val fit = opts.filter { it <= remaining }
        if (fit.isEmpty()) return Decision.Locked("Today's ${Cats.budgetCat(cat)} budget is used up",
            Clock.startOfDay(Clock.date(now).plusDays(1)))
        cooldownUntil(cat, now)?.let { return Decision.Locked("Cooldown after your last ${Cats.budgetCat(cat)} session", it) }
        return Decision.Gate(fit, remaining)
    }

    fun log(type: String, text: String) = dao.insertEvent(EventRow(time = Clock.now(), type = type, text = text))
}
