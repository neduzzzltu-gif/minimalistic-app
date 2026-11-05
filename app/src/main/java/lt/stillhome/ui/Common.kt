package lt.stillhome.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.stillhome.core.Rules
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import android.view.HapticFeedbackConstants
import kotlin.math.roundToInt

val Black = Color(0xFF000000)
val Bright = Color(0xFFE0E0E0)
val White = Color(0xFFFFFFFF)
val Dim = Color(0xFF808080)
val Faint = Color(0xFF3A3A3A)
val Red = Color(0xFFFF5252)

/** Bundled Inter, so text never falls back to the phone's theme font. */
val Inter = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(lt.stillhome.R.font.inter_regular, FontWeight.Normal),
    androidx.compose.ui.text.font.Font(lt.stillhome.R.font.inter_medium, FontWeight.Medium),
    androidx.compose.ui.text.font.Font(lt.stillhome.R.font.inter_semibold, FontWeight.SemiBold),
    androidx.compose.ui.text.font.Font(lt.stillhome.R.font.inter_bold, FontWeight.Bold),
)

/** Edge-to-edge, transparent status bar, white icons, black everywhere. */
fun ComponentActivity.blackWindow() {
    enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.BLACK))
    window.setBackgroundDrawableResource(android.R.color.black)
}

@Composable
fun T(text: String, size: TextUnit = 16.sp, color: Color = Bright, modifier: Modifier = Modifier, bold: Boolean = false, lineHeight: TextUnit = TextUnit.Unspecified) =
    Text(text, modifier = modifier, color = color, fontSize = size, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
        fontFamily = Inter, lineHeight = lineHeight)

/** Full-screen black page with optional scrolling column. */
@Composable
fun Page(scroll: Boolean = true, state: androidx.compose.foundation.ScrollState? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Black).safeDrawingPadding().imePadding()) {
        // [state] from the caller keeps the scroll position across rebuilds (Settings pages).
        val st = state ?: rememberScrollState()
        Column(
            Modifier.fillMaxSize().then(if (scroll) Modifier.verticalScroll(st) else Modifier)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

@Composable
fun Title(text: String) { T(text, 24.sp, White, bold = true); Spacer(Modifier.height(6.dp)) }

@Composable
fun Section(text: String) { Spacer(Modifier.height(12.dp)); T(text, 14.sp, Dim) }

/** Outlined, rounded text button. */
@Composable
fun Btn(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.border(1.5.dp, if (enabled) White else Faint, RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, 16.sp, if (enabled) White else Faint) }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.border(1.dp, if (selected) White else Faint, RoundedCornerShape(18.dp))
            .background(if (selected) Color(0xFF1C1C1C) else Black, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { T(text, 15.sp, if (selected) White else Dim) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(options: List<String>, selected: Set<String>, onClick: (String) -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { Chip(it, it in selected) { onClick(it) } }
    }

/** A tappable settings row (StayFree-style list row): label left, value right (dim). */
@Composable
fun RowItem(label: String, value: String = "", color: Color = White, onClick: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 8.dp),
        contentAlignment = Alignment.CenterStart) {
        T(label, 16.sp, color, Modifier.align(Alignment.CenterStart).padding(end = 120.dp))
        if (value.isNotEmpty()) T(value, 14.sp, Dim, Modifier.align(Alignment.CenterEnd))
    }
}

/** On/off switch in the app's colours: on = white track + black knob, off = dark track with grey outline + grey knob. */
@Composable
fun Toggle(on: Boolean, onClick: () -> Unit) {
    val x by androidx.compose.animation.core.animateDpAsState(if (on) 24.dp else 4.dp, label = "knob")
    Box(Modifier.size(width = 52.dp, height = 32.dp).clip(CircleShape)
        .background(if (on) White else Color(0xFF1C1C1C)).border(1.5.dp, if (on) White else Faint, CircleShape)
        .clickable(onClick = onClick)) {
        Box(Modifier.padding(start = x).align(Alignment.CenterStart).size(22.dp).clip(CircleShape).background(if (on) Black else Dim))
    }
}

/** List row with a title, a dim second part ("& 3 other"), an optional line under it and a switch on the right. */
@Composable
fun ToggleRow(title: String, extra: String = "", sub: String = "", on: Boolean, onToggle: () -> Unit, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(title, 16.sp, White)
                if (extra.isNotEmpty()) { Spacer(Modifier.width(8.dp)); T(extra, 14.sp, Dim) }
            }
            if (sub.isNotEmpty()) T(sub, 13.sp, Dim)
        }
        Toggle(on, onToggle)
    }
}

/** Grey section header like "Blocked (5)" with a chevron that folds the section. */
@Composable
fun Header(text: String, open: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        T(text, 14.sp, Dim, Modifier.weight(1f))
        androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {   // chevron: ^ open, v closed
            val w = size.width; val h = size.height; val sw = 2.dp.toPx()
            val (a, b) = if (open) 0.7f to 0.3f else 0.3f to 0.7f
            drawLine(White, androidx.compose.ui.geometry.Offset(w * 0.1f, h * a), androidx.compose.ui.geometry.Offset(w * 0.5f, h * b), sw, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(White, androidx.compose.ui.geometry.Offset(w * 0.5f, h * b), androidx.compose.ui.geometry.Offset(w * 0.9f, h * a), sw, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** Rounded search field like the home one: thin grey outline, magnifier, hint. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String) {
    BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(White, 16.sp, fontFamily = Inter), cursorBrush = SolidColor(White),
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().height(50.dp).border(1.5.dp, Faint, CircleShape).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                    val r = size.minDimension * 0.33f
                    drawCircle(Dim, r, androidx.compose.ui.geometry.Offset(r + 2f, r + 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                    drawLine(Dim, androidx.compose.ui.geometry.Offset(r * 1.75f + 2f, r * 1.75f + 2f), androidx.compose.ui.geometry.Offset(size.width - 2f, size.height - 2f), 1.5.dp.toPx())
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) { if (value.isEmpty()) T(hint, 16.sp, Faint); inner() }
            }
        })
}

/** Toolbar that never offers Paste (used for the Gate reason field). */
private object NoToolbar : TextToolbar {
    override val status = TextToolbarStatus.Hidden
    override fun hide() {}
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {}
}

/**
 * Single text field. noPaste = no paste menu AND big one-shot insertions are rejected
 * (keyboard clipboard chips paste whole strings; normal typing adds 1 char, suggestions ~1 word).
 */
@Composable
fun Field(value: String, onChange: (String) -> Unit, hint: String = "", noPaste: Boolean = false, singleLine: Boolean = false,
          modifier: Modifier = Modifier, size: TextUnit = 18.sp) {
    val field = @Composable {
        BasicTextField(
            value = value,
            onValueChange = { nv -> if (!noPaste || typedInsert(value, nv)) onChange(nv) },
            singleLine = singleLine,
            textStyle = TextStyle(color = White, fontSize = size),
            cursorBrush = SolidColor(White),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C1C)).padding(14.dp),
            decorationBox = { inner -> Box { if (value.isEmpty()) T(hint, size, Faint); inner() } },
        )
    }
    if (noPaste) CompositionLocalProvider(LocalTextToolbar provides NoToolbar) { field() } else field()
}

/**
 * Paste guard: an edit may add up to 15 chars, or up to 30 if the inserted part is one word
 * (glide typing commits long LT words like "nepriklausomybės " at once). Bigger / multi-word = paste.
 */
fun typedInsert(old: String, new: String): Boolean {
    val d = new.length - old.length
    if (d <= 15) return true
    if (d > 30) return false
    val p = old.commonPrefixWith(new).length
    val s = minOf(old.length - p, new.length - p).let { max -> old.reversed().commonPrefixWith(new.reversed()).length.coerceAtMost(max) }
    return new.substring(p, new.length - s).trim().none { it.isWhitespace() }
}

@Composable
fun Center(content: @Composable BoxScope.() -> Unit) =
    Box(Modifier.fillMaxSize().background(Black).safeDrawingPadding(), contentAlignment = Alignment.Center, content = content)

/** Small helper so every rule edit shows whether it applied or is waiting 1 h. */
fun Activity.ruleChange(key: String, value: String?, description: String): String =
    if (Rules.change(key, value, description)) "Applied" else "Saved. Loosening: you'll be asked to confirm in 1 h"

abstract class BlackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); blackWindow() }
}

/**
 * Pick a time period by sliding: snaps to the allowed [options] (minutes, sorted), big live value above.
 * [onChange] fires on every snap step while sliding; [onDone] once the finger lifts (use it for saved settings).
 * Drawn by hand to fit the black screens: thin track, hour ticks, a white knob that swells while held, a haptic tick per step.
 */
@Composable
fun TimeSlider(options: List<Int>, value: Int, onChange: (Int) -> Unit, onDone: (Int) -> Unit = {}) {
    if (options.isEmpty()) return
    val last = options.lastIndex
    fun indexOf(v: Int) = options.indexOfFirst { it >= v }.let { if (it < 0) last else it }
    val idx = remember(options) { mutableIntStateOf(indexOf(value)) }
    val held = remember { mutableStateOf(false) }
    // Parent changed the value from outside (not while the finger is down): follow it.
    LaunchedEffect(value, options) { if (!held.value) idx.intValue = indexOf(value) }
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val view = LocalView.current
    val cur = options[idx.intValue.coerceIn(0, last)]
    val frac by animateFloatAsState(if (last == 0) 0f else idx.intValue / last.toFloat(),
        spring(stiffness = Spring.StiffnessHigh), label = "pos")
    val knob by animateDpAsState(if (held.value) 26.dp else 20.dp, spring(stiffness = Spring.StiffnessMedium), label = "knob")
    val halo by animateFloatAsState(if (held.value) 1f else 0f, label = "halo")
    // Ticks only where they mean something: every hour on long ranges, every step on short ones.
    val span = options.last() - options.first()
    val ticks = options.indices.filter { i -> if (last <= 12) true else span >= 120 && options[i] % 60 == 0 }
    Column(Modifier.fillMaxWidth()) {
        Text(lt.stillhome.core.fmtDur(cur * 60_000L), color = White, fontSize = 30.sp, fontFamily = Inter, fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.5).sp, style = TextStyle(fontFeatureSettings = "tnum"))
        if (last == 0) return@Column
        fun move(i: Int) {
            if (i == idx.intValue) return
            idx.intValue = i
            view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                else HapticFeedbackConstants.CLOCK_TICK)
            change(options[i])
        }
        Box(Modifier.fillMaxWidth().height(52.dp)
            // Screen readers: announce the step position and let volume keys / swipes move it.
            .semantics {
                stateDescription = lt.stillhome.core.fmtDur(cur * 60_000L)
                progressBarRangeInfo = ProgressBarRangeInfo(idx.intValue.toFloat(), 0f..last.toFloat(), (last - 1).coerceAtLeast(0))
                setProgress { v -> move(v.roundToInt().coerceIn(0, last)); done(options[idx.intValue]); true }
            }
            .pointerInput(options) {
            val pad = 14.dp.toPx()   // the knob never pokes out of the box
            fun at(x: Float) = (((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) * last).roundToInt()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Knob swells only once this is really a slider touch (drag or tap), not while the page scrolls past.
                try {
                    // Sideways = drag; lift without moving = jump there; up/down = left to the page's scroll.
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                    if (drag != null) {
                        held.value = true
                        move(at(drag.position.x))
                        horizontalDrag(drag.id) { it.consume(); move(at(it.position.x)) }
                        done(options[idx.intValue])
                    } else {
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed && !up.isConsumed) { up.consume(); move(at(down.position.x)); done(options[idx.intValue]) }
                    }
                } finally { held.value = false }
            }
        }) {
            Canvas(Modifier.fillMaxSize()) {
                val pad = 14.dp.toPx(); val y = size.height / 2; val w = size.width - 2 * pad
                val x = pad + w * frac; val track = 4.dp.toPx()
                drawLine(Color(0xFF2A2A2A), Offset(pad, y), Offset(pad + w, y), track, StrokeCap.Round)
                drawLine(White, Offset(pad, y), Offset(x, y), track, StrokeCap.Round)
                ticks.forEach { i ->
                    val tx = pad + w * i / last
                    drawLine(if (tx <= x) Dim else Faint, Offset(tx, y + 9.dp.toPx()), Offset(tx, y + 14.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
                }
                if (halo > 0f) drawCircle(White.copy(alpha = 0.14f * halo), knob.toPx() / 2 + 9.dp.toPx() * halo, Offset(x, y))
                drawCircle(White, knob.toPx() / 2, Offset(x, y))
                drawCircle(Black, 3.dp.toPx(), Offset(x, y))   // small dark core: reads as a knob, not a dot
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            T(lt.stillhome.core.fmtDur(options.first() * 60_000L), 12.sp, Dim)
            Spacer(Modifier.weight(1f))
            T(lt.stillhome.core.fmtDur(options.last() * 60_000L), 12.sp, Dim)
        }
    }
}

/** Quiz answers are stored as hours ("2.25", old ones "10+"): show them as "2h 15m". */
fun hoursText(h: String): String = h.toDoubleOrNull()?.let { lt.stillhome.core.fmtDur((it * 60).toLong() * 60_000L) } ?: if (h == "?") h else "$h h"
