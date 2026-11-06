package com.kafisir.wetalks.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.SecureRandom

/**
 * Sending and receiving video, audio and document files (max 30 MB).
 *  - every file gets its own random key; the file is encrypted in 1 MB pieces straight to disk (so a 30 MB file never sits in memory)
 *  - the key travels inside the end-to-end encrypted message, so the server only ever holds ciphertext
 *  - only a short list of known, harmless file types is accepted; names are cleaned; the start of the file must look like what it claims to be
 */
object FileKit {
    const val MAX_BYTES = 30L * 1024 * 1024

    val VIDEO = setOf("mp4", "m4v", "3gp", "mkv", "webm", "mov")
    val AUDIO = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr")
    val DOC = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt", "ods", "odp", "json", "xml", "md", "epub", "vcf", "zip", "rar", "7z", "tar", "gz")

    private val MIME = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "3gp" to "video/3gpp", "mkv" to "video/x-matroska", "webm" to "video/webm", "mov" to "video/quicktime",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav", "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg", "flac" to "audio/flac", "amr" to "audio/amr",
        "pdf" to "application/pdf", "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation", "txt" to "text/plain", "csv" to "text/csv", "rtf" to "application/rtf",
        "odt" to "application/vnd.oasis.opendocument.text", "ods" to "application/vnd.oasis.opendocument.spreadsheet", "odp" to "application/vnd.oasis.opendocument.presentation",
        "json" to "application/json", "xml" to "text/xml", "md" to "text/markdown", "epub" to "application/epub+zip", "vcf" to "text/x-vcard", "zip" to "application/zip",
        "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed", "tar" to "application/x-tar", "gz" to "application/gzip")

    fun mimeOf(ext: String) = MIME[ext.lowercase()] ?: "application/octet-stream"
    fun extOf(name: String) = name.substringAfterLast('.', "").lowercase()
    fun kindOf(ext: String) = when (ext) { in VIDEO -> "video"; in AUDIO -> "audio"; in DOC -> "file"; else -> null }

    /** A safe display / file name: no folders, no control or odd characters, a sane length, and the extension kept. */
    fun cleanName(raw: String?): String {
        var n = (raw ?: "file").substringAfterLast('/').substringAfterLast('\\').filter { it.code >= 32 && it !in "<>:\"|?*‮‭" }.trim().trimStart('.')
        if (n.isEmpty()) n = "file"
        if (n.length > 80) { val e = extOf(n); n = n.take(80 - e.length - 1).trimEnd('.') + (if (e.isNotEmpty()) ".$e" else "") }
        return n
    }

    class Picked(val name: String, val ext: String, val kind: String, val mime: String, val size: Long)

    /** Throws a message fit for the screen when the file may not be sent. */
    fun check(ctx: Context, uri: Uri, wanted: String?): Picked {
        var name: String? = null; var size = -1L
        try { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0); size = if (c.isNull(1)) -1 else c.getLong(1) } } } catch (_: Exception) {}
        val clean = cleanName(name ?: uri.lastPathSegment)
        val ext = extOf(clean)
        val kind = kindOf(ext) ?: throw IllegalArgumentException(if (ext.isEmpty()) "This file has no type, so it can't be sent" else ".$ext files can't be sent for safety. Send a PDF, Office file, text, zip, audio or video.")
        if (wanted != null && kind != wanted) throw IllegalArgumentException("Please pick a " + (if (wanted == "file") "document" else wanted) + " file")
        if (size > MAX_BYTES) throw IllegalArgumentException("Files can be at most 30 MB (this one is " + (size / 1024 / 1024) + " MB)")
        if (size == 0L) throw IllegalArgumentException("That file is empty")
        return Picked(clean, ext, kind, mimeOf(ext), size)
    }

    private val rnd = SecureRandom()
    fun newKey() = ByteArray(32).also { rnd.nextBytes(it) }
    private fun stream(key: ByteArray) = AesGcmHkdfStreaming(key, "HmacSHA256", 32, 1 shl 20, 0)
    private val AAD = "wetalks-file-v1".toByteArray()

    /** Reads the picked file, checks its start looks right, encrypts it to [dst]. Returns the plain size. */
    fun encryptPicked(ctx: Context, uri: Uri, p: Picked, key: ByteArray, dst: File, onProgress: (Float) -> Unit = {}): Long {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Could not read that file")
        return encryptStream(input, p, key, dst, onProgress)
    }

    fun encryptStream(input: InputStream, p: Picked, key: ByteArray, dst: File, onProgress: (Float) -> Unit = {}): Long {
        var total = 0L
        input.use { raw ->
            val head = ByteArray(64); val hn = readFully(raw, head)
            if (hn == 0) throw IllegalArgumentException("That file is empty")
            if (!looksRight(p.ext, head, hn)) throw IllegalArgumentException("This file isn't a real .${p.ext} file")
            FileOutputStream(dst).use { fos ->
                stream(key).newEncryptingStream(fos, AAD).use { enc ->
                    enc.write(head, 0, hn); total = hn.toLong()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = raw.read(buf); if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) { dst.delete(); throw IllegalArgumentException("Files can be at most 30 MB") }
                        enc.write(buf, 0, n)
                        if (p.size > 0) onProgress((total.toFloat() / p.size).coerceIn(0f, 1f))
                    }
                }
            }
        }
        return total
    }

    /** Decrypts a downloaded file. Throws when it was tampered with or the key is wrong. */
    fun decrypt(src: File, key: ByteArray, dst: File, expectedSize: Long, ext: String): File {
        var total = 0L
        FileInputStream(src).use { fis ->
            stream(key).newDecryptingStream(fis, AAD).use { dec ->
                FileOutputStream(dst).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = dec.read(buf); if (n < 0) break; total += n; if (total > MAX_BYTES) { dst.delete(); throw IllegalStateException("File too large") }; out.write(buf, 0, n) }
                }
            }
        }
        if (expectedSize > 0 && total != expectedSize) { dst.delete(); throw IllegalStateException("The file does not match what was sent") }
        val head = ByteArray(64); val hn = FileInputStream(dst).use { readFully(it, head) }
        if (!looksRight(ext, head, hn)) { dst.delete(); throw IllegalStateException("The file is not a real .$ext file") }
        return dst
    }

    private fun readFully(i: InputStream, b: ByteArray): Int { var n = 0; while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) break; n += r }; return n }

    private fun at(b: ByteArray, off: Int, s: String) = b.size >= off + s.length && s.indices.all { b[off + it] == s[it].code.toByte() }

    /** The first bytes must match the claimed type (types without a fixed signature are accepted as long as they are not binary programs). */
    fun looksRight(ext: String, b: ByteArray, n: Int): Boolean {
        val h = b.copyOf(n)
        if (n >= 4 && (at(h, 0, "MZ") || at(h, 0, "\u007FELF") || at(h, 0, "#!") || at(h, 0, "dex\n"))) return false   // programs, never allowed under any name
        return when (ext) {
            "mp4", "m4v", "3gp", "mov", "m4a" -> at(h, 4, "ftyp") || at(h, 4, "moov") || at(h, 4, "mdat") || at(h, 4, "free") || at(h, 4, "wide")
            "mkv", "webm" -> n >= 4 && h[0] == 0x1A.toByte() && h[1] == 0x45.toByte() && h[2] == 0xDF.toByte() && h[3] == 0xA3.toByte()
            "mp3" -> at(h, 0, "ID3") || (n >= 2 && h[0] == 0xFF.toByte() && (h[1].toInt() and 0xE0) == 0xE0)
            "aac" -> n >= 2 && h[0] == 0xFF.toByte() && (h[1].toInt() and 0xF0) == 0xF0 || at(h, 0, "ADIF")
            "wav" -> at(h, 0, "RIFF") && at(h, 8, "WAVE")
            "ogg", "oga", "opus" -> at(h, 0, "OggS")
            "flac" -> at(h, 0, "fLaC")
            "amr" -> at(h, 0, "#!AMR")
            "pdf" -> at(h, 0, "%PDF")
            "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "zip" -> at(h, 0, "PK")
            "doc", "xls", "ppt" -> n >= 4 && h[0] == 0xD0.toByte() && h[1] == 0xCF.toByte() && h[2] == 0x11.toByte() && h[3] == 0xE0.toByte()
            "rtf" -> at(h, 0, "{\\rtf")
            "rar" -> at(h, 0, "Rar!")
            "7z" -> n >= 2 && h[0] == '7'.code.toByte() && h[1] == 'z'.code.toByte()
            "gz" -> n >= 2 && h[0] == 0x1F.toByte() && h[1] == 0x8B.toByte()
            "txt", "csv", "json", "xml", "md", "vcf", "tar" -> h.none { it.toInt() == 0 } || ext == "tar"
            else -> false
        }
    }

    /** Where a decrypted copy lives while it is being viewed. */
    /** Downloaded and sent files are kept (like WhatsApp) so they open instantly and never need downloading twice. Unopened for 30 days = removed. */
    fun cacheDir(ctx: Context) = File(ctx.filesDir, "files").apply { mkdirs() }
    fun cachedFile(ctx: Context, id: String, name: String) = File(File(cacheDir(ctx), id.takeLast(40)).apply { mkdirs() }, cleanName(name))
    fun sweep(ctx: Context) { try { val cut = System.currentTimeMillis() - 30L * 24 * 3600_000L; cacheDir(ctx).walkBottomUp().forEach { if (it.isFile && it.lastModified() < cut) it.delete() else if (it.isDirectory && it.list().isNullOrEmpty() && it != cacheDir(ctx)) it.delete() } } catch (_: Exception) {} }

    /** A copy of a file the person just sent, stored under the server id, so their own video plays without downloading. */
    fun keepSent(ctx: Context, id: String, name: String, uri: Uri) {
        try { val out = cachedFile(ctx, id, name); ctx.contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(out).use { o -> i.copyTo(o) } } } catch (_: Exception) {}
    }

    class VideoInfo(val seconds: Int, val w: Int, val h: Int, val thumb: ByteArray?)
    /** Length, size and a small preview picture of a video (the picture travels inside the encrypted message, like WhatsApp's preview). */
    fun videoInfo(ctx: Context, uri: Uri): VideoInfo {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val dur = (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000
            var w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            var thumb: ByteArray? = null
            val frame = r.getFrameAtTime(minOf(1_000_000L, dur * 500_000L), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame != null) {
                val sc = 400f / maxOf(frame.width, frame.height)
                val bmp = if (sc < 1f) android.graphics.Bitmap.createScaledBitmap(frame, (frame.width * sc).toInt().coerceAtLeast(1), (frame.height * sc).toInt().coerceAtLeast(1), true) else frame
                var q = 70
                do { val bo = java.io.ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, bo); thumb = bo.toByteArray(); q -= 15 } while (thumb!!.size > 28_000 && q > 20)
                if (thumb!!.size > 28_000) thumb = null
            }
            VideoInfo(dur.toInt(), w, h, thumb)
        } catch (e: Exception) { VideoInfo(0, 0, 0, null) } finally { try { r.release() } catch (_: Exception) {} }
    }

    fun size(n: Long): String = when { n >= 1024 * 1024 -> String.format("%.1f MB", n / 1048576.0); n >= 1024 -> (n / 1024).toString() + " KB"; else -> "$n B" }

    /** Copies a file into the phone's Downloads folder (no permission needed on Android 10+). */
    fun saveToDownloads(ctx: Context, f: File, name: String, mime: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime); put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WeTalks") }
            val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
            ctx.contentResolver.openOutputStream(u)!!.use { o -> FileInputStream(f).use { it.copyTo(o) } }
            true
        } else {
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WeTalks").apply { mkdirs() }
            FileInputStream(f).use { i -> FileOutputStream(File(d, name)).use { i.copyTo(it) } }; true
        }
    } catch (e: Exception) { false }
}
