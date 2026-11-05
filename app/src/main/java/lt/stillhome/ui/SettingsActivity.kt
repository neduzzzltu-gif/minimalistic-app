package lt.stillhome.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import lt.stillhome.App
import lt.stillhome.R
import lt.stillhome.cards.Barcodes
import lt.stillhome.cards.CardOpen
import lt.stillhome.cards.Geo
import lt.stillhome.core.Apps
import lt.stillhome.core.Cats
import lt.stillhome.core.Clock
import lt.stillhome.core.Gray
import lt.stillhome.core.Group
import lt.stillhome.core.Groups
import lt.stillhome.core.Guard
import lt.stillhome.core.Limits
import lt.stillhome.core.Rules
import lt.stillhome.core.Window
import lt.stillhome.core.fmtDur
import lt.stillhome.core.fmtHm
import lt.stillhome.core.fmtMmSs
import lt.stillhome.data.CardRow
import lt.stillhome.data.PlaceRow
import lt.stillhome.data.Store
import lt.stillhome.detect.Detectors
import lt.stillhome.notif.DIGEST
import lt.stillhome.notif.DROP
import lt.stillhome.notif.Notifs
import lt.stillhome.notif.PING
import lt.stillhome.service.GuardA11yService
import lt.stillhome.service.GuardService

/**
 * Settings: a tiny page stack instead of a navigation library. Every rule edit goes through the 1 h waiting rule.
 * Look = the home screen's: black page, dark rounded cards, white text, grey sub-text, red only for warnings.
 * Main page = the one pending change on top, then six groups (Gates, Daily essay, Screen time, Home & apps,
 * Permissions, About); every row shows its current value in grey.
 */
/** Store keys written in the background or by the checklist: never a reason to rebuild a Settings page. */
private val housekeeping = listOf("clock.", "pass.", "broken.", "digest.last.", "sf.cache.", "cardWallet.", "inStore", "perm.",
    "pendingNotified.", "pendingEl.", "pendingBoot.", "intentCheck", "review.", "phase", "st.", "settingsPass",
    "grantPass", "uninstallPass")

class SettingsActivity : BlackActivity() {
    private val stack = mutableStateListOf("main")
    private var scanned by mutableStateOf<Pair<String, String>?>(null)   // number, format
    private var focusMin by mutableIntStateOf(25)   // focus slider, kept here so page rebuilds don't reset it
    private var focusStrict by mutableStateOf(false)
    private var mainQ by mutableStateOf("")           // settings search + folds: survive page rebuilds after a toggle
    private var openBlocked by mutableStateOf(true)
    private var openTimes by mutableStateOf(true)
    private var bump by mutableIntStateOf(0)        // rule() may only queue a change (1 h rule): rebuild anyway
    private val scan = registerForActivityResult(ScanContract()) { r ->
        if (r.contents != null) scanned = r.contents to (r.formatName ?: Barcodes.guess(r.contents))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("page")?.let { stack.add(it) }
        setContent {
            val map by Store.map.collectAsState()
            BackHandler { back() }
            // key(map): pages read Store directly, so rebuild the page whenever a setting changes. Scroll position
            // and typed text live in the activity (scrollFor / keep), so a rebuild doesn't lose them.
            // Housekeeping keys (clock, services, reminders, checklist marks, settings pass) are left out,
            // or a slider would be rebuilt mid-drag and its change lost.
            val settings = map.filterKeys { k -> k != "tighten.last" && housekeeping.none { k.startsWith(it) } }
            val top = stack.last()
            // The group editor keeps an unsaved draft: never rebuild it when other settings change.
            if (top.startsWith("group:")) androidx.compose.runtime.key(top, stack.size) { GroupPage(top.removePrefix("group:")) }
            else androidx.compose.runtime.key(settings, bump) { when (val p = top) {
                // Old routes from other screens map onto the new pages ("pending" = the banner on Main).
                "main", "pending" -> Main()
                "goal", "time", "stats" -> GoalPage()
                "focus" -> FocusPage()
                "gates", "limits" -> GatesPage()
                "groups" -> GroupsPage()
                "essays" -> Sub("Essay history") { EssayHistory() }
                "hidden" -> Sub("Hidden apps") { HiddenAppsPage() }
                "labels" -> LabelsPage()
                "modes" -> ModesPage()
                "domains" -> DomainsPage()
                "notifs" -> NotifAppsPage()
                "digest" -> DigestPage()
                "home" -> PickApps("Home apps", "Shown on home. Gated apps can't be pinned.", "homeApps", 6)
                "quick" -> PickApps("Lock-screen apps", "Shown on the lock screen. Gated apps can't be pinned.", "quickApps", 3)
                "cards" -> CardsPage()
                "lockdown" -> LockdownPage()
                "permissions" -> Sub("Permissions") { Permissions(this@SettingsActivity) }
                else -> if (p.startsWith("notif:")) ChannelsPage(p.removePrefix("notif:")) else Main()
            } }
        }
    }

    private fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish() }
    /** A newly opened page starts at the top with empty fields; back keeps where you were. */
    private fun go(p: String) { scrolls.remove(p); pageState.clear(); stack.add(p) }

    // Survive page rebuilds (key(settings) below): one scroll position per route + page fields.
    private val scrolls = HashMap<String, ScrollState>()
    private fun scrollFor(route: String) = scrolls.getOrPut(route) { ScrollState(0) }
    private val pageState = HashMap<String, MutableState<*>>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> keep(k: String, init: T) = pageState.getOrPut(k) { mutableStateOf(init) } as MutableState<T>
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun rule(key: String, value: String?, desc: String) { toast(ruleChange(key, value, desc)); bump++ }

    /** Sub-page frame: back arrow + title, then the cards. */
    @Composable private fun Sub(title: String, content: @Composable ColumnScope.() -> Unit) = Page(state = scrollFor(stack.last())) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(SetCard).clickable(role = Role.Button) { back() }, contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.si_chevron_right), "Back", Modifier.size(22.dp).rotate(180f), tint = White)
            }
            Spacer(Modifier.width(14.dp))
            T(title, 22.sp, White, Modifier.weight(1f), bold = true)
        }
        content()
        Spacer(Modifier.height(16.dp))
    }

    // ------------------------------------------------------------------ main

    /** Main page: search, the pending change, warnings, then the six groups with each row's current value. */
    @Composable private fun Main() = Page(state = scrollFor("main")) {
        val ctx = this@SettingsActivity
        val q = mainQ
        // Phone-settings layout: centered title, borderless rounded groups, value on the right of each row.
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), contentAlignment = Alignment.Center) { T("Settings", 22.sp, White) }
        SearchField(q, { mainQ = it }, "Search in settings")
        Spacer(Modifier.height(8.dp))
        PendingBanner { toast(it); bump++ }
        if (!GuardA11yService.running()) SetGroup {
            NavRow(R.drawable.si_error_outline, "Blocker can't run: turn on Accessibility", color = Red) { go("permissions") }
        }
        val broken = (Detectors.withDetector + Detectors.YT).filter { Store.long("broken.$it", 0) > Clock.now() - 86400_000L }
        if (broken.isNotEmpty()) SetGroup { broken.forEach { pkg ->
            NavRow(R.drawable.si_error_outline, "Detector broken: ${Apps.label(ctx, pkg)}", color = Red) { go("modes") } } }

        val cards by remember { App.db.dao().cardsFlow() }.collectAsState(emptyList())
        val essays by remember { App.db.dao().essaysFlow() }.collectAsState(emptyList())
        val paused = Guard.paused()
        val hiddenSet = Store.list("hiddenApps").toSet()
        val groupsOn = Groups.all().count { it.on }
        val withDet = Detectors.withDetector.filter { packageManager.getLaunchIntentForPackage(it) != null }
        val sites = Detectors.domains().size
        val today = androidx.compose.runtime.produceState<Long?>(null) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { lt.stillhome.core.ScreenTime.compute(ctx, Clock.date())?.total } catch (_: Exception) { null } }
        }.value
        val ring = Store.list("whitelistCats").ifEmpty { listOf(Cats.CHAT, Cats.FINANCE, Cats.SCHOOL) }
        val digest = Store.list("digestTimes").ifEmpty { listOf("1200") }.mapNotNull { it.toIntOrNull() }.sorted()
        val (permDone, permAll) = remember { permsDone(ctx) }
        val version = remember { try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" } }
        fun n(k: Int, one: String) = if (k == 1) "1 $one" else "$k ${one}s"
        val sess = Cats.budgeted.map { Limits.sessionMax(it) }

        val groups = listOf(
            "Limits" to listOf(
                Nav(R.drawable.si_schedule, "Limit rules", when { sess.isEmpty() -> ""; sess.min() == sess.max() -> "${sess.min()}m sessions"
                    else -> "${sess.min()}-${sess.max()}m sessions" }, find = "gate gates longest session countdown shortest reason") { go("gates") },
                Nav(R.drawable.si_block, "App groups", if (groupsOn > 0) "$groupsOn on" else "None on") { go("groups") },
                Nav(R.drawable.si_label, "Apps & labels", find = "category of each app") { go("labels") },
                Nav(R.drawable.si_speaker_notes_off, "Chats-free & grayscale",
                    "${withDet.count { Detectors.chatsFree(it) }} of ${withDet.size} apps", find = "chats free") { go("modes") },
                Nav(R.drawable.si_language, "Chrome sites", n(sites, "site")) { go("domains") },
                Nav(R.drawable.si_shield, "Lockdown", lockdownState()) { go("lockdown") },
                Nav(R.drawable.si_power_settings_new, if (paused) "App blocker is off, turn it on" else "Turn app blocker off",
                    if (paused) "Off" else "On", warn = paused) { startActivity(android.content.Intent(ctx, SwitchActivity::class.java)) },
            ),
            "Daily essay" to listOf(
                Nav(0, "Daily essay", find = "200 words before home", content = { Box(Modifier.padding(horizontal = 16.dp)) { EssayToggle { toast(it); bump++ } } }) {},
                Nav(R.drawable.si_pending_actions, "Essay history", n(essays.size, "essay")) { go("essays") },
            ),
            "Screen time" to listOf(
                Nav(R.drawable.si_bar_chart, "Open screen time", today?.let { "Today ${fmtDur(it)}" } ?: "", find = "per app usage") {
                    startActivity(android.content.Intent(ctx, UsageActivity::class.java)) },
                Nav(R.drawable.si_hourglass_empty, "Daily goal", fmtDur(Store.int("targetMin", 120) * 60_000L), find = "last 14 days") { go("goal") },
                Nav(R.drawable.si_timer, "Focus mode", if (Guard.focusActive()) "Until ${fmtHm(Clock.minuteOfDay(Guard.focusUntil()))}" else "Off") { go("focus") },
            ),
            "Home & apps" to listOf(
                Nav(R.drawable.si_home, "Home apps", "${Store.list("homeApps").count { it !in hiddenSet }} pinned") { go("home") },
                Nav(R.drawable.si_lock, "Lock-screen apps", "${Store.list("quickApps").count { it !in hiddenSet }} pinned") { go("quick") },
                Nav(R.drawable.si_label, "Hidden apps", "${hiddenSet.size}") { go("hidden") },
                Nav(R.drawable.si_credit_card, "Cards", n(cards.size, "card")) { go("cards") },
                Nav(R.drawable.si_notifications, "Notifications", "${ring.size} ring", find = ring.joinToString(" ")) { go("notifs") },
                Nav(R.drawable.si_inbox, "Digest times", digest.joinToString(", ") { fmtHm(it) }) { go("digest") },
            ),
            "Permissions" to listOf(
                Nav(R.drawable.si_admin_panel_settings, "Permissions", "$permDone of $permAll done") { go("permissions") },
            ),
            "About" to listOf(
                Nav(R.drawable.si_error_outline, "Version", version, chevron = false) {},
                Nav(R.drawable.si_quiz, "Retake quiz", find = "set your limits again") {
                    startActivity(android.content.Intent(ctx, OnboardingActivity::class.java).putExtra("page", "quiz")) },
            ),
        )
        fun match(vararg s: String) = q.isBlank() || s.any { it.contains(q.trim(), ignoreCase = true) }
        for ((title, list) in groups) {
            val shown = list.filter { match(title, it.label, it.sub, it.find) }
            if (shown.isEmpty()) continue
            Spacer(Modifier.height(12.dp))
            SetGroup { shown.forEach { r ->
                r.content?.invoke() ?: NavRow(r.icon, r.label, sub = r.sub, color = if (r.warn) Red else White, chevron = r.chevron,
                    onClick = r.onClick.takeIf { r.chevron }, tall = true)
            } }
        }
        Spacer(Modifier.height(16.dp))
    }

    private fun lockdownState(): String {
        val at = Store.long("lockdownAt", 0)
        return when { Guard.lockdownArmed() -> "Armed"; Store.str("lockdown", "1") == "0" -> "Off"
            at > 0 -> "Arms ${Clock.day(at)} ${fmtHm(Clock.minuteOfDay(at))}"; else -> "Not scheduled" }
    }

    // ------------------------------------------------------------------ screen time

    /** Today, the daily goal and the last 14 days (old routes "time" and "stats" land here). */
    @Composable private fun GoalPage() = Sub("Daily goal") {
        val ctx = this@SettingsActivity
        val goalMin = Store.int("targetMin", 120)
        val use = androidx.compose.runtime.produceState<lt.stillhome.core.DayUse?>(null) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { lt.stillhome.core.ScreenTime.compute(ctx, Clock.date()) } catch (_: Exception) { null } }
        }.value
        val usedMs = use?.total ?: (App.db.dao().screenSince(Clock.day()).sumOf { it.seconds } * 1000)
        val over = usedMs > goalMin * 60_000L
        Label("Today")
        SetGroup {
            Inner {
                Row(verticalAlignment = Alignment.Bottom) {
                    T(fmtDur(usedMs), 30.sp, if (over) Red else White, bold = true)
                    Spacer(Modifier.width(8.dp))
                    T("of ${fmtDur(goalMin * 60_000L)}", 15.sp, Dim, Modifier.padding(bottom = 5.dp))
                }
                T(if (use != null) "From Android usage data" else "Own count", 13.sp, Dim)
            }
            use?.apps?.take(3)?.forEach { a -> SetDivider0(); ValueRow(Apps.label(ctx, a.pkg), fmtDur(a.ms)) }
        }

        Label("Daily goal")
        SetGroup { Inner {
            TimeSlider((2..24).map { it * 15 }, goalMin, onChange = {}) {   // 30 min to 6 h
                if (it != goalMin) rule("targetMin", "$it", "Goal → ${fmtDur(it * 60_000L)}") }
            pendingMark("targetMin").takeIf { it.isNotEmpty() }?.let { T(it, 13.sp, Dim) }
        } }

        Label("Last 14 days")
        val days = remember { App.db.dao().screenSince(Clock.daysAgo(13)).reversed() }
        SetGroup {
            if (days.isEmpty()) Inner { T("No history yet.", 14.sp, Dim) }
            days.forEachIndexed { i, s -> if (i > 0) SetDivider0(); ValueRow(s.day, fmtDur(s.seconds * 1000), color = if (s.seconds * 1000 > goalMin * 60_000L) Red else Dim) }
        }
    }

    @Composable private fun FocusPage() = Sub("Focus mode") {
        val ctx = this@SettingsActivity
        var now by remember { mutableLongStateOf(Clock.now()) }
        LaunchedEffect(Unit) { while (true) { delay(1000); now = Clock.now() } }
        SetGroup {
            if (Guard.focusActive(now)) Inner {
                T("${fmtMmSs(Guard.focusUntil() - now)} left", 30.sp, White, bold = true)
                if (Guard.focusStrict(now)) T("Whole phone locked. Can't end early.", 13.sp, Dim)
                else MainBtn("End focus mode now", primary = false) {
                    Guard.endFocus(); now = Clock.now()
                    GuardService.refresh(ctx); GuardA11yService.instance?.recheck()
                }
            } else {
                Inner {
                    T("Locks blocked apps. Calls and alarms still ring.", 13.sp, Dim)
                    TimeSlider((1..36).map { it * 5 }, focusMin, onChange = { focusMin = it })   // 5 min to 3 h
                }
                SetDivider0()
                Box(Modifier.padding(horizontal = 16.dp)) {
                    ToggleRow("Lock the whole phone", sub = if (focusStrict) "Can't end early" else "You can end early",
                        on = focusStrict, onToggle = { focusStrict = !focusStrict })
                }
                Inner { MainBtn("Start focus mode") {
                    Guard.startFocus(focusMin, focusStrict)
                    GuardService.refresh(ctx); GuardA11yService.instance?.recheck()
                } }
            }
            SetDivider0()
            Box(Modifier.padding(horizontal = 16.dp)) {
                val sc = Store.bool("focusShortcut", false)
                ToggleRow("Show “Focus mode” on home", on = sc, onToggle = { Store.set("focusShortcut", if (sc) "0" else "1") })
            }
        }
    }

    // ------------------------------------------------------------------ gates

    /** Longest session per category, countdown, shortest reason (old route "limits"). */
    @Composable private fun GatesPage() = Sub("Limit rules") {
        T("What a gated app asks before it opens.", 13.sp, Dim)
        Label("Longest session")
        SetGroup {
            Cats.budgeted.forEachIndexed { i, cat ->
                if (i > 0) SetDivider0()
                Inner {
                    T(cat, 15.sp, White)
                    TimeSlider(Limits.of(cat).sessions, Limits.sessionMax(cat), onChange = {}) {
                        if (it != Limits.sessionMax(cat)) rule("sessionMax.$cat", "$it", "$cat longest session ${Limits.sessionMax(cat)} → $it min") }
                    pendingMark("sessionMax.$cat").takeIf { it.isNotEmpty() }?.let { T(it, 13.sp, Dim) }
                }
            }
        }
        Label("Before you can type")
        SetGroup {
            Inner {
                T("Countdown", 15.sp, White)
                Chips(listOf("10 s", "20 s"), setOf("${Store.str("countdown", "10")} s")) { val v = it.removeSuffix(" s"); rule("countdown", v, "Gate countdown → $v s") }
                pendingMark("countdown").takeIf { it.isNotEmpty() }?.let { T(it, 13.sp, Dim) }
            }
            SetDivider0()
            Inner {
                T("Shortest reason", 15.sp, White)
                Chips(listOf("20 letters", "30 letters"), setOf("${Store.str("reasonMin", "20")} letters")) {
                    val v = it.removeSuffix(" letters"); rule("reasonMin", v, "Reason minimum → $v chars") }
                pendingMark("reasonMin").takeIf { it.isNotEmpty() }?.let { T(it, 13.sp, Dim) }
            }
        }
    }

    /** All app groups: always blocked / at set times, with a New button. */
    @Composable private fun GroupsPage() = Sub("App groups") {
        val ctx = this@SettingsActivity
        val groups = Groups.all()
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Block apps always or at set times.", 13.sp, Dim, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Row(Modifier.clip(CircleShape).background(SetCard).clickable(role = Role.Button) { go("group:new") }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.si_add), null, Modifier.size(16.dp), tint = White)
                Spacer(Modifier.width(4.dp)); T("New", 14.sp, White)
            }
        }
        Spacer(Modifier.height(6.dp))
        @Composable fun GroupRow(g: Group) = Box(Modifier.padding(horizontal = 16.dp)) {
            ToggleRow(g.name, g.summary(ctx), listOf(if (g.always) "" else g.schedule(), pendingMark(Groups.key(g.id))).filter { it.isNotEmpty() }.joinToString(" · "), g.on,
                onToggle = { rule(Groups.key(g.id), g.copy(on = !g.on).encode(), "${g.name} ${if (g.on) "off" else "on"}") },
                onClick = { go("group:${g.id}") })
        }
        val always = groups.filter { it.always }
        val timed = groups.filter { !it.always }
        SetGroup {
            NavRow(R.drawable.si_block, "Always blocked", "${always.size}", open = openBlocked) { openBlocked = !openBlocked }
            if (openBlocked) { if (always.isEmpty()) Hint("No always-blocked groups"); always.forEach { GroupRow(it) } }
            SetDivider()
            NavRow(R.drawable.si_schedule, "Blocked at certain times", "${timed.size}", open = openTimes) { openTimes = !openTimes }
            if (openTimes) { if (timed.isEmpty()) Hint("Tap “New” to block apps at set times"); timed.forEach { GroupRow(it) } }
        }
    }

    // ------------------------------------------------------------------ app groups

    /** Make or edit one app group: name, always / at times, its time periods, and its apps. Saved with one tap. */
    @Composable private fun GroupPage(idArg: String) {
        val ctx = this@SettingsActivity
        val id = remember { if (idArg == "new") Groups.newId() else idArg }
        val orig = remember { Groups.get(id) }
        var name by remember { mutableStateOf(orig?.name ?: "") }
        var always by remember { mutableStateOf(orig?.always ?: false) }
        var members by remember { mutableStateOf(orig?.members ?: emptyList()) }
        var windows by remember { mutableStateOf(orig?.windows ?: emptyList()) }
        var q by remember { mutableStateOf("") }
        var days by remember { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
        var start by remember { mutableIntStateOf(8 * 60) }
        var end by remember { mutableIntStateOf(15 * 60) }
        Sub(if (orig == null) "New group" else orig.name) {
            pendingMark(Groups.key(id)).takeIf { it.isNotEmpty() }?.let { T(it, 13.sp, Dim) }
            Label("Name")
            Field(name, { name = it }, "e.g. socials", singleLine = true)

            Label("When")
            SetGroup {
                Inner { Chips(listOf("At certain times", "Always blocked"), setOf(if (always) "Always blocked" else "At certain times")) { always = it == "Always blocked" } }
                if (!always) {
                    windows.forEachIndexed { i, w -> SetDivider0(); ValueRow(w.label(), "Remove") { windows = windows.filterIndexed { j, _ -> j != i } } }
                    SetDivider0()
                    Inner {
                        T("Add a time", 15.sp, White)
                        val names = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
                        Chips(names, days.map { names[it - 1] }.toSet()) { n -> val d = names.indexOf(n) + 1; days = if (d in days) days - d else days + d }
                        Stepper("From", start) { start = it }
                        Stepper("To", end) { end = it }
                        MainBtn("Add ${Window(days, start, end).label()}", primary = false, enabled = days.isNotEmpty() && start != end) { windows = windows + Window(days, start, end) }
                    }
                }
            }

            Label("Whole categories")
            SetGroup { Inner {
                Chips(listOf(Cats.SOCIAL, Cats.VIDEO, Cats.GAMES, Cats.SHOPS, Cats.BROWSER, Cats.MUSIC), members.filter { it.startsWith("cat:") }.map { it.removePrefix("cat:") }.toSet()) { c ->
                    members = if ("cat:$c" in members) members - "cat:$c" else members + "cat:$c" }
            } }

            Label("Apps (${members.count { !it.startsWith("cat:") }} picked)")
            SearchField(q, { q = it }, "Search apps")
            val apps = remember { Apps.all(ctx) }
            SetGroup {
                apps.filter { q.isBlank() || it.label.contains(q.trim(), true) }.sortedBy { if (it.pkg in members) 0 else 1 }
                    .forEachIndexed { i, a ->
                        if (i > 0) SetDivider0()
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            ToggleRow(a.label, Cats.of(a.pkg), on = a.pkg in members, onToggle = { members = if (a.pkg in members) members - a.pkg else members + a.pkg })
                        }
                    }
            }
            Spacer(Modifier.height(10.dp))
            val draft = Group(id, name.trim().ifEmpty { "Group" }, orig?.on ?: true, always, members, windows)
            MainBtn(if (orig == null) "Create group" else "Save", enabled = members.isNotEmpty() && (always || windows.isNotEmpty())) {
                Groups.register(id)
                val cur = Groups.get(id)
                if (cur != null && Groups.looser(cur.encode(), draft.encode())) {
                    // Mixed edit: what it adds blocks right away; what it removes is the pending change (confirm after 1 h).
                    val union = draft.copy(on = cur.on || draft.on, always = cur.always || draft.always,
                        members = (cur.members + draft.members).distinct(), windows = (cur.windows + draft.windows).distinct())
                    if (union.encode() != cur.encode()) Rules.change(Groups.key(id), union.encode(), "Group ${draft.name}: add new apps/times")
                }
                rule(Groups.key(id), draft.encode(), (if (orig == null) "New group " else "Edit group ") + draft.name)
                back()
            }
            if (orig != null) MainBtn("Delete group", primary = false, danger = true) {
                val applied = Rules.change(Groups.key(id), null, "Delete group ${orig.name}")
                toast(if (applied) "Deleted" else "Confirm the delete in 1 h")
                bump++; back()
            }
        }
    }

    /** Big time with round −/+ buttons (30 min steps). */
    @Composable private fun Stepper(label: String, v: Int, set: (Int) -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        T(label, 15.sp, Dim, Modifier.width(56.dp))
        T(fmtHm(v), 22.sp, White, Modifier.weight(1f), bold = true)
        RoundBtn("−") { set((v - 30 + 1440) % 1440) }
        Spacer(Modifier.width(10.dp))
        RoundBtn("+") { set((v + 30) % 1440) }
    }

    // ------------------------------------------------------------------ apps

    @Composable private fun LabelsPage() = Sub("Apps & labels") {
        T("Pick a category for each app.", 13.sp, Dim)
        var open by keep<String?>("labels.open", null)
        var newLabel by keep("labels.newLabel", "")
        var q by keep("labels.q", "")
        Label("New label")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(newLabel, { newLabel = it }, "label name", singleLine = true, modifier = Modifier.weight(1f))
            MainBtn("Add", primary = false) { if (newLabel.isNotBlank()) { Store.setList("customLabels", Store.list("customLabels") + newLabel.trim()); newLabel = "" } }
        }
        Label("Apps")
        SearchField(q, { q = it }, "Search apps")
        SetGroup {
            Apps.all(this@SettingsActivity).filter { q.isBlank() || it.label.contains(q.trim(), true) }.forEachIndexed { i, a ->
                if (i > 0) SetDivider0()
                val cur = Cats.of(a.pkg)
                ValueRow(a.label, cur.ifEmpty { "Unlabelled" }, sub = pendingMark("label.${a.pkg}"), chevron = true) { open = if (open == a.pkg) null else a.pkg }
                if (open == a.pkg) Inner {
                    if (a.pkg in Cats.lockedSocial) T("Locked to Social", 14.sp, Dim)
                    else Chips(Cats.all(), setOf(cur)) { rule("label.${a.pkg}", it, "Label ${a.label}: ${cur.ifEmpty { "unlabelled" }} → $it"); open = null }
                }
            }
        }
    }

    @Composable private fun ModesPage() = Sub("Chats-free & grayscale") {
        val ctx = this@SettingsActivity
        T("Gate only the feeds, or turn apps gray.", 13.sp, Dim)
        val withDet = Detectors.withDetector.filter { packageManager.getLaunchIntentForPackage(it) != null }
        if (withDet.isNotEmpty()) {
            Label("Chats-free mode")
            SetGroup {
                withDet.forEachIndexed { i, pkg ->
                    if (i > 0) SetDivider0()
                    val name = Apps.label(ctx, pkg)
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        ToggleRow(name, sub = listOf(if (Detectors.chatsFree(pkg)) "Chats free" else "Whole app gated", pendingMark("mode.$pkg"))
                            .filter { it.isNotEmpty() }.joinToString(" · "),
                            on = Detectors.chatsFree(pkg),
                            onToggle = { val cf = Detectors.chatsFree(pkg); rule("mode.$pkg", if (cf) "whole" else "chats", "$name mode → ${if (cf) "whole app" else "chats free"}") })
                    }
                }
            }
        }
        Label("Grayscale while open")
        if (!Gray.canWrite(ctx)) T("Needs the adb grant first (see Permissions).", 13.sp, Dim)
        SetGroup {
            Apps.all(ctx).filter { Cats.isGated(ctx, it.pkg) }.forEachIndexed { i, a ->
                if (i > 0) SetDivider0()
                val on = Gray.enabledFor(a.pkg)
                Box(Modifier.padding(horizontal = 16.dp)) {
                    ToggleRow(a.label, sub = pendingMark("gray.${a.pkg}"), on = on,
                        onToggle = { rule("gray.${a.pkg}", if (on) "0" else "1", "Grayscale ${a.label} → ${if (on) "off" else "on"}") })
                }
            }
        }
    }

    @Composable private fun DomainsPage() = Sub("Chrome sites") {
        T("Gates these sites in Chrome. Shorts is always blocked.", 13.sp, Dim)
        var open by keep<String?>("domains.open", null)
        Label("Sites")
        SetGroup {
            val ds = Detectors.domains().toSortedMap()
            if (ds.isEmpty()) Inner { T("No sites yet.", 14.sp, Dim) }
            ds.entries.forEachIndexed { i, (d, c) ->
                if (i > 0) SetDivider0()
                ValueRow(d, c, sub = pendingMark("domain.$d"), chevron = true) { open = if (open == d) null else d }
                if (open == d) Inner {
                    Chips(Cats.all() + "Remove", setOf(c)) {
                        rule("domain.$d", if (it == "Remove") null else it, if (it == "Remove") "Remove site $d" else "Site $d → $it"); open = null }
                }
            }
        }
        Label("Add a site")
        var host by keep("domains.host", "")
        var cat by keep("domains.cat", Cats.SOCIAL)
        SetGroup { Inner {
            Field(host, { host = it.lowercase().trim() }, "example.com", singleLine = true)
            Chips(listOf(Cats.SOCIAL, Cats.VIDEO, Cats.SHOPS, Cats.GAMES), setOf(cat)) { cat = it }
            MainBtn("Add site", enabled = host.contains('.')) { rule("domain.${host.removePrefix("www.")}", cat, "Add site $host → $cat"); host = "" }
        } }
    }

    @Composable private fun PickApps(title: String, hint: String, key: String, max: Int) = Sub(title) {
        T(hint, 13.sp, Dim)
        val hidden = Store.list("hiddenApps").toSet()
        val cur = Store.list(key).filter { it !in hidden }   // hidden apps stay stored but don't count
        var q by keep("pick.q", "")
        Label("Pinned (${cur.size} of $max)")
        SetGroup {
            if (cur.isEmpty()) Inner { T("Nothing pinned yet.", 14.sp, Dim) }
            cur.forEachIndexed { i, p -> if (i > 0) SetDivider0()
                ValueRow(Apps.label(this@SettingsActivity, p), "Remove") { Store.setList(key, Store.list(key) - p); GuardService.refresh(this@SettingsActivity) } }
        }
        Label("Add")
        SearchField(q, { q = it }, "Search apps")
        SetGroup {
            Apps.all(this@SettingsActivity).filter { !Cats.gatedAtLaunch(this@SettingsActivity, it.pkg) && it.pkg !in cur && it.pkg !in hidden && (q.isBlank() || it.label.contains(q.trim(), true)) }
                .forEachIndexed { i, a -> if (i > 0) SetDivider0()
                    ValueRow(a.label, "Add") { if (cur.size < max) { Store.setList(key, Store.list(key) + a.pkg); GuardService.refresh(this@SettingsActivity) } else toast("Max $max") } }
        }
    }

    // ------------------------------------------------------------------ notifications

    @Composable private fun NotifAppsPage() = Sub("Notifications") {
        T("Calls, SMS, alarms and codes always ring.", 13.sp, Dim)
        Label("Categories that ring")
        val wl = Store.list("whitelistCats").ifEmpty { listOf(Cats.CHAT, Cats.FINANCE, Cats.SCHOOL) }
        SetGroup { Inner { Chips(Cats.all(), wl.toSet()) { c -> Store.setList("whitelistCats", if (c in wl) wl - c else wl + c) } } }
        Label("Never filter these contacts")
        var contact by keep("notifs.contact", "")
        SetGroup {
            Store.list("neverFilterContacts").forEachIndexed { i, c -> if (i > 0) SetDivider0()
                ValueRow(c, "Remove") { Store.setList("neverFilterContacts", Store.list("neverFilterContacts") - c) } }
            if (Store.list("neverFilterContacts").isNotEmpty()) SetDivider0()
            Inner {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(contact, { contact = it }, "name as shown in notifications", singleLine = true, modifier = Modifier.weight(1f))
                    MainBtn("Add", primary = false) { if (contact.isNotBlank()) { Store.setList("neverFilterContacts", Store.list("neverFilterContacts") + contact.trim()); contact = "" } }
                }
            }
        }
        Label("Apps that notified you")
        val chans by App.db.dao().channelsFlow().collectAsState(emptyList())
        SetGroup {
            val byApp = chans.groupBy { it.pkg }
            if (byApp.isEmpty()) Inner { T("None yet.", 14.sp, Dim) }
            byApp.entries.forEachIndexed { i, (pkg, l) ->
                if (i > 0) SetDivider0()
                val never = pkg in Store.list("neverFilterApps")
                ValueRow(l.first().app, if (never) "Never filtered" else "${l.size} channels", chevron = true) { go("notif:$pkg") }
            }
        }
    }

    @Composable private fun ChannelsPage(pkg: String) {
        val dao = App.db.dao()
        val chans by dao.channelsFlow().collectAsState(emptyList())
        val mine = chans.filter { it.pkg == pkg }
        val cat = Cats.of(pkg).ifEmpty { Cats.OTHER }
        Sub(mine.firstOrNull()?.app ?: pkg) {
            val never = pkg in Store.list("neverFilterApps")
            SetGroup { Box(Modifier.padding(horizontal = 16.dp)) {
                ToggleRow("Never filter this app", sub = "Every notification pings", on = never, onToggle = {
                    Store.setList("neverFilterApps", if (never) Store.list("neverFilterApps") - pkg else Store.list("neverFilterApps") + pkg) })
            } }
            if (pkg in Detectors.withDetector) {
                Label("By type")
                SetGroup {
                    Notifs.splitKinds.forEachIndexed { i, k ->
                        if (i > 0) SetDivider0()
                        val cur = Store.get("nkind.$pkg.$k")
                        Inner {
                            T(k, 15.sp, White); if (cur == null) T("default: ${Notifs.default(cat, k)}", 13.sp, Dim)
                            Chips(listOf(PING, DIGEST, DROP), setOfNotNull(cur)) { Store.set("nkind.$pkg.$k", if (cur == it) null else it) }
                        }
                    }
                }
                val samples = remember { dao.notifLog(pkg).filter { it.text.isNotEmpty() }.take(10) }
                if (samples.isNotEmpty()) {
                    Label("Logged examples")
                    SetGroup { samples.forEachIndexed { i, s -> if (i > 0) SetDivider0(); ValueRow(s.title, s.kind, sub = s.text) } }
                }
            }
            Label("Channels")
            SetGroup {
                if (mine.isEmpty()) Inner { T("No channels seen yet.", 14.sp, Dim) }
                mine.forEachIndexed { i, c ->
                    if (i > 0) SetDivider0()
                    Inner {
                        T(c.name, 15.sp, White); if (c.decision == null) T("default", 13.sp, Dim)
                        Chips(listOf(PING, DIGEST, DROP), setOfNotNull(c.decision)) { dao.upsertChannel(c.copy(decision = if (c.decision == it) null else it)) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            MainBtn("Open system notification settings", primary = false) {
                openSystemSettings(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg))
            }
        }
    }

    @Composable private fun DigestPage() = Sub("Digest times") {
        T("When held notifications show up.", 13.sp, Dim)
        val times = Store.list("digestTimes").ifEmpty { listOf("1200") }.mapNotNull { it.toIntOrNull() }.sorted()
        Label("Times")
        SetGroup { times.forEachIndexed { i, t -> if (i > 0) SetDivider0()
            ValueRow(fmtHm(t), "Remove") { Store.setList("digestTimes", (times - t).map { "$it" }) } } }
        var add by keep("digest.add", 12 * 60)
        Label("Add a time")
        SetGroup { Inner {
            Stepper("At", add) { add = it }
            MainBtn("Add ${fmtHm(add)}") { Store.setList("digestTimes", (times + add).distinct().map { "$it" }) }
        } }
    }

    // ------------------------------------------------------------------ phone

    @SuppressLint("MissingPermission")
    @Composable private fun CardsPage() = Sub("Cards") {
        val ctx = this@SettingsActivity
        val dao = App.db.dao()
        val cards by dao.cardsFlow().collectAsState(emptyList())
        val walletOk = remember { CardOpen.walletInstalled(ctx) }
        val live by Store.map.collectAsState()   // cardWallet.* is kept out of the page-rebuild key, so read it live here
        Label("Your cards")
        SetGroup {
            if (cards.isEmpty()) Inner { T("No cards yet.", 14.sp, Dim) }
            cards.forEachIndexed { i, c ->
                if (i > 0) SetDivider0()
                val wallet = live["cardWallet.${c.id}"] == "1"
                ValueRow("${c.name} · ${c.brand}", "Delete",
                    sub = when { wallet -> "opens Google Wallet"; c.appPkg.isNotEmpty() -> "app-only"; else -> c.format },
                    onLabel = { CardOpen.open(ctx, c.id) }) {
                    CardOpen.setWallet(c.id, false)
                    if (Store.get("inStoreCard") == c.id.toString()) Geo.leaveStore(ctx)   // drop its home button + notification
                    dao.deleteCard(c.id); Geo.refresh(ctx) }
                // Per card: open it in Google Wallet, or show the barcode saved here.
                val walletOnly = c.number.isBlank() && c.appPkg.isEmpty()   // nothing to show as a barcode: keep Wallet on
                if ((walletOk || wallet) && !walletOnly) Box(Modifier.padding(horizontal = 16.dp)) {
                    ToggleRow("Open in Google Wallet",
                        on = wallet, onToggle = { CardOpen.setWallet(c.id, !wallet) })
                }
            }
        }
        var name by keep("cards.name", "")
        var number by keep("cards.number", "")
        var brand by keep("cards.brand", "")
        var brandQ by keep("cards.brandQ", "")
        var appPkg by keep("cards.appPkg", "")
        var appOnly by keep("cards.appOnly", false)
        var inWallet by keep("cards.inWallet", false)
        LaunchedEffect(scanned) { scanned?.let { number = it.first } }
        Label("Add a card")
        SetGroup {
            Inner {
                Field(name, { name = it }, "card name (e.g. Maxima)", singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(number, { number = it.trim() }, "number", singleLine = true, modifier = Modifier.weight(1f))
                    MainBtn("Scan", primary = false) { scan.launch(ScanOptions().setBeepEnabled(false).setOrientationLocked(true).setPrompt("Scan the card's barcode")) }
                }
                T("Format: " + (scanned?.takeIf { it.first == number }?.second ?: Barcodes.guess(number)), 13.sp, Dim)
                T("Store brand: ${brand.ifEmpty { "none" }}", 15.sp, White)
                Field(brandQ, { brandQ = it }, "search brand", singleLine = true)
                if (brandQ.length >= 2) Chips(Geo.brands(ctx).filter { it.contains(brandQ, true) }.take(12), setOf(brand)) { brand = it; brandQ = "" }
            }
            SetDivider0()
            if (walletOk) Box(Modifier.padding(horizontal = 16.dp)) {
                ToggleRow("Card is in Google Wallet", sub = "Opens Wallet at the store. Number optional.",
                    on = inWallet, onToggle = { inWallet = !inWallet })
            }
            Box(Modifier.padding(horizontal = 16.dp)) { ToggleRow("App-only card", sub = "Code is in the store's app", on = appOnly, onToggle = { appOnly = !appOnly }) }
            if (appOnly) Inner {
                T("Store app: ${if (appPkg.isEmpty()) "pick one" else Apps.label(ctx, appPkg)}", 15.sp, White)
                Chips(Apps.all(ctx).filter { Cats.of(it.pkg) in setOf(Cats.TOOLS, Cats.SHOPS, Cats.OTHER, Cats.FINANCE) }.map { it.label }, emptySet()) { l ->
                    appPkg = Apps.all(ctx).first { it.label == l }.pkg }
            }
            Inner {
                MainBtn("Save card", enabled = name.isNotBlank() && brand.isNotEmpty() && (number.isNotBlank() || inWallet || (appOnly && appPkg.isNotEmpty()))) {
                    val fmt = scanned?.takeIf { it.first == number }?.second ?: Barcodes.guess(number)
                    val id = dao.upsertCard(CardRow(name = name.trim(), brand = brand, number = number, format = fmt, appPkg = if (appOnly) appPkg else ""))
                    if (inWallet) CardOpen.setWallet(id, true)
                    name = ""; number = ""; brand = ""; appPkg = ""; appOnly = false; inWallet = false; scanned = null
                    Geo.refresh(ctx)
                }
            }
        }
        Label("Save a store's location")
        var placeBrand by keep("cards.placeBrand", "")
        SetGroup { Inner {
            Chips(cards.map { it.brand }.distinct(), setOf(placeBrand)) { placeBrand = it }
            MainBtn("Save current location as ${placeBrand.ifEmpty { "…" }}", primary = false, enabled = placeBrand.isNotEmpty() && Geo.hasPermission(ctx)) {
                LocationServices.getFusedLocationProviderClient(ctx).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { loc -> if (loc != null) { dao.insertPlace(PlaceRow(brand = placeBrand, lat = loc.latitude, lon = loc.longitude)); Geo.refresh(ctx); toast("Saved") } else toast("No location fix") }
            }
            if (!Geo.hasPermission(ctx)) T("Needs location “Allow all the time”.", 13.sp, Red)
        } }
    }

    @Composable private fun LockdownPage() = Sub("Lockdown") {
        T("Blocks the settings that could turn this app off.", 13.sp, Dim)
        val on = Store.str("lockdown", "1") != "0"
        SetGroup {
            Box(Modifier.padding(horizontal = 16.dp)) {
                ToggleRow("Lockdown", sub = listOf(lockdownState(), pendingMark("lockdown")).filter { it.isNotEmpty() }.joinToString(" · "),
                    on = on, onToggle = { if (on) rule("lockdown", "0", "Disable lockdown") else rule("lockdown", "1", "Lockdown on") })
            }
            if (!Guard.lockdownArmed() && on) Inner { MainBtn("Arm now", primary = false) {
                Store.set("lockdownAt", Clock.now().toString()); Guard.log("lockdown", "Armed by hand") } }
        }
        T("Turning it off waits 1 h. Locked out? See RECOVERY.md.", 13.sp, Dim)
    }
}

/** One main-page row: [sub] = current value in grey; [find] = extra search words, never shown; [content] replaces the row; no [chevron] = not tappable. */
private class Nav(@androidx.annotation.DrawableRes val icon: Int, val label: String, val sub: String = "", val find: String = "", val warn: Boolean = false,
                  val chevron: Boolean = true, val content: (@Composable () -> Unit)? = null, val onClick: () -> Unit)

// ---- Settings list pieces (home look: rounded dark cards, icon rows, thin inset dividers) ----
internal val SetCard = Color(0xFF141414)
private val SetLine = Color(0xFF232323)

/** Rounded dark card that holds a few rows. */
@Composable
internal fun SetGroup(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SetCard).padding(vertical = 4.dp), content = content)

/** Thin line between rows, starting after the icon like the reference sidebar. */
@Composable
private fun SetDivider() = Box(Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp).height(1.dp).background(SetLine))

/** Thin line between rows that have no icon. */
@Composable
internal fun SetDivider0() = Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(SetLine))

/** Grey title above a card. */
@Composable
internal fun Label(text: String, modifier: Modifier = Modifier) = T(text, 14.sp, Dim, modifier.padding(top = 14.dp, start = 4.dp))

/** Padded block inside a card for sliders, chips, fields and buttons. */
@Composable
internal fun Inner(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)

@Composable
private fun Hint(text: String) = T(text, 14.sp, Faint, Modifier.padding(start = 52.dp, end = 16.dp, top = 2.dp, bottom = 10.dp))

/**
 * Plain card row: label (+ grey line under it) on the left, grey value / action on the right.
 * [onClick] = tap on the value (or the whole row when there's no [onLabel]); [chevron] adds "›".
 */
@Composable
internal fun ValueRow(label: String, value: String = "", sub: String = "", color: Color = Dim, chevron: Boolean = false,
                      onLabel: (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .then(if (onClick != null && onLabel == null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).then(if (onLabel != null) Modifier.clickable(onClick = onLabel) else Modifier)) {
            T(label, 16.sp, White)
            if (sub.isNotEmpty()) T(sub, 13.sp, Dim)
        }
        if (value.isNotEmpty()) T(value, 14.sp, color,
            Modifier.padding(start = 12.dp).then(if (onClick != null && onLabel != null) Modifier.clickable(onClick = onClick).padding(vertical = 6.dp) else Modifier))
        if (chevron) Icon(painterResource(R.drawable.si_chevron_right), null, Modifier.padding(start = 4.dp).size(20.dp), tint = Dim)
    }
}

/** Main action = filled white with black text; secondary = outlined; danger = red outline. */
@Composable
internal fun MainBtn(text: String, primary: Boolean = true, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val fg = when { !enabled -> Faint; danger -> Red; primary -> Black; else -> White }
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.heightIn(min = 46.dp).clip(shape)
            .then(if (primary && !danger) Modifier.background(if (enabled) White else Color(0xFF262626)) else
                Modifier.background(Color(0xFF1C1C1C)).then(Modifier.padding(0.dp)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) { T(text, 15.sp, fg, bold = true) }
}

/** Round dark button with a symbol ("−", "+"). */
@Composable
private fun RoundBtn(sym: String, onClick: () -> Unit) =
    Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF262626)).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        T(sym, 20.sp, White)
    }

/**
 * One row: icon, label (+ grey [sub] line with the current value), optional count pill, then a chevron
 * (› to open a page, ⌄/⌃ for folding sections). [onClick] null = plain info row, no chevron.
 */
@Composable
private fun NavRow(@androidx.annotation.DrawableRes icon: Int, label: String, badge: String = "", color: Color = White,
                   open: Boolean? = null, sub: String = "", chevron: Boolean = true, tall: Boolean = false, onClick: (() -> Unit)?) {
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open == true) -90f else 90f, label = "chev")
    // A short value sits on the right before the chevron (phone-settings style); a long one goes under the label.
    val right = sub.isNotEmpty() && sub.length <= 24 && !sub.contains('\n')
    Row(Modifier.fillMaxWidth().heightIn(min = if (tall) 64.dp else 54.dp).padding(horizontal = 6.dp).clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics { if (open != null) stateDescription = if (open) "Expanded" else "Collapsed" }.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(21.dp), tint = if (color == White) Bright else color)
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f)) {
            T(label, 16.sp, color)
            if (sub.isNotEmpty() && !right) T(sub, 13.sp, Dim)
        }
        if (right) T(sub, 15.sp, Dim, Modifier.padding(start = 12.dp, end = 4.dp))
        if (badge.isNotEmpty()) {
            Box(Modifier.padding(end = 8.dp).clip(CircleShape).background(Color(0xFF262626))
                .padding(horizontal = 9.dp, vertical = 2.dp)) { T(badge, 13.sp, Bright) }
        }
        if (chevron && onClick != null) Icon(painterResource(R.drawable.si_chevron_right), null,
            Modifier.size(20.dp).then(if (open != null) Modifier.rotate(turn) else Modifier), tint = Dim)
    }
}
