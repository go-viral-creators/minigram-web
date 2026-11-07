package com.kafisir.wetalks.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** A problem the person can fix or just has to know about (shown as it is, not run through Repo.explain). */
class ReportException(message: String) : Exception(message)

/** One report as the group's/channel's admins see it. The reporter's own copy stays private. */
data class AdminReport(
    val rid: String, val by: String, val kind: String, val reason: String, val note: String,
    val tid: String, val mid: String, val snap: String, val st: String, val ts: Long,
)

/** A ban entry (who was banned, by whom, when). */
data class BanEntry(val uid: String, val by: String, val ts: Long)

/**
 * Reports to WeTalks and moderation of groups and channels. Server nodes (see database.rules.json):
 * `reports/<me>/<targetKey>` (write-only for the app, one per person per target), `report_quota/<me>` (daily counter),
 * `group_reports/<chatId>/<me>~<targetKey>` (copy for that chat's admins), `group_bans/<gid>/<uid>`, `channel_bans/<cid>/<uid>`.
 */
object Reports {
    private val db get() = Repo.db
    private val me get() = Repo.uid

    /** True when this person already reported the target (they can read their own report). */
    suspend fun alreadyReported(t: ReportTarget): Boolean = try {
        db.getReference("reports/$me/${ReportLogic.targetKey(t, me)}").get().await().exists()
    } catch (_: Exception) { false }

    /**
     * Sends the report in ONE atomic update: the private report, the daily counter and (for groups/channels) the admins' copy.
     * Nothing is read from the chat except what [t] already carries. Throws [ReportException] for problems found before sending.
     */
    suspend fun submit(t: ReportTarget, reason: ReportReason, note: String, alsoBlock: Boolean = false) {
        ReportLogic.problem(t, me)?.let { throw ReportException(it) }
        val tk = ReportLogic.targetKey(t, me)
        if (alreadyReported(t)) throw ReportException("You already reported this. We will look at it.")
        val q = db.getReference("report_quota/$me").get().await()
        val next = ReportLogic.nextQuota(q.child("d").getValue(Long::class.java), q.child("n").getValue(Long::class.java), System.currentTimeMillis())
            ?: throw ReportException("You sent ${ReportLogic.DAILY_MAX} reports today. Please try again tomorrow.")
        val body = HashMap<String, Any?>()
        body["by"] = me; body["ts"] = ServerValue.TIMESTAMP; body["kind"] = ReportLogic.kindOf(t); body["r"] = reason.key; body["q"] = next.second
        ReportLogic.cleanNote(note).takeIf { it.isNotEmpty() }?.let { body["note"] = it }
        ReportLogic.reportedPerson(t)?.let { body["tid"] = it }
        when (t) {
            is ReportTarget.User -> t.context?.takeIf { it.kind != Kind.DM }?.let { body["cid"] = it.id }
            is ReportTarget.Message -> {
                body["cid"] = t.ref.id; body["mid"] = t.mid; body["st"] = t.type.take(24); body["sts"] = t.ts
                if (t.snapshot.isNotEmpty()) body["snap"] = t.snapshot.take(ReportLogic.SNAP_MAX)
            }
            is ReportTarget.Chat -> body["cid"] = ReportLogic.dmId(me, t.peer)
            is ReportTarget.Group -> body["cid"] = t.id
            is ReportTarget.Channel -> body["cid"] = t.id
        }
        val up = HashMap<String, Any?>()
        up["reports/$me/$tk"] = body
        up["report_quota/$me"] = mapOf("d" to next.first, "n" to next.second)
        ReportLogic.adminCopyChat(t)?.let { cid ->
            val copy = HashMap<String, Any?>()
            copy["by"] = me; copy["ts"] = ServerValue.TIMESTAMP; copy["kind"] = body["kind"]; copy["r"] = reason.key; copy["tk"] = tk
            for (k in listOf("note", "tid", "mid", "snap", "st", "sts")) body[k]?.let { copy[k] = it }
            up["group_reports/$cid/$me~$tk"] = copy
        }
        db.reference.updateChildren(up).await()
        if (alsoBlock) ReportLogic.reportedPerson(t)?.let { Repo.block(it, true) }
    }

    // ------------------------------------------------------------------------------------------ admin side
    /** Live list of the reports for one group/channel (admins only), newest first. */
    fun adminReports(cid: String): Flow<List<AdminReport>> = callbackFlow {
        val r = db.getReference("group_reports/$cid")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                trySend(s.children.mapNotNull { c ->
                    val k = c.key ?: return@mapNotNull null
                    AdminReport(k, c.s("by"), c.s("kind"), c.s("r"), c.s("note"), c.s("tid"), c.s("mid"), c.s("snap"), c.s("st"), c.child("ts").getValue(Long::class.java) ?: 0L)
                }.sortedByDescending { it.ts })
            }
            override fun onCancelled(e: DatabaseError) { trySend(emptyList()) }
        }
        r.addValueEventListener(l)
        awaitClose { r.removeEventListener(l) }
    }

    /** Admin closes a report (it was handled or is not a problem). */
    suspend fun dismiss(cid: String, rid: String) { db.getReference("group_reports/$cid/$rid").removeValue().await() }

    private fun DataSnapshot.s(k: String) = child(k).getValue(String::class.java) ?: ""

    // ------------------------------------------------------------------------------------------ bans
    private fun banNode(kind: Kind, id: String) = (if (kind == Kind.GROUP) "group_bans/" else "channel_bans/") + id

    suspend fun bans(kind: Kind, id: String): List<BanEntry> =
        db.getReference(banNode(kind, id)).get().await().children.mapNotNull { c ->
            val k = c.key ?: return@mapNotNull null
            BanEntry(k, c.s("by"), c.child("ts").getValue(Long::class.java) ?: 0L)
        }.sortedByDescending { it.ts }

    suspend fun isBanned(kind: Kind, id: String): Boolean = try { db.getReference("${banNode(kind, id)}/$me").get().await().exists() } catch (_: Exception) { false }

    private fun banBody() = mapOf("by" to me, "ts" to ServerValue.TIMESTAMP)

    /** Bans a group member and removes them (the group key is rotated by [Repo.removeMember]). They cannot be added or rejoin until unbanned. */
    suspend fun banFromGroup(gid: String, uid: String) {
        db.getReference("${banNode(Kind.GROUP, gid)}/$uid").setValue(banBody()).await()
        Repo.removeMember(gid, uid)
    }

    /** Bans a channel follower: they are removed now and cannot follow again (public join, invite link) until unbanned. */
    suspend fun banFromChannel(cid: String, uid: String) {
        val wasFollower = db.getReference("channel_subs/$cid/$uid").get().await().exists()
        val up = HashMap<String, Any?>()
        up["${banNode(Kind.CHANNEL, cid)}/$uid"] = banBody()
        if (wasFollower) { up["channel_subs/$cid/$uid"] = null; up["channels/$cid/subCount"] = ServerValue.increment(-1) }
        db.reference.updateChildren(up).await()
    }

    /** Removes a follower without a ban (they could follow again). */
    suspend fun removeFollower(cid: String, uid: String) {
        val up = HashMap<String, Any?>()
        up["channel_subs/$cid/$uid"] = null; up["channels/$cid/subCount"] = ServerValue.increment(-1)
        db.reference.updateChildren(up).await()
    }

    suspend fun unban(kind: Kind, id: String, uid: String) { db.getReference("${banNode(kind, id)}/$uid").removeValue().await() }

    /** Admin removes a reported message for everybody (same call as "Delete for everyone"). */
    suspend fun removeMessage(ref: ChatRef, mid: String) { Repo.deleteMessages(ref, listOf(mid)) }
}
