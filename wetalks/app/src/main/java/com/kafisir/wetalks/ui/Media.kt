package com.kafisir.wetalks.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream

object Pics {
    /** Decodes a picked image and scales it so the longest side is [max] px; returns JPEG bytes. */
    fun jpegFrom(ctx: Context, uri: Uri, max: Int, quality: Int): Triple<ByteArray, Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > max * 2) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        val scale = max.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val bo = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality, bo)
        return Triple(bo.toByteArray(), out.width, out.height)
    }

    /** Square avatar thumbnail as base64 (about 10-20 KB). */
    fun avatarB64(ctx: Context, uri: Uri): String? {
        val (bytes, _, _) = jpegFrom(ctx, uri, 256, 80) ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Down-sampled decode for grids and thumbnails. */
    fun decodeSampled(bytes: ByteArray, target: Int): ImageBitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= target) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
    }

    // decoded photos kept in memory: chats re-draw constantly while scrolling, and decoding or downloading again each time makes them blink
    private val bmpCache = object : android.util.LruCache<String, ImageBitmap>(48 * 1024 * 1024) { override fun sizeOf(k: String, v: ImageBitmap) = v.width * v.height * 4 }
    fun cachedBmp(id: String): ImageBitmap? = bmpCache.get(id)
    fun putBmp(id: String, b: ImageBitmap) { bmpCache.put(id, b) }

    fun decode(bytes: ByteArray): ImageBitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    fun decodeB64(s: String): ImageBitmap? = if (s.isEmpty()) null else try { decode(Base64.decode(s, Base64.NO_WRAP)) } catch (e: Exception) { null }
    fun asset(ctx: Context, name: String): ImageBitmap? = try { ctx.assets.open("stickers/$name.webp").use { BitmapFactory.decodeStream(it)?.asImageBitmap() } } catch (e: Exception) { null }
}

object StickerPacks {
    val moods = listOf("face_happy", "face_laugh", "face_love", "face_cool", "face_cry", "face_angry", "face_sleepy", "face_wow", "face_wink", "face_party", "face_kiss", "face_shock", "face_star", "face_sad")
    val reactions = listOf("hand_like", "big_heart", "big_star")
    val words = listOf("word_ok", "word_lol", "word_thanks", "word_wow_t", "word_haan", "word_nahi", "word_chai", "word_party_t", "word_love_t", "word_gn", "word_gm", "word_omw", "word_sahi", "word_arre")
}

object Gallery {
    /** Saves image bytes into Pictures/WeTalks so it shows up in the phone's gallery. */
    fun save(ctx: Context, bytes: ByteArray, mime: String = "image/jpeg"): Boolean = try {
        val ext = when { mime.contains("png") -> "png"; mime.contains("gif") -> "gif"; mime.contains("webp") -> "webp"; else -> "jpg" }
        val name = "WeTalks_${System.currentTimeMillis()}.$ext"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val v = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name); put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WeTalks"); put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            v.clear(); v.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0); ctx.contentResolver.update(uri, v, null, null)
        } else {
            val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "WeTalks"); dir.mkdirs()
            val f = java.io.File(dir, name); f.writeBytes(bytes)
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(mime), null)
        }
        true
    } catch (e: Exception) { false }
}
