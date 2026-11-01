package com.silu.minigram;

import android.graphics.Bitmap;

/**
 * NotificationImageUtil
 *
 * Lightweight bitmap helpers used only for building push-notification images:
 *   • centerCropSquare() — produces a square thumbnail for the "small / collapsed"
 *     notification icon (setLargeIcon).
 *   • stackBlur()        — fast, pure-Java box-blur (no RenderScript / android.renderscript
 *     dependency, since RenderScript is deprecated since API 31 and removed on newer
 *     devices). Used to blur both the small icon and the big picture when the user has
 *     enabled "Blur notification images" for that specific channel/chat.
 *
 * The stack-blur algorithm below is the classic Mario Klingemann / Romain Guy
 * implementation (public domain), adapted for in-place ARGB_8888 bitmaps.
 */
public final class NotificationImageUtil {

    private NotificationImageUtil() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Center-crop to square (used for the collapsed/default notification icon)
    // ─────────────────────────────────────────────────────────────────────────
    public static Bitmap centerCropSquare(Bitmap src, int targetSize) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        int cropSize = Math.min(w, h);
        int x = (w - cropSize) / 2;
        int y = (h - cropSize) / 2;
        Bitmap cropped = Bitmap.createBitmap(src, x, y, cropSize, cropSize);
        if (cropSize == targetSize) return cropped;
        return Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true);
    }

    /** Downscales large images so memory stays low (big picture max edge ~1024px). */
    public static Bitmap downscale(Bitmap src, int maxEdge) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        int longEdge = Math.max(w, h);
        if (longEdge <= maxEdge) return src;
        float scale = maxEdge / (float) longEdge;
        return Bitmap.createScaledBitmap(src, Math.round(w * scale), Math.round(h * scale), true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fast box/stack blur — privacy blur for adult/sensitive channel notifications
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Applies an in-place stack blur to a mutable copy of the bitmap and returns it.
     * radius ~ 20-30 gives a strong, content-hiding blur suitable for privacy purposes.
     */
    public static Bitmap stackBlur(Bitmap src, int radius) {
        if (src == null) return null;
        if (radius < 1) return src;

        Bitmap bitmap = src.copy(Bitmap.Config.ARGB_8888, true);

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pix = new int[w * h];
        bitmap.getPixels(pix, 0, w, 0, 0, w, h);

        int wm = w - 1, hm = h - 1, wh = w * h, div = radius + radius + 1;
        int[] r = new int[wh], g = new int[wh], b = new int[wh];
        int rsum, gsum, bsum, x, y, i, p, yp, yi, yw;
        int[] vmin = new int[Math.max(w, h)];

        int divsum = (div + 1) >> 1;
        divsum *= divsum;
        int[] dv = new int[256 * divsum];
        for (i = 0; i < 256 * divsum; i++) dv[i] = (i / divsum);

        yw = yi = 0;
        int[][] stack = new int[div][3];
        int stackpointer, stackstart;
        int[] sir;
        int rbs;
        int routsum, goutsum, boutsum, rinsum, ginsum, binsum;

        for (y = 0; y < h; y++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            for (i = -radius; i <= radius; i++) {
                p = pix[yi + Math.min(wm, Math.max(i, 0))];
                sir = stack[i + radius];
                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);
                rbs = radius + 1 - Math.abs(i);
                rsum += sir[0] * rbs; gsum += sir[1] * rbs; bsum += sir[2] * rbs;
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; }
                else       { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; }
            }
            stackpointer = radius;

            for (x = 0; x < w; x++) {
                r[yi] = dv[rsum]; g[yi] = dv[gsum]; b[yi] = dv[bsum];
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2];

                if (y == 0) vmin[x] = Math.min(x + radius + 1, wm);
                p = pix[yw + vmin[x]];

                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);

                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2];
                rsum += rinsum;   gsum += ginsum;    bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer % div];

                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2];
                rinsum -= sir[0];  ginsum -= sir[1];   binsum -= sir[2];

                yi++;
            }
            yw += w;
        }

        for (x = 0; x < w; x++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            yp = -radius * w;
            for (i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;
                sir = stack[i + radius];
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi];
                rbs = radius + 1 - Math.abs(i);
                rsum += r[yi] * rbs; gsum += g[yi] * rbs; bsum += b[yi] * rbs;
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; }
                else       { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; }
                if (i < hm) yp += w;
            }
            yi = x; stackpointer = radius;
            for (y = 0; y < h; y++) {
                pix[yi] = 0xff000000 | (dv[rsum] << 16) | (dv[gsum] << 8) | dv[bsum];
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2];

                if (x == 0) vmin[y] = Math.min(y + radius + 1, hm) * w;
                p = x + vmin[y];

                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p];

                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2];
                rsum += rinsum;   gsum += ginsum;    bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer];

                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2];
                rinsum -= sir[0];  ginsum -= sir[1];   binsum -= sir[2];

                yi += w;
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h);
        return bitmap;
    }
}
