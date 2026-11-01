package com.silu.minigram;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches a rich link preview (title/description/image/site) for a URL —
 * powers the WhatsApp/Telegram-style preview cards under messages and
 * channel posts.
 *
 * No new library dependency — HttpURLConnection (built into Android) plus
 * regex extraction of standard Open Graph / <title> / <meta
 * name="description"> tags, which is exactly what these tags exist for and
 * what every major site already publishes so previews work.
 *
 * ── WHY PREVIEWS USED TO FAIL ON MOST BIG SITES ──────────────────────────
 * The old version read only the first 64KB of a page and parsed that. That
 * silently broke every modern JS-heavy site, because they inline hundreds
 * of KB of script BEFORE the meta tags. Measured on the real pages:
 *
 *     github.com                    og tags at ~4KB      → worked
 *     play.google.com (app page)    og tags at ~934KB    → never found
 *                                   (page is 1.17MB total)
 *
 * So Play Store, YouTube, Facebook, Instagram etc. all returned "no
 * preview" — not because they lack the tags, but because the reader gave
 * up ~900KB too early. That's why links rendered as bare blue text.
 *
 * Now it STREAMS and re-scans as it goes, stopping the moment it has what
 * it needs. Normal sites still finish in a few KB (github: 32KB); only the
 * genuinely huge pages read further, capped at MAX_BYTES.
 *
 * ── oEmbed FAST PATH ─────────────────────────────────────────────────────
 * Some hosts publish a tiny oEmbed JSON endpoint. Using it is far better
 * than downloading a megabyte of HTML: one small request, no scraping, and
 * no rate-limit page. YouTube in particular frequently answers plain page
 * requests with HTTP 429, so scraping it is unreliable by nature.
 *
 * Results (including failures) are cached in memory per app session so the
 * same URL is never fetched twice.
 */
public class LinkPreviewHelper {

    public interface PreviewCallback {
        /** preview is null if nothing usable could be found/fetched — callers should just skip showing a card. */
        void onPreviewReady(LinkPreview preview);
    }

    public static class LinkPreview {
        public String title;
        public String description;
        public String imageUrl;
        public String siteName;
        public String sourceUrl;
    }

    private static final HashMap<String, LinkPreview> cache = new HashMap<>();
    private static final ExecutorService executor = Executors.newFixedThreadPool(2);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Hard ceiling on how much of a page we'll read. Play Store's app
     *  pages put their og tags around 934KB, so a smaller cap silently
     *  loses those previews; the early-exit below means ordinary sites
     *  never get anywhere near this. */
    private static final int MAX_BYTES  = 1_400_000;
    private static final int CHUNK      = 16384;
    private static final int MAX_REDIRECTS = 5;

    private static final String UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    /** First http(s) URL found in the text, or null. Public so adapters can
     *  check "does this message have a link" before deciding to fetch. */
    public static String extractFirstUrl(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = android.util.Patterns.WEB_URL.matcher(text);
        while (m.find()) {
            String candidate = m.group();
            // Patterns.WEB_URL also matches bare domains and things like
            // "example.com" with no scheme; only real http(s) links can be
            // fetched, so skip anything else rather than failing later.
            if (candidate.startsWith("http://") || candidate.startsWith("https://")) return candidate;
        }
        return null;
    }

    public static void fetch(String url, PreviewCallback callback) {
        if (url == null || url.isEmpty()) { callback.onPreviewReady(null); return; }

        synchronized (cache) {
            if (cache.containsKey(url)) {
                callback.onPreviewReady(cache.get(url));
                return;
            }
        }

        executor.execute(() -> {
            LinkPreview preview = _buildPreview(url);
            synchronized (cache) { cache.put(url, preview); } // cache failures too — never retry a dead URL this session
            mainHandler.post(() -> callback.onPreviewReady(preview));
        });
    }

    private static LinkPreview _buildPreview(String url) {
        LinkPreview viaOEmbed = _tryOEmbed(url);
        if (viaOEmbed != null) return viaOEmbed;
        LinkPreview scraped = _fetchAndParse(url);
        if (scraped != null) return scraped;
        return _fallbackFromUrl(url);
    }

    /**
     * Last resort when a site gives us nothing usable.
     *
     * Some sites simply will not serve Open Graph tags to anyone without a
     * login — Instagram and Facebook post pages are the main ones; they
     * answer every request (normal browser UA and crawler UA alike) with a
     * login/JS wall containing no meta tags at all. Verified against real
     * Instagram post URLs: no og:title, no og:image, for any User-Agent.
     * Their public oEmbed endpoints were also locked behind an app token
     * years ago, so there is no key-free way to read them.
     *
     * Rather than render such links as bare blue text, show a minimal card
     * built from the URL itself — the host as the site name and a readable
     * title. That's the same graceful degradation messengers fall back to
     * when metadata can't be read.
     */
    private static LinkPreview _fallbackFromUrl(String urlStr) {
        String host = _hostOf(urlStr);
        if (host == null || host.isEmpty()) return null;
        LinkPreview p = new LinkPreview();
        p.sourceUrl = urlStr;
        p.siteName  = host;

        String pretty = host.startsWith("www.") ? host.substring(4) : host;
        // Capitalise the brand, e.g. "instagram.com" → "Instagram"
        int dot = pretty.indexOf('.');
        String brand = dot > 0 ? pretty.substring(0, dot) : pretty;
        if (!brand.isEmpty()) {
            brand = Character.toUpperCase(brand.charAt(0)) + brand.substring(1);
        }
        p.title = brand;
        p.description = urlStr.length() > 90 ? urlStr.substring(0, 90) + "…" : urlStr;
        return p;
    }

    // ── oEmbed fast path ─────────────────────────────────────────────────

    /** Returns an oEmbed endpoint for hosts that publish one, else null. */
    private static String _oEmbedEndpointFor(String urlStr) {
        String host = _hostOf(urlStr);
        if (host == null) return null;
        host = host.toLowerCase();
        try {
            String enc = URLEncoder.encode(urlStr, "UTF-8");
            if (host.endsWith("youtube.com") || host.endsWith("youtu.be")) {
                return "https://www.youtube.com/oembed?url=" + enc + "&format=json";
            }
            if (host.endsWith("vimeo.com")) {
                return "https://vimeo.com/api/oembed.json?url=" + enc;
            }
            if (host.endsWith("soundcloud.com")) {
                return "https://soundcloud.com/oembed?format=json&url=" + enc;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static LinkPreview _tryOEmbed(String urlStr) {
        String endpoint = _oEmbedEndpointFor(urlStr);
        if (endpoint == null) return null;
        HttpURLConnection conn = null;
        try {
            conn = _open(endpoint);
            if (conn == null) return null;
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream is = conn.getInputStream();
            byte[] buf = new byte[4096];
            int n, total = 0;
            while (total < 200_000 && (n = is.read(buf)) != -1) { out.write(buf, 0, n); total += n; }
            is.close();

            JSONObject json = new JSONObject(out.toString("UTF-8"));
            LinkPreview p = new LinkPreview();
            p.sourceUrl   = urlStr;
            p.title       = _clean(json.optString("title", null));
            p.imageUrl    = json.optString("thumbnail_url", null);
            p.siteName    = _clean(json.optString("provider_name", null));
            // oEmbed has no description field; the uploader/author is the
            // closest useful equivalent and is what YouTube cards show.
            String author = _clean(json.optString("author_name", null));
            p.description = author;
            if (p.siteName == null) p.siteName = _hostOf(urlStr);
            if (p.title == null || p.title.isEmpty()) return null;
            return p;
        } catch (Exception e) {
            return null; // fall through to normal HTML scraping
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── Generic Open Graph scraping ──────────────────────────────────────

    private static LinkPreview _fetchAndParse(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = _open(urlStr);
            if (conn == null) return null;

            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) return null;

            String contentType = conn.getContentType();
            if (contentType != null && !contentType.toLowerCase().contains("html")) {
                return null; // images/PDFs/etc. have nothing to scrape
            }

            // Stream and re-scan as we go, stopping the moment we have what
            // we need. This is the whole fix: a fixed small read cap threw
            // away every site whose og tags sit past it.
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[CHUNK];
            int n, total = 0;
            boolean sawTitle = false, sawImage = false, sawEnd = false;
            // Carry the tail of the previous chunk so a marker split across
            // a chunk boundary is still matched.
            byte[] carry = new byte[0];
            while (total < MAX_BYTES && (n = is.read(chunk)) != -1) {
                out.write(chunk, 0, n);
                total += n;

                // Scan only the NEW bytes (plus the small carry), on raw
                // ASCII — decoding or copying the whole growing buffer each
                // chunk was ~58 full passes over a ~950KB page.
                byte[] window = new byte[carry.length + n];
                System.arraycopy(carry, 0, window, 0, carry.length);
                System.arraycopy(chunk, 0, window, carry.length, n);
                if (!sawTitle) sawTitle = _containsAscii(window, "og:title");
                if (!sawImage) sawImage = _containsAscii(window, "og:image");
                if (!sawEnd)   sawEnd   = _containsAscii(window, "</html>");
                if ((sawTitle && sawImage) || sawEnd) break;

                int keep = Math.min(16, n); // longest marker is well under this
                carry = new byte[keep];
                System.arraycopy(chunk, n - keep, carry, 0, keep);
            }
            is.close();
            String html = out.toString("UTF-8");

            LinkPreview preview = new LinkPreview();
            preview.sourceUrl   = urlStr;
            preview.title       = _extractMeta(html, "og:title");
            if (preview.title == null)       preview.title = _extractMeta(html, "twitter:title");
            if (preview.title == null)       preview.title = _extractTitleTag(html);
            preview.description = _extractMeta(html, "og:description");
            if (preview.description == null) preview.description = _extractMeta(html, "twitter:description");
            if (preview.description == null) preview.description = _extractMeta(html, "description");
            preview.imageUrl    = _extractMeta(html, "og:image");
            if (preview.imageUrl == null)    preview.imageUrl = _extractMeta(html, "og:image:secure_url");
            if (preview.imageUrl == null)    preview.imageUrl = _extractMeta(html, "twitter:image");
            preview.siteName    = _extractMeta(html, "og:site_name");
            if (preview.siteName == null)    preview.siteName = _hostOf(urlStr);

            // Some sites give protocol-relative or root-relative image paths.
            preview.imageUrl = _absolutize(preview.imageUrl, urlStr);

            if ((preview.title == null || preview.title.isEmpty())
                && (preview.description == null || preview.description.isEmpty())) {
                return null; // nothing worth showing
            }
            return preview;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Opens a connection, following redirects MANUALLY. HttpURLConnection's
     * own auto-follow refuses to cross between http and https, which is
     * exactly the hop most shortened and legacy links take (http://… →
     * https://…) — those would otherwise just return a 30x and be dropped.
     */
    private static HttpURLConnection _open(String urlStr) {
        try {
            String current = urlStr;
            for (int i = 0; i < MAX_REDIRECTS; i++) {
                HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setConnectTimeout(7000);
                conn.setReadTimeout(7000);
                conn.setInstanceFollowRedirects(false); // handled here instead
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null) return null;
                    current = _absolutize(loc, current);
                    if (current == null) return null;
                    continue;
                }
                return conn;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Plain ASCII substring search over raw bytes — lets the streaming
     *  scan above avoid decoding the page just to look for a marker. */
    private static boolean _containsAscii(byte[] haystack, String needle) {
        int nLen = needle.length();
        if (nLen == 0 || haystack.length < nLen) return false;
        outer:
        for (int i = 0; i <= haystack.length - nLen; i++) {
            for (int j = 0; j < nLen; j++) {
                if ((char) (haystack[i + j] & 0xFF) != needle.charAt(j)) continue outer;
            }
            return true;
        }
        return false;
    }

    private static String _absolutize(String maybeRelative, String baseUrl) {
        if (maybeRelative == null || maybeRelative.isEmpty()) return maybeRelative;
        try {
            return new URL(new URL(baseUrl), maybeRelative).toString();
        } catch (Exception e) {
            return maybeRelative;
        }
    }

    private static String _hostOf(String urlStr) {
        try { return new URL(urlStr).getHost(); } catch (Exception e) { return null; }
    }

    private static String _extractMeta(String html, String property) {
        // property/content order (most common)
        Matcher m1 = Pattern.compile(
            "<meta[^>]+(?:property|name)=[\"']" + Pattern.quote(property) + "[\"'][^>]+content=[\"']([^\"']*)[\"']",
            Pattern.CASE_INSENSITIVE).matcher(html);
        if (m1.find()) return _clean(m1.group(1));

        // content/property order (some sites write attributes in reverse order)
        Matcher m2 = Pattern.compile(
            "<meta[^>]+content=[\"']([^\"']*)[\"'][^>]+(?:property|name)=[\"']" + Pattern.quote(property) + "[\"']",
            Pattern.CASE_INSENSITIVE).matcher(html);
        if (m2.find()) return _clean(m2.group(1));

        return null;
    }

    private static String _extractTitleTag(String html) {
        Matcher m = Pattern.compile("<title[^>]*>([^<]*)</title>", Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? _clean(m.group(1).trim()) : null;
    }

    private static String _clean(String s) {
        if (s == null || s.isEmpty() || "null".equals(s)) return null;
        String out = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                       .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                       .replace("&nbsp;", " ").trim();
        return out.length() > 200 ? out.substring(0, 200) + "…" : out;
    }
}
