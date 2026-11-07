package com.kafisir.wetalks.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Hooks other features can plug in without this file knowing them (for example the starred messages). Both are optional. */
object BackupExtras {
    @Volatile var starredIds: (() -> List<String>)? = null
    @Volatile var restoreStarred: ((List<String>) -> Unit)? = null
}

/** Chat backup settings (kept on this phone) and the state shared by the screens and the background worker. */
object BackupStore {
    var freq by mutableStateOf(BackupFreq.OFF); private set
    var wifiOnly by mutableStateOf(true); private set
    var charging by mutableStateOf(false); private set
    var includeMedia by mutableStateOf(false); private set
    var keepSets by mutableIntStateOf(BackupPlan.DEFAULT_KEEP_SETS); private set
    var encrypted by mutableStateOf(false); private set
    var cloudOn by mutableStateOf(false); private set
    var lastSuccess by mutableLongStateOf(0L); private set
    var lastMessage by mutableStateOf(""); private set
    var scheduledSince by mutableLongStateOf(0L); private set
    /** Progress of the backup that is running now in this process; null when none. */
    val progress = MutableStateFlow<BackupEngine.Progress?>(null)
    @Volatile var cancelRequested = false
    val runLock = Mutex()
    private var loaded = false

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("wt_backup", Context.MODE_PRIVATE)
    fun dir(c: Context) = File(c.applicationContext.filesDir, "backups").apply { mkdirs() }
    fun work(c: Context) = File(c.applicationContext.noBackupFilesDir, "backup-work").apply { mkdirs() }

    @Synchronized fun ensure(c: Context) {
        if (loaded) return
        val s = sp(c)
        freq = BackupFreq.of(s.getString("freq", null)); wifiOnly = s.getBoolean("wifi", true); charging = s.getBoolean("charge", false)
        includeMedia = s.getBoolean("media", false); keepSets = s.getInt("keep", BackupPlan.DEFAULT_KEEP_SETS); encrypted = s.getBoolean("enc", false)
        cloudOn = s.getBoolean("cloud", false); lastSuccess = s.getLong("last", 0L); lastMessage = s.getString("lastMsg", "") ?: ""; scheduledSince = s.getLong("since", 0L)
        loaded = true
    }

    fun setFreq(c: Context, v: BackupFreq) { ensure(c); freq = v; scheduledSince = if (v == BackupFreq.OFF) 0L else System.currentTimeMillis(); sp(c).edit().putString("freq", v.key).putLong("since", scheduledSince).apply(); BackupScheduler.apply(c) }
    fun setWifiOnly(c: Context, v: Boolean) { ensure(c); wifiOnly = v; sp(c).edit().putBoolean("wifi", v).apply(); BackupScheduler.apply(c) }
    fun setCharging(c: Context, v: Boolean) { ensure(c); charging = v; sp(c).edit().putBoolean("charge", v).apply(); BackupScheduler.apply(c) }
    fun setIncludeMedia(c: Context, v: Boolean) { ensure(c); includeMedia = v; sp(c).edit().putBoolean("media", v).apply() }
    fun setKeepSets(c: Context, v: Int) { ensure(c); keepSets = v.coerceIn(1, 5); sp(c).edit().putInt("keep", keepSets).apply() }
    fun setCloud(c: Context, v: Boolean) { ensure(c); cloudOn = v; sp(c).edit().putBoolean("cloud", v).apply() }
    fun recordResult(c: Context, ok: Boolean, message: String) {
        ensure(c)
        if (ok) { lastSuccess = System.currentTimeMillis(); sp(c).edit().putLong("last", lastSuccess).apply() }
        lastMessage = message; sp(c).edit().putString("lastMsg", message).apply()
    }

    // ---- passphrase. The key is derived once (PBKDF2, 600 000 rounds) and kept wrapped by this phone's hardware key, so automatic backups can encrypt without asking.
    // The passphrase itself is never stored. It is independent of the account key-backup passphrase.
    suspend fun setPassphrase(c: Context, pass: String) = withContext(Dispatchers.Default) {
        require(pass.length >= 8) { "Use at least 8 characters." }
        val k = BackupKey.derive(pass)
        sp(c).edit().putBoolean("enc", true).putString("salt", BackupFormat.b64(k.salt)).putInt("iter", k.iter).putString("wk", Crypto.wrapForDevice(k.key)).commit()
        encrypted = true
    }
    fun clearPassphrase(c: Context) { ensure(c); encrypted = false; sp(c).edit().putBoolean("enc", false).remove("salt").remove("iter").remove("wk").apply() }
    /** The key for new backups, or null when the phone cannot unlock it (then the person must set the passphrase again). */
    fun key(c: Context): BackupKey? {
        val s = sp(c); val w = s.getString("wk", null) ?: return null
        return try { BackupKey(Crypto.unwrapForDevice(w), BackupFormat.unb64(s.getString("salt", "") ?: ""), s.getInt("iter", BackupFormat.ITER)) } catch (e: Exception) { null }
    }

    fun local(c: Context): List<BackupFiles.Local> = BackupFiles.list(dir(c))
    fun deleteAll(c: Context) { BackupFiles.list(dir(c)).forEach { BackupFiles.delete(it) }; work(c).deleteRecursively() }

    /** Copies a backup file to a place the person chose (Storage Access Framework), without any storage permission. */
    fun copyTo(c: Context, file: File, dest: android.net.Uri) {
        c.contentResolver.openOutputStream(dest, "w")?.use { o -> file.inputStream().use { it.copyTo(o) } } ?: throw java.io.IOException("Could not open the place you chose.")
    }
}

/** Reads the real data: chats and messages from Firebase (decrypted on this phone), small settings and plain media from this phone. */
class RepoBackupSource(private val ctx: Context) : BackupSource {
    private var chatLabels = HashMap<String, List<String>>()

    override suspend fun chats(): List<ChatRef> {
        val me = Repo.uid
        val snap = Repo.db.getReference("user_chats/$me").get().await()
        val out = ArrayList<ChatRef>(); val labels = HashMap<String, List<String>>()
        for (c in snap.children) {
            val id = c.key ?: continue
            val type = c.child("type").getValue(String::class.java) ?: ""
            val peer = c.child("peer").getValue(String::class.java) ?: ""
            val ref = when (type) {
                "group" -> ChatRef(id, Kind.GROUP)
                "channel" -> continue                           // public channel posts are not private history
                else -> if (peer.isEmpty()) continue else ChatRef(id, Kind.DM, peer)
            }
            if (ChatLock.isLocked(id)) continue                 // locked chats are never part of a backup
            out.add(ref)
            val l = c.child("labels").children.mapNotNull { it.key }
            if (l.isNotEmpty()) labels[id] = l
        }
        chatLabels = labels
        return out
    }

    override suspend fun page(ref: ChatRef, afterKey: String?, limit: Int): List<Msg> {
        var q = Repo.db.getReference("messages/${ref.id}").orderByKey()
        if (afterKey != null) q = q.startAfter(afterKey)
        val snap = q.limitToFirst(limit).get().await()
        val raws = snap.children.map { Repo.rawOf(it) }
        return withContext(Dispatchers.Default) { Repo.decryptAll(ref, raws) }
    }

    override fun settings(): JSONObject {
        val j = JSONObject().put("v", 1)
        val notif = JSONObject()
        ctx.getSharedPreferences("wt_chat_notif", Context.MODE_PRIVATE).all.forEach { (k, v) ->
            val cid = k.substringAfter('_', ""); if (cid.isEmpty() || ChatLock.isLocked(cid)) return@forEach
            if (k.startsWith("mu_") && v is Long && v <= System.currentTimeMillis()) return@forEach       // a mute that has ended is not worth keeping
            notif.put(k, v)
        }
        j.put("chatNotif", notif)
        val labels = JSONArray(); for (l in Repo.labels.value) labels.put(JSONObject().put("id", l.id).put("name", l.name).put("color", l.color))
        j.put("labels", labels)
        val cl = JSONObject(); chatLabels.forEach { (cid, l) -> cl.put(cid, JSONArray(l)) }
        j.put("chatLabels", cl)
        BackupExtras.starredIds?.let { f -> try { j.put("starred", JSONArray(f())) } catch (_: Exception) {} }
        return j
    }

    override fun mediaCandidates(): List<String> = DataPrefs.plainIds()
    override fun mediaFile(id: String): File? = DataPrefs.plainFile(id)
}

/** Puts restored settings back. It only adds: values already set on this phone are never overwritten, and nothing about other people is written. */
object BackupSettingsRestore {
    suspend fun apply(ctx: Context, j: JSONObject?): String {
        if (j == null) return ""
        val done = ArrayList<String>()
        j.optJSONObject("chatNotif")?.let { n ->
            val sp = ctx.getSharedPreferences("wt_chat_notif", Context.MODE_PRIVATE); val e = sp.edit(); var c = 0
            n.keys().forEach { k -> if (!sp.contains(k) && !ChatLock.isLocked(k.substringAfter('_', ""))) { c++; when (val v = n.get(k)) { is Boolean -> e.putBoolean(k, v); is Number -> e.putLong(k, v.toLong()); else -> c-- } } }
            e.apply(); if (c > 0) done.add("notification settings for $c chats")
        }
        try {
            val lab = j.optJSONArray("labels")
            if (lab != null && lab.length() > 0 && Repo.uid.isNotEmpty() && Repo.labels.value.isEmpty()) {
                val map = HashMap<String, String>()
                for (i in 0 until lab.length()) { val l = lab.getJSONObject(i); map[l.getString("id")] = Repo.createLabel(l.optString("name"), l.optInt("color")) }
                val cl = j.optJSONObject("chatLabels")
                cl?.keys()?.forEach { cid -> val a = cl.getJSONArray(cid); for (i in 0 until a.length()) map[a.getString(i)]?.let { nid -> try { Repo.setChatLabel(cid, nid, true) } catch (_: Exception) {} } }
                done.add("${map.size} labels")
            }
        } catch (e: Exception) { Repo.explain(e, "Restoring labels") }
        j.optJSONArray("starred")?.let { a -> val ids = (0 until a.length()).map { a.getString(it) }; val f = BackupExtras.restoreStarred; if (f != null && ids.isNotEmpty()) { f(ids); done.add("${ids.size} starred messages") } }
        return done.joinToString(", ")
    }
}

/** One backup run (used by the background worker and by the screen). Only one runs at a time. */
object BackupRunner {
    class Result(val ok: Boolean, val message: String, val retry: Boolean = false)

    suspend fun run(ctx: Context, forceFull: Boolean = false): Result {
        val app = ctx.applicationContext
        BackupStore.ensure(app)
        if (Repo.uid.isEmpty()) return Result(false, "Sign in to WeTalks to back up your chats.")
        if (!BackupStore.runLock.tryLock()) return Result(false, "A backup is already running.")
        BackupStore.cancelRequested = false
        try {
            val key = if (BackupStore.encrypted) (BackupStore.key(app) ?: return Result(false, "Set your backup passphrase again (this phone could not unlock it).")) else null
            val version = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
            val engine = BackupEngine(RepoBackupSource(app), BackupStore.dir(app), BackupStore.work(app), BackupFormat.accountTag(Repo.uid), version)
            val o = BackupEngine.Options(key, BackupStore.includeMedia, forceFull, BackupStore.keepSets)
            val out = withContext(Dispatchers.IO) { engine.run(o, { BackupStore.progress.value = it }, { BackupStore.cancelRequested }) }
            val r = when (out) {
                is BackupEngine.Outcome.Done -> Result(true, "Backed up ${out.messages} messages" + (if (out.manifest.media.isNotEmpty()) " and ${out.manifest.media.size} photos and voice notes" else "") + ".")
                BackupEngine.Outcome.NothingNew -> Result(true, "Nothing new to back up.")
                BackupEngine.Outcome.Cancelled -> Result(false, "Backup stopped. It will carry on where it stopped next time.")
                is BackupEngine.Outcome.Failed -> Result(false, out.message, retry = !out.diskFull)
            }
            BackupStore.recordResult(app, r.ok, r.message)
            if (r.ok && BackupStore.cloudOn) {
                val c = CloudSync.upload(app)
                if (c.isNotEmpty()) BackupStore.recordResult(app, true, r.message + " " + c)
            }
            return r
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            val m = Repo.explain(e, "Backup")
            BackupStore.recordResult(app, false, m); return Result(false, m, retry = true)
        } finally { BackupStore.progress.value = null; BackupStore.runLock.unlock() }
    }
}
