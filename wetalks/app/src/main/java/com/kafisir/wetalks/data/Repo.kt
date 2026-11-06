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
import org.json.JSONObject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // ------------------------------------------------------------------------------------------ app status (maintenance) + push token
    /** Set from the Firebase console (config/maintenance). Readable without signing in so a closed app still shows it. */
    data class Maint(val on: Boolean = false, val title: String = "", val message: String = "", val eta: String = "", val minVersion: Long = 0)
    val maint = kotlinx.coroutines.flow.MutableStateFlow(Maint())
    private var cfgWatching = false
    fun watchConfig() {
        if (cfgWatching) return
        cfgWatching = true
        db.getReference("config/maintenance").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                maint.value = Maint(s.child("on").getValue(Boolean::class.java) ?: false, s.child("title").getValue(String::class.java) ?: "",
                    s.child("message").getValue(String::class.java) ?: "", s.child("eta").getValue(String::class.java) ?: "", s.child("minVersion").getValue(Long::class.java) ?: 0)
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    /** Registers this phone for push (FCM). The push carries no message text, only "something new in chat X", so end-to-end encryption is untouched. */
    fun registerPush(token: String? = null) {
        if (uid.isEmpty()) return
        val put = { t: String -> db.getReference("fcm_tokens/$uid/$t").setValue(ServerValue.TIMESTAMP); Unit }
        if (token != null) put(token)
        else try { com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { if (it != null) put(it) } } catch (_: Exception) {}
    }
    private fun unregisterPush() {
        if (uid.isEmpty()) return
        val u = uid
        try { com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { if (it != null) db.getReference("fcm_tokens/$u/$it").removeValue() } } catch (_: Exception) {}
    }

    fun signOut() {
        unregisterPush()
        setOnline(false)
        detachAll()
        auth.signOut()
        ConnectService.sync(app)
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
    private fun detachAll() { inboxWatching = ""; serviceMsgs.value = emptyList(); listeners.forEach { it.first.removeEventListener(it.second) }; listeners.clear(); userFlows.clear(); groupFlows.clear(); channelFlows.clear(); dmKeys.clear(); groupKeys.clear(); keyConflict.value = false; dmAll.clear(); dmWinner.clear(); Calls.reset() }

    private val userFlows = HashMap<String, MutableStateFlow<User?>>()
    private val groupFlows = HashMap<String, MutableStateFlow<Group?>>()
    private val channelFlows = HashMap<String, MutableStateFlow<Channel?>>()

    private fun parseUser(id: String, s: DataSnapshot) = if (!s.exists()) null else User(
        id, s.str("name"), s.str("username"), s.str("about"), s.str("photo"), s.str("pk"), s.bool("online"), s.lng("lastSeen"),
        s.bool("showLastSeen", true), s.bool("readReceipts", true), s.bool("webProfile", true))

    private fun parseGroup(id: String, s: DataSnapshot) = if (!s.exists()) null else Group(
        id, s.str("name"), s.str("about"), s.str("photo"), s.str("owner"), (s.lng("kv").takeIf { it > 0 } ?: 1L).toInt(),
        s.child("members").children.associate { it.key!! to (it.getValue(String::class.java) ?: "member") },
        s.child("perms").children.associate { it.key!! to (it.getValue(Boolean::class.java) ?: true) }, s.lng("slow").toInt())

    private fun parseChannel(id: String, s: DataSnapshot) = if (!s.exists()) null else Channel(
        id, s.str("name"), s.str("handle"), s.str("about"), s.str("photo"), s.str("owner"),
        s.child("admins").children.mapNotNull { it.key }.toSet(), s.lng("subCount"), s.lng("lastTs"), s.str("lastPreview"),
        s.child("perms").children.associate { it.key!! to (it.getValue(Boolean::class.java) ?: true) }, s.lng("slow").toInt(), s.bool("priv"))

    private val presOnline = HashMap<String, Boolean?>()
    private val presLast = HashMap<String, Long?>()
    private fun withPresence(u: User) = u.copy(online = presOnline[u.uid] == true, lastSeen = presLast[u.uid] ?: 0L, showLastSeen = presLast[u.uid] != null)

    fun user(id: String): StateFlow<User?> = userFlows.getOrPut(id) {
        presOnline.remove(id); presLast.remove(id)
        MutableStateFlow<User?>(null).also { f ->
            watch("users/$id") { f.value = parseUser(id, it)?.let(::withPresence) }
            // each presence field is readable only if its owner allows it; a denied read simply means "hidden"
            watchRaw("presence/$id/online", { presOnline[id] = it.getValue(Boolean::class.java) ?: false; f.value = f.value?.let(::withPresence) }, { presOnline[id] = null; f.value = f.value?.let(::withPresence) })
            watchRaw("presence/$id/lastSeen", { presLast[id] = it.getValue(Long::class.java) ?: 0L; f.value = f.value?.let(::withPresence) }, { presLast[id] = null; f.value = f.value?.let(::withPresence) })
        }
    }

    private fun watchRaw(path: String, cb: (DataSnapshot) -> Unit, denied: () -> Unit) {
        val ref = db.getReference(path)
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) = cb(s)
            override fun onCancelled(e: DatabaseError) = denied()
        }
        ref.addValueEventListener(l); listeners.add(ref to l)
    }

    // ---- privacy choices: "all" | "contacts" | "nobody"
    val privacy = MutableStateFlow<Map<String, String>>(emptyMap())
    private var privacyWatching = ""
    fun watchPrivacy() {
        if (uid.isEmpty() || privacyWatching == uid) return
        privacyWatching = uid
        watch("privacy/$uid") { s -> privacy.value = s.children.associate { it.key!! to (it.getValue(String::class.java) ?: "") } }
    }
    fun privacyOf(key: String, def: String) = privacy.value[key] ?: def

    suspend fun setPrivacyChoice(key: String, v: String) {
        db.getReference("privacy/$uid/$key").setValue(v).await()
        if (key == "find") {   // "who can find me by my number" also controls the lookup entry
            val h = db.getReference("users/$uid/phoneKey").get().await().getValue(String::class.java)
            if (h != null) { if (v == "nobody") db.getReference("phones/$h").removeValue() else try { db.getReference("phones/$h").setValue(uid).await() } catch (_: Exception) {} }
        }
    }

    /** A person's phone number, only if they let this account see it. */
    suspend fun phoneOf(id: String): String? = try { db.getReference("contact_info/$id/phone").get().await().getValue(String::class.java)?.takeIf { it.isNotEmpty() } } catch (e: Exception) { null }
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
    private var connWatching = false
    /** After a reconnect the server forgets "online" (it was reset by onDisconnect): assert it again while the app is on screen. */
    fun watchConnection() {
        if (connWatching) return
        connWatching = true
        db.getReference(".info/connected").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { if (s.getValue(Boolean::class.java) == true && Calls.foreground && uid.isNotEmpty()) setOnline(true) }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    /** Presence lives in its own node so the owner's privacy choices can be enforced by the database rules. */
    fun setOnline(on: Boolean) {
        if (uid.isEmpty()) return
        val r = db.getReference("presence/$uid")
        if (on) {
            r.child("online").setValue(true)
            r.child("online").onDisconnect().setValue(false)
            r.child("lastSeen").onDisconnect().setValue(ServerValue.TIMESTAMP)
        } else {
            r.child("online").setValue(false)
            r.child("lastSeen").setValue(ServerValue.TIMESTAMP)
        }
        // the old public fields are no longer used: remove them so nobody can read them around the privacy settings
        db.getReference("users/$uid").updateChildren(mapOf("online" to null, "lastSeen" to null, "showLastSeen" to null))
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
        return try { db.getReference("users/$me").updateChildren(m).await(); try { publishWebProfile() } catch (_: Exception) {}; null } catch (e: Exception) { e.message ?: "Could not save" }
    }

    // ------------------------------------------------------------------------------------------ service inbox (developer API)
    val serviceMsgs = MutableStateFlow<List<ServiceMsg>>(emptyList())
    private var inboxWatching = ""
    fun watchServiceInbox() {
        if (uid.isEmpty() || inboxWatching == uid) return
        inboxWatching = uid
        watch("service_inbox/$uid") { s ->
            serviceMsgs.value = s.children.mapNotNull { c ->
                val a = c.str("a"); if (a.isEmpty()) return@mapNotNull null
                ServiceMsg(c.key!!, a, c.str("an"), c.lng("v") == 1L, c.str("k"), c.str("t"), c.str("x"), c.str("l"), c.lng("o") == 1L, c.lng("ts"))
            }.sortedByDescending { it.ts }
        }
    }
    suspend fun deleteServiceMsgs(ids: List<String>) { val up = HashMap<String, Any?>(); ids.forEach { up["service_inbox/$uid/$it"] = null }; db.reference.updateChildren(up).await() }
    suspend fun blockedServices(): Set<String> = db.getReference("service_blocks/$uid").get().await().children.mapNotNull { it.key }.toSet()
    suspend fun setServiceBlocked(appId: String, on: Boolean) { db.getReference("service_blocks/$uid/$appId").setValue(if (on) true else null).await() }

    // ------------------------------------------------------------------------------------------ public web card
    // The link page on the web needs a name and picture without signing in. Only what a public profile / public channel already shows
    // is copied to web/u/<username> and web/c/<handle> (no phone, no keys, no chats). Turned off with the privacy switch / private channel.
    suspend fun publishWebProfile() {
        if (uid.isEmpty()) return
        val u = db.getReference("users/$uid").get().await()
        val lower = u.child("usernameLower").getValue(String::class.java).orEmpty()
        if (lower.isEmpty()) return
        val ref = db.getReference("web/u/$lower")
        if (u.child("webProfile").getValue(Boolean::class.java) == false) { ref.removeValue().await(); return }
        val photo = u.child("photo").getValue(String::class.java).orEmpty()
        ref.setValue(hashMapOf("n" to (u.child("name").getValue(String::class.java) ?: ""), "a" to (u.child("about").getValue(String::class.java) ?: ""),
            "p" to if (photo.length <= 40000) photo else "")).await()
    }
    suspend fun publishWebChannel(cid: String) {
        val c = db.getReference("channels/$cid").get().await()
        val lower = c.child("handleLower").getValue(String::class.java).orEmpty()
        if (lower.isEmpty() || c.child("owner").getValue(String::class.java) != uid) return
        val ref = db.getReference("web/c/$lower")
        if (c.child("priv").getValue(Boolean::class.java) == true) { ref.removeValue().await(); return }
        val photo = c.child("photo").getValue(String::class.java).orEmpty()
        ref.setValue(hashMapOf("n" to (c.child("name").getValue(String::class.java) ?: ""), "a" to (c.child("about").getValue(String::class.java) ?: ""),
            "s" to (c.child("subCount").getValue(Long::class.java) ?: 0L), "p" to if (photo.length <= 40000) photo else "")).await()
    }
    suspend fun setWebProfile(on: Boolean) { db.getReference("users/$uid/webProfile").setValue(on).await(); publishWebProfile() }

    /** Called after every sign-in so a reinstalled phone publishes its new key. */
    suspend fun publishKey() {
        if (uid.isEmpty()) return
        val exists = db.getReference("users/$uid/name").get().await().exists()
        val mine = Crypto.b64(Crypto.keys(app).pub)
        val onServer = db.getReference("users/$uid/pk").get().await().getValue(String::class.java).orEmpty()
        // This phone has a different key than the one the server knows (reinstall / new phone). If a backup exists, ask the user to
        // restore it instead of silently replacing the key, which would make every old chat unreadable.
        if (exists && onServer.isNotEmpty() && onServer != mine && hasKeyBackup()) { keyConflict.value = true; return }
        if (exists) { db.getReference("users/$uid/pk").setValue(mine).await(); db.getReference("users/$uid/pkh/" + mine.filter { it.isLetterOrDigit() }.take(24)).setValue(mine) }
        if (exists) auth.currentUser?.phoneNumber?.takeIf { it.isNotEmpty() }?.let { registerPhone(it) }
    }

    val keyConflict = MutableStateFlow(false)
    suspend fun hasKeyBackup(): Boolean = try { db.getReference("key_backups/$uid").get().await().exists() } catch (e: Exception) { false }

    /** Encrypts the private key with a passphrase (PBKDF2 + AES-GCM) and stores it where only the owner can read it. */
    suspend fun backupKey(pass: String) {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }; val iter = 210_000
        val k = Crypto.passKey(pass, salt, iter)
        val me = Crypto.keys(app)
        db.getReference("key_backups/$uid").setValue(hashMapOf("s" to Crypto.b64(salt), "i" to iter, "c" to Crypto.seal(k, me.priv), "pub" to Crypto.b64(me.pub), "ts" to ServerValue.TIMESTAMP)).await()
    }

    /** Restores the backed-up key on this phone. Returns false when the passphrase is wrong. */
    suspend fun restoreKey(pass: String): Boolean {
        val s = db.getReference("key_backups/$uid").get().await()
        if (!s.exists()) return false
        return try {
            val k = Crypto.passKey(pass, Crypto.unb64(s.str("s")), s.lng("i").toInt())
            val priv = Crypto.open(k, s.str("c"))
            Crypto.install(app, priv)
            dmKeys.clear(); groupKeys.clear()
            db.getReference("users/$uid/pk").setValue(Crypto.b64(Crypto.keys(app).pub)).await()
            keyConflict.value = false
            true
        } catch (e: Exception) { false }
    }
    /** "Start fresh": keep this phone's new key; old chats stay unreadable. */
    suspend fun keepNewKey() { val m = Crypto.b64(Crypto.keys(app).pub); db.getReference("users/$uid/pk").setValue(m).await(); db.getReference("users/$uid/pkh/" + m.filter { it.isLetterOrDigit() }.take(24)).setValue(m); keyConflict.value = false }

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
                    // half-written entries (no type / no peer) must never reach the UI: they used to crash the app on start
                    if (type.isEmpty() && c.str("peer").isEmpty() && c.str("lastBody").isEmpty() && c.lng("lastTs") == 0L) return@mapNotNull null
                    if (kind == Kind.DM && c.str("peer").isEmpty()) return@mapNotNull null
                    ChatItem(ChatRef(id, kind, c.str("peer").ifEmpty { null }), c.lng("lastTs"), c.str("lastBody"), c.str("lastType"),
                        c.str("lastFrom"), c.lng("unread"), c.bool("pinned"), c.bool("muted"))
                }
                warmGroupKeys(list)
                trySend(list.sortedWith(compareByDescending<ChatItem> { it.pinned }.thenByDescending { it.lastTs }))
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        ref.addValueEventListener(l)
        awaitClose { ref.removeEventListener(l) }
    }

    fun setPinned(id: String, v: Boolean) { val me = uid; db.getReference("user_chats/$me/$id/type").get().addOnSuccessListener { if (it.exists()) db.getReference("user_chats/$me/$id/pinned").setValue(v) } }
    fun muteDm(peer: String, v: Boolean) {
        val b = "user_chats/$uid/${dmId(peer)}"
        db.getReference(b).updateChildren(mapOf("type" to "dm", "peer" to peer, "muted" to v))
    }

    /** Turns a scanned/pasted wetalks:// link into a screen: wetalks://u/<username> or wetalks://c/<handle>. */
    /** Shareable web links: they open in any browser, and open the app directly on phones that have it. */
    object Links {
        const val HOST = "wetalks.fireclashpro.com"
        fun channel(handle: String) = "https://$HOST/c/$handle"
        fun user(username: String) = "https://$HOST/u/$username"
    }

    suspend fun resolveLink(text: String): com.kafisir.wetalks.ui.Route? {
        val m = Regex("""(?:wetalks://|https?://wetalks-78e5a\.(?:web\.app|firebaseapp\.com)/)([uc])/([A-Za-z0-9_]{3,32})""").find(text.trim()) ?: return null
        val key = m.groupValues[2].lowercase()
        return if (m.groupValues[1] == "u") db.getReference("usernames/$key").get().await().getValue(String::class.java)?.let { com.kafisir.wetalks.ui.RProfile(it) }
        else db.getReference("channel_handles/$key").get().await().getValue(String::class.java)?.let { com.kafisir.wetalks.ui.RChannelInfo(it) }
    }

    fun setMuted(id: String, v: Boolean) { val me = uid; db.getReference("user_chats/$me/$id/type").get().addOnSuccessListener { if (it.exists()) db.getReference("user_chats/$me/$id/muted").setValue(v) } }
    fun removeChat(id: String) { db.getReference("user_chats/$uid/$id").removeValue() }

    fun dmId(peer: String) = listOf(uid, peer).sorted().joinToString("_")
    fun dmRef(peer: String) = ChatRef(dmId(peer), Kind.DM, peer)

    // ------------------------------------------------------------------------------------------ keys
    private val dmKeys = HashMap<String, Pair<String, ByteArray>>()
    private val groupKeys = HashMap<String, ByteArray>()

    // ---- every public key a person has ever used, so chats stay readable when keys change (second phone, two accounts on one phone …)
    private val seenPrefs get() = app.getSharedPreferences("wt_seenpk", Context.MODE_PRIVATE)
    private val dmAll = HashMap<String, Pair<Long, List<ByteArray>>>()
    private val dmWinner = HashMap<String, ByteArray>()

    /** All keys this phone could have used with [peer]: my key x every public key the peer has published (current, history, seen before). */
    private suspend fun dmKeyList(peer: String, fresh: Boolean = false): List<ByteArray> {
        dmAll[peer]?.let { (t, l) -> if (!fresh || System.currentTimeMillis() - t < 20_000) return l }
        val pubs = LinkedHashSet<String>()
        (user(peer).value?.pk ?: userOnce(peer)?.pk)?.takeIf { it.isNotEmpty() }?.let { pubs += it }
        try { db.getReference("users/$peer/pkh").get().await().children.forEach { c -> c.getValue(String::class.java)?.let { pubs += it } } } catch (_: Exception) {}
        pubs += seenPrefs.getStringSet(peer, emptySet()).orEmpty()
        seenPrefs.edit().putStringSet(peer, pubs.toSet()).apply()
        val me = Crypto.keys(app)
        val list = pubs.mapNotNull { p -> try { Crypto.shared(me.priv, me.pub, Crypto.unb64(p)) } catch (e: Exception) { null } }
        dmAll[peer] = System.currentTimeMillis() to list
        return list
    }

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
        // the sender may have reinstalled since: try their current key and the older ones they published
        val pks = LinkedHashSet<String>()
        userOnce(by)?.pk?.takeIf { it.isNotEmpty() }?.let { pks += it }
        try { db.getReference("users/$by/pkh").get().await().children.forEach { c -> c.getValue(String::class.java)?.let { pks += it } } } catch (_: Exception) {}
        pks += seenPrefs.getStringSet("g:$by", emptySet()).orEmpty()
        if (pks.isEmpty()) return null
        val me = Crypto.keys(app)
        for (pk in pks) {
            try { val k = Crypto.open(Crypto.shared(me.priv, me.pub, Crypto.unb64(pk)), ct); groupKeys[ck] = k; seenPrefs.edit().putStringSet("g:$by", pks.toSet()).apply(); return k } catch (_: Exception) {}
        }
        return null
    }

    private val repaired = HashSet<String>()
    /**
     * A member can be left without a usable group key (they had not opened the app when the group was made, or a key changed).
     * Any admin who holds the key hands it out again; if the admin has lost it too, a new key version is created for everyone.
     * Returns true when something was repaired.
     */
    suspend fun repairGroupKeys(gid: String, force: Boolean = false): Boolean {
        if (!force && !repaired.add(gid)) return false
        val g = group(gid).value ?: return false
        if (g.members[uid] != "admin") return false
        val members = g.members.keys.toList()
        val have = groupKey(gid, g.kv)
        val up = HashMap<String, Any?>()
        if (have != null) {
            wrapFor(up, gid, g.kv, have, members)
        } else {
            val nk = Crypto.randomKey(); val nv = g.kv + 1
            wrapFor(up, gid, nv, nk, members)
            up["groups/$gid/kv"] = nv
            groupKeys["$gid:$nv"] = nk
        }
        if (up.isEmpty()) return false
        db.reference.updateChildren(up).await()
        return true
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
              val deleted: Boolean, val edited: Boolean, val react: Map<String, String>, val fwd: String? = null)

    private fun rawOf(c: DataSnapshot) = Raw(c.key!!, c.str("from"), c.lng("ts"), c.str("type"), c.str("body"), c.lng("kv").toInt(), c.str("rid").ifEmpty { null },
        c.bool("deleted"), c.bool("edited"), c.child("react").children.associate { it.key!! to (it.getValue(String::class.java) ?: "") }, c.str("fwd").ifEmpty { null })

    // the last decoded messages of the chats opened in this session: re-opening a chat shows them at once instead of an empty screen
    private val msgCache = object : LinkedHashMap<String, List<Msg>>(16, .75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<Msg>>?) = size > 12 }
    @Synchronized fun cachedMessages(chatId: String): List<Msg>? = msgCache[chatId]
    @Synchronized fun putMessages(chatId: String, l: List<Msg>) { msgCache[chatId] = l }

    private val warmed = HashSet<String>()
    /** Group keys take several round trips the first time: fetch them as soon as the chat list is known, not when the chat is opened. */
    private fun warmGroupKeys(list: List<ChatItem>) {
        for (c in list.asSequence().filter { it.ref.kind == Kind.GROUP }.take(12)) {
            if (!warmed.add(c.ref.id)) continue
            scope.launch { try { groupKey(c.ref.id, groupVersion(c.ref.id)) } catch (_: Exception) { warmed.remove(c.ref.id) } }
        }
    }

    fun rawMessages(ref: ChatRef, limit: Int): Flow<List<Raw>> = callbackFlow {
        val q = db.getReference("messages/${ref.id}").orderByKey().limitToLast(limit)
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                trySend(s.children.map { rawOf(it) })
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
            val opened: String? = try {
                if (ref.kind == Kind.DM) openDm(ref.peer!!, r.body)
                else chatKey(ref, r.kv)?.let { k -> try { Crypto.openText(k, r.body) } catch (e: Exception) { null } }
            } catch (e: Exception) { null }
            plain = opened ?: ""; if (opened == null) bad = true
        }
        var mediaId: String? = null; var dur = 0; var text = plain; var wave: List<Int> = emptyList()
        var link: LinkPreview? = null; var mime = ""; var w = 0; var h = 0; var fname = ""; var fsize = 0L; var fkey = ""; var fthumb = ""; var album = ""
        if (!bad && r.type in listOf("image", "voice", "gif", "link", "call", "video", "audio", "file")) {
            try {
                val j = org.json.JSONObject(plain)
                when (r.type) {
                    "call" -> { dur = j.optInt("d"); mime = j.optString("s"); w = j.optInt("v") }
                    "link" -> { text = j.optString("t"); link = LinkPreview(j.optString("u"), j.optString("ti"), j.optString("d"), j.optString("i")) }
                    else -> {
                        mediaId = j.optString("m"); album = j.optString("ag"); dur = j.optInt("d"); text = j.optString("c"); mime = j.optString("mime"); w = j.optInt("w"); h = j.optInt("h")
                        if (r.type == "video" || r.type == "audio" || r.type == "file") { fname = FileKit.cleanName(j.optString("n")); fsize = j.optLong("s"); fkey = j.optString("k"); fthumb = j.optString("th"); mime = FileKit.mimeOf(FileKit.extOf(fname)) }
                        j.optJSONArray("w")?.let { a -> wave = (0 until a.length()).map { a.optInt(it) } }
                    }
                }
            } catch (e: Exception) { bad = true }
        }
        return Msg(r.id, r.from, r.ts, r.type, text, mediaId, dur, r.rid, false, r.edited, r.react, bad, wave = wave, link = link, mime = mime, w = w, h = h, fwd = r.fwd, name = fname, size = fsize, fkey = fkey, thumb = fthumb, album = album)
    }

    /** Opens a DM body with whichever candidate key works (AES-GCM tells us which one is right). */
    private suspend fun openDm(peer: String, body: String): String? {
        dmWinner[peer]?.let { w -> try { return Crypto.openText(w, body) } catch (_: Exception) {} }
        for (round in 0..1) {
            for (k in dmKeyList(peer, fresh = round == 1)) try { val t = Crypto.openText(k, body); dmWinner[peer] = k; return t } catch (_: Exception) {}
        }
        return null
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

    private val slowPrefs get() = app.getSharedPreferences("wt_slow", Context.MODE_PRIVATE)

    /** Group / channel rules set by the owner. Throws a readable error when this person may not send [type]; returns the slow-mode seconds that apply. */
    private fun checkAllowed(ref: ChatRef, type: String): Int {
        if (ref.kind == Kind.DM) return 0
        val perms: Map<String, Boolean>; val slow: Int; val privileged: Boolean
        if (ref.kind == Kind.GROUP) { val g = group(ref.id).value ?: return 0; perms = g.perms; slow = g.slow; privileged = g.members[uid] == "admin" }
        else { val c = channel(ref.id).value ?: return 0; perms = c.perms; slow = c.slow; privileged = c.owner == uid || uid in c.admins
            if (!privileged && perms["followers"] != true) throw IllegalStateException("Only admins can post in this channel") }
        if (privileged) return 0
        val who = if (ref.kind == Kind.GROUP) "this group" else "this channel"
        if (perms["send"] == false) throw IllegalStateException("Only admins can send messages in $who")
        val key = when (type) { "text", "link" -> "text"; "image", "video", "audio", "file" -> "image"; "gif", "sticker" -> "sticker"; "voice" -> "voice"; else -> "" }
        if (key.isNotEmpty() && perms[key] == false) throw IllegalStateException(when (key) { "text" -> "Text messages"; "image" -> "Photos"; "sticker" -> "Stickers and GIFs"; else -> "Voice messages" } + " are turned off in $who")
        if (slow > 0) { val wait = slowPrefs.getLong(ref.id, 0L) + slow * 1000L - System.currentTimeMillis(); if (wait > 0) throw IllegalStateException("Slow mode is on · wait ${wait / 1000 + 1}s") }
        return slow
    }

    /** Encrypts (DM/group) and sends. [payload] is the plain text, or a small JSON for image/voice, or a sticker id. */
    // ------------------------------------------------------------------------------------------ outbox (shows "sending" bubbles at once)
    class Outgoing(val id: String, val ref: ChatRef, val type: String, val text: String = "", val wave: List<Int> = emptyList(), val dur: Int = 0,
                   val w: Int = 0, val h: Int = 0, val thumb: androidx.compose.ui.graphics.ImageBitmap? = null,
                   val name: String = "", val size: Long = 0, val album: String = "") {
        val ts = System.currentTimeMillis()
        var progress by androidx.compose.runtime.mutableFloatStateOf(0f)
        var failed by androidx.compose.runtime.mutableStateOf(false)
        var error by androidx.compose.runtime.mutableStateOf("")
        var work: (suspend () -> Unit)? = null
    }
    val outgoing = androidx.compose.runtime.mutableStateListOf<Outgoing>()

    /** Adds the bubble immediately, then does the slow work (processing, upload, send). The bubble disappears when the real message arrives. */
    fun runOutgoing(o: Outgoing, work: suspend () -> Unit) {
        o.work = work
        if (o !in outgoing) outgoing.add(o)
        o.failed = false
        scope.launch {
            try { work(); outgoing.remove(o) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { o.failed = true; o.error = e.message ?: "Could not send"; com.kafisir.wetalks.ui.Nav.say(o.error) }
        }
    }
    fun retryOutgoing(o: Outgoing) { o.work?.let { runOutgoing(o, it) } }
    fun dropOutgoing(o: Outgoing) { outgoing.remove(o) }

    /** Turns an already-sent text message into a link card once its preview is ready. */
    suspend fun attachLinkPreview(ref: ChatRef, mid: String, text: String, p: LinkPreview) {
        val kv = if (ref.kind == Kind.GROUP) (group(ref.id).value?.kv ?: 1) else 0
        val key = chatKey(ref, kv)
        val payload = JSONObject().put("t", text).put("u", p.url).put("ti", p.title).put("d", p.desc).put("i", p.thumb).toString()
        val body = if (key == null) payload else Crypto.sealText(key, payload)
        db.getReference("messages/${ref.id}/$mid").updateChildren(mapOf("type" to "link", "body" to body)).await()
    }

    suspend fun send(ref: ChatRef, type: String, payload: String, rid: String?, preview: String, fwd: String? = null): String {
        val slowSecs = checkAllowed(ref, type)
        val kv = if (ref.kind == Kind.GROUP) (group(ref.id).value?.kv ?: groupVersion(ref.id)) else 0
        var key = chatKey(ref, kv)
        if (key == null && ref.kind == Kind.GROUP) {
            // repair (as admin) and try again before giving up
            try { if (repairGroupKeys(ref.id, force = true)) { groupKeys.clear(); key = chatKey(ref, 0) } } catch (_: Exception) {}
        }
        if (ref.kind != Kind.CHANNEL && key == null) throw IllegalStateException(if (ref.kind == Kind.GROUP) "This group's key is not on your phone yet. Ask a group admin to open WeTalks once, then try again." else "Encryption key not available yet. The other person must open WeTalks once.")
        val body = if (key == null) payload else Crypto.sealText(key, payload)
        val listBody = if (type == "link") {
            val slim = try { org.json.JSONObject(payload).apply { remove("i") }.toString() } catch (e: Exception) { payload }
            if (key == null) slim else Crypto.sealText(key, slim)
        } else body
        val mid = db.getReference("messages/${ref.id}").push().key!!
        val up = HashMap<String, Any?>()
        up["messages/${ref.id}/$mid"] = hashMapOf("from" to uid, "ts" to ServerValue.TIMESTAMP, "type" to type, "body" to body, "kv" to kv, "rid" to rid, "fwd" to fwd)
        fanOut(ref, up, type, listBody, preview)
        if (slowSecs > 0) up["slow/${ref.id}/$uid"] = ServerValue.TIMESTAMP
        db.reference.updateChildren(up).await()
        if (slowSecs > 0) slowPrefs.edit().putLong(ref.id, System.currentTimeMillis()).apply()
        return mid
    }

    suspend fun editMessage(ref: ChatRef, id: String, newText: String) {
        val kv = if (ref.kind == Kind.GROUP) (group(ref.id).value?.kv ?: 1) else 0
        val key = chatKey(ref, kv)
        val body = if (key == null) newText else Crypto.sealText(key, newText)
        db.getReference("messages/${ref.id}/$id").updateChildren(mapOf("body" to body, "edited" to true)).await()
    }

    /** Changes only the caption of a photo; the picture itself stays. An empty text removes the caption. */
    suspend fun editCaption(ref: ChatRef, m: Msg, newCaption: String) {
        val j = org.json.JSONObject().put("m", m.mediaId).put("c", newCaption.trim())
        if (m.mime.isNotEmpty()) j.put("mime", m.mime)
        if (m.w > 0) j.put("w", m.w); if (m.h > 0) j.put("h", m.h)
        editMessage(ref, m.id, j.toString())
    }

    /** Removes the messages for everybody, as if they never existed (and their own uploaded media with them). */
    suspend fun deleteMessages(ref: ChatRef, ids: List<String>) {
        if (ids.isEmpty()) return
        val up = HashMap<String, Any?>()
        for (id in ids) {
            up["messages/${ref.id}/$id"] = null; up["pins/${ref.id}/$id"] = null
            try {
                val snap = db.getReference("messages/${ref.id}/$id").get().await()
                if (snap.exists() && snap.str("from") == uid) decrypt(ref, rawOf(snap)).mediaId?.takeIf { it.isNotEmpty() }?.let { removeMedia(it) }
            } catch (_: Exception) {}
        }
        db.reference.updateChildren(up).await()
        refreshLast(ref)
    }

    /** After a deletion, point the chat list at whatever message is now the newest one. */
    private suspend fun refreshLast(ref: ChatRef) {
        val last = db.getReference("messages/${ref.id}").orderByKey().limitToLast(1).get().await().children.firstOrNull()
        if (ref.kind == Kind.CHANNEL) {
            // channel posts are plain: the line under the channel name must follow the newest post that is left
            val body = last?.str("body") ?: ""
            val pv = when (last?.str("type")) {
                null -> ""; "text" -> body.take(90); "image" -> "Photo"; "voice" -> "Voice message"; "gif" -> "GIF"; "sticker" -> "Sticker"; "video" -> "Video"; "audio" -> "Audio"; "file" -> "File"
                "link" -> try { org.json.JSONObject(body).optString("t").take(90) } catch (_: Exception) { "Link" }
                else -> ""
            }
            val up = HashMap<String, Any?>(); up["channels/${ref.id}/lastPreview"] = pv
            if (last != null) up["channels/${ref.id}/lastTs"] = last.lng("ts")
            try { db.reference.updateChildren(up).await() } catch (_: Exception) {}
            return
        }
        val targets = when (ref.kind) {
            Kind.DM -> listOfNotNull(uid, ref.peer).distinct()
            else -> group(ref.id).value?.members?.keys?.toList() ?: db.getReference("groups/${ref.id}/members").get().await().children.mapNotNull { it.key }
        }
        val up = HashMap<String, Any?>()
        for (u in targets) {
            val b = "user_chats/$u/${ref.id}"
            if (ref.kind == Kind.DM) { up["$b/type"] = "dm"; up["$b/peer"] = if (u == uid) ref.peer else uid } else up["$b/type"] = "group"
            if (last != null) {
                var body = last.str("body")
                if (last.str("type") == "link") try {   // keep the chat-list copy small
                    val key = chatKey(ref, last.lng("kv").toInt())
                    val plain = if (key == null) body else Crypto.openText(key, body)
                    val slim = org.json.JSONObject(plain).apply { remove("i") }.toString()
                    body = if (key == null) slim else Crypto.sealText(key, slim)
                } catch (_: Exception) {}
                up["$b/lastTs"] = last.lng("ts"); up["$b/lastBody"] = body; up["$b/lastType"] = last.str("type"); up["$b/lastFrom"] = last.str("from")
            } else { up["$b/lastBody"] = ""; up["$b/lastType"] = ""; up["$b/lastFrom"] = "" }
        }
        try { db.reference.updateChildren(up).await() } catch (_: Exception) {}
    }

    /** Wipes every message of a DM for both people (the chat itself stays in both lists, empty). Groups/channels are cleared only on this phone. */
    suspend fun clearHistory(ref: ChatRef) {
        if (ref.kind != Kind.DM) { cutPrefs.edit().putLong(ref.id, System.currentTimeMillis()).apply(); return }
        try {
            val snap = db.getReference("messages/${ref.id}").get().await()
            for (c in snap.children) if (c.str("from") == uid) {
                val id = try { decrypt(ref, rawOf(c)).mediaId } catch (_: Exception) { null }
                if (!id.isNullOrEmpty()) removeMedia(id)
            }
        } catch (_: Exception) {}
        val up = HashMap<String, Any?>()
        up["messages/${ref.id}"] = null; up["pins/${ref.id}"] = null
        for (u in listOfNotNull(uid, ref.peer).distinct()) {
            val b = "user_chats/$u/${ref.id}"
            up["$b/type"] = "dm"; up["$b/peer"] = if (u == uid) ref.peer else uid
            up["$b/lastBody"] = ""; up["$b/lastType"] = ""; up["$b/lastFrom"] = ""; up["$b/unread"] = 0
        }
        db.reference.updateChildren(up).await()
    }

    private val cutPrefs get() = app.getSharedPreferences("wt_cut", Context.MODE_PRIVATE)
    /** Messages older than this are hidden locally (a chat deleted just for me). */
    fun cutoff(cid: String) = cutPrefs.getLong(cid, 0L)

    /**
     * Deletes a conversation. [forBoth] (DMs only) wipes every message, pin and the chat-list entry on both phones;
     * otherwise it only clears it on this phone.
     */
    suspend fun deleteChat(ref: ChatRef, forBoth: Boolean) {
        if (ref.kind == Kind.DM && forBoth) {
            val up = HashMap<String, Any?>()
            try {
                val snap = db.getReference("messages/${ref.id}").get().await()
                for (c in snap.children) if (c.str("from") == uid) {
                    val id = try { decrypt(ref, rawOf(c)).mediaId } catch (_: Exception) { null }
                    if (!id.isNullOrEmpty()) removeMedia(id)
                }
            } catch (_: Exception) {}
            up["messages/${ref.id}"] = null; up["pins/${ref.id}"] = null
            up["user_chats/$uid/${ref.id}"] = null
            if (ref.peer != null && ref.peer != uid) up["user_chats/${ref.peer}/${ref.id}"] = null
            db.reference.updateChildren(up).await()
        } else {
            cutPrefs.edit().putLong(ref.id, System.currentTimeMillis()).apply()
            removeChat(ref.id)
        }
    }

    fun react(ref: ChatRef, id: String, emoji: String?) {
        val r = db.getReference("messages/${ref.id}/$id/react/$uid")
        if (emoji == null) r.removeValue() else r.setValue(emoji)
    }

    private val hiddenPrefs get() = app.getSharedPreferences("wt_hidden", Context.MODE_PRIVATE)
    fun hideForMe(id: String) { hiddenPrefs.edit().putBoolean(id, true).apply() }
    fun isHidden(id: String) = hiddenPrefs.getBoolean(id, false)

    fun markRead(ref: ChatRef) {
        if (user(uid).value?.readReceipts != false) db.getReference("reads/${ref.id}/$uid").setValue(ServerValue.TIMESTAMP)
        if (ref.kind != Kind.CHANNEL) {
            val me = uid; val cid = ref.id
            db.getReference("user_chats/$me/$cid/type").get().addOnSuccessListener { if (it.exists()) db.getReference("user_chats/$me/$cid/unread").setValue(0) }
        }
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
        // encrypted here on the phone; the media server only ever sees ciphertext
        val sealed = if (key == null) bytes else Crypto.sealBytes(key, bytes)
        val id = MediaApi.upload(ref.id, sealed, mime)
        DataPrefs.addUp(sealed.size.toLong())
        return id
    }

    /** Deletes a file (own uploads only): files from before the move are still in the database, new ones are on the media server. */
    private suspend fun removeMedia(id: String) {
        if (id.isEmpty()) return
        if (MediaApi.isRemote(id)) MediaApi.delete(id) else try { db.getReference("media/$id").removeValue().await() } catch (_: Exception) {}
    }

    private val mediaCache = object : android.util.LruCache<String, ByteArray>(24 * 1024 * 1024) { override fun sizeOf(k: String, v: ByteArray) = v.size }

    suspend fun loadMedia(ref: ChatRef, id: String, kv: Int = 0): ByteArray? {
        mediaCache.get(id)?.let { return it }
        var s = DataPrefs.getDisk(id)
        if (s == null) {
            s = (if (MediaApi.isRemote(id)) MediaApi.download(id)?.let { Crypto.b64(it) } else db.getReference("media/$id/data").get().await().getValue(String::class.java)) ?: return null
            DataPrefs.addDown(s.length.toLong()); DataPrefs.putDisk(id, s)
        }
        val bytes = try {
            if (ref.kind == Kind.DM) {
                var out: ByteArray? = null
                dmWinner[ref.peer!!]?.let { w -> try { out = Crypto.open(w, s) } catch (_: Exception) {} }
                if (out == null) for (k in dmKeyList(ref.peer!!)) try { out = Crypto.open(k, s); break } catch (_: Exception) {}
                out ?: return null
            } else { val key = chatKey(ref, kv); if (key == null) Crypto.unb64(s) else Crypto.open(key, s) }
        } catch (e: Exception) { return null }
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
    /** Video / audio / document: check, encrypt with a fresh key, upload from disk, then send the message that carries the key. */
    suspend fun sendFile(ref: ChatRef, uri: android.net.Uri, p: FileKit.Picked, rid: String?, fwd: String?, onProgress: (Float) -> Unit, info: FileKit.VideoInfo? = null) {
        if (!com.kafisir.wetalks.voice.Premium.files) throw IllegalStateException("Video, audio and documents are coming soon with WeTalks Plus")
        val ctx = app
        val key = FileKit.newKey()
        val tmp = java.io.File(ctx.cacheDir, "up_" + System.nanoTime() + ".enc")
        try {
            val plain = withContext(Dispatchers.IO) { FileKit.encryptPicked(ctx, uri, p, key, tmp) { onProgress(it * 0.25f) } }
            val id = MediaApi.uploadFile(ref.id, tmp, p.kind, p.ext, p.mime) { onProgress(0.25f + it * 0.75f) }
            val j = org.json.JSONObject().put("m", id).put("k", Crypto.b64(key)).put("n", p.name).put("s", plain).put("mime", p.mime)
            if (info != null) { j.put("d", info.seconds).put("w", info.w).put("h", info.h); if (info.thumb != null) j.put("th", Crypto.b64(info.thumb)) }
            // the sender already has the file: keep a copy under the new id so opening it later never needs a download
            withContext(Dispatchers.IO) { FileKit.keepSent(ctx, id, p.name, uri) }
            send(ref, p.kind, j.toString(), rid, when (p.kind) { "video" -> "Video"; "audio" -> "Audio"; else -> "Document" }, fwd)
        } finally { tmp.delete() }
    }

    /** Downloads and decrypts a received file into the cache (once); returns the readable copy. */
    suspend fun fetchFile(m: Msg, onProgress: (Float) -> Unit): java.io.File {
        val id = m.mediaId?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("This file is not available")
        val out = FileKit.cachedFile(app, id, m.name)
        if (out.exists() && out.length() > 0) { out.setLastModified(System.currentTimeMillis()); return out }
        val key = try { Crypto.unb64(m.fkey) } catch (_: Exception) { throw IllegalStateException("This file can't be opened (missing key)") }
        if (key.size != 32) throw IllegalStateException("This file can't be opened (bad key)")
        val enc = java.io.File(app.cacheDir, "dl_" + System.nanoTime() + ".enc")
        try {
            if (!MediaApi.downloadToFile(id, enc) { onProgress(it * 0.85f) }) throw IllegalStateException("Could not download the file. It may have been deleted.")
            return withContext(Dispatchers.IO) {
                try { FileKit.decrypt(enc, key, out, m.size, FileKit.extOf(m.name)) } catch (e: Exception) { out.delete(); throw IllegalStateException(e.message ?: "The file could not be decrypted") }
            }
        } finally { enc.delete() }
    }

    suspend fun forward(from: ChatRef, m: Msg, to: ChatRef) {
        val orig = m.fwd ?: m.from
        when (m.type) {
            "video", "audio", "file" -> {
                val f = fetchFile(m) {}
                val ext = FileKit.extOf(m.name)
                val uri = android.net.Uri.fromFile(f)
                sendFile(to, uri, FileKit.Picked(m.name, ext, m.type, FileKit.mimeOf(ext), f.length()), null, orig, {}, if (m.type == "video") FileKit.videoInfo(app, uri) else null)
            }
            "text" -> send(to, "text", m.text, null, m.text.take(90), orig)
            "sticker" -> send(to, "sticker", m.text, null, "Sticker", orig)
            "link" -> send(to, "link", org.json.JSONObject().put("t", m.text).put("u", m.link?.url ?: "").put("ti", m.link?.title ?: "").put("d", m.link?.desc ?: "").put("i", m.link?.thumb ?: "").toString(), null, m.text.take(90), orig)
            "gif" -> {
                val bytes = loadMedia(from, m.mediaId ?: return) ?: throw IllegalStateException("Media not available")
                val id = uploadMedia(to, bytes, m.mime.ifEmpty { "image/gif" })
                send(to, "gif", org.json.JSONObject().put("m", id).put("mime", m.mime).put("w", m.w).put("h", m.h).toString(), null, "GIF", orig)
            }
            "image", "voice" -> {
                val bytes = loadMedia(from, m.mediaId ?: return) ?: throw IllegalStateException("Media not available")
                val id = uploadMedia(to, bytes, if (m.type == "image") "image/jpeg" else "audio/mp4")
                val j = org.json.JSONObject().put("m", id).put("c", m.text)
                if (m.type == "voice") { j.put("d", m.dur); j.put("w", org.json.JSONArray(m.wave)) }
                send(to, m.type, j.toString(), null, if (m.type == "image") "Photo" else "Voice message", orig)
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
        try { db.getReference("contact_info/$uid/phone").setValue(raw.filter { it.isDigit() || it == '+' }.take(20)).await() } catch (_: Exception) {}
        db.getReference("users/$uid/phoneKey").setValue(key).await()
        if (privacyOf("find", "all") == "nobody") { db.getReference("phones/$key").removeValue(); return }
        try { db.getReference("phones/$key").setValue(uid).await() } catch (_: Exception) { return }
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

    suspend fun setPerm(kind: Kind, id: String, key: String, v: Boolean) { db.getReference("${if (kind == Kind.GROUP) "groups" else "channels"}/$id/perms/$key").setValue(v).await() }
    suspend fun setSlow(kind: Kind, id: String, secs: Int) { db.getReference("${if (kind == Kind.GROUP) "groups" else "channels"}/$id/slow").setValue(secs).await() }
    suspend fun setChannelPrivate(cid: String, v: Boolean) { db.getReference("channels/$cid/priv").setValue(v).await(); try { publishWebChannel(cid) } catch (_: Exception) {} }
    suspend fun setChannelAdmin(cid: String, who: String, on: Boolean) { db.getReference("channels/$cid/admins/$who").setValue(if (on) true else null).await() }
    /** Followers of a channel (only the owner may read this list). */
    suspend fun subscribers(cid: String): List<String> = db.getReference("channel_subs/$cid").get().await().children.mapNotNull { it.key }

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
            try { publishWebChannel(cid) } catch (_: Exception) {}
            cid to null
        } catch (e: Exception) {
            try { db.getReference("channel_handles/$lower").removeValue().await() } catch (_: Exception) {}
            null to (e.message ?: "Could not create channel")
        }
    }

    /** Accepts a handle, @handle, a name, or a link such as wetalks://c/handle or https://…/c/handle. */
    suspend fun searchChannels(q: String): List<Channel> {
        var t = q.trim().lowercase()
        Regex("""(?:wetalks://c/|/c/|t\.me/|wetalks\.app/)([a-z0-9_]{3,32})""").find(t)?.let { t = it.groupValues[1] }
        t = t.removePrefix("@")
        if (t.isEmpty()) {
            val src = db.getReference("channels").orderByChild("subCount").limitToLast(30).get().await()
            return src.children.mapNotNull { parseChannel(it.key!!, it) }.filter { !it.priv }.sortedByDescending { it.subCount }
        }
        val end = t + "\uf8ff"
        val byHandle = db.getReference("channels").orderByChild("handleLower").startAt(t).endAt(end).limitToFirst(20).get().await()
        val byName = try { db.getReference("channels").orderByChild("nameLower").startAt(t).endAt(end).limitToFirst(20).get().await().children.toList() } catch (e: Exception) { emptyList() }
        val seen = HashSet<String>()
        return (byHandle.children.toList() + byName).mapNotNull { c -> if (seen.add(c.key!!)) parseChannel(c.key!!, c)?.takeIf { !it.priv || it.owner == uid } else null }
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
        try { publishWebChannel(cid) } catch (_: Exception) {}
    }
}
