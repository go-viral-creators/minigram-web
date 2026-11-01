package com.silu.minigram;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.text.method.TransformationMethod;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.textview.MaterialTextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * TextView that shows Material icons reliably. Any TextView whose android:fontFamily is the icon font
 * gets (a) that typeface forced from code, (b) zero letter-spacing (Android switches ligatures off otherwise),
 * and (c) a display-only transformation that turns an icon name ("check_circle") into its code point, so the icon
 * shows even where ligature shaping misbehaves. getText() still returns the name, so existing logic is untouched.
 */
public class IconTextView extends MaterialTextView {
    private static volatile Map<String, String> CODES;

    public IconTextView(Context c, AttributeSet a) {
        super(c, a);
        if (a == null) return;
        TypedArray t = c.obtainStyledAttributes(a, new int[]{android.R.attr.fontFamily});
        int res = t.getResourceId(0, 0);
        t.recycle();
        if (res == R.font.material_icons || res == R.font.materialfont) {
            Typeface tf = ResourcesCompat.getFont(c, R.font.material_icons);
            if (tf != null) setTypeface(tf);
            setLetterSpacing(0f);
            setFontFeatureSettings("liga, rlig, clig");
            setIncludeFontPadding(false);
            final Map<String, String> codes = codes(c);
            setTransformationMethod(new TransformationMethod() {
                @Override public CharSequence getTransformation(CharSequence src, View v) {
                    if (src == null) return null;
                    String k = src.toString().trim();
                    String cp = codes.get(k);
                    return cp != null ? cp : src;
                }
                @Override public void onFocusChanged(View v, CharSequence s, boolean f, int d, android.graphics.Rect r) { }
            });
        }
    }

    private static Map<String, String> codes(Context c) {
        Map<String, String> m = CODES;
        if (m != null) return m;
        synchronized (IconTextView.class) {
            if (CODES != null) return CODES;
            Map<String, String> map = new HashMap<>(4096);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getApplicationContext().getResources().openRawResource(R.raw.material_icon_codes)))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int i = line.indexOf('=');
                    if (i > 0) map.put(line.substring(0, i), new String(Character.toChars(Integer.parseInt(line.substring(i + 1), 16))));
                }
            } catch (Exception ignored) { }
            CODES = map;
            return map;
        }
    }
}
