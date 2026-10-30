package lt.stillhome.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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

val Black = Color(0xFF000000)
val Bright = Color(0xFFE0E0E0)
val White = Color(0xFFFFFFFF)
val Dim = Color(0xFF808080)
val Faint = Color(0xFF3A3A3A)
val Red = Color(0xFFFF5252)

/** Edge-to-edge, transparent status bar, white icons, black everywhere. */
fun ComponentActivity.blackWindow() {
    enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.BLACK))
    window.setBackgroundDrawableResource(android.R.color.black)
}

@Composable
fun T(text: String, size: TextUnit = 16.sp, color: Color = Bright, modifier: Modifier = Modifier, bold: Boolean = false, lineHeight: TextUnit = TextUnit.Unspecified) =
    Text(text, modifier = modifier, color = color, fontSize = size, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal, lineHeight = lineHeight)

/** Full-screen black page with optional scrolling column. */
@Composable
fun Page(scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Black).safeDrawingPadding().imePadding()) {
        Column(
            Modifier.fillMaxSize().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 28.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

@Composable
fun Title(text: String) { T(text, 26.sp, White); Spacer(Modifier.height(6.dp)) }

@Composable
fun Section(text: String) { Spacer(Modifier.height(10.dp)); T(text.uppercase(), 12.sp, Dim) }

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

/** A tappable settings row: label left, value right (dim). */
@Composable
fun RowItem(label: String, value: String = "", color: Color = Bright, onClick: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 10.dp)) {
        T(label, 17.sp, color, Modifier.align(Alignment.CenterStart).padding(end = 120.dp))
        if (value.isNotEmpty()) T(value, 15.sp, Dim, Modifier.align(Alignment.CenterEnd))
    }
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
            onValueChange = { nv -> if (!noPaste || nv.length - value.length <= 15) onChange(nv) },
            singleLine = singleLine,
            textStyle = TextStyle(color = White, fontSize = size),
            cursorBrush = SolidColor(White),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = modifier.fillMaxWidth().border(1.dp, Faint, RoundedCornerShape(10.dp)).padding(14.dp),
            decorationBox = { inner -> Box { if (value.isEmpty()) T(hint, size, Faint); inner() } },
        )
    }
    if (noPaste) CompositionLocalProvider(LocalTextToolbar provides NoToolbar) { field() } else field()
}

@Composable
fun Center(content: @Composable BoxScope.() -> Unit) =
    Box(Modifier.fillMaxSize().background(Black).safeDrawingPadding(), contentAlignment = Alignment.Center, content = content)

/** Small helper so every rule edit shows whether it applied or is waiting 24 h. */
fun Activity.ruleChange(key: String, value: String?, description: String): String =
    if (Rules.change(key, value, description)) "Applied" else "Loosening: applies in 24 h (cancel in Settings → Pending)"

abstract class BlackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); blackWindow() }
}
