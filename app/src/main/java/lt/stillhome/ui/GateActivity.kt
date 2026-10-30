package lt.stillhome.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import lt.stillhome.App
import lt.stillhome.core.Apps
import lt.stillhome.core.Cats
import lt.stillhome.core.Clock
import lt.stillhome.core.Decision
import lt.stillhome.core.Guard
import lt.stillhome.core.Similar
import lt.stillhome.core.fmtDur
import lt.stillhome.core.fmtMmSs
import lt.stillhome.data.Store
import lt.stillhome.service.GuardService

/**
 * Section 5/6. One full-screen black activity with four modes:
 *  gate    - reason + duration + countdown, then Open
 *  lock    - why it's locked + time remaining (no override of any kind)
 *  timesup - "Time's up" then the intent check
 *  intent  - just the intent check (when time ran out while I was elsewhere)
 */
class GateActivity : BlackActivity() {
    private var extras by mutableStateOf(Bundle())
    private var nonce by mutableIntStateOf(0)   // bumps on every new intent: fresh reason, countdown...

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extras = intent.extras ?: Bundle()
        setContent { androidx.compose.runtime.key(nonce) { GateRoot(extras, ::home, ::finish) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); extras = intent.extras ?: Bundle(); nonce++ }

    private fun home() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

@Composable
private fun GateRoot(x: Bundle, home: () -> Unit, finish: () -> Unit) {
    val target = x.getString("target") ?: ""
    val pkg = x.getString("pkg") ?: target
    when (x.getString("mode") ?: "gate") {
        "timesup", "intent" -> IntentCheck(x.getLong("session"), x.getString("mode") == "timesup", home)
        "lock" -> LockPage(target, pkg, x.getString("cat") ?: Cats.of(pkg), x.getString("why") ?: "", x.getLong("until"), home)
        else -> GatePage(target, pkg, x.getString("cat") ?: Cats.of(pkg), home, finish)
    }
}

private fun displayName(ctx: android.content.Context, target: String) =
    if (target.startsWith("chrome:")) target.removePrefix("chrome:") else Apps.label(ctx, target)

@Composable
private fun GatePage(target: String, pkg: String, cat: String, home: () -> Unit, finish: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val name = remember(target) { displayName(ctx, target) }
    val decision = remember(target) { Guard.decide(target, pkg, cat) }
    BackHandler { home() }

    // Already allowed (e.g. session started from another path) or now locked: route accordingly.
    when (decision) {
        Decision.Allow -> { LaunchedEffect(Unit) { Apps.launch(ctx, pkg); finish() }; return }
        is Decision.Locked -> { LockPage(target, pkg, cat, decision.why, decision.until, home); return }
        is Decision.Gate -> {}
    }
    decision as Decision.Gate

    val dao = App.db.dao()
    val mine = remember(target) { dao.lastReasonsFor(target, 3) }
    val recent5 = remember { dao.lastReasons(5) }
    val minChars = Store.int("reasonMin", 20)
    var reasonCat by remember { mutableStateOf("") }
    var otherCat by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var minutes by remember { mutableIntStateOf(0) }
    var left by remember { mutableIntStateOf(Store.int("countdown", 10)) }
    LaunchedEffect(target) { while (left > 0) { delay(1000); left-- } }

    val tooShort = reason.trim().length < minChars
    val similar = !tooShort && Similar.tooSimilar(reason, recent5)
    val catOk = reasonCat.isNotEmpty() && (reasonCat != "Other" || otherCat.isNotBlank())
    val valid = catOk && !tooShort && !similar && minutes > 0

    Page {
        Title(name)
        if (Guard.inBaseline()) T("Baseline week: no limits yet, reasons are logged.", 13.sp, Dim)
        Section("Reason")
        Chips(listOf("friends", "school", "posting", "boredom", "Other"), setOf(reasonCat)) { reasonCat = it }
        if (reasonCat == "Other") Field(otherCat, { otherCat = it }, "Other: ___", singleLine = true)
        Spacer(Modifier.height(8.dp))
        mine.forEach { T("“$it”", 14.sp, Faint) }
        T("Why are you opening $name?", 18.sp, White)
        Field(reason, { reason = it }, "at least $minChars characters", noPaste = true)
        when {
            reason.isNotEmpty() && tooShort -> T("${minChars - reason.trim().length} more characters", 13.sp, Dim)
            similar -> T("Too close to one of your last 5 reasons. Say what you'll actually do.", 13.sp, Red)
        }
        Section("How long")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            decision.options.forEach { m -> Chip("$m min", minutes == m) { minutes = m } }
        }
        if (decision.remainingMin != Int.MAX_VALUE) T("${decision.remainingMin} min of ${Cats.budgetCat(cat)} left today", 13.sp, Dim)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Btn(if (left > 0) "Open ($left)" else "Open", enabled = valid && left == 0) {
                val rc = if (reasonCat == "Other") "other: ${otherCat.trim()}" else reasonCat
                Guard.startSession(target, pkg, cat, minutes, rc, reason.trim())
                GuardService.refresh(ctx)
                Apps.launch(ctx, if (target.startsWith("chrome:")) Cats.CHROME else pkg)
                finish()
            }
            Btn("Cancel") { home() }
        }
    }
}

@Composable
private fun LockPage(target: String, pkg: String, cat: String, why: String, until: Long, home: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var now by remember { mutableLongStateOf(Clock.now()) }
    LaunchedEffect(until) { while (true) { delay(1000); now = Clock.now() } }
    BackHandler { home() }
    if (now >= until) {   // lock over: show the normal Gate
        GatePage(target, pkg, cat, home) { (ctx as? android.app.Activity)?.finish() }
        return
    }
    Center {
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            T(displayName(ctx, target), 26.sp, White)
            T(why, 18.sp, Bright)
            val ms = until - now
            T(if (ms > 3600_000) "${fmtDur(ms)} left" else "${fmtMmSs(ms)} left", 40.sp, White)
            Spacer(Modifier.height(20.dp))
            Btn("Home") { home() }
        }
    }
}

@Composable
private fun IntentCheck(sessionId: Long, showTimesUp: Boolean, home: () -> Unit) {
    val s = remember(sessionId) { App.db.dao().session(sessionId) }
    var stage by remember { mutableIntStateOf(if (showTimesUp) 0 else 1) }
    BackHandler { }   // answer first
    LaunchedEffect(stage) { if (stage == 0) { delay(1500); stage = 1 } }
    Center {
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier) {
            if (stage == 0) { T("Time's up", 34.sp, White); return@Column }
            T("Did you do what you wrote?", 22.sp, White)
            s?.let { T("“${it.reason}”", 16.sp, Dim) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Yes", "Partly", "No").forEach { a ->
                    Btn(a) {
                        if (s != null) App.db.dao().setIntent(s.id, a)
                        Store.set("intentCheck", null)
                        home()
                    }
                }
            }
        }
    }
}
