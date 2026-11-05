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

    /**
     * Emergency master switch (guard off). Instant, no 24 h rule: the owner asked for a way to turn
     * everything off without a PC (e.g. on holiday). Reachable from the "Stillhome switch" icon,
     * the Quick Settings tile and Settings.
     */
    fun paused() = Store.bool("guardOff", false)
    fun setPaused(off: Boolean) { Store.set("guardOff", if (off) "1" else "0"); log("switch", if (off) "App blocker turned off" else "App blocker turned on") }

    /** Limits are off for now (owner, 2026-10-30): no budgets, cooldowns, blocked hours or auto-tighten.
     *  The Gate still asks why + how long. Turning them back on is instant (Settings). */
    fun limitsOn() = Store.bool("limitsOn", false)

    fun focusUntil() = Store.long("focusUntil", 0)
    fun focusActive(now: Long = Clock.now()) = focusUntil() > now
    /** "Lock the whole phone" focus: every app except calls, messages and the clock is locked, and it can't be ended early. */
    fun focusStrict(now: Long = Clock.now()) = focusActive(now) && Store.bool("focusStrict", false)
    fun startFocus(min: Int, strict: Boolean) {
        Store.setAll(mapOf("focusUntil" to (Clock.now() + min * 60_000L).toString(), "focusStrict" to if (strict) "1" else "0"))
        log("focus", "Focus $min min" + if (strict) " (whole phone locked)" else "")
    }
    /** Ending early is only allowed for a normal focus. */
    fun endFocus(): Boolean {
        if (!focusActive() || focusStrict()) return false
        Store.set("focusUntil", "0"); log("focus", "Focus ended early"); return true
    }

    fun lockdownArmed(now: Long = Clock.now()): Boolean {
        if (paused()) return false
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

    // ---------- system Settings pass (our own deep links into Settings) ----------
    const val SETTINGS = "com.android.settings"
    /** Settings may be labelled + gated like any app, but pages WE open (permissions, app info...) must not be. */
    fun settingsPass(now: Long = Clock.now()) = Store.long("settingsPassUntil", 0) > now
    /** Void unless Settings shows up within 10 s; then [extendSettingsPass] keeps it going. */
    fun startSettingsPass() { val now = Clock.now(); Store.setAll(mapOf("settingsPassStart" to "$now", "settingsPassUntil" to "${now + 10_000}")) }
    /** While Settings stays in front: 3 more min, hard cap 15 min after the start. */
    fun extendSettingsPass(now: Long = Clock.now()) =
        Store.set("settingsPassUntil", minOf(now + 3 * 60_000, Store.long("settingsPassStart", now) + 15 * 60_000).toString())
    fun endSettingsPass() { if (Store.get("settingsPassUntil") != null) Store.setAll(mapOf("settingsPassUntil" to null, "settingsPassStart" to null)) }

    // ---------- grant pass (lockdown exception for the permission checklist) ----------
    /**
     * Our checklist opened the page that grants permission [step]. While it runs (2 min, never extended)
     * and that permission is still missing, lockdown lets exactly that page through
     * (see [lt.stillhome.detect.Lockdown.grantAllows]). Granted or expired = lockdown as usual.
     */
    fun startGrantPass(step: String) = Store.setAll(mapOf("grantPassStep" to step, "grantPassUntil" to "${Clock.now() + 2 * 60_000}"))
    fun grantPass(now: Long = Clock.now()): String? = Store.get("grantPassStep")?.takeIf { Store.long("grantPassUntil", 0) > now }
    fun endGrantPass() { if (Store.get("grantPassStep") != null) Store.setAll(mapOf("grantPassStep" to null, "grantPassUntil" to null)) }

    // ---------- uninstall pass (home sheet uninstalling another app while lockdown is armed) ----------
    /** Never for ourselves: refused here, and the a11y service also refuses any screen that mentions us. */
    fun startUninstallPass(pkg: String) {
        if (pkg == lt.stillhome.App.app.packageName) return
        Store.setAll(mapOf("uninstallPassPkg" to pkg, "uninstallPassUntil" to "${Clock.now() + 60_000}"))
    }
    fun uninstallPass(now: Long = Clock.now()): String? = Store.get("uninstallPassPkg")?.takeIf { Store.long("uninstallPassUntil", 0) > now }

    // ---------- the decision ----------
    fun decide(target: String, pkg: String, cat: String, now: Long = Clock.now()): Decision {
        if (paused() || passActive(pkg, now) || (pkg == SETTINGS && settingsPass(now))) return Decision.Allow
        if (focusActive(now)) return Decision.Locked("Focus mode", focusUntil())   // focus beats running sessions
        // App groups: blocked always or in their time periods (beats running sessions too).
        Groups.blockedNow(lt.stillhome.App.app, pkg, now)?.let { return Decision.Locked(it.first, it.second) }
        if (session(target, now) != null) return Decision.Allow
        // No daily budgets or cooldowns any more (owner, 2026-11-03): the Gate just asks why + how long.
        return Decision.Gate(Limits.sessionOptions(cat), Int.MAX_VALUE)
    }

    fun log(type: String, text: String) = dao.insertEvent(EventRow(time = Clock.now(), type = type, text = text))
}
