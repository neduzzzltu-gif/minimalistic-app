package lt.stillhome.core

import android.content.Context
import lt.stillhome.data.Store

/**
 * A named group of apps that is blocked, either always or inside its time periods.
 * Members are package names, or "cat:Social" style entries meaning "every app with that label".
 * Stored as "group.<id>" (one line, tab-separated) so every edit goes through [Rules.change]
 * (loosening = waits [Rules.DELAY]).
 */
data class Group(val id: String, val name: String, val on: Boolean, val always: Boolean,
                 val members: List<String>, val windows: List<Window>) {
    fun encode() = listOf(name.replace('\t', ' ').ifBlank { "Group" }, if (on) "1" else "0", if (always) "always" else "times",
        members.joinToString(","), windows.joinToString(";") { it.encode() }).joinToString("\t")

    fun has(pkg: String) = pkg in members || members.any { it.startsWith("cat:") && Cats.of(pkg) == it.removePrefix("cat:") }

    /** "Snapchat & 3 other", "Social, Video". */
    fun summary(ctx: Context): String {
        val cats = members.filter { it.startsWith("cat:") }.map { it.removePrefix("cat:") }
        val apps = members.filterNot { it.startsWith("cat:") }.map { Apps.label(ctx, it) }
        val all = cats + apps
        return when {
            all.isEmpty() -> "no apps yet"
            all.size == 1 -> all[0]
            else -> "${all[0]} & ${all.size - 1} other"
        }
    }

    fun schedule() = if (always) "Always" else windows.joinToString(", ") { it.label() }.ifEmpty { "No times set" }

    companion object {
        fun decode(id: String, s: String): Group? {
            val p = s.split('\t'); if (p.size < 5) return null
            return Group(id, p[0], p[1] == "1", p[2] == "always", p[3].split(',').filter { it.isNotBlank() },
                p[4].split(';').mapNotNull { Window.decode(it) })
        }
    }
}

object Groups {
    fun key(id: String) = "group.$id"
    fun ids() = Store.list("groupIds")
    fun all(): List<Group> = ids().mapNotNull { id -> Store.get(key(id))?.let { Group.decode(id, it) } }
    fun get(id: String) = Store.get(key(id))?.let { Group.decode(id, it) }
    fun newId() = "g${Clock.now()}"
    fun register(id: String) { if (id !in ids()) Store.setList("groupIds", ids() + id) }

    /** If [pkg] is blocked by a switched-on group right now: (why, until). */
    fun blockedNow(ctx: Context, pkg: String, now: Long = Clock.now()): Pair<String, Long>? {
        if (pkg == ctx.packageName) return null
        for (g in all()) {
            if (!g.on || !g.has(pkg)) continue
            if (g.always) return "${g.name} is blocked" to Long.MAX_VALUE   // no end: until you switch the group off
            Blocked.blockedUntil(g.windows, now)?.let { return "${g.name}: blocked right now" to it }
        }
        return null
    }

    /** Does this edit block less than before? (switched off, apps removed, fewer blocked minutes, deleted) */
    fun looser(old: String?, new: String?): Boolean {
        val o = old?.let { Group.decode("", it) } ?: return false
        if (!o.on) return false
        val n = new?.let { Group.decode("", it) } ?: return true
        if (!n.on) return true
        if (o.members.any { it !in n.members }) return true
        if (o.always && !n.always) return true
        if (!o.always && !n.always) {
            val oc = Blocked.coverage(o.windows); oc.andNot(Blocked.coverage(n.windows)); return !oc.isEmpty
        }
        return false
    }
}
