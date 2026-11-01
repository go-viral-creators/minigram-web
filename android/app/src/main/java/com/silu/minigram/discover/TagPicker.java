package com.silu.minigram.discover;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.silu.minigram.Ink;
import com.silu.minigram.R;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** "Topics" row for channel owners: pick up to 3 tags so Discover can match the channel to people's interests. */
public final class TagPicker {
    public static final int MAX = 3;
    private final Activity a;
    private final Set<String> selected = new LinkedHashSet<>();
    private final TextView row;
    private Runnable onChange;

    private TagPicker(Activity a, TextView row) { this.a = a; this.row = row; }

    /** Inserts the row right after {@code anchor} inside its parent. */
    public static TagPicker attachAfter(Activity a, View anchor) {
        TextView t = new TextView(a);
        t.setTextColor(Ink.res(R.color.ink_text));
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight((int) (48 * a.getResources().getDisplayMetrics().density));
        t.setBackgroundResource(R.drawable.card_bg);
        int p = (int) (14 * a.getResources().getDisplayMetrics().density);
        t.setPadding(p, 0, p, 0);
        ViewGroup parent = (ViewGroup) anchor.getParent();
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(-1, -2);
        lp.setMargins(p, p, p, 0);
        parent.addView(t, parent.indexOfChild(anchor) + 1, lp);
        TagPicker tp = new TagPicker(a, t);
        t.setOnClickListener(v -> tp.open());
        tp.refresh();
        return tp;
    }

    /** Appends the row at the end of {@code parent} (used inside dialogs). */
    public static TagPicker attachTo(Activity a, ViewGroup parent) {
        TextView t = new TextView(a);
        t.setTextColor(Ink.res(R.color.ink_text));
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight((int) (48 * a.getResources().getDisplayMetrics().density));
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(-1, -2);
        lp.topMargin = (int) (20 * a.getResources().getDisplayMetrics().density);
        parent.addView(t, lp);
        TagPicker tp = new TagPicker(a, t);
        t.setOnClickListener(v -> tp.open());
        tp.refresh();
        return tp;
    }

    public void setOnChange(Runnable r) { onChange = r; }
    public Set<String> selected() { return selected; }
    public void setSelected(Set<String> s) { selected.clear(); selected.addAll(s); refresh(); }

    private void refresh() {
        row.setText(selected.isEmpty() ? "Topics (helps people find this channel)  ›"
                                       : "Topics: " + String.join(", ", selected) + "  ›");
        row.setContentDescription(row.getText());
    }

    private void open() {
        final List<String> all = InterestTags.all();
        final boolean[] on = new boolean[all.size()];
        for (int i = 0; i < on.length; i++) on[i] = selected.contains(all.get(i));
        new AlertDialog.Builder(a).setTitle("Pick up to " + MAX + " topics")
            .setMultiChoiceItems(all.toArray(new String[0]), on, (d, which, checked) -> {
                int n = 0; for (boolean b : on) if (b) n++;
                if (checked && n > MAX) { on[which] = false; ((AlertDialog) d).getListView().setItemChecked(which, false); }
            })
            .setPositiveButton("Done", (d, w) -> {
                selected.clear();
                for (int i = 0; i < on.length; i++) if (on[i]) selected.add(all.get(i));
                refresh();
                if (onChange != null) onChange.run();
            }).setNegativeButton("Cancel", null).show();
    }
}
