package com.silu.minigram;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Requirement — About Minigram, previously a BottomSheetDialogFragment
 * (AboutSheet), rebuilt as a dedicated Activity matching this project's
 * established screen design (white toolbar, grey background, rounded
 * white cards with section labels — same language as
 * PrivacySecurityActivity, ChannelProfileActivity, etc.) instead of a
 * generic bottom sheet.
 */
public class AboutActivity extends AppCompatActivity {

    private static final String PLAY_URL     = "https://play.google.com/store/apps/details?id=com.silu.minigram";
    private static final String TERMS_URL    = "https://fireclashpro.com/minigram/privacy/index.html#terms";
    private static final String SUPPORT_MAIL = "minigram783@gmail.com";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        EdgeToEdgeHelper.setup(this);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        ((TextView) findViewById(R.id.txt_version)).setText(_getVersionString());

        findViewById(R.id.row_contact).setOnClickListener(v -> _openMail());
        findViewById(R.id.row_rate).setOnClickListener(v -> _openUrl(PLAY_URL));
        findViewById(R.id.row_share).setOnClickListener(v -> _shareApp());
        findViewById(R.id.row_terms).setOnClickListener(v -> _openUrl(TERMS_URL));
    }

    private void _openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open browser", Toast.LENGTH_SHORT).show();
        }
    }

    private void _openMail() {
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + SUPPORT_MAIL));
            i.putExtra(Intent.EXTRA_SUBJECT, "Minigram Support");
            startActivity(Intent.createChooser(i, "Send Email"));
        } catch (Exception e) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Email", SUPPORT_MAIL));
            Toast.makeText(this, "Email copied: " + SUPPORT_MAIL, Toast.LENGTH_SHORT).show();
        }
    }

    private void _shareApp() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, "Chat & discover channels on Minigram \uD83D\uDCAC\n" + PLAY_URL);
        startActivity(Intent.createChooser(i, "Share Minigram"));
    }

    private String _getVersionString() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception e) {
            return "—";
        }
    }
}
