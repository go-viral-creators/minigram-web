package com.kafisir.wetalks.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Disappearing messages on the phone: the per-chat timer (cached listener), the server clock, the clean-up that removes expired
 * messages for everybody, and the WorkManager jobs that run it when the app is closed.
 * The rules themselves (what a timer is, when a message is expired, ...) are in [DisappearRules] and unit tested.
 */
object Disappear {
    /** What `chat_settings/<cid>` says. [loaded] is false until the first answer; [exists] is false for a chat that never had a setting. */
    data class Setting(val loaded: Boolean, val exists: Boolean, val secs: Int, val by: String = "", val ts: Long = 0L) {
        val timer get() = if (exists) secs else 0
    }
    private val UNKNOWN = Setting(false, false, 0)

    private const val PERIODIC = "wt_expiry_periodic"
    private const val ONCE = "wt_expiry_once"
    private const val PERIODIC_MINUTES = 30L
    private const val FULL_SCAN_MS = 6 * 3_600_000L

    private val settings = HashMap<String, MutableStateFlow<Setting>>()
    private val attached = ArrayList<Pair<DatabaseReference, ValueEventListener>>()
    @Volatile private var offset = 0L
    @Volatile private var clockReady = false
    private val lock = Mutex()
    private var ticker: Job? = null

    // ------------------------------------------------------------------------------------------ clock
    /** Server time in milliseconds (the phone clock plus Firebase's measured offset). Expiry times are always on this clock. */
    fun now(): Long = System.currentTimeMillis() + offset

    private suspend fun ensureClock() {
        if (clockReady) return
        try { offset = Repo.db.getReference(".info/serverTimeOffset").get().await().getValue(Long::class.java) ?: 0L; clockReady = true } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------------------------------ the setting of a chat
    /** Live setting of one chat; the listener is attached on first use and kept (it is a tiny node). */
    @Synchronized fun setting(cid: String): StateFlow<Setting> {
        if (Repo.uid.isEmpty()) return MutableStateFlow(Setting(true, false, 0))          // signed out: nothing to listen to (and nothing cached for the next account)
        return settings.getOrPut(cid) {
        val f = MutableStateFlow(UNKNOWN)
        run {
            val r = Repo.db.getReference("chat_settings/$cid")
            val l = object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { f.value = parse(s) }
                override fun onCancelled(e: DatabaseError) { f.value = Setting(true, false, 0) }       // old server rules (no such node) or no access: behaves as "never set"
            }
            r.addValueEventListener(l); attached.add(r to l)
        }
        f
        }
    }

    private fun parse(s: DataSnapshot): Setting {
        if (!s.exists()) return Setting(true, false, 0)
        val secs = (s.child("disappear").getValue(Long::class.java) ?: 0L).toInt()
        return Setting(true, true, if (DisappearRules.isValidTimer(secs.toLong())) secs else 0, s.child("by").getValue(String::class.java) ?: "", s.child("ts").getValue(Long::class.java) ?: 0L)
    }

    /** Is a setting known for this chat right now (without waiting)? Used to decide whether the older server rules may see `lastExp`. */
    fun hasSetting(cid: String): Boolean = synchronized(this) { settings[cid]?.value?.exists == true }

    /** The timer in seconds for a message about to be sent, or null when this chat has never had a setting (then no `exp` and no `lastExp` are written). */
    suspend fun timerFor(ref: ChatRef): Int? {
        if (Repo.uid.isEmpty()) return null
        val f = setting(ref.id)
        val s = if (f.value.loaded) f.value else (withTimeoutOrNull(3000) { f.first { it.loaded } } ?: return null)
        return if (s.exists) s.secs else null
    }

    private fun denied(e: Throwable) = generateSequence(e) { it.cause }.any { (it.message ?: "").lowercase().let { m -> "permission_denied" in m || "permission denied" in m } }

    /**
     * A send was refused. If the chat's timer changed a moment ago (the other person turned it on or off while this message was being sent),
     * the refusal is only about the old timer: report true so the caller sends once more with the new one.
     */
    suspend fun retryAfterRefusal(ref: ChatRef, e: Throwable, used: Int?): Boolean {
        if (!denied(e)) return false
        delay(600)                                                  // the listener needs a moment to see the new setting
        val s = setting(ref.id).value
        val now = if (s.exists) s.secs else null
        return s.loaded && now != used
    }

    /** Turns the timer of a chat on/off and posts the system line. Throws a readable error (the caller shows it). */
    suspend fun setTimer(ref: ChatRef, secs: Int) {
        if (!DisappearRules.isValidTimer(secs.toLong())) throw IllegalArgumentException("That time is not allowed")
        val me = Repo.uid
        try {
            Repo.db.getReference("chat_settings/${ref.id}").setValue(hashMapOf("disappear" to secs, "by" to me, "ts" to ServerValue.TIMESTAMP)).await()
        } catch (e: Exception) { throw IllegalStateException(Repo.explain(e, "Changing disappearing messages")) }
        synchronized(this) { settings[ref.id]?.value = Setting(true, true, secs, me, now()) }
        val name = Repo.user(me).value?.name.orEmpty()
        val text = DisappearRules.systemText(name, secs)
        // the line is a courtesy: if it cannot be posted the setting still stands
        try { Repo.send(ref, "sys", text, null, DisappearRules.systemPreview(text)) } catch (e: CancellationException) { throw e } catch (e: Exception) { android.util.Log.w("WeTalks", "disappearing line not posted", e) }
    }

    // ------------------------------------------------------------------------------------------ the ledger of media (so files go even when the message is already gone)
    private fun prefs(): android.content.SharedPreferences = Repo.app.getSharedPreferences("wt_disappear_" + Repo.uid, Context.MODE_PRIVATE)
    private var ledgerCache: Map<String, DisappearRules.Entry>? = null; private var ledgerUid = ""
    @Synchronized private fun ledger(): Map<String, DisappearRules.Entry> {
        if (ledgerUid != Repo.uid || ledgerCache == null) { ledgerCache = DisappearRules.parseLedger(prefs().getString("ledger", null)); ledgerUid = Repo.uid }
        return ledgerCache!!
    }
    @Synchronized private fun saveLedger(m: Map<String, DisappearRules.Entry>) { ledgerCache = m; ledgerUid = Repo.uid; prefs().edit().putString("ledger", DisappearRules.writeLedger(m)).apply() }

    /** Called with every decoded snapshot of a chat: remembers media of messages that will expire (also received ones, for the local cache). */
    fun noteMessages(msgs: List<Msg>, me: String) {
        if (Repo.uid.isEmpty() || msgs.none { it.exp > 0 && !it.mediaId.isNullOrEmpty() }) return
        try {
            var cur = ledger(); var changed = false
            for (m in msgs) {
                val id = m.mediaId ?: continue
                if (m.exp <= 0 || m.deleted) continue
                val n = DisappearRules.noteMedia(cur, id, m.exp, m.from == me)
                if (n !== cur) { cur = n; changed = true }
            }
            if (changed) saveLedger(cur)
        } catch (_: Exception) {}
    }

    /** After a successful send of a message that expires: remember its media (this phone uploaded it) and make sure a clean-up is scheduled. */
    fun noteSent(exp: Long, type: String, payload: String) {
        try {
            if (type in MEDIA_TYPES) {
                val id = org.json.JSONObject(payload).optString("m")
                if (id.isNotEmpty()) saveLedger(DisappearRules.noteMedia(ledger(), id, exp, true))
            }
            val next = prefs().getLong("next", 0L)
            if (next <= now() || exp < next) { prefs().edit().putLong("next", exp).apply(); scheduleOnce(Repo.app, exp) }
        } catch (_: Exception) {}
    }

    private val MEDIA_TYPES = setOf("image", "voice", "gif", "video", "audio", "file")

    private fun purgeLocal(id: String) {
        DataPrefs.forget(id); Repo.dropCachedMedia(id)
        try { File(FileKit.cacheDir(Repo.app), id.takeLast(40)).deleteRecursively() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------------------------------ clean-up
    private fun timedSet(): MutableSet<String> = (prefs().getStringSet("timed", null) ?: emptySet()).toMutableSet()

    /**
     * Removes expired messages from the database (for both sides), the uploader's own media from the media server and the cached copies from this
     * phone, and fixes the chat-list preview. Looks at every chat that has (or had) a timer. Returns the next time something expires (0 = nothing known).
     */
    suspend fun sweepAll(): Long = lock.withLock { sweepAllLocked() }

    private suspend fun sweepAllLocked(): Long {
        val me = Repo.uid
        if (me.isEmpty()) return 0L
        ensureClock()
        val list = try { Repo.db.getReference("user_chats/$me").get().await() } catch (_: Exception) { return 0L }
        val chats = list.children.mapNotNull { c ->
            val id = c.key ?: return@mapNotNull null
            val type = c.child("type").getValue(String::class.java) ?: ""
            val peer = c.child("peer").getValue(String::class.java) ?: ""
            val kind = when (type) { "group" -> Kind.GROUP; "channel" -> Kind.CHANNEL; else -> Kind.DM }
            if (kind == Kind.DM && peer.isEmpty()) return@mapNotNull null
            Triple(ChatRef(id, kind, peer.ifEmpty { null }), c.child("lastExp").getValue(Long::class.java) ?: 0L, c.child("lastTs").getValue(Long::class.java) ?: 0L)
        }.sortedByDescending { it.third }.take(120)
        val ids = chats.map { it.first.id }.toSet()
        var timed = timedSet().filter { it in ids }.toMutableSet()
        chats.filter { it.second > 0 }.forEach { timed.add(it.first.id) }
        val p = prefs()
        if (now() - p.getLong("scan", 0L) > FULL_SCAN_MS) {                      // once in a while: which chats have a setting at all (a few tiny reads)
            val sem = Semaphore(4)
            val found = coroutineScope {
                chats.map { c -> async(Dispatchers.IO) { sem.withPermit { try { if (Repo.db.getReference("chat_settings/${c.first.id}").get().await().exists()) c.first.id else null } catch (_: Exception) { null } } } }.awaitAll()
            }.filterNotNull().toMutableSet()
            timed = found.also { s -> chats.filter { it.second > 0 }.forEach { s.add(it.first.id) } }
            p.edit().putLong("scan", now()).apply()
        }
        p.edit().putStringSet("timed", timed).apply()
        var next = 0L
        val sem = Semaphore(3)
        val nexts = coroutineScope {
            chats.filter { it.first.id in timed }.map { c -> async(Dispatchers.IO) { sem.withPermit { try { sweepChatLocked(c.first) } catch (e: CancellationException) { throw e } catch (e: Exception) { android.util.Log.w("WeTalks", "disappearing sweep of ${c.first.id} failed", e); 0L } } } }.awaitAll()
        }
        for (n in nexts) if (n > 0 && (next == 0L || n < next)) next = n
        val ln = sweepLedger()
        if (ln > 0 && (next == 0L || ln < next)) next = ln
        p.edit().putLong("next", next).apply()
        if (next > 0) scheduleOnce(Repo.app, next)
        return next
    }

    /** Sweeps one chat now (used when a chat is opened and when its next message expires on screen). */
    suspend fun sweepChat(ref: ChatRef) { lock.withLock { ensureClock(); try { sweepChatLocked(ref) } catch (e: CancellationException) { throw e } catch (e: Exception) { android.util.Log.w("WeTalks", "disappearing sweep failed", e) } } }

    /** Returns the earliest exp that is still in the future in this chat (0 = none). */
    private suspend fun sweepChatLocked(ref: ChatRef): Long {
        val me = Repo.uid
        val msgs = Repo.db.getReference("messages/${ref.id}")
        var removed = 0
        var next = 0L
        for (round in 0 until 6) {
            // the oldest 101 messages that carry an exp: the expired ones come first, the first one that is not expired tells when to come back
            val snap = msgs.orderByChild("exp").startAt(1.0).limitToFirst(101).get().await()
            val due = ArrayList<DataSnapshot>()
            next = 0L
            val cutoff = now() - DisappearRules.SAFETY_MS
            for (c in snap.children) {
                val exp = c.child("exp").getValue(Long::class.java) ?: continue
                if (DisappearRules.isDeletable(exp, cutoff)) due.add(c) else if (next == 0L) next = exp
            }
            if (due.isEmpty()) break
            val up = HashMap<String, Any?>()
            for (c in due) {
                val id = c.key ?: continue
                val exp = c.child("exp").getValue(Long::class.java) ?: 0L
                up["messages/${ref.id}/$id"] = null; up[DisappearRules.indexPath(exp, ref.id, id)] = null
                if (ref.kind == Kind.DM) up["pins/${ref.id}/$id"] = null
                val type = c.child("type").getValue(String::class.java) ?: ""
                if (type in MEDIA_TYPES) {
                    val mid = try { Repo.decrypt(ref, Repo.rawOf(c)).mediaId } catch (_: Exception) { null }
                    if (!mid.isNullOrEmpty()) {
                        if ((c.child("from").getValue(String::class.java) ?: "") == me) try { Repo.removeMedia(mid) } catch (_: Exception) {}
                        purgeLocal(mid)
                    }
                }
            }
            try { Repo.db.reference.updateChildren(up).await() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {                                       // one path the rules refuse must not block the rest: try the messages one by one
                for (c in due) try { Repo.db.getReference("messages/${ref.id}/${c.key}").removeValue().await() } catch (_: Exception) {}
            }
            removed += due.size
            if (next != 0L || snap.childrenCount < 101) break            // everything that was due is gone
        }
        if (removed > 0) Repo.refreshLast(ref)
        return next
    }

    /** Media files of messages that already expired, even when the message is gone from the database. Returns the next expiry among the rest. */
    private suspend fun sweepLedger(): Long {
        val me = Repo.uid
        val cur = ledger()
        val due = DisappearRules.dueMedia(cur, now() - DisappearRules.SAFETY_MS)
        if (due.isEmpty()) return DisappearRules.nextExpiry(cur.values.map { it.exp }, now())
        for (id in due) {
            val e = cur[id] ?: continue
            if (e.mine) try { Repo.removeMedia(id) } catch (_: Exception) {}
            purgeLocal(id)
        }
        if (Repo.uid != me) return 0L
        val left = cur - due.toSet()
        saveLedger(left)
        return DisappearRules.nextExpiry(left.values.map { it.exp }, now())
    }

    // ------------------------------------------------------------------------------------------ when it runs
    /** Called once the person is signed in and past the PIN: schedules the background jobs and keeps a timer going while the process lives. */
    fun start(c: Context) {
        if (Repo.uid.isEmpty() || ticker?.isActive == true) return
        schedulePeriodic(c.applicationContext)
        ticker = Repo.scope.launch(Dispatchers.IO) {
            while (isActive) {
                val next = try { sweepAll() } catch (e: CancellationException) { throw e } catch (_: Exception) { 0L }
                val wait = if (next > 0) DisappearRules.delayUntil(next, now()).coerceIn(5_000L, 15 * 60_000L) else 15 * 60_000L
                delay(wait)
            }
        }
    }

    fun schedulePeriodic(c: Context) {
        try {
            val req = PeriodicWorkRequest.Builder(ExpiryWorker::class.java, PERIODIC_MINUTES, TimeUnit.MINUTES).setConstraints(netOnly()).build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (_: Exception) {}
    }

    /** A one-off run at the next known expiry. The system may run it a bit late (Doze); the periodic job and the server sweep cover that. */
    fun scheduleOnce(c: Context, exp: Long) {
        try {
            val req = OneTimeWorkRequest.Builder(ExpiryWorker::class.java).setInitialDelay(DisappearRules.delayUntil(exp, now()), TimeUnit.MILLISECONDS).setConstraints(netOnly()).build()
            WorkManager.getInstance(c).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
        } catch (_: Exception) {}
    }

    private fun netOnly() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun cancelWork(c: Context) { try { val w = WorkManager.getInstance(c); w.cancelUniqueWork(PERIODIC); w.cancelUniqueWork(ONCE) } catch (_: Exception) {} }

    /** Sign-out: stop the jobs and the listeners. The ledger stays under this account's own preference file. */
    fun onSignOut() {
        ticker?.cancel(); ticker = null
        try { cancelWork(Repo.app) } catch (_: Exception) {}
        synchronized(this) { attached.forEach { try { it.first.removeEventListener(it.second) } catch (_: Exception) {} }; attached.clear(); settings.clear() }
    }
}

/** Runs the clean-up in the background (periodic and one-off). Never needs a screen; does nothing when signed out. */
class ExpiryWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        return try {
            if (Repo.uid.isEmpty()) { Disappear.cancelWork(applicationContext); return Result.success() }
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(4 * 60_000L) { Disappear.sweepAll() } }
            Result.success()
        } catch (e: Throwable) {
            android.util.Log.w("WeTalks", "disappearing worker failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.success()   // the next periodic run tries again anyway
        }
    }
}
