package lt.stillhome.core

import lt.stillhome.App
import lt.stillhome.data.ChangeRow
import lt.stillhome.data.Store
import lt.stillhome.service.Pending
import kotlin.math.abs

/** Section 8 table: options the user may pick from, defaults and hard ceilings. */
data class Limit(val budgets: List<Int>, val default: Int, val ceiling: Int, val sessions: List<Int>)

object Limits {
    /** "Longest session" choices, same for every category (owner, 2026-11-04). Default = the last one (60). */
    private val SESSIONS = listOf(5, 10, 15, 30, 45, 60)
    val table = mapOf(
        Cats.SOCIAL to Limit(listOf(15, 30, 45, 60, 90), 45, 90, SESSIONS),
        Cats.VIDEO to Limit(listOf(30, 45, 60, 90, 120), 60, 120, SESSIONS),
        Cats.GAMES to Limit(listOf(30, 45, 60, 90), 45, 90, SESSIONS),
        Cats.SHOPS to Limit(listOf(10, 15, 30), 15, 30, SESSIONS),
    )
    fun of(cat: String) = table.getValue(Cats.budgetCat(cat))

    /** Today's budget in minutes (never above the ceiling, whatever is stored). */
    fun budget(cat: String): Int { val l = of(cat); return Store.int("budget.${Cats.budgetCat(cat)}", l.default).coerceIn(0, l.ceiling) }
    /** Longest session option the user allows for this category. */
    fun sessionMax(cat: String): Int { val l = of(cat); return Store.int("sessionMax.${Cats.budgetCat(cat)}", l.sessions.last()).coerceAtMost(l.sessions.last()) }
    fun sessionOptions(cat: String) = of(cat).sessions.filter { it <= sessionMax(cat) }

    /** 70% of the baseline average, rounded to the nearest option, within the ceiling. */
    fun fromBaseline(cat: String, avgMin: Double): Int {
        val l = of(cat); val want = avgMin * 0.7
        return l.budgets.minBy { abs(it - want) }.coerceAtMost(l.ceiling)
    }

    val cooldownOptions = listOf(1, 2, 3)            // x session length, min 30 min
    val escalationOptions = listOf(100, 125, 150, 200) // percent per extra session that day
    val tightenOptions = listOf(0, 5, 10)            // minutes per week (Social)
}

/** A blocked-hours window: days (1=Mon..7=Sun) and start/end minute of day. end < start = crosses midnight. */
data class Window(val days: Set<Int>, val start: Int, val end: Int) {
    fun encode() = days.sorted().joinToString("") + "|$start|$end"
    fun label(): String {
        val d = if (days.size == 7) "Daily" else if (days == setOf(1, 2, 3, 4, 5)) "Mon–Fri"
        else days.sorted().joinToString(" ") { listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")[it - 1] }
        return "$d ${fmtHm(start)}–${fmtHm(end)}"
    }
    companion object {
        fun decode(s: String): Window? = s.split('|').takeIf { it.size == 3 }?.let { p ->
            Window(p[0].mapNotNull { it.digitToIntOrNull() }.toSet(), p[1].toInt(), p[2].toInt()) }
    }
}

object Blocked {
    val defaults = listOf(Window((1..7).toSet(), 23 * 60, 8 * 60), Window((1..5).toSet(), 8 * 60, 15 * 60))
    fun windows(): List<Window> = Store.get("blocked")?.let { s -> s.split('\n').mapNotNull { Window.decode(it) } } ?: defaults
    fun encode(ws: List<Window>) = ws.joinToString("\n") { it.encode() }

    /** Set of blocked minutes of the week (0 = Monday 00:00). */
    fun coverage(ws: List<Window>): java.util.BitSet {
        val bits = java.util.BitSet(7 * 1440)
        for (w in ws) for (d in w.days) {
            val len = if (w.end > w.start) w.end - w.start else 1440 - w.start + w.end
            for (i in 0 until len) bits.set(((d - 1) * 1440 + w.start + i) % (7 * 1440))
        }
        return bits
    }

    /** If blocked now: the time the block ends; otherwise null. */
    fun blockedUntil(now: Long = Clock.now()): Long? = blockedUntil(windows(), now)

    /** Same for any list of windows (app groups have their own). */
    fun blockedUntil(ws: List<Window>, now: Long): Long? {
        val bits = coverage(ws)
        val mow = (Clock.dayOfWeek(now).value - 1) * 1440 + Clock.minuteOfDay(now)
        if (!bits[mow]) return null
        var m = mow; var n = 0
        while (bits[m % (7 * 1440)] && n < 7 * 1440) { m++; n++ }
        val secIntoMinute = (now / 1000) % 60
        return now + n * 60_000L - secIntoMinute * 1000
    }
}

/**
 * The waiting rule (section 17, reworked 2026-11-04). Every change goes through [change]:
 * tightening applies instantly; loosening becomes the ONE pending change. After [DELAY] a
 * notification asks "still want it?" and only its Apply (or Apply in Settings) applies it.
 * Ignored = stays pending forever. ChangeRow.applyAt = the time from which it may be applied.
 */
object Rules {
    const val DELAY = 3600_000L   // 1 hour

    /** Before onboarding finishes everything applies instantly. */
    fun setupDone() = Store.bool("setupDone", false)
    fun isLoosening(key: String, old: String?, new: String?): Boolean {
        fun n(s: String?) = s?.toIntOrNull()
        return when {
            key.startsWith("budget.") || key.startsWith("sessionMax.") || key == "tightenFloor" || key == "targetMin" ->
                (n(new) ?: Int.MAX_VALUE) > (n(old) ?: defaultOf(key))
            key == "cooldownMult" || key == "escalation" || key == "autoTighten" || key == "countdown" || key == "reasonMin" ->
                (n(new) ?: 0) < (n(old) ?: defaultOf(key))
            key == "blocked" -> {
                val o = Blocked.coverage(old?.let { s -> s.split('\n').mapNotNull { Window.decode(it) } } ?: Blocked.defaults)
                val nw = Blocked.coverage(new?.split('\n')?.mapNotNull { Window.decode(it) } ?: emptyList())
                o.andNot(nw); !o.isEmpty   // some minute was blocked before and isn't now
            }
            key.startsWith("group.") -> Groups.looser(old, new)
            key.startsWith("gray.") -> new == "0" && old != "0"
            key.startsWith("mode.") -> new == "chats" && old != "chats"
            key == "essay" -> new == "0" && old != "0"   // daily essay off
            key.startsWith("label.") -> {
                val o = (old ?: Cats.UNLABELED).let {
                    // an unlabelled system app (e.g. Settings) was never gated, so labelling it is not loosening
                    if (it == Cats.UNLABELED && !Cats.isGated(App.app, key.removePrefix("label."))) Cats.OTHER else it }
                val nn = new ?: Cats.UNLABELED
                // out of a gated category, or between two gated categories (different budget) = loosening
                Cats.isGatedCat(o) && (!Cats.isGatedCat(nn) || (o != Cats.UNLABELED && o != nn)) ||
                    // leaving a category that a switched-on group blocks
                    (o != nn && Groups.all().any { g -> g.on && "cat:$o" in g.members && "cat:$nn" !in g.members })
            }
            key.startsWith("domain.") -> old != null && old in Cats.gated && (new == null || new !in Cats.gated)
            key == "lockdown" -> new == "0" && old != "0"
            key == "focusShortcut" -> false
            else -> false   // notification / digest / home-app settings: always instant
        }
    }

    private fun defaultOf(key: String): Int = when {
        key.startsWith("budget.") -> Limits.of(key.removePrefix("budget.")).default
        key.startsWith("sessionMax.") -> Limits.of(key.removePrefix("sessionMax.")).sessions.last()
        key == "tightenFloor" -> 20
        key == "targetMin" -> 120
        key == "cooldownMult" -> 2
        key == "escalation" -> 100
        key == "autoTighten" -> 5
        key == "countdown" -> 10
        key == "reasonMin" -> 20
        else -> 0
    }

    /** Clamp to ceilings so nothing above a ceiling can ever be stored. */
    private fun clamp(key: String, v: String?): String? = when {
        v == null -> null
        key.startsWith("budget.") -> v.toInt().coerceIn(0, Limits.of(key.removePrefix("budget.")).ceiling).toString()
        else -> v
    }

    /** Propose one change. Returns true if it applied now, false if it is now the pending change. */
    fun change(key: String, value: String?, description: String): Boolean {
        val dao = App.db.dao()
        val v = clamp(key, value)
        val old = Store.get(key)
        val pend = dao.pending()
        // Any new edit of a key replaces what was waiting for it (also "set back to the current value").
        pend.filter { it.key == key }.forEach { Pending.drop(App.app, it.id) }
        if (old == v) return true
        val now = Clock.now()
        if (!(setupDone() && isLoosening(key, old, v))) {
            Store.set(key, v)
            dao.insertChange(ChangeRow(time = now, description = description, loosening = false, status = "applied", key = key, value = v))
            return true
        }
        // One pending change at a time: a new loosening request cancels every other one and restarts the hour.
        pend.filter { it.key != key }.forEach { Pending.drop(App.app, it.id) }
        val id = dao.insertChange(ChangeRow(time = now, description = description, loosening = true, status = "pending",
            key = key, value = v, applyAt = now + DELAY))
        Pending.stamp(App.app, id)
        Pending.schedule(App.app, id, now + DELAY)
        return false
    }

    /** Would [change] leave this as the pending change (instead of applying it now)? Same value = no. */
    fun wouldWait(key: String, value: String?): Boolean {
        val v = clamp(key, value); val old = Store.get(key)
        return old != v && setupDone() && isLoosening(key, old, v)
    }

    /** Is [key] already set to [value] (after clamping)? Then [change] would do nothing but drop a pending edit of it. */
    fun same(key: String, value: String?) = Store.get(key) == clamp(key, value)

    fun cancel(id: Long) = Pending.drop(App.app, id)

    /** The hour is over (wall clock AND monotonic clock, so moving the clock forward doesn't help). */
    fun isDue(c: ChangeRow, now: Long = Clock.now()) = now >= c.applyAt && Pending.monoLeft(App.app, c.id) <= 0

    /** Time until it may be applied (for countdowns). */
    fun leftMs(c: ChangeRow, now: Long = Clock.now()) = maxOf(c.applyAt - now, Pending.monoLeft(App.app, c.id), 0)

    /** Apply a pending change. Refused (false) before the hour is up or if it's no longer pending. */
    fun apply(id: Long, durable: Boolean = false): Boolean {
        val dao = App.db.dao()
        val c = dao.pending().firstOrNull { it.id == id } ?: return false
        if (!isDue(c)) return false
        // durable: from a receiver the process may die right away, so the value must be on disk before "applied".
        clamp(c.key, c.value).let { if (durable) Store.setDurable(c.key, it) else Store.set(c.key, it) }
        dao.setChangeStatus(c.id, "applied")
        Pending.clear(App.app, c.id)
        return true
    }

    /** The one waiting change (null = none). */
    fun current(): ChangeRow? = App.db.dao().pending().firstOrNull()

    fun pendingFor(key: String): ChangeRow? = App.db.dao().pending().firstOrNull { it.key == key }
}
