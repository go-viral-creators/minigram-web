package com.silu.minigram;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Voice messages in the Media/Links/Voice tabs (MyProfileActivity's own
 * tabs, and ChannelProfileActivity's channel-scoped ones) used to have a
 * separate "download" button that saved the file via Android's system
 * DownloadManager — straight into the public Downloads folder, visible to
 * any other app or the device's file manager.
 *
 * That's not what was wanted: voice should be playable inside Minigram
 * only, never exported. This downloads into the app's own PRIVATE cache
 * directory instead (getCacheDir()/voice_cache/ — not accessible to other
 * apps by default on Android), and the UI now uses a single icon that
 * carries the whole state: download icon (not cached yet) → spinner
 * (downloading) → pause icon + auto-play (just finished) → play icon
 * (paused). Once cached, replays are instant — no re-download.
 */
public class VoiceCacheHelper {

    public interface Callback {
        void onReady(File localFile);
        void onError();
    }

    public static File cacheFileFor(Context ctx, String url) {
        String name = _hash(url) + ".dat";
        File dir = new File(ctx.getCacheDir(), "voice_cache");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    public static boolean isCached(Context ctx, String url) {
        File f = cacheFileFor(ctx, url);
        return f.exists() && f.length() > 0;
    }

    /** If already cached, calls back immediately (still off the calling
     *  thread's assumptions — always posts to main thread) with the local
     *  file. Otherwise downloads in a background thread first. Safe to
     *  call repeatedly for the same URL; each call does its own download
     *  if not yet cached (callers should track their own "already in
     *  flight" state per row to avoid duplicate downloads on rapid taps,
     *  same as any tap-triggered async action). */
    public static void ensureDownloaded(Context ctx, String url, Callback callback) {
        Context appCtx = ctx.getApplicationContext();
        File target = cacheFileFor(appCtx, url);
        Handler main = new Handler(Looper.getMainLooper());

        if (target.exists() && target.length() > 0) {
            main.post(() -> callback.onReady(target));
            return;
        }

        new Thread(() -> {
            File tmp = new File(target.getParentFile(), target.getName() + ".part");
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(true);
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (!tmp.renameTo(target)) {
                    // Rename can fail on some filesystems under concurrent
                    // access; fall back to a direct copy-then-delete.
                    target.delete();
                    tmp.renameTo(target);
                }
                main.post(() -> callback.onReady(target));
            } catch (Exception e) {
                tmp.delete();
                main.post(callback::onError);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private static String _hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // Extremely unlikely (MD5/UTF-8 always available) — fall back
            // to a simple safe transform so caching still works rather
            // than crashing.
            return String.valueOf(Math.abs(input.hashCode()));
        }
    }
}
