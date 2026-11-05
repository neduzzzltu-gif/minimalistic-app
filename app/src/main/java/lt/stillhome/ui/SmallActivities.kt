package lt.stillhome.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.stillhome.App
import lt.stillhome.cards.Barcodes
import lt.stillhome.core.Apps
import lt.stillhome.core.Cats
import lt.stillhome.core.Guard
import lt.stillhome.core.Rules
import lt.stillhome.data.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Newly installed app: pick a label now. Stays gated until labelled; non-gated label = loosening (pending, apply after 1 h). */
class LabelActivity : BlackActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra("pkg") ?: return finish()
        val name = Apps.label(this, pkg)
        val suggested = Cats.autoLabel(this, pkg)
        setContent {
            var msg by remember { mutableStateOf("") }
            BackHandler { goHome() }
            Page {
                Title("New app: $name")
                T("Pick a label. Until then it counts as gated." + (suggested?.let { "\nSuggested: $it" } ?: ""), 15.sp, Dim)
                Chips(Cats.all(), emptySet()) { cat ->
                    if (pkg in Cats.lockedSocial) { msg = "$name is always Social"; return@Chips }
                    // First label from "unlabelled": gated -> instant, non-gated -> pending change (apply after 1 h).
                    msg = if (Rules.change("label.$pkg", cat, "Label $name as $cat")) "Labelled $cat"
                    else "Non-gated label = loosening: $name stays gated until you apply it (Settings, after 1 h)"
                    if (Store.get("label.$pkg") == null) Store.set("label.pending.$pkg", cat)
                }
                if (msg.isNotEmpty()) { T(msg, 15.sp, White); Btn("Done") { goHome() } }
            }
        }
    }

    private fun goHome() { startActivity(Intent(this, MainActivity::class.java)); finish() }
}

/** Everything held back by the notification filter. Reachable from home ("7 new") and the digest notification. */
class DigestActivity : BlackActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val rows by App.db.dao().digestFlow().collectAsState(emptyList())
            val fmt = remember { SimpleDateFormat("EEE HH:mm", Locale.ENGLISH) }
            Page {
                Title("Digest")
                val reels = rows.filter { !it.seen && it.kind == "share" }.groupBy { it.app }
                reels.forEach { (app, l) -> T("${l.size} reels/posts sent to you on $app", 15.sp, White) }
                if (rows.isEmpty()) T("Nothing held back.", 15.sp, Dim)
                rows.groupBy { it.app }.forEach { (app, list) ->
                    Section("$app · ${list.size}")
                    list.take(40).forEach { d ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            T(listOf(d.title, d.text).filter { it.isNotBlank() }.joinToString(" — "), 15.sp, if (d.seen) Dim else Bright)
                            T(fmt.format(Date(d.time)) + " · " + d.kind, 12.sp, Faint)
                        }
                    }
                }
            }
        }
    }

    override fun onStop() { super.onStop(); App.db.dao().markDigestSeen() }
}

/**
 * Full-screen barcode at max brightness. setShowWhenLocked (manifest) = usable from the lock screen.
 * App-only cards (rotating codes) get a "checkout pass" button instead: opens the store's app with no
 * Gate, only inside that store's geofence, auto-closed after 3 min, logged.
 */
class BarcodeActivity : BlackActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val card = App.db.dao().card(intent.getLongExtra("card", 0)) ?: return finish()
        val dm = resources.displayMetrics
        val bmp: Bitmap? = if (card.appPkg.isEmpty()) Barcodes.render(card.number, card.format, dm.widthPixels - 80, dm.widthPixels / 2) else null
        setContent {
            Box(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    androidx.compose.material3.Text(card.name, color = Color.Black, fontSize = 24.sp)
                    if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth().padding(20.dp))
                    androidx.compose.material3.Text(card.number, color = Color.Black, fontSize = 20.sp)
                    if (card.appPkg.isNotEmpty()) {
                        val inside = Store.get("inStoreBrand") == card.brand
                        androidx.compose.material3.Text(
                            if (inside) "Opens ${Apps.label(this@BarcodeActivity, card.appPkg)} for 3 minutes, no Gate."
                            else "Checkout pass only works inside a ${card.brand} store.", color = Color.Black, fontSize = 16.sp)
                        if (inside) androidx.compose.material3.Button(onClick = {
                            Guard.startPass(card.appPkg, Store.str("inStoreName", card.brand))
                            Apps.launch(this@BarcodeActivity, card.appPkg); finish()
                        }) { androidx.compose.material3.Text("Checkout pass") }
                    }
                }
            }
        }
    }
}

