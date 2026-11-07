package com.kafisir.wetalks.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Feeds the on-phone search index: messages are indexed as the open chat decrypts them ([live]) and older history is read in the
 * background ([SearchBackfill]). Locked chats are never indexed. Everything runs off the UI thread.
 */
object SearchIndexer {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    @Volatile private var boundUid = ""

    // what the index already holds for the chats opened in this session: message id -> hash of its text; and every message id seen last time
    private val seen = HashMap<String, HashMap<String, Int>>()
    private val lastIds = HashMap<String, Set<String>>()
    private val cutDone = HashMap<String, Long>()

    fun ensure() {
        try { SearchIndex.init(Repo.app); SearchSettings.load(Repo.app) } catch (_: Exception) {}
    }

    /**
     * The expiry time (epoch ms) of a message, 0 when it has none. Messages whose time has passed are not indexed.
     * Disappearing messages are a separate feature: when its field is added to [Msg], return it here.
     */
    fun expOf(@Suppress("UNUSED_PARAMETER") m: Msg): Long = 0L

    private fun itemOf(m: Msg): SearchIndex.Item? {
        val t = SearchQuery.indexText(m.type, m.text, m.deleted, m.unreadable, m.pending) ?: return null
        if (m.ts <= 0) return null
        val exp = expOf(m)
        if (SearchQuery.expired(exp, System.currentTimeMillis())) return null
        return SearchIndex.Item(m.id, m.ts, m.from, t, exp)
    }

    private suspend fun bound() {
        val u = Repo.uid
        if (u.isEmpty() || u == boundUid) return
        SearchIndex.bindTo(u); boundUid = u
    }

    /** Called by the open chat each time its messages are (re)decrypted. Cheap: only new or edited messages reach the database. */
    fun live(ref: ChatRef, msgs: List<Msg>) {
        ensure()
        if (!SearchSettings.enabled || msgs.isEmpty() || ref.id.isEmpty()) return
        if (ChatLock.isLocked(ref.id)) return
        val cut = Repo.cutoff(ref.id)
        scope.launch {
            lock.withLock {
                try {
                    bound()
                    val cid = ref.id
                    if (cut > 0 && cutDone[cid] != cut) { SearchIndex.removeUpTo(cid, cut); cutDone[cid] = cut }
                    val mine = seen.getOrPut(cid) { HashMap() }
                    val toWrite = ArrayList<SearchIndex.Item>()
                    val newHash = HashMap<String, Int>()
                    for (m in msgs) {
                        if (m.pending) continue
                        val it = itemOf(m)?.takeIf { x -> x.ts > cut } ?: continue
                        val h = it.text.hashCode() * 31 + it.ts.hashCode()
                        newHash[it.mid] = h
                        if (mine[it.mid] != h) toWrite.add(it)
                    }
                    // a message that is no longer there (deleted for everyone) or now only a "deleted" marker: take it out of the index
                    val now = msgs.asSequence().filter { !it.pending }.map { it.id }.toHashSet()
                    val oldest = msgs.asSequence().filter { !it.pending }.map { it.id }.minOrNull()
                    val gone = HashSet<String>()
                    msgs.forEach { if (it.deleted) gone.add(it.id) }
                    lastIds[cid]?.let { prev -> if (oldest != null) for (id in prev) if (id !in now && id > oldest) gone.add(id) }   // only ids inside the window: older ones just scrolled out of it
                    lastIds[cid] = now
                    if (toWrite.isNotEmpty()) SearchIndex.upsert(cid, toWrite)
                    if (gone.isNotEmpty()) SearchIndex.remove(cid, gone)
                    for (g in gone) mine.remove(g)
                    for (w in toWrite) mine[w.mid] = newHash[w.mid]!!
                } catch (e: CancellationException) { throw e } catch (_: Exception) { seen.remove(ref.id); lastIds.remove(ref.id) }
            }
        }
    }

    /** A star-free, text-free reset of what this session remembers (after "Clear search index"). */
    private fun forgetSession() { synchronized(seen) { seen.clear(); lastIds.clear(); cutDone.clear() } }

    fun clearAll() { scope.launch { lock.withLock { forgetSession(); SearchIndex.clear() } } }

    /** Turning the switch off erases the index. */
    fun onDisabled() { SearchBackfill.stop(); clearAll() }

    fun onEnabled() { SearchBackfill.start() }

    /** Sign-out / account deletion: stop, forget and delete the file. */
    fun onSignOut() {
        SearchBackfill.stop()
        Starred.stop()
        forgetSession(); boundUid = ""
        SearchIndex.wipe()
    }

    /** Called once the person is signed in and the app is in use. */
    fun start() {
        ensure()
        Starred.watch()
        if (SearchSettings.enabled) SearchBackfill.start()
    }
}

/** Reads older history of each chat into the index, a page at a time, only while the app is open on screen and idle. */
object SearchBackfill {
    private var job: Job? = null
    @Volatile private var chatList: List<ChatItem> = emptyList()
    var running by androidx.compose.runtime.mutableStateOf(false); private set
    /** 0..1 over the chats that can be indexed; shown in Data & storage. */
    var fraction by androidx.compose.runtime.mutableFloatStateOf(0f); private set
    private val retryAt = HashMap<String, Long>()

    fun start() {
        if (job?.isActive == true) return
        job = SearchIndexer.scope.launch { loop() }
    }

    fun stop() { job?.cancel(); job = null; running = false; chatList = emptyList(); retryAt.clear() }

    private suspend fun loop() {
        val watcher = SearchIndexer.scope.launch { try { Repo.chats().collect { chatList = it } } catch (e: CancellationException) { throw e } catch (_: Exception) {} }
        var lastLocked: Set<String> = emptySet()
        var pruned = false
        var firstSeen = 0L
        try {
            while (isActive) {
                if (!SearchSettings.enabled || Repo.uid.isEmpty()) { running = false; delay(10_000); continue }
                // locked chats are never kept in the index (also when a chat was locked after it had been indexed)
                val locked = ChatLock.all
                if (locked != lastLocked) { SearchIndex.removeChats(locked); lastLocked = locked }
                val chats = chatList
                if (chats.isEmpty()) { delay(3_000); continue }
                if (!pruned) { if (firstSeen == 0L) firstSeen = System.currentTimeMillis(); if (System.currentTimeMillis() - firstSeen > 20_000) { SearchIndex.retainChats(chats.map { it.ref.id }.toSet()); pruned = true } }
                // only while the app is on screen and no call is going on
                if (!Calls.foreground || Calls.phase != CallPhase.IDLE) { running = false; delay(4_000); continue }
                val progress = SearchIndex.progress()
                val order = chats.map { it.ref.id }
                fraction = SearchQuery.Backfill.fraction(order, progress, locked)
                val now = System.currentTimeMillis()
                val skip = retryAt.filterValues { it > now }.keys
                val next = SearchQuery.Backfill.pickNext(order, progress, locked, skip)
                if (next == null) { running = false; SearchIndex.purgeExpired(); delay(60_000); continue }
                running = true
                val item = chats.first { it.ref.id == next }
                try { step(item.ref, progress[next]) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { retryAt[next] = System.currentTimeMillis() + 120_000 }
                delay(1_500)               // gentle: a page, then a pause, so the phone and the data connection stay free
            }
        } finally { watcher.cancel(); running = false }
    }

    private suspend fun step(ref: ChatRef, p: SearchQuery.Progress?) {
        val page = SearchQuery.Backfill.PAGE
        val raws = Repo.rawPage(ref, p?.oldestKey, page)
        // decrypting touches caches that the UI also uses: do it in small slices on the main dispatcher so a frame is never held up for long
        val msgs = ArrayList<Msg>(raws.size)
        for (chunk in raws.chunked(25)) {
            msgs.addAll(withContext(Dispatchers.Main) { Repo.decryptAll(ref, chunk) })
            delay(40)
        }
        val readable = msgs.count { !it.deleted }
        // keys not available yet (a group key that has not arrived): do not mark these messages as read; try this chat again later
        if (readable > 0 && msgs.count { it.unreadable } * 2 > readable) { retryAt[ref.id] = System.currentTimeMillis() + 600_000; return }
        val cut = Repo.cutoff(ref.id)
        val items = msgs.mapNotNull { m ->
            if (Repo.isHidden(m.id) || m.ts <= cut) null
            else SearchQuery.indexText(m.type, m.text, m.deleted, m.unreadable, m.pending)?.let { t ->
                val exp = SearchIndexer.expOf(m)
                if (m.ts <= 0 || SearchQuery.expired(exp, System.currentTimeMillis())) null else SearchIndex.Item(m.id, m.ts, m.from, t, exp)
            }
        }
        if (ChatLock.isLocked(ref.id) || !SearchSettings.enabled) return
        SearchIndex.upsert(ref.id, items)
        SearchIndex.setProgress(ref.id, SearchQuery.Backfill.advance(p, raws.map { it.id }, page))
    }
}
