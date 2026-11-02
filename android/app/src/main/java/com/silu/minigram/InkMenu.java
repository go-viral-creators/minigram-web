package com.silu.minigram;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/** Small anchored popup menu with Material icons (no emoji). Rows: icon name + label. */
final class InkMenu {
    interface OnPick { void pick(int index); }
    private InkMenu() {}

    static void show(Activity a, View anchor, String[] icons, String[] labels, OnPick cb) {
        final float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.menu_popup_bg);
        box.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
        final PopupWindow pw = new PopupWindow(box, (int) (232 * d), LinearLayout.LayoutParams.WRAP_CONTENT, true);
        pw.setElevation(12 * d);
        pw.setOutsideTouchable(true);
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            View row = LayoutInflater.from(a).inflate(R.layout.item_menu_row, box, false);
            ((TextView) row.findViewById(R.id.mi_icon)).setText(icons[i]);
            ((TextView) row.findViewById(R.id.mi_label)).setText(labels[i]);
            row.setOnClickListener(v -> { pw.dismiss(); cb.pick(idx); });
            box.addView(row);
        }
        pw.showAsDropDown(anchor, (int) (-184 * d), (int) (-4 * d));
    }
}
