package com.kafisir.wetalks.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Data & storage settings: disk cache for downloaded media, auto-download rules, upload quality and data-use counters. */
object DataPrefs {
    var cacheLimitMb by mutableIntStateOf(250); private set            // 0 = unlimited
    var cleanDays by mutableIntStateOf(0); private set                 // 0 = never
    var autoPhotos by mutableStateOf("always"); private set            // always | wifi | never
    var saver by mutableStateOf(false); private set                    // send smaller photos
    /** Per-network auto-download rules (Wi-Fi / mobile / roaming); see [MediaPolicy]. [autoPhotos] is kept only so old saved choices carry over. */
    var autoRules by mutableStateOf(MediaPolicy.defaults()); private set
    var down by mutableLongStateOf(0L); private set
    var up by mutableLongStateOf(0L); private set
    private lateinit var app: Context
    private fun sp() = app.getSharedPreferences("wt_data", Context.MODE_PRIVATE)
    private val dir get() = File(app.cacheDir, "media").apply { mkdirs() }

    fun load(c: Context) {
        app = c.applicationContext
        val s = sp()
        cacheLimitMb = s.getInt("limit", 250); cleanDays = s.getInt("clean", 0); autoPhotos = s.getString("auto", "always") ?: "always"; saver = s.getBoolean("saver", false)
        down = s.getLong("down", 0); up = s.getLong("up", 0)
        autoRules = if (s.contains("auto_rules")) MediaPolicy.decode(s.getString("auto_rules", null)) else MediaPolicy.migrateLegacy(if (s.contains("auto")) autoPhotos else null)
        MediaNet.start(app); MediaLedger.init(app)
        Thread { autoClean() }.start()
    }
    fun setLimit(v: Int) { cacheLimitMb = v; sp().edit().putInt("limit", v).apply(); Thread { trim() }.start() }
    fun setClean(v: Int) { cleanDays = v; sp().edit().putInt("clean", v).apply(); Thread { autoClean() }.start() }
    fun saveAutoRules(v: MediaPolicy.Settings) { autoRules = v; sp().edit().putString("auto_rules", MediaPolicy.encode(v)).apply() }
    fun setAuto(v: String) { autoPhotos = v; sp().edit().putString("auto", v).apply() }
    fun updateSaver(v: Boolean) { saver = v; sp().edit().putBoolean("saver", v).apply() }
    fun resetUsage() { down = 0; up = 0; sp().edit().putLong("down", 0).putLong("up", 0).apply() }
    fun addDown(n: Long) { down += n; sp().edit().putLong("down", down).apply() }
    fun addUp(n: Long) { up += n; sp().edit().putLong("up", up).apply() }

    // ---- disk cache (the server's still-encrypted text, so nothing readable is ever stored)
    private fun f(id: String) = File(dir, "m_" + id.filter { it.isLetterOrDigit() || it == '-' || it == '_' })
    fun getDisk(id: String): String? = try { f(id).takeIf { it.exists() }?.also { it.setLastModified(System.currentTimeMillis()) }?.readText() } catch (e: Exception) { null }
    fun hasDisk(id: String) = f(id).exists() || hasPlain(id)

    // ---- the photo / voice file itself, kept inside the app's private storage once it has been opened (or was sent from this phone).
    // The chat then shows it from this file: no download, no decryption, no waiting, the same every time. Cleared by the cache limit / "Clear cache".
    private fun pf(id: String) = File(dir, "p_" + id.filter { it.isLetterOrDigit() || it == '-' || it == '_' })
    fun hasPlain(id: String) = ::app.isInitialized && pf(id).exists()
    fun getPlain(id: String): ByteArray? = try { pf(id).takeIf { it.exists() }?.also { it.setLastModified(System.currentTimeMillis()) }?.readBytes() } catch (e: Exception) { null }
    fun putPlain(id: String, bytes: ByteArray) { try { val t = File(dir, "p_tmp_" + System.nanoTime()); t.writeBytes(bytes); if (!t.renameTo(pf(id))) t.delete(); trim() } catch (_: Exception) {} }
    fun putDisk(id: String, s: String) { try { f(id).writeText(s); trim() } catch (_: Exception) {} }
    fun diskSize(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
    fun clearDisk() { dir.listFiles()?.forEach { it.delete() } }
    fun otherSize(): Long = app.cacheDir.walkTopDown().filter { it.isFile && it.parentFile?.name != "media" }.sumOf { it.length() }
    fun clearOther() { app.cacheDir.listFiles()?.forEach { if (it.name != "media") it.deleteRecursively() } }

    private fun trim() {
        if (cacheLimitMb <= 0) return
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }; val max = cacheLimitMb * 1048576L
        for (x in files) { if (total <= max) break; total -= x.length(); x.delete() }
    }
    private fun autoClean() {
        if (cleanDays <= 0) return
        val cut = System.currentTimeMillis() - cleanDays * 86_400_000L
        dir.listFiles()?.forEach { if (it.lastModified() < cut) it.delete() }
    }

    /** Should this media message download by itself right now? [size] <= 0 = unknown. Reads Compose state, so the caller redraws when the network or the rules change. */
    fun autoVerdict(type: MediaPolicy.Type, size: Long = 0, cached: Boolean = false, mine: Boolean = false): MediaPolicy.Verdict =
        MediaPolicy.decide(type, size, MediaNet.state, autoRules, cached, mine)

    /** May photos / GIFs download by themselves right now? (kept for older callers) */
    fun mayAutoDownload(): Boolean = autoVerdict(MediaPolicy.Type.PHOTO).auto
}
