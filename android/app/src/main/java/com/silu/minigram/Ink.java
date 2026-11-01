package com.silu.minigram;

import android.content.Context;
import android.graphics.Color;

import androidx.annotation.ColorInt;
import androidx.core.content.ContextCompat;

/**
 * Maps the app's historical hard-coded colours onto the Ink &amp; Paper theme colours, so code that
 * still says {@code Color.parseColor("#1A73E8")} follows light/dark mode automatically.
 * Unknown values fall through to a plain parse.
 */
public final class Ink {
    private Ink() {}

    @ColorInt public static int res(int colorRes) {
        return ContextCompat.getColor(SketchApplication.getContext(), colorRes);
    }

    @ColorInt public static int c(String hex) {
        switch (hex.toUpperCase(java.util.Locale.ROOT)) {
            case "#1A73E8": case "#2196F3": case "#229ED9": case "#1565C0": case "#1976D2":
                return res(R.color.ink_accent);
            case "#9E9E9E": case "#757575": case "#8E8E93": case "#616161": case "#5A5A5A":
                return res(R.color.ink_text_2);
            case "#212121": case "#1A1A1A":
                return res(R.color.ink_text);
            case "#E53935": case "#FF3B30": case "#F44336":
                return res(R.color.ink_danger);
            case "#4CAF50": case "#1B5E20": case "#25D366":
                return res(R.color.ink_ok);
            case "#F0F0F0": case "#EEEEEE":
                return res(R.color.ink_surface_alt);
            case "#E0E0E0": case "#BDBDBD":
                return res(R.color.ink_line);
            default:
                return Color.parseColor(hex);
        }
    }
}
