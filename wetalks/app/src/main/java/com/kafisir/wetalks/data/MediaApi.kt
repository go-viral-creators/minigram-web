package com.kafisir.wetalks.data

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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
}
