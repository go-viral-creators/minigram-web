package com.silu.minigram;

import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;
import java.util.*;

public class LoginActivity extends AppCompatActivity {

    // ─── Views ────────────────────────────────────────────────────────────────
    private FrameLayout  frame;
    private LinearLayout linear7;
    private ImageView    img_logo;
    private TextView     textview13;
    private TextView     textview14;
    private LinearLayout btn_google_login;
    private ImageView    imageview12;
    private TextView     textview191;
    private LinearLayout loadingOverlay;
    private ProgressBar  progressbar2;

    // ─── State ────────────────────────────────────────────────────────────────
    private FirebaseAuth auth;
    private String pendingEmail    = "";
    private String pendingFullname = "";

    private static final String SALT = "M!n!gr@m_S3cr3t#K3y_2024$";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdgeHelper.setup(this);
        setContentView(R.layout.login);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));
        initialize();
        FirebaseApp.initializeApp(this);
        initializeLogic();
    }

    private void initialize() {
        frame            = findViewById(R.id.frame);
        linear7          = findViewById(R.id.linear7);
        img_logo         = findViewById(R.id.img_logo);
        textview13       = findViewById(R.id.textview13);
        textview14       = findViewById(R.id.textview14);
        btn_google_login = findViewById(R.id.btn_google_login);
        imageview12      = findViewById(R.id.imageview12);
        textview191      = findViewById(R.id.textview191);
        loadingOverlay   = findViewById(R.id.loadingOverlay);
        progressbar2     = findViewById(R.id.progressbar2);

        btn_google_login.setOnClickListener(v -> {
            if (_hasEmailPermission()) _pickEmail();
        });
        _initEmailAuthUi();
    }

    // ─── Email + password: Sign in / Sign up / Forgot password ───────────────

    private boolean signUpMode = false;
    private com.google.android.material.textfield.TextInputLayout tilEmail, tilPassword, tilConfirm;
    private EditText etEmail, etPassword, etConfirm;
    private TextView btnForgot, txtPasswordHint;
    private com.google.android.material.button.MaterialButton btnPrimary;

    private void _initEmailAuthUi() {
        tilEmail = findViewById(R.id.til_email);
        tilPassword = findViewById(R.id.til_password);
        tilConfirm = findViewById(R.id.til_confirm);
        etEmail = findViewById(R.id.et_email);
        etPassword = findViewById(R.id.et_password);
        etConfirm = findViewById(R.id.et_confirm);
        btnForgot = findViewById(R.id.btn_forgot);
        txtPasswordHint = findViewById(R.id.txt_password_hint);
        btnPrimary = findViewById(R.id.btn_primary);
        com.google.android.material.button.MaterialButtonToggleGroup toggle = findViewById(R.id.toggle_mode);
        toggle.addOnButtonCheckedListener((g, id, checked) -> { if (checked) _setMode(id == R.id.btn_mode_signup); });
        btnPrimary.setOnClickListener(v -> _submit());
        btnForgot.setOnClickListener(v -> _forgotPassword());
        etPassword.setOnEditorActionListener((v, a, e) -> { if (!signUpMode) { _submit(); return true; } return false; });
        etConfirm.setOnEditorActionListener((v, a, e) -> { _submit(); return true; });
        _setMode(false);
    }

    private void _setMode(boolean signUp) {
        signUpMode = signUp;
        tilConfirm.setVisibility(signUp ? View.VISIBLE : View.GONE);
        txtPasswordHint.setVisibility(signUp ? View.VISIBLE : View.GONE);
        btnForgot.setVisibility(signUp ? View.GONE : View.VISIBLE);
        btnPrimary.setText(signUp ? "Create account" : "Sign in");
        textview14.setText(signUp ? "Create your account" : "Welcome back");
        tilEmail.setError(null); tilPassword.setError(null); tilConfirm.setError(null);
        etPassword.setImeOptions(signUp ? android.view.inputmethod.EditorInfo.IME_ACTION_NEXT : android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
    }

    private static boolean _strongEnough(String p) {
        boolean letter = false, digit = false;
        for (char c : p.toCharArray()) { if (Character.isLetter(c)) letter = true; if (Character.isDigit(c)) digit = true; }
        return p.length() >= 8 && letter && digit;
    }

    private void _hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
    }

    private void _submit() {
        String email = etEmail.getText().toString().trim().toLowerCase(Locale.ENGLISH);
        String pw = etPassword.getText().toString();
        tilEmail.setError(null); tilPassword.setError(null); tilConfirm.setError(null);
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { tilEmail.setError("Enter a valid email address"); etEmail.requestFocus(); return; }
        if (pw.isEmpty()) { tilPassword.setError("Enter your password"); etPassword.requestFocus(); return; }
        if (signUpMode) {
            if (!_strongEnough(pw)) { tilPassword.setError("Use 8+ characters with a letter and a number"); etPassword.requestFocus(); return; }
            if (!pw.equals(etConfirm.getText().toString())) { tilConfirm.setError("Passwords don't match"); etConfirm.requestFocus(); return; }
        }
        _hideKeyboard();
        _loadingBar(true);
        if (signUpMode) {
            auth.createUserWithEmailAndPassword(email, pw).addOnCompleteListener(this, task -> {
                _loadingBar(false);
                if (task.isSuccessful() && auth.getCurrentUser() != null) {
                    FirebaseUser u = auth.getCurrentUser();
                    try { u.sendEmailVerification(); } catch (Throwable ignored) { }
                    String raw = email.substring(0, email.indexOf('@'));
                    Intent i = new Intent(this, CompleteProfileActivity.class);
                    i.putExtra("uid", u.getUid());
                    i.putExtra("email", email);
                    i.putExtra("suggested_name", raw.isEmpty() ? "" : raw.substring(0, 1).toUpperCase(Locale.ENGLISH) + raw.substring(1).replace(".", " "));
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                } else {
                    Exception e = task.getException();
                    if (e instanceof com.google.firebase.auth.FirebaseAuthUserCollisionException) {
                        tilEmail.setError("This email already has an account. Sign in, or use Forgot password.");
                    } else if (e instanceof com.google.firebase.auth.FirebaseAuthWeakPasswordException) {
                        tilPassword.setError("That password is too weak");
                    } else _authError(e);
                }
            });
        } else {
            auth.signInWithEmailAndPassword(email, pw).addOnCompleteListener(this, task -> {
                _loadingBar(false);
                if (task.isSuccessful()) { _goToChatlist(); return; }
                Exception e = task.getException();
                if (e instanceof com.google.firebase.auth.FirebaseAuthInvalidUserException
                        || e instanceof com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
                    tilPassword.setError("Wrong email or password. Used Google before? Tap Forgot password to set one.");
                } else _authError(e);
            });
        }
    }

    private void _authError(Exception e) {
        String m;
        if (e instanceof com.google.firebase.FirebaseNetworkException) m = "No internet connection. Check your network and try again.";
        else if (e instanceof com.google.firebase.FirebaseTooManyRequestsException) m = "Too many attempts. Please wait a few minutes and try again.";
        else m = "Something went wrong. Please try again.";
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    private void _forgotPassword() {
        final com.google.android.material.textfield.TextInputLayout til = new com.google.android.material.textfield.TextInputLayout(this);
        til.setHint("Email");
        til.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        final com.google.android.material.textfield.TextInputEditText in = new com.google.android.material.textfield.TextInputEditText(til.getContext());
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        in.setText(etEmail.getText());
        til.addView(in);
        int p = (int) (24 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(p, p / 2, p, 0);
        box.addView(til);
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Reset password")
            .setMessage("We'll email you a link to choose a new password.")
            .setView(box)
            .setPositiveButton("Send link", (d, w) -> {
                String email = in.getText() == null ? "" : in.getText().toString().trim().toLowerCase(Locale.ENGLISH);
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    Toast.makeText(this, "Enter a valid email address", Toast.LENGTH_SHORT).show();
                    return;
                }
                _loadingBar(true);
                auth.sendPasswordResetEmail(email).addOnCompleteListener(this, t -> {
                    _loadingBar(false);
                    // same message whether or not the account exists, so nobody can probe which emails are registered
                    if (t.getException() instanceof com.google.firebase.FirebaseNetworkException) _authError(t.getException());
                    else Toast.makeText(this, "If an account exists for " + email + ", a reset link is on its way. Check spam too.", Toast.LENGTH_LONG).show();
                });
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void initializeLogic() {
        auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() != null) {
            _goToChatlist();
        }
    }

    private String _generatePassword(String email) {
        try {
            String input = email.toLowerCase(Locale.ENGLISH).trim() + SALT;
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(input.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) hex.append(String.format("%02x", b));
            return hex.toString().substring(0, 40);
        } catch (Exception e) {
            String rev = new StringBuilder(email).reverse().toString();
            return android.util.Base64.encodeToString(
                rev.getBytes(), android.util.Base64.NO_WRAP).substring(0, 20);
        }
    }

    // ─── Google account picker ────────────────────────────────────────────────

    private void _pickEmail() {
        android.accounts.AccountManager am = android.accounts.AccountManager.get(this);
        android.accounts.Account[] accounts = am.getAccountsByType("com.google");

        if (accounts.length == 0) {
            Toast.makeText(this, "No Google accounts found", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<String> emails = new ArrayList<>();
        for (android.accounts.Account acc : accounts) emails.add(acc.name);

        new android.app.AlertDialog.Builder(this)
            .setTitle("Sign in with Google")
            .setItems(emails.toArray(new String[0]), (dialog, which) -> {
                pendingEmail = emails.get(which);
                String raw = pendingEmail.contains("@")
                    ? pendingEmail.substring(0, pendingEmail.indexOf("@")) : pendingEmail;
                pendingFullname = raw.length() > 0
                    ? raw.substring(0, 1).toUpperCase(Locale.ENGLISH)
                        + raw.substring(1).replace(".", " ")
                    : raw;
                _signIn();
            })
            .show();
    }

    // ─── Auth flow ────────────────────────────────────────────────────────────

    private void _signIn() {
        _loadingBar(true);
        String securePassword = _generatePassword(pendingEmail);

        auth.signInWithEmailAndPassword(pendingEmail, securePassword)
            .addOnCompleteListener(this, task -> {
                if (task.isSuccessful()) {
                    _loadingBar(false);
                    _goToChatlist();
                } else {
                    String err = task.getException() != null
                        ? task.getException().getMessage() : "";
                    if (err != null && err.contains("INVALID_LOGIN_CREDENTIALS")) {
                        _createAccount(securePassword);
                    } else {
                        _loadingBar(false);
                        SketchwareUtil.showMessage(getApplicationContext(),
                            err != null ? err : "Sign in failed. Try again.");
                    }
                }
            });
    }

    private void _createAccount(String securePassword) {
        auth.createUserWithEmailAndPassword(pendingEmail, securePassword)
            .addOnCompleteListener(this, task -> {
                _loadingBar(false);
                if (task.isSuccessful()) {
                    FirebaseUser user = auth.getCurrentUser();
                    if (user != null) {
                        // Used to call _createUserProfile() here, which
                        // silently auto-generated a username nobody chose
                        // (e.g. "john123_4821") and went straight to the
                        // chat list. Now the account exists but has NO
                        // profile yet — CompleteProfileActivity is a
                        // mandatory gate that writes the real profile once
                        // the person has actually picked their own @handle.
                        Intent i = new Intent(this, CompleteProfileActivity.class);
                        i.putExtra("uid", user.getUid());
                        i.putExtra("email", pendingEmail);
                        i.putExtra("suggested_name", pendingFullname);
                        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                    }
                } else {
                    String err = task.getException() != null
                        ? task.getException().getMessage() : "Account creation failed";
                    SketchwareUtil.showMessage(getApplicationContext(), err);
                }
            });
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void _goToChatlist() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) { _actuallyGoToChatlist(); return; }
        FirebaseDatabase.getInstance().getReference("users").child(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!snap.exists()) {
                        // Auth account exists but the profile was never
                        // finished — e.g. the app was killed right after
                        // signup, before CompleteProfileActivity was
                        // submitted. Send them back to finish it instead of
                        // into a Chatlist with no profile behind it.
                        Intent i = new Intent(getApplicationContext(), CompleteProfileActivity.class);
                        i.putExtra("uid", uid);
                        i.putExtra("email", FirebaseAuth.getInstance().getCurrentUser() != null
                            ? FirebaseAuth.getInstance().getCurrentUser().getEmail() : "");
                        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                        return;
                    }
                    PrivateProfile.readOwn(uid, (phone, cc, num) -> {
                    if (phone == null || phone.isEmpty()) {
                        // Requirement 5 — phone number became mandatory after
                        // this account was created. CompleteProfileActivity
                        // detects an already-complete profile on its own and
                        // only asks for the missing phone in that case —
                        // fullname/username/bio aren't touched or re-asked.
                        Intent i = new Intent(getApplicationContext(), CompleteProfileActivity.class);
                        i.putExtra("uid", uid);
                        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                        return;
                    }
                    if (Boolean.TRUE.equals(snap.child("is_banned").getValue(Boolean.class))) {
                        // Stay signed in — BannedActivity watches for a
                        // realtime unban; rules block banned writes anyway.
                        Intent i = new Intent(getApplicationContext(), BannedActivity.class);
                        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                    } else {
                        _actuallyGoToChatlist();
                    }
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    // Read denied/failed — err on the side of letting them in;
                    // ChatlistActivity ka live listener bhi enforce karega.
                    _actuallyGoToChatlist();
                }
            });
    }

    private void _actuallyGoToChatlist() {
        Intent i = new Intent(getApplicationContext(), ChatlistActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void _loadingBar(boolean visible) {
        if (visible) {
            loadingOverlay.setAlpha(0f);
            loadingOverlay.setVisibility(View.VISIBLE);
            loadingOverlay.animate().alpha(1f).setDuration(200);
        } else {
            loadingOverlay.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> loadingOverlay.setVisibility(View.GONE));
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != 1001) return;
        boolean ok = res.length > 0;
        for (int r : res) if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) ok = false;
        if (ok) _pickEmail();
        else Toast.makeText(this, "Allow Accounts + Contacts access to choose your Google account", Toast.LENGTH_LONG).show();
    }

    private boolean _hasEmailPermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        if (checkSelfPermission(android.Manifest.permission.GET_ACCOUNTS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissions(new String[]{
            android.Manifest.permission.GET_ACCOUNTS,
            android.Manifest.permission.READ_CONTACTS
        }, 1001);
        return false;
    }
}
