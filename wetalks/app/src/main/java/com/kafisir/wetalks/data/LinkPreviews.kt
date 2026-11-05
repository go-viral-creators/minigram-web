package com.kafisir.wetalks.data

import android.graphics.BitmapFactory
import android.util.Base64
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a small preview (title, description, thumbnail) for the first link in a message.
 * It runs on the SENDER's phone; the card then travels inside the encrypted message, so receivers never contact the site.
 */
object LinkPreviews {
    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 WeTalksBot"

    fun firstUrl(text: String): String? {
        val m = android.util.Patterns.WEB_URL.matcher(text)
        while (m.find()) {
            val u = m.group()
            val before = text.getOrNull(m.start() - 1)
            if (before == '@' || u.contains('@')) continue          // e-mail addresses are not links
            return if (u.startsWith("http", true)) u else "https://$u"
        }
        return null
    }

    private fun open(u: String): HttpURLConnection = (URL(u).openConnection() as HttpURLConnection).apply {
        connectTimeout = 3500; readTimeout = 3500; instanceFollowRedirects = true
        setRequestProperty("User-Agent", ua); setRequestProperty("Accept-Language", "en")
    }

    private fun meta(html: String, prop: String): String? {
        val r1 = Regex("""<meta[^>]+(?:property|name)\s*=\s*["']$prop["'][^>]*?content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        val r2 = Regex("""<meta[^>]+content\s*=\s*["']([^"']*)["'][^>]*?(?:property|name)\s*=\s*["']$prop["']""", RegexOption.IGNORE_CASE)
        return (r1.find(html) ?: r2.find(html))?.groupValues?.get(1)?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim() }?.ifEmpty { null }
    }

    suspend fun fetch(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        try {
            val c = open(url)
            if (c.responseCode !in 200..299 || c.contentType?.contains("html", true) == false) return@withContext null
            val html = c.inputStream.use { it.readNBytesCompat(220_000) }.toString(Charsets.UTF_8)
            val title = meta(html, "og:title") ?: meta(html, "twitter:title")
                ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.let { android.text.Html.fromHtml(it, 0).toString().trim() }
            val desc = meta(html, "og:description") ?: meta(html, "description") ?: meta(html, "twitter:description")
            if (title.isNullOrEmpty() && desc.isNullOrEmpty()) return@withContext null
            var thumb = ""
            (meta(html, "og:image") ?: meta(html, "twitter:image"))?.let { img ->
                try {
                    val abs = URL(URL(c.url.toString()), img).toString()
                    val ic = open(abs)
                    if (ic.responseCode in 200..299) {
                        val bytes = ic.inputStream.use { it.readNBytesCompat(1_500_000) }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            val sc = 260f / maxOf(bmp.width, bmp.height)
                            val out = if (sc < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt().coerceAtLeast(1), (bmp.height * sc).toInt().coerceAtLeast(1), true) else bmp
                            val bo = ByteArrayOutputStream(); out.compress(Bitmap.CompressFormat.JPEG, 65, bo)
                            if (bo.size() < 40_000) thumb = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                        }
                    }
                } catch (_: Exception) {}
            }
            LinkPreview(url, (title ?: "").take(140), (desc ?: "").take(240), thumb)
        } catch (e: Exception) { null }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val bo = ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
        while (total < max) { val n = read(buf, 0, minOf(buf.size, max - total)); if (n <= 0) break; bo.write(buf, 0, n); total += n }
        return bo.toByteArray()
    }
}
