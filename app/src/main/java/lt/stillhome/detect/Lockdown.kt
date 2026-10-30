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

    private val classes = listOf(
        "MiuiAccessibilitySettingsActivity", "AccessibilitySettingsActivity", "AccessibilityDetailsSettingsActivity",
        "NotificationAccessSettingsActivity", "NotificationAccessDetailsActivity",
        "UsageAccessSettingsActivity", "AppUsageAccessSettingsActivity",
        "AutoStartManagementActivity", "AutoStartDetailManagementActivity",
        "UninstallerActivity", "UninstallUninstalling",
    )

    private val titles = setOf(
        // Accessibility
        "Accessibility", "Pritaikymas neįgaliesiems", "Pritaikymas neįgal.", "Pritaikomumas", "Pasiekiamumo nustatymai",
        "Downloaded apps", "Atsisiųstos programos",
        // Notification access
        "Notification read, reply & control", "Pranešimų skaitymas, atsakymas į juos ir tvarkymas",
        "Notification access", "Prieiga prie pranešimų",
        // Usage access
        "App usage data", "Programų naudojimo duomenys",
        "Apps with access to app usage data", "Gali pasiekti progr. naudojimo duomenis",
        // HyperOS Security app autostart
        "Background autostart", "Automatinis paleidimas fone", "Autostart", "Automatinis paleidimas", "Automatinis pasileidimas",
        // Uninstall dialog
        "Uninstall app", "Pašalinti programą",
    )

    /**
     * @param ourLabel our app's visible name: any of these system screens showing it is about us
     *   (App info, Battery details, uninstall dialog, default-home picker...).
     */
    fun shouldBlock(pkg: String, className: String?, s: Snapshot?, ourLabel: String): String? {
        if (pkg !in packages) return null
        className?.let { c -> classes.firstOrNull { c.endsWith(it) }?.let { return "screen $it" } }
        if (s == null) return null
        s.barTitle?.trim()?.let { if (it in titles) return "title \"$it\"" }
        if (s.texts.any { it.first.trim() == ourLabel }) return "shows $ourLabel"
        return null
    }
}
