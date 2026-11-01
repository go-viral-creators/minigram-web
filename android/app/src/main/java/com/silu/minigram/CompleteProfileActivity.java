package com.silu.minigram;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Locale;

/**
 * Shown as a mandatory gate in two cases:
 *  1. Right after a brand-new account is created — replaces the old flow
 *     where a username was silently auto-generated from the person's
 *     email/name with nobody ever choosing it.
 *  2. Requirement 5 addition: a RETURNING user whose profile already
 *     exists but has no phone number yet (it became mandatory after these
 *     accounts were created) — LoginActivity/MainActivity route here too
 *     in that case.
 *
 * BUG FIXED: the first version of case 2 pre-filled fullname/username/bio
 * from the existing profile and then LOCKED them (disabled, uneditable) —
 * the idea being "only phone is actually missing, don't re-ask for the
 * rest." In practice this meant someone who spotted a typo in their own
 * name, or just wanted a different @handle, had no way to fix it here
 * (and nowhere else obviously pointed at this problem either). Both cases
 * now share ONE editable flow: fields are pre-filled where there's
 * existing data (or an email-derived suggestion for brand-new users —
 * previously only the full name got a suggestion, not the username), but
 * always changeable. Saving only touches the handle uniqueness registry
 * when the username actually changed from what was there before;
 * fullname/bio just overwrite (no uniqueness concern for those).
 *
 * Mandatory: Full Name, Username (uniqueness-checked live), Phone Number
 * (uniqueness-checked live). Optional: Bio. Back press is disabled — this
 * is a gate, not a skippable step.
 */
public class CompleteProfileActivity extends AppCompatActivity {

    private EditText   etFullname, etUsername, etBio, etCountryCode, etPhone;
    private TextView    txtFullnameError, txtUsernameError, txtUsernameStatus,
                         txtPhoneError, txtPhoneStatus, btnContinue;
    private ProgressBar progressUsernameCheck, progressPhoneCheck, progressSaving;

    private String myUid;
    private String myEmail;

    // Null for a brand-new signup (nothing to compare against yet); set to
    // the CURRENT saved handle for a returning user — used so typing your
    // own existing username back doesn't get flagged as "taken", and so
    // save only touches the handle registry when it actually changed.
    private String originalUsername = null;
    private boolean hasExistingProfile = false;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingUsernameCheck, pendingPhoneCheck;
    private boolean usernameAvailable = false;
    private String lastCheckedUsername = "";
    private boolean phoneAvailable = false;
    private String lastCheckedPhone = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdgeHelper.setup(this);
        setContentView(R.layout.complete_profile);

        myUid   = getIntent().getStringExtra("uid");
        myEmail = getIntent().getStringExtra("email");
        String suggestedName = getIntent().getStringExtra("suggested_name");

        if (myUid == null || myUid.isEmpty()) {
            // Shouldn't happen, but if it does there's nothing safe to do here.
            finish();
            return;
        }

        etFullname            = findViewById(R.id.et_fullname);
        etUsername            = findViewById(R.id.et_username);
        etBio                 = findViewById(R.id.et_bio);
        etCountryCode         = findViewById(R.id.et_country_code);
        etPhone               = findViewById(R.id.et_phone);
        txtFullnameError      = findViewById(R.id.txt_fullname_error);
        txtUsernameError      = findViewById(R.id.txt_username_error);
        txtUsernameStatus     = findViewById(R.id.txt_username_status);
        txtPhoneError         = findViewById(R.id.txt_phone_error);
        txtPhoneStatus        = findViewById(R.id.txt_phone_status);
        progressUsernameCheck = findViewById(R.id.progress_username_check);
        progressPhoneCheck    = findViewById(R.id.progress_phone_check);
        progressSaving        = findViewById(R.id.progress_saving);
        btnContinue           = findViewById(R.id.btn_continue);

        // Requirement 5 clarification: pre-filling here is ONLY correct for
        // a RETURNING user completing a missing phone (their real, already-
        // saved name/username) — see _loadExistingProfile() below. A
        // genuinely brand-new signup starts fully blank; suggestedName is
        // intentionally NOT applied here anymore.

        etUsername.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                _hideFieldError(txtUsernameError);
                String cleaned = s.toString().toLowerCase(Locale.ENGLISH);
                if (!cleaned.equals(s.toString())) {
                    etUsername.setText(cleaned);
                    etUsername.setSelection(cleaned.length());
                    return; // afterTextChanged will fire again with the cleaned text
                }
                _scheduleUsernameCheck(cleaned);
            }
        });

        TextWatcher phoneWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { _schedulePhoneCheck(); }
        };
        etCountryCode.addTextChangedListener(phoneWatcher);
        etPhone.addTextChangedListener(phoneWatcher);

        btnContinue.setOnClickListener(v -> _trySubmit());

        _loadExistingProfile();
    }

    /**
     * Pre-fills whatever's known, but never locks anything — see the class
     * doc comment for why. Two cases:
     *  • Existing profile (retrofit for a missing phone): pre-fill
     *    fullname/username/bio from what's already saved.
     *  • Brand-new signup: fullname already gets `suggested_name` from
     *    LoginActivity; username now ALSO gets a suggestion, derived from
     *    the email the same way — previously it started blank, so every
     *    new user had to think one up from nothing even though a
     *    reasonable starting point was sitting right there in their email.
     * Either way the live availability check runs immediately so the
     * status icon reflects the pre-filled value right away.
     */
    private void _loadExistingProfile() {
        FirebaseDatabase.getInstance().getReference("users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (snap.exists()) {
                        hasExistingProfile = true;
                        String fullname = snap.child("fullname").getValue(String.class);
                        String username = snap.child("handle").getValue(String.class);
                        String bio      = snap.child("bio").getValue(String.class);
                        originalUsername = username;

                        etFullname.setText(fullname != null ? fullname : "");
                        etUsername.setText(username != null ? username : "");
                        etBio.setText(bio != null ? bio : "");

                        TextView header = findViewById(R.id.txt_header);
                        if (header != null) header.setText("Add your phone number");
                        TextView sub = findViewById(R.id.txt_subheader);
                        if (sub != null) sub.setText("One more thing — this lets people who have your number find you, like WhatsApp/Telegram. You can also fix your name or username here if needed.");

                        if (username != null && !username.isEmpty()) _scheduleUsernameCheck(username);
                        etPhone.requestFocus();
                    }
                    // Brand-new signup (no existing profile): fullname and
                    // username both start fully blank — no email-derived
                    // suggestion. Left blank intentionally per clarification;
                    // _suggestUsernameFromEmail() below is now unused here
                    // but kept in case a future call site wants a starting
                    // suggestion again.
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    /**
     * Derives a starting @handle candidate from the local part of an email
     * (e.g. "john.doe97@gmail.com" → "john_doe97"), sanitized to match the
     * [a-z0-9_]{4,20} rule. Purely a starting SUGGESTION — the live check
     * runs on it same as anything typed, and the person can change it to
     * anything they want before continuing.
     */
    private String _suggestUsernameFromEmail(String email) {
        if (email == null || !email.contains("@")) return null;
        String local = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ENGLISH);
        String cleaned = local.replaceAll("[^a-z0-9_]", "_").replaceAll("_{2,}", "_");
        cleaned = cleaned.replaceAll("^_+", "").replaceAll("_+$", "");
        if (cleaned.length() < 4) cleaned = (cleaned + "user").substring(0, Math.max(cleaned.length(), 4));
        if (cleaned.length() > 20) cleaned = cleaned.substring(0, 20);
        return cleaned.isEmpty() ? null : cleaned;
    }

    // Mandatory gate — no skipping via back press. The Auth account already
    // exists; the only way forward is completing this form.
    @Override
    public void onBackPressed() {
        Toast.makeText(this,
            hasExistingProfile ? "Please add your phone number to continue"
                                : "Please choose a username to continue",
            Toast.LENGTH_SHORT).show();
    }

    // ── Live username availability check (debounced) ───────────────────────

    private void _scheduleUsernameCheck(String username) {
        usernameAvailable = false;
        txtUsernameStatus.setText("");
        if (pendingUsernameCheck != null) debounceHandler.removeCallbacks(pendingUsernameCheck);

        if (username.isEmpty()) {
            progressUsernameCheck.setVisibility(View.GONE);
            return;
        }
        if (!_isValidUsername(username)) {
            progressUsernameCheck.setVisibility(View.GONE);
            txtUsernameStatus.setText("close");
            txtUsernameStatus.setTextColor(0xFFE53935);
            return;
        }

        // Typing your own current username back — no need to hit the
        // server, it's trivially "available" to you.
        if (username.equals(originalUsername)) {
            progressUsernameCheck.setVisibility(View.GONE);
            usernameAvailable = true;
            lastCheckedUsername = username;
            txtUsernameStatus.setText("check_circle");
            txtUsernameStatus.setTextColor(0xFF2E7D32);
            _hideFieldError(txtUsernameError);
            return;
        }

        progressUsernameCheck.setVisibility(View.VISIBLE);
        pendingUsernameCheck = () -> _checkUsernameAvailability(username);
        debounceHandler.postDelayed(pendingUsernameCheck, 450);
    }

    private void _checkUsernameAvailability(String username) {
        FirebaseDatabase.getInstance().getReference("handles").child(username)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!username.equals(etUsername.getText().toString())) return; // stale response
                    progressUsernameCheck.setVisibility(View.GONE);
                    lastCheckedUsername = username;
                    String ownerUid = snap.child("id").getValue(String.class);
                    if (snap.exists() && !myUid.equals(ownerUid)) {
                        usernameAvailable = false;
                        txtUsernameStatus.setText("close");
                        txtUsernameStatus.setTextColor(0xFFE53935);
                        _showFieldError(txtUsernameError, "@" + username + " is already taken");
                    } else {
                        usernameAvailable = true;
                        txtUsernameStatus.setText("check_circle");
                        txtUsernameStatus.setTextColor(0xFF2E7D32);
                        _hideFieldError(txtUsernameError);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progressUsernameCheck.setVisibility(View.GONE);
                    lastCheckedUsername = ""; // force a re-check on submit rather than trust nothing
                    txtUsernameStatus.setText("error_outline");
                    txtUsernameStatus.setTextColor(0xFFFF9800);
                }
            });
    }

    private boolean _isValidUsername(String u) {
        return u.matches("[a-z0-9_]{4,20}");
    }

    // ── Live phone availability check (debounced) — same pattern as
    //    username above, against the phone_index/{e164WithoutPlus} node ─────

    /**
     * Combines country code + local number into E.164 (e.g. "+919876543210").
     * No phone-number library is assumed available in this project's fixed
     * Sketchware Pro library set, so this is a plain, deliberately simple
     * concatenation — not full libphonenumber-grade validation. Good enough
     * to produce a consistent, unique key for matching, which is this
     * field's actual job.
     */
    private String _toE164() {
        String cc = etCountryCode.getText().toString().trim();
        String num = etPhone.getText().toString().trim().replaceAll("[^0-9]", "");
        if (!cc.startsWith("+")) cc = "+" + cc.replaceAll("[^0-9]", "");
        if (num.isEmpty()) return null;
        return cc + num;
    }

    private boolean _isValidE164(String e164) {
        return e164 != null && e164.matches("\\+[1-9]\\d{7,14}");
    }

    private void _schedulePhoneCheck() {
        phoneAvailable = false;
        txtPhoneStatus.setText("");
        _hideFieldError(txtPhoneError);
        if (pendingPhoneCheck != null) debounceHandler.removeCallbacks(pendingPhoneCheck);

        String e164 = _toE164();
        if (e164 == null || e164.equals("+")) {
            progressPhoneCheck.setVisibility(View.GONE);
            return;
        }
        if (!_isValidE164(e164)) {
            progressPhoneCheck.setVisibility(View.GONE);
            txtPhoneStatus.setText("close");
            txtPhoneStatus.setTextColor(0xFFE53935);
            return;
        }

        progressPhoneCheck.setVisibility(View.VISIBLE);
        pendingPhoneCheck = () -> _checkPhoneAvailability(e164);
        debounceHandler.postDelayed(pendingPhoneCheck, 450);
    }

    private void _checkPhoneAvailability(String e164) {
        FirebaseDatabase.getInstance().getReference("phone_index").child(_phoneKey(e164))
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!e164.equals(_toE164())) return; // stale response
                    progressPhoneCheck.setVisibility(View.GONE);
                    lastCheckedPhone = e164;
                    String existingOwner = snap.getValue(String.class);
                    if (snap.exists() && !myUid.equals(existingOwner)) {
                        phoneAvailable = false;
                        txtPhoneStatus.setText("close");
                        txtPhoneStatus.setTextColor(0xFFE53935);
                        _showFieldError(txtPhoneError, "This number is already linked to another account");
                    } else {
                        phoneAvailable = true;
                        txtPhoneStatus.setText("check_circle");
                        txtPhoneStatus.setTextColor(0xFF2E7D32);
                        _hideFieldError(txtPhoneError);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progressPhoneCheck.setVisibility(View.GONE);
                    lastCheckedPhone = ""; // force a re-check on submit
                    txtPhoneStatus.setText("error_outline");
                    txtPhoneStatus.setTextColor(0xFFFF9800);
                }
            });
    }

    /**
     * phone_index keys can't contain "+" (Firebase path-key restriction),
     * so the leading + is dropped for the KEY only — the actual E.164 value
     * (with +) is still what's stored in users/{uid}/phone and compared
     * everywhere else.
     */
    private String _phoneKey(String e164) {
        return e164 != null && e164.startsWith("+") ? e164.substring(1) : e164;
    }

    // ── Submit ───────────────────────────────────────────────────────────────

    private void _trySubmit() {
        String fullname = etFullname.getText().toString().trim();
        String username  = etUsername.getText().toString().trim().toLowerCase(Locale.ENGLISH);
        String bio       = etBio.getText().toString().trim();
        String e164      = _toE164();

        _hideFieldError(txtFullnameError);
        _hideFieldError(txtUsernameError);
        _hideFieldError(txtPhoneError);

        if (fullname.isEmpty()) {
            _showFieldError(txtFullnameError, "Name is required");
            etFullname.requestFocus();
            return;
        }
        if (!_isValidUsername(username)) {
            _showFieldError(txtUsernameError, "4–20 chars: lowercase letters, numbers, underscore");
            etUsername.requestFocus();
            return;
        }
        if (!_isValidE164(e164)) {
            _showFieldError(txtPhoneError, "Enter a valid phone number with country code");
            etPhone.requestFocus();
            return;
        }

        boolean needsUsernameRecheck = !username.equals(lastCheckedUsername) || !usernameAvailable;
        boolean needsPhoneRecheck = !e164.equals(lastCheckedPhone) || !phoneAvailable;

        if (needsUsernameRecheck || needsPhoneRecheck) {
            // Re-check once synchronously before giving up, rather than risk
            // a stale "available" flag from an older keystroke.
            _loading(true);
            _revalidateThenSave(fullname, username, bio, e164, needsUsernameRecheck, needsPhoneRecheck);
            return;
        }

        _saveProfile(fullname, username, bio, e164);
    }

    private static final int RESULT_OK = 0, RESULT_TAKEN = 1, RESULT_CHECK_FAILED = 2;

    private void _revalidateThenSave(String fullname, String username, String bio, String e164,
                                      boolean checkUsername, boolean checkPhone) {
        final int[] usernameResult = {(!checkUsername || username.equals(originalUsername)) ? RESULT_OK : -1};
        final int[] phoneResult    = {!checkPhone ? RESULT_OK : -1};
        final int[] pending = {(checkUsername && !username.equals(originalUsername) ? 1 : 0) + (checkPhone ? 1 : 0)};

        Runnable finishCheck = () -> {
            _loading(false);
            if (usernameResult[0] == RESULT_TAKEN) {
                _showFieldError(txtUsernameError, "@" + username + " is already taken");
                return;
            }
            if (usernameResult[0] == RESULT_CHECK_FAILED) {
                _showFieldError(txtUsernameError, "Couldn't check @" + username + " — check your connection and try again");
                return;
            }
            if (phoneResult[0] == RESULT_TAKEN) {
                _showFieldError(txtPhoneError, "This number is already linked to another account");
                return;
            }
            if (phoneResult[0] == RESULT_CHECK_FAILED) {
                _showFieldError(txtPhoneError, "Couldn't verify this number — check your connection and try again");
                return;
            }
            _saveProfile(fullname, username, bio, e164);
        };

        if (checkUsername && !username.equals(originalUsername)) {
            FirebaseDatabase.getInstance().getReference("handles").child(username)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String ownerUid = snap.child("id").getValue(String.class);
                        boolean ok = !snap.exists() || myUid.equals(ownerUid);
                        usernameResult[0] = ok ? RESULT_OK : RESULT_TAKEN;
                        if (--pending[0] == 0) finishCheck.run();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        // BUG FIXED: this used to collapse straight to "taken",
                        // which is actively misleading — a DENIED read (e.g.
                        // rules.json not yet deployed with this node's rule)
                        // isn't the same thing as someone else actually
                        // having this handle, and told people their own,
                        // never-used-before choice was "already taken" no
                        // matter what they typed.
                        usernameResult[0] = RESULT_CHECK_FAILED;
                        if (--pending[0] == 0) finishCheck.run();
                    }
                });
        }
        if (checkPhone) {
            FirebaseDatabase.getInstance().getReference("phone_index").child(_phoneKey(e164))
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String existingOwner = snap.getValue(String.class);
                        boolean ok = !snap.exists() || myUid.equals(existingOwner);
                        phoneResult[0] = ok ? RESULT_OK : RESULT_TAKEN;
                        if (--pending[0] == 0) finishCheck.run();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        // Same fix as above — a failed/denied check is not
                        // "someone else has this number." This exact bug is
                        // what made EVERY phone number look "already taken"
                        // regardless of what was actually typed, whenever the
                        // phone_index rule wasn't live yet.
                        phoneResult[0] = RESULT_CHECK_FAILED;
                        if (--pending[0] == 0) finishCheck.run();
                    }
                });
        }
        if (pending[0] == 0) finishCheck.run();
    }

    private void _saveProfile(String fullname, String username, String bio, String e164) {
        _loading(true);
        DatabaseReference db = FirebaseDatabase.getInstance().getReference();
        HashMap<String, Object> multiWrite = new HashMap<>();

        multiWrite.put("users/" + myUid + "/fullname",       fullname);
        multiWrite.put("users/" + myUid + "/fullname_lower", fullname.toLowerCase(Locale.ENGLISH));
        multiWrite.put("users/" + myUid + "/bio",             bio);
        multiWrite.put(PrivateProfile.prefix(myUid) + "phone",           e164);
        multiWrite.put(PrivateProfile.prefix(myUid) + "phone_cc",        etCountryCode.getText().toString().trim());
        multiWrite.put(PrivateProfile.prefix(myUid) + "phone_number",    etPhone.getText().toString().trim());
        multiWrite.put("phone_index/" + _phoneKey(e164),      myUid);

        boolean usernameChanged = !username.equals(originalUsername);
        if (usernameChanged) {
            HashMap<String, Object> handleData = new HashMap<>();
            handleData.put("type", "user");
            handleData.put("id",   myUid);

            if (originalUsername != null && !originalUsername.isEmpty()) {
                multiWrite.put("handles/" + originalUsername, null); // release old
            }
            multiWrite.put("handles/" + username,          handleData); // claim new
            multiWrite.put("usernames/" + username,         myUid);      // legacy compat
            multiWrite.put("users/" + myUid + "/username",       username);
            multiWrite.put("users/" + myUid + "/username_lower", username);
            multiWrite.put("users/" + myUid + "/handle",          username);
        }

        if (!hasExistingProfile) {
            // Brand-new signup — a few fields only ever get set once.
            multiWrite.put(PrivateProfile.prefix(myUid) + "email",       myEmail != null ? myEmail : "");
            multiWrite.put("users/" + myUid + "/photo_url",   "");
            multiWrite.put("users/" + myUid + "/status",      "offline");
            multiWrite.put("users/" + myUid + "/created_at",  System.currentTimeMillis());
        }

        db.updateChildren(multiWrite).addOnCompleteListener(task -> {
            _loading(false);
            if (task.isSuccessful()) {
                Intent i = new Intent(this, ChatlistActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
            } else {
                // Most likely someone else grabbed this exact username/phone
                // in the split-second between our check and this write — the
                // handles/ and phone_index/ rules block overwriting an
                // existing entry, so this fails safely rather than
                // hijacking it.
                Toast.makeText(this, "That username or phone number was just taken — try again",
                    Toast.LENGTH_LONG).show();
                usernameAvailable = false;
                phoneAvailable = false;
            }
        });
    }

    // ── Small UI helpers (same reliable inline-error pattern used in
    //    EditProfileActivity — avoids Android's buggy TextView.setError(),
    //    which can blow up a row's height on some devices) ────────────────
    private void _showFieldError(TextView errorView, String message) {
        if (errorView == null) return;
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
    }

    private void _hideFieldError(TextView errorView) {
        if (errorView == null) return;
        errorView.setVisibility(View.GONE);
    }

    private void _loading(boolean show) {
        if (progressSaving != null) progressSaving.setVisibility(show ? View.VISIBLE : View.GONE);
        if (btnContinue != null) btnContinue.setEnabled(!show);
    }
}
