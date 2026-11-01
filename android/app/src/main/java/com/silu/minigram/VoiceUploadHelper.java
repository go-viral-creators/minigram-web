package com.silu.minigram;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;

import java.io.*;

/**
 * VoiceUploadHelper — uploads a recorded voice-note file to Minigram's own
 * Hostinger backend (replaces the earlier Vercel + GitHub/jsDelivr version —
 * same reasoning as UploadHelper: this app now owns its own storage
 * instead of depending on a third-party free-tier service).
 *
 * Uploads go to Firebase Storage (see MediaStorage).
 */
public class VoiceUploadHelper {

    private static final String TAG        = "VoiceUploadHelper";

    public interface UploadCallback {
        void onSuccess(String fileUrl);
        void onError(String errorMessage);
    }

    /** Uploads the given local audio file. Callback runs on MAIN thread. */
    public static void uploadVoice(File audioFile, UploadCallback callback) {
        uploadVoice(audioFile, 0, callback);
    }

    /** Same as above, with a known duration (seconds) recorded alongside the file. */
    public static void uploadVoice(File audioFile, int durationSec, UploadCallback callback) {
        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0) {
            mainThread(() -> callback.onError("Recording file not found"));
            return;
        }

        new Thread(() -> {
            try {
                byte[] bytes = readFile(audioFile);
                String uid = FirebaseAuth.getInstance().getUid();
                if (uid == null) throw new IOException("Not signed in");

                String url = MediaStorage.upload(uid, "voice", bytes, "audio/mp4", "m4a");

                mainThread(() -> callback.onSuccess(url));

            } catch (Exception e) {
                Log.e(TAG, "Upload error", e);
                final String msg = e.getMessage() != null ? e.getMessage() : "Unknown upload error";
                mainThread(() -> callback.onError(msg));
            }
        }).start();
    }

    private static byte[] readFile(File f) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private static void mainThread(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
