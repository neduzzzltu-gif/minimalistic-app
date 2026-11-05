package lt.stillhome.ui

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import lt.stillhome.core.Apps
import lt.stillhome.core.Cats
import lt.stillhome.core.Gray
import lt.stillhome.core.Guard
import lt.stillhome.data.Store
import lt.stillhome.notif.NotifService
import lt.stillhome.service.Pending
import lt.stillhome.service.GuardA11yService

/**
 * One checklist row. [done] = live state, null = Android can't tell us (user marks it).
 * [open] = deep link, null = nothing to open (done by hand). [legacy] = old index-based "perm.done.N" key.
 */
class Step(val id: String, val title: String, val how: String, val done: (Context) -> Boolean?,
           val open: ((Activity) -> Unit)?, val legacy: Int? = null)

/**
 * Start a system page. Settings deep links get a short Settings pass so a gated Settings app lets them through.
 * [grant] = checklist step id: also a short grant pass, so lockdown lets that step's own grant page
 * through while the permission is still missing (see GuardA11yService.grantOpen).
 */
fun Context.safeStart(i: Intent, fallback: Intent? = null, grant: String? = null) {
    if (i.action != Intent.ACTION_DELETE) Guard.startSettingsPass()   // uninstall dialog isn't Settings: no pass
    grant?.let { Guard.startGrantPass(it) }
    try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: Exception) { fallback?.let { try { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} } }
}

private fun appDetails(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
private fun notifSettings(ctx: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

private fun opAllowed(ctx: Context, op: String) = ctx.getSystemService(AppOpsManager::class.java)
    .unsafeCheckOpNoThrow(op, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED

/** Xiaomi's private app ops (10021 = background pop-ups, 10008 = autostart), read via reflection. Best effort. */
private fun miuiOp(ctx: Context, op: Int): Boolean? = try {
    val m = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
    (m.invoke(ctx.getSystemService(AppOpsManager::class.java), op, Process.myUid(), ctx.packageName) as Int) == AppOpsManager.MODE_ALLOWED
} catch (_: Exception) { null }

/**
 * Runtime permission: ask the first time; once Android stops showing the dialog (denied twice),
 * send the user to the settings page instead so the button never silently does nothing.
 */
private fun ask(a: Activity, perms: Array<String>, code: Int, fallback: Intent, grant: String) {
    val key = "perm.asked.${perms[0]}"
    if (Store.bool(key, false) && perms.none { a.shouldShowRequestPermissionRationale(it) }) a.safeStart(fallback, appDetails(a), grant)
    else { Store.set(key, "1"); a.requestPermissions(perms, code) }
}

private const val MIUI_SEC = "com.miui.securitycenter"

/**
 * HyperOS per-app permissions page (calendar, location, pop-ups). Used instead of App info for
 * "denied for good": App info has Force stop / Uninstall, so lockdown never lets it through.
 */
fun appPerms(ctx: Context): Intent = Intent("miui.intent.action.APP_PERM_EDITOR").setClassName(MIUI_SEC,
    "com.miui.permcenter.permissions.PermissionsEditorActivity").putExtra("extra_pkgname", ctx.packageName)

/** The setup checklist, most important first. */
val steps = listOf(
    Step("home", "Default launcher", "Pick Stillhome as the Home app.",
        { it.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) },
        { a -> a.startActivityForResult(a.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME), 1) }),
    Step("a11y", "Accessibility service", "Accessibility → Downloaded apps → Stillhome → On. If it says “Restricted setting”: App info → ⋮ (top right) → Allow restricted settings, then try again.",
        { GuardA11yService.running() }, { it.safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),   // no grant pass: never past lockdown
    Step("autostart", "Autostart", "Turn Stillhome on. Without it HyperOS kills the app in the background and its alarms never fire: " +
        "no 1-hour reminders, no daily screen-time save. Android can't report this one, so make sure it's on, then tap “Mark done”.",
        { miuiOp(it, 10008)?.takeIf { ok -> ok } },   // only trust a clear "allowed"; anything else = can't tell
        { a -> a.safeStart(Intent().setComponent(ComponentName(MIUI_SEC, "com.miui.permcenter.autostart.AutoStartManagementActivity")), appDetails(a), "autostart") },
        legacy = 8),
    Step("battery", "Battery: No restrictions", "Android's battery optimisation off, so alarms and the blocker run in the background.",
        { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
        { a -> a.safeStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")), appDetails(a), "battery") }),
    // HyperOS has its own per-app battery saver on top of Android's; it can't be read, so the user marks it.
    Step("miuiBattery", "HyperOS battery saver: No restrictions", "Battery saver → “No restrictions”. HyperOS's own saver, separate from the one above. " +
        "If this opens App info instead: Battery saver → No restrictions.",
        { null },
        { a -> a.safeStart(Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", a.packageName).putExtra("package_label", a.applicationInfo.loadLabel(a.packageManager).toString()), appDetails(a), "miuiBattery") }),
    Step("alarms", "Exact alarms", "Alarms & reminders → allow. Needed for the 1-hour “still want it?” reminders.",
        { Pending.canExact(it) },
        { a -> a.safeStart(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${a.packageName}")), appDetails(a), "alarms") }),
    Step("usage", "Usage access", "App usage data → Stillhome → allow. Gives screen time per app and “last opened”.",
        { opAllowed(it, AppOpsManager.OPSTR_GET_USAGE_STATS) },
        { a -> a.safeStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${a.packageName}")), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), "usage") }),
    Step("notifs", "Notifications", "Allow notifications: the lock-screen status line and reminders.",
        { Pending.canNotify(it) },   // permission + app notifications switched on
        // Already granted but switched off in system settings: asking again shows nothing, so open the page.
        { a -> if (granted(a, Manifest.permission.POST_NOTIFICATIONS)) a.safeStart(notifSettings(a), grant = "notifs")
            else ask(a, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2, notifSettings(a), "notifs") }),
    Step("listener", "Notification access", "Allow Stillhome to read, reply to and control notifications.",
        { c -> Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")?.contains(c.packageName) == true },
        { a -> a.safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(a, NotifService::class.java).flattenToString()),
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "listener") }),
    Step("calendar", "Calendar", "Shows today's events on the home screen.",
        { granted(it, Manifest.permission.READ_CALENDAR) },
        { a -> ask(a, arrayOf(Manifest.permission.READ_CALENDAR), 5, appPerms(a), "calendar") }),
    Step("overlay", "Display over other apps", "Turn on for Stillhome.",
        { Settings.canDrawOverlays(it) },
        { a -> a.safeStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}")), grant = "overlay") }),
    Step("popup", "Pop-ups from the background", "Other permissions → “Display pop-up windows while running in the background” → Always allow. Lets the Gate open over other apps.",
        { miuiOp(it, 10021) },
        { a -> a.safeStart(appPerms(a), appDetails(a), "popup") }),
    Step("location", "Location: Allow all the time", "First allow “While using”, tap again, then choose “Allow all the time”. Only used for store geofences, on the phone.",
        { granted(it, Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
        { a ->
            if (!granted(a, Manifest.permission.ACCESS_FINE_LOCATION))
                ask(a, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 3, appPerms(a), "location")
            else ask(a, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 4, appPerms(a), "location")
        }),
    Step("recents", "Lock in recents", "Open Stillhome Settings (long-press home), open Recents, long-press its card → Lock (padlock).",
        { null }, null, legacy = 10),
    Step("lockscreen", "Show on lock screen", "Notifications → Stillhome → “Status (lock screen)” → Lock screen: show, and allow notifications on the lock screen.",
        { null }, { a -> a.safeStart(notifSettings(a), grant = "lockscreen") }, legacy = 11),
    Step("mute", "Mute filtered apps' channels", "For each app below: turn off sound/pop-up for its channels (Stillhome re-posts what should ping).",
        { null }, null, legacy = 12),
    Step("wss", "Grayscale permission (adb)", "Done from the PC: adb shell pm grant lt.stillhome android.permission.WRITE_SECURE_SETTINGS",
        { Gray.canWrite(it) }, null),
)

private fun marked(s: Step) = Store.bool("perm.done.${s.id}", false) || (s.legacy?.let { Store.bool("perm.done.$it", false) } ?: false)

/** For the lockdown grant pass: is checklist step [id] still not done? (Can't-check steps: not marked done.) Unknown id = false. */
fun stepMissing(ctx: Context, id: String): Boolean {
    val s = steps.firstOrNull { it.id == id } ?: return false
    return !(runCatching { s.done(ctx) }.getOrNull() ?: marked(s))
}

/** For the Settings main page: (done, total), same count as the checklist header. */
fun permsDone(ctx: Context): Pair<Int, Int> = steps.count { it.done(ctx) ?: marked(it) } to steps.size

/** Apps whose notifications are filtered (anything not always-Ping): their system channels must be muted. */
fun filteredApps(ctx: Context) = Apps.all(ctx).filter { a ->
    val c = Cats.of(a.pkg)
    c in Cats.gated || (c != Cats.CHAT && c !in Store.list("whitelistCats").ifEmpty { listOf(Cats.CHAT, Cats.FINANCE, Cats.SCHOOL) } &&
        a.pkg !in Store.list("neverFilterApps") && c.isNotEmpty())
}

private val Ok = Color(0xFF4CD483)

/** Round status mark: green tick = done, red cross = to do, grey dot = can't check (not marked yet). */
@Composable
private fun Mark(done: Boolean, unknown: Boolean) {
    val c = when { done -> Ok; unknown -> Dim; else -> Red }
    Box(Modifier.size(24.dp).clip(CircleShape).background(c.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(12.dp)) {
            val w = size.width; val sw = 2.dp.toPx()
            when {
                done -> { drawLine(c, Offset(w * 0.05f, w * 0.55f), Offset(w * 0.38f, w * 0.88f), sw, StrokeCap.Round)
                          drawLine(c, Offset(w * 0.38f, w * 0.88f), Offset(w * 0.95f, w * 0.15f), sw, StrokeCap.Round) }
                unknown -> drawCircle(c, w * 0.18f)
                else -> { drawLine(c, Offset(w * 0.12f, w * 0.12f), Offset(w * 0.88f, w * 0.88f), sw, StrokeCap.Round)
                          drawLine(c, Offset(w * 0.88f, w * 0.12f), Offset(w * 0.12f, w * 0.88f), sw, StrokeCap.Round) }
            }
        }
    }
}

/** The setup checklist (onboarding + Settings › Permissions). Re-checks on every return and every 1.5 s while open. */
@Composable
fun Permissions(a: Activity) {
    val owner = LocalLifecycleOwner.current
    // Live checks run off the main thread (some hit system services); re-run on every return and every 1.5 s.
    val first = remember { steps.map { s -> runCatching { s.done(a) }.getOrNull() } }
    val autos by produceState(first) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { value = withContext(Dispatchers.Default) { steps.map { s -> runCatching { s.done(a) }.getOrNull() } }; delay(1500) }
        }
    }
    val map by Store.map.collectAsState()   // "Mark done" taps show at once
    val filtered = remember(map, Apps.version.intValue) { filteredApps(a) }
    val states = steps.mapIndexed { i, s -> autos[i].let { auto -> auto to (auto ?: map.let { marked(s) }) } }
    val n = states.count { it.second }
    T("What the app needs to run. Rows update on their own.", 14.sp, Dim)
    // Summary: count + thin progress bar.
    Column(Modifier.padding(top = 10.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T("$n of ${steps.size} done", 18.sp, if (n == steps.size) Ok else White, bold = true)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color(0xFF2A2A2A))) {
            Box(Modifier.fillMaxWidth(n / steps.size.toFloat()).height(4.dp).clip(CircleShape).background(if (n == steps.size) Ok else White))
        }
    }
    steps.forEachIndexed { i, s ->
        val (auto, done) = states[i]
        SetGroup {
            Inner {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mark(done, auto == null && !done)
                    Spacer(Modifier.width(12.dp))
                    T(s.title, 16.sp, if (done) Dim else White, Modifier.weight(1f))
                    T(when { done && auto == null -> "Marked done"; done -> "Done"; auto == null -> "Can't check"; else -> "To do" },
                        13.sp, when { done -> Ok; auto == null -> Dim; else -> Red })
                }
                // Finished rows fold to one line; open rows explain what to do.
                if (!done) T(s.how, 13.sp, Dim, Modifier.padding(start = 36.dp))
                val showOpen = s.open != null && (!done || auto == null)
                if (showOpen || auto == null) Row(Modifier.padding(start = 36.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (showOpen) MainBtn("Open", primary = !done) { s.open?.invoke(a) }
                    if (auto == null) MainBtn(if (done) "Undo" else "Mark done", primary = false) {
                        Store.setAll(mapOf("perm.done.${s.id}" to if (done) "0" else "1") + (s.legacy?.let { mapOf("perm.done.$it" to null) } ?: emptyMap()))
                    }
                }
            }
            if (s.id == "mute") filtered.forEach { app ->
                SetDivider0()
                ValueRow(app.label, "Mute", chevron = true) { a.safeStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.pkg)) }
            }
        }
    }
    T("Install via adb only, or Android blocks some settings.", 13.sp, Faint, Modifier.padding(top = 8.dp))
}
