package lt.stillhome.cards

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import lt.stillhome.App
import lt.stillhome.core.Clock
import lt.stillhome.data.Store
import lt.stillhome.service.GuardService
import org.json.JSONArray

data class StoreLoc(val brand: String, val name: String, val lat: Double, val lon: Double)

/**
 * Store geofences (section 11). Store locations come from the bundled OpenStreetMap export
 * (assets/stores.json, refreshed on the PC with tools/fetch_stores.py) plus places saved by hand.
 * Location is only ever handed to Play Services' on-device geofencing; nothing is uploaded by us.
 */
object Geo {
    private const val MOVE_ID = "move"
    private const val MAX = 90           // leave room for the "move" fence (limit is 100 per app)
    private var assetStores: List<StoreLoc>? = null

    fun stores(ctx: Context): List<StoreLoc> {
        val base = assetStores ?: run {
            val json = ctx.assets.open("stores.json").bufferedReader().readText()
            val a = JSONArray(json)
            List(a.length()) { i -> a.getJSONObject(i).let { StoreLoc(it.getString("b"), it.getString("n"), it.getDouble("lat"), it.getDouble("lon")) } }
                .also { assetStores = it }
        }
        return base + App.db.dao().places().map { StoreLoc(it.brand, "${it.brand} (saved)", it.lat, it.lon) }
    }

    fun brands(ctx: Context) = stores(ctx).map { it.brand }.distinct().sortedBy { it.lowercase() }

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(ctx: Context) = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)   // must be mutable: Play Services fills in the event

    /** Re-register the <=90 nearest stores whose brand has a card, plus a 3 km "moved away" fence. */
    @SuppressLint("MissingPermission")
    fun refresh(ctx: Context) {
        if (!hasPermission(ctx)) return
        val c = ctx.applicationContext
        Thread {
            try {
                val brands = App.db.dao().cards().map { it.brand }.toSet()
                val client = LocationServices.getGeofencingClient(c)
                client.removeGeofences(pendingIntent(c))
                if (brands.isEmpty()) return@Thread
                LocationServices.getFusedLocationProviderClient(c)
                    .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener { loc -> register(c, brands, loc) }
                    .addOnFailureListener { register(c, brands, null) }
            } catch (e: Exception) { Log.w("Geo", "refresh", e) }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun register(c: Context, brands: Set<String>, loc: Location?) {
        val list = stores(c).filter { it.brand in brands }
            .sortedBy { s -> loc?.let { dist(it.latitude, it.longitude, s.lat, s.lon) } ?: 0f }
            .take(MAX)
        val fences = list.mapIndexed { i, s ->
            // Stable id (position, not list index) so a refresh while inside the store still matches on exit.
            Geofence.Builder().setRequestId("s:${"%.5f_%.5f".format(java.util.Locale.ROOT, s.lat, s.lon)}:${s.brand}:${s.name}")
                .setCircularRegion(s.lat, s.lon, 100f)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT)
                .setLoiteringDelay(60_000)
                .setExpirationDuration(Geofence.NEVER_EXPIRE).build()
        }.toMutableList()
        if (loc != null) fences += Geofence.Builder().setRequestId(MOVE_ID)
            .setCircularRegion(loc.latitude, loc.longitude, 3000f)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT).setExpirationDuration(Geofence.NEVER_EXPIRE).build()
        if (fences.isEmpty()) return
        val req = GeofencingRequest.Builder().setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_DWELL).addGeofences(fences).build()
        LocationServices.getGeofencingClient(c).addGeofences(req, pendingIntent(c))
            .addOnFailureListener { Log.w("Geo", "addGeofences failed", it) }
    }

    private fun dist(a: Double, b: Double, c: Double, d: Double) = FloatArray(1).also { Location.distanceBetween(a, b, c, d, it) }[0]

    /** Called when a store fence fires. */
    fun enter(ctx: Context, brand: String, name: String, fenceId: String) {
        val card = App.db.dao().cards().firstOrNull { it.brand == brand } ?: return
        // Fences re-fire while you stay: notify once per visit. A visit older than 3 h is over even if its exit got lost.
        val already = Store.get("inStoreBrand") == brand && Clock.now() - Store.long("inStoreAt", 0) < 3 * 3600_000L
        Store.setAll(mapOf("inStoreCard" to card.id.toString(), "inStoreBrand" to brand, "inStoreName" to name, "inStoreFence" to fenceId,
            "inStoreAt" to (if (already) Store.get("inStoreAt") else Clock.now().toString())))
        GuardService.refresh(ctx)
        if (!already) CardOpen.notifyArrival(ctx, card, name.removeSuffix(" (saved)"))
    }

    fun exit(ctx: Context, fenceId: String) {
        if (Store.get("inStoreFence") != fenceId) return
        leaveStore(ctx)
    }

    /** Forget the current store visit: home button, notification card action and arrival heads-up all go. */
    fun leaveStore(ctx: Context) {
        Store.setAll(mapOf("inStoreCard" to null, "inStoreBrand" to null, "inStoreName" to null, "inStoreFence" to null, "inStoreAt" to null))
        GuardService.refresh(ctx)
        CardOpen.clearArrival(ctx)
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError()) return
        for (f in ev.triggeringGeofences.orEmpty()) {
            if (f.requestId == "move") { Geo.refresh(ctx); continue }   // significant move: re-pick nearest stores
            val parts = f.requestId.split(":", limit = 4)   // s:<i>:<brand>:<name>
            if (parts.size < 4) continue
            when (ev.geofenceTransition) {
                Geofence.GEOFENCE_TRANSITION_DWELL, Geofence.GEOFENCE_TRANSITION_ENTER -> Geo.enter(ctx, parts[2], parts[3], f.requestId)
                Geofence.GEOFENCE_TRANSITION_EXIT -> Geo.exit(ctx, f.requestId)
            }
        }
    }
}

object Barcodes {
    /** Guess the format of a typed number (scanned cards keep the format the scanner reported). */
    fun guess(number: String): String {
        val digits = number.all { it.isDigit() }
        return when {
            digits && number.length == 13 && eanOk(number) -> "EAN_13"
            digits && number.length == 8 && eanOk(number) -> "EAN_8"
            digits && number.length == 12 -> "UPC_A"
            else -> "CODE_128"
        }
    }

    private fun eanOk(n: String): Boolean {
        val d = n.map { it.digitToInt() }
        val sum = d.dropLast(1).reversed().mapIndexed { i, v -> if (i % 2 == 0) v * 3 else v }.sum()
        return (10 - sum % 10) % 10 == d.last()
    }

    fun render(number: String, format: String, w: Int, h: Int): Bitmap? = try {
        val f = BarcodeFormat.valueOf(format)
        val square = f in setOf(BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC, BarcodeFormat.DATA_MATRIX)
        val (bw, bh) = if (square) minOf(w, h).let { it to it } else w to h
        val m = MultiFormatWriter().encode(number, f, bw, bh, mapOf(EncodeHintType.MARGIN to 2))
        Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until m.width) for (y in 0 until m.height) setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    } catch (e: Exception) {
        // e.g. an EAN-13 typed with a wrong check digit: show it as Code 128 rather than nothing.
        Log.w("Barcodes", "render $format failed, trying CODE_128", e)
        if (format != "CODE_128") render(number, "CODE_128", w, h) else null
    }
}
