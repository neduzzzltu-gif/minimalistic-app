package lt.stillhome.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import lt.stillhome.R
import lt.stillhome.core.Cats
import lt.stillhome.core.Clock
import lt.stillhome.core.Decision
import lt.stillhome.core.Gray
import lt.stillhome.core.Guard
import lt.stillhome.data.Store
import lt.stillhome.detect.Detectors
import lt.stillhome.detect.Kind
import lt.stillhome.detect.Lockdown
import lt.stillhome.detect.Screen
import lt.stillhome.detect.Snapshot
import lt.stillhome.ui.GateActivity
import lt.stillhome.ui.LabelActivity
import lt.stillhome.ui.MainActivity

/**
 * Enforcement (section 15). Watches which app/screen is in front and:
 *  - gated screen without a session -> Home -> Gate (or Lock screen when locked)
 *  - YouTube Shorts -> Back
 *  - lockdown screens (accessibility / notification access / our App info...) -> Home
 *  - picture-in-picture of a gated app without a session -> dismissed
 * Session expiry is driven by [GuardService], which calls [recheck].
 */
class GuardA11yService : AccessibilityService() {

    /** What is in front right now; read by GuardService for metering and the status line. */
    data class Fg(val pkg: String, val target: String, val cat: String, val screen: Screen)

    companion object {
        @Volatile var instance: GuardA11yService? = null; private set
        @Volatile var fg: Fg? = null; private set
        fun running() = instance != null
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastContentCheck = 0L
    private var lastEnforce = 0L
    private var lastEnforceTarget = ""
    private var lastBack = 0L
    private var unknownSince = 0L
    private var imes: Set<String> = emptySet()
    private lateinit var ourLabel: String

    override fun onServiceConnected() {
        instance = this
        ourLabel = getString(R.string.app_name)
        imes = getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
        GuardService.start(this)   // make sure the timers are running too
    }

    override fun onDestroy() { instance = null; fg = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        // Window-list changes (PiP appearing, etc.) have no package name.
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) { checkPip(); return }
        val pkg = e.packageName?.toString() ?: return
        if (pkg == "com.android.systemui" || pkg in imes) {
            // Shade / keyboard / PiP menu: never changes what app is "in front", but PiP shows up here.
            if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) checkPip()
            return
        }
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> { checkPip(); return }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Content changes only matter inside scoped apps (gated, Chrome, lockdown targets). Throttled for battery.
                if (!inScope(pkg)) return
                val t = SystemClock.uptimeMillis()
                if (t - lastContentCheck < 400) return
                lastContentCheck = t
                handle(pkg, null)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handle(pkg, e.className?.toString())
        }
    }

    private fun inScope(pkg: String) = pkg == Cats.CHROME || pkg in Lockdown.packages || Cats.isGated(this, pkg)

    /** Re-evaluate whatever is in front now (called after a session expires or focus starts). */
    fun recheck() = main.post { rootInActiveWindow?.packageName?.toString()?.let { handle(it, null) } }

    private fun handle(eventPkg: String, cls: String?) {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: eventPkg
        if (pkg == packageName) { leaveGated(); return }   // our own Home / Gate

        // ---- lockdown (protect the off switch) ----
        if (pkg in Lockdown.packages && Guard.lockdownArmed()) {
            val why = Lockdown.shouldBlock(pkg, if (pkg == eventPkg) cls else null, root?.let { Snapshot(it) }, ourLabel)
            if (why != null) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                Guard.log("lockdown", "$pkg: $why")
                toast("Locked: $ourLabel's settings are protected")
                return
            }
        }

        if (!inScope(pkg) || pkg in Lockdown.packages) { leaveGated(); return }

        // ---- brand-new app: label it first ----
        if (Cats.needsLabel(this, pkg)) {
            goHomeThen(Intent(this, LabelActivity::class.java).putExtra("pkg", pkg))
            return
        }

        val snap = root?.let { Snapshot(it) }
        val screen = if (snap == null) Screen(Kind.UNKNOWN, "no window") else Detectors.classify(pkg, snap)
        val cat = screen.cat ?: Cats.of(pkg)
        val target = screen.target ?: pkg

        when (screen.kind) {
            Kind.BLOCK -> {
                // Shorts: Back first. Ignore the events of the back animation (1.2 s); if Shorts is still
                // there after that (deep link, Shorts tab as start page), go Home.
                val now = SystemClock.uptimeMillis()
                when {
                    now - lastBack < 1200 -> {}
                    now - lastBack < 5000 -> { performGlobalAction(GLOBAL_ACTION_HOME); lastBack = 0 }
                    else -> { performGlobalAction(GLOBAL_ACTION_BACK); toast("Shorts are blocked"); lastBack = now }
                }
            }
            Kind.FREE -> {
                unknownSince = 0
                if (pkg == Cats.CHROME) { leaveGated(); return }    // a normal website
                setFg(Fg(pkg, target, cat, screen))
            }
            Kind.METERED, Kind.UNKNOWN -> {
                if (screen.kind == Kind.UNKNOWN) {
                    noteUnknown(pkg, snap)
                    // Fail closed, but not on splash/loading frames: re-check after a short grace period.
                    if (Clock.now() - unknownSince < 1500) {
                        setFg(Fg(pkg, target, cat, screen))   // metered meanwhile
                        main.postDelayed({ recheck() }, 1600)
                        return
                    }
                } else unknownSince = 0
                when (val d = Guard.decide(target, pkg, cat)) {
                    Decision.Allow -> setFg(Fg(pkg, target, cat, screen))
                    else -> enforce(d, target, pkg, cat)
                }
            }
        }
    }

    /** FAIL CLOSED: an unidentified screen is metered; if it lasts >5 s, flag the detector as broken. */
    private fun noteUnknown(pkg: String, snap: Snapshot?) {
        val now = Clock.now()
        android.util.Log.d("StillDetector", "unknown $pkg tab=${snap?.selectedTab} ids=${snap?.ids?.sorted()?.take(40)}")
        if (unknownSince == 0L) unknownSince = now
        else if (now - unknownSince > 5000 && Store.long("broken.$pkg", 0) < now - 3600_000) {
            Store.set("broken.$pkg", now.toString())
            // Fingerprint (view IDs) so the detector can be fixed from Settings → Stats / logcat.
            val ids = snap?.ids?.sorted()?.take(25)?.joinToString(",") ?: "-"
            Guard.log("detector", "unknown screen in $pkg: $ids")
            android.util.Log.w("StillDetector", "unknown $pkg: $ids")
        }
    }

    private fun setFg(f: Fg) {
        val first = fg?.pkg != f.pkg
        fg = f
        if (Gray.enabledFor(f.pkg)) Gray.set(this, true, force = first) else Gray.set(this, false)
    }

    private fun leaveGated() {
        if (fg != null) { fg = null; Gray.set(this, false) }
    }

    private fun enforce(d: Decision, target: String, pkg: String, cat: String) {
        val now = SystemClock.uptimeMillis()
        if (target == lastEnforceTarget && now - lastEnforce < 1500) return   // one Gate per open, not per event
        lastEnforce = now; lastEnforceTarget = target
        leaveGated()
        val i = Intent(this, GateActivity::class.java)
            .putExtra("target", target).putExtra("pkg", pkg).putExtra("cat", cat)
        if (d is Decision.Locked) i.putExtra("mode", "lock").putExtra("why", d.why).putExtra("until", d.until)
        else i.putExtra("mode", "gate")
        goHomeThen(i)
    }

    /** Called by GuardService when a session ends while its app is in front. */
    fun timesUp(target: String, pkg: String, sessionId: Long) {
        main.post {
            lastPip = 0; checkPip()
            if (fg?.target != target) return@post
            leaveGated()
            goHomeThen(Intent(this, GateActivity::class.java).putExtra("mode", "timesup")
                .putExtra("target", target).putExtra("pkg", pkg).putExtra("session", sessionId))
        }
    }

    /**
     * Go Home, then show [i] on top. Done through our own home screen (it is the launcher):
     * HyperOS runs GLOBAL_ACTION_HOME asynchronously, so "Home then start the Gate" raced and the
     * Home transition could land on top of the Gate. MainActivity opens [i] once it is in front.
     */
    private fun goHomeThen(i: Intent) {
        val home = Intent(this, MainActivity::class.java)
            // NO_USER_ACTION: the gated app gets no onUserLeaveHint, so it can't auto-enter picture-in-picture.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            .putExtra(MainActivity.EXTRA_NEXT, i)
        try { startActivity(home) } catch (_: Exception) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            main.postDelayed({ try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }, 600)
        }
    }

    // ---------- picture-in-picture ----------
    private var lastPip = 0L

    /** Also called by GuardService when a session ends or focus starts. */
    fun checkPip() {
        val pip = windows.firstOrNull { it.isInPictureInPictureMode } ?: return
        val pkg = pipPackage(pip)
        android.util.Log.d("StillPip", "pip window title=${pip.title} pkg=$pkg")
        if (pkg == null) return
        if (!Cats.isGated(this, pkg)) return
        val allowed = Guard.openSessions().any { it.pkg == pkg && Clock.now() < it.plannedEnd } && !Guard.focusActive()
        if (allowed) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPip < 2500) return
        lastPip = now
        closePip(pip)
    }

    /**
     * Dismiss a PiP window. HyperOS's PiP menu ignores injected taps and drag-to-dismiss, so instead:
     * bring the app back to full screen (its launch intent re-opens the pinned task); the normal
     * enforcement then sees a gated app without a session and sends it to the Gate. Because Home is
     * started with FLAG_ACTIVITY_NO_USER_ACTION, the app gets no "user leave" and can't re-enter PiP.
     */
    /** HyperOS gives PiP windows no root node; the window title is the app's label then. */
    private fun pipPackage(w: AccessibilityWindowInfo): String? =
        w.root?.packageName?.toString() ?: w.title?.toString()?.let { t -> lt.stillhome.core.Apps.all(this).firstOrNull { it.label == t }?.pkg }

    private fun closePip(w: AccessibilityWindowInfo) {
        val pkg = pipPackage(w) ?: return
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) } catch (_: Exception) {}
        lastEnforceTarget = ""   // make sure the following enforcement isn't debounced
        Guard.log("pip", "closed PiP of $pkg")
    }

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
}
