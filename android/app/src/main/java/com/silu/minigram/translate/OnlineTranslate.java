package com.silu.minigram.translate;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Optional online engine (Google Translate web endpoint) used ONLY when the user switched on "Better accuracy
 * (online)". The message text leaves the device for this call. Any failure returns null and the caller falls back
 * to the on-device ML Kit models.
 */
public final class OnlineTranslate {
    private OnlineTranslate() {}

    /** Blocking; call off the main thread. {@code src} may be "auto". */
    public static String translate(String text, String src, String tgt) {
        if (text == null || text.trim().isEmpty() || text.length() > 1500) return null;
        HttpURLConnection c = null;
        try {
            String q = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl=" + URLEncoder.encode(src, "UTF-8")
                + "&tl=" + URLEncoder.encode(tgt, "UTF-8") + "&q=" + URLEncoder.encode(text, "UTF-8");
            c = (HttpURLConnection) new URL(q).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line; while ((line = r.readLine()) != null) sb.append(line);
            }
            return parse(sb.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** [[["translated","original",...],["more","more"]],null,"hi",...] -> concatenated first elements. */
    static String parse(String json) {
        try {
            JSONArray segs = new JSONArray(json).getJSONArray(0);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < segs.length(); i++) {
                JSONArray s = segs.optJSONArray(i);
                if (s != null && !s.isNull(0)) out.append(s.getString(0));
            }
            String r = out.toString().trim();
            return r.isEmpty() ? null : r;
        } catch (Exception e) {
            return null;
        }
    }
}
