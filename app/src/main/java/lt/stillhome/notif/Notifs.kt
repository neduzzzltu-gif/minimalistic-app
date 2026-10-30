package lt.stillhome.notif

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.provider.Telephony
import android.telecom.TelecomManager
import lt.stillhome.App
import lt.stillhome.core.Cats
import lt.stillhome.core.Guard
import lt.stillhome.data.Store
import lt.stillhome.detect.Detectors

const val PING = "Ping"
const val DIGEST = "Digest"
const val DROP = "Drop"

/**
 * Notification rules (section 10). Pure functions: [NotifService] calls [decide] and acts on it.
 * kind = what the notification is about, guessed from channel + text (EN + LT keywords).
 */
object Notifs {
    private val otpWord = Regex("(code|kodas|kodą|kodo|kodu|otp|pin|verification|patvirtinimo|slaptažod|password|passcode)", RegexOption.IGNORE_CASE)
    private val otpDigits = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    fun isOtp(text: String) = otpDigits.containsMatchIn(text) && otpWord.containsMatchIn(text)

    /** Kinds that the Instagram / Snapchat split exposes in Settings. */
    val splitKinds = listOf("msg", "snap", "share", "like", "comment", "follow", "story", "streak", "live", "posted", "suggest", "other")

    fun kind(pkg: String, channelId: String, channelName: String, title: String, text: String): String {
        val t = "$title $text".lowercase()
        val ch = "$channelId $channelName".lowercase()
        fun any(vararg w: String) = w.any { it in t }
        return when {
            any("sent you a reel", "sent a reel", "shared a reel", "sent you a post", "shared a post", "sent a post",
                "sent you a spotlight", "shared a spotlight", "atsiuntė įraš", "pasidalijo įraš") -> "share"
            pkg == Detectors.SNAP && any("streak", "⌛", "⏳") -> "streak"
            pkg == Detectors.SNAP && any("sent you a snap", "new snap", "snap from", "sent a snap", "atsiuntė snap") -> "snap"
            any("liked your", "likes your", "patiko jūsų", "patinka jūsų", "reacted to your") -> "like"
            any("commented", "comment on", "mentioned you", "komentavo", "paminėjo") -> "comment"
            any("started following", "follow request", "pradėjo sekti", "accepted your") -> "follow"
            any("is live", "started a live", "live video", "tiesiogin") -> "live"
            any("added to their story", "story", "istorij") -> "story"
            any("posted", "new post", "paskelbė", "just shared", "uploaded") -> "posted"
            any("suggested", "you might know", "recommended", "people you may", "rekomend", "siūlom") -> "suggest"
            any("order", "shipped", "delivered", "delivery", "parcel", "package", "užsakym", "siunt", "pristat") -> "order"
            any("sale", "% off", "discount", "coins", "coupon", "deal", "nuolaid", "akcij", "promo", "free shipping") -> "promo"
            Regex("^[^:]{1,40}: ").containsMatchIn(text) ||   // "Name: text" = chat message (MessagingStyle)
                any("message", "chat", "typing", "žinut") || ch.contains("message") || ch.contains("direct") ||
                ch.contains("chat") || ch.contains("_dm") -> "msg"
            else -> "other"
        }
    }

    /** Calls, SMS, alarms and one-time codes always ping; nothing can change that. */
    fun alwaysPing(ctx: Context, pkg: String, n: Notification, text: String): Boolean {
        if (n.category in setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM, Notification.CATEGORY_REMINDER,
                Notification.CATEGORY_MISSED_CALL)) return true
        if (pkg == Telephony.Sms.getDefaultSmsPackage(ctx)) return true
        if (pkg == ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage) return true
        if (pkg in setOf("com.android.deskclock", "com.google.android.deskclock", "com.android.phone", "com.android.server.telecom")) return true
        return isOtp(text)
    }

    /** Default for a category + kind when the user hasn't chosen anything. */
    fun default(cat: String, kind: String): String {
        val whitelist = Store.list("whitelistCats").ifEmpty { listOf(Cats.CHAT, Cats.FINANCE, Cats.SCHOOL) }
        return when (cat) {
            Cats.CHAT -> PING
            Cats.SOCIAL -> when (kind) {
                "msg", "snap" -> PING
                "like", "comment", "follow", "share" -> DIGEST
                "suggest", "live", "posted" -> DROP
                "streak" -> if (Store.bool("quiz.streaksDie", false)) DROP else DIGEST
                "story" -> if (Store.bool("quiz.storiesDigest", false)) DIGEST else DROP
                else -> DIGEST
            }
            Cats.SHOPS -> when (kind) { "order" -> PING; "promo" -> DROP; else -> DROP }
            Cats.GAMES -> DROP
            Cats.VIDEO -> DIGEST
            else -> if (cat in whitelist) PING else DIGEST
        }
    }

    /**
     * Final decision for one notification.
     * Order: always-ping > never-filter list > focus mode > user choice (kind, then channel) > quiz rules > defaults.
     */
    fun decide(ctx: Context, pkg: String, channelId: String, n: Notification, title: String, text: String, kind: String): String {
        if (alwaysPing(ctx, pkg, n, "$title $text")) return PING
        if (pkg in Store.list("neverFilterApps")) return PING
        val contacts = Store.list("neverFilterContacts")
        if (contacts.any { c -> c.isNotBlank() && (title.contains(c, true) || text.startsWith(c, true)) }) return PING
        val cat = Cats.of(pkg).ifEmpty { Cats.OTHER }
        if (Guard.focusActive()) return if (cat == Cats.GAMES) DROP else DIGEST   // focus: chats too go to the digest
        Store.get("nkind.$pkg.$kind")?.let { return it }
        App.db.dao().channel(pkg, channelId)?.decision?.let { return it }
        if (Store.bool("quiz.likesDrop", false) && kind in setOf("like", "comment", "follow")) return DROP
        return default(cat, kind)
    }

    /** System components (battery, USB, updates...) are never filtered. */
    fun isSystem(ctx: Context, pkg: String): Boolean = if (pkg == "com.android.shell" && Store.bool("debug.shellNotifs", false)) false else try {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ctx.packageManager.getLaunchIntentForPackage(pkg) == null
    } catch (_: Exception) { true }
}
