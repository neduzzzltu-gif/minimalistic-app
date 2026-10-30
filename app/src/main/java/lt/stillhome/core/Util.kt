package lt.stillhome.core

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.provider.Settings
import android.util.Log
import lt.stillhome.data.Store

/** Grayscale via the system colour-correction (daltonizer) setting. Needs WRITE_SECURE_SETTINGS (adb grant). */
object Gray {
    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"
    private var on = false

    fun canWrite(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Turn grayscale on/off. Re-applied on every gated open (force = true) in case something reset it. */
    fun set(ctx: Context, enable: Boolean, force: Boolean = false) {
        if (enable == on && !force) return
        if (!canWrite(ctx)) return
        val cr = ctx.contentResolver
        try {
            if (enable) {
                if (!on) {   // remember the user's own colour-correction state to restore it later
                    Store.set("gray.prevEnabled", Settings.Secure.getInt(cr, ENABLED, 0).toString())
                    Store.set("gray.prevMode", Settings.Secure.getInt(cr, MODE, -1).toString())
                }
                Settings.Secure.putInt(cr, MODE, 0)      // 0 = monochromacy
                Settings.Secure.putInt(cr, ENABLED, 1)
            } else {
                Settings.Secure.putInt(cr, ENABLED, Store.int("gray.prevEnabled", 0))
                Store.int("gray.prevMode", -1).let { if (it >= 0) Settings.Secure.putInt(cr, MODE, it) }
            }
            on = enable
        } catch (e: SecurityException) { Log.w("Gray", "no WRITE_SECURE_SETTINGS", e) }
    }

    fun enabledFor(pkg: String) = Store.str("gray.$pkg", "1") != "0"
}

/** Text similarity for "reason too similar to a recent one" (normalised Levenshtein). */
object Similar {
    fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()

    fun ratio(a: String, b: String): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() && y.isEmpty()) return 1.0
        val d = IntArray(y.length + 1) { it }
        for (i in 1..x.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..y.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (x[i - 1] == y[j - 1]) 0 else 1)
                prev = t
            }
        }
        return 1.0 - d[y.length].toDouble() / maxOf(x.length, y.length)
    }

    /** Rejected if exactly equal or >= 80% similar to any of the given reasons. */
    fun tooSimilar(reason: String, recent: List<String>) = recent.any { norm(it) == norm(reason) || ratio(it, reason) >= 0.8 }
}

data class AppEntry(val pkg: String, val label: String)

/** Launchable apps (text only, no icons are ever loaded). */
object Apps {
    @Volatile private var cache: List<AppEntry>? = null

    fun all(ctx: Context): List<AppEntry> = cache ?: run {
        val la = ctx.getSystemService(LauncherApps::class.java)
        la.getActivityList(null, Process.myUserHandle())
            .filter { it.applicationInfo.packageName != ctx.packageName }
            .map { AppEntry(it.applicationInfo.packageName, it.label.toString()) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
            .also { cache = it }
    }

    fun invalidate() { cache = null }

    fun label(ctx: Context, pkg: String): String =
        cache?.firstOrNull { it.pkg == pkg }?.label ?: try {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try { ctx.startActivity(i); true } catch (_: Exception) { false }
    }
}
