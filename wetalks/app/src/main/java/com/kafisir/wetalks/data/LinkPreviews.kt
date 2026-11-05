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

    private fun open(u: String, agent: String = ua): HttpURLConnection = (URL(u).openConnection() as HttpURLConnection).apply {
        connectTimeout = 4000; readTimeout = 4000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", agent); setRequestProperty("Accept-Language", "en"); setRequestProperty("Accept", "text/html,application/json,image/*,*/*")
    }

    private fun meta(html: String, prop: String): String? {
        val r1 = Regex("""<meta[^>]+(?:property|name)\s*=\s*["']$prop["'][^>]*?content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        val r2 = Regex("""<meta[^>]+content\s*=\s*["']([^"']*)["'][^>]*?(?:property|name)\s*=\s*["']$prop["']""", RegexOption.IGNORE_CASE)
        return (r1.find(html) ?: r2.find(html))?.groupValues?.get(1)?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim() }?.ifEmpty { null }
    }

    private const val CRAWLER = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    private fun youtubeId(url: String): String? =
        Regex("""(?:youtu\.be/|youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|embed/|live/)|youtube-nocookie\.com/embed/)([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1)

    private fun jsonField(json: String, key: String): String? = try { org.json.JSONObject(json).optString(key).ifEmpty { null } } catch (e: Exception) { null }

    private fun getText(u: String, ua: String, max: Int = 260_000): Pair<String, String>? {
        val c = open(u, ua)
        if (c.responseCode !in 200..299) return null
        return c.inputStream.use { it.readNBytesCompat(max) }.toString(Charsets.UTF_8) to c.url.toString()
    }

    private fun thumbFrom(url: String): String {
        return try {
            val ic = open(url, ua)
            if (ic.responseCode !in 200..299) return ""
            val bytes = ic.inputStream.use { it.readNBytesCompat(2_000_000) }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
            val sc = 320f / maxOf(bmp.width, bmp.height)
            val out = if (sc < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt().coerceAtLeast(1), (bmp.height * sc).toInt().coerceAtLeast(1), true) else bmp
            val bo = ByteArrayOutputStream(); out.compress(Bitmap.CompressFormat.JPEG, 70, bo)
            if (bo.size() < 45_000) Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP) else ""
        } catch (e: Exception) { "" }
    }

    suspend fun fetch(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        try {
            // YouTube: the watch page is consent-gated, so use the official oEmbed endpoint and the public thumbnail
            youtubeId(url)?.let { id ->
                val o = getText("https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(url, "UTF-8"), ua, 20_000)?.first
                val title = o?.let { jsonField(it, "title") }
                val author = o?.let { jsonField(it, "author_name") }
                val th = thumbFrom("https://i.ytimg.com/vi/$id/mqdefault.jpg")
                if (title != null || th.isNotEmpty()) return@withContext LinkPreview(url, (title ?: "YouTube").take(140), (author ?: "YouTube").take(100), th)
            }
            // everything else (Instagram, news, blogs …): read the Open Graph tags the way social crawlers do
            var got = getText(url, CRAWLER)
            var html = got?.first ?: ""
            fun og(p: String) = meta(html, p)
            if (html.isEmpty() || (og("og:image") == null && og("og:title") == null)) { got = getText(url, ua) ?: got; html = got?.first ?: html }
            if (html.isEmpty()) return@withContext null
            val base = got?.second ?: url
            val title = og("og:title") ?: og("twitter:title")
                ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.let { android.text.Html.fromHtml(it, 0).toString().trim() }
            val desc = og("og:description") ?: og("description") ?: og("twitter:description")
            val img = og("og:image") ?: og("og:image:url") ?: og("og:image:secure_url") ?: og("twitter:image") ?: og("twitter:image:src")
            val thumb = if (img != null) try { thumbFrom(URL(URL(base), img.replace("&amp;", "&")).toString()) } catch (e: Exception) { "" } else ""
            if (title.isNullOrEmpty() && desc.isNullOrEmpty() && thumb.isEmpty()) return@withContext null
            LinkPreview(url, (title ?: "").take(140), (desc ?: "").take(240), thumb)
        } catch (e: Exception) { null }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val bo = ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
        while (total < max) { val n = read(buf, 0, minOf(buf.size, max - total)); if (n <= 0) break; bo.write(buf, 0, n); total += n }
        return bo.toByteArray()
    }
}
