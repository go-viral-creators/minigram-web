package com.silu.minigram.e2ee;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.silu.minigram.VoiceCacheHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads encrypted media blobs, decrypts them on-device and exposes a local file to the chat UI. */
public final class E2eeMedia {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Set<String> inflight = ConcurrentHashMap.newKeySet();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_BYTES = 40 * 1024 * 1024;
    private E2eeMedia() {}

    private static File imageFile(Context c, String url) {
        File d = new File(c.getCacheDir(), "e2ee_media");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, Crypto.b64(java.util.Arrays.copyOf(Crypto.sha256(url.getBytes()), 12)).replace('/', '_').replace('+', '-') + ".img");
    }

    /** Synthetic "url" under which a decrypted voice note lives in VoiceCacheHelper's cache. */
    private static String voiceKey(String url) {
        return "e2ee:" + Crypto.b64(java.util.Arrays.copyOf(Crypto.sha256(url.getBytes()), 12));
    }

    /**
     * If {@code msg} carries encrypted media, points image_url / voice_url at the decrypted local copy
     * (or leaves them empty and decrypts in the background, calling {@code onReady} when done).
     */
    public static void attach(Context ctx, Map<String, Object> msg, Runnable onReady) {
        Object u = msg.get("e2ee_media_url"), k = msg.get("e2ee_media_key");
        if (!(u instanceof String) || !(k instanceof String)) return;
        final String url = (String) u, key = (String) k;
        final boolean voice = "voice".equals(msg.get("type"));
        if (key.isEmpty()) {                      // forwarded plain media: nothing to decrypt
            if (!voice) msg.put("image_url", url);
            else        msg.put("voice_url", url);
            return;
        }
        final Context app = ctx.getApplicationContext();
        File target = voice ? VoiceCacheHelper.cacheFileFor(app, voiceKey(url)) : imageFile(app, url);
        if (target.exists() && target.length() > 0) {
            msg.put(voice ? "voice_url" : "image_url", voice ? voiceKey(url) : target.getAbsolutePath());
            return;
        }
        if (!inflight.add(url)) return;
        POOL.execute(() -> {
            try {
                byte[] boxed = download(url);
                byte[] plain = E2ee.openMedia(boxed, key);
                File tmp = new File(target.getParentFile(), target.getName() + ".part");
                try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(plain); }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(target);
                if (onReady != null) MAIN.post(onReady);
            } catch (Exception e) {
                android.util.Log.e("E2EE", "media decrypt failed: " + e.getClass().getSimpleName());
            } finally {
                inflight.remove(url);
            }
        });
    }

    private static byte[] download(String url) throws Exception {
        if (!url.startsWith("https://")) throw new SecurityException("https only");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new java.io.IOException("too large");
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally { c.disconnect(); }
    }
}
