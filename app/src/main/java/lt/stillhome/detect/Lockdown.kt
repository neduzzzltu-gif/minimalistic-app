package lt.stillhome.detect

/**
 * Section 16: once armed, leave these screens immediately. Matching is by activity class
 * (language independent) AND by page title / visible app name in English + Lithuanian.
 * Class names were read with `dumpsys activity` on the phone; LT strings were extracted with
 * `aapt2 dump resources` from the phone's own Settings.apk and SecurityCenter.apk.
 */
object Lockdown {
    val packages = setOf(
        "com.android.settings", "com.miui.securitycenter",
        "com.miui.global.packageinstaller", "com.google.android.packageinstaller", "com.android.packageinstaller",
    )

    /** Pages that turn the blocker off: never opened by any pass. */
    private val a11yClasses = listOf(
        "MiuiAccessibilitySettingsActivity", "AccessibilitySettingsActivity", "AccessibilityDetailsSettingsActivity",
    )
    private val uninstallClasses = listOf("UninstallerActivity", "UninstallUninstalling")
    private val classes = a11yClasses + listOf(
        "NotificationAccessSettingsActivity", "NotificationAccessDetailsActivity",
        "UsageAccessSettingsActivity", "AppUsageAccessSettingsActivity",
        "AutoStartManagementActivity", "AutoStartDetailManagementActivity",
    ) + uninstallClasses

    private val a11yTitles = setOf(
        "Accessibility", "Pritaikymas neįgaliesiems", "Pritaikymas neįgal.", "Pritaikomumas", "Pasiekiamumo nustatymai",
        "Downloaded apps", "Atsisiųstos programos",
    )
    private val uninstallTitles = setOf("Uninstall app", "Pašalinti programą")
    private val titles = a11yTitles + setOf(
        // Notification access
        "Notification read, reply & control", "Pranešimų skaitymas, atsakymas į juos ir tvarkymas",
        "Notification access", "Prieiga prie pranešimų",
        // Usage access
        "App usage data", "Programų naudojimo duomenys",
        "Apps with access to app usage data", "Gali pasiekti progr. naudojimo duomenis",
        // HyperOS Security app autostart
        "Background autostart", "Automatinis paleidimas fone", "Autostart", "Automatinis paleidimas", "Automatinis pasileidimas",
    ) + uninstallTitles

    /**
     * @param ourLabel our app's visible name: any of these system screens showing it is about us
     *   (App info, Battery details, uninstall dialog, default-home picker...).
     * @param skipUninstall the uninstall-screen class/title rules are skipped (home sheet uninstalling
     *   ANOTHER app; the caller has already checked that the screen doesn't mention us).
     */
    fun shouldBlock(pkg: String, className: String?, s: Snapshot?, ourLabel: String, skipUninstall: Boolean = false): String? {
        if (pkg !in packages) return null
        className?.let { c -> classes.firstOrNull { c.endsWith(it) && !(skipUninstall && it in uninstallClasses) }?.let { return "screen $it" } }
        if (s == null) return null
        s.barTitle?.trim()?.let { if (it in titles && !(skipUninstall && it in uninstallTitles)) return "title \"$it\"" }
        if (s.texts.any { it.first.trim() == ourLabel }) return "shows $ourLabel"
        return null
    }

    // ---------- grant pass: our own permission checklist opening the exact page that grants a missing permission ----------

    /** Per checklist step: the pages (class name fragments) that grant it. Anything else stays locked. */
    private val grantPages = mapOf(
        "alarms" to listOf("AlarmsAndReminders"),
        "battery" to listOf("RequestIgnoreBatteryOptimizations"),
        "miuiBattery" to listOf("HiddenAppsConfig", "com.miui.powercenter."),
        "usage" to listOf("UsageAccessSettingsActivity"),
        "notifs" to listOf("AppNotificationSettings", "ChannelNotificationSettings", "NotificationSettingsActivity"),
        "lockscreen" to listOf("AppNotificationSettings", "ChannelNotificationSettings", "NotificationSettingsActivity"),
        "listener" to listOf("NotificationAccessDetailsActivity", "NotificationAccessSettingsActivity"),
        "autostart" to listOf("AutoStartManagementActivity", "AutoStartDetailManagementActivity"),
        "overlay" to listOf("DrawOverlay", "OverlaySettings"),
        "popup" to listOf("PermissionsEditorActivity"),
        "calendar" to listOf("PermissionsEditorActivity"),
        "location" to listOf("PermissionsEditorActivity"),
    )

    /** Buttons that would stop or remove us (App info): a page showing one is never opened by a grant pass. */
    private val dangerTexts = setOf("force stop", "uninstall", "priverstinai sustabdyti", "priverst. sustabdyti", "pašalinti", "pašalinti programą")

    /**
     * True if the grant pass for [step] may open this page: its activity class is one of the step's
     * grant pages, it isn't an accessibility / uninstall page, and it shows no stop/uninstall button.
     * Fails closed: unknown class or no window = no.
     */
    fun grantAllows(step: String, className: String?, s: Snapshot?): Boolean {
        val c = className ?: return false
        if (s == null) return false
        if (a11yClasses.any { c.endsWith(it) } || uninstallClasses.any { c.endsWith(it) }) return false
        if (grantPages[step]?.any { c.contains(it) } != true) return false
        s.barTitle?.trim()?.let { if (it in a11yTitles || it in uninstallTitles) return false }
        if (s.texts.any { it.first.trim().lowercase() in dangerTexts }) return false
        return true
    }
}
