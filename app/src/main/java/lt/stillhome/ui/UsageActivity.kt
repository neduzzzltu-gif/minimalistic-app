package lt.stillhome.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import lt.stillhome.core.AppUse
import lt.stillhome.core.Apps
import lt.stillhome.core.Clock
import lt.stillhome.core.DayUse
import lt.stillhome.core.Guard
import lt.stillhome.core.ScreenTime
import lt.stillhome.core.fmtDur
import lt.stillhome.core.fmtHm
import lt.stillhome.data.Store
import lt.stillhome.service.GuardA11yService
import lt.stillhome.service.GuardService
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Screen time / Calendar / Focus in one screen, opened from the home cards. Same black look as home
 * (H colors, HCard, Tx). Extra "tab" = "screen" | "calendar" | "focus". Back closes it.
 */
class UsageActivity : BlackActivity() {
    private var tab by mutableStateOf("screen")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getString("tab") ?: pick(intent)
        setContent { UsageScreen(tab) { tab = it } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); tab = pick(intent) }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putString("tab", tab) }
    private fun pick(i: Intent) = i.getStringExtra("tab")?.takeIf { it in TABS.map { t -> t.first } } ?: "screen"

    companion object {
        val TABS = listOf("screen" to "Screen time", "calendar" to "Calendar", "focus" to "Focus")
        fun open(ctx: Context, tab: String) = ctx.startActivity(newTask(Intent(ctx, UsageActivity::class.java).putExtra("tab", tab)))
    }
}

private val Good = Color(0xFF46A758)
private val GoodSoft = Color(0xFF12261A)
private val dm = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val wdm = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)

/** Finished days already read, kept while the screen is open (also across tab switches). */
private class DayCache { val days = HashMap<String, DayUse?>() }

@Composable
private fun UsageScreen(tab: String, onTab: (String) -> Unit) {
    val cache = remember { DayCache() }
    Box(Modifier.fillMaxSize().background(H.Bg).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            // Opened from the usage card: screen time only, no Calendar / Focus switch.
            if (tab != "screen") Segments(UsageActivity.TABS, tab, Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp), big = true, onPick = onTab)
            else Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    "calendar" -> CalendarTab()
                    "focus" -> FocusTab()
                    else -> ScreenTab(cache)
                }
            }
        }
    }
}

/** Pill-shaped segmented control: the picked one is white with black text. */
@Composable
private fun Segments(items: List<Pair<String, String>>, picked: String, modifier: Modifier = Modifier, big: Boolean = false, onPick: (String) -> Unit) {
    Row(modifier.fillMaxWidth().clip(CircleShape).background(H.Card).padding(4.dp)) {
        items.forEach { (key, label) ->
            val on = key == picked
            val bg by animateColorAsState(if (on) H.Ink else Color.Transparent, label = "seg")
            Box(Modifier.weight(1f).clip(CircleShape).background(bg).clickable { onPick(key) }
                .padding(vertical = if (big) 10.dp else 8.dp), contentAlignment = Alignment.Center) {
                Tx(label, if (big) 14.sp else 13.sp, if (on) Color.Black else H.Sub, FontWeight.Medium)
            }
        }
    }
}

/** Re-runs [block] every time the screen comes back, then every [everyMs] while it stays visible. */
@Composable
private fun WhileVisible(everyMs: Long, block: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { block(); delay(everyMs - Clock.now() % everyMs) } }
    }
}

// ================= Screen time =================

private val RANGES = listOf(1 to "Today", 3 to "3 days", 7 to "1 week", 14 to "2 weeks", 30 to "1 month")

/** One top-apps row, label already resolved off the main thread. */
private class AppRow(val label: String, val ms: Long, val opens: Int)

/** Everything the screen-time tab shows for one period. days: null = unknown day. change: % vs the period before. */
private class StView(val len: Int, val back: Int, val from: LocalDate, val to: LocalDate, val days: List<Pair<LocalDate, DayUse?>>,
                     val apps: List<AppRow>, val total: Long, val avg: Long, val opens: Int, val known: Int,
                     val change: Int?, val missing: String?)

/** Heavy part (events + Room), on IO. Finished days are cached for as long as the screen is open. Cancellable between days. */
private suspend fun buildView(ctx: Context, len: Int, back: Int, cache: MutableMap<String, DayUse?>): StView {
    val today = Clock.date()
    val to = today.minusDays(back.toLong() * len); val from = to.minusDays(len - 1L)
    suspend fun get(d: LocalDate, hours: Boolean): DayUse? {
        currentCoroutineContext().ensureActive()   // period changed meanwhile: stop reading days
        if (d >= today) return ScreenTime.day(ctx, d)   // today is always live
        val k = "$d|$hours"
        synchronized(cache) { if (cache.containsKey(k)) return cache[k] }
        return ScreenTime.day(ctx, d, hours).also { synchronized(cache) { cache[k] = it } }
    }
    val days = (0 until len).map { from.plusDays(it.toLong()) }.map { it to get(it, len == 1) }
    val known = days.mapNotNull { it.second }
    val merged = known.flatMap { it.apps }.groupBy { it.pkg }
        .map { (p, l) -> AppUse(p, l.sumOf { it.ms }, l.sumOf { it.opens }) }.sortedByDescending { it.ms }
    val total = known.sumOf { it.total }
    val avg = if (known.isEmpty()) 0 else total / known.size
    // Period before, same length. While this period is still running, today's partner day is cut at the
    // same time of day, so half of today isn't compared with a whole day. If events no longer cover the
    // partner, today and its partner both stay out (compare the len-1 complete days).
    val pFrom = from.minusDays(len.toLong()); val partner = today.minusDays(len.toLong())
    val running = back == 0
    val partnerOk = running && ScreenTime.covered(ctx, partner)
    val prev = (0 until len).map { pFrom.plusDays(it.toLong()) }.mapNotNull { d ->
        when {
            running && d == partner && partnerOk -> ScreenTime.compute(ctx, d, Clock.startOfDay(d) + (Clock.now() - Clock.startOfDay(today)))
            running && d == partner -> null
            else -> get(d, false)
        }
    }
    val cmp = if (running && !partnerOk) known.filter { it.day < today } else known
    val cmpAvg = if (cmp.isEmpty()) 0 else cmp.sumOf { it.total } / cmp.size
    val prevAvg = if (prev.isEmpty()) 0 else prev.sumOf { it.total } / prev.size
    val change = if (prevAvg > 0 && cmp.isNotEmpty()) Math.round((cmpAvg - prevAvg) * 100.0 / prevAvg).toInt() else null
    // Unknown days: all before the first known one = history starts there; otherwise name the gap.
    val nulls = days.filter { it.second == null }.map { it.first }
    val firstKnown = days.firstOrNull { it.second != null }?.first
    val missing = when {
        nulls.isEmpty() -> null
        firstKnown != null && nulls.all { it < firstKnown } -> "Data available from ${firstKnown.format(dm)}"
        nulls.size == 1 -> "No data for ${nulls[0].format(dm)}"
        else -> "No data for ${nulls.first().format(dm)}–${nulls.last().format(dm)}"
    }
    return StView(len, back, from, to, days, merged.map { AppRow(Apps.label(ctx, it.pkg), it.ms, it.opens) }, total, avg,
        known.sumOf { it.opens }, known.size, change, missing)
}

@Composable
private fun ScreenTab(dc: DayCache) {
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(ScreenTime.hasAccess(ctx)) }
    var len by rememberSaveable { mutableIntStateOf(1) }
    var back by rememberSaveable { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<StView?>(null) }
    // Back from the usage-access settings = re-check; then today is refreshed once a minute.
    WhileVisible(60_000) { access = ScreenTime.hasAccess(ctx); tick++ }
    if (!access) { NoAccess(); return }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ScreenTime.catchUp(ctx) } }   // save finished days before Android forgets them
    LaunchedEffect(len, back, tick) {
        if (tick == 0) return@LaunchedEffect   // first tick comes right away from WhileVisible
        view = withContext(Dispatchers.IO) {
            buildView(ctx, len, back, dc.days)
        }
    }
    val older: () -> Unit = { back++ }
    val newer: () -> Unit = { if (back > 0) back-- }
    val density = LocalDensity.current
    Column(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {   // swipe left/right = newer/older period
                var dx = 0f
                val min = with(density) { 70.dp.toPx() }
                detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx > min) older() else if (dx < -min) newer() }) { _, d -> dx += d }
            }
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Segments(RANGES.map { "${it.first}" to it.second }, "$len") { len = it.toInt(); back = 0 }
        val v = view
        // Header: ‹ date range ›
        val to = Clock.date().minusDays(back.toLong() * len); val from = to.minusDays(len - 1L)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Arrow("‹", true, older)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Tx(rangeTitle(len, back, from, to), 20.sp, H.Ink, FontWeight.SemiBold)
                Tx(if (len == 1) from.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) else "${len} days", 13.sp, H.Sub)
            }
            Arrow("›", back > 0, newer)
        }
        if (v == null) { Tx("Counting…", 14.sp, H.Sub, modifier = Modifier.padding(4.dp)); return@Column }
        // Old numbers stay (dimmed) until the new period has loaded.
        Column(Modifier.alpha(if (v.len == len && v.back == back) 1f else 0.5f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Summary(v)
            Chart(v) { d -> len = 1; back = ChronoUnit.DAYS.between(d, Clock.date()).toInt() }
            TopApps(v)
        }
        Spacer(Modifier.height(12.dp))
    }
}

private fun rangeTitle(len: Int, back: Int, from: LocalDate, to: LocalDate) = when {
    len == 1 && back == 0 -> "Today"
    len == 1 && back == 1 -> "Yesterday"
    len == 1 -> from.format(dm)
    else -> "${from.format(dm)} – ${to.format(dm)}"
}

@Composable
private fun Arrow(sym: String, enabled: Boolean, onClick: () -> Unit) =
    Box(Modifier.size(44.dp).clip(CircleShape).background(H.Card).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Tx(sym, 24.sp, if (enabled) H.Ink else H.Line, FontWeight.Medium, Modifier.padding(bottom = 3.dp))
    }

@Composable
private fun NoAccess() = Column(Modifier.fillMaxSize().padding(20.dp)) {
    val ctx = LocalContext.current
    HCard {
        Tx("Usage access needed", 17.sp, H.Ink, FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Tx("Screen time is counted from Android's usage data. Turn on usage access for Stillhome, then come back.", 14.sp, H.Sub, maxLines = 4)
        Spacer(Modifier.height(14.dp))
        MainBtn("Grant usage access") {
            ctx.openSystemSettings(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS,
                android.net.Uri.fromParts("package", ctx.packageName, null)), Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS), grant = "usage")
        }
    }
}

private fun dur(ms: Long) = if (ms in 1 until 60_000) "<1m" else fmtDur(ms)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(v: StView) = HCard {
    Tx(if (v.len == 1) "Screen time" else "Total", 14.sp, H.Sub, FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Tx(if (v.known == 0) "–" else fmtDur(v.total), 34.sp, H.Ink, FontWeight.SemiBold)
    Spacer(Modifier.height(2.dp))
    Tx(if (v.len == 1) "${v.opens} opens" else "Daily average ${fmtDur(v.avg)}" + if (v.known < v.len) " · ${v.known} of ${v.len} days" else "",
        14.sp, H.Sub)
    val pills = v.change != null || v.missing != null
    if (pills) {
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            v.change?.let { c ->
                val vs = when {
                    v.len == 1 && v.back == 0 -> "this time yesterday"
                    v.len == 1 -> "the day before"
                    else -> "previous ${v.len} days"
                }
                when {
                    c > 0 -> Pill("▲ $c% vs $vs", H.WarnSoft, H.Warn)
                    c < 0 -> Pill("▼ ${-c}% vs $vs", GoodSoft, Good)
                    else -> Pill("Same as $vs", H.Track, H.Sub)
                }
            }
            v.missing?.let { Pill(it, H.Track, H.Sub) }
        }
    }
}

/** Per-hour bars for one day, per-day bars otherwise (tap a day to open it). Dashed line = daily goal. */
@Composable
private fun Chart(v: StView, onDay: (LocalDate) -> Unit) {
    val hours = if (v.len == 1) v.days.first().second?.hours else null
    if (v.len == 1 && hours == null) return   // a saved old day has no hours: the total says it all
    val goal = Store.int("targetMin", 120) * 60_000L
    val values: List<Long?> = hours?.toList() ?: v.days.map { it.second?.total }
    val labels = if (hours != null) List(24) { if (it % 6 == 0) "%02d".format(it) else "" }
    else v.days.mapIndexed { i, (d, _) -> when {
        v.len <= 7 -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)
        v.len <= 14 -> "${d.dayOfMonth}"
        else -> if (i % 5 == 0 || i == v.len - 1) "${d.dayOfMonth}" else ""
    } }
    val peak = values.indices.maxByOrNull { values[it] ?: 0 }?.takeIf { (values[it] ?: 0) > 0 }
    HCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Tx(if (hours != null) "By hour" else "By day", 14.sp, H.Sub, FontWeight.Medium, Modifier.weight(1f))
            peak?.let { Tx(if (hours != null) "Busiest ${"%02d:00".format(it)}" else "Most ${dur(values[it] ?: 0)}", 13.sp, H.Sub) }
        }
        Spacer(Modifier.height(14.dp))
        val top = maxOf(values.maxOf { it ?: 0 }, if (hours == null) goal else 0L, if (hours != null) 15 * 60_000L else 60 * 60_000L) * 1.08f
        val n = values.size
        val tap = if (hours == null) Modifier.pointerInput(v) {
            detectTapGestures { o -> val i = (o.x / (size.width / n.toFloat())).toInt().coerceIn(0, n - 1); onDay(v.days[i].first) }
        } else Modifier
        Canvas(Modifier.fillMaxWidth().height(140.dp).then(tap)) {
            val slot = size.width / n
            val bw = (slot * if (n > 20) 0.62f else 0.5f).coerceAtMost(28.dp.toPx())
            val r = CornerRadius(minOf(bw / 2, 5.dp.toPx()))
            drawLine(H.Line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            values.forEachIndexed { i, ms ->
                val x = slot * i + (slot - bw) / 2
                if (ms == null) {   // unknown day: a faint stub, not a zero
                    drawRoundRect(H.Line, Offset(x, size.height - 4.dp.toPx()), Size(bw, 4.dp.toPx()), r); return@forEachIndexed
                }
                if (ms <= 0) return@forEachIndexed
                val h = (size.height * ms / top).coerceAtLeast(3.dp.toPx())
                val c = if (hours == null && ms > goal) H.Warn else H.Ink
                drawRoundRect(c, Offset(x, size.height - h), Size(bw, h), r)
            }
            if (hours == null) {
                val y = size.height - size.height * goal / top
                drawLine(H.Sub, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { l ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (l.isNotEmpty()) Tx(l, 11.sp, H.Sub, modifier = Modifier.wrapContentWidth(unbounded = true))
                }
            }
        }
        if (hours == null) {
            Spacer(Modifier.height(10.dp))
            Tx("Dashed line = daily goal ${fmtDur(goal)}. Tap a day to open it.", 12.sp, H.Sub, maxLines = 2)
        }
    }
}

@Composable
private fun TopApps(v: StView) {
    if (v.apps.isEmpty()) { if (v.known > 0) HCard { Tx("No screen time", 14.sp, H.Sub) }; return }
    var all by remember(v.from, v.len) { mutableStateOf(false) }
    val max = v.apps.first().ms.coerceAtLeast(1)
    HCard {
        Tx("Top apps", 14.sp, H.Sub, FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        (if (all) v.apps else v.apps.take(8)).forEach { a ->
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tx(a.label, 15.sp, H.Ink, FontWeight.Medium, Modifier.weight(1f).padding(end = 12.dp))
                    Tx(dur(a.ms), 15.sp, H.Ink, FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(H.Track)) {
                        Box(Modifier.fillMaxWidth(a.ms.toFloat() / max).fillMaxHeight().clip(CircleShape).background(H.Seg[1]))
                    }
                    Spacer(Modifier.width(12.dp))
                    Tx(if (a.opens == 1) "1 open" else "${a.opens} opens", 12.sp, H.Sub)
                }
            }
        }
        if (v.apps.size > 8) {
            Spacer(Modifier.height(4.dp))
            Tx(if (all) "Show less" else "Show all ${v.apps.size} apps", 14.sp, H.Ink, FontWeight.Medium,
                Modifier.clip(CircleShape).clickable { all = !all }.padding(vertical = 8.dp))
        }
    }
}

// ================= Calendar =================

/** An event instance today. color = the calendar's display color. */
private class CalItem(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int, val where: String)

/** Today's instances from every calendar synced to the phone (read-only). null = no permission. */
private fun loadToday(ctx: Context): List<CalItem>? {
    if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
    val jd = (Clock.date().toEpochDay() + 2440588).toInt()   // Julian day, what CONTENT_BY_DAY_URI wants
    val uri = CalendarContract.Instances.CONTENT_BY_DAY_URI.buildUpon().appendPath("$jd").appendPath("$jd").build()
    val out = mutableListOf<CalItem>()
    try {
        ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.EVENT_LOCATION),
            "${CalendarContract.Instances.VISIBLE} = 1 AND (${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED})" +
                " AND (${CalendarContract.Instances.SELF_ATTENDEE_STATUS} IS NULL OR ${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED})", null,
            "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) out += CalItem(c.getLong(0), c.getString(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "(no title)",
                c.getLong(2), c.getLong(3), c.getInt(4) == 1, if (c.isNull(5)) 0xFF039BE5.toInt() else c.getInt(5), c.getString(6)?.trim() ?: "")
        }
    } catch (_: Exception) { }
    return out
}

/** Opens the event in Google Calendar (any calendar app if that one is missing). */
private fun openEvent(ctx: Context, e: CalItem) {
    val i = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end)
    try { ctx.startActivity(newTask(Intent(i).setPackage("com.google.android.calendar"))) }
    catch (_: Exception) { try { ctx.startActivity(newTask(i)) } catch (_: Exception) { } }
}

private fun hm(t: Long) = fmtHm(Clock.minuteOfDay(t))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarTab() {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<CalItem>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(Clock.now()) }
    // Every minute (on the minute) while visible: moves the "now" line and picks up calendar changes.
    WhileVisible(60_000) { now = Clock.now(); items = withContext(Dispatchers.IO) { loadToday(ctx) }; loaded = true }
    // Same as the home card: denied for good = Android shows no dialog, so open the app's settings (from the 2nd try).
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val act = ctx as? android.app.Activity
        if (ok) scope.launch { items = withContext(Dispatchers.IO) { loadToday(ctx) } }
        else if (askedOnce && act != null && !act.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR))
            ctx.openSystemSettings(appPerms(ctx), Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", ctx.packageName, null)), grant = "calendar")
        askedOnce = true
    }
    val list = items
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Tx("Today", 26.sp, H.Ink, FontWeight.SemiBold)
                Tx(Clock.date(now).format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)), 14.sp, H.Sub)
            }
            if (list != null) Pill("Open Calendar", H.Card, H.Ink) {
                try { ctx.startActivity(newTask(Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon()
                    .appendPath("time").appendPath("${Clock.now()}").build()))) } catch (_: Exception) { }
            }
        }
        if (!loaded) return@Column
        if (list == null) {
            HCard {
                Tx("Calendar access needed", 17.sp, H.Ink, FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Tx("Shows today's events from the calendars synced to this phone. Read-only.", 14.sp, H.Sub, maxLines = 3)
                Spacer(Modifier.height(14.dp))
                MainBtn("Grant calendar access") { ask.launch(Manifest.permission.READ_CALENDAR) }
            }
            return@Column
        }
        val allDay = list.filter { it.allDay }
        val timed = list.filter { !it.allDay }
        if (allDay.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            allDay.forEach { e ->
                val c = Color(e.color)
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(c).clickable { openEvent(ctx, e) }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Tx(e.title, 13.sp, if (c.luminance() > 0.55f) Color.Black else Color.White, FontWeight.Medium)
                }
            }
        }
        if (list.isEmpty()) Tx("Nothing planned today", 14.sp, H.Sub, modifier = Modifier.padding(start = 4.dp))
        Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(24.dp)).background(H.Card)) {
            Timeline(timed, now) { openEvent(ctx, it) }
        }
    }
}

/** Whole day, one row per hour, scrolled to now on open. Overlapping events sit side by side. */
@Composable
private fun Timeline(events: List<CalItem>, now: Long, onEvent: (CalItem) -> Unit) {
    val hourH = 60.dp; val labelW = 50.dp; val minH = 24.dp
    val dayStart = Clock.startOfDay(Clock.date(now)); val dayEnd = Clock.startOfDay(Clock.date(now).plusDays(1))
    // Wall-clock position (minute of day), so DST days still line up with the hour labels.
    fun y(t: Long): Dp = when {
        t <= dayStart -> 0.dp
        t >= dayEnd -> hourH * 24
        else -> Clock.dateTime(t).let { hourH * ((it.hour * 60 + it.minute + it.second / 60f) / 60f) }
    }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) {   // start with "now" a bit below the top
        snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
        scroll.scrollTo(with(density) { (y(Clock.now()) - 90.dp).toPx() }.toInt().coerceAtLeast(0))
    }
    // Columns: events that (visibly) overlap share a cluster and split its width. A short event counts as
    // at least minH tall, so blocks that would touch on screen also go side by side.
    val minMs = (minH / hourH * 3_600_000).toLong()
    val sorted = events.sortedWith(compareBy<CalItem> { it.begin }.thenByDescending { it.end - it.begin })
    val col = HashMap<CalItem, Int>(); val cols = HashMap<CalItem, Int>()
    var cluster = mutableListOf<CalItem>(); var ends = mutableListOf<Long>(); var clusterEnd = Long.MIN_VALUE
    fun flush() { cluster.forEach { cols[it] = ends.size }; cluster = mutableListOf(); ends = mutableListOf() }
    sorted.forEach { e ->
        val end = maxOf(e.end, e.begin + minMs)
        if (e.begin >= clusterEnd) flush()
        val free = ends.indexOfFirst { it <= e.begin }
        if (free >= 0) { col[e] = free; ends[free] = end } else { col[e] = ends.size; ends += end }
        cluster += e; clusterEnd = maxOf(clusterEnd, end)
    }
    flush()
    Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp).height(hourH * 24)) {
            val areaW = maxWidth - labelW - 8.dp
            for (h in 0..24) {
                val top = hourH * h
                Box(Modifier.offset(x = labelW, y = top).fillMaxWidth().height(1.dp).background(H.Line))
                if (h in 1..23) Tx("%02d:00".format(h), 12.sp, H.Sub, modifier = Modifier.offset(x = 10.dp, y = top - 8.dp))
            }
            sorted.forEach { e ->
                val n = cols[e] ?: 1; val w = areaW / n
                val top = y(e.begin)
                val h = maxOf(y(e.end) - top - 2.dp, minH)
                val c = Color(e.color); val past = e.end <= now
                val fg = if (c.luminance() > 0.55f) Color.Black else Color.White
                Box(Modifier.offset(x = labelW + 4.dp + w * (col[e] ?: 0), y = top + 1.dp).width(w - 3.dp).height(h)
                    .clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = if (past) 0.4f else 1f)).clickable { onEvent(e) }
                    .padding(horizontal = 8.dp, vertical = if (h < 36.dp) 3.dp else 6.dp)) {
                    Column {
                        Tx(e.title, 13.sp, fg, FontWeight.Medium, maxLines = if (h >= 64.dp) 2 else 1)
                        if (h >= 40.dp) Tx("${hm(e.begin)}–${hm(e.end)}" + if (e.where.isNotEmpty()) " · ${e.where}" else "", 11.sp, fg.copy(alpha = 0.8f))
                    }
                }
            }
            // "now" line: red, Google Calendar style, moves every minute.
            val ny = y(now)
            Box(Modifier.offset(x = labelW, y = ny - 1.dp).fillMaxWidth().height(2.dp).background(H.NowLine))
            Box(Modifier.offset(x = labelW - 5.dp, y = ny - 5.dp).size(10.dp).clip(CircleShape).background(H.NowLine))
        }
    }
}

// ================= Focus =================

private fun hms(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Same controls as Settings → Screen time → Focus mode. */
@Composable
private fun FocusTab() {
    val ctx = LocalContext.current
    var min by rememberSaveable { mutableIntStateOf(25) }
    var strict by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(Clock.now()) }
    LaunchedEffect(Unit) { while (true) { now = Clock.now(); delay(1000 - now % 1000) } }
    fun applied() { now = Clock.now(); GuardService.refresh(ctx); GuardA11yService.instance?.recheck() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (Guard.focusActive(now)) HCard {
            Tx("Focus mode on", 14.sp, H.Sub, FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Tx(hms(Guard.focusUntil() - now), 48.sp, H.Ink, FontWeight.SemiBold)
            Tx("until ${hm(Guard.focusUntil())}", 15.sp, H.Sub)
            Spacer(Modifier.height(16.dp))
            if (Guard.focusStrict(now)) Tx("Whole phone locked. It can't be ended early.", 14.sp, H.Sub, maxLines = 2)
            else MainBtn("End focus mode now", primary = false) { Guard.endFocus(); applied() }
        } else HCard {
            Tx("Focus mode", 17.sp, H.Ink, FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Tx("Blocked apps lock and chat notifications wait in the digest. Calls, SMS, alarms and codes still come through.", 14.sp, H.Sub, maxLines = 4)
            Spacer(Modifier.height(16.dp))
            TimeSlider((1..36).map { it * 5 }, min, onChange = { min = it })   // 5 min to 3 h
            Spacer(Modifier.height(8.dp))
            ToggleRow("Lock the whole phone", sub = if (strict) "Only calls, messages and the clock. Can't be ended early." else "You can end it early.",
                on = strict, onToggle = { strict = !strict })
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth()) { MainBtn("Start focus mode") { Guard.startFocus(min, strict); applied() } }
        }
        if (Guard.paused()) Tx("The app blocker is off, so focus mode won't lock anything.", 13.sp, H.Sub, maxLines = 2, modifier = Modifier.padding(start = 4.dp))
    }
}
