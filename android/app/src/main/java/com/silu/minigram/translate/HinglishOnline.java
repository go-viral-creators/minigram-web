package com.silu.minigram.translate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Optional, opt-in high-accuracy Roman-Hindi to Devanagari transliteration using Google Input Tools
 * (the engine behind Gboard Hinglish typing). The message text leaves the device for this call, so it only runs
 * when the user has enabled "Better Hinglish accuracy". Returns null on any failure; callers fall back to the
 * offline rules in {@link Hinglish}.
 */
public final class HinglishOnline {
    private HinglishOnline() {}

    /** Blocking. Call from a background thread. */
    public static String transliterate(String text) {
        if (text == null || text.trim().isEmpty() || text.length() > 600) return null;
        HttpURLConnection c = null;
        try {
            String q = "https://inputtools.google.com/request?text=" + URLEncoder.encode(text, "UTF-8")
                + "&itc=hi-t-i0-und&num=1&cp=0&cs=1&ie=utf-8&oe=utf-8&app=minigram";
            c = (HttpURLConnection) new URL(q).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(5000);
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

    private static final java.util.regex.Pattern RESP = java.util.regex.Pattern.compile(
        "^\\s*\\[\\s*\"SUCCESS\"\\s*,\\s*\\[\\s*\\[\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*,\\s*\\[\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** ["SUCCESS",[[source,[candidate],...]]] -> candidate of the first entry. */
    static String parse(String json) {
        if (json == null) return null;
        java.util.regex.Matcher m = RESP.matcher(json);
        if (!m.find()) return null;
        String out = m.group(1);
        // decode \\uXXXX escapes if the server sent them
        java.util.regex.Matcher u = java.util.regex.Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(out);
        StringBuffer sb = new StringBuffer();
        while (u.find()) u.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(u.group(1), 16))));
        u.appendTail(sb);
        out = sb.toString().replace("\\\"", "\"").replace("\\\\", "\\");
        return out.isEmpty() ? null : out;
    }
}
