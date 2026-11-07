package com.kafisir.wetalks.data

import org.json.JSONArray
import org.json.JSONObject

enum class BackupFreq(val key: String, val label: String, val ms: Long) {
    OFF("off", "Off", 0L), DAILY("daily", "Daily", 86_400_000L), WEEKLY("weekly", "Weekly", 7 * 86_400_000L);
    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } ?: OFF }
}

/**
 * The rules of the chat backup, as plain functions (no Android, no Firebase), so they can be unit tested:
 * what is new since the last backup, when a new full backup starts, which old backups to delete, when the next backup is due and how an interrupted backup resumes.
 */
object BackupPlan {
    // ---------------------------------------------------------------------------------------- what is new
    /** The place in one chat's history up to which everything is already in a backup. Message ids are Firebase push ids: they sort by time. */
    class Mark(val key: String, val ts: Long)

    /** Messages sent within the last day before a backup started are always saved again by the next one (a message written offline gets its id before it reaches the server). */
    const val OVERLAP_MS = 86_400_000L

    /** True when a message is not yet covered by [mark] (no mark = everything is new). */
    fun isNew(id: String, mark: Mark?) = mark == null || id > mark.key

    /**
     * Works out the mark to store after a backup, one message at a time (messages come in id order).
     * The mark moves over a message only when (1) it is older than [OVERLAP_MS] at the start of the run and (2) every message before it was readable,
     * so a message that could not be opened yet (missing key) is tried again next time instead of being skipped for ever.
     */
    class MarkTracker(private val prev: Mark?, runStart: Long) {
        private val cutoff = runStart - OVERLAP_MS
        private var blocked = false
        var mark: Mark? = prev; private set
        fun add(id: String, ts: Long, readable: Boolean) {
            if (blocked) return
            if (!readable) { blocked = true; return }
            if (ts in 1..cutoff) mark = Mark(id, ts) else blocked = true
        }
    }

    // ---------------------------------------------------------------------------------------- generations and retention
    /** What the engine knows about one backup file on this phone (from its stored manifest). */
    class Info(val id: String, val created: Long, val gen: String, val seq: Int, val full: Boolean, val size: Long, val encrypted: Boolean = false)

    const val MAX_INCREMENTALS = 10
    const val FULL_EVERY_MS = 35L * 86_400_000L
    const val DEFAULT_KEEP_SETS = 2

    /** A set = one full backup + the small backups after it (seq 0, 1, 2 ...). It can be restored only when no part is missing. */
    fun intact(set: List<Info>): Boolean {
        val s = set.sortedBy { it.seq }
        return s.isNotEmpty() && s[0].full && s[0].seq == 0 && s.indices.all { s[it].seq == it }
    }

    private fun sets(history: List<Info>): List<List<Info>> = history.groupBy { it.gen }.values.sortedByDescending { g -> g.maxOf { it.created } }

    /** The set a new backup would extend, or null when a new full backup has to start. */
    fun currentSet(history: List<Info>, now: Long, forceFull: Boolean = false): List<Info>? {
        if (forceFull) return null
        val newest = sets(history).firstOrNull() ?: return null
        if (!intact(newest)) return null
        if (newest.size > MAX_INCREMENTALS) return null
        val full = newest.first { it.full }
        if (now - full.created > FULL_EVERY_MS) return null
        return newest.sortedBy { it.seq }
    }

    class Next(val full: Boolean, val seq: Int, val base: String?, val gen: String?)

    fun next(history: List<Info>, now: Long, forceFull: Boolean = false): Next {
        val cur = currentSet(history, now, forceFull) ?: return Next(true, 0, null, null)
        val last = cur.last()
        return Next(false, last.seq + 1, last.id, last.gen)
    }

    /** The marks to continue from: later backups of the set override earlier ones. Empty when a full backup is due. */
    fun marks(set: List<BackupManifest>): Map<String, Mark> {
        val out = HashMap<String, Mark>()
        for (m in set.sortedBy { it.seq }) for (c in m.chats) { if (c.markKey.isNotEmpty()) out[c.id] = Mark(c.markKey, c.markTs) else out.remove(c.id) }
        return out
    }

    /** Media files already inside the set (so they are not stored twice). */
    fun mediaIn(set: List<BackupManifest>): Set<String> = set.flatMap { it.media.map { x -> x.id } }.toHashSet()

    /**
     * Retention: keep the newest [keepSets] sets whole, delete everything older.
     * The newest set is never touched, even if it is broken; an older set is never cut in the middle (a half set is useless), only removed whole.
     * Files that belong to no set at all cannot exist (every file has a gen), so nothing else is ever deleted.
     */
    fun toDelete(history: List<Info>, keepSets: Int): List<Info> {
        val all = sets(history)
        val keep = keepSets.coerceAtLeast(1)
        return all.drop(keep).flatten()
    }

    // ---------------------------------------------------------------------------------------- schedule
    /** When the next automatic backup should happen: the interval after the last success, or right away if there was none or it is overdue. null = schedule is off. */
    fun nextRun(freq: BackupFreq, lastSuccess: Long, now: Long): Long? =
        if (freq == BackupFreq.OFF) null else if (lastSuccess <= 0L) now else maxOf(now, lastSuccess + freq.ms)

    /** Delay to hand to the system scheduler. */
    fun initialDelayMs(freq: BackupFreq, lastSuccess: Long, now: Long): Long = (nextRun(freq, lastSuccess, now) ?: now) - now

    /** Shown as a warning when no automatic backup has worked for twice the chosen interval. */
    fun overdue(freq: BackupFreq, lastSuccess: Long, now: Long, scheduledSince: Long): Boolean =
        freq != BackupFreq.OFF && now - maxOf(lastSuccess, scheduledSince) > 2 * freq.ms

    // ---------------------------------------------------------------------------------------- resume
    class ChatDone(val part: ChatPart, val file: String)

    /**
     * The progress record of a backup that is being built. It is saved after every finished chat; after the app was killed the next run
     * reads it and carries on with the chats that are not done. It is thrown away when it is too old or was made for a different kind of backup.
     */
    class Journal(val runId: String, val started: Long, val full: Boolean, val gen: String, val seq: Int, val base: String?, val media: Boolean,
                  val mediaPlan: List<String>, val done: LinkedHashMap<String, ChatDone> = LinkedHashMap()) {
        fun toJson(): String = JSONObject().put("run", runId).put("started", started).put("full", full).put("gen", gen).put("seq", seq).put("base", base ?: JSONObject.NULL).put("media", media)
            .put("mediaPlan", JSONArray(mediaPlan))
            .put("done", JSONArray().also { a -> for (d in done.values) a.put(JSONObject().put("file", d.file).put("chat", JSONObject().put("id", d.part.id).put("kind", d.part.kind).put("entry", d.part.entry).put("count", d.part.count)
                .put("firstTs", d.part.firstTs).put("lastTs", d.part.lastTs).put("markKey", d.part.markKey).put("markTs", d.part.markTs))) }).toString()

        /** Resuming is allowed for the same kind of backup, started less than 24 hours ago. */
        fun usable(full: Boolean, gen: String?, seq: Int, base: String?, media: Boolean, now: Long): Boolean =
            now - started in 0..86_400_000L && this.full == full && this.seq == seq && this.base == base && this.media == media && (full || this.gen == gen)

        fun pending(chatIds: List<String>): List<String> = chatIds.filter { it !in done }

        companion object {
            fun parse(s: String): Journal? = try {
                val j = JSONObject(s)
                val d = j.getJSONArray("done"); val map = LinkedHashMap<String, ChatDone>()
                for (i in 0 until d.length()) { val o = d.getJSONObject(i); val c = o.getJSONObject("chat")
                    map[c.getString("id")] = ChatDone(ChatPart(c.getString("id"), c.optString("kind"), c.optString("entry"), c.getInt("count"), c.optLong("firstTs"), c.optLong("lastTs"), c.optString("markKey"), c.optLong("markTs")), o.getString("file")) }
                val mp = j.getJSONArray("mediaPlan")
                Journal(j.getString("run"), j.getLong("started"), j.getBoolean("full"), j.getString("gen"), j.getInt("seq"), if (j.isNull("base")) null else j.getString("base"), j.getBoolean("media"), (0 until mp.length()).map { mp.getString(it) }, map)
            } catch (e: Exception) { null }
        }
    }
}
