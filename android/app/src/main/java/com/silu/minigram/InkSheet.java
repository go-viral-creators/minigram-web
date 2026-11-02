package com.silu.minigram;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

/** Bottom sheet with an optional avatar header and icon rows (danger rows in red). */
final class InkSheet {
    interface OnPick { void pick(int index); }
    private InkSheet() {}

    static void show(Activity a, String title, String subtitle, String avatarUrl, String[] icons, String[] labels,
                     boolean[] danger, OnPick cb) {
        final float d = a.getResources().getDisplayMetrics().density;
        final BottomSheetDialog dlg = new BottomSheetDialog(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, (int) (8 * d), 0, (int) (16 * d));

        if (title != null) {
            LinearLayout head = new LinearLayout(a);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding((int) (24 * d), (int) (12 * d), (int) (24 * d), (int) (14 * d));
            ImageView av = new ImageView(a);
            av.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams((int) (48 * d), (int) (48 * d));
            alp.setMarginEnd((int) (14 * d));
            head.addView(av, alp);
            AvatarHelper.load(a, av, avatarUrl, title);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextSize(18);
            t.setTextColor(Ink.res(R.color.ink_text));
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            t.setSingleLine(true);
            col.addView(t);
            if (subtitle != null && !subtitle.isEmpty()) {
                TextView s = new TextView(a);
                s.setText(subtitle);
                s.setTextSize(13);
                s.setTextColor(Ink.res(R.color.ink_text_2));
                s.setSingleLine(true);
                col.addView(s);
            }
            head.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            box.addView(head);
            View line = new View(a);
            line.setBackgroundColor(Ink.res(R.color.ink_line));
            box.addView(line, new LinearLayout.LayoutParams(-1, 1));
        }
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            View row = LayoutInflater.from(a).inflate(R.layout.item_menu_row, box, false);
            ((LinearLayout.LayoutParams) (row.getLayoutParams() == null ? new LinearLayout.LayoutParams(-1, (int) (54 * d)) : row.getLayoutParams()))
                .height = (int) (54 * d);
            row.setPadding((int) (24 * d), 0, (int) (24 * d), 0);
            TextView ic = row.findViewById(R.id.mi_icon), lb = row.findViewById(R.id.mi_label);
            ic.setText(icons[i]);
            lb.setText(labels[i]);
            boolean red = danger != null && danger[i];
            ic.setTextColor(Ink.res(red ? R.color.ink_danger : R.color.ink_text_2));
            lb.setTextColor(Ink.res(red ? R.color.ink_danger : R.color.ink_text));
            row.setOnClickListener(v -> { dlg.dismiss(); cb.pick(idx); });
            box.addView(row);
        }
        dlg.setContentView(box);
        dlg.show();
    }
}
