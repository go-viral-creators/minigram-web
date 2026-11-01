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
        preview();
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
        b.pvRecv.setTextSize(Appearance.textSp(this));
        b.pvSent.setTextSize(Appearance.textSp(this));
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
