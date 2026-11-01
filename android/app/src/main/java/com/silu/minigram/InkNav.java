package com.silu.minigram;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

/** Wires the shared bottom navigation (view_bottom_nav.xml) used by Settings and Discover. */
final class InkNav {
    static final int CHATS = 0, DISCOVER = 1, CHANNELS = 2, SETTINGS = 3;
    static final String EXTRA_TAB = "tab";
    private InkNav() {}

    static void bind(Activity a, View root, int selected) {
        int[] items = {R.id.nav_linear83, R.id.nav_linear_discover, R.id.nav_linear84, R.id.nav_linear85};
        int[] inds = {R.id.nav_ind_chats, R.id.nav_ind_discover, R.id.nav_ind_channels, R.id.nav_ind_settings};
        int[] icons = {R.id.nav_icon_chats, R.id.nav_icon_discover, R.id.nav_icon_channels, R.id.nav_icon_profile};
        int[] texts = {R.id.nav_textview73, R.id.nav_textview_discover, R.id.nav_textview74, R.id.nav_textview75};
        int on = Ink.res(R.color.ink_text), off = Ink.res(R.color.ink_text_2);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            boolean sel = i == selected;
            root.findViewById(inds[i]).setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
            ((TextView) root.findViewById(icons[i])).setTextColor(sel ? on : off);
            TextView t = root.findViewById(texts[i]);
            t.setTextColor(sel ? on : off);
            t.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            root.findViewById(items[i]).setOnClickListener(v -> go(a, idx, selected));
        }
    }

    private static void go(Activity a, int target, int current) {
        if (target == current) return;
        Intent i;
        if (target == DISCOVER) i = new Intent(a, DiscoverActivity.class);
        else if (target == SETTINGS) i = new Intent(a, SettingsActivity.class);
        else {
            i = new Intent(a, ChatlistActivity.class);
            i.putExtra(EXTRA_TAB, target == CHANNELS ? 1 : 0);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        a.startActivity(i);
        a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        if (current == DISCOVER || current == SETTINGS) a.finish();
    }
}
