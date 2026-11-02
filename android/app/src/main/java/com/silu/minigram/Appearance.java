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

    // ---- bubble colours / text colours / font
    public static final String[] SENT_NAMES = {"Default", "Indigo", "Emerald", "Ocean", "Rose", "Amber", "Violet", "Graphite"};
    private static final String[] SENT = {null, "#3730A3", "#047857", "#0369A1", "#BE185D", "#B45309", "#6D28D9", "#374151"};
    public static final String[] RECV_NAMES = {"Default", "Indigo", "Mint", "Sand", "Rose", "Sky"};
    private static final String[][] RECV = {{null, null}, {"#E0E7FF", "#2A2750"}, {"#DCFCE7", "#14352A"}, {"#F3EBDD", "#332A1D"}, {"#FCE7F3", "#3B1A2B"}, {"#E0F2FE", "#12293A"}};
    public static final String[] FONT_NAMES = {"Inter", "Serif", "Mono", "System"};

    public static int sentIndex(Context c) { return sp(c).getInt("sent_color", 0); }
    public static void setSentIndex(Context c, int i) { sp(c).edit().putInt("sent_color", i).apply(); }
    public static int recvIndex(Context c) { return sp(c).getInt("recv_color", 0); }
    public static void setRecvIndex(Context c, int i) { sp(c).edit().putInt("recv_color", i).apply(); }
    /** 0 auto (contrast), 1 light, 2 dark */
    public static int textMode(Context c, boolean sent) { return sp(c).getInt(sent ? "sent_text" : "recv_text", 0); }
    public static void setTextMode(Context c, boolean sent, int m) { sp(c).edit().putInt(sent ? "sent_text" : "recv_text", m).apply(); }
    public static int fontIndex(Context c) { return sp(c).getInt("font", 0); }
    public static void setFontIndex(Context c, int i) { sp(c).edit().putInt("font", i).apply(); }

    public static int sentColor(Context c) {
        int i = Math.min(sentIndex(c), SENT.length - 1);
        return SENT[i] == null ? Ink.res(R.color.ink_sent) : Color.parseColor(SENT[i]);
    }
    public static int recvColor(Context c) {
        int i = Math.min(recvIndex(c), RECV.length - 1);
        String h = RECV[i][night(c) ? 1 : 0];
        return h == null ? Ink.res(R.color.ink_surface) : Color.parseColor(h);
    }
    private static boolean isDark(int color) {
        double l = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
        return l < 0.55;
    }
    public static int sentText(Context c) {
        int m = textMode(c, true);
        if (m == 1) return Color.WHITE;
        if (m == 2) return Color.parseColor("#1A1A1A");
        return isDark(sentColor(c)) ? Color.WHITE : Color.parseColor("#1A1A1A");
    }
    public static int recvText(Context c) {
        int m = textMode(c, false);
        if (m == 1) return Color.WHITE;
        if (m == 2) return Color.parseColor("#1A1A1A");
        return isDark(recvColor(c)) ? Color.WHITE : Color.parseColor("#1A1A1A");
    }
    public static android.graphics.Typeface typeface(Context c) {
        android.graphics.Typeface t = null;
        switch (fontIndex(c)) {
            case 1: t = androidx.core.content.res.ResourcesCompat.getFont(c, R.font.newsreader); break;
            case 2: t = androidx.core.content.res.ResourcesCompat.getFont(c, R.font.jetbrains_mono); break;
            case 3: return android.graphics.Typeface.DEFAULT;
            default: t = androidx.core.content.res.ResourcesCompat.getFont(c, R.font.inter);
        }
        return t != null ? t : android.graphics.Typeface.DEFAULT;
    }

    public static void reset(Context c) { sp(c).edit().clear().apply(); applyTheme(c); }

    private static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    public static Drawable bubble(Context c, boolean sent) {
        float r = dp(c, bubbleDp(c)), tail = dp(c, 3);
        GradientDrawable g = new GradientDrawable();
        g.setColor(sent ? sentColor(c) : recvColor(c));
        if (sent) g.setCornerRadii(new float[]{r, r, r, r, tail, tail, r, r});     // tail bottom-right
        else { g.setCornerRadii(new float[]{tail, tail, r, r, r, r, r, r}); g.setStroke(dp(c, 1), Ink.res(R.color.ink_line)); }
        return g;
    }

    public static boolean night(Context c) {
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
