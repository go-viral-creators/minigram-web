package com.kafisir.wetalks.data

import android.content.Context
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Cloud copies of the backup files. Only Google Drive exists (see [DriveCloudBackup]). Backup files are already encrypted (if the person chose a passphrase) before they get here. */
interface CloudBackup {
    class Item(val id: String, val name: String, val size: Long, val created: String)
    class Quota(val used: Long, val limit: Long)
    /** Throws [CloudException] with a message that can be shown as it is. */
    suspend fun list(): List<Item>
    suspend fun upload(file: File, name: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): String
    suspend fun download(id: String, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> })
    suspend fun delete(id: String)
    suspend fun quota(): Quota?
}

class CloudException(message: String, val notSetUp: Boolean = false, val retry: Boolean = true) : IOException(message)

/** Gives an OAuth access token for the Drive "appdata" scope. */
interface DriveTokens { suspend fun token(): String }

/**
 * Google Drive `appDataFolder` through the Drive REST API v3 (plain HTTPS, no Google client library). Files in that folder are hidden from the
 * person's Drive and only this app can read them. Upload uses the resumable protocol (`uploadType=resumable`, 8 MiB chunks, the session address is
 * kept so an interrupted upload continues from the byte Drive already has).
 *
 * STATUS: written against the public Drive documentation and tested only against a local fake server ([DriveRestTest]). It has never talked to
 * the real Google Drive, because the Google Cloud project is not set up for it yet (see BACKUP_NOTES.md).
 */
class DriveRest(private val tokens: DriveTokens, private val api: String = "https://www.googleapis.com/drive/v3", private val upload: String = "https://www.googleapis.com/upload/drive/v3",
                private val sessions: SessionStore = SessionStore.None, private val chunk: Int = 8 shl 20, private val backoffMs: Long = 1000) : CloudBackup {
    /** Remembers the address of an unfinished upload (survives the app being killed). */
    interface SessionStore { fun get(key: String): String?; fun put(key: String, uri: String?)
        object None : SessionStore { override fun get(key: String): String? = null; override fun put(key: String, uri: String?) {} } }

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var last: CloudException? = null
        for (i in 0 until 4) {
            try { return block() } catch (e: CloudException) { if (!e.retry) throw e; last = e; if (backoffMs > 0) kotlinx.coroutines.delay(backoffMs shl i) }
        }
        throw last!!
    }

    private fun fail(code: Int, body: String): CloudException = when (code) {
        401 -> CloudException("Google Drive refused the sign-in. Connect your Google account again.", retry = false)
        403 -> if ("storageQuotaExceeded" in body || "quota" in body.lowercase()) CloudException("Your Google Drive is full.", retry = false) else if ("rateLimit" in body || "userRateLimit" in body) CloudException("Google Drive is busy, trying again.") else CloudException("Google Drive did not allow this (403). Check that the Drive API is enabled for this app.", retry = false)
        404 -> CloudException("Not found on Google Drive.", retry = false)
        429 -> CloudException("Google Drive is busy, trying again.")
        in 500..599 -> CloudException("Google Drive had a problem ($code), trying again.")
        else -> CloudException("Google Drive error $code.", retry = false)
    }

    private fun HttpURLConnection.text(): String = (if (responseCode >= 400) errorStream else inputStream)?.bufferedReader()?.use { it.readText() } ?: ""

    private fun open(url: String, method: String, token: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 20_000; readTimeout = 60_000; setRequestProperty("Authorization", "Bearer $token")
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    override suspend fun list(): List<CloudBackup.Item> = retrying {
        val out = ArrayList<CloudBackup.Item>(); var page: String? = null
        do {
            val c = open("$api/files?spaces=appDataFolder&pageSize=100&fields=${enc("nextPageToken,files(id,name,size,createdTime)")}" + (page?.let { "&pageToken=${enc(it)}" } ?: ""), "GET", tokens.token())
            val body = c.text(); if (c.responseCode != 200) throw fail(c.responseCode, body)
            val j = JSONObject(body); val a = j.optJSONArray("files")
            if (a != null) for (i in 0 until a.length()) { val f = a.getJSONObject(i); out.add(CloudBackup.Item(f.getString("id"), f.getString("name"), f.optString("size", "0").toLongOrNull() ?: 0, f.optString("createdTime"))) }
            page = j.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        out
    }

    override suspend fun quota(): CloudBackup.Quota? = try {
        retrying {
            val c = open("$api/about?fields=storageQuota", "GET", tokens.token()); val body = c.text(); if (c.responseCode != 200) throw fail(c.responseCode, body)
            val q = JSONObject(body).getJSONObject("storageQuota"); CloudBackup.Quota(q.optString("usage", "0").toLong(), q.optString("limit", "0").toLong())
        }
    } catch (e: CloudException) { null }

    override suspend fun delete(id: String) { retrying { val c = open("$api/files/${enc(id)}", "DELETE", tokens.token()); val b = c.text(); if (c.responseCode !in 200..299 && c.responseCode != 404) throw fail(c.responseCode, b) } }

    override suspend fun download(id: String, dest: File, onProgress: (Long, Long) -> Unit) {
        retrying {
            val have = if (dest.exists()) dest.length() else 0L
            val c = open("$api/files/${enc(id)}?alt=media", "GET", tokens.token()); if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code != 200 && code != 206) throw fail(code, c.text())
            val total = (c.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L) + (if (code == 206) have else 0L)
            var got = if (code == 206) have else 0L
            java.io.FileOutputStream(dest, code == 206).use { o -> c.inputStream.use { i -> val b = ByteArray(64 * 1024); while (true) { val r = i.read(b); if (r < 0) break; o.write(b, 0, r); got += r; onProgress(got, total) } } }
        }
    }

    /** Resumable upload. Returns the Drive file id. */
    override suspend fun upload(file: File, name: String, onProgress: (Long, Long) -> Unit): String {
        val total = file.length()
        val key = name + "/" + total
        return retrying {
            var uri = sessions.get(key)
            var sent = 0L
            if (uri != null) {                                      // ask Drive how much of the earlier attempt it kept
                val q = (URL(uri).openConnection() as HttpURLConnection).apply { requestMethod = "PUT"; connectTimeout = 20_000; readTimeout = 60_000; setRequestProperty("Content-Range", "bytes */$total"); setRequestProperty("Content-Length", "0"); doOutput = true; setFixedLengthStreamingMode(0) }
                q.outputStream.close()
                when (q.responseCode) {
                    200, 201 -> { val id = JSONObject(q.text()).getString("id"); sessions.put(key, null); return@retrying id }
                    308 -> sent = rangeEnd(q) + 1
                    else -> { sessions.put(key, null); uri = null }   // expired (404 / 410) or unknown: start again
                }
            }
            if (uri == null) {
                val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put("appDataFolder")).toString().toByteArray()
                val c = open("$upload/files?uploadType=resumable", "POST", tokens.token()).apply {
                    doOutput = true; setRequestProperty("Content-Type", "application/json; charset=UTF-8"); setRequestProperty("X-Upload-Content-Type", "application/octet-stream"); setRequestProperty("X-Upload-Content-Length", total.toString()); setFixedLengthStreamingMode(meta.size)
                }
                c.outputStream.use { it.write(meta) }
                if (c.responseCode != 200) throw fail(c.responseCode, c.text())
                uri = c.getHeaderField("Location") ?: throw CloudException("Google Drive gave no upload address.")
                sessions.put(key, uri); sent = 0L
            }
            java.io.RandomAccessFile(file, "r").use { raf ->
                while (true) {
                    val n = minOf(chunk.toLong(), total - sent).toInt()
                    val buf = ByteArray(n); raf.seek(sent); raf.readFully(buf)
                    val c = (URL(uri).openConnection() as HttpURLConnection).apply {
                        requestMethod = "PUT"; connectTimeout = 20_000; readTimeout = 120_000; doOutput = true; setFixedLengthStreamingMode(n)
                        setRequestProperty("Content-Range", if (n == 0) "bytes */$total" else "bytes $sent-${sent + n - 1}/$total")
                    }
                    c.outputStream.use { it.write(buf) }
                    when (c.responseCode) {
                        200, 201 -> { val id = JSONObject(c.text()).getString("id"); sessions.put(key, null); onProgress(total, total); return@retrying id }
                        308 -> { sent = rangeEnd(c) + 1; onProgress(sent, total) }
                        404, 410 -> { sessions.put(key, null); throw CloudException("The upload expired, starting again.") }
                        else -> throw fail(c.responseCode, c.text())
                    }
                }
            }
            throw CloudException("Google Drive did not confirm the upload.")
        }
    }

    // Drive answers 308 with "Range: bytes=0-N" (the bytes it has), or no Range header when it has nothing yet
    private fun rangeEnd(c: HttpURLConnection): Long = c.getHeaderField("Range")?.substringAfter('-')?.toLongOrNull() ?: -1L
}

/**
 * The Google sign-in for Drive. Until the owner has set up the Google Cloud project (BACKUP_NOTES.md) it deliberately does nothing but report that.
 * [SETUP_DONE] must be changed to true in the same change that adds the OAuth client; then [token] asks Google Play services for an access token
 * for the `drive.appdata` scope. If Google needs the person to agree first, [needsConsent] holds the pending dialog for the screen to start.
 */
object DriveAuth : DriveTokens {
    const val SETUP_DONE = false
    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    const val NOT_SET_UP = "Google Drive is not set up for this app yet"
    @Volatile var needsConsent: android.app.PendingIntent? = null
    private lateinit var app: Context
    fun init(c: Context) { app = c.applicationContext }

    override suspend fun token(): String {
        if (!SETUP_DONE) throw CloudException(NOT_SET_UP, notSetUp = true, retry = false)
        try {
            val req = com.google.android.gms.auth.api.identity.AuthorizationRequest.builder().setRequestedScopes(listOf(com.google.android.gms.common.api.Scope(SCOPE))).build()
            val r = com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(app).authorize(req).await()
            if (r.hasResolution()) { needsConsent = r.pendingIntent; throw CloudException("Allow WeTalks to save its backup in your Google Drive (open Chat backup and tap Connect).", retry = false) }
            return r.accessToken ?: throw CloudException("Google did not give an access token.", retry = false)
        } catch (e: CloudException) { throw e }
        catch (e: Exception) { throw CloudException(NOT_SET_UP + " (" + (e.message ?: e.javaClass.simpleName).take(80) + ")", notSetUp = true, retry = false) }
    }
}

/** Uploads local backup files that Drive does not have yet. */
object CloudSync {
    fun cloud(): CloudBackup = DriveRest(DriveAuth, sessions = PrefSessions)

    private object PrefSessions : DriveRest.SessionStore {
        private fun sp() = Repo.app.getSharedPreferences("wt_backup_cloud", Context.MODE_PRIVATE)
        override fun get(key: String) = sp().getString("s_$key", null)
        override fun put(key: String, uri: String?) { sp().edit().apply { if (uri == null) remove("s_$key") else putString("s_$key", uri) }.apply() }
    }

    /** Returns a short message to add to the status line ("" when nothing was done). */
    suspend fun upload(ctx: Context): String {
        return try {
            DriveAuth.init(ctx)
            val c = cloud(); val have = c.list().map { it.name }.toSet()
            var n = 0
            for (l in BackupStore.local(ctx).sortedBy { it.manifest.created }) {
                if (l.file.name in have) continue
                c.upload(l.file, l.file.name) { done, total -> BackupStore.progress.value = BackupEngine.Progress("Uploading to Google Drive", 0, 1, 0, done) }
                n++
            }
            if (n > 0) "Uploaded $n file(s) to Google Drive." else ""
        } catch (e: CloudException) { "Google Drive: " + e.message }
        catch (e: Exception) { "Google Drive: " + Repo.explain(e, "Uploading to Google Drive") }
    }
}
