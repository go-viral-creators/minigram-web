package com.silu.minigram;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.List;

/** Sticker picker: pack tabs on top, grid below. Tap = send. */
public class StickerSheet extends BottomSheetDialogFragment {
    public interface OnPick { void send(String stickerId); }
    private OnPick cb;
    public void setCallback(OnPick c) { cb = c; }

    private LinearLayout grid;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Nullable @Override
    public View onCreateView(@NonNull android.view.LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ink.res(R.color.ink_surface));
        root.setMinimumHeight(dp(420));

        HorizontalScrollView tabsScroll = new HorizontalScrollView(requireContext());
        tabsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(requireContext());
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(12), dp(12), dp(12), dp(8));
        tabsScroll.addView(tabs);
        root.addView(tabsScroll);

        ScrollView sv = new ScrollView(requireContext());
        grid = new LinearLayout(requireContext());
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(8), dp(4), dp(8), dp(24));
        sv.addView(grid);
        root.addView(sv, new LinearLayout.LayoutParams(-1, dp(360)));

        final List<Stickers.Pack> packs = Stickers.packs();
        final TextView[] tabViews = new TextView[packs.size()];
        for (int i = 0; i < packs.size(); i++) {
            final int idx = i;
            TextView t = new TextView(requireContext());
            t.setText(packs.get(i).title);
            t.setTextSize(14);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(16), 0, dp(16), 0);
            t.setMinHeight(dp(40));
            t.setOnClickListener(v -> { select(tabViews, idx); show(packs.get(idx)); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
            lp.setMarginEnd(dp(8));
            tabs.addView(t, lp);
            tabViews[i] = t;
        }
        select(tabViews, 0);
        show(packs.get(0));
        return root;
    }

    private void select(TextView[] tabs, int sel) {
        for (int i = 0; i < tabs.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(20));
            boolean on = i == sel;
            g.setColor(Ink.res(on ? R.color.ink_accent : R.color.ink_surface_alt));
            tabs[i].setBackground(g);
            tabs[i].setTextColor(Ink.res(on ? R.color.ink_on_accent : R.color.ink_text));
        }
    }

    private void show(Stickers.Pack pack) {
        grid.removeAllViews();
        boolean phrases = pack.items.get(0).startsWith("t:");
        int cols = phrases ? 2 : 4;
        LinearLayout row = null;
        for (int i = 0; i < pack.items.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            final String id = pack.items.get(i);
            TextView cell = new TextView(requireContext());
            cell.setGravity(Gravity.CENTER);
            if (phrases) {
                cell.setText(Stickers.phraseOf(id));
                cell.setTextColor(0xFFFFFFFF);
                cell.setTextSize(16);
                cell.setTypeface(null, android.graphics.Typeface.BOLD);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(14));
                g.setColor(Stickers.colorOf(id));
                cell.setBackground(g);
                cell.setPadding(dp(10), dp(18), dp(10), dp(18));
            } else {
                cell.setText(Stickers.emojiOf(id));
                cell.setTextSize(40);
                cell.setPadding(0, dp(10), 0, dp(10));
                cell.setBackgroundResource(R.drawable.btn_round_outline);
                cell.setBackground(null);
            }
            cell.setOnClickListener(v -> { dismiss(); if (cb != null) cb.send(id); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(cell, lp);
        }
        // pad last row so cells keep their width
        int rem = pack.items.size() % cols;
        if (rem != 0 && row != null) for (int k = rem; k < cols; k++) row.addView(new View(requireContext()), new LinearLayout.LayoutParams(0, 1, 1f));
    }
}
