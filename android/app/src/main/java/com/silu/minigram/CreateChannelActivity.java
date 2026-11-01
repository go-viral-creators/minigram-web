package com.silu.minigram;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Locale;

import androidx.annotation.NonNull;

public class CreateChannelActivity extends AppCompatActivity {

    private Uri imageUri;
    private String uploadedPhotoUrl = "";
    private final InterstitialAdManager createdInterstitialAd =
        InterstitialAdManager.forChannelCreated();
    private String myUid;

    private LinearLayout linear118;
    private ScrollView   vscroll6;
    private TextView     btn_back;
    private TextView     textview94;
    private TextView     btn_done;
    private LinearLayout linear119;
    private android.widget.RelativeLayout relativelayout7;
    private TextView     textview96;
    private EditText     et_name;
    private View         view121;
    private TextView     textview97;
    private EditText     et_desc;
    private CheckBox     cb_private;
    private View         view122;
    private LinearLayout linear123;
    private ImageView    img_channel;
    private LinearLayout linear120;
    private TextView     textview95;
    private TextView     textview98;
    private TextView     textview99;

    // ── Handle field (injected programmatically after et_desc) ────────
    private EditText     et_handle;
    private TextView     tvHandleResult;   // NEW: realtime availability result

    private static final long HANDLE_CHECK_DEBOUNCE_MS = 400;
    private Handler  handleCheckHandler = new Handler(Looper.getMainLooper());
    private Runnable handleCheckRunnable;

    private LinearLayout loadingOverlay;
    private FirebaseAuth auth;
    private FirebaseDatabase database;

    @Override
    protected void onCreate(Bundle _savedInstanceState) {
        super.onCreate(_savedInstanceState);
        EdgeToEdgeHelper.setup(this);
        setContentView(R.layout.create_channel);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));
        initialize(_savedInstanceState);
        FirebaseApp.initializeApp(this);
        initializeLogic();
        com.google.android.gms.ads.MobileAds.initialize(this, initStatus -> {});
        createdInterstitialAd.preload(this);
    }

    private void initialize(Bundle _savedInstanceState) {
        linear118       = findViewById(R.id.linear118);
        vscroll6        = findViewById(R.id.vscroll6);
        btn_back        = findViewById(R.id.btn_back);
        textview94      = findViewById(R.id.textview94);
        btn_done        = findViewById(R.id.btn_done);
        linear119       = findViewById(R.id.linear119);
        relativelayout7 = findViewById(R.id.relativelayout7);
        textview96      = findViewById(R.id.textview96);
        et_name         = findViewById(R.id.et_name);
        view121         = findViewById(R.id.view121);
        textview97      = findViewById(R.id.textview97);
        et_desc         = findViewById(R.id.et_desc);
        cb_private      = findViewById(R.id.cb_private);
        view122         = findViewById(R.id.view122);
        linear123       = findViewById(R.id.linear123);
        img_channel     = findViewById(R.id.img_channel);
        linear120       = findViewById(R.id.linear120);
        textview95      = findViewById(R.id.textview95);
        textview98      = findViewById(R.id.textview98);
        textview99      = findViewById(R.id.textview99);

        // ── Inject handle label + field into linear119 after cb_private ──
        _injectHandleField();

        // ── Loading overlay ──────────────────────────────────────────────
        loadingOverlay = new LinearLayout(this);
        loadingOverlay.setBackgroundColor(0xAA000000);
        loadingOverlay.setGravity(android.view.Gravity.CENTER);
        loadingOverlay.setVisibility(View.GONE);
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        pb.getIndeterminateDrawable().setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
        loadingOverlay.addView(pb);
        android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT);
        ((ViewGroup) getWindow().getDecorView()).addView(loadingOverlay, flp);

        btn_back.setOnClickListener(v -> finish());
        relativelayout7.setOnClickListener(v -> _pickImage());
        linear120.setOnClickListener(v -> _pickImage());
        btn_done.setOnClickListener(v -> _validateAndCreate());
    }

    /** Programmatically inserts a Handle label + EditText into the scroll layout. */
    private void _injectHandleField() {
        int dp = (int) getResources().getDisplayMetrics().density;

        // Divider
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#EEEEEE"));
        LinearLayout.LayoutParams dvLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp); // 1dp
        dvLp.topMargin    = 8 * dp;
        dvLp.bottomMargin = 16 * dp;

        // Label
        TextView label = new TextView(this);
        label.setText("Channel Handle (unique @handle)");
        label.setTextSize(12f);
        label.setTextColor(Color.parseColor("#212121"));
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        // Hint note
        TextView hint = new TextView(this);
        hint.setText("4–20 chars: a-z, 0-9, _ only  ·  e.g. techworld");
        hint.setTextSize(11f);
        hint.setTextColor(Color.parseColor("#212121"));
        hint.setPadding(0, 2 * dp, 0, 4 * dp);

        // EditText
        et_handle = new EditText(this);
        et_handle.setHint("@handle");
        et_handle.setTextSize(16f);
        et_handle.setSingleLine(true);
        et_handle.setBackground(null);
        et_handle.setInputType(
            android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        // Realtime availability result (Checking... / ✓ available / ✗ taken)
        tvHandleResult = new TextView(this);
        tvHandleResult.setTextSize(12f);
        tvHandleResult.setPadding(0, 6 * dp, 0, 0);
        tvHandleResult.setVisibility(View.GONE);

        // Insert after cb_private (index of cb_private + 1 in linear119)
        int insertIdx = linear119.indexOfChild(view122); // before the bottom divider
        linear119.addView(divider,  insertIdx,   dvLp);
        linear119.addView(label,    insertIdx + 1);
        linear119.addView(hint,     insertIdx + 2);
        linear119.addView(et_handle, insertIdx + 3);
        linear119.addView(tvHandleResult, insertIdx + 4);

        _setupHandleLiveCheck();
    }

    /** Debounced realtime handle-availability check while user types. */
    private void _setupHandleLiveCheck() {
        et_handle.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String handle = s.toString().trim().toLowerCase(Locale.ENGLISH).replaceAll("\\s+", "");
                if (handleCheckRunnable != null) handleCheckHandler.removeCallbacks(handleCheckRunnable);

                if (handle.isEmpty()) {
                    tvHandleResult.setVisibility(View.GONE);
                    return;
                }
                if (!_isValidHandle(handle)) {
                    tvHandleResult.setVisibility(View.VISIBLE);
                    tvHandleResult.setTextColor(Color.parseColor("#E53935"));
                    tvHandleResult.setText("✗ 4–20 chars: a-z, 0-9, _ only");
                    return;
                }
                tvHandleResult.setVisibility(View.VISIBLE);
                tvHandleResult.setTextColor(Color.parseColor("#9E9E9E"));
                tvHandleResult.setText("Checking availability...");

                handleCheckRunnable = () -> _checkHandleLive(handle);
                handleCheckHandler.postDelayed(handleCheckRunnable, HANDLE_CHECK_DEBOUNCE_MS);
            }
        });
    }

    /** Queries Firebase live and updates the result label. Guards against stale results. */
    private void _checkHandleLive(String handle) {
        if (database == null) return;
        database.getReference("handles").child(handle)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    String current = et_handle.getText().toString().trim()
                            .toLowerCase(Locale.ENGLISH).replaceAll("\\s+", "");
                    if (!current.equals(handle)) return; // user kept typing, ignore stale result
                    if (snap.exists()) {
                        tvHandleResult.setTextColor(Color.parseColor("#E53935"));
                        tvHandleResult.setText("✗ @" + handle + " is already taken");
                    } else {
                        tvHandleResult.setTextColor(Color.parseColor("#2E7D32"));
                        tvHandleResult.setText("✓ @" + handle + " is available");
                    }
                }
                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    tvHandleResult.setTextColor(Color.parseColor("#E53935"));
                    tvHandleResult.setText("Couldn't check — network error");
                }
            });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handleCheckRunnable != null) handleCheckHandler.removeCallbacks(handleCheckRunnable);
    }

    private void initializeLogic() {
        auth     = FirebaseAuth.getInstance();
        database = FirebaseDatabase.getInstance();
        myUid    = auth.getCurrentUser().getUid();
    }

    private void _pickImage() {
        Intent intent = new Intent();
        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResult(Intent.createChooser(intent, "Select Channel Photo"), 3001);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 3001 && resultCode == RESULT_OK && data != null) {
            imageUri = data.getData();
            if (imageUri == null) return;
            Glide.with(getApplicationContext()).load(imageUri).circleCrop().into(img_channel);
            _uploadChannelPhoto();
        }
    }

    private void _uploadChannelPhoto() {
        _loading(true);
        UploadHelper.uploadImage(this, imageUri, new UploadHelper.UploadCallback() {
            @Override
            public void onSuccess(String url) {
                runOnUiThread(() -> {
                    uploadedPhotoUrl = url;
                    _loading(false);
                    Toast.makeText(CreateChannelActivity.this, "Photo uploaded!", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    _loading(false);
                    Toast.makeText(CreateChannelActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ─── Step 1: validate → check handle uniqueness → create ──────────────────

    private void _validateAndCreate() {
        String name   = et_name.getText().toString().trim();
        String handle = et_handle.getText().toString().trim()
                .toLowerCase(Locale.ENGLISH).replaceAll("\\s+", "");

        if (name.isEmpty()) {
            et_name.setError("Channel name is required");
            et_name.requestFocus();
            return;
        }
        if (handle.isEmpty()) {
            et_handle.setError("Handle is required");
            et_handle.requestFocus();
            return;
        }
        if (!_isValidHandle(handle)) {
            et_handle.setError("Handle must be 4–20 chars: a-z, 0-9, _ only");
            et_handle.requestFocus();
            return;
        }

        _loading(true);

        // Check global handle uniqueness
        database.getReference("handles").child(handle)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    if (snap.exists()) {
                        _loading(false);
                        et_handle.setError("@" + handle + " is already taken");
                        et_handle.requestFocus();
                        return;
                    }
                    _createChannel(handle);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    _loading(false);
                    Toast.makeText(CreateChannelActivity.this,
                        "Network error. Try again.", Toast.LENGTH_SHORT).show();
                }
            });
    }

    // ─── Step 2: atomic channel creation ──────────────────────────────────────

    private void _createChannel(String handle) {
        String name = et_name.getText().toString().trim();
        String desc = et_desc.getText().toString().trim();

        DatabaseReference channelsRef = database.getReference("channels");
        String channelId = channelsRef.push().getKey();
        if (channelId == null) { _loading(false); return; }

        long ts = System.currentTimeMillis();

        HashMap<String, Object> channelData = new HashMap<>();
        channelData.put("name",             name);
        channelData.put("name_lower",       name.toLowerCase(java.util.Locale.ENGLISH));
        channelData.put("type",             "channel");
        channelData.put("description",      desc);
        channelData.put("handle",           handle);
        channelData.put("photo_url",        uploadedPhotoUrl);
        channelData.put("owner_uid",        myUid);
        channelData.put("subscriber_count", 1);
        channelData.put("privacy",          cb_private.isChecked() ? "private" : "public");
        channelData.put("created_at",       ts);

        HashMap<String, Object> handleData = new HashMap<>();
        handleData.put("type", "channel");
        handleData.put("id",   channelId);

        // Atomic write: channel + handle registry
        HashMap<String, Object> multiWrite = new HashMap<>();
        multiWrite.put("channels/" + channelId,      channelData);
        multiWrite.put("handles/"  + handle,          handleData);

        database.getReference().updateChildren(multiWrite).addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                _loading(false);
                // The most likely cause of a rejected write here is the handle
                // getting claimed by someone else between our check and this
                // write (the rules now block overwriting an existing handle).
                et_handle.setError("@" + handle + " was just taken. Try another.");
                et_handle.requestFocus();
                Toast.makeText(this, "Couldn't create channel — handle may be taken.",
                    Toast.LENGTH_LONG).show();
                return;
            }

            database.getReference("channel_subscribers").child(channelId).child(myUid).setValue(true);
            database.getReference("user_channels").child(myUid).child(channelId).setValue(true);

            _loading(false);
            Toast.makeText(this, "Channel created!", Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(this, ChannelActivity.class);
            intent.putExtra("channel_id",    channelId);
            intent.putExtra("channel_name",  name);
            intent.putExtra("channel_photo", uploadedPhotoUrl);
            intent.putExtra("owner_uid",     myUid);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

            // Channel creation is a rare, deliberate action (not a frequent
            // flow like leaving a chat) — cap=1 means "show every time a
            // preloaded ad happens to be ready", no artificial throttling needed.
            createdInterstitialAd.maybeShow(this, () -> {
                startActivity(intent);
                finish();
            });
        });
    }

    // ─── Handle validation ────────────────────────────────────────────────────

    private boolean _isValidHandle(String handle) {
        if (handle == null || handle.isEmpty()) return false;
        return handle.matches("[a-z0-9_]{4,20}");
    }

    private void _loading(boolean show) {
        loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}