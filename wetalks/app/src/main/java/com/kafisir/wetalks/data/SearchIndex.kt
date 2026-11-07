package com.kafisir.wetalks.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** The on/off switch for the on-phone search index (default on). Turning it off also erases the index. */
object SearchSettings {
    var enabled by mutableStateOf(true); private set
    private lateinit var app: Context
    fun load(c: Context) { app = c.applicationContext; enabled = app.getSharedPreferences("wt_search", Context.MODE_PRIVATE).getBoolean("on", true) }
    fun turn(v: Boolean) {
        enabled = v
        if (::app.isInitialized) app.getSharedPreferences("wt_search", Context.MODE_PRIVATE).edit().putBoolean("on", v).apply()
        if (v) SearchIndexer.onEnabled() else SearchIndexer.onDisabled()
    }
}

/**
 * Local full-text index of decrypted message text. It lives in this app's private storage only (backups are off), is built on the phone
 * (the server only ever holds encrypted text), is never given locked chats and is erased on sign-out.
 *
 * SQLite FTS4 with the unicode61 tokenizer (Devanagari / Hinglish words split like any other words, accents ignored, prefix search).
 * Reads and writes run on their own threads, never on the UI thread.
 */
object SearchIndex {
    private const val NAME = "wt_search.db"
    private const val VERSION = 1
    private lateinit var app: Context
    @Volatile private var helper: Helper? = null

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "search-write").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }.asCoroutineDispatcher()
    private val readers = Executors.newFixedThreadPool(2) { r -> Thread(r, "search-read").apply { isDaemon = true } }.asCoroutineDispatcher()

    fun init(c: Context) { app = c.applicationContext }
    private val ready get() = ::app.isInitialized

    private class Helper(c: Context) : SQLiteOpenHelper(c, NAME, null, VERSION) {
        init { setWriteAheadLoggingEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE msg(rowid INTEGER PRIMARY KEY AUTOINCREMENT, chat TEXT NOT NULL, mid TEXT NOT NULL, ts INTEGER NOT NULL, frm TEXT NOT NULL DEFAULT '', exp INTEGER NOT NULL DEFAULT 0, UNIQUE(chat, mid))")
            db.execSQL("CREATE INDEX msg_chat_ts ON msg(chat, ts)")
            db.execSQL("CREATE INDEX msg_ts ON msg(ts)")
            db.execSQL("CREATE VIRTUAL TABLE msg_fts USING fts4(body, tokenize=unicode61)")
            db.execSQL("CREATE TABLE progress(chat TEXT PRIMARY KEY, oldest TEXT, done INTEGER NOT NULL DEFAULT 0, indexed INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {   // it is only a cache of what the server already has: rebuild
            db.execSQL("DROP TABLE IF EXISTS msg_fts"); db.execSQL("DROP TABLE IF EXISTS msg"); db.execSQL("DROP TABLE IF EXISTS progress"); db.execSQL("DROP TABLE IF EXISTS meta")
            onCreate(db)
        }
    }

    @Synchronized private fun db(): SQLiteDatabase {
        var h = helper
        if (h == null) { h = Helper(app); helper = h }
        return h.writableDatabase
    }

    class Item(val mid: String, val ts: Long, val from: String, val text: String, val exp: Long = 0)
    class Row(val rowId: Long, val chat: String, val mid: String, val ts: Long, val from: String, val body: String)
    class Stats(val messages: Long, val chats: Long, val bytes: Long)

    /** Adds new messages and updates edited ones (same chat + message id). */
    suspend fun upsert(chat: String, items: List<Item>) {
        if (items.isEmpty() || !ready) return
        withContext(writer) {
            val d = db()
            d.beginTransaction()
            try {
                val find = d.compileStatement("SELECT rowid FROM msg WHERE chat = ? AND mid = ?")
                for (it in items) {
                    find.clearBindings(); find.bindString(1, chat); find.bindString(2, it.mid)
                    val id = try { find.simpleQueryForLong() } catch (_: android.database.sqlite.SQLiteDoneException) { -1L }
                    if (id >= 0) {
                        d.execSQL("UPDATE msg SET ts = ?, frm = ?, exp = ? WHERE rowid = ?", arrayOf(it.ts, it.from, it.exp, id))
                        d.execSQL("UPDATE msg_fts SET body = ? WHERE docid = ?", arrayOf(it.text, id))
                    } else {
                        val cv = ContentValues().apply { put("chat", chat); put("mid", it.mid); put("ts", it.ts); put("frm", it.from); put("exp", it.exp) }
                        val nid = d.insert("msg", null, cv)
                        if (nid >= 0) d.execSQL("INSERT INTO msg_fts(docid, body) VALUES (?, ?)", arrayOf(nid, it.text))
                    }
                }
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
        }
    }

    suspend fun remove(chat: String, mids: Collection<String>) {
        if (mids.isEmpty() || !ready) return
        withContext(writer) {
            val d = db(); d.beginTransaction()
            try {
                for (m in mids) {
                    d.execSQL("DELETE FROM msg_fts WHERE docid IN (SELECT rowid FROM msg WHERE chat = ? AND mid = ?)", arrayOf(chat, m))
                    d.execSQL("DELETE FROM msg WHERE chat = ? AND mid = ?", arrayOf(chat, m))
                }
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
        }
    }

    /** Forgets everything of these chats (a chat that was locked, deleted or left), progress included. */
    suspend fun removeChats(chats: Collection<String>) {
        if (chats.isEmpty() || !ready) return
        withContext(writer) {
            val d = db(); d.beginTransaction()
            try {
                for (c in chats) {
                    d.execSQL("DELETE FROM msg_fts WHERE docid IN (SELECT rowid FROM msg WHERE chat = ?)", arrayOf(c))
                    d.execSQL("DELETE FROM msg WHERE chat = ?", arrayOf(c))
                    d.execSQL("DELETE FROM progress WHERE chat = ?", arrayOf(c))
                }
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
        }
    }

    /** Keeps only these chats (call with the chat list, never with an empty one). */
    suspend fun retainChats(keep: Set<String>) {
        if (keep.isEmpty() || !ready) return
        val have = withContext(readers) { db().rawQuery("SELECT DISTINCT chat FROM msg UNION SELECT chat FROM progress", null).use { c -> val l = ArrayList<String>(); while (c.moveToNext()) l.add(c.getString(0)); l } }
        removeChats(have.filter { it !in keep })
    }

    /** "Clear chat for me": messages up to [ts] are gone from this person's chat. */
    suspend fun removeUpTo(chat: String, ts: Long) {
        if (!ready) return
        withContext(writer) {
            val d = db(); d.beginTransaction()
            try {
                d.execSQL("DELETE FROM msg_fts WHERE docid IN (SELECT rowid FROM msg WHERE chat = ? AND ts <= ?)", arrayOf(chat, ts))
                d.execSQL("DELETE FROM msg WHERE chat = ? AND ts <= ?", arrayOf(chat, ts))
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
        }
    }

    /** Disappearing messages: rows whose expiry time has passed. */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()) {
        if (!ready) return
        withContext(writer) {
            val d = db(); d.beginTransaction()
            try {
                d.execSQL("DELETE FROM msg_fts WHERE docid IN (SELECT rowid FROM msg WHERE exp > 0 AND exp <= ?)", arrayOf(now))
                d.execSQL("DELETE FROM msg WHERE exp > 0 AND exp <= ?", arrayOf(now))
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
        }
    }

    // ---- searching
    private fun where(p: SearchQuery.Parsed, chat: String?, exclude: Set<String>, after: SearchQuery.Cursor?, args: ArrayList<String>, match: String?): String {
        val sb = StringBuilder("1=1")
        if (match != null) { sb.append(" AND m.rowid IN (SELECT docid FROM msg_fts WHERE msg_fts MATCH ?)"); args.add(match) }
        if (chat != null) { sb.append(" AND m.chat = ?"); args.add(chat) }
        if (p.from != null) { sb.append(" AND m.ts >= ?"); args.add(p.from.toString()) }
        if (p.to != null) { sb.append(" AND m.ts <= ?"); args.add(p.to.toString()) }
        val ex = exclude.take(400)
        if (ex.isNotEmpty()) { sb.append(" AND m.chat NOT IN (").append(ex.joinToString(",") { "?" }).append(")"); args.addAll(ex) }
        if (after != null) { sb.append(" AND (m.ts < ? OR (m.ts = ? AND m.rowid < ?))"); args.add(after.ts.toString()); args.add(after.ts.toString()); args.add(after.rowId.toString()) }
        sb.append(" AND (m.exp = 0 OR m.exp > ").append(System.currentTimeMillis()).append(")")
        return sb.toString()
    }

    /** One page of results, newest first. [chat] = only that chat (otherwise all chats except [exclude], which is where locked chats go). */
    suspend fun search(p: SearchQuery.Parsed, chat: String?, exclude: Set<String>, after: SearchQuery.Cursor?, limit: Int): List<Row> {
        if (!ready || p.isEmpty) return emptyList()
        return withContext(readers) {
            val args = ArrayList<String>()
            val w = where(p, chat, exclude, after, args, SearchQuery.matchExpr(p))
            val sql = "SELECT m.rowid, m.chat, m.mid, m.ts, m.frm, (SELECT body FROM msg_fts WHERE docid = m.rowid) FROM msg m WHERE $w ORDER BY m.ts DESC, m.rowid DESC LIMIT $limit"
            db().rawQuery(sql, args.toTypedArray()).use { c ->
                val out = ArrayList<Row>()
                while (c.moveToNext()) out.add(Row(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4) ?: "", c.getString(5) ?: ""))
                out
            }
        }
    }

    /** Message ids only (no text) of the matches inside one chat, newest first, up to [cap]. Used for "3 of 12" and next / previous. */
    suspend fun chatHits(chat: String, p: SearchQuery.Parsed, cap: Int = 3000): List<SearchQuery.Hit> {
        if (!ready || p.isEmpty) return emptyList()
        return withContext(readers) {
            val args = ArrayList<String>()
            val w = where(p, chat, emptySet(), null, args, SearchQuery.matchExpr(p))
            db().rawQuery("SELECT m.rowid, m.mid, m.ts, m.frm FROM msg m WHERE $w ORDER BY m.ts DESC, m.rowid DESC LIMIT $cap", args.toTypedArray()).use { c ->
                val out = ArrayList<SearchQuery.Hit>()
                while (c.moveToNext()) out.add(SearchQuery.Hit(chat, c.getString(1), c.getLong(2), c.getLong(0), c.getString(3) ?: ""))
                out
            }
        }
    }

    // ---- back-fill progress
    suspend fun progress(): Map<String, SearchQuery.Progress> {
        if (!ready) return emptyMap()
        return withContext(readers) {
            db().rawQuery("SELECT chat, oldest, done, indexed FROM progress", null).use { c ->
                val m = HashMap<String, SearchQuery.Progress>()
                while (c.moveToNext()) m[c.getString(0)] = SearchQuery.Progress(c.getString(1), c.getInt(2) != 0, c.getInt(3))
                m
            }
        }
    }

    suspend fun setProgress(chat: String, p: SearchQuery.Progress) {
        if (!ready) return
        withContext(writer) { db().execSQL("INSERT OR REPLACE INTO progress(chat, oldest, done, indexed) VALUES (?, ?, ?, ?)", arrayOf(chat, p.oldestKey, if (p.done) 1 else 0, p.indexed)) }
    }

    // ---- housekeeping
    suspend fun stats(): Stats {
        if (!ready) return Stats(0, 0, 0)
        return withContext(readers) {
            val d = db()
            val n = d.rawQuery("SELECT COUNT(*), COUNT(DISTINCT chat) FROM msg", null).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else 0L to 0L }
            val bytes = app.getDatabasePath(NAME).let { (if (it.exists()) it.length() else 0L) + java.io.File(it.path + "-wal").let { w -> if (w.exists()) w.length() else 0L } }
            Stats(n.first, n.second, bytes)
        }
    }

    /** "Clear search index": everything is erased; back-fill starts over if search is on. */
    suspend fun clear() {
        if (!ready) return
        withContext(writer) {
            val d = db(); d.beginTransaction()
            try { d.execSQL("DELETE FROM msg_fts"); d.execSQL("DELETE FROM msg"); d.execSQL("DELETE FROM progress"); d.setTransactionSuccessful() } finally { d.endTransaction() }
            try { d.execSQL("VACUUM") } catch (_: Exception) {}
        }
    }

    /** The index belongs to one account: opening it under another account erases it first. */
    suspend fun bindTo(uid: String) {
        if (!ready || uid.isEmpty()) return
        val owner = withContext(readers) { db().rawQuery("SELECT v FROM meta WHERE k = 'uid'", null).use { c -> if (c.moveToFirst()) c.getString(0) else null } }
        if (owner == uid) return
        if (owner != null) clear()
        withContext(writer) { db().execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES ('uid', ?)", arrayOf(uid)) }
    }

    /** Sign-out: closes the database and deletes the file (blocks briefly; call it from sign-out only). */
    fun wipe() {
        if (!ready) return
        try {
            kotlinx.coroutines.runBlocking(writer) {
                synchronized(this@SearchIndex) { helper?.close(); helper = null }
                app.deleteDatabase(NAME)
            }
        } catch (_: Exception) { try { app.deleteDatabase(NAME) } catch (_: Exception) {} }
    }
}
