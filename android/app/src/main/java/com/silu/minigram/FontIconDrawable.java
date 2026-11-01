package com.silu.minigram;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.core.content.res.ResourcesCompat;

/**
 * Renders a Material Icons ligature (the same "forward" / "content_copy" /
 * "delete" names used with android:fontFamily="@font/material_icons"
 * everywhere else in the layouts) into a Drawable.
 *
 * Why this exists: ActionMode / Menu items can only take a Drawable, not a
 * font ligature. So the selection toolbar was the one place still pulling
 * icons from separate drawable assets, which made it visually inconsistent
 * with every other icon in the app — different weight, different shape,
 * different sizing. This bridges the gap so menus can use the same icon
 * font as the rest of the UI.
 *
 * Icons are cached per (ligature, colour, size) — a menu rebuilds its
 * items every time selection changes, and re-rasterising the same glyph
 * each time would be wasteful.
 */
public class FontIconDrawable {

    private static final java.util.HashMap<String, Drawable> cache = new java.util.HashMap<>();

    /** Default size for toolbar/menu icons — matches the ~22sp the app's
     *  other header icons render at, so the selection toolbar doesn't look
     *  oversized or undersized next to them. */
    public static final int DEFAULT_SIZE_DP = 22;

    public static Drawable create(Context ctx, String ligature, int colorInt) {
        return create(ctx, ligature, colorInt, DEFAULT_SIZE_DP);
    }

    public static Drawable create(Context ctx, String ligature, int colorInt, int sizeDp) {
        String key = ligature + "|" + colorInt + "|" + sizeDp;
        synchronized (cache) {
            Drawable cached = cache.get(key);
            if (cached != null) return cached;
        }

        Drawable d = _render(ctx, ligature, colorInt, sizeDp);
        if (d != null) {
            synchronized (cache) { cache.put(key, d); }
        }
        return d;
    }

    private static Drawable _render(Context ctx, String ligature, int colorInt, int sizeDp) {
        try {
            Typeface tf = ResourcesCompat.getFont(ctx, R.font.material_icons);
            if (tf == null) return null;

            float density = ctx.getResources().getDisplayMetrics().density;
            float textPx  = sizeDp * density;
            // A little headroom so glyphs that overshoot the nominal em box
            // aren't clipped at the edges.
            int canvasPx = (int) Math.ceil(textPx * 1.25f);

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(tf);
            paint.setTextSize(textPx);
            paint.setColor(colorInt);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setSubpixelText(true);

            Bitmap bmp = Bitmap.createBitmap(canvasPx, canvasPx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint.FontMetrics fm = paint.getFontMetrics();
            // Vertically centre using the font metrics rather than the glyph
            // bounds — glyph bounds vary per icon and would make each menu
            // item sit at a slightly different height.
            float baselineY = canvasPx / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(ligature, canvasPx / 2f, baselineY, paint);

            return new BitmapDrawable(ctx.getResources(), bmp);
        } catch (Throwable t) {
            // Never let an icon problem take down a menu — the caller falls
            // back to a text-only menu item, which still works.
            return null;
        }
    }
}
