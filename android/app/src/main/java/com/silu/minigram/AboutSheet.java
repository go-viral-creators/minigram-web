package com.silu.minigram;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class AboutSheet extends BottomSheetDialogFragment {

    private static final String PLAY_URL     = "https://play.google.com/store/apps/details?id=com.silu.minigram";
    private static final String TERMS_URL    = "https://fireclashpro.com/minigram/privacy/index.html#terms";
    private static final String SUPPORT_MAIL = "minigram783@gmail.Com";

    public static AboutSheet show() { return new AboutSheet(); }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.sheet_about, container, false);

        // ── Card backgrounds ───────────────────────────────────────────────
        applyCardBg(v.findViewById(R.id.card_info));
        applyCardBg(v.findViewById(R.id.card_links));

        // ── Logo gradient circle ───────────────────────────────────────────
        GradientDrawable logoBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Ink.c("#1565C0"), Ink.c("#2196F3")});
        logoBg.setCornerRadius(dpToPx(20));
        v.findViewById(R.id.logo_circle).setBackground(logoBg);

        // ── Drag handle ────────────────────────────────────────────────────
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(Ink.c("#2E3248"));
        handleBg.setCornerRadius(dpToPx(2));
        v.findViewById(R.id.drag_handle).setBackground(handleBg);

        // ── Version ────────────────────────────────────────────────────────
        ((TextView) v.findViewById(R.id.txt_version)).setText(getVersionString());

        // ── Click listeners ────────────────────────────────────────────────
        v.findViewById(R.id.row_contact)     .setOnClickListener(x -> openMail());
        v.findViewById(R.id.row_rate)        .setOnClickListener(x -> openUrl(PLAY_URL));
        v.findViewById(R.id.row_share)       .setOnClickListener(x -> shareApp());
        v.findViewById(R.id.row_terms)       .setOnClickListener(x -> openUrl(TERMS_URL));

        // ── Ripple feedback on tappable rows ──────────────────────────────
        int[] tappable = {R.id.row_contact, R.id.row_rate, R.id.row_share,
                          R.id.row_terms};
        for (int id : tappable) applyRipple(v.findViewById(id));

        return v;
    }

    // ─── Actions ──────────────────────────────────────────────────────────────

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Browser open nahi hua", Toast.LENGTH_SHORT).show();
        }
    }

    private void openMail() {
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + SUPPORT_MAIL));
            i.putExtra(Intent.EXTRA_SUBJECT, "Minigram Support");
            startActivity(Intent.createChooser(i, "Send Email"));
        } catch (Exception e) {
            ClipboardManager cm = (ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null)
                cm.setPrimaryClip(ClipData.newPlainText("Email", SUPPORT_MAIL));
            Toast.makeText(requireContext(),
                    "Email copied: " + SUPPORT_MAIL, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareApp() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT,
                "Chat & discover channels on Minigram \uD83D\uDCAC\n" + PLAY_URL);
        startActivity(Intent.createChooser(i, "Share Minigram"));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String getVersionString() {
        try {
            PackageInfo pi = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception e) { return "—"; }
    }

    private void applyCardBg(View view) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Ink.res(R.color.ink_surface));
        gd.setCornerRadius(dpToPx(14));
        view.setBackground(gd);
        view.setClipToOutline(true);
    }

    private void applyRipple(View view) {
        StateListDrawable sl = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(Ink.c("#2A2E4A"));
        sl.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sl.addState(new int[]{}, new GradientDrawable());
        view.setBackground(sl);
        view.setClickable(true);
        view.setFocusable(true);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * requireContext().getResources().getDisplayMetrics().density);
    }
}
