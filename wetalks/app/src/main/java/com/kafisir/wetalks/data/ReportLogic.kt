package com.kafisir.wetalks.data

/** Why someone is reported. [key] is what is stored on the server (the rules accept exactly these five). */
enum class ReportReason(val key: String, val label: String, val hint: String) {
    SPAM("spam", "Spam", "Unwanted ads, links or repeated messages"),
    SCAM("scam", "Scam or fraud", "Tries to trick people or get money"),
    NUDITY("nudity", "Nudity or sexual content", "Sexual or explicit material"),
    HARASSMENT("harassment", "Harassment or hate", "Threats, bullying or hate"),
    OTHER("other", "Something else", "Tell us more in the note");
}

/** What is being reported. The server-side id of every kind is derived by [ReportLogic.targetKey]. */
sealed class ReportTarget {
    /** One person. [context] is the group/channel id when reported from its member list (so its admins can see it too). */
    data class User(val uid: String, val context: ChatRef? = null) : ReportTarget()
    /** One message. [snapshot] is the plain text the reporter's phone could read; nothing else of the chat is sent. */
    data class Message(val ref: ChatRef, val mid: String, val sender: String, val snapshot: String, val type: String, val ts: Long) : ReportTarget()
    /** A whole 1-to-1 chat (with [peer]). */
    data class Chat(val peer: String) : ReportTarget()
    data class Group(val id: String) : ReportTarget()
    data class Channel(val id: String) : ReportTarget()
}

/** Pure rules of the report feature (what is sent, how it is keyed, the daily limit). The database rules enforce the same limits. */
object ReportLogic {
    const val NOTE_MAX = 500
    const val SNAP_MAX = 4000
    const val DAILY_MAX = 10L
    private const val DAY_MS = 86_400_000L
    private val idRe = Regex("^[A-Za-z0-9_-]{1,128}$")

    /** Day number the server rules use for the daily counter (UTC days since 1970). */
    fun dayKey(nowMs: Long): Long = nowMs / DAY_MS

    fun validId(id: String?): Boolean = id != null && idRe.matches(id)

    fun userKey(uid: String) = "user~$uid"
    fun messageKey(cid: String, mid: String) = "msg~$cid~$mid"
    fun chatKey(cid: String) = "chat~$cid"
    fun groupKey(gid: String) = "group~$gid"
    fun channelKey(cid: String) = "channel~$cid"

    /** The key under `reports/<me>/`: one report per person per target. [me] is needed for the 1-to-1 chat id. */
    fun targetKey(t: ReportTarget, me: String): String = when (t) {
        is ReportTarget.User -> userKey(t.uid)
        is ReportTarget.Message -> messageKey(t.ref.id, t.mid)
        is ReportTarget.Chat -> chatKey(dmId(me, t.peer))
        is ReportTarget.Group -> groupKey(t.id)
        is ReportTarget.Channel -> channelKey(t.id)
    }

    /** Same sorted id the chats use. */
    fun dmId(a: String, b: String) = if (a < b) a + "_" + b else b + "_" + a

    /** Who is reported (a person), if the target is about a person. */
    fun reportedPerson(t: ReportTarget): String? = when (t) {
        is ReportTarget.User -> t.uid
        is ReportTarget.Message -> t.sender
        is ReportTarget.Chat -> t.peer
        else -> null
    }

    /** The group or channel whose admins also get a copy, or null (1-to-1 chats are never copied to anyone). */
    fun adminCopyChat(t: ReportTarget): String? = when (t) {
        is ReportTarget.User -> t.context?.takeIf { it.kind != Kind.DM }?.id
        is ReportTarget.Message -> t.ref.takeIf { it.kind != Kind.DM }?.id
        is ReportTarget.Group -> t.id
        is ReportTarget.Channel -> t.id
        is ReportTarget.Chat -> null
    }

    /** Kind string stored on the server. */
    fun kindOf(t: ReportTarget) = when (t) {
        is ReportTarget.User -> "user"; is ReportTarget.Message -> "message"; is ReportTarget.Chat -> "chat"
        is ReportTarget.Group -> "group"; is ReportTarget.Channel -> "channel"
    }

    fun cleanNote(s: String): String = s.trim().take(NOTE_MAX)

    /** Plain text that stands for the reported message; media is described, never uploaded. */
    fun snapshotOf(type: String, text: String): String {
        val label = when (type) { "image" -> "[photo]"; "video" -> "[video]"; "audio" -> "[audio file]"; "voice" -> "[voice message]"; "file" -> "[file]"; "sticker" -> "[sticker]"; "gif" -> "[gif]"; else -> "" }
        val body = text.trim()
        return (if (label.isEmpty()) body else if (body.isEmpty()) label else "$label $body").take(SNAP_MAX)
    }

    /** Problems found before anything is sent, as a sentence for the person; null when the target is fine. */
    fun problem(t: ReportTarget, me: String): String? {
        if (me.isEmpty()) return "You are not signed in."
        val person = reportedPerson(t)
        if (person != null && person == me) return "You can't report yourself."
        return when (t) {
            is ReportTarget.User -> if (!validId(t.uid)) "This person can't be reported." else null
            is ReportTarget.Message -> if (!validId(t.ref.id) || !validId(t.mid) || !validId(t.sender)) "This message can't be reported." else null
            is ReportTarget.Chat -> if (!validId(t.peer)) "This chat can't be reported." else null
            is ReportTarget.Group -> if (!validId(t.id)) "This group can't be reported." else null
            is ReportTarget.Channel -> if (!validId(t.id)) "This channel can't be reported." else null
        }
    }

    /**
     * The counter after one more report: (day, n), or null when the daily limit is used up.
     * [prevDay]/[prevN] are what is stored now (null when never reported).
     */
    fun nextQuota(prevDay: Long?, prevN: Long?, nowMs: Long): Pair<Long, Long>? {
        val today = dayKey(nowMs)
        val n = if (prevDay == today) (prevN ?: 0L) + 1 else 1L
        return if (n > DAILY_MAX) null else today to n
    }
}
