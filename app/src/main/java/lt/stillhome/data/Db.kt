package lt.stillhome.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One Gate session. target = package name, or "chrome:<domain>" for gated sites in Chrome. */
@Entity
data class SessionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val target: String,
    val pkg: String,
    val category: String,      // budget category (Social / Video / Games / Shops)
    val start: Long,
    val plannedMin: Int,
    val end: Long = 0,         // 0 = still running
    val reasonCat: String,
    val reason: String,
    val intent: String = "",   // Yes / Partly / No after time's up
    val baseline: Boolean = false,
) { val plannedEnd get() = start + plannedMin * 60_000L }

/** Seconds spent on metered screens, per day and budget category (for stats + baseline averages). */
@Entity(primaryKeys = ["day", "category"])
data class MeterRow(val day: String, val category: String, val seconds: Long)

/** Total screen-on seconds per day (own tracking via SCREEN_ON/OFF, works without Usage access). */
@Entity
data class ScreenRow(@PrimaryKey val day: String, val seconds: Long)

/** A notification held for the digest. kind: msg / snap / share / like / other. */
@Entity
data class DigestRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String, val app: String, val title: String, val text: String,
    val time: Long, val kind: String, val seen: Boolean = false,
)

/** Notification channel discovered from real notifications. decision = user override or null (default). */
@Entity(primaryKeys = ["pkg", "channel"])
data class ChannelRow(val pkg: String, val channel: String, val name: String, val app: String, val decision: String? = null)

/** Raw notification texts (Instagram/Snapchat during baseline) + every decision, for the review. */
@Entity
data class NotifLogRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String, val channel: String, val title: String, val text: String,
    val time: Long, val kind: String, val decision: String,
)

@Entity
data class CardRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val brand: String, val number: String, val format: String,
    val appPkg: String = "",   // non-empty = app-only card (rotating code) -> "checkout pass"
)

/** Store location saved by hand ("Save this place"). */
@Entity
data class PlaceRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val brand: String, val lat: Double, val lon: Double)

/**
 * Rule change log + 24 h queue. status: applied / pending / cancelled.
 * Pending rows carry key/value and are applied by the service once applyAt passes.
 */
@Entity
data class ChangeRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long, val description: String, val loosening: Boolean,
    val status: String, val key: String = "", val value: String? = null, val applyAt: Long = 0,
)

/** Per-day, per-app screen time snapshot (from UsageEvents RESUMED/PAUSED pairs). day = "2026-11-04". */
@Entity(primaryKeys = ["day", "pkg"])
data class AppDayRow(val day: String, val pkg: String, val ms: Long, val opens: Int)

/** A day whose snapshot was saved (so a day with 0 use is "known", not "missing"). final = the day is over. */
@Entity
data class DaySavedRow(@PrimaryKey val day: String, val savedAt: Long, val final: Boolean)

/** The daily essay. day = essay day (resets 04:00). */
@Entity
data class EssayRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val day: String, val time: Long, val prompt: String, val text: String, val words: Int)

/** Generic event log: checkout passes, detector failures, lockdown hits, SAS scores, goal ratings... */
@Entity
data class EventRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val time: Long, val type: String, val text: String)

@Dao
interface Dao_ {
    // sessions
    @Insert fun insertSession(s: SessionRow): Long
    @Query("UPDATE SessionRow SET `end` = :end WHERE id = :id") fun endSession(id: Long, end: Long)
    @Query("UPDATE SessionRow SET intent = :intent WHERE id = :id") fun setIntent(id: Long, intent: String)
    @Query("SELECT * FROM SessionRow WHERE `end` = 0") fun openSessions(): List<SessionRow>
    @Query("SELECT * FROM SessionRow WHERE start >= :from ORDER BY start") fun sessionsSince(from: Long): List<SessionRow>
    @Query("SELECT * FROM SessionRow WHERE category = :cat AND `end` > 0 ORDER BY `end` DESC LIMIT 1") fun lastEnded(cat: String): SessionRow?
    @Query("SELECT reason FROM SessionRow ORDER BY start DESC LIMIT :n") fun lastReasons(n: Int): List<String>
    @Query("SELECT reason FROM SessionRow WHERE target = :target ORDER BY start DESC LIMIT :n") fun lastReasonsFor(target: String, n: Int): List<String>
    @Query("SELECT * FROM SessionRow WHERE id = :id") fun session(id: Long): SessionRow?

    // metering + screen time
    @Query("INSERT OR IGNORE INTO MeterRow(day, category, seconds) VALUES(:day, :cat, 0)") fun ensureMeter(day: String, cat: String)
    @Query("UPDATE MeterRow SET seconds = seconds + :s WHERE day = :day AND category = :cat") fun addMeter(day: String, cat: String, s: Long)
    @Query("SELECT * FROM MeterRow WHERE day >= :from") fun metersSince(from: String): List<MeterRow>
    @Query("INSERT OR IGNORE INTO ScreenRow(day, seconds) VALUES(:day, 0)") fun ensureScreen(day: String)
    @Query("UPDATE ScreenRow SET seconds = seconds + :s WHERE day = :day") fun addScreen(day: String, s: Long)
    @Query("SELECT * FROM ScreenRow WHERE day >= :from ORDER BY day") fun screenSince(from: String): List<ScreenRow>
    @Query("SELECT * FROM ScreenRow WHERE day >= :from ORDER BY day") fun screenFlow(from: String): Flow<List<ScreenRow>>

    // digest + notifications
    @Insert fun insertDigest(d: DigestRow)
    @Query("SELECT * FROM DigestRow ORDER BY time DESC LIMIT 500") fun digestFlow(): Flow<List<DigestRow>>
    @Query("SELECT COUNT(*) FROM DigestRow WHERE seen = 0") fun unseenDigestFlow(): Flow<Int>
    @Query("SELECT COUNT(*) FROM DigestRow WHERE seen = 0") fun unseenDigest(): Int
    @Query("UPDATE DigestRow SET seen = 1") fun markDigestSeen()
    @Query("DELETE FROM DigestRow WHERE time < :before") fun pruneDigest(before: Long)
    @Upsert fun upsertChannel(c: ChannelRow)
    @Query("SELECT * FROM ChannelRow WHERE pkg = :pkg AND channel = :ch") fun channel(pkg: String, ch: String): ChannelRow?
    @Query("SELECT * FROM ChannelRow ORDER BY app, name") fun channelsFlow(): Flow<List<ChannelRow>>
    @Query("SELECT * FROM ChannelRow") fun channels(): List<ChannelRow>
    @Insert fun insertNotifLog(n: NotifLogRow)
    @Query("SELECT * FROM NotifLogRow WHERE pkg = :pkg ORDER BY time DESC LIMIT 200") fun notifLog(pkg: String): List<NotifLogRow>
    @Query("SELECT * FROM NotifLogRow WHERE time >= :from") fun notifLogSince(from: Long): List<NotifLogRow>
    @Query("DELETE FROM NotifLogRow WHERE time < :before") fun pruneNotifLog(before: Long)

    // cards + places
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsertCard(c: CardRow): Long
    @Query("DELETE FROM CardRow WHERE id = :id") fun deleteCard(id: Long)
    @Query("SELECT * FROM CardRow ORDER BY name") fun cardsFlow(): Flow<List<CardRow>>
    @Query("SELECT * FROM CardRow") fun cards(): List<CardRow>
    @Query("SELECT * FROM CardRow WHERE id = :id") fun card(id: Long): CardRow?
    @Insert fun insertPlace(p: PlaceRow)
    @Query("SELECT * FROM PlaceRow") fun places(): List<PlaceRow>

    // rule changes
    @Insert fun insertChange(c: ChangeRow): Long
    @Query("SELECT * FROM ChangeRow WHERE status = 'pending' ORDER BY applyAt") fun pending(): List<ChangeRow>
    @Query("SELECT * FROM ChangeRow WHERE status = 'pending' ORDER BY applyAt") fun pendingFlow(): Flow<List<ChangeRow>>
    @Query("UPDATE ChangeRow SET status = :status WHERE id = :id") fun setChangeStatus(id: Long, status: String)
    @Query("SELECT * FROM ChangeRow WHERE time >= :from ORDER BY time") fun changesSince(from: Long): List<ChangeRow>

    // events
    @Insert fun insertEvent(e: EventRow)
    @Query("SELECT * FROM EventRow WHERE type = :type ORDER BY time DESC LIMIT :n") fun events(type: String, n: Int = 50): List<EventRow>
    @Query("SELECT * FROM EventRow WHERE time >= :from ORDER BY time") fun eventsSince(from: Long): List<EventRow>

    // per-app daily screen time (screen time tab)
    @Upsert fun upsertAppDays(rows: List<AppDayRow>)
    @Query("DELETE FROM AppDayRow WHERE day = :day") fun clearAppDay(day: String)
    @Query("SELECT * FROM AppDayRow WHERE day >= :from AND day <= :to") fun appDays(from: String, to: String): List<AppDayRow>
    @Upsert fun upsertDaySaved(d: DaySavedRow)
    @Query("SELECT * FROM DaySavedRow WHERE day >= :from AND day <= :to") fun daysSaved(from: String, to: String): List<DaySavedRow>
    @Query("SELECT MIN(day) FROM DaySavedRow") fun firstSavedDay(): String?

    // daily essay
    @Insert fun insertEssay(e: EssayRow): Long
    @Query("SELECT * FROM EssayRow ORDER BY time DESC") fun essaysFlow(): Flow<List<EssayRow>>
    @Query("SELECT * FROM EssayRow WHERE day = :day LIMIT 1") fun essayFor(day: String): EssayRow?
}

@Database(
    entities = [SessionRow::class, MeterRow::class, ScreenRow::class, DigestRow::class, ChannelRow::class,
        NotifLogRow::class, CardRow::class, PlaceRow::class, ChangeRow::class, EventRow::class,
        AppDayRow::class, DaySavedRow::class, EssayRow::class],
    version = 2,
    autoMigrations = [androidx.room.AutoMigration(from = 1, to = 2)],
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): Dao_

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            // allowMainThreadQueries: all queries are tiny and indexed; keeps the code simple.
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "stillhome.db")
                .allowMainThreadQueries().build().also { inst = it }
        }
    }
}
