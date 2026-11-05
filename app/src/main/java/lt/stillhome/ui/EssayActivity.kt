package lt.stillhome.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.stillhome.App
import lt.stillhome.core.Clock
import lt.stillhome.core.Essay
import lt.stillhome.data.EssayRow

/**
 * Daily essay, shown by MainActivity.onResume before Home is usable. Back does nothing; Home brings
 * you back to MainActivity, which shows this again. Phone / Maps / Messages always work.
 */
class EssayActivity : BlackActivity() {
    private var day by mutableStateOf(Essay.day())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EssayPage(day, ::done) }
    }

    // Written already (or switched off / guard paused meanwhile): nothing to do here. Else refresh the day (04:00 passed?).
    override fun onResume() { super.onResume(); if (!Essay.due()) done() else day = Essay.day() }

    private fun done() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

/** No paste menu at all. */
private object NoMenu : TextToolbar {
    override val status = TextToolbarStatus.Hidden
    override fun hide() {}
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {}
}

/** Like Field(noPaste = true) (same [typedInsert] guard), but tall and multi-line. */
@Composable
private fun EssayField(value: String, onChange: (String) -> Unit) = CompositionLocalProvider(LocalTextToolbar provides NoMenu) {
    BasicTextField(
        value = value,
        onValueChange = { nv -> if (typedInsert(value, nv)) onChange(nv) },
        textStyle = TextStyle(color = White, fontSize = 17.sp, fontFamily = Inter),
        cursorBrush = SolidColor(White),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C1C)).padding(14.dp),
        decorationBox = { inner -> Box { if (value.isEmpty()) T("Write here…", 17.sp, Faint); inner() } },
    )
}

@Composable
private fun EssayPage(day: String, done: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prompt = remember(day) { Essay.prompt(day) }
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val count = remember(text) { Essay.words(text).size }
    BackHandler { }   // write first

    Page {
        // Emergency: always reachable, no essay needed.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Phone") { ctx.start(Intent(Intent.ACTION_DIAL)) }
            Btn("Maps") { ctx.start(ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps") ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))) }
            Btn("Messages") {
                ctx.start(Telephony.Sms.getDefaultSmsPackage(ctx)?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
                    ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING))
            }
        }
        Spacer(Modifier.height(10.dp))
        Title("Daily essay")
        T(prompt, 20.sp, White, lineHeight = 26.sp)
        T("At least ${Essay.MIN_WORDS} words, Lithuanian or English. No pasting.", 13.sp, Dim)
        Spacer(Modifier.height(4.dp))
        EssayField(text) { text = it; error = null }
        T("$count / ${Essay.MIN_WORDS} words", 14.sp, if (count >= Essay.MIN_WORDS) White else Dim)
        error?.let { T(it, 14.sp, Red) }
        Spacer(Modifier.height(8.dp))
        Btn("Done", enabled = count >= Essay.MIN_WORDS) {
            val why = Essay.check(text)
            if (why != null) { error = why; return@Btn }
            // Recompute the day now: the screen may have been open across 04:00.
            App.db.dao().insertEssay(EssayRow(day = Essay.day(), time = Clock.now(), prompt = prompt, text = text.trim(), words = count))
            done()
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun Context.start(i: Intent) { try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
