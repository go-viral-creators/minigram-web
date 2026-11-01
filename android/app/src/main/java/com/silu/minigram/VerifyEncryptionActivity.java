package com.silu.minigram;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.silu.minigram.databinding.ActivityVerifyEncryptionBinding;
import com.silu.minigram.e2ee.E2ee;
import com.silu.minigram.e2ee.SafetyNumber;

/** Safety number + QR for a chat (Ink &amp; Paper design 05). Always FLAG_SECURE (see AppLock). */
public class VerifyEncryptionActivity extends AppCompatActivity {
    private ActivityVerifyEncryptionBinding b;
    private String chatId, peerUid, peerName, myUid;
    private E2ee.Safety safety;

    private final ActivityResultLauncher<ScanOptions> scanner =
        registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() == null || safety == null) return;
            if (SafetyNumber.matchesQr(result.getContents(), safety.number)) {
                E2ee.get(this, myUid).setVerified(chatId, true);
                Toast.makeText(this, "Verified ✓ Nobody is in the middle.", Toast.LENGTH_LONG).show();
                render();
            } else {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Numbers don't match")
                    .setMessage("The scanned code is different from yours. Someone may be intercepting this chat, or you scanned the wrong person's code.")
                    .setPositiveButton("OK", null).show();
            }
        });

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        b = ActivityVerifyEncryptionBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        EdgeToEdgeHelper.applyInsets(b.main);
        chatId = getIntent().getStringExtra("chat_id");
        peerUid = getIntent().getStringExtra("peer_uid");
        peerName = getIntent().getStringExtra("peer_name");
        myUid = FirebaseAuth.getInstance().getUid();
        if (chatId == null || peerUid == null || myUid == null) { finish(); return; }

        b.btnBack.setOnClickListener(v -> finish());
        b.txtPeer.setText(peerName != null ? peerName : "Contact");
        b.txtIntro.setText("Scan this QR with " + (peerName != null ? peerName : "your contact")
            + " or compare the numbers. If they match, nobody is listening in between.");
        b.btnScan.setOnClickListener(v -> scanner.launch(new ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
            .setOrientationLocked(true).setPrompt("Scan " + (peerName != null ? peerName : "contact") + "'s QR")));
        b.btnShare.setOnClickListener(v -> {
            if (safety == null) return;
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Minigram safety number: " + String.join(" ", safety.groups));
            startActivity(Intent.createChooser(i, "Share safety number"));
        });
        b.btnMarkVerified.setOnClickListener(v -> {
            E2ee.get(this, myUid).setVerified(chatId, true);
            render();
        });
        b.btnAcceptChange.setOnClickListener(v -> {
            E2ee.get(this, myUid).acceptKeyChange(chatId);
            load();
        });
        load();
    }

    private void load() {
        b.txtStatus.setText("Loading keys…");
        E2ee.get(this, myUid).loadSafety(chatId, peerUid, s -> {
            if (isFinishing() || isDestroyed()) return;
            safety = s;
            render();
        });
    }

    private void render() {
        if (safety == null) {
            b.txtStatus.setText("Your contact hasn't set up encryption yet, so there's nothing to verify.");
            b.btnScan.setEnabled(false); b.btnShare.setEnabled(false); b.btnMarkVerified.setEnabled(false);
            return;
        }
        E2ee e2 = E2ee.get(this, myUid);
        safety.verified = e2.isVerified(chatId);
        safety.keyChanged = e2.hasKeyChange(chatId);
        b.cardKeychange.setVisibility(safety.keyChanged ? android.view.View.VISIBLE : android.view.View.GONE);
        b.pillVerified.setVisibility(safety.verified && !safety.keyChanged ? android.view.View.VISIBLE : android.view.View.GONE);
        b.txtStatus.setText(safety.verified ? "You verified this chat." : "Not verified yet. Messages are still encrypted.");
        b.imgQr.setImageBitmap(qr(safety.qr));
        b.gridNumber.removeAllViews();
        for (String g : safety.groups) {
            TextView t = new TextView(this);
            t.setText(g);
            t.setTextAppearance(this, R.style.TextAppearance_Ink_Mono);
            t.setTextSize(18);
            t.setGravity(Gravity.CENTER);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0; lp.setMargins(0, 10, 0, 10);
            b.gridNumber.addView(t, lp);
        }
        b.gridNumber.setContentDescription("Safety number " + String.join(", ", safety.groups));
    }

    private Bitmap qr(String text) {
        try {
            BitMatrix m = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512);
            Bitmap bmp = Bitmap.createBitmap(m.getWidth(), m.getHeight(), Bitmap.Config.ARGB_8888);
            for (int x = 0; x < m.getWidth(); x++)
                for (int y = 0; y < m.getHeight(); y++) bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
            return bmp;
        } catch (Exception e) { return null; }
    }
}
