package lt.stillhome.ui

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.stillhome.core.Guard
import lt.stillhome.core.AppEntry
import lt.stillhome.core.Apps
import lt.stillhome.core.Clock
import lt.stillhome.data.Store
import java.time.temporal.ChronoUnit

/**
 * Tap = [onTap], hold ~600 ms = [onHold] (with a haptic bump). Consumes the down so a parent long-press
 * (home → Settings) never fires on top; moving past touch slop cancels so lists still scroll.
 */
fun Modifier.tapOrHold(holdMs: Long = 600, onHold: () -> Unit, onTap: () -> Unit): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    // Screen readers: double-tap = open, double-tap-and-hold = the app sheet.
    semantics { role = Role.Button; onClick { tap(); true }; onLongClick { hold(); true } }
        .indication(src, LocalIndication.current).pointerInput(holdMs) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val press = PressInteraction.Press(down.position)
            scope.launch { src.emit(press) }
            // true = lifted (tap), false = moved away / cancelled, null = still down after holdMs (hold).
            val r: Boolean? = withTimeoutOrNull(holdMs) {
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if (c.changedToUp()) { c.consume(); return@withTimeoutOrNull true }
                    if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            when (r) {
                true -> { scope.launch { src.emit(PressInteraction.Release(press)) }; tap() }
                false -> scope.launch { src.emit(PressInteraction.Cancel(press)) }
                null -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch { src.emit(PressInteraction.Release(press)) }
                    hold()
                    // Swallow the rest of this touch so lifting the finger doesn't also tap.
                    do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                }
            }
        }
    }
}

/** Last time the app was in the foreground (up to 2 years back), null = unknown / never. */
fun lastOpened(ctx: Context, pkg: String): Long? = try {
    val usm = ctx.getSystemService(UsageStatsManager::class.java)
    val now = Clock.now()
    // INTERVAL_BEST quietly drops to daily buckets (~10 days) when no file spans 2 years: ask every bucket size.
    listOf(UsageStatsManager.INTERVAL_YEARLY, UsageStatsManager.INTERVAL_MONTHLY, UsageStatsManager.INTERVAL_WEEKLY, UsageStatsManager.INTERVAL_DAILY)
        .flatMap { usm.queryUsageStats(it, now - 730L * 86_400_000L, now).orEmpty() }
        .filter { it.packageName == pkg }.maxOfOrNull { it.lastTimeUsed }?.takeIf { it > 0 }
        ?: run {   // fallback: raw events of the last week
            val ev = usm.queryEvents(now - 7 * 86_400_000L, now); val e = UsageEvents.Event(); var last: Long? = null
            while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.packageName == pkg && e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.timeStamp }
            last
        }
} catch (_: Exception) { null }

private fun usageAllowed(ctx: Context) = ctx.getSystemService(AppOpsManager::class.java)
    .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED

/** FLAG_SYSTEM stays set on updated system apps too, so this covers both. */
fun isSystemApp(ctx: Context, pkg: String) = try {
    ctx.packageManager.getApplicationInfo(pkg, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
} catch (_: Exception) { false }

/** [days] since last opened (null = never seen), [installDays] since first install; idle counts from the later of the two. */
private class AppFacts(val days: Long?, val installDays: Long, val known: Boolean, val system: Boolean) {
    val idle get() = minOf(days ?: Long.MAX_VALUE, installDays)
}

/** Bumped by MainActivity.onNewIntent (Home button): open app sheets close. */
val homeReset = androidx.compose.runtime.mutableIntStateOf(0)

/** Long-press sheet for one app: when it was last opened + Uninstall / Hide / App info (system apps: Hide only). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(app: AppEntry, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Home button pressed while open: close.
    val reset = remember { homeReset.intValue }
    if (homeReset.intValue != reset) androidx.compose.runtime.LaunchedEffect(Unit) { onDismiss() }
    val facts by produceState<AppFacts?>(null, app.pkg) {
        value = withContext(Dispatchers.IO) {
            fun ago(t: Long) = ChronoUnit.DAYS.between(Clock.date(t), Clock.date()).coerceAtLeast(0)
            val installed = try { ctx.packageManager.getPackageInfo(app.pkg, 0).firstInstallTime } catch (_: Exception) { 0L }
            AppFacts(lastOpened(ctx, app.pkg)?.let(::ago), ago(installed), usageAllowed(ctx), isSystemApp(ctx, app.pkg))
        }
    }
    val f = facts
    val sub = when {
        f == null -> " "
        !f.known -> "Last opened: unknown (no usage access)"
        f.days == null -> "Installed ${f.installDays} days ago, never opened"
        f.days == 0L -> "Opened today"
        f.days == 1L -> "Last opened yesterday"
        else -> "Last opened ${f.days} days ago"
    }
    val stale = f != null && f.known && !f.system && f.idle >= 14
    val title = if (stale) "Not used in ${f!!.idle} days. Uninstall?" else app.label
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Color(0xFF141414), contentColor = White,
        scrimColor = Black.copy(alpha = 0.6f),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Faint)) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (stale) T(app.label, 14.sp, Dim)
            T(title, 20.sp, White, bold = true)
            T(sub, 14.sp, Dim)
            Spacer(Modifier.height(8.dp))
            // Plain start, not safeStart: another app's App info must not hand out a Settings pass.
            val go = { i: Intent -> onDismiss(); try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
            if (f != null && !f.system) SheetBtn("Uninstall", Red) {
                // Lockdown blocks uninstall screens; a 60 s pass lets this one app's dialog through (never ours).
                if (app.pkg != ctx.packageName) Guard.startUninstallPass(app.pkg)
                go(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
            }
            SheetBtn("Hide") { Apps.hide(app.pkg); onDismiss() }
            if (f != null && !f.system) SheetBtn("App info") { go(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))) }
        }
    }
}

@Composable
private fun SheetBtn(text: String, color: Color = White, onClick: () -> Unit) =
    Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1F1F1F)).clickable(onClick = onClick)
        .padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) { T(text, 16.sp, color) }

/** Settings › Hidden apps: content only (the caller supplies the page frame). */
@Composable
internal fun HiddenAppsPage() {
    val ctx = LocalContext.current
    val map by Store.map.collectAsState()
    val hidden = map["hiddenApps"]?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
    if (hidden.isEmpty()) { T("No hidden apps. Long-press an app in the app list to hide it.", 14.sp, Dim); return }
    Label("Hidden from the app list, search and home")
    SetGroup {
        hidden.sortedBy { Apps.label(ctx, it).lowercase() }.forEachIndexed { i, p ->
            if (i > 0) SetDivider0()
            ValueRow(Apps.label(ctx, p), "Unhide", color = White) { Apps.unhide(p) }
        }
    }
}

