package com.silu.minigram;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shown after an uncaught crash so the stack trace is readable instead of a silent close. */
public class DebugActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView tv = new TextView(this);
        tv.setTextIsSelectable(true);
        tv.setPadding(32, 96, 32, 48);
        tv.setText("Minigram crashed.\n\n" + getIntent().getStringExtra("error"));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
    }
}
