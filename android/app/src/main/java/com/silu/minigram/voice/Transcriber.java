package com.silu.minigram.voice;

import android.content.Context;
import android.content.SharedPreferences;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Offline speech-to-text for voice messages (Vosk / Kaldi). Models are small (about 40 MB) and downloaded once, on
 * request; recognition then runs fully on the phone, for both the sender and the receiver of a voice note.
 */
public final class Transcriber {
    public static final class ModelInfo {
        public final String code, label, folder; public final int mb;
        ModelInfo(String code, String label, String folder, int mb) { this.code = code; this.label = label; this.folder = folder; this.mb = mb; }
    }
    public static final ModelInfo[] MODELS = {
        new ModelInfo("hi", "Hindi", "vosk-model-small-hi-0.22", 42),
        new ModelInfo("en", "English (India)", "vosk-model-small-en-in-0.4", 36),
    };
    public interface Progress { void percent(int p); }

    private static final Map<String, Model> LOADED = new HashMap<>();
    private Transcriber() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("transcribe", Context.MODE_PRIVATE); }
    public static ModelInfo preferred(Context c) {
        String code = sp(c).getString("lang", null);
        for (ModelInfo m : MODELS) if (m.code.equals(code) && ready(c, m)) return m;
        for (ModelInfo m : MODELS) if (ready(c, m)) return m;
        return null;
    }
    public static void setPreferred(Context c, ModelInfo m) { sp(c).edit().putString("lang", m.code).apply(); }

    private static File root(Context c) { return new File(c.getFilesDir(), "vosk"); }
    public static boolean ready(Context c, ModelInfo m) { return new File(root(c), m.folder + "/am").isDirectory(); }

    // ---- transcripts are cached per message so they survive restarts
    public static String cached(Context c, String msgId) { return sp(c).getString("t_" + msgId, null); }
    public static void store(Context c, String msgId, String text) { sp(c).edit().putString("t_" + msgId, text).apply(); }

    /** Blocking download + unzip. */
    public static void download(Context c, ModelInfo m, Progress p) throws Exception {
        File base = root(c);
        //noinspection ResultOfMethodCallIgnored
        base.mkdirs();
        HttpURLConnection con = (HttpURLConnection) new URL("https://alphacephei.com/vosk/models/" + m.folder + ".zip").openConnection();
        con.setConnectTimeout(15000); con.setReadTimeout(30000);
        long total = con.getContentLengthLong();
        File tmp = new File(base, m.folder + ".zip.part");
        try (InputStream in = new BufferedInputStream(con.getInputStream()); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536]; long got = 0; int n, last = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n); got += n;
                int pc = total > 0 ? (int) (got * 90 / total) : 0;
                if (pc != last && p != null) { last = pc; p.percent(pc); }
            }
        } finally { con.disconnect(); }
        File canonicalBase = base.getCanonicalFile();
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(tmp)))) {
            ZipEntry e; byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                File f = new File(base, e.getName()).getCanonicalFile();
                if (!f.getPath().startsWith(canonicalBase.getPath())) throw new SecurityException("bad zip entry");   // zip-slip guard
                if (e.isDirectory()) { //noinspection ResultOfMethodCallIgnored
                    f.mkdirs(); continue; }
                //noinspection ResultOfMethodCallIgnored
                f.getParentFile().mkdirs();
                try (FileOutputStream o = new FileOutputStream(f)) { int n; while ((n = z.read(buf)) > 0) o.write(buf, 0, n); }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        if (p != null) p.percent(100);
        if (!ready(c, m)) throw new IllegalStateException("model files missing after download");
    }

    /** Blocking recognition of an audio file. */
    public static String transcribe(Context c, File audio, ModelInfo m) throws Exception {
        byte[] pcm = AudioDecoder.pcm16kMono(audio);
        Model model;
        synchronized (LOADED) {
            model = LOADED.get(m.code);
            if (model == null) {
                LibVosk.setLogLevel(LogLevel.WARNINGS);
                model = new Model(new File(root(c), m.folder).getAbsolutePath());
                LOADED.put(m.code, model);
            }
        }
        StringBuilder text = new StringBuilder();
        try (Recognizer r = new Recognizer(model, 16000.0f)) {
            int off = 0, chunk = 8000;
            while (off < pcm.length) {
                int len = Math.min(chunk, pcm.length - off);
                if (r.acceptWaveForm(java.util.Arrays.copyOfRange(pcm, off, off + len), len)) append(text, r.getResult());
                off += len;
            }
            append(text, r.getFinalResult());
        }
        return text.toString().trim();
    }

    private static void append(StringBuilder sb, String json) {
        try {
            String t = new org.json.JSONObject(json).optString("text", "").trim();
            if (!t.isEmpty()) { if (sb.length() > 0) sb.append(' '); sb.append(t); }
        } catch (Exception ignored) {}
    }
}
