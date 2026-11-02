package com.silu.minigram;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/** Chat bubble that never grows past ~80% of the screen, so long text wraps and short text hugs its content. */
public class BubbleLayout extends LinearLayout {
    public BubbleLayout(Context c) { super(c); }
    public BubbleLayout(Context c, AttributeSet a) { super(c, a); }
    public BubbleLayout(Context c, AttributeSet a, int s) { super(c, a, s); }

    @Override protected void onMeasure(int w, int h) {
        int cap = (int) (getResources().getDisplayMetrics().widthPixels * 0.80f);
        int mode = MeasureSpec.getMode(w), size = MeasureSpec.getSize(w);
        if (mode == MeasureSpec.UNSPECIFIED) w = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST);
        else if (size > cap) w = MeasureSpec.makeMeasureSpec(cap, mode == MeasureSpec.EXACTLY ? MeasureSpec.AT_MOST : mode);
        super.onMeasure(w, h);
    }
}
