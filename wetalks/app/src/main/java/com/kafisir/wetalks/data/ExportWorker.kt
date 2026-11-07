package com.kafisir.wetalks.data

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.database.DataSnapshot
import com.kafisir.wetalks.MainActivity
import com.kafisir.wetalks.R
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** What the person asked for. Travels to the background worker as WorkManager input data. */
class ExportRequest(
    val cid: String, val kind: Kind, val peer: String?, val title: String,
    val range: ChatExport.Range, val media: Boolean, val locked: Boolean,
    val estBytes: Long = 0, val estMessages: Long = 0,
) {
    val ref get() = ChatRef(cid, kind, peer)
    fun toData(): Data = workDataOf(
        "cid" to cid, "kind" to kind.ordinal, "peer" to (peer ?: ""), "title" to title, "from" to (range.from ?: -1L), "to" to (range.to ?: -1L),
        "media" to media, "locked" to locked, "estBytes" to estBytes, "estMsgs" to estMessages)
    companion object {
        fun from(d: Data) = ExportRequest(
            d.getString("cid").orEmpty(), Kind.values().getOrElse(d.getInt("kind", 0)) { Kind.DM }, d.getString("peer")?.ifEmpty { null }, d.getString("title").orEmpty(),
            ChatExport.Range(d.getLong("from", -1L).takeIf { it >= 0 }, d.getLong("to", -1L).takeIf { it >= 0 }), d.getBoolean("media", false), d.getBoolean("locked", false),
            d.getLong("estBytes", 0L), d.getLong("estMsgs", 0L))
    }
}

/** Reads one chat page by page from the database (never the whole chat at once). */
object ExportSource {
    private const val PAGE = 150

    class Scan(val messages: Long, val mediaItems: Long, val mediaBytes: Long)

    private fun rawOf(c: DataSnapshot): Repo.Raw {
        fun s(k: String) = c.child(k).getValue(String::class.java) ?: ""
        fun l(k: String) = c.child(k).getValue(Long::class.java) ?: 0L
        return Repo.Raw(c.key!!, s("from"), l("ts"), s("type"), s("body"), l("kv").toInt(), s("rid").ifEmpty { null },
            c.child("deleted").getValue(Boolean::class.java) ?: false, c.child("edited").getValue(Boolean::class.java) ?: false,
            c.child("react").children.associate { it.key!! to (it.getValue(String::class.java) ?: "") }, s("fwd").ifEmpty { null })
    }

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var wait = 1000L
        repeat(3) {
            try { return block() } catch (e: CancellationException) { throw e } catch (e: Exception) { delay(wait); wait *= 2 }
        }
        return block()
    }

    /** The id of the newest message right now: the export stops there, so messages that arrive while it runs are not half-included. */
    suspend fun newestKey(ref: ChatRef): String? = retrying {
        Repo.db.getReference("messages/${ref.id}").orderByKey().limitToLast(1).get().await().children.firstOrNull()?.key
    }

    /** Calls [each] with every page in order. [each] gets the raw messages and the optional `exp` (disappearing) time of each. */
    suspend fun pages(ref: ChatRef, range: ChatExport.Range, endKey: String?, each: suspend (List<Repo.Raw>, Map<String, Long>) -> Unit) {
        var last: String? = null
        val startKey = ChatExport.startKeyFor(range)
        while (true) {
            coroutineContext.ensureActive()
            val snap = retrying {
                var q = Repo.db.getReference("messages/${ref.id}").orderByKey()
                q = if (last != null) q.startAfter(last) else if (startKey != null) q.startAt(startKey) else q
                q.limitToFirst(PAGE).get().await()
            }
            val kids = snap.children.toList()
            if (kids.isEmpty()) return
            last = kids.last().key
            val inScope = kids.filter { endKey == null || it.key!! <= endKey }
            val exp = HashMap<String, Long>()
            for (k in inScope) k.child("exp").getValue(Long::class.java)?.let { exp[k.key!!] = it }
            withContext(Dispatchers.IO) { each(inScope.map { rawOf(it) }, exp) }
            if (kids.size < PAGE || inScope.size < kids.size) return
        }
    }

    /** True when this message belongs in the export: not deleted, not hidden on this phone, not from a blocked person, in range, not gone (disappearing). */
    fun wanted(r: Repo.Raw, exp: Map<String, Long>, range: ChatExport.Range, cut: Long, blocked: Set<String>, now: Long): Boolean {
        if (r.deleted || Repo.isHidden(r.id) || r.from in blocked) return false
        if (r.ts != 0L && r.ts <= cut) return false
        if (!range.contains(r.ts)) return false
        val e = exp[r.id]; if (e != null && e in 1..now) return false
        return true
    }

    /** Counts what an export would contain, for "about 120 MB of media". Decrypts only the messages that carry media. */
    suspend fun scan(ref: ChatRef, range: ChatExport.Range, onProgress: (Long) -> Unit): Scan {
        val now = System.currentTimeMillis(); val cut = Repo.cutoff(ref.id); val blocked = Repo.blockedUsers.value
        val end = newestKey(ref)
        var n = 0L; var items = 0L; var bytes = 0L
        pages(ref, range, end) { raws, exp ->
            val ok = raws.filter { wanted(it, exp, range, cut, blocked, now) }
            n += ok.size
            val withMedia = ok.filter { it.type in ChatExport.MEDIA_TYPES || it.type == "gif" }
            if (withMedia.isNotEmpty()) for (m in Repo.decryptAll(ref, withMedia)) {
                if (!m.mediaId.isNullOrEmpty() && !m.unreadable) { items++; bytes += ChatExport.estimateBytes(m.type, m.size, m.w, m.h, m.dur) }
            }
            onProgress(n)
        }
        return Scan(n, items, bytes)
    }

    suspend fun noExport(cid: String): Boolean = try { Repo.db.getReference("chat_settings/$cid/noExport").get().await().getValue(Boolean::class.java) == true } catch (e: Exception) { false }

    /** The reason this person may not export this chat, or null. Read fresh from the server (the screen may be out of date). */
    suspend fun blockedReason(ref: ChatRef): String? {
        if (ref.kind == Kind.DM) return null
        val owner = try {
            Repo.db.getReference((if (ref.kind == Kind.GROUP) "groups/" else "channels/") + ref.id + "/owner").get().await().getValue(String::class.java)
        } catch (e: Exception) { null }
        return ChatExport.blockedReason(ref.kind, noExport(ref.id), owner != null && owner == Repo.uid)
    }

    /** Times at which messages were pinned (pins/<cid>/<mid> holds that time). */
    suspend fun pinTimes(ref: ChatRef): List<Long> = try {
        Repo.db.getReference("pins/${ref.id}").get().await().children.mapNotNull { it.getValue(Long::class.java) }.sorted()
    } catch (e: Exception) { emptyList() }
}

/** Folder, notifications and the share sheet for exports. */
object ExportFiles {
    const val NOTIF_PROGRESS = 7300; const val NOTIF_DONE = 7400
    fun dir(c: Context, cid: String) = File(File(c.cacheDir, "exports"), Integer.toHexString(cid.hashCode()))
    fun notifId(base: Int, cid: String) = base + (cid.hashCode() and 0x3FF)
    fun uniqueName(cid: String) = "export_$cid"

    /** Old exports (and half-written ones) are removed so they never fill the phone. */
    fun sweep(c: Context, keep: File? = null) {
        val root = File(c.cacheDir, "exports")
        val cut = System.currentTimeMillis() - 2L * 86_400_000L
        root.listFiles()?.forEach { d -> if (d != keep && d.lastModified() < cut) d.deleteRecursively() }
    }
    fun free(c: Context): Long = try { StatFs(c.cacheDir.path).availableBytes } catch (e: Exception) { Long.MAX_VALUE }

    fun shareIntent(c: Context, file: File, title: String?, zip: Boolean): Intent {
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType(if (zip) "application/zip" else "text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, title ?: "WeTalks chat export")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, "Share chat export").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun openChat(c: Context, r: ExportRequest, code: Int): PendingIntent = PendingIntent.getActivity(c, code,
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("cid", r.cid).putExtra("kind", r.kind.ordinal).putExtra("peer", r.peer ?: ""), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun shown(r: ExportRequest) = if (r.locked) "Chat export" else r.title.ifEmpty { "Chat export" }

    fun progress(c: Context, r: ExportRequest, cancel: PendingIntent, messages: Long, files: Int): Notification {
        val total = r.estMessages
        val b = NotificationCompat.Builder(c, NotifChannels.BACKGROUND).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Exporting " + (if (r.locked) "a chat" else shown(r)))
            .setContentText("%,d messages".format(messages) + (if (files > 0) " · %,d files".format(files) else ""))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openChat(c, r, notifId(0, r.cid)))
            .addAction(0, "Cancel", cancel)
        if (total > 0) b.setProgress(100, (messages * 100 / total).toInt().coerceIn(0, 99), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    fun foreground(c: Context, r: ExportRequest, cancel: PendingIntent, messages: Long, files: Int): ForegroundInfo {
        val n = progress(c, r, cancel, messages, files); val id = notifId(NOTIF_PROGRESS, r.cid)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, n)
    }

    private fun post(c: Context, id: Int, n: Notification) {
        try { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n) } catch (_: Exception) {}
    }
    fun update(c: Context, r: ExportRequest, cancel: PendingIntent, messages: Long, files: Int) = post(c, notifId(NOTIF_PROGRESS, r.cid), progress(c, r, cancel, messages, files))

    /** The final notification. A locked chat gets no Share button and no name: it opens the chat, which asks for the lock first. */
    fun done(c: Context, r: ExportRequest, file: File, zip: Boolean, text: String) {
        val b = NotificationCompat.Builder(c, NotifChannels.BACKGROUND).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (r.locked) "Chat export is ready" else "Export ready: " + shown(r)).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setOnlyAlertOnce(true).setSilent(false)
            .setContentIntent(openChat(c, r, notifId(1000, r.cid)))
        if (!r.locked) {
            val share = PendingIntent.getActivity(c, notifId(2000, r.cid), shareIntent(c, file, r.title, zip), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(0, "Share", share)
        }
        post(c, notifId(NOTIF_DONE, r.cid), b.build())
    }

    fun failed(c: Context, r: ExportRequest, reason: String) {
        val n = NotificationCompat.Builder(c, NotifChannels.BACKGROUND).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Export stopped").setContentText(reason).setStyle(NotificationCompat.BigTextStyle().bigText(reason)).setAutoCancel(true)
            .setContentIntent(openChat(c, r, notifId(1000, r.cid))).build()
        post(c, notifId(NOTIF_DONE, r.cid), n)
    }
    fun dismissDone(c: Context, cid: String) { try { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifId(NOTIF_DONE, cid)) } catch (_: Exception) {} }
}

/** Starts, watches and cancels exports (what the screen uses). */
object ExportManager {
    fun start(c: Context, r: ExportRequest) {
        val req = OneTimeWorkRequestBuilder<ExportWorker>().setInputData(r.toData())
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).addTag("export").build()
        ExportFiles.dismissDone(c, r.cid)
        WorkManager.getInstance(c).enqueueUniqueWork(ExportFiles.uniqueName(r.cid), ExistingWorkPolicy.KEEP, req)
    }
    fun cancel(c: Context, cid: String) { WorkManager.getInstance(c).cancelUniqueWork(ExportFiles.uniqueName(cid)) }
    fun watch(c: Context, cid: String): Flow<List<WorkInfo>> = WorkManager.getInstance(c).getWorkInfosForUniqueWorkFlow(ExportFiles.uniqueName(cid))
}

class ExportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val req = ExportRequest.from(inputData)
    private val cancelIntent get() = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

    override suspend fun getForegroundInfo(): ForegroundInfo = ExportFiles.foreground(applicationContext, req, cancelIntent, 0, 0)

    private class Fail(msg: String) : Exception(msg)

    override suspend fun doWork(): Result {
        val c = applicationContext
        val dir = ExportFiles.dir(c, req.cid)
        var txtTmp: File? = null; var out: File? = null; var writer: ChatExport.Writer? = null
        try {
            if (req.cid.isEmpty() || Repo.uid.isEmpty()) throw Fail("Please sign in again.")
            try { setForeground(getForegroundInfo()) } catch (_: Exception) { /* Android may refuse a start from the background: the work still runs, only without the foreground notification */ }
            ExportSource.blockedReason(req.ref)?.let { throw Fail(it) }
            ExportFiles.sweep(c)
            dir.deleteRecursively(); dir.mkdirs()

            val needed = ChatExport.neededBytes(req.estBytes, req.estMessages, req.media)
            val free = ExportFiles.free(c)
            if (!ChatExport.enoughSpace(free, needed)) throw Fail("Not enough free space. The export needs " + ChatExport.sizeLabel(needed).removePrefix("about ") + ", this phone has " + ChatExport.exactSize(free) + " free.")

            val me = Repo.uid
            val fmt = ChatExport.Fmt(Locale.getDefault(), TimeZone.getDefault())
            val now = System.currentTimeMillis()
            val zip = req.media
            val finalName = ChatExport.exportFileName(if (req.locked) "chat" else req.title, now, zip, fmt, group = req.kind != Kind.DM || req.locked)
            out = File(dir, "$finalName.part")
            txtTmp = if (zip) File(dir, "chat.tmp") else out
            val ref = req.ref
            val names = HashMap<String, String>()
            suspend fun nameOf(uid: String): String = names.getOrPut(uid) {
                try { Repo.userOnce(uid)?.name?.takeIf { it.isNotBlank() } } catch (_: Exception) { null } ?: if (req.kind == Kind.CHANNEL && req.title.isNotEmpty() && !req.locked) req.title else "Unknown"
            }
            val source = ChatExport.MediaSource { m ->
                val (msg, kv) = @Suppress("UNCHECKED_CAST") (m.tag as Pair<Msg, Int>)
                when (m.type) {
                    "video", "audio", "file" -> {
                        val existed = FileKit.cachedFile(c, m.mediaId, msg.name).let { it.exists() && it.length() > 0 }
                        val f = Repo.fetchFile(msg) {}
                        val head = ByteArray(64).let { b -> FileInputStream(f).use { i -> val n = i.read(b); if (n <= 0) ByteArray(0) else b.copyOf(n) } }
                        ChatExport.Blob(f.length(), head, { FileInputStream(f) }, { if (!existed) f.delete() })
                    }
                    else -> {
                        val bytes = Repo.loadMedia(ref, m.mediaId, kv) ?: throw IllegalStateException("could not be downloaded or opened (it may have been deleted)")
                        ChatExport.Blob(bytes.size.toLong(), bytes.copyOf(minOf(64, bytes.size)), { ByteArrayInputStream(bytes) })
                    }
                }
            }
            val w = ChatExport.Writer(txtTmp, if (zip) out else null, fmt, if (zip) source else null)
            writer = w

            val pins = ExportSource.pinTimes(ref).filter { req.range.contains(it) }.toMutableList()
            val recent = object : LinkedHashMap<String, Pair<String, String>>(256, .75f, false) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<String, String>>?) = size > 3000 }
            var lookups = 0
            val cut = Repo.cutoff(req.cid); val blocked = Repo.blockedUsers.value
            val end = ExportSource.newestKey(ref)
            var count = 0L; var files = 0; var lastUi = 0L; var checked = 0

            ExportSource.pages(ref, req.range, end) { raws, exp ->
                val ok = raws.filter { ExportSource.wanted(it, exp, req.range, cut, blocked, now) }
                val msgs = Repo.decryptAll(ref, ok)
                for ((i, m) in msgs.withIndex()) {
                    coroutineContext.ensureActive()
                    val r = ok[i]
                    while (pins.isNotEmpty() && pins.first() <= m.ts) w.system(pins.removeAt(0), ChatExport.pinNotice())
                    var replyName: String? = null; var replyText: String? = null
                    if (m.rid != null) {
                        val hit = recent[m.rid]
                        if (hit != null) { replyName = hit.first; replyText = hit.second }
                        else if (lookups < 200) {
                            lookups++
                            val o = try { Repo.messageOnce(ref, m.rid) } catch (_: Exception) { null }
                            if (o != null && !o.deleted) { replyName = nameOf(o.from); replyText = ChatExport.previewOf(o.type, o.text) }
                        }
                        if (replyName == null) replyName = ""
                    }
                    val who = nameOf(m.from)
                    val item = ChatExport.Item(
                        m.id, m.ts, if (m.from == me && who == "Unknown") "You" else who, m.type, m.text, m.unreadable, m.edited,
                        fwdName = m.fwd?.let { nameOf(it).let { n -> if (n == "Unknown") "" else n } },
                        replyName = replyName?.takeIf { it.isNotEmpty() } ?: if (m.rid != null) "an earlier message" else null, replyText = replyText,
                        mime = m.mime, fileName = m.name, dur = m.dur, w = m.w, h = m.h, size = m.size,
                        callVideo = m.w == 1, callState = m.mime, callMine = m.from == me,
                        mediaId = m.mediaId.orEmpty(), tag = Pair(m, r.kv))
                    w.add(item)
                    recent[m.id] = Pair(item.name, ChatExport.previewOf(m.type, m.text))
                    count++
                    if (item.hasMedia && zip) {
                        files++
                        if (++checked % 20 == 0 && ExportFiles.free(c) < ChatExport.SPACE_MARGIN) throw Fail("The phone ran out of free space, so the export was stopped.")
                    }
                }
                val t = System.currentTimeMillis()
                if (t - lastUi > 1000) {
                    lastUi = t
                    setProgress(workDataOf("msgs" to count, "files" to files))
                    ExportFiles.update(c, req, cancelIntent, count, files)
                }
            }
            while (pins.isNotEmpty()) w.system(pins.removeAt(0), ChatExport.pinNotice())
            val sum = w.finish(); writer = null
            val result = File(dir, finalName)
            if (!out.renameTo(result)) throw Fail("The export file could not be saved.")
            if (zip) txtTmp.delete()
            out = null

            val parts = ArrayList<String>()
            parts += "%,d messages".format(sum.messages)
            if (zip) parts += "%,d media files (%s)".format(sum.mediaFiles, ChatExport.exactSize(sum.mediaBytes))
            if (sum.skipped.isNotEmpty()) parts += "%,d media files could not be added (listed in %s)".format(sum.skipped.size, ChatExport.NOTES_NAME)
            if (sum.unreadable > 0) parts += "%,d messages could not be decrypted".format(sum.unreadable)
            if (sum.zipFull) parts += "the zip reached its size limit"
            val text = parts.joinToString(" · ") + ". The file is not encrypted."
            ExportFiles.done(c, req, result, zip, text)
            return Result.success(workDataOf("path" to result.absolutePath, "zip" to zip, "msgs" to sum.messages.toLong(), "files" to sum.mediaFiles, "skipped" to sum.skipped.size,
                "unreadable" to sum.unreadable, "bytes" to result.length(), "text" to text, "end" to now))
        } catch (e: CancellationException) {
            writer?.abort(); dir.deleteRecursively()
            throw e
        } catch (e: Throwable) {
            writer?.abort(); dir.deleteRecursively()
            val reason = when {
                e is Fail -> e.message ?: "The export failed."
                ChatExport.noSpace(e) -> "The phone ran out of free space, so the export was stopped."
                else -> Repo.explain(e, "Chat export")
            }
            ExportFiles.failed(c, req, reason)
            return Result.failure(workDataOf("error" to reason))
        }
    }
}
