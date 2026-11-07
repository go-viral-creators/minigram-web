package com.kafisir.wetalks.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Starred messages. Only a reference is stored: starred/<uid>/<chatId>/<messageId> = server time of the star.
 * The message itself stays in the chat (encrypted) and is read and decrypted on the phone when the list is opened.
 * The node is private to its owner, so it follows the person to their other phones.
 */
object Starred {
    class Entry(val cid: String, val mid: String, val ts: Long)

    /** Newest star first. */
    var entries by mutableStateOf<List<Entry>>(emptyList()); private set
    private var ids by mutableStateOf<Set<String>>(emptySet())
    private var listening: Pair<String, Pair<com.google.firebase.database.DatabaseReference, ValueEventListener>>? = null

    /** True when this message is starred. Message ids are push ids, unique across chats. Reading it in a composable updates the screen on change. */
    fun isStarred(mid: String) = mid in ids

    fun count(chatId: String? = null) = if (chatId == null) entries.size else entries.count { it.cid == chatId }

    fun watch(attempt: Int = 0) {
        val u = Repo.uid
        if (u.isEmpty() || listening?.first == u) return
        stop()
        val r = Repo.db.getReference("starred/$u")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = ArrayList<Entry>()
                for (c in s.children) { val cid = c.key ?: continue; for (m in c.children) { val mid = m.key ?: continue; list.add(Entry(cid, mid, m.getValue(Long::class.java) ?: 0L)) } }
                list.sortByDescending { it.ts }
                entries = list; ids = list.mapTo(HashSet()) { it.mid }
            }
            override fun onCancelled(e: DatabaseError) {
                try { r.removeEventListener(this) } catch (_: Exception) {}
                if (listening?.first == u) listening = null           // the server never revives a refused listener: attach again later
                Repo.retryAfterCancel(attempt) { n -> watch(n) }
            }
        }
        r.addValueEventListener(l)
        listening = u to (r to l)
    }

    fun stop() {
        listening?.second?.let { (r, l) -> try { r.removeEventListener(l) } catch (_: Exception) {} }
        listening = null; entries = emptyList(); ids = emptySet()
    }

    /** Stars or unstars. The change is seen at once (the database tells this phone about its own writes straight away). Failures show the real reason. */
    fun toggle(cid: String, mid: String) {
        val on = !isStarred(mid)
        set(cid, mid, on)
    }

    fun set(cid: String, mid: String, on: Boolean) {
        val u = Repo.uid
        if (u.isEmpty() || cid.isEmpty() || mid.isEmpty()) return
        watch()
        Repo.scope.launch {
            try {
                val r = Repo.db.getReference("starred/$u/$cid/$mid")
                if (on) r.setValue(ServerValue.TIMESTAMP).await() else r.removeValue().await()
                com.kafisir.wetalks.ui.Nav.toast.value = if (on) "Message starred" else "Star removed"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { com.kafisir.wetalks.ui.Nav.toast.value = Repo.explain(e, if (on) "Starring the message" else "Removing the star") }
        }
    }

    /** Removes stars quietly (the message no longer exists). */
    fun drop(list: List<Entry>) {
        val u = Repo.uid
        if (u.isEmpty() || list.isEmpty()) return
        val up = HashMap<String, Any?>()
        for (e in list) up["starred/$u/${e.cid}/${e.mid}"] = null
        Repo.scope.launch { try { Repo.db.reference.updateChildren(up).await() } catch (_: Exception) {} }
    }

    /** What the Starred screen needs about one star. */
    sealed interface Loaded {
        class Ok(val msg: Msg) : Loaded
        /** The message was deleted. */
        data object Gone : Loaded
        /** Could not be read right now (offline, no access); the star is kept. */
        data object Failed : Loaded
    }

    suspend fun load(ref: ChatRef, mid: String): Loaded {
        val m = Repo.messageOnce(ref, mid)
        if (m != null) return if (m.deleted) Loaded.Gone else Loaded.Ok(m)
        // messageOnce is null both for "deleted" and "could not read": ask again to tell them apart
        return try {
            val s = Repo.db.getReference("messages/${ref.id}/$mid").get().await()
            if (s.exists()) Loaded.Failed else Loaded.Gone
        } catch (_: Exception) { Loaded.Failed }
    }
}
