package lt.stillhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import lt.stillhome.data.Store

/**
 * Test hook for adb (debug builds only):
 *   adb shell am broadcast -n lt.stillhome/.DebugReceiver --es set "key=value;key2=value2"
 *   adb shell am broadcast -n lt.stillhome/.DebugReceiver --es dump 1
 *   adb shell am broadcast -n lt.stillhome/.DebugReceiver --es reset 1   (wipes settings + DB = fresh install)
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        i.getStringExtra("set")?.split(';')?.forEach { kv ->
            val k = kv.substringBefore('='); val v = kv.substringAfter('=', "")
            Store.set(k, v.ifEmpty { null })
        }
        if (i.getStringExtra("dump") != null) Store.map.value.toSortedMap().forEach { (k, v) -> Log.i("StillDebug", "$k=$v") }
        if (i.getStringExtra("db") != null) {
            val d = App.db.dao()
            d.notifLogSince(0).takeLast(15).forEach { Log.i("StillDebug", "notif ${it.pkg} ch=${it.channel} kind=${it.kind} -> ${it.decision}") }
            d.channels().forEach { Log.i("StillDebug", "channel ${it.pkg} ${it.channel} '${it.name}' ${it.decision}") }
            d.sessionsSince(0).forEach { Log.i("StillDebug", "session ${it.id} ${it.target} ${it.category} ${it.plannedMin}m end=${it.end} intent=${it.intent}") }
            d.eventsSince(0).takeLast(15).forEach { Log.i("StillDebug", "event ${it.type}: ${it.text}") }
        }
        i.getStringExtra("card")?.let { spec ->   // "name|brand|number|format"
            val p = spec.split('|')
            val id = App.db.dao().upsertCard(lt.stillhome.data.CardRow(name = p[0], brand = p[1], number = p[2], format = p[3]))
            Log.i("StillDebug", "card id=$id")
        }
        if (i.getStringExtra("reset") != null) {
            Store.setAll(Store.map.value.keys.associateWith { null })
            App.db.clearAllTables()
            Log.i("StillDebug", "reset done")
        }
        Log.i("StillDebug", "ok")
    }
}
