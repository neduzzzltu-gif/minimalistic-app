package lt.stillhome.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import lt.stillhome.cards.Geo

/** BOOT_COMPLETED / app update: restart the guard, re-arm the pending-change alarm. All other state lives in DataStore + Room, so nothing else to restore. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        GuardService.start(ctx)
        Geo.refresh(ctx)   // geofences are cleared by a reboot
        try { Pending.restore(ctx) } catch (e: Exception) { android.util.Log.w("Boot", "pending: $e") }   // alarms too
    }
}
