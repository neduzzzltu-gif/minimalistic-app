package lt.stillhome.core

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import lt.stillhome.data.Store

/** Category labels. User-created labels are plain strings stored next to these. */
object Cats {
    const val SOCIAL = "Social"; const val CHAT = "Chat"; const val SCHOOL = "School/Work"
    const val SHOPS = "Shops"; const val VIDEO = "Video"; const val MUSIC = "Music"
    const val GAMES = "Games"; const val TOOLS = "Tools"; const val FINANCE = "Finance"
    const val BROWSER = "Browser"; const val OTHER = "Other"
    /** Pseudo-label for apps nobody has labelled yet. Treated as gated until labelled. */
    const val UNLABELED = ""

    val builtIn = listOf(SOCIAL, CHAT, SCHOOL, SHOPS, VIDEO, MUSIC, GAMES, TOOLS, FINANCE, BROWSER, OTHER)
    val gated = setOf(SOCIAL, VIDEO, GAMES, SHOPS, BROWSER)
    /** Categories that own a daily budget. Other browsers spend the Social budget. */
    val budgeted = listOf(SOCIAL, VIDEO, GAMES, SHOPS)
    fun budgetCat(cat: String) = if (cat in budgeted) cat else SOCIAL   // browsers + unlabelled apps spend Social

    const val CHROME = "com.android.chrome"

    /** Known social apps: always Social, cannot be relabelled. */
    val lockedSocial = setOf(
        "com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.snapchat.android",
        "com.facebook.katana", "com.facebook.lite", "com.reddit.frontpage", "com.twitter.android",
        "com.instagram.barcelona", "com.bereal.ft", "com.pinterest", "com.tumblr", "com.vkontakte.android",
        "com.lemon8", "com.ss.android.ugc.aweme", "com.instagram.lite", "com.zhiliaoapp.musically.go",
        "app.bsky.android", "org.joinmastodon.android", "com.linkedin.android.lite",
    )

    private val known = mapOf(
        // Chat (never gated by default)
        "com.whatsapp" to CHAT, "com.facebook.orca" to CHAT, "com.discord" to CHAT, "com.viber.voip" to CHAT,
        "org.telegram.messenger" to CHAT, "org.thoughtcrime.securesms" to CHAT, "com.google.android.dialer" to CHAT,
        "com.android.contacts" to CHAT, "com.google.android.apps.messaging" to CHAT, "com.android.mms" to CHAT,
        "com.android.incallui" to CHAT, "com.skype.raider" to CHAT,
        // Video
        "com.google.android.youtube" to VIDEO, "com.netflix.mediaclient" to VIDEO, "com.crunchyroll.crunchyroid" to VIDEO,
        "tv.twitch.android.app" to VIDEO, "com.disney.disneyplus" to VIDEO, "com.amazon.avod.thirdpartyclient" to VIDEO,
        "com.kick.mobile" to VIDEO, "com.google.android.apps.youtube.kids" to VIDEO, "com.hbo.hbonow" to VIDEO,
        "com.wbd.stream" to VIDEO,
        // Shops
        "com.einnovation.temu" to SHOPS, "com.zzkko" to SHOPS, "com.alibaba.aliexpresshd" to SHOPS,
        "com.alibaba.intl.android.apps.poseidon" to SHOPS, "fr.vinted" to SHOPS, "lt.pigu.pigu" to SHOPS,
        "com.mi.global.shop" to SHOPS, "com.thehutgroup.ecommerce.myprotein" to SHOPS,
        "com.amazon.mShop.android.shopping" to SHOPS, "com.ebay.mobile" to SHOPS, "com.contextlogic.wish" to SHOPS,
        "de.zalando.mobile" to SHOPS, "com.joom" to SHOPS, "com.shein.android" to SHOPS, "com.inditex.zara" to SHOPS,
        "com.hm.goe" to SHOPS, "lt.varle.app" to SHOPS,
        // Music
        "com.spotify.music" to MUSIC, "com.soundcloud.android" to MUSIC, "com.google.android.apps.youtube.music" to MUSIC,
        "com.google.android.apps.podcasts" to MUSIC, "com.miui.fm" to MUSIC, "deezer.android.app" to MUSIC,
        // Games
        "com.block.juggle" to GAMES, "com.robtopx.geometryjump" to GAMES, "com.chess" to GAMES, "com.rstgames.durak" to GAMES,
        "com.valvesoftware.android.steam.community" to TOOLS,
        // Finance
        "com.revolut.revolut" to FINANCE, "com.revolut.junior" to FINANCE, "se.seb.lithuania" to FINANCE,
        "lt.swedbank.mobile" to FINANCE, "com.tradingview.tradingviewapp" to FINANCE, "net.metaquotes.metatrader5" to FINANCE,
        "com.tradelocker.mobile" to FINANCE, "com.google.android.apps.walletnfcrel" to FINANCE, "lt.luminor.mobile" to FINANCE,
        "com.paypal.android.p2pmobile" to FINANCE,
        // School / work
        "lt.zet.tamo" to SCHOOL, "com.microsoft.teams" to SCHOOL, "com.microsoft.office.word" to SCHOOL,
        "com.microsoft.office.powerpoint" to SCHOOL, "com.microsoft.office.excel" to SCHOOL, "com.duolingo" to SCHOOL,
        "com.todoist" to SCHOOL, "com.google.android.apps.classroom" to SCHOOL, "com.google.android.apps.docs" to SCHOOL,
        "com.google.android.apps.docs.editors.docs" to SCHOOL, "cn.wps.xiaomi.abroad.lite" to SCHOOL,
        "com.openai.chatgpt" to TOOLS, "com.anthropic.claude" to TOOLS, "com.deepseek.chat" to TOOLS, "ai.x.grok" to TOOLS,
        "com.miui.notes" to SCHOOL, "com.google.android.keep" to SCHOOL,
        // Tools / other
        "com.android.chrome" to BROWSER, "lt.maxima.aciu" to TOOLS, "lt.iki.app" to TOOLS, "com.lidl.eci.lidlplus" to TOOLS,
        "com.smart_id" to TOOLS, "com.trafi.android.tr" to TOOLS, "ee.mtakso.client" to TOOLS, "com.ubercab" to TOOLS,
        "com.booking" to TOOLS, "com.strava" to TOOLS, "com.hevy" to TOOLS, "com.fitbit.FitbitMobile" to TOOLS,
        "com.cronometer.android.gold" to TOOLS, "de.komoot.android" to TOOLS,
    )

    fun isBrowser(ctx: Context, pkg: String) = pkg in browsers(ctx)

    private var browserCache: Set<String>? = null
    /** Every app that handles http links, except Chrome. These are gated whole-app. */
    fun browsers(ctx: Context): Set<String> = browserCache ?: run {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        ctx.packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .filter { it != CHROME && it != ctx.packageName }
            // Apps that merely handle *some* links (Instagram, YouTube...) also match http; keep real browsers only:
            .filter { pkg -> ctx.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, Uri.parse("http://random-${pkg.hashCode()}.example.org/x")).setPackage(pkg), 0).isNotEmpty() }
            .toSet().also { browserCache = it }
    }

    /** Best guess for an app nobody labelled yet, or null when we really don't know. */
    fun autoLabel(ctx: Context, pkg: String): String? {
        known[pkg]?.let { return it }
        if (pkg in lockedSocial) return SOCIAL
        if (isBrowser(ctx, pkg)) return BROWSER
        val ai = try { ctx.packageManager.getApplicationInfo(pkg, 0) } catch (_: Exception) { return null }
        return when (ai.category) {
            ApplicationInfo.CATEGORY_GAME -> GAMES
            ApplicationInfo.CATEGORY_SOCIAL -> SOCIAL
            ApplicationInfo.CATEGORY_AUDIO -> MUSIC
            ApplicationInfo.CATEGORY_VIDEO -> VIDEO
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> SCHOOL
            ApplicationInfo.CATEGORY_MAPS, ApplicationInfo.CATEGORY_IMAGE, ApplicationInfo.CATEGORY_ACCESSIBILITY -> TOOLS
            ApplicationInfo.CATEGORY_NEWS -> OTHER
            else -> if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) TOOLS else null
        }
    }

    /** Current label of an app ("" = unlabelled). */
    fun of(pkg: String): String = when {
        pkg in lockedSocial -> SOCIAL
        else -> Store.get("label.$pkg") ?: UNLABELED
    }

    fun isGatedCat(cat: String) = cat in gated || cat == UNLABELED

    /**
     * An unlabelled *user-installed, launchable* app needs a label and is gated until it gets one.
     * System components (keyboard, SystemUI, permission dialogs...) never need labels.
     */
    fun needsLabel(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName || of(pkg) != UNLABELED) return false
        if (Store.get("label.pending.$pkg") != null) return false   // labelled, waiting out the 24 h rule
        val ai = try { ctx.packageManager.getApplicationInfo(pkg, 0) } catch (_: Exception) { return false }
        if (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return false
        return ctx.packageManager.getLaunchIntentForPackage(pkg) != null
    }

    /** Is this app gated? Chrome itself is not (its gated sites are, see Detectors). */
    fun isGated(ctx: Context, pkg: String): Boolean =
        pkg != CHROME && (of(pkg).let { it != UNLABELED && it in gated } ||
            Store.get("label.pending.$pkg") != null && of(pkg) == UNLABELED || needsLabel(ctx, pkg))

    /** All labels, built-in first, then user-created ones. */
    fun all(): List<String> = builtIn + Store.list("customLabels")
}
