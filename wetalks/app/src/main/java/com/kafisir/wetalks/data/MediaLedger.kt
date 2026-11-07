package com.kafisir.wetalks.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.concurrent.Executors

/** Plain helpers for the storage manager (no Android classes, unit tested). */
object MediaKinds {
    const val PHOTO = "photo"; const val GIF = "gif"; const val VOICE = "voice"; const val AUDIO = "audio"; const val VIDEO = "video"; const val DOC = "doc"; const val OTHER = "other"
    /** The order types are shown in. */
    val ALL = listOf(PHOTO, GIF, VIDEO, VOICE, AUDIO, DOC, OTHER)
    fun label(t: String) = when (t) { PHOTO -> "Photos"; GIF -> "GIFs"; VIDEO -> "Videos"; VOICE -> "Voice messages"; AUDIO -> "Audio"; DOC -> "Documents"; else -> "Other" }

    /** What a cached photo / voice / GIF file is, from its first bytes (the cache keeps no extension). */
    fun sniff(h: ByteArray, n: Int = h.size): String {
        fun at(off: Int, s: String) = n >= off + s.length && s.indices.all { h[off + it] == s[it].code.toByte() }
        fun b(i: Int) = if (i < n) h[i].toInt() and 255 else -1
        return when {
            b(0) == 0xFF && b(1) == 0xD8 -> PHOTO
            b(0) == 0x89 && at(1, "PNG") -> PHOTO
            at(0, "GIF8") -> GIF
            at(0, "RIFF") && at(8, "WEBP") -> PHOTO
            at(4, "ftyp") && at(8, "heic") || at(4, "ftyp") && at(8, "mif1") -> PHOTO
            at(4, "ftyp") || at(0, "OggS") || at(0, "ID3") || at(0, "#!AMR") || (b(0) == 0xFF && (b(1) and 0xE0) == 0xE0) -> VOICE
            else -> OTHER
        }
    }

    /** A cached video / audio / document file, from its extension. */
    fun ofExtension(ext: String): String = when (FileKit.kindOf(ext.lowercase())) { "video" -> VIDEO; "audio" -> AUDIO; "file" -> DOC; else -> OTHER }

    fun ofMessageType(t: String): String? = when (t) { "image" -> PHOTO; "gif" -> GIF; "voice" -> VOICE; "video" -> VIDEO; "audio" -> AUDIO; "file" -> DOC; else -> null }

    fun bytes(n: Long): String = when {
        n >= 1L shl 30 -> String.format("%.1f GB", n / 1073741824.0)
        n >= 1L shl 20 -> String.format("%.1f MB", n / 1048576.0)
        n >= 1024 -> (n / 1024).toString() + " KB"
        else -> "$n B"
    }

    /** Bar segments: each part's share of the total (0..1), zero-size parts left out. Shares add up to 1 (or the list is empty). */
    fun shares(parts: List<Pair<String, Long>>): List<Pair<String, Float>> {
        val total = parts.sumOf { it.second.coerceAtLeast(0) }
        if (total <= 0) return emptyList()
        return parts.filter { it.second > 0 }.map { it.first to it.second.toFloat() / total }
    }

    /** "Free up about X": how many of the biggest items must go to reach [target] bytes. */
    fun countToFree(sizesBiggestFirst: List<Long>, target: Long): Int { var s = 0L; for ((i, v) in sizesBiggestFirst.withIndex()) { s += v; if (s >= target) return i + 1 }; return sizesBiggestFirst.size }
}

/**
 * A small SQLite list of every media file kept on this phone: which chat and message it belongs to, what type it is, how big,
 * and when it was last opened. It sits NEXT TO the cache folders (never inside them), so clearing the cache never loses it, and the
 * cache folders themselves are untouched. [reconcile] re-reads the folders, so files cached before this list existed are picked up too.
 * Everything here is blocking: call it from Dispatchers.IO (the small write-only calls queue themselves).
 */
object MediaLedger {
    class Entry(val mediaId: String, val chatId: String, val type: String, val bytes: Long, val opened: Long)
    class ChatUse(val chatId: String, val bytes: Long, val files: Int)
    class TypeUse(val type: String, val bytes: Long, val files: Int)

    private lateinit var app: Context
    private var helper: Helper? = null
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "media-ledger").apply { isDaemon = true } }
    private val noted = HashSet<String>()

    fun init(c: Context) { app = c.applicationContext }
    private fun ready() = ::app.isInitialized

    private class Helper(c: Context) : SQLiteOpenHelper(c, "media_ledger.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE media(path TEXT PRIMARY KEY, media_id TEXT NOT NULL, chat_id TEXT NOT NULL DEFAULT '', msg_id TEXT NOT NULL DEFAULT '', type TEXT NOT NULL, size INTEGER NOT NULL, created INTEGER NOT NULL, opened INTEGER NOT NULL, seen INTEGER NOT NULL DEFAULT 1)")
            db.execSQL("CREATE INDEX media_by_id ON media(media_id)")
            db.execSQL("CREATE INDEX media_by_chat ON media(chat_id, type)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }
    private fun db(): SQLiteDatabase = synchronized(lock) { (helper ?: Helper(app).also { helper = it }).writableDatabase }

    // ---- where the cache keeps things (the same places DataPrefs and FileKit already use)
    private fun mediaDir() = File(app.cacheDir, "media")
    private fun filesDir() = FileKit.cacheDir(app)

    // ------------------------------------------------------------------------------------------ writing, called when media is cached or shown
    /** A photo / voice / GIF has just been stored (or was sent from this phone) for [chatId]. [head] = its first bytes, to tell what it is. */
    fun noteMedia(chatId: String, mediaId: String, head: ByteArray?, type: String? = null, msgId: String = "") {
        if (!ready()) return
        val t = type ?: head?.let { MediaKinds.sniff(it, minOf(it.size, 16)) }
        writer.execute { try { upsertPlain(mediaId, chatId, msgId, t) } catch (_: Exception) {} }
    }

    /** A chat is showing this media: remember which chat and message it belongs to. Cheap to call on every redraw. */
    fun seenMedia(chatId: String, msgId: String, mediaId: String?, msgType: String) {
        if (!ready() || mediaId.isNullOrEmpty()) return
        val key = "$mediaId|$chatId"
        synchronized(noted) { if (key in noted) return }
        val t = MediaKinds.ofMessageType(msgType)
        writer.execute { try { if (upsertPlain(mediaId, chatId, msgId, t)) synchronized(noted) { noted.add(key) } } catch (_: Exception) {} }
    }

    /** A video / audio / document file is on disk for [chatId] (just downloaded, opened, or sent). */
    fun noteFile(chatId: String, msgId: String, mediaId: String, name: String, msgType: String) {
        if (!ready()) return
        val key = "f$mediaId|$chatId"
        synchronized(noted) { if (key in noted) return }
        writer.execute { try {
            val f = FileKit.cachedFile(app, mediaId, name)
            if (f.exists()) synchronized(noted) { noted.add(key) }
            if (f.exists()) upsert(f, mediaId.takeLast(40), chatId, msgId, MediaKinds.ofMessageType(msgType) ?: MediaKinds.ofExtension(FileKit.extOf(name)))
        } catch (_: Exception) {} }
    }

    // ---- the size of an upload travels inside the (encrypted) message as "s", so receivers can show "2.4 MB" before downloading
    private val upSizes = object : LinkedHashMap<String, Long>(64, .75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Long>) = size > 200 }
    fun rememberUpload(mediaId: String, bytes: Long) { synchronized(upSizes) { upSizes[mediaId] = bytes } }
    /** Adds the upload size to a photo / voice / GIF payload; any other payload, or one that already has a size, is returned unchanged. */
    fun stampSize(type: String, payload: String): String {
        if (type != "image" && type != "voice" && type != "gif") return payload
        return try {
            val j = org.json.JSONObject(payload)
            val id = j.optString("m"); if (id.isEmpty() || j.has("s")) return payload
            val n = synchronized(upSizes) { upSizes[id] } ?: return payload
            j.put("s", n).toString()
        } catch (_: Exception) { payload }
    }

    /** Forgets the "already noted" memory after a clear, so the next chat visit writes the rows again. */
    private fun forgetNoted() { synchronized(noted) { noted.clear() } }

    private fun upsertPlain(mediaId: String, chatId: String, msgId: String, type: String?): Boolean {
        val id = mediaId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val p = File(mediaDir(), "p_$id"); val m = File(mediaDir(), "m_$id")
        val t = type ?: if (p.exists()) sniffFile(p) else null
        if (p.exists()) upsert(p, id, chatId, msgId, t ?: MediaKinds.OTHER)
        if (m.exists()) upsert(m, id, chatId, msgId, t ?: MediaKinds.OTHER)
        return p.exists() || m.exists()
    }

    private fun sniffFile(f: File): String = try { f.inputStream().use { i -> val b = ByteArray(16); val n = i.read(b); MediaKinds.sniff(b, maxOf(n, 0)) } } catch (_: Exception) { MediaKinds.OTHER }

    private fun upsert(f: File, mediaId: String, chatId: String, msgId: String, type: String) {
        val now = System.currentTimeMillis()
        val d = db()
        val cv = ContentValues().apply { put("size", f.length()); put("opened", now); put("type", type); put("seen", 1); if (chatId.isNotEmpty()) put("chat_id", chatId); if (msgId.isNotEmpty()) put("msg_id", msgId) }
        synchronized(lock) {
            if (d.update("media", cv, "path=?", arrayOf(f.absolutePath)) == 0) {
                cv.put("path", f.absolutePath); cv.put("media_id", mediaId); cv.put("created", now)
                d.insertWithOnConflict("media", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    // ------------------------------------------------------------------------------------------ catching up with what is really on disk
    /**
     * Walks the cache folders once (streaming, a few thousand rows per transaction), adds files the list does not know yet (chat "unknown"
     * until a chat shows them), refreshes sizes and last-opened times, and drops rows whose file is gone. Returns the number of files seen.
     */
    fun reconcile(): Int {
        if (!ready()) return 0
        val d = db(); var n = 0; var ok = false
        synchronized(lock) {
            d.execSQL("UPDATE media SET seen=0")
            val upd = d.compileStatement("UPDATE media SET size=?, opened=?, seen=1 WHERE path=?")
            val ins = d.compileStatement("INSERT OR REPLACE INTO media(path,media_id,chat_id,msg_id,type,size,created,opened,seen) VALUES(?,?,'','',?,?,?,?,1)")
            d.beginTransaction()
            try {
                fun visit(f: File, mediaId: String, type: () -> String) {
                    val len = f.length(); val mt = f.lastModified()
                    upd.clearBindings(); upd.bindLong(1, len); upd.bindLong(2, mt); upd.bindString(3, f.absolutePath)
                    if (upd.executeUpdateDelete() == 0) {
                        ins.clearBindings(); ins.bindString(1, f.absolutePath); ins.bindString(2, mediaId); ins.bindString(3, type()); ins.bindLong(4, len); ins.bindLong(5, mt); ins.bindLong(6, mt); ins.executeInsert()
                    }
                    if (++n % 2000 == 0) { d.setTransactionSuccessful(); d.endTransaction(); d.beginTransaction() }
                }
                // photos / voice / GIFs: p_<id> is the readable file, m_<id> the still-sealed server copy
                val md = mediaDir()
                if (md.isDirectory) java.nio.file.Files.newDirectoryStream(md.toPath()).use { s -> for (e in s) {
                    val f = e.toFile(); if (!f.isFile) continue
                    val name = f.name
                    when {
                        name.startsWith("p_tmp_") -> visit(f, name, { MediaKinds.OTHER })
                        name.startsWith("p_") -> visit(f, name.substring(2), { sniffFile(f) })
                        name.startsWith("m_") -> visit(f, name.substring(2), {
                            val sib = File(md, "p_" + name.substring(2))          // a sealed copy counts as whatever its opened sibling is
                            if (sib.exists()) sniffFile(sib) else MediaKinds.OTHER })
                        else -> visit(f, name, { MediaKinds.OTHER })
                    }
                } }
                // video / audio / document files: files/<id>/<name>
                val fd = filesDir()
                if (fd.isDirectory) java.nio.file.Files.newDirectoryStream(fd.toPath()).use { s -> for (e in s) {
                    val dir = e.toFile(); if (!dir.isDirectory) continue
                    dir.listFiles()?.forEach { f -> if (f.isFile) visit(f, dir.name, { MediaKinds.ofExtension(FileKit.extOf(f.name)) }) }
                } }
                d.setTransactionSuccessful(); ok = true
            } finally { d.endTransaction() }
            upd.close(); ins.close()
            if (ok) d.execSQL("DELETE FROM media WHERE seen=0")
        }
        return n
    }

    // ------------------------------------------------------------------------------------------ reading (all aggregated in SQL, never a big list in memory)
    fun byType(): List<TypeUse> = synchronized(lock) {
        db().rawQuery("SELECT type, SUM(size), COUNT(*) FROM media GROUP BY type", null).use { c ->
            val out = ArrayList<TypeUse>(); while (c.moveToNext()) out.add(TypeUse(c.getString(0), c.getLong(1), c.getInt(2))); out
        }
    }

    fun byChat(): List<ChatUse> = synchronized(lock) {
        db().rawQuery("SELECT chat_id, SUM(size) AS s, COUNT(*) FROM media GROUP BY chat_id ORDER BY s DESC", null).use { c ->
            val out = ArrayList<ChatUse>(); while (c.moveToNext()) out.add(ChatUse(c.getString(0), c.getLong(1), c.getInt(2))); out
        }
    }

    fun chatByType(chatId: String): List<TypeUse> = synchronized(lock) {
        db().rawQuery("SELECT type, SUM(size), COUNT(*) FROM media WHERE chat_id=? GROUP BY type", arrayOf(chatId)).use { c ->
            val out = ArrayList<TypeUse>(); while (c.moveToNext()) out.add(TypeUse(c.getString(0), c.getLong(1), c.getInt(2))); out
        }
    }

    /** The biggest items first (a photo's readable file and its sealed copy count as one item). Page with [offset]. */
    fun largest(limit: Int, offset: Int = 0, minBytes: Long = 0): List<Entry> = synchronized(lock) {
        db().rawQuery("SELECT media_id, MAX(chat_id), MAX(type), SUM(size) AS s, MAX(opened) FROM media GROUP BY media_id HAVING s >= ? ORDER BY s DESC LIMIT ? OFFSET ?",
            arrayOf(minBytes.toString(), limit.toString(), offset.toString())).use { c ->
            val out = ArrayList<Entry>(); while (c.moveToNext()) out.add(Entry(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4))); out
        }
    }

    /** Items of one chat, biggest first (same grouping as [largest]), optionally only some types. */
    fun ofChat(chatId: String, types: Set<String>? = null, limit: Int = 200, offset: Int = 0): List<Entry> = synchronized(lock) {
        val filter = if (types.isNullOrEmpty()) "" else " AND type IN (" + types.joinToString(",") { "?" } + ")"
        db().rawQuery("SELECT media_id, chat_id, MAX(type), SUM(size) AS s, MAX(opened) FROM media WHERE chat_id=?$filter GROUP BY media_id ORDER BY s DESC LIMIT ? OFFSET ?",
            (listOf(chatId) + (types?.toList() ?: emptyList()) + listOf(limit.toString(), offset.toString())).toTypedArray()).use { c ->
            val out = ArrayList<Entry>(); while (c.moveToNext()) out.add(Entry(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4))); out
        }
    }

    fun totalBytes(): Long = synchronized(lock) { db().rawQuery("SELECT COALESCE(SUM(size),0) FROM media", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } }

    // ------------------------------------------------------------------------------------------ deleting local copies (messages and the server copy are never touched)
    /** Deletes the local files of these items. Returns the bytes freed. */
    fun deleteItems(mediaIds: Collection<String>): Long {
        if (!ready() || mediaIds.isEmpty()) return 0
        var freed = 0L
        synchronized(lock) {
            val d = db(); d.beginTransaction()
            try {
                for (id in mediaIds) {
                    val paths = ArrayList<String>()
                    d.rawQuery("SELECT path FROM media WHERE media_id=?", arrayOf(id)).use { c -> while (c.moveToNext()) paths.add(c.getString(0)) }
                    for (p in paths) { val f = File(p); val len = f.length(); if (f.delete() || !f.exists()) { freed += len; f.parentFile?.let { dir -> if (dir.parentFile == filesDir() && dir.list().isNullOrEmpty()) dir.delete() } } }
                    d.delete("media", "media_id=?", arrayOf(id))
                }
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
        }
        forgetNoted()
        return freed
    }

    /** Deletes one chat's local media, only the given types (null = all of that chat). */
    fun clearChat(chatId: String, types: Set<String>? = null): Long {
        var freed = 0L
        while (true) {
            val page = ofChat(chatId, types, 500, 0)          // rows disappear as they are deleted, so page 0 is always the next batch
            if (page.isEmpty()) break
            freed += deleteItems(page.map { it.mediaId })
            if (page.size < 500) break
        }
        return freed
    }

    /** Wipes every local media file the app keeps (photos, voice, GIFs, videos, audio, documents) and the list. The cache limit / auto-clean keep working. */
    fun clearAllMedia() {
        DataPrefs.clearDisk()
        try { filesDir().listFiles()?.forEach { it.deleteRecursively() } } catch (_: Exception) {}
        if (ready()) synchronized(lock) { db().execSQL("DELETE FROM media") }
        forgetNoted()
    }

    /** Size of the app's databases folder (messages cache, search index, this list). */
    fun databaseBytes(): Long = try { app.getDatabasePath("x").parentFile?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L } catch (_: Exception) { 0L }
}
