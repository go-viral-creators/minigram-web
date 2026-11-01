package com.silu.minigram;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * App Lock enforcement — Requirement 1.
 *
 * BUG BEING FIXED: PrivacySecuritySheet's "App Lock" toggle previously did
 * nothing but save a SharedPreferences flag and show a toast claiming
 * "biometrics required on next launch" — no biometric prompt, no lock
 * screen, no enforcement existed ANYWHERE in the codebase (confirmed: no
 * androidx.biometric import or BiometricPrompt usage in the whole project).
 * Toggling it ON had literally zero effect on anything.
 *
 * Chose a PIN (not biometric) because androidx.biometric is not in this
 * project's fixed Sketchware Pro library set and per the project's own
 * rule ("if an API may not exist in your environment, ask before using it")
 * a new dependency shouldn't be assumed. A PIN needs nothing beyond
 * SharedPreferences + MessageDigest, both already guaranteed available, and
 * is itself a first-class passcode-lock option in Telegram/WhatsApp, not a
 * fallback. Biometric can be layered on top later as an *additional* unlock
 * option without changing this screen's contract, if the library turns out
 * to be available and wanted.
 *
 * Two modes, chosen by the caller via EXTRA_MODE:
 *   MODE_SETUP  — PrivacySecurityActivity uses this when App Lock is first
 *                 turned on and no PIN exists yet. Asks for a PIN, then asks
 *                 again to confirm, then stores its SHA-256 hash (never the
 *                 raw PIN) and finishes with RESULT_OK.
 *   MODE_VERIFY — MainActivity uses this at cold start when App Lock is on.
 *                 Asks for the PIN, checks it against the stored hash.
 *                 finish()es with RESULT_OK only on a correct match; the
 *                 system back button is disabled in this mode so the lock
 *                 can't be bypassed by backing out of it.
 *
 * KNOWN LIMITATION (documented, not silently glossed over): there is no
 * "forgot PIN" recovery flow yet — that needs a real identity check (e.g.
 * re-authenticating with the account password) to avoid becoming a security
 * hole itself, which is a separate feature. If this is needed, say so and
 * it can be added as its own follow-up.
 */
public class PinLockActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_SETUP  = "setup";
    public static final String MODE_VERIFY = "verify";

    public static final String KEY_PIN_HASH = "app_lock_pin_hash";

    private String mode;
    private String firstEntry; // holds the PIN from step 1 of setup, while confirming

    private TextView txtTitle, txtSubtitle, txtError;
    private EditText edtPin;
    private Button   btnAction;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pin_lock);
        EdgeToEdgeHelper.setup(this);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));

        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = MODE_VERIFY;

        txtTitle    = findViewById(R.id.txt_pin_title);
        txtSubtitle = findViewById(R.id.txt_pin_subtitle);
        txtError    = findViewById(R.id.txt_pin_error);
        edtPin      = findViewById(R.id.edt_pin);
        btnAction   = findViewById(R.id.btn_pin_action);

        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(Color.parseColor("#F0F0F0"));
        fieldBg.setCornerRadius(dp(12));
        edtPin.setBackground(fieldBg);

        edtPin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        if (MODE_SETUP.equals(mode)) {
            _showStep("Set a PIN", "Choose a 4–6 digit PIN to lock Minigram", "Continue");
        } else {
            _showStep("Enter PIN", "Enter your PIN to unlock Minigram", "Unlock");
            // MODE_VERIFY is the actual lock screen — there is nothing valid
            // behind it to reveal, so the system back gesture/button must
            // not be able to dismiss it. (Setup mode is reached from inside
            // the already-unlocked app, so back there behaves normally.)
        }

        btnAction.setOnClickListener(v -> _onActionTap());
        if (MODE_VERIFY.equals(mode) && BiometricGate.isEnabled(this)) {
            BiometricGate.unlock(this, new BiometricGate.Callback() {
                @Override public void onUnlocked() { AppLock.markUnlocked(); setResult(Activity.RESULT_OK); finish(); }
                @Override public void onFailed(boolean userCancelled) { /* PIN entry stays available */ }
            });
        }
        edtPin.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (txtError != null) txtError.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    @Override
    public void onBackPressed() {
        if (MODE_VERIFY.equals(mode)) {
            // No unlocked app to go back to — leaving here means leaving the
            // app entirely, exactly like a real lock screen.
            moveTaskToBack(true);
            return;
        }
        super.onBackPressed();
    }

    private void _showStep(String title, String subtitle, String actionLabel) {
        txtTitle.setText(title);
        txtSubtitle.setText(subtitle);
        btnAction.setText(actionLabel);
        edtPin.setText("");
        edtPin.requestFocus();
    }

    private void _onActionTap() {
        String entered = edtPin.getText() == null ? "" : edtPin.getText().toString().trim();

        if (entered.length() < 4 || entered.length() > 6) {
            _showError("PIN must be 4–6 digits");
            return;
        }

        if (MODE_SETUP.equals(mode)) {
            if (firstEntry == null) {
                // Step 1 done — ask again to confirm, so a typo doesn't
                // silently lock the user out of their own app.
                firstEntry = entered;
                _showStep("Confirm PIN", "Enter the same PIN again", "Confirm");
                return;
            }
            if (!firstEntry.equals(entered)) {
                _showError("PINs didn't match — try again");
                firstEntry = null;
                _showStep("Set a PIN", "Choose a 4–6 digit PIN to lock Minigram", "Continue");
                return;
            }
            _savePinHash(entered);
            setResult(Activity.RESULT_OK);
            finish();

        } else { // MODE_VERIFY
            long wait = PinVault.lockedForMs(this);
            if (wait > 0) {
                _showError("Too many attempts. Try again in " + Math.max(1, wait / 1000) + " s");
                edtPin.setText("");
                return;
            }
            if (PinVault.verify(this, entered)) {
                AppLock.markUnlocked();
                setResult(Activity.RESULT_OK);
                finish();
            } else {
                _showError("Incorrect PIN");
                edtPin.setText("");
                edtPin.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in));
            }
        }
    }

    private void _showError(String msg) {
        if (txtError == null) return;
        txtError.setText(msg);
        txtError.setVisibility(View.VISIBLE);
    }

    private void _savePinHash(String pin) {
        try { PinVault.setPin(this, pin); }
        catch (Exception e) { Toast.makeText(this, "Could not store PIN securely", Toast.LENGTH_LONG).show(); }
    }

    /** True once a PIN has actually been set — PrivacySecurityActivity and
     *  MainActivity both need this before treating App Lock as enforceable. */
    public static boolean hasPinSet(Context ctx) {
        return PinVault.hasPin(ctx);
    }

    private String _hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
            // SHA-256 and UTF-8 are both guaranteed present on every Android
            // device — this branch is unreachable in practice, but a PIN
            // that silently always "matches" on a hashing failure would be
            // a real security hole, so fail closed with an impossible value
            // instead of falling back to something comparable.
            return "unhashable";
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
