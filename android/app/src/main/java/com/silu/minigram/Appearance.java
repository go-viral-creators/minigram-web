package com.silu.minigram;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

import androidx.appcompat.app.AppCompatDelegate;

/** User-controlled look & feel: theme mode, message text size, bubble shape, chat wallpaper. Stored locally. */
public final class Appearance {
    private Appearance() {}
    private static final String P = "appearance";

    public static final String[] WALLPAPER_NAMES = {"Paper", "Dots", "Indigo mist", "Mint", "Sand", "Midnight"};

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(P, Context.MODE_PRIVATE); }

    // ---- theme: 0 system, 1 light, 2 dark
    public static int themeMode(Context c) { return sp(c).getInt("theme", 0); }
    public static void setThemeMode(Context c, int m) { sp(c).edit().putInt("theme", m).apply(); applyTheme(c); }
    public static void applyTheme(Context c) {
        int m = themeMode(c);
        AppCompatDelegate.setDefaultNightMode(m == 1 ? AppCompatDelegate.MODE_NIGHT_NO
                : m == 2 ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    // ---- message text size (sp)
    public static int textSp(Context c) { return sp(c).getInt("text_sp", 16); }
    public static void setTextSp(Context c, int v) { sp(c).edit().putInt("text_sp", Math.max(13, Math.min(22, v))).apply(); }

    // ---- bubble corner radius (dp)
    public static int bubbleDp(Context c) { return sp(c).getInt("bubble_dp", 12); }
    public static void setBubbleDp(Context c, int v) { sp(c).edit().putInt("bubble_dp", v).apply(); }

    // ---- wallpaper index
    public static int wallpaper(Context c) { return sp(c).getInt("wallpaper", 0); }
    public static void setWallpaper(Context c, int i) { sp(c).edit().putInt("wallpaper", i).apply(); }

    public static void reset(Context c) { sp(c).edit().clear().apply(); applyTheme(c); }

    private static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    public static Drawable bubble(Context c, boolean sent) {
        float r = dp(c, bubbleDp(c)), tail = dp(c, 3);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Ink.res(sent ? R.color.ink_sent : R.color.ink_surface));
        if (sent) g.setCornerRadii(new float[]{r, r, r, r, tail, tail, r, r});     // tail bottom-right
        else { g.setCornerRadii(new float[]{tail, tail, r, r, r, r, r, r}); g.setStroke(dp(c, 1), Ink.res(R.color.ink_line)); }
        return g;
    }

    private static boolean night(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    public static Drawable wallpaperDrawable(Context c, int i) {
        boolean n = night(c);
        switch (i) {
            case 1: {                                   // dots on paper
                Bitmap b = Bitmap.createBitmap(dp(c, 22), dp(c, 22), Bitmap.Config.ARGB_8888);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Ink.res(R.color.ink_line));
                new Canvas(b).drawCircle(b.getWidth() / 2f, b.getHeight() / 2f, dp(c, 1.4f), p);
                BitmapDrawable bd = new BitmapDrawable(c.getResources(), b);
                bd.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
                return new LayerDrawable(new Drawable[]{new ColorDrawable(Ink.res(R.color.ink_bg)), bd});
            }
            case 2: return grad(n ? "#1A1833" : "#EEF0FF", n ? "#241A38" : "#F7F0FF");
            case 3: return grad(n ? "#102620" : "#E8F8F1", n ? "#12302B" : "#F1FBF6");
            case 4: return grad(n ? "#2A2118" : "#F6EEDF", n ? "#33281C" : "#FBF5EA");
            case 5: return new ColorDrawable(Color.parseColor("#0F0D1A"));
            default: return new ColorDrawable(Ink.res(R.color.ink_bg));
        }
    }

    private static Drawable grad(String a, String b) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Color.parseColor(a), Color.parseColor(b)});
    }

    public static Drawable wallpaperDrawable(Context c) { return wallpaperDrawable(c, wallpaper(c)); }
}
