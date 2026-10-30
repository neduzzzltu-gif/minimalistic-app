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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import lt.stillhome.core.Apps
import lt.stillhome.core.Cats
import lt.stillhome.core.Gray
import lt.stillhome.data.Store
import lt.stillhome.notif.NotifService
import lt.stillhome.service.GuardA11yService

/** One walkthrough step: deep link + a way to know it's done (null = can't detect, user marks it). */
class Step(val title: String, val how: String, val done: (Context) -> Boolean?, val open: (Activity) -> Unit)

fun Context.safeStart(i: Intent, fallback: Intent? = null) {
    try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: Exception) { fallback?.let { try { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} } }
}

private fun appDetails(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

private fun opAllowed(ctx: Context, op: String) = ctx.getSystemService(AppOpsManager::class.java)
    .unsafeCheckOpNoThrow(op, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED

/** Xiaomi's own "display pop-up windows while running in background" op (10021), read via reflection. */
private fun miuiBackgroundPopup(ctx: Context): Boolean? = try {
    val m = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
    (m.invoke(ctx.getSystemService(AppOpsManager::class.java), 10021, Process.myUid(), ctx.packageName) as Int) == AppOpsManager.MODE_ALLOWED
} catch (_: Exception) { null }

/** Section 18 walkthrough, in order. */
val steps = listOf(
    Step("1. Default launcher", "Pick Stillhome as the Home app.",
        { it.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) },
        { a -> a.startActivityForResult(a.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME), 1) }),
    Step("2. Accessibility service", "Accessibility → Downloaded apps → Stillhome → On. If it says “Restricted setting”: App info → ⋮ (top right) → Allow restricted settings, then try again.",
        { GuardA11yService.running() }, { it.safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
    Step("3. Notification access", "Allow Stillhome to read, reply to and control notifications.",
        { c -> Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")?.contains(c.packageName) == true },
        { a -> a.safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(a, NotifService::class.java).flattenToString()),
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }),
    Step("4. Usage access", "App usage data → Stillhome → allow. Gives the per-app breakdown.",
        { opAllowed(it, AppOpsManager.OPSTR_GET_USAGE_STATS) },
        { a -> a.safeStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${a.packageName}")), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }),
    Step("5. Notifications", "Allow notifications (the lock-screen status line).",
        { it.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
        { it.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }),
    Step("6. Location → Allow all the time", "First allow “While using”, tap again, then choose “Allow all the time”. Used only for store geofences on the phone.",
        { it.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED },
        { a ->
            if (a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                a.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 3)
            else a.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 4)
        }),
    Step("7. Display over other apps", "Turn on for Stillhome.",
        { Settings.canDrawOverlays(it) },
        { a -> a.safeStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}"))) }),
    Step("8. Pop-up windows while running in background", "Other permissions → “Display pop-up windows while running in the background” → Always allow. Needed so the Gate can open over other apps.",
        { miuiBackgroundPopup(it) },
        { a -> a.safeStart(Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter",
            "com.miui.permcenter.permissions.PermissionsEditorActivity").putExtra("extra_pkgname", a.packageName), appDetails(a)) }),
    Step("9. Autostart", "Background autostart → Stillhome → On.", { null },
        { a -> a.safeStart(Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"), appDetails(a)) }),
    Step("10. Battery saver: No restrictions", "App info → Power → Battery saver → No restrictions.",
        { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
        { a -> a.safeStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")), appDetails(a)) }),
    Step("11. Lock in recents", "Open Stillhome Settings (long-press home), open Recents, long-press its card → Lock (padlock).", { null }, { }),
    Step("12. Show on lock screen", "Notifications → Stillhome → “Status (lock screen)” → Lock screen: show, and allow notifications on lock screen.",
        { null }, { a -> a.safeStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)) }),
    Step("13. Mute filtered apps' channels", "For each app below: turn off sound/pop-up for its channels (Stillhome re-posts what should ping).",
        { null }, { }),
    Step("14. WRITE_SECURE_SETTINGS (adb)", "Done from the PC: adb shell pm grant lt.stillhome android.permission.WRITE_SECURE_SETTINGS",
        { Gray.canWrite(it) }, { }),
)

/** Apps whose notifications are filtered (anything not always-Ping): their system channels must be muted. */
fun filteredApps(ctx: Context) = Apps.all(ctx).filter { a ->
    val c = Cats.of(a.pkg)
    c in Cats.gated || (c != Cats.CHAT && c !in Store.list("whitelistCats").ifEmpty { listOf(Cats.CHAT, Cats.FINANCE, Cats.SCHOOL) } &&
        a.pkg !in Store.list("neverFilterApps") && c.isNotEmpty())
}

@Composable
fun Permissions(a: Activity) {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { tick++; delay(1500) } } }
    tick.let { }
    T("Install only via adb (avoids Android's “Restricted settings” block).", 13.sp, Dim)
    steps.forEachIndexed { i, s ->
        val auto = s.done(a)
        val done = auto ?: Store.bool("perm.done.$i", false)
        Column(Modifier.padding(vertical = 6.dp)) {
            T((if (done) "✓ " else "") + s.title, 17.sp, if (done) Dim else White)
            T(s.how, 13.sp, Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (i != 10 && i != 12 && i != 13) Btn("Open") { s.open(a) }
                if (auto == null) Btn(if (done) "Undo" else "Mark done") { Store.set("perm.done.$i", if (done) "0" else "1") }
            }
            if (i == 12) filteredApps(a).forEach { app ->
                RowItem(app.label, "mute ›") { a.safeStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.pkg)) }
            }
        }
    }
}
