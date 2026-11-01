package com.silu.minigram;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.widget.ImageView;

import java.io.*;

/**
 * ImageEditHelper
 * ─────────────────────────────────────────────────────────────────────
 * Utility class for:
 *   1. Image compression (before upload — WhatsApp style)
 *   2. Draw overlay (canvas drawing on bitmap)
 *   3. Launch UCrop for cropping
 *
 * Note: Crop is handled via UCrop library (already in project).
 *       Draw tool uses a simple Paint canvas approach.
 *       Compression is done before passing URI to upload.
 * ─────────────────────────────────────────────────────────────────────
 */
public class ImageEditHelper {

    /** Max dimension for compressed image (WhatsApp uses ~1280px) */
    private static final int MAX_DIM    = 1280;
    /** JPEG quality (0-100) — 80 gives good quality & small size */
    private static final int JPEG_QUALITY = 80;

    // ─────────────────────────────────────────────────────────────────
    // Compression
    // ─────────────────────────────────────────────────────────────────

    public interface CompressCallback {
        void onSuccess(Uri compressedUri);
        void onError(String message);
    }

    /**
     * Compress image from URI in background thread.
     * Returns compressed URI via callback on main thread.
     */
    public static void compressImage(Context context, Uri sourceUri, CompressCallback callback) {
        new Thread(() -> {
            try {
                Bitmap original = MediaStore.Images.Media.getBitmap(
                        context.getContentResolver(), sourceUri);

                Bitmap compressed = resizeAndCompress(original);

                // Save to cache
                File outFile = new File(context.getCacheDir(),
                        "compressed_" + System.currentTimeMillis() + ".jpg");
                FileOutputStream fos = new FileOutputStream(outFile);
                compressed.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
                fos.flush();
                fos.close();

                if (!original.isRecycled()) original.recycle();
                if (!compressed.isRecycled() && compressed != original) compressed.recycle();

                Uri result = Uri.fromFile(outFile);
                new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess(result));

            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onError("Compression failed: " + e.getMessage()));
            }
        }).start();
    }

    /**
     * Resize bitmap to MAX_DIM maintaining aspect ratio
     */
    public static Bitmap resizeAndCompress(Bitmap original) {
        int w = original.getWidth();
        int h = original.getHeight();

        if (w <= MAX_DIM && h <= MAX_DIM) return original;

        float ratio = Math.min((float) MAX_DIM / w, (float) MAX_DIM / h);
        int newW = Math.round(w * ratio);
        int newH = Math.round(h * ratio);

        return Bitmap.createScaledBitmap(original, newW, newH, true);
    }

    /**
     * Get compressed bitmap bytes directly
     */
    public static byte[] getBitmapBytes(Bitmap bitmap) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Bitmap resized = resizeAndCompress(bitmap);
        resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
        return baos.toByteArray();
    }

    /**
     * Save a bitmap (e.g. after draw) to cache and return Uri
     */
    public static Uri saveBitmapToCache(Context context, Bitmap bitmap) throws IOException {
        File outFile = new File(context.getCacheDir(),
                "edited_" + System.currentTimeMillis() + ".jpg");
        FileOutputStream fos = new FileOutputStream(outFile);
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
        fos.flush();
        fos.close();
        return Uri.fromFile(outFile);
    }

    // ─────────────────────────────────────────────────────────────────
    // Drawing Tool (used by DrawingView)
    // ─────────────────────────────────────────────────────────────────

    /** Supported draw modes */
    public enum DrawMode {
        PEN, LINE, RECT, ARROW, ERASER
    }

    /** Current draw settings — set before starting draw activity */
    public static int   drawColor     = Color.RED;
    public static float drawStrokeWidth = 8f;
    public static DrawMode drawMode   = DrawMode.PEN;

    /**
     * Flatten drawing overlay bitmap onto background bitmap.
     * Call after user finishes drawing.
     */
    public static Bitmap flattenDrawing(Bitmap background, Bitmap overlay) {
        Bitmap result = background.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(result);
        canvas.drawBitmap(overlay, 0, 0, null);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────
    // UCrop helper
    // ─────────────────────────────────────────────────────────────────

    /**
     * Build destination URI for UCrop output
     */
    public static Uri getCropDestination(Context context) {
        return Uri.fromFile(new File(context.getCacheDir(),
                "crop_" + System.currentTimeMillis() + ".jpg"));
    }

    // ─────────────────────────────────────────────────────────────────
    // Size label helper
    // ─────────────────────────────────────────────────────────────────

    public static String getFileSizeLabel(Context context, Uri uri) {
        try {
            android.content.res.AssetFileDescriptor afd =
                    context.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd != null) {
                long bytes = afd.getLength();
                afd.close();
                if (bytes > 1024 * 1024)
                    return String.format("%.1f MB", bytes / 1048576.0);
                return String.format("%d KB", bytes / 1024);
            }
        } catch (Exception ignored) {}
        return "";
    }
}
