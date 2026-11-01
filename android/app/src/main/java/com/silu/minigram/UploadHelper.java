package com.silu.minigram;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;

import java.io.*;

/**
 * UploadHelper — uploads images to Minigram's own Hostinger backend
 * (replaces the earlier imgbb-based version — imgbb is a free third-party
 * host with no control over retention/availability; this app now owns its
 * own storage instead).
 *
 * Uploads now go to Firebase Storage (see MediaStorage) — no server secret in the app.
 */
public class UploadHelper {


    private static final String TAG = "UploadHelper";

    private static final int MAX_DIMENSION = 1920;
    private static final int JPEG_QUALITY  = 85;

    public interface UploadCallback {
        void onSuccess(String imageUrl);
        void onError(String errorMessage);
    }

    /** Upload image from Uri to our own Hostinger backend. Callback runs on MAIN thread. */
    public static void uploadImage(Context context, Uri imageUri, UploadCallback callback) {
        if (imageUri == null) {
            mainThread(() -> callback.onError("No image selected"));
            return;
        }

        new Thread(() -> {
            try {
                byte[] imageBytes = readAndCompress(context, imageUri);
                if (imageBytes == null || imageBytes.length == 0)
                    throw new IOException("Could not read image file");

                String uid = FirebaseAuth.getInstance().getUid();
                if (uid == null) throw new IOException("Not signed in");

                String url = MediaStorage.upload(uid, "image", imageBytes, "image/jpeg", "jpg");

                final String finalUrl = url;
                mainThread(() -> callback.onSuccess(finalUrl));

            } catch (Exception e) {
                Log.e(TAG, "Upload error", e);
                final String msg = e.getMessage() != null ? e.getMessage() : "Unknown upload error";
                mainThread(() -> callback.onError(msg));
            }
        }).start();
    }

    // ── Helper: read + compress image (unchanged from the imgbb version) ───────

    private static byte[] readAndCompress(Context context, Uri uri) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            InputStream probe = context.getContentResolver().openInputStream(uri);
            if (probe == null) return null;
            BitmapFactory.decodeStream(probe, null, opts);
            probe.close();

            int origW = opts.outWidth;
            int origH = opts.outHeight;

            int sampleSize = 1;
            while (origW / sampleSize > MAX_DIMENSION * 2 || origH / sampleSize > MAX_DIMENSION * 2)
                sampleSize *= 2;

            opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;

            InputStream is = context.getContentResolver().openInputStream(uri);
            if (is == null) return null;
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
            is.close();

            if (bitmap == null) return null;

            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w > MAX_DIMENSION || h > MAX_DIMENSION) {
                float scale = Math.min((float) MAX_DIMENSION / w, (float) MAX_DIMENSION / h);
                int newW = Math.round(w * scale);
                int newH = Math.round(h * scale);
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
                bitmap.recycle();
                bitmap = scaled;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
            bitmap.recycle();
            return baos.toByteArray();

        } catch (Exception e) {
            Log.e(TAG, "readAndCompress error", e);
            return null;
        }
    }

    private static void mainThread(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
