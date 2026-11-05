package com.kafisir.wetalks.data

import android.app.Activity
import android.content.Context
import com.google.firebase.FirebaseException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

private const val DB_URL = "https://wetalks-78e5a-default-rtdb.firebaseio.com"

private fun DataSnapshot.str(k: String) = child(k).getValue(String::class.java) ?: ""
private fun DataSnapshot.lng(k: String) = child(k).getValue(Long::class.java) ?: 0L
private fun DataSnapshot.bool(k: String, d: Boolean = false) = child(k).getValue(Boolean::class.java) ?: d

/** All Firebase access lives here: auth, profiles, chats, groups, channels, media and the E2EE glue. */
object Repo {
    lateinit var app: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    lateinit var db: FirebaseDatabase

    val uid: String get() = auth.currentUser?.uid ?: ""

    fun init(c: Context) {
        app = c.applicationContext
        Crypto.init()
        db = FirebaseDatabase.getInstance(DB_URL)
        try { db.setPersistenceEnabled(true) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------------------------------ auth
    fun authState(): Flow<String?> = callbackFlow {
        val l = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(l)
        awaitClose { auth.removeAuthStateListener(l) }
    }

    suspend fun signInEmail(email: String, pass: String) { auth.signInWithEmailAndPassword(email.trim(), pass).await() }
    suspend fun signUpEmail(email: String, pass: String) { auth.createUserWithEmailAndPassword(email.trim(), pass).await() }
    suspend fun resetPassword(email: String) { auth.sendPasswordResetEmail(email.trim()).await() }
    suspend fun signInWith(cred: AuthCredential) { auth.signInWithCredential(cred).await() }
    suspend fun signInGoogle(idToken: String) = signInWith(GoogleAuthProvider.getCredential(idToken, null))
    suspend fun signInPhoneCode(verificationId: String, code: String) = signInWith(PhoneAuthProvider.getCredential(verificationId, code))

    fun startPhone(activity: Activity, e164: String, onCode: (String) -> Unit, onAuto: (PhoneAuthCredential) -> Unit, onError: (String) -> Unit) {
        val opts = PhoneAuthOptions.newBuilder(auth).setPhoneNumber(e164).setTimeout(60L, TimeUnit.SECONDS).setActivity(activity)
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(c: PhoneAuthCredential) = onAuto(c)
                override fun onVerificationFailed(e: FirebaseException) = onError(e.message ?: "Verification failed")
                override fun onCodeSent(id: String, t: PhoneAuthProvider.ForceResendingToken) = onCode(id)
            }).build()
        PhoneAuthProvider.verifyPhoneNumber(opts)
    }

    fun signOut() {
        setOnline(false)
        detachAll()
        auth.signOut()
    }

    suspend fun deleteAccount() {
        val me = uid
        val u = userOnce(me)
        if (u != null && u.username.isNotEmpty()) db.getReference("usernames/${u.username.lowercase()}").removeValue().await()
        db.getReference("users/$me").removeValue().await()
        db.getReference("user_chats/$me").removeValue().await()
        auth.currentUser?.delete()?.await()
        Crypto.clear(app)
    }

    // ------------------------------------------------------------------------------------------ live caches
    private val listeners = ArrayList<Pair<com.google.firebase.database.DatabaseReference, ValueEventListener>>()
    private fun detachAll() { listeners.forEach { it.first.removeEventListener(it.second) }; listeners.clear(); userFlows.clear(); groupFlows.clear(); channelFlows.clear() }

    private val userFlows = HashMap<String, MutableStateFlow<User?>>()
    private val groupFlows = HashMap<String, MutableStateFlow<Group?>>()
    private val channelFlows = HashMap<String, MutableStateFlow<Channel?>>()

    private fun parseUser(id: String, s: DataSnapshot) = if (!s.exists()) null else User(
        id, s.str("name"), s.str("username"), s.str("about"), s.str("photo"), s.str("pk"), s.bool("online"), s.lng("lastSeen"),
        s.bool("showLastSeen", true), s.bool("readReceipts", true))

    private fun parseGroup(id: String, s: DataSnapshot) = if (!s.exists()) null else Group(
        id, s.str("name"), s.str("about"), s.str("photo"), s.str("owner"), (s.lng("kv").takeIf { it > 0 } ?: 1L).toInt(),
        s.child("members").children.associate { it.key!! to (it.getValue(String::class.java) ?: "member") })

    private fun parseChannel(id: String, s: DataSnapshot) = if (!s.exists()) null else Channel(
        id, s.str("name"), s.str("handle"), s.str("about"), s.str("photo"), s.str("owner"),
        s.child("admins").children.mapNotNull { it.key }.toSet(), s.lng("subCount"), s.lng("lastTs"), s.str("lastPreview"))

    fun user(id: String): StateFlow<User?> = userFlows.getOrPut(id) {
        MutableStateFlow<User?>(null).also { f -> watch("users/$id") { f.value = parseUser(id, it) } }
    }
    fun group(id: String): StateFlow<Group?> = groupFlows.getOrPut(id) {
        MutableStateFlow<Group?>(null).also { f -> watch("groups/$id") { f.value = parseGroup(id, it) } }
    }
    fun channel(id: String): StateFlow<Channel?> = channelFlows.getOrPut(id) {
        MutableStateFlow<Channel?>(null).also { f -> watch("channels/$id") { f.value = parseChannel(id, it) } }
    }

    private fun watch(path: String, cb: (DataSnapshot) -> Unit) {
        val ref = db.getReference(path)
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) = cb(s)
            override fun onCancelled(e: DatabaseError) {}
        }
        ref.addValueEventListener(l)
        listeners.add(ref to l)
    }

    suspend fun userOnce(id: String): User? = parseUser(id, db.getReference("users/$id").get().await())

    // ------------------------------------------------------------------------------------------ profile
    fun setOnline(on: Boolean) {
        if (uid.isEmpty()) return
        val r = db.getReference("users/$uid")
        if (on) {
            r.child("online").setValue(true)
            r.child("online").onDisconnect().setValue(false)
            r.child("lastSeen").onDisconnect().setValue(ServerValue.TIMESTAMP)
        } else {
            r.child("online").setValue(false)
            r.child("lastSeen").setValue(ServerValue.TIMESTAMP)
        }
    }

    suspend fun usernameFree(lower: String): Boolean {
        val s = db.getReference("usernames/$lower").get().await()
        return !s.exists() || s.getValue(String::class.java) == uid
    }

    /** Creates or updates the profile. Returns an error message or null on success. */
    suspend fun saveProfile(name: String, username: String, about: String, photoB64: String?): String? {
        val me = uid
        val lower = username.lowercase()
        val old = userOnce(me)
        try {
            if (old == null || old.username.lowercase() != lower) {
                db.getReference("usernames/$lower").setValue(me).await()           // fails if somebody owns it
                if (old != null && old.username.isNotEmpty()) db.getReference("usernames/${old.username.lowercase()}").removeValue()
            }
        } catch (e: Exception) { return "That username is taken" }
        val m = HashMap<String, Any?>()
        m["name"] = name.trim(); m["nameLower"] = name.trim().lowercase()
        m["username"] = username; m["usernameLower"] = lower
        m["about"] = about.trim()
        m["pk"] = Crypto.b64(Crypto.keys(app).pub)
        if (photoB64 != null) m["photo"] = photoB64
        if (old == null) m["createdAt"] = ServerValue.TIMESTAMP
        return try { db.getReference("users/$me").updateChildren(m).await(); null } catch (e: Exception) { e.message ?: "Could not save" }
    }

    /** Called after every sign-in so a reinstalled phone publishes its new key. */
    suspend fun publishKey() {
        if (uid.isEmpty()) return
        val exists = db.getReference("users/$uid/name").get().await().exists()
        if (exists) db.getReference("users/$uid/pk").setValue(Crypto.b64(Crypto.keys(app).pub)).await()
        if (exists) auth.currentUser?.phoneNumber?.takeIf { it.isNotEmpty() }?.let { registerPhone(it) }
    }

    fun setPrivacy(key: String, v: Boolean) { db.getReference("users/$uid/$key").setValue(v) }

    suspend fun searchUsers(q: String): List<User> {
        val t = q.trim().lowercase().removePrefix("@")
        if (t.length < 2) return emptyList()
        val a = db.getReference("users").orderByChild("usernameLower").startAt(t).endAt(t + "").limitToFirst(20).get().await()
        val b = db.getReference("users").orderByChild("nameLower").startAt(t).endAt(t + "").limitToFirst(20).get().await()
        val out = LinkedHashMap<String, User>()
        for (s in a.children + b.children) { val u = parseUser(s.key!!, s); if (u != null && u.uid != uid) out[u.uid] = u }
        return out.values.toList()
    }

    // blocking is local-first: blocked senders are filtered on this phone
    private val blocked = MutableStateFlow<Set<String>>(emptySet())
    val blockedUsers: StateFlow<Set<String>> get() = blocked
    fun watchBlocks() = watch("blocks/$uid") { s -> blocked.value = s.children.mapNotNull { it.key }.toSet() }
    fun block(id: String, on: Boolean) { val r = db.getReference("blocks/$uid/$id"); if (on) r.setValue(true) else r.removeValue() }

    // ------------------------------------------------------------------------------------------ chat list
    fun chats(): Flow<List<ChatItem>> = callbackFlow {
        val ref = db.getReference("user_chats/$uid")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = s.children.mapNotNull { c ->
                    val type = c.str("type")
                    val kind = when (type) { "group" -> Kind.GROUP; "channel" -> Kind.CHANNEL; else -> Kind.DM }
                    val id = c.key ?: return@mapNotNull null
                    ChatItem(ChatRef(id, kind, c.str("peer").ifEmpty { null }), c.lng("lastTs"), c.str("lastBody"), c.str("lastType"),
                        c.str("lastFrom"), c.lng("unread"), c.bool("pinned"), c.bool("muted"))
                }
                trySend(list.sortedWith(compareByDescending<ChatItem> { it.pinned }.thenByDescending { it.lastTs }))
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        ref.addValueEventListener(l)
        awaitClose { ref.removeEventListener(l) }
    }

    fun setPinned(id: String, v: Boolean) { db.getReference("user_chats/$uid/$id/pinned").setValue(v) }
    fun setMuted(id: String, v: Boolean) { db.getReference("user_chats/$uid/$id/muted").setValue(v) }
    fun removeChat(id: String) { db.getReference("user_chats/$uid/$id").removeValue() }

    fun dmId(peer: String) = listOf(uid, peer).sorted().joinToString("_")
    fun dmRef(peer: String) = ChatRef(dmId(peer), Kind.DM, peer)

    // ------------------------------------------------------------------------------------------ keys
    private val dmKeys = HashMap<String, Pair<String, ByteArray>>()
    private val groupKeys = HashMap<String, ByteArray>()

    private suspend fun dmKey(peer: String): ByteArray? {
        val pk = (user(peer).value?.pk?.takeIf { it.isNotEmpty() } ?: userOnce(peer)?.pk?.takeIf { it.isNotEmpty() }) ?: return null
        dmKeys[peer]?.let { if (it.first == pk) return it.second }
        val me = Crypto.keys(app)
        val k = Crypto.shared(me.priv, me.pub, Crypto.unb64(pk))
        dmKeys[peer] = pk to k
        return k
    }

    private suspend fun groupKey(gid: String, kv: Int): ByteArray? {
        val ck = "$gid:$kv"
        groupKeys[ck]?.let { return it }
        val s = db.getReference("group_keys/$gid/$kv/$uid").get().await()
        if (!s.exists()) return null
        val by = s.str("by"); val ct = s.str("ct")
        val byPk = userOnce(by)?.pk?.takeIf { it.isNotEmpty() } ?: return null
        val me = Crypto.keys(app)
        return try {
            val wk = Crypto.shared(me.priv, me.pub, Crypto.unb64(byPk))
            Crypto.open(wk, ct).also { groupKeys[ck] = it }
        } catch (e: Exception) { null }
    }

    suspend fun chatKey(ref: ChatRef, kv: Int = 0): ByteArray? = when (ref.kind) {
        Kind.DM -> dmKey(ref.peer!!)
        Kind.GROUP -> groupKey(ref.id, if (kv > 0) kv else (group(ref.id).value?.kv ?: groupVersion(ref.id)))
        Kind.CHANNEL -> null
    }

    private suspend fun groupVersion(gid: String): Int = (db.getReference("groups/$gid/kv").get().await().getValue(Long::class.java) ?: 1L).toInt()

    /** Safety number for a DM (same on both phones). */
    suspend fun safetyNumber(peer: String): String? {
        val pk = userOnce(peer)?.pk?.takeIf { it.isNotEmpty() } ?: return null
        return Crypto.safetyNumber(Crypto.keys(app).pub, Crypto.unb64(pk))
    }

    // ------------------------------------------------------------------------------------------ messages
    class Raw(val id: String, val from: String, val ts: Long, val type: String, val body: String, val kv: Int, val rid: String?,
              val deleted: Boolean, val edited: Boolean, val react: Map<String, String>)

    fun rawMessages(ref: ChatRef, limit: Int): Flow<List<Raw>> = callbackFlow {
        val q = db.getReference("messages/${ref.id}").orderByKey().limitToLast(limit)
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                trySend(s.children.map { c ->
                    Raw(c.key!!, c.str("from"), c.lng("ts"), c.str("type"), c.str("body"), c.lng("kv").toInt(), c.str("rid").ifEmpty { null },
                        c.bool("deleted"), c.bool("edited"),
                        c.child("react").children.associate { it.key!! to (it.getValue(String::class.java) ?: "") })
                })
            }
            override fun onCancelled(e: DatabaseError) { close() }
        }
        q.addValueEventListener(l)
        awaitClose { q.removeEventListener(l) }
    }

    suspend fun decrypt(ref: ChatRef, r: Raw): Msg {
        if (r.deleted) return Msg(r.id, r.from, r.ts, r.type, "", deleted = true, rid = r.rid)
        var plain: String
        var bad = false
        if (ref.kind == Kind.CHANNEL) plain = r.body
        else {
            val key = chatKey(ref, r.kv)
            plain = if (key == null) { bad = true; "" } else try { Crypto.openText(key, r.body) } catch (e: Exception) { bad = true; "" }
        }
        var mediaId: String? = null; var dur = 0; var text = plain; var wave: List<Int> = emptyList()
        if (!bad && (r.type == "image" || r.type == "voice")) {
            try {
                val j = org.json.JSONObject(plain); mediaId = j.optString("m"); dur = j.optInt("d"); text = j.optString("c")
                j.optJSONArray("w")?.let { a -> wave = (0 until a.length()).map { a.optInt(it) } }
            } catch (e: Exception) { bad = true }
        }
        return Msg(r.id, r.from, r.ts, r.type, text, mediaId, dur, r.rid, false, r.edited, r.react, bad, wave = wave)
    }

    private suspend fun fanOut(ref: ChatRef, updates: HashMap<String, Any?>, type: String, body: String, preview: String) {
        val now = ServerValue.TIMESTAMP
        fun entry(u: String, kind: String, peer: String?, mine: Boolean) {
            val b = "user_chats/$u/${ref.id}"
            updates["$b/type"] = kind
            if (peer != null) updates["$b/peer"] = peer
            updates["$b/lastTs"] = now; updates["$b/lastBody"] = body; updates["$b/lastType"] = type; updates["$b/lastFrom"] = uid
            updates["$b/unread"] = if (mine) 0 else ServerValue.increment(1)
        }
        when (ref.kind) {
            Kind.DM -> { entry(uid, "dm", ref.peer, true); if (ref.peer != uid) entry(ref.peer!!, "dm", uid, false) }
            Kind.GROUP -> {
                val members = group(ref.id).value?.members?.keys
                    ?: db.getReference("groups/${ref.id}/members").get().await().children.mapNotNull { it.key }
                for (m in members) entry(m, "group", null, m == uid)
            }
            Kind.CHANNEL -> { updates["channels/${ref.id}/lastTs"] = now; updates["channels/${ref.id}/lastPreview"] = preview }
        }
    }

    /** Encrypts (DM/group) and sends. [payload] is the plain text, or a small JSON for image/voice, or a sticker id. */
    suspend fun send(ref: ChatRef, type: String, payload: String, rid: String?, preview: String) {
        val kv = if (ref.kind == Kind.GROUP) (group(ref.id).value?.kv ?: groupVersion(ref.id)) else 0
        val key = chatKey(ref, kv)
        if (ref.kind != Kind.CHANNEL && key == null) throw IllegalStateException("Encryption key not available yet. The other person must open WeTalks once.")
        val body = if (key == null) payload else Crypto.sealText(key, payload)
        val mid = db.getReference("messages/${ref.id}").push().key!!
        val up = HashMap<String, Any?>()
        up["messages/${ref.id}/$mid"] = hashMapOf("from" to uid, "ts" to ServerValue.TIMESTAMP, "type" to type, "body" to body, "kv" to kv, "rid" to rid)
        fanOut(ref, up, type, body, preview)
        db.reference.updateChildren(up).await()
    }

    suspend fun editMessage(ref: ChatRef, id: String, newText: String) {
        val kv = if (ref.kind == Kind.GROUP) (group(ref.id).value?.kv ?: 1) else 0
        val key = chatKey(ref, kv)
        val body = if (key == null) newText else Crypto.sealText(key, newText)
        db.getReference("messages/${ref.id}/$id").updateChildren(mapOf("body" to body, "edited" to true)).await()
    }

    fun deleteForAll(ref: ChatRef, id: String) { db.getReference("messages/${ref.id}/$id").updateChildren(mapOf("deleted" to true, "body" to "")) }
    fun react(ref: ChatRef, id: String, emoji: String?) {
        val r = db.getReference("messages/${ref.id}/$id/react/$uid")
        if (emoji == null) r.removeValue() else r.setValue(emoji)
    }

    private val hiddenPrefs get() = app.getSharedPreferences("wt_hidden", Context.MODE_PRIVATE)
    fun hideForMe(id: String) { hiddenPrefs.edit().putBoolean(id, true).apply() }
    fun isHidden(id: String) = hiddenPrefs.getBoolean(id, false)

    fun markRead(ref: ChatRef) {
        if (user(uid).value?.readReceipts != false) db.getReference("reads/${ref.id}/$uid").setValue(ServerValue.TIMESTAMP)
        if (ref.kind != Kind.CHANNEL) db.getReference("user_chats/$uid/${ref.id}/unread").setValue(0)
    }

    fun readTs(ref: ChatRef, who: String): Flow<Long> = callbackFlow {
        val r = db.getReference("reads/${ref.id}/$who")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { trySend(s.getValue(Long::class.java) ?: 0L) }
            override fun onCancelled(e: DatabaseError) {}
        }
        r.addValueEventListener(l)
        awaitClose { r.removeEventListener(l) }
    }

    fun typing(ref: ChatRef): Flow<List<String>> = callbackFlow {
        val r = db.getReference("typing/${ref.id}")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val now = System.currentTimeMillis()
                trySend(s.children.filter { it.key != uid && (it.getValue(Long::class.java) ?: 0L) > now - 15000 }.mapNotNull { it.key })
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        r.addValueEventListener(l)
        awaitClose { r.removeEventListener(l) }
    }

    fun setTyping(ref: ChatRef, on: Boolean) {
        val r = db.getReference("typing/${ref.id}/$uid")
        if (on) { r.setValue(ServerValue.TIMESTAMP); r.onDisconnect().removeValue() } else r.removeValue()
    }

    // ------------------------------------------------------------------------------------------ media (kept in the database, encrypted)
    suspend fun uploadMedia(ref: ChatRef, bytes: ByteArray, mime: String): String {
        val kv = if (ref.kind == Kind.GROUP) (group(ref.id).value?.kv ?: 1) else 0
        val key = chatKey(ref, kv)
        val data = if (key == null) Crypto.b64(bytes) else Crypto.seal(key, bytes)
        val id = db.getReference("media").push().key!!
        db.getReference("media/$id").setValue(hashMapOf("from" to uid, "mime" to mime, "data" to data)).await()
        return id
    }

    private val mediaCache = object : android.util.LruCache<String, ByteArray>(24 * 1024 * 1024) { override fun sizeOf(k: String, v: ByteArray) = v.size }

    suspend fun loadMedia(ref: ChatRef, id: String, kv: Int = 0): ByteArray? {
        mediaCache.get(id)?.let { return it }
        val s = db.getReference("media/$id/data").get().await().getValue(String::class.java) ?: return null
        val key = chatKey(ref, kv)
        val bytes = try { if (key == null) Crypto.unb64(s) else Crypto.open(key, s) } catch (e: Exception) { return null }
        mediaCache.put(id, bytes)
        return bytes
    }

    // ------------------------------------------------------------------------------------------ pinned messages
    fun pins(ref: ChatRef): Flow<List<String>> = callbackFlow {
        val r = db.getReference("pins/${ref.id}")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { trySend(s.children.sortedBy { it.getValue(Long::class.java) ?: 0L }.mapNotNull { it.key }) }
            override fun onCancelled(e: DatabaseError) {}
        }
        r.addValueEventListener(l)
        awaitClose { r.removeEventListener(l) }
    }

    fun setPin(ref: ChatRef, mid: String, on: Boolean) {
        val r = db.getReference("pins/${ref.id}/$mid")
        if (on) r.setValue(System.currentTimeMillis()) else r.removeValue()
    }

    // ------------------------------------------------------------------------------------------ forward
    suspend fun forward(from: ChatRef, m: Msg, to: ChatRef) {
        when (m.type) {
            "text" -> send(to, "text", m.text, null, m.text.take(90))
            "sticker" -> send(to, "sticker", m.text, null, "Sticker")
            "image", "voice" -> {
                val bytes = loadMedia(from, m.mediaId ?: return) ?: throw IllegalStateException("Media not available")
                val id = uploadMedia(to, bytes, if (m.type == "image") "image/jpeg" else "audio/mp4")
                val j = org.json.JSONObject().put("m", id).put("c", m.text)
                if (m.type == "voice") { j.put("d", m.dur); j.put("w", org.json.JSONArray(m.wave)) }
                send(to, m.type, j.toString(), null, if (m.type == "image") "Photo" else "Voice message")
            }
        }
    }

    // ------------------------------------------------------------------------------------------ contact discovery (hashed phone numbers)
    fun phoneKey(raw: String): String? {
        val d = raw.filter { it.isDigit() }
        if (d.length < 7) return null
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(("wt:" + d.takeLast(10)).toByteArray())
        return h.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** Lets friends who have your number find you. Only a hash of the last 10 digits is stored. */
    suspend fun registerPhone(raw: String) {
        val key = phoneKey(raw) ?: return
        val old = db.getReference("users/$uid/phoneKey").get().await().getValue(String::class.java)
        if (old != null && old != key) db.getReference("phones/$old").removeValue()
        try { db.getReference("phones/$key").setValue(uid).await() } catch (_: Exception) { return }
        db.getReference("users/$uid/phoneKey").setValue(key).await()
    }

    suspend fun myPhoneRegistered(): Boolean = db.getReference("users/$uid/phoneKey").get().await().exists()

    /** key -> uid for every key that belongs to a WeTalks account. */
    suspend fun lookupPhones(keys: List<String>): Map<String, String> {
        val out = java.util.concurrent.ConcurrentHashMap<String, String>()
        kotlinx.coroutines.coroutineScope {
            keys.chunked(40).forEach { chunk ->
                chunk.map { k -> async(Dispatchers.IO) {
                    try { db.getReference("phones/$k").get().await().getValue(String::class.java)?.let { out[k] = it } } catch (_: Exception) {}
                } }.forEach { it.await() }
            }
        }
        return out
    }

    // ------------------------------------------------------------------------------------------ groups
    suspend fun createGroup(name: String, about: String, photo: String, members: List<String>): String {
        val gid = db.getReference("groups").push().key!!
        val all = (members + uid).distinct()
        db.getReference("groups/$gid").setValue(hashMapOf("name" to name.trim(), "about" to about.trim(), "photo" to photo, "owner" to uid,
            "kv" to 1, "createdAt" to ServerValue.TIMESTAMP, "members" to mapOf(uid to "admin"))).await()
        val gk = Crypto.randomKey()
        val up = HashMap<String, Any?>()
        wrapFor(up, gid, 1, gk, all)
        for (m in all.filter { it != uid }) up["groups/$gid/members/$m"] = "member"
        db.reference.updateChildren(up).await()
        val up2 = HashMap<String, Any?>()
        for (m in all) { val b = "user_chats/$m/$gid"; up2["$b/type"] = "group"; up2["$b/lastTs"] = ServerValue.TIMESTAMP; up2["$b/unread"] = 0 }
        db.reference.updateChildren(up2).await()
        groupKeys["$gid:1"] = gk
        return gid
    }

    private suspend fun wrapFor(up: HashMap<String, Any?>, gid: String, kv: Int, gk: ByteArray, members: List<String>) {
        val me = Crypto.keys(app)
        for (m in members) {
            val pk = userOnce(m)?.pk?.takeIf { it.isNotEmpty() } ?: continue
            val wk = Crypto.shared(me.priv, me.pub, Crypto.unb64(pk))
            up["group_keys/$gid/$kv/$m"] = hashMapOf("by" to uid, "ct" to Crypto.seal(wk, gk))
        }
    }

    suspend fun addMembers(gid: String, add: List<String>) {
        val g = group(gid).value ?: return
        val kv = g.kv
        val gk = groupKey(gid, kv) ?: throw IllegalStateException("Group key missing")
        val up = HashMap<String, Any?>()
        for (m in add) up["groups/$gid/members/$m"] = "member"
        db.reference.updateChildren(up).await()
        val up2 = HashMap<String, Any?>()
        wrapFor(up2, gid, kv, gk, add)
        for (m in add) { val b = "user_chats/$m/$gid"; up2["$b/type"] = "group"; up2["$b/lastTs"] = ServerValue.TIMESTAMP; up2["$b/unread"] = 0 }
        db.reference.updateChildren(up2).await()
    }

    /** Admin removes a member and rotates the group key so the removed person can't read new messages. */
    suspend fun removeMember(gid: String, who: String) {
        val g = group(gid).value ?: return
        val remaining = g.members.keys.filter { it != who }
        val nk = Crypto.randomKey(); val nv = g.kv + 1
        val up = HashMap<String, Any?>()
        up["groups/$gid/members/$who"] = null
        up["groups/$gid/kv"] = nv
        db.reference.updateChildren(up).await()
        val up2 = HashMap<String, Any?>()
        wrapFor(up2, gid, nv, nk, remaining)
        up2["user_chats/$who/$gid"] = null
        db.reference.updateChildren(up2).await()
        groupKeys["$gid:$nv"] = nk
    }

    suspend fun leaveGroup(gid: String) {
        db.getReference("user_chats/$uid/$gid").removeValue().await()
        db.getReference("groups/$gid/members/$uid").removeValue().await()
    }

    suspend fun setAdmin(gid: String, who: String, admin: Boolean) { db.getReference("groups/$gid/members/$who").setValue(if (admin) "admin" else "member").await() }
    suspend fun updateGroup(gid: String, name: String, about: String, photo: String?) {
        val m = hashMapOf<String, Any?>("name" to name.trim(), "about" to about.trim()); if (photo != null) m["photo"] = photo
        db.getReference("groups/$gid").updateChildren(m).await()
    }

    // ------------------------------------------------------------------------------------------ channels
    suspend fun handleFree(lower: String) = !db.getReference("channel_handles/$lower").get().await().exists()

    /** Returns (channelId, null) or (null, error message). */
    suspend fun createChannel(name: String, handle: String, about: String, photo: String): Pair<String?, String?> {
        val lower = handle.lowercase()
        val cid = db.getReference("channels").push().key!!
        try { db.getReference("channel_handles/$lower").setValue(cid).await() } catch (e: Exception) { return null to "That link is already taken" }
        return try {
            db.getReference("channels/$cid").setValue(hashMapOf("name" to name.trim(), "handle" to handle, "handleLower" to lower, "nameLower" to name.trim().lowercase(),
                "about" to about.trim(), "photo" to photo, "owner" to uid, "createdAt" to ServerValue.TIMESTAMP, "subCount" to 1,
                "admins" to mapOf(uid to true))).await()
            db.getReference("channel_subs/$cid/$uid").setValue(true).await()
            db.getReference("user_chats/$uid/$cid").updateChildren(mapOf("type" to "channel", "lastTs" to ServerValue.TIMESTAMP)).await()
            cid to null
        } catch (e: Exception) {
            try { db.getReference("channel_handles/$lower").removeValue().await() } catch (_: Exception) {}
            null to (e.message ?: "Could not create channel")
        }
    }

    suspend fun searchChannels(q: String): List<Channel> {
        val t = q.trim().lowercase().removePrefix("@")
        val src = if (t.isEmpty()) db.getReference("channels").orderByChild("subCount").limitToLast(30).get().await()
        else db.getReference("channels").orderByChild("handleLower").startAt(t).endAt(t + "").limitToFirst(30).get().await()
        val list = src.children.mapNotNull { parseChannel(it.key!!, it) }
        return if (t.isEmpty()) list.sortedByDescending { it.subCount } else list
    }

    suspend fun isSubscribed(cid: String) = db.getReference("channel_subs/$cid/$uid").get().await().exists()

    suspend fun subscribe(cid: String) {
        db.getReference("channel_subs/$cid/$uid").setValue(true).await()
        db.getReference("channels/$cid/subCount").setValue(ServerValue.increment(1)).await()
        db.getReference("user_chats/$uid/$cid").updateChildren(mapOf("type" to "channel", "lastTs" to ServerValue.TIMESTAMP)).await()
    }

    suspend fun unsubscribe(cid: String) {
        db.getReference("channel_subs/$cid/$uid").removeValue().await()
        db.getReference("channels/$cid/subCount").setValue(ServerValue.increment(-1)).await()
        db.getReference("user_chats/$uid/$cid").removeValue().await()
    }

    suspend fun updateChannel(cid: String, name: String, about: String, photo: String?) {
        val m = hashMapOf<String, Any?>("name" to name.trim(), "nameLower" to name.trim().lowercase(), "about" to about.trim()); if (photo != null) m["photo"] = photo
        db.getReference("channels/$cid").updateChildren(m).await()
    }
}
