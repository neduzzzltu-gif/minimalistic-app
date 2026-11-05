package lt.stillhome.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.stillhome.core.Gray
import lt.stillhome.core.Guard
import lt.stillhome.service.GuardService

/** Turn the whole guard off/on. Shared by the switch screen and the Quick Settings tile. */
fun setGuard(ctx: Context, on: Boolean) {
    if (!on && Guard.focusStrict()) {   // whole-phone focus can't be escaped by switching the blocker off
        android.widget.Toast.makeText(ctx, "Focus mode locks the phone until it ends", android.widget.Toast.LENGTH_SHORT).show(); return
    }
    Guard.setPaused(!on)
    if (!on) Gray.set(ctx, false, force = true)   // never leave the screen grey
    GuardService.refresh(ctx)
    TileService.requestListeningState(ctx, ComponentName(ctx, GuardTile::class.java))
}

/**
 * Emergency on/off switch with its own launcher icon ("Stillhome switch"), so it can be reached
 * from any launcher without a PC. Instant: no 24 h wait, no lockdown.
 */
class SwitchActivity : BlackActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var on by remember { mutableStateOf(!Guard.paused()) }
            Page {
                Title("Stillhome switch")
                T(if (on) "App blocker is on" else "App blocker is off", 30.sp, if (on) White else Red)
                T(if (on) "Gate, limits, lockdown, notification filter and grayscale are active."
                  else "Nothing is blocked or filtered. The home screen still works.", 15.sp, Dim)
                Spacer(Modifier.height(16.dp))
                Btn(if (on) "Turn it off" else "Turn it on") { setGuard(this@SwitchActivity, !on); on = !on }
                Spacer(Modifier.height(24.dp))
                T("If the Stillhome home screen itself misbehaves, switch back to the Xiaomi launcher:", 15.sp, Dim)
                Btn("Choose home app") {
                    safeStart(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                }
                Spacer(Modifier.height(16.dp))
                T("Also in the notification shade: edit the quick tiles and add “Stillhome blocker”.", 13.sp, Dim)
            }
        }
    }
}

/** Quick Settings tile: one tap in the notification shade turns the guard on/off. */
class GuardTile : TileService() {
    override fun onStartListening() = update()
    override fun onClick() { setGuard(this, Guard.paused()); update() }

    private fun update() {
        val t = qsTile ?: return
        val on = !Guard.paused()
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Stillhome blocker"
        t.subtitle = if (on) "On" else "Off"
        t.updateTile()
    }
}
