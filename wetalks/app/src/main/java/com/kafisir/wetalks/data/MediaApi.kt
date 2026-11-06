package com.kafisir.wetalks.data

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Photos, voice notes and GIFs live on our own server (Hostinger), not in the database.
 * What is sent is already end-to-end encrypted on the phone, so the server only stores ciphertext.
 * Every call carries the person's Firebase sign-in token; the server checks it and asks the database rules who may use the chat.
 */
object MediaApi {
    const val BASE = "https://wetalks.fireclashpro.com/api/v1"
    fun isRemote(id: String) = id.length == 33 && id[0] == 'x'

    private suspend fun token(force: Boolean): String =
        (FirebaseAuth.getInstance().currentUser ?: throw IllegalStateException("Please sign in again")).getIdToken(force).await().token ?: throw IllegalStateException("Please sign in again")

    private class Reply(val code: Int, val body: ByteArray)

    private suspend fun call(method: String, path: String, body: ByteArray?, mime: String?): Reply {
        var force = false
        repeat(2) {
            val r = withContext(Dispatchers.IO) {
                val c = (URL("$BASE/$path").openConnection() as HttpURLConnection).apply {
                    requestMethod = method; connectTimeout = 15000; readTimeout = 60000
                    setRequestProperty("Authorization", "Bearer " + token(force)); if (mime != null) setRequestProperty("X-Mime", mime)
                    if (body != null) { doOutput = true; setFixedLengthStreamingMode(body.size); setRequestProperty("Content-Type", "application/octet-stream") }
                }
                try {
                    if (body != null) c.outputStream.use { it.write(body) }
                    val code = c.responseCode
                    val data = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                    Reply(code, data)
                } finally { c.disconnect() }
            }
            if (r.code != 401 || force) return r
            force = true     // the sign-in token may just have expired: get a fresh one and try once more
        }
        throw IllegalStateException("Upload failed")
    }

    private fun why(r: Reply): String = try { org.json.JSONObject(String(r.body)).optString("error") } catch (_: Exception) { "" }

    suspend fun upload(chatId: String, bytes: ByteArray, mime: String): String {
        val r = try { call("POST", "media?cid=" + java.net.URLEncoder.encode(chatId, "UTF-8"), bytes, mime) }
        catch (e: java.io.IOException) { throw IllegalStateException("No connection to the media server. Check your internet.") }
        if (r.code != 201) throw IllegalStateException(when (r.code) {
            413 -> "That file is too large"; 429 -> "Too many uploads, try again later"; 507 -> "Your media storage is full"
            403 -> "You are not allowed to send media in this chat"; else -> "Upload failed (" + r.code + (why(r).let { if (it.isEmpty()) "" else ": $it" }) + ")" })
        return org.json.JSONObject(String(r.body)).getString("id")
    }

    /** The encrypted bytes, or null when the file is gone. */
    suspend fun download(id: String): ByteArray? {
        val r = try { call("GET", "media/$id", null, null) } catch (e: java.io.IOException) { return null }
        return if (r.code == 200) r.body else null
    }

    suspend fun delete(id: String) { try { call("DELETE", "media/$id", null, null) } catch (_: Exception) {} }

    /** Uploads an already encrypted file straight from disk (never held in memory), reporting progress. */
    suspend fun uploadFile(chatId: String, f: File, kind: String, ext: String, mime: String, onProgress: (Float) -> Unit): String {
        var force = false
        repeat(2) {
            val (code, body) = try { withContext(Dispatchers.IO) {
                val c = (URL("$BASE/media?cid=" + java.net.URLEncoder.encode(chatId, "UTF-8")).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 15000; readTimeout = 120000; doOutput = true
                    setFixedLengthStreamingMode(f.length()); setRequestProperty("Content-Type", "application/octet-stream")
                    setRequestProperty("Authorization", "Bearer " + token(force)); setRequestProperty("X-Kind", kind); setRequestProperty("X-Mime", mime); setRequestProperty("X-Ext", ext)
                }
                try {
                    FileInputStream(f).use { i -> c.outputStream.use { o ->
                        val buf = ByteArray(64 * 1024); var sent = 0L; val total = f.length().coerceAtLeast(1)
                        while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); sent += n; onProgress((sent.toFloat() / total).coerceIn(0f, 1f)) }
                    } }
                    val code = c.responseCode
                    code to ((if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0))
                } finally { c.disconnect() }
            } } catch (e: java.io.IOException) { throw IllegalStateException("No connection to the media server. Check your internet.") }
            if (code == 401 && !force) { force = true; return@repeat }
            if (code != 201) throw IllegalStateException(when (code) {
                413 -> "That file is too large"; 415 -> "That file type is not allowed"; 429 -> "Too many uploads, try again later"; 507 -> "Your media storage is full"
                403 -> "You are not allowed to send files in this chat"; else -> "Upload failed ($code)" })
            return org.json.JSONObject(String(body)).getString("id")
        }
        throw IllegalStateException("Upload failed")
    }

    /** Downloads the encrypted file to disk. Returns false when it is gone or the connection fails. */
    suspend fun downloadToFile(id: String, dst: File, onProgress: (Float) -> Unit): Boolean {
        var force = false
        repeat(2) {
            val code = try { withContext(Dispatchers.IO) {
                val c = (URL("$BASE/media/$id").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 60000; setRequestProperty("Authorization", "Bearer " + token(force))
                }
                try {
                    val code = c.responseCode
                    if (code == 200) {
                        val total = c.contentLengthLong.coerceAtLeast(1)
                        c.inputStream.use { i -> FileOutputStream(dst).use { o ->
                            val buf = ByteArray(64 * 1024); var got = 0L
                            while (true) { val n = i.read(buf); if (n < 0) break; got += n; if (got > 32L * 1024 * 1024) throw java.io.IOException("too large"); o.write(buf, 0, n); onProgress((got.toFloat() / total).coerceIn(0f, 1f)) }
                        } }
                    }
                    code
                } finally { c.disconnect() }
            } } catch (e: java.io.IOException) { dst.delete(); return false }
            if (code == 401 && !force) { force = true; return@repeat }
            if (code != 200) dst.delete()
            return code == 200
        }
        return false
    }
}
