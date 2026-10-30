package lt.stillhome.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import lt.stillhome.App
import lt.stillhome.cards.Barcodes
import lt.stillhome.cards.Geo
import lt.stillhome.core.Apps
import lt.stillhome.core.Blocked
import lt.stillhome.core.Cats
import lt.stillhome.core.Clock
import lt.stillhome.core.Gray
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

/** Settings: a tiny page stack instead of a navigation library. Every rule edit goes through the 24 h rule. */
class SettingsActivity : BlackActivity() {
    private val stack = mutableStateListOf("main")
    private var scanned by mutableStateOf<Pair<String, String>?>(null)   // number, format
    private val scan = registerForActivityResult(ScanContract()) { r ->
        if (r.contents != null) scanned = r.contents to (r.formatName ?: Barcodes.guess(r.contents))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("page")?.let { stack.add(it) }
        setContent {
            val map by Store.map.collectAsState()
            BackHandler { if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish() }
            // key(map): pages read Store directly, so rebuild the page whenever any setting changes.
            androidx.compose.runtime.key(map) { when (val p = stack.last()) {
                "main" -> Main()
                "limits" -> LimitsPage()
                "blocked" -> BlockedPage()
                "labels" -> LabelsPage()
                "modes" -> ModesPage()
                "domains" -> DomainsPage()
                "notifs" -> NotifAppsPage()
                "digest" -> DigestPage()
                "home" -> PickApps("Home apps (max 6)", "homeApps", 6)
                "quick" -> PickApps("Lock-screen apps (max 3)", "quickApps", 3)
                "cards" -> CardsPage()
                "focus" -> FocusPage()
                "pending" -> PendingPage()
                "stats" -> StatsPage()
                "lockdown" -> LockdownPage()
                "permissions" -> Page { Title("Permissions"); Permissions(this@SettingsActivity) }
                else -> if (p.startsWith("notif:")) ChannelsPage(p.removePrefix("notif:")) else Main()
            } }
        }
    }

    private fun go(p: String) { stack.add(p) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun rule(key: String, value: String?, desc: String) = toast(ruleChange(key, value, desc))
    private fun pendingMark(key: String) = Rules.pendingFor(key)?.let { " ⏳ ${it.value ?: "off"} in ${fmtDur(it.applyAt - Clock.now())}" } ?: ""

    @Composable private fun Main() = Page {
        Title("Settings")
        if (!GuardA11yService.running()) RowItem("guard off — turn the accessibility service on", color = Red) { go("permissions") }
        Detectors.withDetector.plus(Detectors.YT).forEach { pkg ->
            if (Store.long("broken.$pkg", 0) > Clock.now() - 86400_000L)
                RowItem("detector broken: ${Apps.label(this@SettingsActivity, pkg)}", color = Red)
        }
        val phase = Guard.phase()
        T(when (phase) {
            "baseline" -> "Baseline week: day ${((Clock.now() - Store.long("baselineStart", Clock.now())) / 86400_000L) + 1} of 7"
            "active" -> "Rules active" + if (Guard.lockdownArmed()) " · lockdown armed" else " · lockdown arms ${Clock.day(Store.long("lockdownAt", 0))}"
            else -> "Setup not finished"
        }, 14.sp, Dim)
        val pending = App.db.dao().pending().size
        RowItem("Limits & budgets") { go("limits") }
        RowItem("Blocked hours") { go("blocked") }
        RowItem("Focus mode") { go("focus") }
        RowItem("Apps & labels") { go("labels") }
        RowItem("Chats-free modes & grayscale") { go("modes") }
        RowItem("Chrome sites") { go("domains") }
        RowItem("Notifications") { go("notifs") }
        RowItem("Digest times") { go("digest") }
        RowItem("Home apps") { go("home") }
        RowItem("Lock-screen apps") { go("quick") }
        RowItem("Cards") { go("cards") }
        RowItem("Pending changes", if (pending > 0) "$pending" else "") { go("pending") }
        RowItem("Stats") { go("stats") }
        RowItem("Lockdown") { go("lockdown") }
        RowItem("Permissions") { go("permissions") }
        RowItem("Retake quiz") { startActivity(android.content.Intent(this@SettingsActivity, OnboardingActivity::class.java).putExtra("page", "quiz")) }
    }

    @Composable private fun LimitsPage() = Page {
        Title("Limits")
        T("Tightening applies now. Loosening waits 24 h. Ceilings can't be exceeded.", 13.sp, Dim)
        for (cat in Cats.budgeted) {
            val l = Limits.of(cat)
            Section("$cat — used ${Guard.usedMin(cat)} of ${Limits.budget(cat)} min today")
            T("Daily budget" + pendingMark("budget.$cat"), 14.sp, Dim)
            Chips((l.budgets + Limits.budget(cat)).distinct().sorted().map { "$it" }, setOf("${Limits.budget(cat)}")) {
                rule("budget.$cat", it, "$cat budget ${Limits.budget(cat)} → $it min") }
            T("Longest session" + pendingMark("sessionMax.$cat"), 14.sp, Dim)
            Chips(l.sessions.map { "$it" }, setOf("${Limits.sessionMax(cat)}")) {
                rule("sessionMax.$cat", it, "$cat longest session ${Limits.sessionMax(cat)} → $it min") }
        }
        Section("Cooldown after a session (× session length, min 30 min)" + pendingMark("cooldownMult"))
        Chips(Limits.cooldownOptions.map { "${it}×" }, setOf("${Store.int("cooldownMult", 2)}×")) {
            rule("cooldownMult", it.removeSuffix("×"), "Cooldown ${Store.int("cooldownMult", 2)}× → $it") }
        Section("Escalation per extra session today" + pendingMark("escalation"))
        Chips(Limits.escalationOptions.map { if (it == 100) "off" else "×${it / 100.0}" },
            setOf(Store.int("escalation", 100).let { if (it == 100) "off" else "×${it / 100.0}" })) {
            val v = if (it == "off") 100 else (it.removePrefix("×").toDouble() * 100).toInt()
            rule("escalation", "$v", "Escalation → $it") }
        Section("Weekly auto-tighten (Social)" + pendingMark("autoTighten"))
        Chips(Limits.tightenOptions.map { if (it == 0) "off" else "−$it min" }, setOf(Store.int("autoTighten", 5).let { if (it == 0) "off" else "−$it min" })) {
            rule("autoTighten", if (it == "off") "0" else it.filter(Char::isDigit), "Auto-tighten → $it") }
        T("Floor" + pendingMark("tightenFloor"), 14.sp, Dim)
        Chips(listOf("10", "15", "20", "30"), setOf(Store.str("tightenFloor", "20"))) { rule("tightenFloor", it, "Auto-tighten floor → $it min") }
        Section("Daily phone-time target" + pendingMark("targetMin"))
        Chips(listOf(60, 90, 120, 150, 180, 240).map { fmtDur(it * 60_000L) }, setOf(fmtDur(Store.int("targetMin", 120) * 60_000L))) { s ->
            val m = listOf(60, 90, 120, 150, 180, 240).first { fmtDur(it * 60_000L) == s }
            rule("targetMin", "$m", "Target → $s") }
        Section("Gate countdown" + pendingMark("countdown"))
        Chips(listOf("10", "20"), setOf(Store.str("countdown", "10"))) { rule("countdown", it, "Gate countdown → $it s") }
        Section("Minimum reason length" + pendingMark("reasonMin"))
        Chips(listOf("20", "30"), setOf(Store.str("reasonMin", "20"))) { rule("reasonMin", it, "Reason minimum → $it chars") }
    }

    @Composable private fun BlockedPage() = Page {
        Title("Blocked hours")
        T("No gated app opens inside these windows." + pendingMark("blocked"), 13.sp, Dim)
        val ws = Blocked.windows()
        ws.forEachIndexed { i, w ->
            RowItem(w.label(), "remove") { rule("blocked", Blocked.encode(ws.filterIndexed { j, _ -> j != i }), "Remove blocked hours ${w.label()}") }
        }
        Section("Add a window")
        var days by remember { mutableStateOf(setOf(1, 2, 3, 4, 5, 6, 7)) }
        var start by remember { mutableIntStateOf(22 * 60) }
        var end by remember { mutableIntStateOf(7 * 60) }
        val names = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
        Chips(names, days.map { names[it - 1] }.toSet()) { n -> val d = names.indexOf(n) + 1; days = if (d in days) days - d else days + d }
        Stepper("From", start) { start = it }
        Stepper("To", end) { end = it }
        Btn("Add ${Window(days, start, end).label()}", enabled = days.isNotEmpty() && start != end) {
            rule("blocked", Blocked.encode(ws + Window(days, start, end)), "Add blocked hours ${Window(days, start, end).label()}")
        }
    }

    @Composable private fun Stepper(label: String, v: Int, set: (Int) -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        T("$label ${fmtHm(v)}", 18.sp, White)
        T("−", 22.sp, White, Modifier.clickable { set((v - 30 + 1440) % 1440) })
        T("+", 22.sp, White, Modifier.clickable { set((v + 30) % 1440) })
    }

    @Composable private fun LabelsPage() = Page {
        Title("Apps & labels")
        T("Moving an app out of a gated category waits 24 h. Known social apps are locked to Social.", 13.sp, Dim)
        var open by remember { mutableStateOf<String?>(null) }
        var newLabel by remember { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(newLabel, { newLabel = it }, "new label", singleLine = true, modifier = Modifier.weight(1f))
            Btn("Add") { if (newLabel.isNotBlank()) { Store.setList("customLabels", Store.list("customLabels") + newLabel.trim()); newLabel = "" } }
        }
        Apps.all(this@SettingsActivity).forEach { a ->
            val cur = Cats.of(a.pkg)
            RowItem(a.label, (cur.ifEmpty { "unlabelled" }) + pendingMark("label.${a.pkg}")) { open = if (open == a.pkg) null else a.pkg }
            if (open == a.pkg) {
                if (a.pkg in Cats.lockedSocial) T("Locked to Social", 14.sp, Dim)
                else Chips(Cats.all(), setOf(cur)) { rule("label.${a.pkg}", it, "Label ${a.label}: ${cur.ifEmpty { "unlabelled" }} → $it"); open = null }
            }
        }
    }

    @Composable private fun ModesPage() = Page {
        Title("Modes & grayscale")
        for (pkg in Detectors.withDetector) {
            if (packageManager.getLaunchIntentForPackage(pkg) == null) continue
            val name = Apps.label(this@SettingsActivity, pkg)
            Section(name + pendingMark("mode.$pkg"))
            Chips(listOf("chats free", "whole app"), setOf(if (Detectors.chatsFree(pkg)) "chats free" else "whole app")) {
                rule("mode.$pkg", if (it == "chats free") "chats" else "whole", "$name mode → $it") }
        }
        Section("Grayscale while open")
        T(if (Gray.canWrite(this@SettingsActivity)) "Uses colour correction (monochrome)." else "Needs WRITE_SECURE_SETTINGS (adb grant).", 13.sp, Dim)
        Apps.all(this@SettingsActivity).filter { Cats.isGated(this@SettingsActivity, it.pkg) }.forEach { a ->
            val on = Gray.enabledFor(a.pkg)
            RowItem(a.label, (if (on) "on" else "off") + pendingMark("gray.${a.pkg}")) {
                rule("gray.${a.pkg}", if (on) "0" else "1", "Grayscale ${a.label} → ${if (on) "off" else "on"}") }
        }
    }

    @Composable private fun DomainsPage() = Page {
        Title("Chrome sites")
        T("Sites in gated categories need a Gate session in Chrome. youtube.com/shorts is always blocked. Other browsers are gated whole-app.", 13.sp, Dim)
        var open by remember { mutableStateOf<String?>(null) }
        Detectors.domains().toSortedMap().forEach { (d, c) ->
            RowItem(d, c + pendingMark("domain.$d")) { open = if (open == d) null else d }
            if (open == d) {
                Chips(Cats.all() + "remove", setOf(c)) {
                    rule("domain.$d", if (it == "remove") null else it, if (it == "remove") "Remove site $d" else "Site $d → $it"); open = null }
            }
        }
        Section("Add a site")
        var host by remember { mutableStateOf("") }
        var cat by remember { mutableStateOf(Cats.SOCIAL) }
        Field(host, { host = it.lowercase().trim() }, "example.com", singleLine = true)
        Chips(listOf(Cats.SOCIAL, Cats.VIDEO, Cats.SHOPS, Cats.GAMES), setOf(cat)) { cat = it }
        Btn("Add") { if (host.contains('.')) { rule("domain.${host.removePrefix("www.")}", cat, "Add site $host → $cat"); host = "" } }
    }

    @Composable private fun NotifAppsPage() = Page {
        Title("Notifications")
        T("Calls, SMS, alarms and one-time codes always ping. Changes here apply instantly.", 13.sp, Dim)
        Section("Whitelist categories (make sound)")
        val wl = Store.list("whitelistCats").ifEmpty { listOf(Cats.CHAT, Cats.FINANCE, Cats.SCHOOL) }
        Chips(Cats.all(), wl.toSet()) { c -> Store.setList("whitelistCats", if (c in wl) wl - c else wl + c) }
        Section("Never filter (contacts, matched by sender name)")
        var contact by remember { mutableStateOf("") }
        Store.list("neverFilterContacts").forEach { c -> RowItem(c, "remove") { Store.setList("neverFilterContacts", Store.list("neverFilterContacts") - c) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(contact, { contact = it }, "name", singleLine = true, modifier = Modifier.weight(1f))
            Btn("Add") { if (contact.isNotBlank()) { Store.setList("neverFilterContacts", Store.list("neverFilterContacts") + contact.trim()); contact = "" } }
        }
        Section("Apps that have notified you")
        val chans by App.db.dao().channelsFlow().collectAsState(emptyList())
        chans.groupBy { it.pkg }.forEach { (pkg, l) ->
            val never = pkg in Store.list("neverFilterApps")
            RowItem(l.first().app, "${l.size} channels" + if (never) " · never filter" else "") { go("notif:$pkg") }
        }
    }

    @Composable private fun ChannelsPage(pkg: String) = Page {
        val dao = App.db.dao()
        val chans by dao.channelsFlow().collectAsState(emptyList())
        val mine = chans.filter { it.pkg == pkg }
        val cat = Cats.of(pkg).ifEmpty { Cats.OTHER }
        Title(mine.firstOrNull()?.app ?: pkg)
        val never = pkg in Store.list("neverFilterApps")
        RowItem("Never filter this app", if (never) "on" else "off") {
            Store.setList("neverFilterApps", if (never) Store.list("neverFilterApps") - pkg else Store.list("neverFilterApps") + pkg) }
        if (pkg in Detectors.withDetector) {
            Section("By type (from texts logged on this phone)")
            Notifs.splitKinds.forEach { k ->
                val cur = Store.get("nkind.$pkg.$k")
                T(k + if (cur == null) " (default ${Notifs.default(cat, k)})" else "", 15.sp)
                Chips(listOf(PING, DIGEST, DROP), setOfNotNull(cur)) { Store.set("nkind.$pkg.$k", if (cur == it) null else it) }
            }
            val samples = remember { dao.notifLog(pkg).filter { it.text.isNotEmpty() }.take(10) }
            if (samples.isNotEmpty()) { Section("Logged examples"); samples.forEach { T("${it.kind}: ${it.title} — ${it.text}", 13.sp, Dim) } }
        }
        Section("Channels")
        mine.forEach { c ->
            T(c.name + if (c.decision == null) " (default)" else "", 15.sp)
            Chips(listOf(PING, DIGEST, DROP), setOfNotNull(c.decision)) { dao.upsertChannel(c.copy(decision = if (c.decision == it) null else it)) }
        }
        Spacer(Modifier.height(10.dp))
        Btn("Open system notification settings (mute channels)") {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg))
        }
    }

    @Composable private fun DigestPage() = Page {
        Title("Digest times")
        val times = Store.list("digestTimes").ifEmpty { listOf("1200") }.mapNotNull { it.toIntOrNull() }.sorted()
        times.forEach { t -> RowItem(fmtHm(t), "remove") { Store.setList("digestTimes", (times - t).map { "$it" }) } }
        var add by remember { mutableIntStateOf(12 * 60) }
        Stepper("Add", add) { add = it }
        Btn("Add ${fmtHm(add)}") { Store.setList("digestTimes", (times + add).distinct().map { "$it" }) }
    }

    @Composable private fun PickApps(title: String, key: String, max: Int) = Page {
        Title(title)
        T("Apps from gated categories can't be pinned.", 13.sp, Dim)
        val cur = Store.list(key)
        cur.forEach { p -> RowItem(Apps.label(this@SettingsActivity, p), "remove") { Store.setList(key, cur - p); GuardService.refresh(this@SettingsActivity) } }
        Section("Add")
        Apps.all(this@SettingsActivity).filter { !Cats.isGated(this@SettingsActivity, it.pkg) && it.pkg !in cur }.forEach { a ->
            RowItem(a.label) { if (cur.size < max) { Store.setList(key, cur + a.pkg); GuardService.refresh(this@SettingsActivity) } else toast("Max $max") }
        }
    }

    @SuppressLint("MissingPermission")
    @Composable private fun CardsPage() = Page {
        Title("Cards")
        val dao = App.db.dao()
        val cards by dao.cardsFlow().collectAsState(emptyList())
        cards.forEach { c ->
            RowItem("${c.name} · ${c.brand}", if (c.appPkg.isNotEmpty()) "app-only" else c.format) {
                startActivity(android.content.Intent(this@SettingsActivity, BarcodeActivity::class.java).putExtra("card", c.id)) }
            T("delete", 13.sp, Dim, Modifier.clickable { dao.deleteCard(c.id); Geo.refresh(this@SettingsActivity) })
        }
        Section("Add a card")
        var name by remember { mutableStateOf("") }
        var number by remember { mutableStateOf("") }
        var brand by remember { mutableStateOf("") }
        var brandQ by remember { mutableStateOf("") }
        var appPkg by remember { mutableStateOf("") }
        var appOnly by remember { mutableStateOf(false) }
        LaunchedEffect(scanned) { scanned?.let { number = it.first } }
        Field(name, { name = it }, "card name (e.g. Maxima)", singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(number, { number = it.trim() }, "number", singleLine = true, modifier = Modifier.weight(1f))
            Btn("Scan") { scan.launch(ScanOptions().setBeepEnabled(false).setOrientationLocked(true).setPrompt("Scan the card's barcode")) }
        }
        T("Format: " + (scanned?.takeIf { it.first == number }?.second ?: Barcodes.guess(number)), 13.sp, Dim)
        T("Store brand: ${brand.ifEmpty { "none" }}", 15.sp)
        Field(brandQ, { brandQ = it }, "search brand", singleLine = true)
        if (brandQ.length >= 2) Chips(Geo.brands(this@SettingsActivity).filter { it.contains(brandQ, true) }.take(12), setOf(brand)) { brand = it; brandQ = "" }
        RowItem("App-only card (rotating code)", if (appOnly) "yes" else "no") { appOnly = !appOnly }
        if (appOnly) {
            T("Store app: ${if (appPkg.isEmpty()) "pick below" else Apps.label(this@SettingsActivity, appPkg)}", 15.sp)
            Chips(Apps.all(this@SettingsActivity).filter { Cats.of(it.pkg) in setOf(Cats.TOOLS, Cats.SHOPS, Cats.OTHER, Cats.FINANCE) }.map { it.label }, emptySet()) { l ->
                appPkg = Apps.all(this@SettingsActivity).first { it.label == l }.pkg }
        }
        Btn("Save card", enabled = name.isNotBlank() && brand.isNotEmpty() && (number.isNotBlank() || (appOnly && appPkg.isNotEmpty()))) {
            val fmt = scanned?.takeIf { it.first == number }?.second ?: Barcodes.guess(number)
            dao.upsertCard(CardRow(name = name.trim(), brand = brand, number = number, format = fmt, appPkg = if (appOnly) appPkg else ""))
            name = ""; number = ""; brand = ""; appPkg = ""; appOnly = false; scanned = null
            Geo.refresh(this@SettingsActivity)
        }
        Section("Missing store? Save this place")
        var placeBrand by remember { mutableStateOf("") }
        Chips(cards.map { it.brand }.distinct(), setOf(placeBrand)) { placeBrand = it }
        Btn("Save current location as ${placeBrand.ifEmpty { "…" }}", enabled = placeBrand.isNotEmpty() && Geo.hasPermission(this@SettingsActivity)) {
            LocationServices.getFusedLocationProviderClient(this@SettingsActivity).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc -> if (loc != null) { dao.insertPlace(PlaceRow(brand = placeBrand, lat = loc.latitude, lon = loc.longitude)); Geo.refresh(this@SettingsActivity); toast("Saved") } else toast("No location fix") }
        }
        if (!Geo.hasPermission(this@SettingsActivity)) T("Needs location “Allow all the time” (Permissions).", 13.sp, Red)
    }

    @Composable private fun FocusPage() = Page {
        Title("Focus mode")
        T("All gated apps locked, chat notifications go to the digest. Calls, SMS, alarms and codes still come through. Cannot be ended early.", 14.sp, Dim)
        var now by remember { mutableLongStateOf(Clock.now()) }
        LaunchedEffect(Unit) { while (true) { delay(1000); now = Clock.now() } }
        if (Guard.focusActive(now)) T("Focus: ${fmtMmSs(Guard.focusUntil() - now)} left", 30.sp, White)
        else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(25, 50).forEach { m -> Btn("$m min") {
                Store.set("focusUntil", (Clock.now() + m * 60_000L).toString())
                Guard.log("focus", "Focus $m min")
                GuardService.refresh(this@SettingsActivity); GuardA11yService.instance?.recheck()
            } }
        }
        Spacer(Modifier.height(10.dp))
        RowItem("Show “focus” on home", if (Store.bool("focusShortcut", false)) "on" else "off") {
            Store.set("focusShortcut", if (Store.bool("focusShortcut", false)) "0" else "1") }
    }

    @Composable private fun PendingPage() = Page {
        Title("Pending changes")
        T("Loosening changes wait 24 h. Cancel any of them.", 13.sp, Dim)
        val list by App.db.dao().pendingFlow().collectAsState(emptyList())
        var now by remember { mutableLongStateOf(Clock.now()) }
        LaunchedEffect(Unit) { while (true) { delay(1000); now = Clock.now() } }
        if (list.isEmpty()) T("Nothing pending.", 15.sp, Dim)
        list.forEach { c ->
            Column(Modifier.padding(vertical = 6.dp)) {
                T(c.description, 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    T("applies in ${fmtMmSs(c.applyAt - now).let { if (c.applyAt - now > 3600_000) fmtDur(c.applyAt - now) else it }}", 14.sp, Dim)
                    T("cancel", 14.sp, White, Modifier.clickable { Rules.cancel(c.id) })
                }
            }
        }
    }

    @Composable private fun StatsPage() = Page {
        Title("Stats")
        val dao = App.db.dao()
        val screen = remember { dao.screenSince(Clock.daysAgo(13)) }
        val meters = remember { dao.metersSince(Clock.daysAgo(13)) }
        Section("Phone time (own tracking)")
        screen.reversed().forEach { s -> RowItem(s.day, fmtDur(s.seconds * 1000) + " · " + meters.filter { it.day == s.day }.joinToString(" ") { "${it.category.take(2)} ${it.seconds / 60}" }) }
        Section("Today")
        Cats.budgeted.forEach { RowItem(it, "${Guard.usedMin(it)}/${Limits.budget(it)} min") }
        Section("Quiz")
        RowItem("Guess", "${Store.str("quiz.guessH", "?")} h/day")
        RowItem("Target", fmtDur(Store.int("targetMin", 120) * 60_000L))
    }

    @Composable private fun LockdownPage() = Page {
        Title("Lockdown")
        T("When armed, these send you home: Accessibility, Notification access, Usage access, this app's App info, its uninstall dialog, " +
            "its battery & autostart pages. Recovery: see RECOVERY.md (safe mode / adb).", 14.sp, Dim)
        val at = Store.long("lockdownAt", 0)
        T(when { Guard.lockdownArmed() -> "Armed"; at > 0 -> "Arms ${Clock.day(at)} ${fmtHm(Clock.minuteOfDay(at))}"; else -> "Not scheduled" } + pendingMark("lockdown"), 18.sp, White)
        if (Store.str("lockdown", "1") == "0") Btn("Turn lockdown back on") { rule("lockdown", "1", "Lockdown on") }
        else Btn("Disable lockdown (24 h)") { rule("lockdown", "0", "Disable lockdown") }
        if (!Guard.lockdownArmed() && Store.str("lockdown", "1") != "0") Btn("Arm now") {
            Store.set("lockdownAt", Clock.now().toString()); Guard.log("lockdown", "Armed by hand") }
    }
}
