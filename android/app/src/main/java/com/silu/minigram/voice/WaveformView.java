package com.silu.minigram.voice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.silu.minigram.R;

/** Scrolling bar waveform fed by LiveRecorder levels (Ink &amp; Paper design 04). */
public class WaveformView extends View {
    private final float[] levels = new float[64];
    private int head;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public WaveformView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        p.setColor(ContextCompat.getColor(c, R.color.ink_accent));
        setContentDescription("Live voice level");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void push(float level) {
        levels[head] = Math.max(0.04f, Math.min(1f, level));
        head = (head + 1) % levels.length;
        postInvalidateOnAnimation();
    }

    public void reset() { java.util.Arrays.fill(levels, 0.04f); head = 0; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        int n = levels.length;
        float w = getWidth(), h = getHeight(), slot = w / n, bar = slot * 0.5f;
        for (int i = 0; i < n; i++) {
            float lv = levels[(head + i) % n];
            float bh = Math.max(bar, lv * h);
            float cx = i * slot + slot / 2;
            r.set(cx - bar / 2, (h - bh) / 2, cx + bar / 2, (h + bh) / 2);
            p.setAlpha(80 + (int) (175 * i / (float) n));
            c.drawRoundRect(r, bar / 2, bar / 2, p);
        }
    }
}
