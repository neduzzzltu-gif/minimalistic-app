package lt.stillhome.detect

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import lt.stillhome.core.Cats
import lt.stillhome.data.Store

/** FREE = no budget (DMs), METERED = needs a session, BLOCK = back out at once (Shorts), UNKNOWN = fail closed. */
enum class Kind { FREE, METERED, BLOCK, UNKNOWN }

/** Result of looking at one screen. target/cat override the app's own (used for Chrome sites). */
data class Screen(val kind: Kind, val name: String, val target: String? = null, val cat: String? = null)

/**
 * One pass over the visible view tree, collecting what the detectors need.
 * View IDs below come from real `uiautomator dump`s taken on the phone (see TESTING.md), not guesses.
 */
class Snapshot(root: AccessibilityNodeInfo) {
    val ids = HashSet<String>()
    val descs = ArrayList<String>()
    val texts = ArrayList<Pair<String, Rect>>()
    var selectedTab: String? = null
    var urlBar: String? = null
    var barTitle: String? = null   // Settings/Security page title (action_bar_title / _expand)
    private var count = 0

    init { walk(root, null) }

    private fun walk(n: AccessibilityNodeInfo, tab: String?) {
        if (count++ > 2500) return                       // hard cap: keeps each event cheap
        val rid = n.viewIdResourceName?.substringAfter(":id/")
        var curTab = tab
        if (rid != null) {
            if (rid == "url_bar") urlBar = n.text?.toString()
            if (rid.startsWith("action_bar_title") && n.text != null) barTitle = n.text.toString()
            if (n.isVisibleToUser) ids.add(rid)
            if (rid.endsWith("_tab")) curTab = rid
        }
        if (n.isSelected && curTab != null) selectedTab = curTab
        if (n.isVisibleToUser) {
            n.contentDescription?.let { descs.add(it.toString()) }
            n.text?.let { t -> Rect().also { n.getBoundsInScreen(it) }.let { texts.add(t.toString() to it) } }
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, curTab) }
    }

    fun has(vararg id: String) = id.any { it in ids }
    fun descStarts(prefix: String) = descs.any { it.startsWith(prefix) }
    /** Text of a page title near the top of the screen. */
    fun topText(options: Set<String>) = texts.firstOrNull { (t, r) -> r.top < 320 && t.trim() in options }?.first?.trim()
}

object Detectors {
    const val IG = "com.instagram.android"
    const val SNAP = "com.snapchat.android"
    const val YT = "com.google.android.youtube"

    /** Apps that have an in-app detector and therefore a "chats free" option. */
    val withDetector = setOf(IG, SNAP)

    fun chatsFree(pkg: String) = pkg in withDetector && Store.str("mode.$pkg", "chats") == "chats"

    /** Classify the current screen of a gated app (or Chrome). */
    fun classify(pkg: String, s: Snapshot): Screen = when (pkg) {
        IG -> if (chatsFree(pkg)) instagram(s) else Screen(Kind.METERED, "Instagram")
        SNAP -> if (chatsFree(pkg)) snapchat(s) else Screen(Kind.METERED, "Snapchat")
        YT -> youtube(s)
        Cats.CHROME -> chrome(s)
        else -> Screen(Kind.METERED, "whole app")
    }

    // Instagram — order matters: overlays (story viewer, reel player) sit on top of the tab pages.
    private fun instagram(s: Snapshot): Screen = when {
        s.has("reel_viewer_title", "reel_viewer_text_container") -> Screen(Kind.METERED, "Stories")
        s.has("clips_video_container", "clips_media_component") -> Screen(Kind.METERED, "Reels")
        s.has("row_thread_composer_edittext") -> Screen(Kind.FREE, "Chat thread")
        s.has("camera_shutter_button") ->
            // The DM camera shows "Message <name>" as header_title; the story camera has no header.
            if (s.has("header_title")) Screen(Kind.FREE, "DM camera") else Screen(Kind.METERED, "Story camera")
        s.selectedTab == "direct_tab" -> Screen(Kind.FREE, "Inbox")
        s.selectedTab == "feed_tab" -> Screen(Kind.METERED, "Feed")
        s.selectedTab == "clips_tab" -> Screen(Kind.METERED, "Reels")
        s.selectedTab == "search_tab" -> Screen(Kind.METERED, "Explore")
        s.selectedTab == "profile_tab" -> Screen(Kind.METERED, "Profile")
        s.has("row_profile_header_imageview", "profile_header_full_name_above_vanity") -> Screen(Kind.METERED, "Profile")
        s.has("action_bar_search_edit_text") -> Screen(Kind.METERED, "Explore")
        else -> Screen(Kind.UNKNOWN, "unknown")
    }

    // Snapchat — the page title ("Chat"/"Stories"/"Spotlight") stays in the tree under the media viewer ("opera").
    private val snapChat = setOf("Chat", "Pokalbiai")
    private val snapStories = setOf("Stories", "Istorijos")
    private val snapSpot = setOf("Spotlight")

    private fun snapchat(s: Snapshot): Screen {
        val title = s.topText(snapChat + snapStories + snapSpot)
        val opera = s.descStarts("opera_content_index")
        return when {
            s.descs.any { it == "Snap Map" } -> Screen(Kind.METERED, "Map")
            title in snapStories -> Screen(Kind.METERED, "Stories")
            title in snapSpot -> Screen(Kind.METERED, "Spotlight")
            s.has("chat_input_text_field") -> Screen(Kind.FREE, "Chat thread")
            title in snapChat -> Screen(Kind.FREE, if (opera) "Snap from a friend" else "Chats")
            s.has("profile_identity_section_display_name") -> Screen(Kind.FREE, "Friend profile")
            opera -> Screen(Kind.METERED, "Story viewer")
            s.has("camera_capture_button", "camera_page") -> Screen(Kind.FREE, "Camera")
            else -> Screen(Kind.UNKNOWN, "unknown")
        }
    }

    // YouTube — Shorts player is blocked; everything else spends the Video budget.
    private fun youtube(s: Snapshot): Screen =
        if (s.has("reel_watch_fragment_root", "reel_recycler", "reel_player_page_container")) Screen(Kind.BLOCK, "Shorts")
        else Screen(Kind.METERED, "YouTube")

    // ---------- Chrome ----------
    val defaultDomains = mapOf(
        "instagram.com" to Cats.SOCIAL, "tiktok.com" to Cats.SOCIAL, "facebook.com" to Cats.SOCIAL,
        "reddit.com" to Cats.SOCIAL, "x.com" to Cats.SOCIAL, "twitter.com" to Cats.SOCIAL,
        "threads.net" to Cats.SOCIAL, "threads.com" to Cats.SOCIAL, "snapchat.com" to Cats.SOCIAL, "pinterest.com" to Cats.SOCIAL,
        "youtube.com" to Cats.VIDEO, "youtu.be" to Cats.VIDEO, "twitch.tv" to Cats.VIDEO, "kick.com" to Cats.VIDEO,
        "temu.com" to Cats.SHOPS, "shein.com" to Cats.SHOPS, "aliexpress.com" to Cats.SHOPS, "aliexpress.us" to Cats.SHOPS,
        "vinted.lt" to Cats.SHOPS, "pigu.lt" to Cats.SHOPS, "amazon.de" to Cats.SHOPS, "amazon.com" to Cats.SHOPS,
        "ebay.com" to Cats.SHOPS, "zalando.lt" to Cats.SHOPS, "wish.com" to Cats.SHOPS,
    )

    fun domains(): Map<String, String> =
        Store.keys("domain.").associate { it.removePrefix("domain.") to Store.str(it, Cats.OTHER) }

    private var lastChrome: Screen = Screen(Kind.FREE, "Chrome")

    private fun chrome(s: Snapshot): Screen {
        // The URL bar hides while scrolling / in fullscreen video: keep the last verdict then.
        val url = s.urlBar ?: return lastChrome
        val clean = url.lowercase().removePrefix("https://").removePrefix("http://")
        val host = clean.substringBefore('/').substringBefore('?').removePrefix("www.").removePrefix("m.")
        val path = clean.substringAfter('/', "")
        val match = domains().entries.firstOrNull { (d, _) -> host == d || host.endsWith(".$d") }
        val r = when {
            match == null -> Screen(Kind.FREE, host)
            (match.key == "youtube.com") && path.startsWith("shorts") -> Screen(Kind.BLOCK, "Shorts")
            Cats.isGatedCat(match.value) && match.value != Cats.UNLABELED ->
                Screen(Kind.METERED, match.key, target = "chrome:${match.key}", cat = match.value)
            else -> Screen(Kind.FREE, host)
        }
        lastChrome = r
        return r
    }
}
