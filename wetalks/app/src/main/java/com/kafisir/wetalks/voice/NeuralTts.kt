package com.kafisir.wetalks.voice

import android.app.ActivityManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.kafisir.wetalks.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Natural, human-like voices that run on the phone (Supertonic 3, 31 languages, through sherpa-onnx).
 * The voice pack is NOT inside the app: it is downloaded once from our server when the person asks for it, checked with a SHA-256 hash and unpacked safely.
 * Off by default: the normal Android voice stays the default.
 */
object NeuralTts {
    const val BASE = "https://wetalks.fireclashpro.com"
    const val PACK_ID = "supertonic3-int8-v1"
    const val MAX_PACK_BYTES = 300L * 1024 * 1024
    private val REQUIRED = listOf("duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx", "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin")

    enum class Status { Unknown, Missing, Downloading, Ready, TooWeak, Broken }
    var status by mutableStateOf(Status.Unknown); private set
    var progress by mutableFloatStateOf(0f); private set
    var error by mutableStateOf(""); private set
    var enabled by mutableStateOf(false); private set
    var voice by mutableStateOf(0); private set          // 0-4 = Female 1-5, 5-9 = Male 1-5
    var packBytes by mutableStateOf(124_032_822L); private set

    private fun prefs(c: Context) = c.getSharedPreferences("wt_ntts", Context.MODE_PRIVATE)
    fun dir(c: Context) = File(c.filesDir, "tts/supertonic3")

    /** Reads settings and decides what state the voice pack is in. Safe to call at start-up. */
    fun load(c: Context) {
        val p = prefs(c)
        enabled = p.getBoolean("on", false); voice = p.getInt("voice", 0).coerceIn(0, 9)
        val mem = ActivityManager.MemoryInfo().also { (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        status = when {
            p.getBoolean("broken", false) -> { enabled = false; Status.Broken }
            mem.totalMem < 2_800_000_000L -> Status.TooWeak
            installed(c) -> Status.Ready
            else -> Status.Missing
        }
    }
    fun installed(c: Context) = File(dir(c), "ready.json").exists() && REQUIRED.all { File(dir(c), it).let { f -> f.exists() && f.length() > 0 } }
    fun usable() = enabled && status == Status.Ready
    fun setEnabled(c: Context, v: Boolean) { enabled = v && status == Status.Ready; prefs(c).edit().putBoolean("on", enabled).apply() }
    fun setVoice(c: Context, v: Int) { voice = v.coerceIn(0, 9); prefs(c).edit().putInt("voice", voice).apply() }
    val voiceNames = listOf("Female 1", "Female 2", "Female 3", "Female 4", "Female 5", "Male 1", "Male 2", "Male 3", "Male 4", "Male 5")
    fun resetBroken(c: Context) { prefs(c).edit().remove("loading").remove("busy").remove("broken").apply(); lastCrash = ""; load(c) }

    // ------------------------------------------------------------------------------------------ download
    private var dlJob: Job? = null
    fun cancelDownload() { dlJob?.cancel() }

    fun download(c: Context) {
        if (status == Status.Downloading || status == Status.Ready) return
        status = Status.Downloading; progress = 0f; error = ""
        dlJob = Repo.scope.launch(Dispatchers.IO) {
            try {
                val man = JSONObject(httpText("$BASE/models/tts/manifest.json"))
                val pack = validatePack(man)
                packBytes = pack.bytes
                val root = File(c.filesDir, "tts").apply { mkdirs() }
                val free = root.usableSpace
                if (free < pack.bytes + pack.unpackedBytes + 50L * 1024 * 1024) throw IllegalStateException("Not enough free space. You need about " + (pack.bytes + pack.unpackedBytes) / 1048576 + " MB.")
                val part = File(root, "pack.zip.part")
                downloadResumable(pack.url, part, pack.bytes) { progress = it * 0.9f }
                if (!isActive) return@launch
                if (sha256(part) != pack.sha256) { part.delete(); throw IllegalStateException("The download was damaged. Please try again.") }
                progress = 0.92f
                val tmp = File(root, "tts.tmp"); tmp.deleteRecursively()
                extractZip(part, tmp, pack.unpackedBytes + 20L * 1024 * 1024, 2000)
                if (!REQUIRED.all { File(tmp, it).let { f -> f.exists() && f.length() > 0 } }) { tmp.deleteRecursively(); throw IllegalStateException("The voice pack is incomplete") }
                File(tmp, "ready.json").writeText(JSONObject().put("id", pack.id).put("sha256", pack.sha256).toString())
                dir(c).deleteRecursively(); tmp.renameTo(dir(c)); part.delete()
                progress = 1f; status = Status.Ready
                setEnabled(c, true)
            } catch (e: kotlinx.coroutines.CancellationException) { status = Status.Missing; throw e
            } catch (e: Exception) { error = e.message ?: "Download failed"; status = Status.Missing }
        }
    }

    fun remove(c: Context) { stop(); free(); dir(c).deleteRecursively(); File(c.filesDir, "tts/pack.zip.part").delete(); enabled = false; prefs(c).edit().putBoolean("on", false).apply(); status = if (status == Status.TooWeak) status else Status.Missing }

    class Pack(val id: String, val bytes: Long, val unpackedBytes: Long, val sha256: String, val url: String)

    /** The manifest is data from the network: only accept what we expect (our host, a .zip path, sane sizes, a real hash). */
    fun validatePack(man: JSONObject): Pack {
        val arr = man.optJSONArray("packs") ?: throw IllegalStateException("Voice list unavailable")
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            if (p.optString("id") != PACK_ID) continue
            val url = p.optString("url"); val sha = p.optString("sha256").lowercase(); val bytes = p.optLong("bytes"); val un = p.optLong("unpacked_bytes")
            require(url.startsWith("/models/tts/") && url.endsWith(".zip") && !url.contains("..") && !url.contains("//") && url.length < 120) { "Invalid voice pack address" }
            require(Regex("^[0-9a-f]{64}$").matches(sha)) { "Invalid voice pack hash" }
            require(bytes in 1_000_000..MAX_PACK_BYTES && un in bytes..(MAX_PACK_BYTES * 3)) { "Invalid voice pack size" }
            return Pack(PACK_ID, bytes, un, sha, BASE + url)
        }
        throw IllegalStateException("Voice pack not found")
    }

    private fun httpText(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        try { if (c.responseCode != 200) throw IllegalStateException("Server answered " + c.responseCode); return c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8).take(20000) } finally { c.disconnect() }
    }

    private suspend fun downloadResumable(u: String, part: File, total: Long, onProgress: (Float) -> Unit) {
        var tries = 0
        while (true) {
            val have = if (part.exists()) part.length() else 0L
            if (have > total) part.delete()
            if (have == total) { onProgress(1f); return }
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            try {
                val code = c.responseCode
                if (code != 200 && code != 206) throw java.io.IOException("Server answered $code")
                val append = code == 206 && have > 0
                var got = if (append) have else 0L
                c.inputStream.use { i -> FileOutputStream(part, append).use { o ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        got += n; if (got > total + 1024) throw IllegalStateException("The file is larger than expected")
                        o.write(buf, 0, n); onProgress((got.toFloat() / total).coerceIn(0f, 1f))
                        if (!kotlin.coroutines.coroutineContext.isActive) return
                    }
                } }
                if (part.length() >= total) return
                throw java.io.IOException("connection closed early")
            } catch (e: java.io.IOException) {
                if (++tries > 6) throw IllegalStateException("The connection dropped. Check your internet and try again, the download will continue where it stopped.")
                delay(1500L * tries)
            } finally { c.disconnect() }
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(256 * 1024)
        f.inputStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Unpacks a zip safely: no path escaping, no links, a cap on size and number of files. */
    fun extractZip(zip: File, dest: File, maxBytes: Long, maxEntries: Int) {
        dest.mkdirs()
        val base = dest.canonicalPath + File.separator
        var total = 0L; var count = 0
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (++count > maxEntries) throw IllegalStateException("The voice pack has too many files")
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(base) && out.canonicalPath + File.separator != base) throw IllegalStateException("The voice pack contains an unsafe path")
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { o ->
                    val buf = ByteArray(128 * 1024)
                    while (true) { val n = z.read(buf); if (n < 0) break; total += n; if (total > maxBytes) throw IllegalStateException("The voice pack is larger than expected"); o.write(buf, 0, n) }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------ speaking
    /** The 31 languages Supertonic 3 can read. Anything else uses the phone's own voice. */
    private val LANGS = setOf("ar", "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "hi", "hr", "hu", "id", "it", "ja", "ko", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi")
    fun norm(lang: String) = when (lang) { "in" -> "id"; "iw" -> "he"; else -> lang }
    fun supports(lang: String) = norm(lang) in LANGS

    // The engine is native code and lives in its own process (TtsService, ":tts"): a crash there cannot close the app.
    private var svc: android.os.Messenger? = null
    private var bound = false
    private var queued: android.os.Message? = null
    private var doneCb: (() -> Unit)? = null
    private var sayCb: ((String) -> Unit)? = null
    private var appCtx: Context? = null
    var lastCrash by mutableStateOf("")          // what the system recorded when the voice engine process died
        private set
    private val replies by lazy { android.os.Messenger(android.os.Handler(android.os.Looper.getMainLooper()) { m ->
        when (m.what) {
            TtsService.R_STARTED -> {}
            TtsService.R_DONE -> finishCall()
            TtsService.R_ERROR -> { sayCb?.invoke("Natural voice failed. Using your phone's voice next time."); lastCrash = "Engine error: " + (m.data?.getString("t") ?: ""); appCtx?.let { markBroken(it) }; finishCall() }
        }
        true
    }) }
    private val conn by lazy { object : android.content.ServiceConnection {
        override fun onServiceConnected(n: android.content.ComponentName?, b: android.os.IBinder?) {
            svc = android.os.Messenger(b); bound = true
            queued?.let { try { svc?.send(it) } catch (_: Exception) {} }; queued = null
        }
        override fun onServiceDisconnected(n: android.content.ComponentName?) { onEngineDied() }
        override fun onBindingDied(n: android.content.ComponentName?) { onEngineDied() }
    } }

    private fun finishCall() { val d = doneCb; doneCb = null; sayCb = null; appCtx?.let { prefs(it).edit().remove("busy").remove("loading").apply() }; d?.invoke() }

    private fun markBroken(c: Context) { prefs(c).edit().putBoolean("on", false).putBoolean("broken", true).remove("busy").remove("loading").apply(); enabled = false; status = Status.Broken }

    /** The engine process died in the middle of speaking (a native crash). The app is fine: say so, switch the natural voice off and read the reason the system kept. */
    private fun onEngineDied() {
        svc = null; bound = false; queued = null
        val c = appCtx ?: return
        if (doneCb == null) return
        lastCrash = readExitReason(c)
        sayCb?.invoke("The natural voice engine crashed on this phone, so it was switched off. Your phone's voice is used instead.")
        markBroken(c)
        com.kafisir.wetalks.data.CrashLog.note(c, "natural voice engine process died: $lastCrash")
        finishCall()
    }

    /** Android keeps the reason a process ended (signal number and, for native crashes, the tombstone). Pull out the readable bits. */
    private fun readExitReason(c: Context): String {
        if (android.os.Build.VERSION.SDK_INT < 30) return "unknown (needs Android 11+ to read)"
        return try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val e = am.getHistoricalProcessExitReasons(c.packageName, 0, 8).firstOrNull { it.processName.endsWith(":tts") } ?: return "no record"
            val sb = StringBuilder("reason=" + e.reason + " status=" + e.status + " " + (e.description ?: ""))
            if (e.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) try {
                val raw = e.traceInputStream?.use { it.readBytes() } ?: ByteArray(0)
                // the tombstone is a binary record: keep only the printable runs that name a library or function
                val lines = Regex("[\\x20-\\x7e]{8,}").findAll(String(raw, Charsets.ISO_8859_1)).map { it.value }.filter { it.contains(".so") || it.contains("::") || it.contains("signal") || it.contains("Abort") }.distinct().take(14).toList()
                if (lines.isNotEmpty()) sb.append("\n").append(lines.joinToString("\n") { it.take(160) })
            } catch (_: Exception) {}
            sb.toString().take(1500)
        } catch (_: Exception) { "unreadable" }
    }

    private fun send(c: Context, m: android.os.Message) {
        val ctx = c.applicationContext; appCtx = ctx
        val s = svc
        if (s != null) { try { s.send(m); return } catch (_: Exception) { svc = null } }
        queued = m
        if (!bound) ctx.bindService(android.content.Intent(ctx, TtsService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    private fun free() { appCtx?.let { c -> send(c, android.os.Message.obtain(null, TtsService.MSG_FREE)) } }

    fun stop() { try { svc?.send(android.os.Message.obtain(null, TtsService.MSG_STOP)) } catch (_: Exception) {} }
    fun pause() { try { svc?.send(android.os.Message.obtain(null, TtsService.MSG_PAUSE)) } catch (_: Exception) {} }
    fun resume() { try { svc?.send(android.os.Message.obtain(null, TtsService.MSG_RESUME)) } catch (_: Exception) {} }

    /** Reads [text] aloud. Starts playing after the first sentence is ready. [done] is called when it ends or is stopped (also on error). */
    fun speak(c: Context, text: String, lang: String, hinglish: Boolean, say: (String) -> Unit, done: () -> Unit) {
        val ctx = c.applicationContext; appCtx = ctx
        var code = norm(lang)
        var body = prepare(text)
        if (hinglish) { Hinglish.load(ctx); body = Hinglish.toDevanagari(body); code = "hi" }   // romanised Hindi is read in Devanagari
        if (code !in LANGS) { done(); return }
        stop()
        doneCb?.let { it() }          // an earlier reading that was replaced
        doneCb = done; sayCb = say
        lastCrash = ""
        prefs(ctx).edit().putBoolean("busy", true).apply()
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val m = android.os.Message.obtain(null, TtsService.MSG_SPEAK).apply {
            replyTo = replies
            data = android.os.Bundle().apply { putString("dir", dir(ctx).absolutePath + "/"); putStringArrayList("chunks", ArrayList(chunks(body))); putString("lang", code); putInt("sid", voice); putInt("threads", threads) }
        }
        send(ctx, m)
    }

    /** Emoji and symbols confuse the model; links are not read out. */
    fun prepare(t: String): String = t.replace(Regex("https?://\\S+"), " link ").replace(Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Sentence-sized pieces so playback can start early. */
    fun chunks(s: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder()
        for (x in s.split(Regex("(?<=[.!?।])\\s+"))) {
            if (sb.length + x.length > 220 && sb.isNotEmpty()) { out.add(sb.toString().trim()); sb.clear() }
            if (x.length > 220) x.chunked(220).forEach { out.add(it) } else sb.append(x).append(' ')
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.ifEmpty { listOf(s.take(220)) }
    }
}
