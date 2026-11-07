package com.kafisir.wetalks.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.kafisir.wetalks.MainActivity
import com.kafisir.wetalks.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** One warning for the owner/admins: a member's phone thought this message may be sensitive. */
data class NsfwAlert(val mid: String, val by: String, val ts: Long, val bucket: Int)

/**
 * The database side of sensitive-image protection (groups and channels only):
 *  - `moderation/<cid>/<mid>/<scanner>`  = {s, by, ts}   the scanner's mark (create-only; admins read; the scanner reads his own)
 *  - `admin_alerts/<cid>/<mid>`          = {kind, mid, by, ts, s}   the warning with the message reference (admins read and delete)
 *  - `admin_alert_quota/<cid>/<me>`      = {h, n, m}   5 alerts per person per chat per hour, written in the SAME update as the alert
 *  - `moderation_ok/<cid>/<mid>`         = true   an admin said "not sensitive": nobody flags or blurs it again
 * A failed flag is silent on purpose (normal when someone flagged it first, or when the server rules are older): scanning and blurring on
 * this phone work without it. It must never go through Repo.explain, which would claim "rules outdated" for a harmless refusal.
 */
object NsfwAlerts {
    private val db get() = Repo.db
    private val me get() = Repo.uid
    private fun sp() = Repo.app.getSharedPreferences("wt_nsfw", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------------------------------ member side: flag
    private val tried = HashSet<String>()

    /** Tells the admins about one message. Returns true when the flag was stored. Groups and channels only; a DM is refused here too. */
    suspend fun report(ref: ChatRef, mid: String, bucket: Int): Boolean {
        if (!NsfwLogic.kindAllowed(ref.kind) || me.isEmpty() || mid.isEmpty()) return false
        val key = "${ref.id}/$mid"
        synchronized(tried) { if (!tried.add(key)) return false }
        if (sp().getBoolean("sent/$key", false)) return false
        return try {
            val q = db.getReference("admin_alert_quota/${ref.id}/$me").get().await()
            val next = NsfwLogic.nextQuota(q.child("h").getValue(Long::class.java), q.child("n").getValue(Long::class.java), System.currentTimeMillis()) ?: return false
            val up = HashMap<String, Any?>()
            up["admin_alerts/${ref.id}/$mid"] = mapOf("kind" to "nsfw", "mid" to mid, "by" to me, "ts" to ServerValue.TIMESTAMP, "s" to bucket)
            up["admin_alert_quota/${ref.id}/$me"] = mapOf("h" to next.first, "n" to next.second, "m" to mid)
            up["moderation/${ref.id}/$mid/$me"] = mapOf("s" to bucket, "by" to me, "ts" to ServerValue.TIMESTAMP)
            db.reference.updateChildren(up).await()
            sp().edit().putBoolean("sent/$key", true).apply()
            true
        } catch (e: Exception) { android.util.Log.i("WeTalks", "sensitive-image flag not stored: " + (e.message ?: e.javaClass.simpleName)); false }
    }

    private val clearedYes = HashSet<String>()
    /** True when an admin already said this message is fine (the flag and the blur are then skipped). */
    suspend fun cleared(cid: String, mid: String): Boolean {
        val k = "$cid/$mid"
        if (synchronized(clearedYes) { k in clearedYes }) return true
        val yes = try { db.getReference("moderation_ok/$cid/$mid").get().await().exists() } catch (_: Exception) { false }
        if (yes) synchronized(clearedYes) { clearedYes.add(k) }
        return yes
    }

    // ------------------------------------------------------------------------------------------ admin side
    fun alerts(cid: String): Flow<List<NsfwAlert>> = callbackFlow {
        val r = db.getReference("admin_alerts/$cid")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                trySend(s.children.mapNotNull { c ->
                    val k = c.key ?: return@mapNotNull null
                    NsfwAlert(k, c.child("by").getValue(String::class.java) ?: "", c.child("ts").getValue(Long::class.java) ?: 0L, (c.child("s").getValue(Long::class.java) ?: 0L).toInt())
                }.sortedByDescending { it.ts })
            }
            override fun onCancelled(e: DatabaseError) { trySend(emptyList()) }
        }
        r.addValueEventListener(l)
        awaitClose { r.removeEventListener(l) }
    }

    /** Message ids that somebody flagged, for admins' phones (they can read the marks): such a message is blurred by default. */
    private val markFlows = HashMap<String, MutableStateFlow<Set<String>>>()
    private val markRefs = ArrayList<Pair<com.google.firebase.database.DatabaseReference, ValueEventListener>>()
    fun marked(cid: String): StateFlow<Set<String>> = synchronized(markFlows) {
        markFlows.getOrPut(cid) {
            MutableStateFlow<Set<String>>(emptySet()).also { f ->
                val r = db.getReference("moderation/$cid")
                val l = object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) { f.value = s.children.mapNotNull { it.key }.toSet() }
                    override fun onCancelled(e: DatabaseError) {}
                }
                r.addValueEventListener(l); markRefs.add(r to l)
            }
        }
    }

    fun forget() { synchronized(markFlows) { markRefs.forEach { (r, l) -> r.removeEventListener(l) }; markRefs.clear(); markFlows.clear() }; synchronized(clearedYes) { clearedYes.clear() } }

    suspend fun senderOf(cid: String, mid: String): String? = try { db.getReference("messages/$cid/$mid/from").get().await().getValue(String::class.java) } catch (_: Exception) { null }

    /** "Not sensitive": the alert and the marks go away and the message can never be flagged again. */
    suspend fun notSensitive(cid: String, mid: String) {
        val up = HashMap<String, Any?>()
        up["moderation_ok/$cid/$mid"] = true; up["admin_alerts/$cid/$mid"] = null; up["moderation/$cid/$mid"] = null
        db.reference.updateChildren(up).await()
        synchronized(clearedYes) { clearedYes.add("$cid/$mid") }
    }

    /** Deletes the message for everybody (same call as "Delete for everyone") and closes the alert. */
    suspend fun removeMessage(ref: ChatRef, mid: String) {
        Reports.removeMessage(ref, mid)
        close(ref.id, mid)
    }

    suspend fun close(cid: String, mid: String) {
        val up = HashMap<String, Any?>()
        up["admin_alerts/$cid/$mid"] = null; up["moderation/$cid/$mid"] = null
        db.reference.updateChildren(up).await()
    }

    // ------------------------------------------------------------------------------------------ unread count (this phone's "seen" time)
    private val seenTick = MutableStateFlow(0)
    fun seenAt(cid: String) = try { sp().getLong("seen/$cid", 0L) } catch (_: Exception) { 0L }
    fun markSeen(cid: String, upTo: Long) { try { sp().edit().putLong("seen/$cid", upTo).apply() } catch (_: Exception) {}; seenTick.value++ }

    /** Number of alerts this admin has not looked at yet. [staff] false gives a constant 0 and starts no listener. */
    fun unread(cid: String, staff: Boolean): Flow<Int> =
        if (!staff) flowOf(0) else combine(alerts(cid), seenTick) { l, _ -> val seen = seenAt(cid); l.count { it.ts > seen } }

    // ------------------------------------------------------------------------------------------ notification "A message in <group> was flagged"
    private var job: Job? = null
    private lateinit var app: Context
    private val watching = HashSet<String>()

    fun start(c: Context) {
        app = c.applicationContext
        if (job?.isActive == true || me.isEmpty()) return
        job = Repo.scope.launch {
            Repo.chats().collect { list ->
                for (ci in list) {
                    if (!NsfwLogic.kindAllowed(ci.ref.kind)) continue
                    if (!synchronized(watching) { watching.add(ci.ref.id) }) continue
                    launch { watchOne(ci.ref) }
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null; synchronized(watching) { watching.clear() } }

    private suspend fun isStaff(ref: ChatRef): Boolean = if (ref.kind == Kind.GROUP) {
        withTimeoutOrNull(6000) { Repo.group(ref.id).first { it != null } }?.members?.get(me) == "admin"
    } else {
        withTimeoutOrNull(6000) { Repo.channel(ref.id).first { it != null } }?.let { it.owner == me || me in it.admins } == true
    }

    private suspend fun watchOne(ref: ChatRef) {
        if (!isStaff(ref)) { synchronized(watching) { watching.remove(ref.id) }; return }
        var known: Set<String>? = null
        alerts(ref.id).collect { list ->
            val ids = list.map { it.mid }.toSet()
            val base = known; known = ids
            if (base == null) return@collect                               // first answer: everything in it is old news
            val fresh = list.filter { it.mid !in base && it.by != me }
            if (fresh.isNotEmpty()) try { notify(ref, fresh.size) } catch (_: Exception) {}
        }
    }

    private fun notify(ref: ChatRef, n: Int) {
        if (!::app.isInitialized || !Notifier.enabled(app)) return
        NotifChannels.ensureAll(app)
        val name = (if (ref.kind == Kind.GROUP) Repo.group(ref.id).value?.name else Repo.channel(ref.id).value?.name)?.ifEmpty { null } ?: "a chat"
        val text = if (n == 1) "A message in $name was flagged" else "$n messages in $name were flagged"
        val open = PendingIntent.getActivity(app, ("nsfw" + ref.id).hashCode(),
            Intent(app, MainActivity::class.java).putExtra("cid", ref.id).putExtra("kind", ref.kind.ordinal).putExtra("peer", "")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).setAction("wt.open." + ref.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(app, NotifChannels.GROUPS).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Sensitive image flagged").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text. Open the group or channel, then Moderation > Flagged."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true).setContentIntent(open).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        try { NotificationManagerCompat.from(app).notify(("nsfw" + ref.id).hashCode(), b.build()) } catch (_: SecurityException) {}
    }
}
