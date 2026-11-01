package com.silu.minigram;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

import com.google.android.material.theme.MaterialComponentsViewInflater;

/** Inflates TextViews as {@link IconTextView} so icon-font TextViews render reliably. */
public class InkViewInflater extends MaterialComponentsViewInflater {
    @Override
    protected AppCompatTextView createTextView(Context context, AttributeSet attrs) {
        return new IconTextView(context, attrs);
    }
}
