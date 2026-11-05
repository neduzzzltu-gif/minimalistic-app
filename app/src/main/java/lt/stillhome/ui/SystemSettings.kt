package lt.stillhome.ui

import android.content.Context
import android.content.Intent
import lt.stillhome.core.Guard

/**
 * Open a system Settings page from our own UI (usage access, accessibility, app info, battery...).
 * Starts a short pass so a gated Settings app isn't gated on our own deep links; the pass ends
 * after ~3 min or as soon as you leave Settings (see GuardA11yService.settingsPassCheck).
 * [grant] = permission checklist step id: lockdown lets that step's grant page through while it's missing.
 */
fun Context.openSystemSettings(i: Intent, fallback: Intent? = null, grant: String? = null) {
    grant?.let { Guard.startGrantPass(it) }
    if (!go(i)) fallback?.let { go(it) }
}

/** Pass only when the page really is in com.android.settings (HyperOS sends some to Security, which isn't gated). */
private fun Context.go(i: Intent): Boolean {
    if (i.resolveActivity(packageManager)?.packageName == Guard.SETTINGS) Guard.startSettingsPass()
    return try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
}
