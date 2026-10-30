package lt.stillhome.core

import android.os.SystemClock
import lt.stillhome.data.Store
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Tamper-resistant clock. If the system time jumps backwards (to get a fresh budget or end a
 * cooldown), keep counting from the latest time we have seen, using the monotonic clock.
 */
object Clock {
    private var baseWall = 0L      // wall time at last rebase
    private var baseElapsed = 0L   // elapsedRealtime at last rebase
    private var lastPersist = 0L

    fun now(): Long {
        val wall = System.currentTimeMillis()
        val el = SystemClock.elapsedRealtime()
        if (baseWall == 0L) {
            // First call in this process: never go below the last time saved before a kill/reboot.
            baseWall = maxOf(wall, Store.long("clock.lastSeen", 0L)); baseElapsed = el
        }
        val expected = baseWall + (el - baseElapsed)
        val t = if (wall < expected - 60_000) expected else wall   // >1 min backwards = tampering
        baseWall = t; baseElapsed = el
        if (t - lastPersist > 60_000) { lastPersist = t; Store.set("clock.lastSeen", t.toString()) }
        return t
    }

    private val zone get() = ZoneId.systemDefault()
    fun dateTime(t: Long = now()): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(t), zone)
    fun date(t: Long = now()): LocalDate = dateTime(t).toLocalDate()
    fun day(t: Long = now()): String = date(t).toString()          // "2026-10-30"
    fun startOfDay(d: LocalDate = date()): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
    fun minuteOfDay(t: Long = now()): Int = dateTime(t).let { it.hour * 60 + it.minute }
    fun dayOfWeek(t: Long = now()): DayOfWeek = dateTime(t).dayOfWeek
    fun daysAgo(n: Long): String = date().minusDays(n).toString()
}

fun fmtDur(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    return if (m >= 60) (if (m % 60 == 0L) "${m / 60}h" else "${m / 60}h ${m % 60}m") else "${m}m"
}

fun fmtMmSs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun fmtHm(minOfDay: Int) = "%02d:%02d".format(minOfDay / 60 % 24, minOfDay % 60)
