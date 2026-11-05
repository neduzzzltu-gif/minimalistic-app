package lt.stillhome.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import lt.stillhome.App
import lt.stillhome.core.Clock
import lt.stillhome.core.Rules
import lt.stillhome.core.fmtMmSs
import lt.stillhome.data.Store

/*
 * Settings pieces for the pending-change rule and the daily essay. Kept out of SettingsActivity.kt
 * so Settings can be reorganised separately; drop them wherever they belong.
 */

/** "⏳ answer in 43:10" / "⏳ waiting for your answer" for a key with a pending change, else "". */
internal fun pendingMark(key: String): String = Rules.pendingFor(key)?.let {
    val left = Rules.leftMs(it)
    if (left <= 0) "⏳ waiting for your answer" else "⏳ you can answer in ${fmtMmSs(left)}"
} ?: ""

/** Top of Settings: the one pending change with Apply (only once the hour is over) and Cancel. */
@Composable
internal fun PendingBanner(onMessage: (String) -> Unit = {}) {
    val list by remember { App.db.dao().pendingFlow() }.collectAsState(initial = emptyList())
    val c = list.firstOrNull() ?: return
    var now by remember { mutableLongStateOf(Clock.now()) }
    LaunchedEffect(c.id) { while (true) { now = Clock.now(); delay(1000) } }
    val due = Rules.isDue(c, now)
    SetGroup {
        Inner {
            T("Waiting change", 13.sp, Dim)
            T(c.description, 17.sp, White)
            T(if (due) "Waiting for your answer" else "You can answer in ${fmtMmSs(Rules.leftMs(c, now))}", 14.sp, Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MainBtn("Apply", enabled = due) { onMessage(if (Rules.apply(c.id)) "Applied" else "Not yet") }
                MainBtn("Cancel", primary = false) { Rules.cancel(c.id); onMessage("Cancelled") }
            }
        }
    }
}

/** Daily essay on/off. Off is loosening (pending, asked again after 1 h); on applies at once. */
@Composable
internal fun EssayToggle(onMessage: (String) -> Unit = {}) {
    val map by Store.map.collectAsState()
    val pend by remember { App.db.dao().pendingFlow() }.collectAsState(initial = emptyList())   // re-reads pendingMark
    val on = map["essay"] != "0"
    val mark = remember(pend, map) { pendingMark("essay") }
    ToggleRow("Daily essay", sub = mark.ifEmpty { "200 words, once a day" }, on = on, onToggle = {
        if (on) onMessage(if (Rules.change("essay", "0", "Daily essay → off")) "Daily essay off"
            else "Waiting 1 h, then you'll be asked to confirm")
        else { Rules.change("essay", "1", "Daily essay → on"); onMessage("Daily essay on") }
    })
}

/** Past essays, newest first: date + word count, tap to read the full text. */
@Composable
internal fun EssayHistory() {
    val rows by remember { App.db.dao().essaysFlow() }.collectAsState(initial = emptyList())
    var open by remember { mutableStateOf<Long?>(null) }
    if (rows.isEmpty()) { T("No essays yet.", 14.sp, Dim, Modifier.padding(start = 4.dp)); return }
    SetGroup {
        rows.forEachIndexed { i, r ->
            if (i > 0) SetDivider0()
            ValueRow(r.day, "${r.words} words", sub = r.prompt) { open = if (open == r.id) null else r.id }
            if (open == r.id) Inner { T(r.text, 15.sp, Bright, lineHeight = 22.sp) }
        }
    }
}
