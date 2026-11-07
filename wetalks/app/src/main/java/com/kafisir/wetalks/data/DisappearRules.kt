package com.kafisir.wetalks.data

import org.json.JSONObject

/**
 * Pure logic of disappearing messages (no Android, no Firebase): the allowed timers, the expiry time written into a message,
 * "is it expired", the hourly index bucket used by the server sweep, which messages of a snapshot are due, the system line, the
 * ledger of media files that must be removed from this phone / the media server.
 *
 * Times are milliseconds on the SERVER clock (the phone adds Firebase's serverTimeOffset before calling in here).
 */
object DisappearRules {
    /** The timer choices in seconds, in the order the sheet shows them. 0 = off. The database rules accept exactly these. */
    val CHOICES = listOf(0, 24 * 3600, 7 * 24 * 3600, 90 * 24 * 3600)
    const val HOUR_MS = 3_600_000L
    /** The rules let a message's exp be at most this much beyond (now + timer): clock drift allowance. */
    const val TOLERANCE_MS = 300_000L

    fun isValidTimer(secs: Long) = secs.toInt().toLong() == secs && secs.toInt() in CHOICES

    fun label(secs: Int) = when (secs) { 0 -> "Off"; 86400 -> "24 hours"; 604800 -> "7 days"; 7776000 -> "90 days"; else -> if (secs % 86400 == 0) "${secs / 86400} days" else "${secs / 3600} hours" }

    /** Absolute expiry for a message sent at [nowMs] under a timer of [secs]; 0 when the timer is off (the message then carries no exp). */
    fun computeExp(nowMs: Long, secs: Int): Long = if (secs <= 0) 0L else nowMs + secs * 1000L

    /** Hidden in the UI from this moment on. A message without exp (0) never expires. */
    fun isExpired(exp: Long, nowMs: Long) = exp > 0 && exp <= nowMs

    /** Deletable in the database from this moment on (the rules say exp < now, strictly). */
    fun isDeletable(exp: Long, nowMs: Long) = exp > 0 && exp < nowMs

    /** Hour bucket of the server index `expiry/<bucket>/<cid>/<mid>`. */
    fun bucket(exp: Long): Long = exp / HOUR_MS

    /** The index path of one message. */
    fun indexPath(exp: Long, cid: String, mid: String) = "expiry/${bucket(exp)}/$cid/$mid"

    /** Buckets whose whole hour lies in the past (so everything inside is due), from [lastDone]+1 up to the last finished hour. */
    fun dueBuckets(lastDone: Long, nowMs: Long, maxBuckets: Int = 48): List<Long> {
        val newest = nowMs / HOUR_MS - 1
        if (lastDone >= newest) return emptyList()
        val first = maxOf(lastDone + 1, newest - maxBuckets + 1)
        return (first..newest).toList()
    }

    /** Ids (in order) of the messages in [items] (id to exp) that can be deleted now. */
    fun dueIds(items: List<Pair<String, Long>>, nowMs: Long): List<String> = items.filter { isDeletable(it.second, nowMs) }.map { it.first }

    /** The next moment something expires after [nowMs], or 0 when nothing is waiting. */
    fun nextExpiry(exps: Collection<Long>, nowMs: Long): Long = exps.filter { it > nowMs }.minOrNull() ?: 0L

    /** The clean-up only deletes messages that expired at least this long ago, so a clock estimate that is a little off never makes the server refuse a delete. */
    const val SAFETY_MS = 3000L

    /** How long to wait before running a clean-up for an expiry at [exp]: the expiry itself, the safety margin and a little extra. */
    fun delayUntil(exp: Long, nowMs: Long): Long = (exp - nowMs + SAFETY_MS + 500L).coerceAtLeast(0L)

    /** Who may change the timer. Mirrors the database rules: a DM either person, a group only admins, a channel its owner and admins. */
    fun mayChange(kind: Kind, isAdmin: Boolean) = when (kind) { Kind.DM -> true; Kind.GROUP, Kind.CHANNEL -> isAdmin }

    /** The text of the system line. [actor] is the display name of the person who changed the timer. */
    fun systemText(actor: String, secs: Int): String {
        val who = actor.trim().ifEmpty { "Someone" }
        return if (secs <= 0) "$who turned off disappearing messages" else "$who turned on disappearing messages: ${label(secs)}"
    }

    /** On the phone of the person who made the change the line starts with "You" instead of their own name. */
    fun systemTextFor(text: String, actorName: String, mine: Boolean): String {
        val n = actorName.trim()
        return if (mine && n.isNotEmpty() && text.startsWith(n)) "You" + text.removePrefix(n) else text
    }

    /** One short line for the chat list / notifications / quoted replies. */
    fun systemPreview(text: String) = text.take(90)

    /** The small line under the chat title, e.g. "Disappearing · 24 hours". */
    fun subtitle(secs: Int) = if (secs <= 0) null else "Disappearing · ${label(secs)}"

    // ------------------------------------------------------------------------------------------ ledger of media tied to an expiring message
    /** One uploaded or received media file whose message has an exp: [mine] = this phone uploaded it (so only this phone can delete it on the server). */
    data class Entry(val exp: Long, val mine: Boolean)

    fun parseLedger(json: String?): Map<String, Entry> = try {
        val o = JSONObject(json ?: "{}")
        o.keys().asSequence().associateWith { k -> val a = o.getJSONArray(k); Entry(a.getLong(0), a.optInt(1, 0) == 1) }
    } catch (_: Exception) { emptyMap() }

    fun writeLedger(m: Map<String, Entry>): String {
        val o = JSONObject()
        for ((k, e) in m) o.put(k, org.json.JSONArray().put(e.exp).put(if (e.mine) 1 else 0))
        return o.toString()
    }

    /** Adds or keeps [id]; an upload by this phone is never downgraded to "received". Returns the same map when nothing changes. */
    fun noteMedia(m: Map<String, Entry>, id: String, exp: Long, mine: Boolean, cap: Int = 2000): Map<String, Entry> {
        if (id.isEmpty() || exp <= 0) return m
        val old = m[id]
        val e = Entry(exp, mine || old?.mine == true)
        if (old == e) return m
        val out = LinkedHashMap(m); out[id] = e
        if (out.size > cap) out.entries.sortedBy { it.value.exp }.take(out.size - cap).forEach { out.remove(it.key) }
        return out
    }

    /** Media ids whose exp has passed. */
    fun dueMedia(m: Map<String, Entry>, nowMs: Long): List<String> = m.filter { isDeletable(it.value.exp, nowMs) }.keys.toList()
}
