package lt.stillhome.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.delay
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

/** What the home screen's bottom line shows. */
data class HomeStatus(val line: String, val newCount: Int, val overTarget: Boolean, val card: String?, val guardOff: Boolean)

fun homeStatus(): HomeStatus {
    val dao = App.db.dao()
    val todayMs = (dao.screenSince(Clock.day()).sumOf { it.seconds }) * 1000
    val target = Store.int("targetMin", 120)
    val parts = mutableListOf("${fmtDur(todayMs)} today", "target ${fmtDur(target * 60_000L)}")
    val short = mapOf(Cats.SOCIAL to "social", Cats.VIDEO to "yt", Cats.GAMES to "games", Cats.SHOPS to "shops")
    for (c in Cats.budgeted) {
        val used = Guard.usedMin(c)
        if (c == Cats.SOCIAL || c == Cats.VIDEO || used > 0) parts += "${short[c]} $used/${Limits.budget(c)}"
    }
    if (Guard.focusActive()) parts.add(0, "focus until ${lt.stillhome.core.fmtHm(Clock.minuteOfDay(Guard.focusUntil()))}")
    val n = dao.unseenDigest()
    val card = Store.get("inStoreCard")?.toLongOrNull()?.let { dao.card(it)?.name }
    return HomeStatus(parts.joinToString(" · "), n, todayMs > target * 60_000L, card, !GuardA11yService.running())
}

class MainActivity : BlackActivity() {
    companion object { const val EXTRA_NEXT = "next" }
    private var drawerOpen = mutableStateOf(false)
    private var next: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        next = intent.getParcelableExtra(EXTRA_NEXT, Intent::class.java)
        setContent { Launcher(drawerOpen) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        drawerOpen.value = false   // Home button always lands on the home page
        next = intent.getParcelableExtra(EXTRA_NEXT, Intent::class.java)
    }

    override fun onResume() {
        super.onResume()
        // Sent here by the guard: we are Home now, so open the Gate / Lock / Time's up on top.
        next?.let { next = null; intent.removeExtra(EXTRA_NEXT); startActivity(newTask(it)); return }
        GuardService.start(this)
        // Things that must be dealt with before using the phone:
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
    if (Cats.isGated(this, pkg)) {
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
    var status by remember { mutableStateOf(homeStatus()) }
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { status = homeStatus(); tick++; delay(30_000) }
        }
    }
    Box(Modifier.fillMaxSize().background(Black)) {
        Home(status, onSwipeUp = { drawerOpen.value = true })
        AnimatedVisibility(
            drawerOpen.value,
            enter = slideInVertically(tween(140)) { it / 6 } + fadeIn(tween(120)),
            exit = slideOutVertically(tween(120)) { it / 6 } + fadeOut(tween(100)),
        ) { Drawer(onClose = { drawerOpen.value = false }) }
    }
}

@Composable
private fun Home(s: HomeStatus, onSwipeUp: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pinned = remember(Store.map.value) {
        Store.list("homeApps").filter { !Cats.isGated(ctx, it) }.take(6).map { AppEntry(it, Apps.label(ctx, it)) }
    }
    Box(
        Modifier.fillMaxSize().safeDrawingPadding()
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total < -120) onSwipeUp() }) { _, dy -> total += dy }
            }
            .pointerInput(Unit) { detectTapGestures(onLongPress = { ctx.startActivity(newTask(Intent(ctx, SettingsActivity::class.java))) }) },
    ) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            pinned.forEach { a -> T(a.label, 36.sp, Bright, Modifier.clickable { ctx.openApp(a.pkg) }) }
            if (Store.bool("focusShortcut", false) && !Guard.focusActive())
                T("focus", 16.sp, Dim, Modifier.padding(top = 10.dp).clickable { ctx.startActivity(newTask(Intent(ctx, SettingsActivity::class.java).putExtra("page", "focus"))) })
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.guardOff) T("guard off", 14.sp, Red, Modifier.clickable { ctx.startActivity(newTask(Intent(ctx, SettingsActivity::class.java).putExtra("page", "permissions"))) })
            s.card?.let { c -> T("$c card", 14.sp, Dim, Modifier.clickable {
                ctx.startActivity(newTask(Intent(ctx, BarcodeActivity::class.java).putExtra("card", Store.long("inStoreCard", 0)))) }) }
            Row {
                T(s.line, 13.sp, if (s.overTarget) White else Dim)
                if (s.newCount > 0) T(" · ${s.newCount} new", 13.sp, if (s.overTarget) White else Dim,
                    Modifier.clickable { ctx.startActivity(newTask(Intent(ctx, DigestActivity::class.java))) })
            }
        }
    }
}

@Composable
private fun Drawer(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var filter by remember { mutableStateOf("All") }
    var menu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val all = remember { Apps.all(ctx) }
    val apps = all.filter { a ->
        (filter == "All" || Cats.of(a.pkg) == filter) && (query.isEmpty() || a.label.contains(query, ignoreCase = true))
    }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current

    BackHandler { if (searching) { searching = false; query = "" } else onClose() }
    LaunchedEffect(searching) { if (searching) { focus.requestFocus(); kb?.show() } }
    // Single match while searching = open it right away.
    LaunchedEffect(query) {
        if (searching && query.isNotEmpty() && apps.size == 1) { kb?.hide(); onClose(); ctx.openApp(apps[0].pkg) }
    }
    // Pull down while already at the top = back to home.
    val pullDown = remember {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0) { pulled += available.y; if (pulled > 180) { pulled = 0f; onClose() } } else pulled = 0f
                return Offset.Zero
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Black).safeDrawingPadding().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().padding(start = 40.dp, end = 24.dp, top = 16.dp, bottom = 8.dp)
                .pointerInput(Unit) { var t = 0f; detectVerticalDragGestures(onDragStart = { t = 0f }, onDragEnd = { if (t > 100) onClose() }) { _, dy -> t += dy } }) {
                Box(Modifier.align(Alignment.CenterStart)) {
                    T(if (filter == "All") "Apps ▾" else "$filter ▾", 18.sp, White, Modifier.clickable { menu = true })
                    DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Black,
                        modifier = Modifier.border(1.dp, Faint)) {
                        (listOf("All") + Cats.all()).forEach { c ->
                            DropdownMenuItem(text = { T(c, 16.sp, if (c == filter) White else Dim) }, onClick = { filter = c; menu = false })
                        }
                    }
                }
                T("Settings", 18.sp, White, Modifier.align(Alignment.CenterEnd).clickable { ctx.startActivity(newTask(Intent(ctx, SettingsActivity::class.java))) })
            }
            if (searching) {
                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(White, 20.sp), cursorBrush = SolidColor(White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { apps.firstOrNull()?.let { onClose(); ctx.openApp(it.pkg) } }),
                    modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = 24.dp, top = 8.dp, bottom = 8.dp).focusRequester(focus),
                    decorationBox = { inner -> Box { if (query.isEmpty()) T("search", 20.sp, Faint); inner() } })
            }
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
                    Box(Modifier.fillMaxWidth().height(56.dp).clickable { onClose(); ctx.openApp(a.pkg) }.padding(start = 40.dp),
                        contentAlignment = Alignment.CenterStart) { T(a.label, 17.sp, Bright) }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
        // Round outlined search button, bottom-right.
        if (!searching) Box(
            Modifier.align(Alignment.BottomEnd).padding(24.dp).size(56.dp).border(1.5.dp, White, CircleShape)
                .clickable { searching = true },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                val r = size.minDimension * 0.33f
                drawCircle(White, r, Offset(r + 2f, r + 2f), style = Stroke(1.5.dp.toPx()))
                drawLine(White, Offset(r * 1.75f + 2f, r * 1.75f + 2f), Offset(size.width - 2f, size.height - 2f), 1.5.dp.toPx())
            }
        }
    }
}
