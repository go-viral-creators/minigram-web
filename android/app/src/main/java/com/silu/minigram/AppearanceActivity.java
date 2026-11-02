package com.silu.minigram;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.silu.minigram.databinding.ActivityAppearanceBinding;

/** Appearance: theme, message text size, bubble shape and chat wallpaper, with a live preview. */
public class AppearanceActivity extends AppCompatActivity {
    private ActivityAppearanceBinding b;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        b = ActivityAppearanceBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        EdgeToEdgeHelper.applyInsets(b.main);
        b.btnBack.setOnClickListener(v -> finish());

        int t = Appearance.themeMode(this);
        b.chipsTheme.check(t == 1 ? R.id.theme_light : t == 2 ? R.id.theme_dark : R.id.theme_system);
        b.chipsTheme.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            Appearance.setThemeMode(this, id == R.id.theme_light ? 1 : id == R.id.theme_dark ? 2 : 0);   // recreates the screen
        });

        b.sliderText.setValue(Appearance.textSp(this));
        b.sliderText.addOnChangeListener((sl, v, fromUser) -> { Appearance.setTextSp(this, (int) v); preview(); });

        int r = Appearance.bubbleDp(this);
        b.chipsBubble.check(r <= 4 ? R.id.bubble_sharp : r >= 20 ? R.id.bubble_pill : R.id.bubble_round);
        b.chipsBubble.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            Appearance.setBubbleDp(this, id == R.id.bubble_sharp ? 3 : id == R.id.bubble_pill ? 22 : 12);
            preview();
        });

        b.btnReset.setOnClickListener(v -> { Appearance.reset(this); recreate(); });
        buildWallpapers();
        buildExtras();
        preview();
    }

    // ---------------------------------------------------------------- bubble colours, text colours, font
    private void buildExtras() {
        android.view.ViewGroup parent = (android.view.ViewGroup) b.btnReset.getParent();
        int at = parent.indexOfChild(b.btnReset);
        parent.addView(eyebrow("Sent bubble colour"), at++);
        parent.addView(swatches(Appearance.SENT_NAMES.length, Appearance.sentIndex(this),
            i -> { Appearance.setSentIndex(this, i); Appearance.setTextMode(this, true, 0); preview(); rebuildExtras(); },
            i -> { int save = Appearance.sentIndex(this); Appearance.setSentIndex(this, i); int col = Appearance.sentColor(this); Appearance.setSentIndex(this, save); return col; }), at++);
        parent.addView(eyebrow("Received bubble colour"), at++);
        parent.addView(swatches(Appearance.RECV_NAMES.length, Appearance.recvIndex(this),
            i -> { Appearance.setRecvIndex(this, i); Appearance.setTextMode(this, false, 0); preview(); rebuildExtras(); },
            i -> { int save = Appearance.recvIndex(this); Appearance.setRecvIndex(this, i); int col = Appearance.recvColor(this); Appearance.setRecvIndex(this, save); return col; }), at++);
        parent.addView(eyebrow("Text colour"), at++);
        parent.addView(chipRow(new String[]{"Auto", "Light", "Dark"}, Appearance.textMode(this, true), true), at++);
        parent.addView(chipRow(new String[]{"Auto", "Light", "Dark"}, Appearance.textMode(this, false), false), at++);
        parent.addView(eyebrow("Font"), at++);
        parent.addView(fontRow(), at++);
        extrasStart = parent.indexOfChild(b.btnReset) - (at - parent.indexOfChild(b.btnReset));
    }
    private int extrasStart;
    private void rebuildExtras() { recreate(); }

    private TextView eyebrow(String s) {
        TextView t = new TextView(this);
        t.setTextAppearance(R.style.TextAppearance_Ink_Eyebrow);
        t.setText(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(20);
        t.setLayoutParams(lp);
        return t;
    }

    private interface IntCb { void run(int i); }
    private interface ColorOf { int of(int i); }

    private View swatches(int n, int sel, IntCb pick, ColorOf colorOf) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(8), 0, 0);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            View sw = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(colorOf.of(i));
            g.setStroke(dp(i == sel ? 3 : 1), i == sel ? Ink.res(R.color.ink_accent) : Ink.res(R.color.ink_line));
            sw.setBackground(g);
            sw.setContentDescription("Colour " + (i + 1) + (i == sel ? ", selected" : ""));
            sw.setOnClickListener(v -> pick.run(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.setMarginEnd(dp(12));
            row.addView(sw, lp);
        }
        hs.addView(row);
        return hs;
    }

    private View chipRow(String[] names, int sel, boolean sent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(6), 0, 0);
        TextView label = new TextView(this);
        label.setText(sent ? "Sent" : "Received");
        label.setTextColor(Ink.res(R.color.ink_text_2));
        label.setMinWidth(dp(84));
        box.addView(label);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            com.google.android.material.chip.Chip c = new com.google.android.material.chip.Chip(this, null, 0);
            c.setText(names[i]);
            c.setCheckable(true);
            c.setChecked(i == sel);
            c.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(Ink.res(i == sel ? R.color.ink_accent_soft : R.color.ink_surface)));
            c.setOnClickListener(v -> { Appearance.setTextMode(this, sent, idx); recreate(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(dp(8));
            box.addView(c, lp);
        }
        return box;
    }

    private View fontRow() {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(8), 0, 0);
        int sel = Appearance.fontIndex(this);
        for (int i = 0; i < Appearance.FONT_NAMES.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(Appearance.FONT_NAMES[i] + "\nAa");
            t.setGravity(android.view.Gravity.CENTER);
            int save = Appearance.fontIndex(this);
            Appearance.setFontIndex(this, i);
            t.setTypeface(Appearance.typeface(this));
            Appearance.setFontIndex(this, save);
            t.setTextColor(Ink.res(R.color.ink_text));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(12));
            g.setColor(Ink.res(i == sel ? R.color.ink_accent_soft : R.color.ink_surface));
            g.setStroke(dp(i == sel ? 2 : 1), i == sel ? Ink.res(R.color.ink_accent) : Ink.res(R.color.ink_line));
            t.setBackground(g);
            t.setOnClickListener(v -> { Appearance.setFontIndex(this, idx); recreate(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(92), dp(72));
            lp.setMarginEnd(dp(10));
            row.addView(t, lp);
        }
        hs.addView(row);
        return hs;
    }

    private void buildWallpapers() {
        b.rowWallpapers.removeAllViews();
        int sel = Appearance.wallpaper(this), size = dp(72);
        for (int i = 0; i < Appearance.WALLPAPER_NAMES.length; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, 0, dp(12), 0);
            View sw = new View(this);
            sw.setBackground(Appearance.wallpaperDrawable(this, i));
            GradientDrawable ring = new GradientDrawable();
            ring.setColor(android.graphics.Color.TRANSPARENT);
            ring.setCornerRadius(dp(10));
            ring.setStroke(dp(i == sel ? 3 : 1), i == sel ? Ink.res(R.color.ink_accent) : Ink.res(R.color.ink_line));
            sw.setForeground(ring);
            sw.setClipToOutline(true);
            sw.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, dp(96));
            cell.addView(sw, lp);
            TextView tv = new TextView(this);
            tv.setText(Appearance.WALLPAPER_NAMES[i]);
            tv.setTextSize(12);
            tv.setTextColor(Ink.res(i == sel ? R.color.ink_text : R.color.ink_text_2));
            tv.setPadding(0, dp(6), 0, 0);
            cell.addView(tv);
            cell.setMinimumHeight(dp(48));
            cell.setContentDescription("Wallpaper " + Appearance.WALLPAPER_NAMES[i] + (i == sel ? ", selected" : ""));
            cell.setOnClickListener(v -> { Appearance.setWallpaper(this, idx); buildWallpapers(); preview(); });
            b.rowWallpapers.addView(cell);
        }
    }

    private void preview() {
        b.preview.setBackground(Appearance.wallpaperDrawable(this));
        b.pvRecv.setBackground(Appearance.bubble(this, false));
        b.pvSent.setBackground(Appearance.bubble(this, true));
        b.pvRecv.setTextColor(Appearance.recvText(this));
        b.pvSent.setTextColor(Appearance.sentText(this));
        b.pvRecv.setTypeface(Appearance.typeface(this));
        b.pvSent.setTypeface(Appearance.typeface(this));
        b.pvRecv.setTextSize(Appearance.textSp(this));
        b.pvSent.setTextSize(Appearance.textSp(this));
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
