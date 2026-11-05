package lt.stillhome.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import lt.stillhome.App
import lt.stillhome.core.Apps
import lt.stillhome.core.Blocked
import lt.stillhome.core.Cats
import lt.stillhome.core.Clock
import lt.stillhome.core.Guard
import lt.stillhome.core.Limits
import lt.stillhome.core.Rules
import lt.stillhome.core.Window
import lt.stillhome.core.fmtHm
import lt.stillhome.data.EventRow
import lt.stillhome.data.Store
import lt.stillhome.detect.Detectors

/** Smartphone Addiction Scale – Short Version (Kwon et al., 2013, PLOS ONE 8(12): e83558), 6-point scale. */
val sasItems = listOf(
    "Missing planned work due to smartphone use",
    "Having a hard time concentrating in class, while doing assignments, or while working due to smartphone use",
    "Feeling pain in the wrists or at the back of the neck while using a smartphone",
    "Won't be able to stand not having a smartphone",
    "Feeling impatient and fretful when I am not holding my smartphone",
    "Having my smartphone in my mind even when I am not using it",
    "I will never give up using my smartphone even when my daily life is already greatly affected by it",
    "Constantly checking my smartphone so as not to miss conversations between other people on Twitter or Facebook",
    "Using my smartphone longer than I had intended",
    "The people around me tell me that I use my smartphone too much",
)
val sasScale = listOf("Strongly disagree", "Disagree", "Weakly disagree", "Weakly agree", "Agree", "Strongly agree")

/**
 * Section 12: 8-question quiz (~3 min) → summary of rules → setup screens → permissions → baseline choice.
 * Every answer shows the rule it creates. Rules are applied together on the summary page through
 * [Rules.change], so a retake that loosens anything waits 24 h.
 */
class OnboardingActivity : BlackActivity() {
    private var page by mutableIntStateOf(0)
    private val a = mutableStateMapOf<String, String>()      // answers (single)
    private val m = mutableStateMapOf<String, Set<String>>() // answers (multi)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstRunDefaults()
        val start = intent.getStringExtra("page")
        page = when (start) { "sas" -> 100; "quiz" -> 1; else -> if (Store.bool("setupDone", false)) 1 else 0 }
        setContent {
            BackHandler { if (page in 2..99) page-- else finish() }
            // Questions slide in from the side you're heading (back = from the left) and fade; off when
            // the phone's animations are off.
            val calm = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            androidx.compose.animation.AnimatedContent(page, transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                val t = if (calm) 0 else 220
                (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(t, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { dir * it / 6 } +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(t))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(t, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -dir * it / 6 } +
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(t / 2)))
            }, label = "quiz") { p ->
              // The page sliding out stays on screen ~220 ms: block its buttons so a fast double tap can't save twice.
              androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().then(if (p == page) androidx.compose.ui.Modifier
                  else androidx.compose.ui.Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true)
                      awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() } } })) {
              when (p) {
                0 -> Welcome()
                in 1..7 -> Question(p)
                8 -> SasPage(optional = true) { page = 9 }
                9 -> Summary()
                10 -> LabelSetup()
                11 -> Page { Title("Home apps"); PickList("homeApps", 6) { page = 12 } }
                12 -> Page { Title("Lock-screen apps"); PickList("quickApps", 3) { page = 13 } }
                13 -> Page {
                    Title("Cards (optional)")
                    T("Add loyalty cards now or later in Settings → Cards.", 15.sp, Dim)
                    Btn("Add cards") { startActivity(Intent(this@OnboardingActivity, SettingsActivity::class.java).putExtra("page", "cards")) }
                    Btn("Next") { page = 14 }
                }
                14 -> Page { Title("Permissions"); Permissions(this@OnboardingActivity); Btn("Next") { page = 15 } }
                15 -> BaselineChoice()
                100 -> SasPage(optional = false) { finish() }
            } } }
        }
    }

    /** Defaults that must exist before anything else: Chrome site list, auto-labels, chats-free modes. */
    private fun firstRunDefaults() {
        if (Store.get("domainsInit") == null) {
            Store.setAll(Detectors.defaultDomains.mapKeys { "domain.${it.key}" } + ("domainsInit" to "1"))
        }
        val labels = HashMap<String, String?>()
        for (app in Apps.all(this)) if (Store.get("label.${app.pkg}") == null) Cats.autoLabel(this, app.pkg)?.let { labels["label.${app.pkg}"] = it }
        Store.setAll(labels)
        // Existing apps with no auto-label are labelled on the setup screen; until then: Other.
        if (Store.get("setupAt") == null) Store.set("setupAt", Clock.now().toString())
    }

    // ---------------- quiz ----------------
    private val goalsOpts = listOf("Have more time", "Time drain", "Sleep", "Attention span", "Compulsive checking", "Procrastination",
        "Anxiety and low mood", "Comparison", "Doomscrolling stress", "Real life feels boring", "Weaker self-control",
        "Validation dependence", "Worse learning", "Loneliness", "Half-present with people", "Hijacked mornings", "FOMO",
        "Algorithm-shaped opinions", "Fewer real skills", "Lost sense of time", "No boredom, no thinking")
    private val pullOpts = listOf("Endless feeds", "Likes & comments", "Streaks", "Stories", "Waiting for replies")
    private val whenOpts = listOf("In bed", "Right after waking", "School breaks", "While studying", "When bored")
    private val forOpts = listOf("Messages", "A video", "Checking something", "Nothing specific")
    private val otherRules = listOf("block a feed", "earlier night block", "send to digest", "suggest focus mode", "just note it")

    @Composable private fun Welcome() = Page {
        Title("Stillhome")
        T("A quiet launcher that guards the apps that pull you in. 8 quick questions (~3 min), then setup.", 17.sp)
        T("Nothing leaves this phone. The app has no internet permission.", 14.sp, Dim)
        Spacer(Modifier.height(20.dp))
        Btn("Start") { page = 1 }
    }

    @Composable private fun Rule(text: String) = T("→ $text", 14.sp, White)

    /** Hour answers by sliding in 15-minute steps; stored as hours ("2.25") like the old chips. */
    @Composable private fun HoursSlider(key: String, options: List<Int>, default: Int) {
        androidx.compose.runtime.LaunchedEffect(key) { if (a[key] == null) a[key] = hoursOf(default) }
        val cur = a[key]?.toDoubleOrNull()?.let { (it * 60).toInt() } ?: default
        TimeSlider(options, cur, onChange = { a[key] = hoursOf(it) })
    }
    private fun hoursOf(min: Int) = (min / 60.0).toString().removeSuffix(".0")

    @Composable private fun OtherField(key: String) {
        val v = a["$key.other"] ?: ""
        Field(v, { a["$key.other"] = it }, "Other: ___", singleLine = true)
        if (v.isNotBlank()) {
            T("Which rule should this create?", 14.sp, Dim)
            Chips(otherRules, setOfNotNull(a["$key.otherRule"])) { a["$key.otherRule"] = it }
            a["$key.otherRule"]?.let { Rule(otherRuleText(it)) }
        }
    }

    private fun otherRuleText(r: String) = when (r) {
        "block a feed" -> "Social budget set to the lowest option (15 min)"
        "earlier night block" -> "Night block starts 30 min earlier"
        "send to digest" -> "Social likes/comments/follows go to the digest"
        "suggest focus mode" -> "“focus” shortcut on home"
        else -> "Noted in your weekly review"
    }

    @Composable private fun Question(q: Int) = Page {
        T("$q / 8", 13.sp, Dim)
        when (q) {
            1 -> {
                Title("How many hours a day do you think you spend on your phone?")
                HoursSlider("guess", (1..48).map { it * 15 },
                    Store.str("quiz.guessH", "").toDoubleOrNull()?.let { ((it * 4).roundToInt() * 15).coerceIn(15, 720) } ?: 180)   // 15 min to 12 h
                (a["guess"] ?: "").let { Rule("Shown next to your real number after the baseline week") }
            }
            2 -> {
                Title("How many hours a day do you want?")
                HoursSlider("want", (2..24).map { it * 15 }, Store.int("targetMin", 120).coerceIn(30, 360))   // retake keeps your goal   // 30 min to 6 h
                (a["want"] ?: hoursOf(Store.int("targetMin", 120))).let { Rule("Goal ${hoursText(it)}: on home + lock screen; weekly auto-tighten stops once your average is below it") }
            }
            3 -> {
                Title("What do you want to improve?")
                val s = m["goals"] ?: emptySet()
                Chips(goalsOpts, s) { m["goals"] = if (it in s) s - it else s + it }
                if (s.isNotEmpty() || !a["q3.other"].isNullOrBlank()) Rule("Each goal gets a 1–5 rating in the Sunday review")
            }
            4 -> {
                Title("What pulls you most?")
                val s = m["pull"] ?: emptySet()
                Chips(pullOpts, s) { m["pull"] = if (it in s) s - it else s + it }
                if ("Endless feeds" in s) {
                    T("Which feeds?", 14.sp, Dim)
                    val f = m["feeds"] ?: setOf(Cats.SOCIAL)
                    Chips(listOf(Cats.SOCIAL, Cats.VIDEO, Cats.SHOPS, Cats.GAMES), f) { m["feeds"] = if (it in f) f - it else f + it }
                    Rule("${f.joinToString()} budget → lowest option (${f.joinToString { "${Limits.of(it).budgets.first()} min" }})")
                }
                if ("Likes & comments" in s) {
                    Rule("Like/comment/follow notifications → Drop")
                    T("Also: Instagram → Settings → Likes and shares → Hide like and share counts.", 13.sp, Dim)
                }
                if ("Streaks" in s) {
                    Chips(listOf("keep them", "let them die"), setOfNotNull(a["streaks"])) { a["streaks"] = it }
                    if (a["streaks"] == "let them die") Rule("Streak notifications → Drop") else Rule("Streak notifications → Digest")
                }
                if ("Stories" in s) Rule("Story notifications → Digest")
                if ("Waiting for replies" in s) {
                    Rule("Checklist: turn off read receipts")
                    T("Instagram: Settings → Messages and story replies → Show read receipts: off\nWhatsApp: Settings → Privacy → Read receipts: off\n" +
                        "Messenger: Settings → Privacy & safety → Read receipts: off\nSnapchat: not possible", 13.sp, Dim)
                }
            }
            5 -> {
                Title("When do you scroll most?")
                val s = m["when"] ?: emptySet()
                Chips(whenOpts, s) { m["when"] = if (it in s) s - it else s + it }
                if ("In bed" in s) Rule("Night block starts 30 min earlier (22:30)")
                if ("Right after waking" in s) Rule("Night block ends 1 h later (09:00)")
                if ("School breaks" in s) Rule("School block hours: confirm on the summary page")
                if ("While studying" in s) Rule("“focus” shortcut on home")
                if ("When bored" in s) Rule("Gate countdown 20 s")
            }
            6 -> {
                Title("What do you open the phone for, then end up scrolling?")
                val s = m["for"] ?: emptySet()
                Chips(forOpts, s) { m["for"] = if (it in s) s - it else s + it }
                if ("Messages" in s) Rule("Instagram & Snapchat: chats free, feeds metered")
                if ("A video" in s) Rule("Video budget → lowest option (30 min)")
                if ("Checking something" in s) Rule("Gate reason minimum 30 characters")
                if ("Nothing specific" in s) Rule("Gate countdown 20 s")
            }
            7 -> {
                Title("Who or what should never be filtered?")
                T("Apps", 14.sp, Dim)
                val s = m["never"] ?: Store.list("neverFilterApps").toSet()
                val chatApps = Apps.all(this@OnboardingActivity).filter { Cats.of(it.pkg) in setOf(Cats.CHAT, Cats.SCHOOL, Cats.FINANCE, Cats.TOOLS) }
                Chips(chatApps.map { it.label }, chatApps.filter { it.pkg in s }.map { it.label }.toSet()) { l ->
                    val p = chatApps.first { it.label == l }.pkg; m["never"] = if (p in s) s - p else s + p }
                T("Contacts (names as they appear in notifications, comma separated)", 14.sp, Dim)
                Field(a["contacts"] ?: Store.list("neverFilterContacts").joinToString(", "), { a["contacts"] = it }, "Mom, Dad")
                Rule("Always ping, whatever else is set")
            }
        }
        Spacer(Modifier.height(16.dp))
        Btn("Next") { page = q + 1 }
    }

    @Composable private fun SasPage(optional: Boolean, done: () -> Unit) {
        val ans = remember { mutableStateMapOf<Int, Int>() }
        Page {
            T("8 / 8", 13.sp, Dim)
            Title("Self-check (SAS-SV)")
            T("Smartphone Addiction Scale – Short Version (Kwon et al., 2013). Personal trend, not a diagnosis." +
                if (optional) " Optional today; required every 4 weeks." else "", 13.sp, Dim)
            sasItems.forEachIndexed { i, q ->
                T("${i + 1}. $q", 16.sp, White)
                Chips(sasScale.mapIndexed { j, s -> "${j + 1} $s" }, setOfNotNull(ans[i]?.let { "$it ${sasScale[it - 1]}" })) { ans[i] = it.substringBefore(' ').toInt() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Btn("Save", enabled = ans.size == 10) {
                    App.db.dao().insertEvent(EventRow(time = Clock.now(), type = "sas", text = ans.values.sum().toString()))
                    done()
                }
                if (optional) Btn("Skip") { done() }
            }
        }
    }

    /** Turn the answers into concrete settings. */
    private fun proposal(school: Window): List<Triple<String, String?, String>> {
        val out = ArrayList<Triple<String, String?, String>>()
        val pull = m["pull"] ?: emptySet(); val wh = m["when"] ?: emptySet(); val fo = m["for"] ?: emptySet()
        val others = (1..7).mapNotNull { a["q$it.otherRule"] }
        a["want"]?.let { out += Triple("targetMin", ((it.toDouble()) * 60).toInt().toString(), "Goal ${hoursText(it)}/day") }
        a["guess"]?.let { Store.set("quiz.guessH", it) }
        if ("Endless feeds" in pull) (m["feeds"] ?: setOf(Cats.SOCIAL)).forEach {
            out += Triple("budget.$it", Limits.of(it).budgets.first().toString(), "$it budget → lowest (quiz: endless feeds)") }
        if ("block a feed" in others) out += Triple("budget.${Cats.SOCIAL}", "15", "Social budget → 15 min (quiz: other)")
        if ("A video" in fo) out += Triple("budget.${Cats.VIDEO}", "30", "Video budget → 30 min (quiz: a video)")
        if ("Checking something" in fo) out += Triple("reasonMin", "30", "Reason minimum 30 chars")
        if ("When bored" in wh || "Nothing specific" in fo) out += Triple("countdown", "20", "Gate countdown 20 s")
        if ("Messages" in fo) Detectors.withDetector.forEach { out += Triple("mode.$it", "chats", "${Apps.label(this, it)}: chats free") }
        // blocked hours: night window (+ quiz adjustments) and school window
        val nightStart = 23 * 60 - (if ("In bed" in wh || "earlier night block" in others) 30 else 0)
        val nightEnd = 8 * 60 + (if ("Right after waking" in wh) 60 else 0)
        val ws = listOf(Window((1..7).toSet(), nightStart, nightEnd), school)
        out += Triple("blocked", Blocked.encode(ws), "Blocked hours: " + ws.joinToString(", ") { it.label() })
        return out
    }

    @Composable private fun Summary() {
        var schoolStart by remember { mutableIntStateOf(8 * 60) }
        var schoolEnd by remember { mutableIntStateOf(15 * 60) }
        val school = Window((1..5).toSet(), schoolStart, schoolEnd)
        val props = proposal(school)
        Page {
            Title("Your rules")
            props.forEach { T("• " + it.third, 15.sp) }
            val pull = m["pull"] ?: emptySet()
            if ("Likes & comments" in pull || "send to digest" in (1..7).mapNotNull { a["q$it.otherRule"] })
                T("• Likes/comments/follows → ${if ("Likes & comments" in pull) "Drop" else "Digest"}", 15.sp)
            if ("Stories" in pull) T("• Story notifications → Digest", 15.sp)
            if (a["streaks"] == "let them die") T("• Streak notifications → Drop", 15.sp)
            if ("While studying" in (m["when"] ?: emptySet()) || "suggest focus mode" in (1..7).mapNotNull { a["q$it.otherRule"] })
                T("• “focus” shortcut on home", 15.sp)
            val goals = (m["goals"] ?: emptySet()) + listOfNotNull(a["q3.other"]?.takeIf { it.isNotBlank() })
            if (goals.isNotEmpty()) T("• Weekly 1–5 rating: ${goals.joinToString()}", 15.sp)
            T("• Defaults: Social 45 · Video 60 · Games 45 · Shops 15 min/day, cooldown 2×, auto-tighten −5 min/week", 15.sp, Dim)
            Section("School hours (Mon–Fri, editable)")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                T("From ${fmtHm(schoolStart)}", 17.sp); T("−", 20.sp, White, Modifier.clickableNoRipple { schoolStart -= 30 }); T("+", 20.sp, White, Modifier.clickableNoRipple { schoolStart += 30 })
                Spacer(Modifier.width(10.dp))
                T("to ${fmtHm(schoolEnd)}", 17.sp); T("−", 20.sp, White, Modifier.clickableNoRipple { schoolEnd -= 30 }); T("+", 20.sp, White, Modifier.clickableNoRipple { schoolEnd += 30 })
            }
            Spacer(Modifier.height(16.dp))
            Btn("Apply") {
                // Only one looser change can wait at a time (Rules.change replaces it), so: tightenings apply now,
                // at most ONE loosening is queued, and an existing pending change is never replaced silently.
                val todo = props.filter { (k, v, _) -> !Rules.same(k, v) }   // unchanged: leave any pending edit of it alone
                val (loose, tight) = todo.partition { (k, v, _) -> Rules.wouldWait(k, v) }
                val before = Rules.current()
                tight.forEach { (k, v, d) -> Rules.change(k, v, d) }
                val replaced = before?.takeIf { b -> tight.any { it.first == b.key } }   // a new edit of its key replaced it
                val stillWaiting = Rules.current()
                val queued = if (stillWaiting == null) loose.firstOrNull()?.also { (k, v, d) -> Rules.change(k, v, d) } else null
                val skipped = loose.filter { it !== queued }
                applyFlags(goals)
                val msg = buildList {
                    replaced?.let { add("Replaced the waiting change “${it.description}”.") }
                    queued?.let { add("Waiting 1 h: ${it.third}.") }
                    if (skipped.isNotEmpty()) add(
                        (if (queued == null && stillWaiting != null) "Already waiting: “${stillWaiting.description}”. " else "") +
                        "Only one looser change can wait at a time, so not queued: " + skipped.joinToString("; ") { it.third } +
                        ". Change them in Settings later.")
                }
                if (msg.isNotEmpty()) Toast.makeText(this@OnboardingActivity, msg.joinToString(" "), Toast.LENGTH_LONG).show()
                page = if (Store.bool("setupDone", false)) { finish(); 9 } else 10
            }
        }
    }

    /** Notification/home flags apply instantly (section 17: notification settings aren't rules). */
    private fun applyFlags(goals: Set<String>) {
        val pull = m["pull"] ?: emptySet(); val wh = m["when"] ?: emptySet()
        val others = (1..7).mapNotNull { a["q$it.otherRule"] }
        Store.setAll(mapOf(
            "quiz.likesDrop" to if ("Likes & comments" in pull) "1" else "0",
            "quiz.streaksDie" to if (a["streaks"] == "let them die") "1" else "0",
            "quiz.storiesDigest" to if ("Stories" in pull) "1" else "0",
            "focusShortcut" to if ("While studying" in wh || "suggest focus mode" in others) "1" else Store.str("focusShortcut", "0"),
        ))
        if ("send to digest" in others) listOf("like", "comment", "follow").forEach { k -> Detectors.withDetector.forEach { Store.set("nkind.$it.$k", "Digest") } }
        Store.setList("goals", goals.toList())
        (m["never"])?.let { Store.setList("neverFilterApps", it.toList()) }
        a["contacts"]?.let { Store.setList("neverFilterContacts", it.split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }) }
        Store.setList("quiz.notes", (1..7).mapNotNull { q -> a["q$q.other"]?.takeIf { it.isNotBlank() }?.let { "Q$q: $it" } })
    }

    // ---------------- setup screens ----------------
    @Composable private fun LabelSetup() = Page {
        Title("Label your apps")
        T("Apps we couldn't recognise. Tap a label for each (default: Other).", 14.sp, Dim)
        val unl = remember { Apps.all(this@OnboardingActivity).filter { Cats.of(it.pkg) == Cats.UNLABELED } }
        val picks = remember { mutableStateMapOf<String, String>() }
        unl.forEach { app ->
            T(app.label, 17.sp, White)
            Chips(Cats.all(), setOfNotNull(picks[app.pkg] ?: Store.get("label.${app.pkg}"))) { picks[app.pkg] = it }
        }
        Btn("Next") {
            unl.forEach { app -> Store.set("label.${app.pkg}", picks[app.pkg] ?: Store.get("label.${app.pkg}") ?: Cats.OTHER) }
            page = 11
        }
    }

    @Composable private fun PickList(key: String, max: Int, next: () -> Unit) {
        var cur by remember { mutableStateOf(Store.list(key)) }
        T("Up to $max. Apps from gated categories can't be pinned.", 14.sp, Dim)
        val apps = Apps.all(this).filter { !Cats.gatedAtLaunch(this, it.pkg) }
        Chips(apps.map { it.label }, apps.filter { it.pkg in cur }.map { it.label }.toSet()) { l ->
            val p = apps.first { it.label == l }.pkg
            cur = if (p in cur) cur - p else if (cur.size < max) cur + p else cur
            Store.setList(key, cur)
        }
        Btn("Next") { next() }
    }

    @Composable private fun BaselineChoice() = Page {
        Title("Baseline week")
        T("Recommended: 7 days of tracking only. The Gate still asks for reasons, but no budgets, cooldowns or blocked hours. " +
            "Then budgets are set to 70% of what you really use, and lockdown arms.", 15.sp)
        T("Or skip: defaults apply now and lockdown arms in 48 h.", 15.sp, Dim)
        Spacer(Modifier.height(12.dp))
        Btn("Start baseline week") { finishSetup(baseline = true) }
        Btn("Skip baseline") { finishSetup(baseline = false) }
    }

    private fun finishSetup(baseline: Boolean) {
        val now = Clock.now()
        Store.setAll(mapOf(
            "phase" to if (baseline) "baseline" else "active",
            "baselineStart" to now.toString(),
            "lockdownAt" to (now + if (baseline) 7 * 86400_000L else 48 * 3600_000L).toString(),
            "setupDone" to "1",
        ))
        Guard.log("phase", if (baseline) "Baseline started" else "Baseline skipped; lockdown in 48 h")
        Toast.makeText(this, "Lockdown arms ${Clock.day(Store.long("lockdownAt", now))} — test RECOVERY.md first", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MainActivity::class.java)); finish()
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit) = this.clickable(onClick = onClick)
