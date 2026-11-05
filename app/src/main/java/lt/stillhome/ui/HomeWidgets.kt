package lt.stillhome.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.interaction.DragInteraction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.stillhome.core.AppEntry
import lt.stillhome.core.Apps
import lt.stillhome.core.Clock
import lt.stillhome.core.fmtDur
import java.util.Locale

/** Black home palette: white text, red only for screen time over the goal, Google-Calendar blue for events. */
object H {
    val Bg = Color(0xFF000000)
    val Card = Color(0xFF111111)
    val Ink = Color(0xFFFFFFFF)
    val Sub = Color(0xFF8C8C8C)
    val Line = Color(0xFF262626)
    val Track = Color(0xFF1F1F1F)
    val Accent = Color(0xFFFFFFFF)
    val AccentSoft = Color(0xFF1F1F1F)
    val Warn = Color(0xFFE5484D)
    val WarnSoft = Color(0xFF2A1215)
    val Shadow = Color(0x00000000)
    /** Google Calendar's event blue and its "now" line red. */
    val Event = Color(0xFF039BE5)
    val NowLine = Color(0xFFD93025)
    /** Finished events: grey box, grey text. */
    val Past = Color(0xFF2A2A2A)
    /** Slice colors for the screen-time bar: top app brightest, "other" darkest. */
    val Seg = listOf(Color(0xFFFFFFFF), Color(0xFFB0B0B0), Color(0xFF707070), Color(0xFF404040))
}

// ---------- data (loaded off the main thread by Launcher) ----------

/** One pie slice: app name (or "other") and minutes used today. */
data class Slice(val label: String, val min: Int)

/** A calendar event for today: title + "08:00–08:45" (or "all day"), with its start/end to find the current one. */
data class CalEvent(val title: String, val time: String, val begin: Long, val end: Long, val allDay: Boolean) {
    fun now(t: Long) = !allDay && t in begin until end
}

/** Pie + today's calendar. null events = no calendar permission; loaded = false until the first load finishes. */
data class HomeExtras(val pie: List<Slice> = emptyList(), val events: List<CalEvent>? = null,
                      val date: java.time.LocalDate = Clock.date(), val loaded: Boolean = false, val at: Long = 0,
                      val usedMs: Long? = null)   // today's screen time from Android's usage events (null = no usage access)

/** `at` = load time: changes every refresh, so the box recomputes which event is "now" even if the list didn't change. */
fun loadExtras(ctx: Context): HomeExtras {
    val now = Clock.now()
    val use = try { lt.stillhome.core.ScreenTime.compute(ctx, Clock.date()) } catch (_: Exception) { null }
    return HomeExtras(slices(ctx, use), calendarToday(ctx, now), Clock.date(), true, now, use?.total)
}

/**
 * Foreground time per app since midnight, from the shared events engine (core/ScreenTime, same numbers as
 * the Screen time screen). Top 3 apps + "other"; launchers and system UI don't count.
 */
private fun slices(ctx: Context, use: lt.stillhome.core.DayUse?): List<Slice> = try {
    val apps = (use?.apps ?: emptyList())
        .map { it.pkg to (it.ms / 60_000).toInt() }.filter { it.second > 0 }
    val top = apps.take(3).map { Slice(Apps.label(ctx, it.first), it.second) }
    val rest = apps.drop(3).sumOf { it.second }
    if (rest > 0) top + Slice("other", rest) else top
} catch (_: Exception) { emptyList() }

/** Titles of today's events from the phone's synced calendars (Google Calendar), all-day first. */
private fun calendarToday(ctx: Context, now: Long): List<CalEvent>? {
    if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
    val day = (Clock.date().toEpochDay() + 2440588).toInt()   // Julian day, what Instances.CONTENT_BY_DAY_URI wants
    val uri = CalendarContract.Instances.CONTENT_BY_DAY_URI.buildUpon().appendPath("$day").appendPath("$day").build()
    val out = mutableListOf<CalEvent>()
    val hm = { ms: Long -> java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) } }
    try {
        ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY), "${CalendarContract.Instances.VISIBLE} = 1 AND (${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED})" +
            " AND (${CalendarContract.Instances.SELF_ATTENDEE_STATUS} IS NULL OR ${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED})", null,
            "${CalendarContract.Instances.ALL_DAY} DESC, ${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) (c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } ?: "(no title)").let {
                val allDay = c.getInt(3) == 1
               out += CalEvent(it, if (allDay) "all day" else hm(c.getLong(1)), c.getLong(1), c.getLong(2), allDay)
            }
        }
    } catch (_: Exception) { }
    return out
}

// ---------- building blocks ----------

/** Long-press anywhere on home = Settings. Provided by Home so tappable cards don't swallow it. */
val LocalHomeLongPress = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/** Tap = [onClick], long-press = the home long-press action. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.homeClick(onClick: () -> Unit): Modifier = composed {
    val long = LocalHomeLongPress.current
    combinedClickable(onClick = onClick, onLongClick = long)
}

/** Home text: Inter, one line by default, ellipsis when too long. */
@Composable
fun Tx(text: String, size: TextUnit, color: Color = H.Ink, weight: FontWeight = FontWeight.Normal,
       modifier: Modifier = Modifier, maxLines: Int = 1) =
    Text(text, modifier = modifier, color = color, fontSize = size, fontWeight = weight, fontFamily = Inter,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis, letterSpacing = if (size.value >= 24) (-0.5).sp else 0.sp)

/** White rounded card with a soft shadow. */
@Composable
fun HCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier.fillMaxWidth().shadow(10.dp, shape, ambientColor = H.Shadow, spotColor = H.Shadow)
            .background(H.Card, shape).clip(shape)
            .then(if (onClick != null) Modifier.homeClick(onClick) else Modifier)
            .padding(16.dp),
        content = content,
    )
}

/** Small rounded label ("59m", "Now · 21:00–21:30"). */
@Composable
fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) =
    Box(
        modifier.clip(CircleShape).background(bg)
            .then(if (onClick != null) Modifier.homeClick(onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) { Tx(text, 12.sp, fg, FontWeight.Medium) }

private fun mins(m: Int) = fmtDur(m * 60_000L)

// ---------- cards ----------

/** "Tuesday" + "3 November", text only (Settings = long-press anywhere, or the app list). */
@Composable
fun HomeHeader(date: java.time.LocalDate, card: String? = null, onCard: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Tx(date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH), 30.sp, H.Ink, FontWeight.SemiBold)
            Tx("${date.dayOfMonth} ${date.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)}", 14.sp, H.Sub)
        }
        // In a store with a saved card: a small button top right that pops in and opens the card.
        var lastCard by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(card ?: "") }   // keep the name while it animates out
        if (card != null) lastCard = card
        androidx.compose.animation.AnimatedVisibility(card != null,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.85f),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.85f)) {
            Row(Modifier.padding(top = 6.dp).clip(CircleShape).background(H.Card).homeClick(onCard).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(lt.stillhome.R.drawable.si_credit_card), null,
                    Modifier.size(18.dp), tint = H.Ink)
                Spacer(Modifier.width(8.dp))
                Tx(lastCard, 14.sp, H.Ink, FontWeight.Medium)
            }
        }
    }
}

/** Screen time today vs. the goal: big number, a bar split by app, then the top apps with their minutes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreenTimeCard(usedMs: Long, goalMs: Long, over: Boolean, slices: List<Slice>, budgets: List<Budget>, focus: String?,
                   onClick: (() -> Unit)? = null) {
    val pct = if (goalMs > 0) (usedMs * 100 / goalMs).toInt() else 0
    HCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Tx("Screen time", 14.sp, H.Sub, FontWeight.Medium, Modifier.weight(1f))
            Pill("$pct% used", if (over) H.WarnSoft else H.AccentSoft, if (over) H.Warn else H.Accent)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Tx(fmtDur(usedMs), 30.sp, if (over) H.Warn else H.Ink, FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Tx("of ${fmtDur(goalMs)}", 15.sp, H.Sub, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(14.dp))
        SplitBar(usedMs, goalMs, over, slices)
        if (slices.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                slices.forEachIndexed { i, s ->
                    if (i > 0) Box(Modifier.width(1.dp).height(34.dp).background(H.Line))
                    Column(Modifier.weight(1f).padding(start = if (i > 0) 12.dp else 0.dp, end = 4.dp)) {
                        Tx(mins(s.min), 17.sp, H.Ink, FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(H.Seg[i.coerceAtMost(H.Seg.lastIndex)]))
                            Spacer(Modifier.width(5.dp))
                            Tx(s.label, 12.sp, H.Sub)
                        }
                    }
                }
            }
        }
        if (budgets.isNotEmpty() || focus != null) {
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                focus?.let { Pill(it, H.AccentSoft, H.Accent) }
                budgets.forEach { b -> Pill(b.text, if (b.usedUp) H.WarnSoft else H.Track, if (b.usedUp) H.Warn else H.Sub) }
            }
        }
    }
}

/** Rounded bar to the daily goal. Filled part is split by app (same colors as the list); all red once over. */
@Composable
private fun SplitBar(usedMs: Long, goalMs: Long, over: Boolean, slices: List<Slice>) {
    val frac = if (goalMs > 0) (usedMs.toFloat() / goalMs).coerceIn(0f, 1f) else 0f
    val total = slices.sumOf { it.min }
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val rad = CornerRadius(size.height / 2)
        drawRoundRect(H.Track, cornerRadius = rad)
        val fill = size.width * frac
        if (fill <= 0f) return@Canvas
        val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, fill, size.height, rad)) }
        clipPath(clip) {
            if (over || total == 0) { drawRect(if (over) H.Warn else H.Accent, size = Size(fill, size.height)); return@clipPath }
            var x = 0f
            val gap = 2.dp.toPx()
            slices.forEachIndexed { i, s ->
                val w = fill * s.min / total
                drawRect(H.Seg[i.coerceAtMost(H.Seg.lastIndex)], Offset(x, 0f), Size((if (i < slices.lastIndex) w - gap else w).coerceAtLeast(0f), size.height))
                x += w
            }
        }
    }
}

/** Today's calendar: all-day events on top, then the scrollable day timeline. Tap = Google Calendar. */
@Composable
fun CalendarCard(x: HomeExtras, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val events = x.events
    // Denied for good ("don't ask again"): Android shows no dialog any more, so open the app's settings instead.
    // Only from the second try: a dialog dismissed by tapping outside looks the same as "denied for good".
    var askedOnce by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val act = ctx as? android.app.Activity
        if (!ok && askedOnce && act != null && !act.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR))
            ctx.openSystemSettings(appPerms(ctx), Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", ctx.packageName, null)), grant = "calendar")
        askedOnce = true
    }
    HCard(modifier, onClick = {
        if (events == null) ask.launch(Manifest.permission.READ_CALENDAR)
        else openGoogleCalendar(ctx)
    }) {
        when {
            !x.loaded -> {}
            events == null -> Tx("Tap to show your calendar", 14.sp, H.Sub)
            events.isEmpty() -> Tx("Nothing left today", 14.sp, H.Sub)
            else -> {
                val allDay = events.filter { it.allDay }
                val timed = events.filter { !it.allDay }
                allDay.take(2).forEach { ev -> EventBlock("${ev.title}, all day", Modifier.fillMaxWidth().height(30.dp)); Spacer(Modifier.height(4.dp)) }
                if (allDay.isNotEmpty() && timed.isNotEmpty()) Spacer(Modifier.height(6.dp))
                if (timed.isNotEmpty()) DayTimeline(timed, x.at)   // all of today, scrollable, even after the last task
                else if (allDay.isEmpty()) Tx("Nothing left today", 14.sp, H.Sub)
            }
        }
    }
}

/** Opens Google Calendar on today (any calendar app if Google's is missing). */
fun openGoogleCalendar(ctx: Context) {
    val i = Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath("${Clock.now()}").build())
    try { ctx.startActivity(newTask(Intent(i).setPackage("com.google.android.calendar"))) }
    catch (_: Exception) { try { ctx.startActivity(newTask(i)) } catch (_: Exception) { } }
}

/** Grey rounded event box with "title, 18:00"; finished ones get dimmer text. */
@Composable
private fun EventBlock(text: String, modifier: Modifier, past: Boolean = false) =
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(H.Past).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
        Tx(text, 14.sp, if (past) H.Sub else Color.White, FontWeight.Medium)
    }

/**
 * Google-Calendar-style day view of all of today. At rest the red "now" line sits one hour from the top and
 * moves down the day as time passes. Drag to look through the day right here (a native scroll, so it follows
 * the finger and flings); 10 s after the last drag it glides back to the red line.
 */
@Composable
private fun DayTimeline(all: List<CalEvent>, now: Long) {
    val hour = 3_600_000L
    val zone = java.time.ZoneId.systemDefault()
    val hourDp = 52f
    val labelW = 52.dp
    val minMs = (30f / hourDp * hour).toLong()   // shortest block drawn: 30 dp of time
    val day = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun y(t: Long) = (hourDp * (t - dayStart) / hour).dp
    // Rest view starts 12 dp above "an hour ago" so an hour label right at the top is never cut in half.
    val restPx = with(LocalDensity.current) { (y(now - hour) - 12.dp).roundToPx() }.coerceAtLeast(0)
    val rest by rememberUpdatedState(restPx)
    val scroll = rememberScrollState(restPx)
    val glide = tween<Float>(450, easing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f))
    var away by remember { mutableStateOf(false) }
    // 10 s after the last drag: glide back to the red line. A new drag stops the glide and restarts the wait.
    // (Compose already honours the phone's "animations off" setting.)
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collectLatest { i ->
            if (i is DragInteraction.Start) away = true
            else if (i is DragInteraction.Stop || i is DragInteraction.Cancel) {
                delay(10_000)
                scroll.animateScrollTo(rest.coerceAtMost(scroll.maxValue), glide)
                away = false
            }
        }
    }
    // Leaving Home ends the look-around, so coming back always shows the red line.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.currentStateFlow.collect {
            if (it == Lifecycle.State.CREATED && away) { away = false; scroll.scrollTo(rest.coerceAtMost(scroll.maxValue)) }
        }
    }
    // As time passes, follow the red line (gliding, never jumping), unless the user is looking around.
    LaunchedEffect(restPx) {
        if (away) return@LaunchedEffect
        val to = restPx.coerceAtMost(scroll.maxValue)
        if (kotlin.math.abs(to - scroll.value) > 6) scroll.animateScrollTo(to, glide) else scroll.scrollTo(to)
    }
    // Tasks that overlap on screen (short ones drawn at least minMs tall) go side by side, each overlap group
    // with its own column count; everything else takes the full width.
    val events = all.filter { it.end > dayStart && it.begin < dayEnd }
    fun visEnd(e: CalEvent) = maxOf(e.end, e.begin + minMs).coerceAtMost(dayEnd)
    val col = IntArray(events.size); val span = IntArray(events.size)
    val ends = mutableListOf<Long>(); var groupEnd = Long.MIN_VALUE; var from = 0
    events.forEachIndexed { i, e ->
        if (e.begin >= groupEnd) { for (k in from until i) span[k] = ends.size; ends.clear(); from = i }
        val free = ends.indexOfFirst { it <= e.begin }
        if (free >= 0) { col[i] = free; ends[free] = visEnd(e) } else { col[i] = ends.size; ends += visEnd(e) }
        groupEnd = maxOf(groupEnd, visEnd(e))
    }
    for (k in from until events.size) span[k] = ends.size.coerceAtLeast(1)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = (hourDp * 4).dp)) {   // 4 h, less if home is short on room
        val areaW = maxWidth - labelW
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().height(y(dayEnd))) {
                var hStart = java.time.Instant.ofEpochMilli(dayStart).atZone(zone).plusHours(1)
                while (hStart.toInstant().toEpochMilli() < dayEnd) {
                    val top = y(hStart.toInstant().toEpochMilli())
                    Box(Modifier.offset(x = labelW - 6.dp, y = top).fillMaxWidth().height(1.dp).background(H.Line))
                    Tx("%02d:00".format(hStart.hour), 13.sp, H.Sub, modifier = Modifier.offset(y = top - 8.dp).width(labelW))
                    hStart = hStart.plusHours(1)
                }
                Box(Modifier.offset(x = labelW).width(1.dp).fillMaxSize().background(H.Line))
                events.forEachIndexed { i, e ->
                    val top = y(e.begin.coerceAtLeast(dayStart)) + 1.dp
                    val h = (y(visEnd(e)) - top - 2.dp).coerceAtLeast(12.dp)
                    val w = areaW / span[i]
                    EventBlock("${e.title}, ${e.time}", past = e.end <= now,
                        modifier = Modifier.offset(x = labelW + 4.dp + w * col[i], y = top).width(w - 6.dp).height(h))
                }
                val nowY = y(now)
                Box(Modifier.offset(x = labelW, y = nowY - 1.dp).fillMaxWidth().height(2.dp).background(H.NowLine))
                Box(Modifier.offset(x = labelW - 6.dp, y = nowY - 6.dp).size(12.dp).clip(CircleShape).background(H.NowLine))
            }
        }
    }
}

/** Pinned apps as a 3-wide grid of names (no icons), "+ Add" to add, optional Focus mode pill. */
@Composable
fun AppsCard(pinned: List<AppEntry>, showPlus: Boolean, showFocus: Boolean,
             onApp: (String) -> Unit, onEdit: () -> Unit, onFocus: () -> Unit) {
    // Hidden apps drop off home right away (live Store read, not the 30 s refresh). Long-press an app = its sheet.
    val map by lt.stillhome.data.Store.map.collectAsState()
    val hidden = map.let { Apps.hidden() }   // reading the map = recompose on change
    var sheet by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<AppEntry?>(null) }
    sheet?.let { AppSheet(it) { sheet = null } }
    HCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Tap "Apps" = Settings › Home apps.
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).homeClick(onEdit).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Tx("Apps", 17.sp, H.Ink, FontWeight.SemiBold)
            }
            if (showFocus) Pill("Focus mode", H.AccentSoft, H.Accent, onClick = onFocus)
        }
        Spacer(Modifier.height(10.dp))
        val tiles: List<AppEntry?> = pinned.filter { it.pkg !in hidden } + if (showPlus) listOf(null) else emptyList()
        tiles.chunked(3).forEachIndexed { r, row ->
            if (r > 0) Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                row.forEach { a ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .then(if (a == null) Modifier.homeClick(onEdit) else Modifier.tapOrHold(onHold = { sheet = a }) { onApp(a.pkg) })
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Tx(a?.label ?: "+ Add", 15.sp, if (a == null) H.Sub else H.Ink, FontWeight.Medium, Modifier.padding(vertical = 8.dp))
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Floating search pill at the bottom: opens the app list. */
@Composable
fun SearchPill(onClick: () -> Unit) {
    Row(
        // Same look as the search field in the app list: black, thin grey outline, magnifier, "search".
        Modifier.padding(horizontal = 4.dp).fillMaxWidth().requiredHeight(50.dp).clip(CircleShape).background(Black)
            .border(1.5.dp, Faint, CircleShape).homeClick(onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val r = size.minDimension * 0.33f
            drawCircle(Dim, r, Offset(r + 2f, r + 2f), style = Stroke(1.5.dp.toPx()))
            drawLine(Dim, Offset(r * 1.75f + 2f, r * 1.75f + 2f), Offset(size.width - 2f, size.height - 2f), 1.5.dp.toPx())
        }
        Spacer(Modifier.width(12.dp))
        Tx("search", 18.sp, Faint)
    }
}
