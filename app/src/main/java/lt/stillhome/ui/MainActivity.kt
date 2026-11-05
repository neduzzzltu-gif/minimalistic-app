package lt.stillhome.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import lt.stillhome.App
import lt.stillhome.core.AppEntry
import lt.stillhome.core.Apps
import lt.stillhome.core.Cats
import lt.stillhome.core.Clock
import lt.stillhome.core.Decision
import lt.stillhome.core.Guard
import lt.stillhome.core.Limits
import lt.stillhome.core.fmtDur
import lt.stillhome.data.Store
import lt.stillhome.service.GuardA11yService
import lt.stillhome.service.GuardService

/** A category budget on the home screen: "social 5 of 15 min". */
data class Budget(val text: String, val usedUp: Boolean)

/** What the home screen's bottom lines show (one fact per line, no dot-joined strings). */
data class HomeStatus(val time: String, val budgets: List<Budget>, val focus: String?, val newCount: Int, val paused: Boolean,
                      val overTarget: Boolean, val card: String?, val guardOff: Boolean, val usedMs: Long, val goalMs: Long)

fun homeStatus(): HomeStatus {
    val dao = App.db.dao()
    val todayMs = (dao.screenSince(Clock.day()).sumOf { it.seconds }) * 1000
    val target = Store.int("targetMin", 120)
    val budgets = emptyList<Budget>()   // daily app limits removed (2026-11-03)
    val focus = if (Guard.focusActive()) "focus until ${lt.stillhome.core.fmtHm(Clock.minuteOfDay(Guard.focusUntil()))}" else null
    val n = dao.unseenDigest()
    val card = Store.get("inStoreCard")?.toLongOrNull()?.let { dao.card(it)?.name }
    return HomeStatus("${fmtDur(todayMs)} / ${fmtDur(target * 60_000L)} today", budgets, focus, n, Guard.paused(),
        todayMs > target * 60_000L, card, !GuardA11yService.running(), todayMs, target * 60_000L)
}

class MainActivity : BlackActivity() {
    companion object { const val EXTRA_NEXT = "next" }
    private var drawerOpen = mutableStateOf(false)
    private var next: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Home is black: light status/nav icons.
        enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.BLACK))
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        next = intent.getParcelableExtra(EXTRA_NEXT, Intent::class.java)
        setContent { Launcher(drawerOpen) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        drawerOpen.value = false   // Home button always lands on the home page
        homeReset.intValue++       // ...and closes any open app sheet
        next = intent.getParcelableExtra(EXTRA_NEXT, Intent::class.java)
    }

    override fun onStart() {
        super.onStart()
        // StayFree's widgets used to be hosted off-screen to read its numbers; free that host once.
        if (!Store.bool("sf.hostGone", false)) {
            try { android.appwidget.AppWidgetHost(this, 0x57F).deleteHost() } catch (_: Exception) { }
            Store.set("sf.hostGone", "true")
        }
    }

    override fun onResume() {
        super.onResume()
        GuardService.start(this)
        // Daily essay first, even before a guard-sent Gate (once per day from 04:00; off / paused / before setup = never).
        if (lt.stillhome.core.Essay.due()) {
            next = null; intent.removeExtra(EXTRA_NEXT)
            startActivity(newTask(Intent(this, EssayActivity::class.java))); return
        }
        // Sent here by the guard: we are Home now, so open the Gate / Lock / Time's up on top.
        next?.let { next = null; intent.removeExtra(EXTRA_NEXT); startActivity(newTask(it)); return }
        // Things that must be dealt with before using the phone (none while the guard is off):
        if (Guard.paused()) return
        when {
            !Store.bool("setupDone", false) -> startActivity(newTask(Intent(this, OnboardingActivity::class.java)))
            Store.get("review.pending") != null || Store.get("baselineSummary") == "1" ->
                startActivity(newTask(Intent(this, ReviewActivity::class.java).putExtra("baseline", Store.get("baselineSummary") == "1")))
            Store.get("intentCheck") != null && Clock.now() - Store.long("intentCheckAt", 0) > 5000 -> startActivity(newTask(Intent(this, GateActivity::class.java)
                .putExtra("mode", "intent").putExtra("session", Store.long("intentCheck", 0))))
        }
    }
}

/** Start our own screens in their own task: keeps the Home task = only the home screen. */
fun newTask(i: Intent): Intent = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Open an app from the launcher: gated apps go straight to the Gate (no flash of the app). */
fun android.content.Context.openApp(pkg: String) {
    // App group blocked right now: straight to the lock screen, even for apps that are otherwise free.
    lt.stillhome.core.Groups.blockedNow(this, pkg)?.let { (why, until) ->
        if (!Guard.paused()) { startActivity(newTask(Intent(this, GateActivity::class.java).putExtra("target", pkg).putExtra("pkg", pkg)
            .putExtra("cat", Cats.of(pkg)).putExtra("mode", "lock").putExtra("why", why).putExtra("until", until))); return }
    }
    if (Cats.gatedAtLaunch(this, pkg)) {
        if (Cats.needsLabel(this, pkg)) { startActivity(newTask(Intent(this, LabelActivity::class.java).putExtra("pkg", pkg))); return }
        val cat = Cats.of(pkg)
        val i = Intent(this, GateActivity::class.java).putExtra("target", pkg).putExtra("pkg", pkg).putExtra("cat", cat)
        when (val d = Guard.decide(pkg, pkg, cat)) {
            Decision.Allow -> { Apps.launch(this, pkg); return }
            is Decision.Locked -> i.putExtra("mode", "lock").putExtra("why", d.why).putExtra("until", d.until)
            is Decision.Gate -> i.putExtra("mode", "gate")
        }
        startActivity(newTask(i))
    } else Apps.launch(this, pkg)
}

@Composable
private fun Launcher(drawerOpen: androidx.compose.runtime.MutableState<Boolean>) {
    // Home's root activity has no back stack: with no enabled callback the system handles back itself
    // (Android 12+: moves the home task back and re-shows it = the "second home" flash; predictive back plays
    // its back-to-home animation). An always-on, do-nothing callback swallows it. The Drawer's own handler is
    // composed later, so it wins while the drawer is open.
    BackHandler(enabled = !drawerOpen.value) { }
    var status by remember { mutableStateOf(homeStatus()) }
    var extras by remember { mutableStateOf(HomeExtras()) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        // Restarts on every return to Home (also after the calendar permission dialog).
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                status = homeStatus()
                extras = withContext(Dispatchers.IO) { loadExtras(ctx) }   // pie + calendar: slower, off the main thread
                delay(30_000)
            }
        }
    }
    // Opening search = the whole screen gets pushed up: home glides up + dims away while the app list rises from the bottom.
    val rise = CubicBezierEasing(0.2f, 0f, 0f, 1f)      // fast start, soft landing
    val sink = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f) // gentle start, quick exit
    val p by androidx.compose.animation.core.animateFloatAsState(
        if (drawerOpen.value) 1f else 0f,
        if (drawerOpen.value) tween(420, easing = rise) else tween(300, easing = rise),
        label = "drawer",
    )
    Box(Modifier.fillMaxSize().background(H.Bg)) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationY = -size.height * 0.22f * p
            alpha = 1f - p
            val s = 1f - 0.04f * p; scaleX = s; scaleY = s
        }) { Home(status, extras, onSwipeUp = { drawerOpen.value = true }) }
        AnimatedVisibility(
            drawerOpen.value,
            enter = slideInVertically(tween(420, easing = rise)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(300, easing = sink)) { it } + fadeOut(tween(180, 100)),
        ) { Drawer(open = drawerOpen.value, onClose = { drawerOpen.value = false }) }
    }
}

@Composable
private fun Home(s: HomeStatus, x: HomeExtras, onSwipeUp: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val storeNow by Store.map.collectAsState()   // observe settings so pins update live
    val pinned = remember(storeNow, Apps.version.intValue) {
        // Only installed, unhidden pins take a slot (uninstalled ones would show as a raw package name).
        val installed = Apps.all(ctx).mapTo(HashSet()) { it.pkg }; val hidden = Apps.hidden()
        Store.list("homeApps").filter { it in installed && it !in hidden && !Cats.gatedAtLaunch(ctx, it) }.take(6).map { AppEntry(it, Apps.label(ctx, it)) }
    }
    Box(
        // Ignore keyboard space so home doesn't squish behind the drawer and spring back on close.
        Modifier.fillMaxSize().consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.ime).safeDrawingPadding()
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total < -120) onSwipeUp() }) { _, dy -> total += dy }
            }
            .pointerInput(Unit) { detectTapGestures(onLongPress = { ctx.startActivity(newTask(Intent(ctx, SettingsActivity::class.java))) }) },
    ) {
        // Clean card layout: date header, screen time, today's calendar, pinned apps, search pill at the bottom.
        fun open(cls: Class<*>, page: String? = null) =
            ctx.startActivity(newTask(Intent(ctx, cls).apply { page?.let { putExtra("page", it) } }))
        androidx.compose.runtime.CompositionLocalProvider(LocalHomeLongPress provides { open(SettingsActivity::class.java) }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Store card button (top right): reacts the moment a store geofence fires, not on the 30 s refresh.
            val storeMap by Store.map.collectAsState()
            val cardId = storeMap["inStoreCard"]?.toLongOrNull()
            val cardName = remember(cardId) { cardId?.let { App.db.dao().card(it)?.name } }
            // Top part takes what's left after the apps + search; the calendar shrinks to fit, never pushing search off screen.
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeHeader(x.date, cardName) { cardId?.let { lt.stillhome.cards.CardOpen.open(ctx, it) } }
            // Screen time from Android's usage events (what Digital Wellbeing counts); own count until the first load.
            val used = x.usedMs ?: s.usedMs
            val pie = x.pie
            ScreenTimeCard(used, s.goalMs, used > s.goalMs, pie, s.budgets, s.focus) { UsageActivity.open(ctx, "screen") }
            CalendarCard(x, Modifier.weight(1f, fill = false))   // tap = Google Calendar; drag = scroll the day right here
            }
            AppsCard(pinned, showPlus = pinned.size < 6, showFocus = Store.bool("focusShortcut", false) && !Guard.focusActive(),
                onApp = { ctx.openApp(it) }, onEdit = { open(SettingsActivity::class.java, "home") },
                onFocus = { open(SettingsActivity::class.java, "focus") })
            // Status pills: digest, blocker state (the store card lives top right now).
            // Only there when it has a pill, so the gaps between cards stay even (12 dp each).
            val warn = !s.paused && s.guardOff
            if (warn || s.newCount > 0) {
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (warn) Pill("Blocker can't run · tap to fix", H.WarnSoft, H.Warn) { open(SettingsActivity::class.java, "permissions") }
                    if (s.newCount > 0) Pill("${s.newCount} new", H.Card, H.Ink) { open(DigestActivity::class.java) }
                }
            }
            SearchPill(onSwipeUp)
        }
        }
    }
}

@Composable
private fun Drawer(open: Boolean, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var filter by remember { mutableStateOf("All") }
    var menu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Re-read after installs/uninstalls (Apps.version bumps) and on every return here (uninstall dialog, Settings).
    val owner = LocalLifecycleOwner.current
    // (Skips the first resume so opening the drawer never re-scans the app list mid-animation.)
    LaunchedEffect(Unit) { var first = true; owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { if (first) first = false else Apps.invalidate() } }
    val all = remember(Apps.version.intValue) { Apps.all(ctx) }
    val storeMap by lt.stillhome.data.Store.map.collectAsState()
    val hidden = storeMap.let { Apps.hidden() }   // reading the map = recompose on change
    var sheet by remember { mutableStateOf<AppEntry?>(null) }
    sheet?.let { AppSheet(it) { sheet = null } }
    val apps = all.filter { a ->
        a.pkg !in hidden && (filter == "All" || Cats.of(a.pkg) == filter) && (query.isEmpty() || a.label.contains(query, ignoreCase = true))
    }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // Every close path drops the keyboard first so it never lingers.
    val close = { kb?.hide(); focusManager.clearFocus(); onClose() }
    var lastLen by remember { mutableIntStateOf(0) }

    BackHandler { if (query.isNotEmpty()) query = "" else close() }
    // Drawer opens straight into search: fresh query, focus the field, pull up the keyboard.
    // Keyed on `open` so a quick reopen during the exit animation still refocuses; Home button close also hides the keyboard.
    LaunchedEffect(open) {
        // Keyboard is asked for at once so it rises together with the drawer (one frame first so the field is attached).
        if (open) { query = ""; lastLen = 0; androidx.compose.runtime.withFrameNanos { }; focus.requestFocus(); kb?.show() }
        else { kb?.hide(); focusManager.clearFocus() }
    }
    // Single match while typing = open it right away (only when adding letters, never while deleting).
    LaunchedEffect(query) {
        val grew = query.length > lastLen
        lastLen = query.length
        if (open && grew && query.isNotEmpty() && apps.size == 1) { close(); ctx.openApp(apps[0].pkg) }
        else list.scrollToItem(0)
    }
    // Pull down while already at the top = back to home.
    val pullDown = remember {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0) { pulled += available.y; if (pulled > 180) { pulled = 0f; close() } } else pulled = 0f
                return Offset.Zero
            }
        }
    }

    // Empty pointerInput = drawer swallows every touch so the hidden home underneath can't be tapped.
    Box(Modifier.fillMaxSize().background(Black).pointerInput(Unit) {}.safeDrawingPadding().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().padding(start = 40.dp, end = 24.dp, top = 16.dp, bottom = 8.dp)
                .pointerInput(Unit) { var t = 0f; detectVerticalDragGestures(onDragStart = { t = 0f }, onDragEnd = { if (t > 100) close() }) { _, dy -> t += dy } }) {
                Box(Modifier.align(Alignment.CenterStart)) {
                    T(if (filter == "All") "Apps" else filter, 18.sp, White, Modifier.clickable { menu = true })
                    DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Black,
                        modifier = Modifier.border(1.dp, Faint)) {
                        (listOf("All") + Cats.all()).forEach { c ->
                            DropdownMenuItem(text = { T(c, 16.sp, if (c == filter) White else Dim) }, onClick = { filter = c; menu = false })
                        }
                    }
                }
                T("Settings", 18.sp, White, Modifier.align(Alignment.CenterEnd).clickable { ctx.startActivity(newTask(Intent(ctx, SettingsActivity::class.java))) })
            }
            // Full-width pill search bar at the top, always there.
            BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(White, 18.sp), cursorBrush = SolidColor(White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { apps.firstOrNull()?.let { close(); ctx.openApp(it.pkg) } }),
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp).focusRequester(focus),
                decorationBox = { inner ->
                    Row(
                        Modifier.fillMaxWidth().height(50.dp).border(1.5.dp, Faint, CircleShape).padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                            val r = size.minDimension * 0.33f
                            drawCircle(Dim, r, Offset(r + 2f, r + 2f), style = Stroke(1.5.dp.toPx()))
                            drawLine(Dim, Offset(r * 1.75f + 2f, r * 1.75f + 2f), Offset(size.width - 2f, size.height - 2f), 1.5.dp.toPx())
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f)) { if (query.isEmpty()) T("search", 18.sp, Faint); inner() }
                    }
                })
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().nestedScroll(pullDown).drawWithContent {
                    drawContent()
                    // thin scroll indicator on the right
                    val total = list.layoutInfo.totalItemsCount
                    val visible = list.layoutInfo.visibleItemsInfo.size
                    if (total > visible && total > 0) {
                        val h = size.height * visible / total
                        val y = (size.height - h) * list.firstVisibleItemIndex / (total - visible).coerceAtLeast(1)
                        drawRect(Dim, Offset(size.width - 6.dp.toPx(), y), Size(2.dp.toPx(), h))
                    }
                },
            ) {
                items(apps, key = { it.pkg }) { a ->
                    Box(Modifier.fillMaxWidth().height(56.dp).tapOrHold(onHold = { kb?.hide(); sheet = a }) { close(); ctx.openApp(a.pkg) }.padding(start = 40.dp),
                        contentAlignment = Alignment.CenterStart) { T(a.label, 17.sp, Bright) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
