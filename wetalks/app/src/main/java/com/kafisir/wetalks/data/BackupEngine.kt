package com.kafisir.wetalks.data

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Where a backup gets its data from. The real one reads Firebase and the phone ([RepoBackupSource]); tests use a fake. */
interface BackupSource {
    /** The chats to back up (not locked chats, not channels). */
    suspend fun chats(): List<ChatRef>
    /** Decrypted messages with an id larger than [afterKey], oldest first, at most [limit]. Deleted and unreadable messages are included, flagged. */
    suspend fun page(ref: ChatRef, afterKey: String?, limit: Int): List<Msg>
    /** Small settings that belong in the backup (never the list of locked chats). */
    fun settings(): JSONObject
    /** Ids of media this phone holds as a plain file, newest first. */
    fun mediaCandidates(): List<String>
    fun mediaFile(id: String): File?
}

/** The backup files in a folder: `<id>.wtbk` plus `<id>.meta.json` (a copy of the manifest, so the list needs no decryption and no passphrase). */
object BackupFiles {
    class Local(val manifest: BackupManifest, val file: File, val encrypted: Boolean) {
        val size get() = file.length()
        val info get() = BackupPlan.Info(manifest.id, manifest.created, manifest.gen, manifest.seq, manifest.full, size, encrypted)
    }

    fun list(dir: File): List<Local> {
        val out = ArrayList<Local>()
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp") && System.currentTimeMillis() - f.lastModified() > 3_600_000L) f.delete()
            if (!f.name.endsWith(".meta.json")) return@forEach
            val data = File(dir, f.name.removeSuffix(".meta.json") + BackupFormat.EXT)
            if (!data.exists()) { f.delete(); return@forEach }
            try {
                val m = BackupManifest.parse(f.readText())
                val enc = data.inputStream().use { i -> val b = ByteArray(6); BackupFormat.readFully(i, b); b[5].toInt() and 1 == 1 }
                out.add(Local(m, data, enc))
            } catch (e: Exception) { /* unreadable sidecar: the file is simply not listed */ }
        }
        return out.sortedByDescending { it.manifest.created }
    }

    fun delete(l: Local) { l.file.delete(); File(l.file.parentFile, l.manifest.id + ".meta.json").delete() }
}

/**
 * Builds one backup file. Everything is checkpointed: the messages of each chat go to a part file in [work] and the journal is saved when a chat is
 * finished, so a backup interrupted by the system (or by the person) continues with the next chat instead of starting again. The final file is written to a
 * temporary name and renamed when complete: a half-written backup never looks like a backup.
 */
class BackupEngine(
    private val src: BackupSource, private val dir: File, private val work: File, private val account: String, private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis, private val freeBytes: () -> Long = { dir.usableSpace },
    private val pageSize: Int = 200,
) {
    class Options(val key: BackupKey?, val includeMedia: Boolean, val forceFull: Boolean, val keepSets: Int = BackupPlan.DEFAULT_KEEP_SETS, val mediaCapBytes: Long = 1L shl 30)
    class Progress(val stage: String, val chatsDone: Int, val chatsTotal: Int, val messages: Int, val bytes: Long)

    sealed class Outcome {
        class Done(val manifest: BackupManifest, val file: File, val messages: Int, val resumed: Boolean) : Outcome()
        /** Nothing has changed since the last backup, so no new file was made. */
        data object NothingNew : Outcome()
        data object Cancelled : Outcome()
        class Failed(val message: String, val diskFull: Boolean = false, val retry: Boolean = true) : Outcome()
    }

    private fun newId(): String {
        val f = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return f.format(Date(clock())) + "-" + BackupFormat.hex(BackupFormat.randomBytes(2))
    }

    private fun journalFile() = File(work, "journal.json")
    private fun saveJournal(j: BackupPlan.Journal) { val t = File(work, "journal.tmp"); t.writeText(j.toJson()); t.renameTo(journalFile()) }
    private fun partFile(chatId: String) = File(work, "c_" + BackupFormat.sha256Hex(chatId).take(24) + ".part")

    suspend fun run(o: Options, progress: (Progress) -> Unit, isCancelled: () -> Boolean): Outcome {
        try {
            dir.mkdirs(); work.mkdirs()
            val now = clock()
            val local = BackupFiles.list(dir)
            val next = BackupPlan.next(local.map { it.info }, now, o.forceFull)
            val set = if (next.full) emptyList() else local.filter { it.manifest.gen == next.gen }.map { it.manifest }
            val marks = if (next.full) emptyMap() else BackupPlan.marks(set)
            val haveMedia = if (next.full) emptySet() else BackupPlan.mediaIn(set)

            // resume an interrupted run of the same kind, or start a new one
            var journal = journalFile().takeIf { it.exists() }?.let { BackupPlan.Journal.parse(it.readText()) }
            var resumed = false
            if (journal != null && journal.usable(next.full, next.gen, next.seq, next.base, o.includeMedia, now)) resumed = true
            else {
                work.listFiles()?.forEach { it.deleteRecursively() }
                val id = newId()
                val plan = ArrayList<String>()
                if (o.includeMedia) {
                    var total = 0L
                    for (mid in src.mediaCandidates()) {
                        if (mid in haveMedia) continue
                        val f = src.mediaFile(mid) ?: continue
                        if (total + f.length() > o.mediaCapBytes) continue
                        total += f.length(); plan.add(mid)
                    }
                }
                journal = BackupPlan.Journal(id, now, next.full, next.gen ?: id, next.seq, next.base, o.includeMedia, plan)
                saveJournal(journal)
            }
            val j = journal!!

            // 1. messages, chat by chat
            val chats = src.chats().sortedBy { it.id }
            val todo = j.pending(chats.map { it.id })
            progress(Progress("Reading chats", chats.size - todo.size, chats.size, j.done.values.sumOf { it.part.count }, 0))
            for (ref in chats) {
                if (ref.id in j.done) continue
                if (isCancelled()) return Outcome.Cancelled
                val part = partFile(ref.id); part.delete()
                val tracker = BackupPlan.MarkTracker(marks[ref.id], j.started)
                var count = 0; var first = 0L; var last = 0L; var after: String? = marks[ref.id]?.key
                part.bufferedWriter().use { w ->
                    while (true) {
                        val page = src.page(ref, after, pageSize)
                        for (m in page) {
                            if (!BackupPlan.isNew(m.id, marks[ref.id])) continue
                            tracker.add(m.id, m.ts, readable = m.deleted || !m.unreadable)
                            if (!BackupMsgCodec.keep(m)) continue
                            w.write(BackupMsgCodec.encode(m)); w.newLine(); count++
                            if (first == 0L || m.ts in 1 until first) first = m.ts
                            if (m.ts > last) last = m.ts
                        }
                        if (page.size < pageSize) break
                        after = page.last().id
                        if (isCancelled()) return Outcome.Cancelled
                    }
                }
                val mk = tracker.mark
                if (count == 0) part.delete()
                j.done[ref.id] = BackupPlan.ChatDone(ChatPart(ref.id, ref.kind.name.lowercase(), if (count > 0) BackupFormat.chatEntry(ref.id) else "", count, first, last, mk?.key ?: "", mk?.ts ?: 0L), part.name)
                saveJournal(j)
                progress(Progress("Reading chats", j.done.size.coerceAtMost(chats.size), chats.size, j.done.values.sumOf { it.part.count }, 0))
            }
            val liveIds = chats.map { it.id }.toHashSet()
            val parts = j.done.values.filter { it.part.id in liveIds }
            val total = parts.sumOf { it.part.count }
            val mediaFiles = j.mediaPlan.mapNotNull { id -> src.mediaFile(id)?.let { id to it } }
            if (!next.full && total == 0 && mediaFiles.isEmpty()) { work.deleteRecursively(); return Outcome.NothingNew }

            // 2. the file
            val need = parts.sumOf { it.file.let { n -> File(work, n).length() } } + mediaFiles.sumOf { it.second.length() } + (16L shl 20)
            if (freeBytes() < need) return Outcome.Failed("Not enough free space on this phone for the backup. Free some space and try again.", diskFull = true)
            val tmp = File(dir, j.runId + BackupFormat.EXT + ".tmp")
            try {
                val written = ArrayList<MediaPart>()
                val fos = FileOutputStream(tmp)
                val w = BackupWriter(fos, o.key)
                var done = 0; val steps = parts.count { it.part.count > 0 } + mediaFiles.size
                fun tick(stage: String) { progress(Progress(stage, done, steps.coerceAtLeast(1), total, tmp.length())) }
                for (p in parts) {
                    if (p.part.count == 0) continue
                    if (isCancelled()) { w.abort(); tmp.delete(); return Outcome.Cancelled }
                    File(work, p.file).inputStream().use { w.addStream(p.part.entry, it, compress = true) }
                    done++; tick("Saving messages")
                }
                for ((id, f) in mediaFiles) {
                    if (isCancelled()) { w.abort(); tmp.delete(); return Outcome.Cancelled }
                    try { val sz = f.inputStream().use { w.addStream(BackupFormat.mediaEntry(id), it, compress = false) }; written.add(MediaPart(id, BackupFormat.mediaEntry(id), sz)) } catch (e: java.io.FileNotFoundException) { /* removed from the cache meanwhile */ }
                    done++; tick("Saving photos and voice notes")
                }
                w.addBytes("settings.json", src.settings().toString().toByteArray(Charsets.UTF_8))
                val manifest = w.finish { entries -> manifestOf(j, next, now, parts, written, entries) }
                val final = File(dir, manifest.id + BackupFormat.EXT)
                if (!tmp.renameTo(final)) throw IOException("could not finish the backup file")
                val side = File(dir, manifest.id + ".meta.json.tmp"); side.writeText(manifest.toJson().toString())
                if (!side.renameTo(File(dir, manifest.id + ".meta.json"))) throw IOException("could not finish the backup list")
                work.deleteRecursively()
                // retention: whole old sets only
                val after = BackupFiles.list(dir)
                BackupPlan.toDelete(after.map { it.info }, o.keepSets).forEach { d -> after.firstOrNull { it.manifest.id == d.id }?.let { BackupFiles.delete(it) } }
                return Outcome.Done(manifest, final, total, resumed)
            } catch (e: IOException) {
                tmp.delete()
                return failed(e)
            } catch (e: kotlinx.coroutines.CancellationException) {
                tmp.delete(); throw e
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: IOException) { return failed(e) }
        catch (e: Exception) { return Outcome.Failed((e.message ?: e.javaClass.simpleName).take(160)) }
    }

    private fun failed(e: IOException): Outcome.Failed {
        val m = (e.message ?: "").lowercase()
        return if ("enospc" in m || "no space" in m || "disk full" in m) Outcome.Failed("Not enough free space on this phone for the backup. Free some space and try again.", diskFull = true)
        else Outcome.Failed("Writing the backup failed: " + (e.message ?: e.javaClass.simpleName).take(120))
    }

    private fun manifestOf(j: BackupPlan.Journal, next: BackupPlan.Next, now: Long, parts: List<BackupPlan.ChatDone>, media: List<MediaPart>, entries: List<EntryInfo>) =
        BackupManifest(j.runId, now, appVersion, account, j.gen, next.seq, next.base, next.full, j.media, parts.map { it.part }, media, entries)
}
