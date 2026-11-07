package com.kafisir.wetalks.data

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** The chat's stored choice. [on] null = nothing stored, the default for the chat type applies. */
data class NsfwSetting(val on: Boolean?, val level: NsfwLevel)

/**
 * Runs the picture check on this phone. Privacy:
 *  - nothing leaves the phone for scanning; the picture is already decrypted for display and is only looked at in memory
 *  - private chats are refused before the model is even loaded (and again inside [NsfwLogic.scan])
 *  - the only thing ever sent is a flag (a 0-100 bucket and the message id), by [NsfwAlerts], for groups and channels
 *  - scanning happens after the picture is shown on the RECEIVING phone, never in the send path
 */
object NsfwRuntime {
    private val gate = Semaphore(1)                       // one scan at a time: gentle on low-end phones
    @Volatile private var model: TfliteNsfw? = null
    @Volatile private var tried = false

    /** True when a model file is present. Without it the feature is off and nothing is ever scanned. */
    fun available(c: Context = Repo.app) = TfliteNsfw.present(c)

    private fun loadModel(): NsfwClassifier? {
        if (!tried) synchronized(this) { if (!tried) { model = TfliteNsfw.load(Repo.app); tried = true } }
        return model
    }

    private class BitmapInputs(val src: Bitmap) : NsfwInputs {
        private fun chw(b: Bitmap): FloatArray {
            val s = Bitmap.createScaledBitmap(b, NsfwLogic.SIZE, NsfwLogic.SIZE, true)
            val px = IntArray(NsfwLogic.SIZE * NsfwLogic.SIZE); s.getPixels(px, 0, NsfwLogic.SIZE, 0, 0, NsfwLogic.SIZE, NsfwLogic.SIZE)
            if (s !== b) s.recycle()
            return NsfwLogic.toChw(px)
        }
        override fun full() = chw(src)
        override fun centre(): FloatArray {
            val r = NsfwLogic.centreRect(src.width, src.height)
            val c = Bitmap.createBitmap(src, r[0], r[1], r[2] - r[0], r[3] - r[1])
            return chw(c).also { if (c !== src) c.recycle() }
        }
    }

    /** Scores one picture. The chat kind comes first: a private chat is [NsfwResult.Refused] and no model is loaded. */
    suspend fun scan(kind: Kind, meta: NsfwMeta, level: NsfwLevel, bmp: Bitmap): NsfwResult {
        if (!NsfwLogic.kindAllowed(kind)) return NsfwResult.Refused("private chats are never scanned")
        NsfwLogic.exemption(meta)?.let { return NsfwResult.Skipped(it) }
        if (NsfwLogic.tooSmall(bmp.width, bmp.height)) return NsfwResult.Skipped("too small")
        if (!available()) return NsfwResult.Skipped("no model on this phone")
        return withContext(Dispatchers.Default) {
            gate.withPermit {
                try { NsfwLogic.scan(kind, meta, level, loadModel(), BitmapInputs(bmp)) }
                catch (e: Throwable) { android.util.Log.w("WeTalks", "sensitive-image scan failed: " + e.message); NsfwResult.Skipped("scan failed") }
            }
        }
    }

    // ------------------------------------------------------------------------------------------ remembered answers (so a picture is scored once)
    private val mem = object : android.util.LruCache<String, NsfwScores>(1500) {}
    private fun sp() = Repo.app.getSharedPreferences("wt_nsfw", Context.MODE_PRIVATE)
    fun cached(cid: String, mid: String): NsfwScores? {
        val k = "$cid/$mid"
        mem.get(k)?.let { return it }
        val s = try { sp().getString(k, null) } catch (_: Exception) { null } ?: return null
        val p = s.split(':'); if (p.size != 2) return null
        return NsfwScores((p[0].toIntOrNull() ?: return null) / 100f, (p[1].toIntOrNull() ?: return null) / 100f).also { mem.put(k, it) }
    }
    fun remember(cid: String, mid: String, s: NsfwScores) {
        val k = "$cid/$mid"; mem.put(k, s)
        // only pictures that were at least flagged for the strictest level are kept on disk; the rest are cheap to forget
        if (maxOf(s.full, s.crop) < NsfwLevel.STRICT.flagAt) return
        try {
            val p = sp(); if (p.all.size > 1500) p.edit().clear().apply()
            p.edit().putString(k, "${Math.round(s.full * 100)}:${Math.round(s.crop * 100)}").apply()
        } catch (_: Exception) {}
    }

    /** Kept only for this run: a tap on "Sensitive content" shows that one message on this phone, the next start blurs it again. */
    val revealed = mutableStateMapOf<String, Boolean>()
    fun reveal(cid: String, mid: String) { revealed["$cid/$mid"] = true }
    fun isRevealed(cid: String, mid: String) = revealed["$cid/$mid"] == true
}

/** This person's own switch ("Blur sensitive images in groups and channels"). On by default. Only ever read for groups and channels. */
object NsfwPrefs {
    private fun sp() = Repo.app.getSharedPreferences("wt_ui", Context.MODE_PRIVATE)
    var blur by mutableStateOf(true); private set
    private var loaded = false
    fun ensureLoaded() { if (!loaded) { loaded = true; blur = try { sp().getBoolean("nsfw_blur", true) } catch (_: Exception) { true } } }
    fun setBlurOn(v: Boolean) { ensureLoaded(); blur = v; try { sp().edit().putBoolean("nsfw_blur", v).apply() } catch (_: Exception) {} }
}

/** `chat_settings/<cid>/nsfw`: per group/channel, written by its owner/admins, read by members. Never created for private chats. */
object NsfwSettings {
    private val flows = HashMap<String, MutableStateFlow<NsfwSetting?>>()
    private val refs = ArrayList<Pair<com.google.firebase.database.DatabaseReference, ValueEventListener>>()

    /** null until the first answer arrives. A chat that stores nothing gives NsfwSetting(null, BALANCED). */
    fun flow(cid: String): StateFlow<NsfwSetting?> = synchronized(flows) {
        flows.getOrPut(cid) {
            MutableStateFlow<NsfwSetting?>(null).also { f ->
                val r = Repo.db.getReference("chat_settings/$cid/nsfw")
                val l = object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        f.value = NsfwSetting(s.child("on").getValue(Boolean::class.java), NsfwLevel.of(s.child("lvl").getValue(Long::class.java)?.toInt()))
                    }
                    override fun onCancelled(e: DatabaseError) { if (f.value == null) f.value = NsfwSetting(null, NsfwLevel.DEFAULT) }
                }
                r.addValueEventListener(l); refs.add(r to l)
            }
        }
    }

    fun forget() = synchronized(flows) { refs.forEach { (r, l) -> r.removeEventListener(l) }; refs.clear(); flows.clear() }

    /** Owner/admins only (the server checks). */
    suspend fun save(cid: String, on: Boolean, level: NsfwLevel) {
        val body = mapOf("on" to on, "lvl" to level.code, "by" to Repo.uid, "ts" to ServerValue.TIMESTAMP)
        Repo.db.getReference("chat_settings/$cid/nsfw").setValue(body).await()
    }
}
