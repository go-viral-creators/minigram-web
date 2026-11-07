package com.kafisir.wetalks.data

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The restored archive: this person's own copy of old messages, kept in app-private storage (`filesDir/archive`, separate from the cache, so
 * "Clear cache" never touches it). Nothing in it is ever written to the shared database: other people's messages are not re-created on the
 * server. The chat screen shows the live messages and, where the server no longer has a message, the archived copy ([union]).
 *
 * Layout: `chats/<sha256(chatId)>.jsonl` (one message per line, newest wins nothing: the first copy of an id is kept), `media/<sha256(mediaId)>`.
 */
class ArchiveStore(val root: File) {
    private val chatsDir get() = File(root, "chats").apply { mkdirs() }
    private val mediaDir get() = File(root, "media").apply { mkdirs() }
    private fun chatFile(chatId: String) = File(chatsDir, BackupFormat.chatEntry(chatId).removePrefix("chats/"))
    private val lock = Any()
    @Volatile var version = 0L; private set            // bumps on every change, so screens know when to read again

    fun hasChat(chatId: String) = chatFile(chatId).exists()

    /** Adds the lines whose message id is not in the archive yet. Returns how many were added. Rewrites the chat file safely (temp file, then rename). */
    fun merge(chatId: String, lines: Sequence<String>): Int = synchronized(lock) {
        val f = chatFile(chatId)
        val have = LinkedHashMap<String, String>()
        if (f.exists()) f.forEachLine { l -> BackupMsgCodec.idOf(l)?.let { have[it] = l } }
        var added = 0
        for (l in lines) { val id = BackupMsgCodec.idOf(l) ?: continue; if (id !in have) { have[id] = l; added++ } }
        if (added == 0) return 0
        val tmp = File(f.parentFile, f.name + ".tmp")
        try {
            tmp.bufferedWriter().use { w -> for (l in have.values.sortedWith(compareBy({ ts(it) }, { BackupMsgCodec.idOf(it) ?: "" }))) { w.write(l); w.newLine() } }
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IOException("could not save the archive") }
        } catch (e: Exception) { tmp.delete(); throw e }
        version++
        added
    }

    private fun ts(line: String): Long = try { JSONObject(line).optLong("ts") } catch (e: Exception) { 0L }

    /** The newest [limit] archived messages of a chat, oldest first. */
    fun messages(chatId: String, limit: Int = 4000): List<Msg> {
        val f = chatFile(chatId); if (!f.exists()) return emptyList()
        val out = ArrayList<Msg>()
        synchronized(lock) { f.forEachLine { l -> BackupMsgCodec.decode(l)?.let { out.add(it) } } }
        return if (out.size > limit) out.subList(out.size - limit, out.size).toList() else out
    }

    fun putMedia(mediaId: String, from: File): Boolean = synchronized(lock) {
        val to = File(mediaDir, BackupFormat.mediaEntry(mediaId).removePrefix("media/"))
        if (to.exists()) { from.delete(); return false }
        if (!from.renameTo(to)) { from.copyTo(to, overwrite = true); from.delete() }
        version++; true
    }
    fun mediaFile(mediaId: String): File? = File(mediaDir, BackupFormat.mediaEntry(mediaId).removePrefix("media/")).takeIf { it.exists() }

    class Stats(val chats: Int, val bytes: Long)
    fun stats(): Stats {
        val c = chatsDir.listFiles { x -> x.name.endsWith(".jsonl") }?.size ?: 0
        return Stats(c, root.walkTopDown().filter { it.isFile }.sumOf { it.length() })
    }

    fun clear() { synchronized(lock) { root.deleteRecursively(); version++ } }

    fun stagingDir(): File = File(root, ".staging").also { it.deleteRecursively(); it.mkdirs() }
    fun dropStaging() { File(root, ".staging").deleteRecursively() }

    companion object {
        @Volatile private var inst: ArchiveStore? = null
        /** The archive of this app (needs [Repo.init] to have run). */
        fun get(): ArchiveStore = inst ?: synchronized(this) { inst ?: ArchiveStore(File(Repo.app.filesDir, "archive")).also { inst = it } }

        /**
         * Live messages and archived messages of one chat together. A live message always wins (so edits and reactions are current); an archived
         * message is added only when the server has no message with that id. A live line without sender and time is only a leftover (for example a reaction
         * on a message the server deleted), it does not hide the archived copy. When the archive has nothing, the live list is returned unchanged.
         */
        fun union(live: List<Msg>, archive: List<Msg>): List<Msg> {
            if (archive.isEmpty()) return live
            val have = HashSet<String>(live.size * 2)
            for (m in live) if (m.from.isNotEmpty() || m.deleted) have.add(m.id)
            val extra = archive.filter { it.id !in have }
            if (extra.isEmpty()) return live
            val extraIds = extra.map { it.id }.toHashSet()
            val liveReal = live.filter { l -> l.from.isNotEmpty() || l.deleted || l.id !in extraIds }
            return (liveReal + extra).sortedWith(compareBy<Msg>({ if (it.ts == 0L) Long.MAX_VALUE else it.ts }, { it.id }))
        }
    }
}

/** What a restore did. */
class RestoreResult(val messagesAdded: Int, val chats: Int, val media: Int, val settings: JSONObject?, val manifest: BackupManifest)

/**
 * Restore of one backup file into the [ArchiveStore]. The file is read once: chats and media go to a staging folder while their hashes are checked;
 * only when the manifest has been read and EVERY hash matches, the staging folder is merged into the archive. A damaged, cut-off, wrong-passphrase or
 * wrong-account file therefore changes nothing. Restoring the same file (or the several files of one set, in any order) twice adds nothing twice.
 */
class BackupRestore(private val store: ArchiveStore, private val account: String) {
    /** [keyFor] returns the key for an encrypted file, null when the passphrase is still needed. [check] is called often and may throw to cancel. */
    fun restore(inp: InputStream, keyFor: (BackupFormat.Header) -> BackupKey?, check: () -> Unit = {}, onProgress: (chatsDone: Int) -> Unit = {}): RestoreResult {
        val stage = store.stagingDir()
        try {
            var settings: JSONObject? = null
            val staged = HashSet<String>()
            val m = BackupReader.read(inp, keyFor) { name, s ->
                check()
                when {
                    name == "settings.json" -> settings = try { JSONObject(String(s.readBytes(), Charsets.UTF_8)) } catch (e: Exception) { null }
                    else -> {
                        val f = File(stage, name.replace('/', '_'))
                        f.outputStream().buffered().use { o -> val b = ByteArray(32 * 1024); while (true) { val r = s.read(b); if (r < 0) break; o.write(b, 0, r); check() } }
                        staged.add(name)
                    }
                }
            }
            if (m.account != account) throw BackupException(BackupException.Kind.WRONG_ACCOUNT, "This backup belongs to a different WeTalks account, so it cannot be restored here.")
            var added = 0; var chats = 0; var done = 0
            for (c in m.chats) {
                check()
                if (c.entry.isEmpty() || c.entry !in staged) { done++; continue }
                val f = File(stage, c.entry.replace('/', '_'))
                val n = f.useLines { store.merge(c.id, it.toList().asSequence()) }
                if (n > 0) chats++
                added += n; done++; onProgress(done); f.delete()
            }
            var media = 0
            for (x in m.media) {
                check()
                if (x.entry !in staged) continue
                if (store.putMedia(x.id, File(stage, x.entry.replace('/', '_')))) media++
            }
            return RestoreResult(added, chats, media, settings, m)
        } finally { store.dropStaging() }
    }
}
