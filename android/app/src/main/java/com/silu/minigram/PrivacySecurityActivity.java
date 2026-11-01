package com.silu.minigram;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Switch;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.FirebaseDatabase;

/**
 * Requirement 1 — dedicated Privacy & Security screen, replacing the old
 * PrivacySecuritySheet BottomSheetDialogFragment. Reachable from
 * SettingsActivity exactly where the sheet used to open from.
 *
 * What changed vs the old sheet, and why (each is a real functional gap
 * found while auditing — not cosmetic):
 *
 *  • App Lock — previously saved a pref and showed a toast promising
 *    "biometrics required on next launch"; nothing anywhere enforced it —
 *    no biometric/PIN screen existed in the whole project. Now wired to
 *    PinLockActivity: turning this ON walks straight into PIN setup if no
 *    PIN exists yet, and MainActivity actually gates app entry on it (see
 *    that file's changes). See PinLockActivity's doc comment for why PIN
 *    instead of biometric.
 *
 *  • Online Status visibility — the pref was saved and used to write
 *    "offline" once at toggle time, but ChatActivity/ChatlistActivity's
 *    onResume() unconditionally wrote "online" back on every screen visit,
 *    regardless of this setting — so the toggle had no lasting effect.
 *    Both files now check this pref before writing "online" (see their
 *    changes).
 *
 *  • Last Seen visibility — this toggle referred to a feature that didn't
 *    actually exist anywhere: no "last active" timestamp was ever stored,
 *    only a live online/offline flag, and nothing displayed a "last seen"
 *    time. Built the underlying feature (a last_active_ts field, written
 *    when going offline, and an actual "last seen X ago" label in
 *    ChatActivity — see that file) so this toggle has something real to
 *    control.
 *
 *  • Read Receipts — the pref was saved but ChatActivity always wrote
 *    read_at on every message it marked read, regardless of this setting.
 *    Now gated (see ChatActivity's changes) — turning it off means the
 *    peer no longer sees a read receipt for messages read from your side;
 *    you still read your own messages locally either way.
 *
 *  • Notification Preview and Delete Account were already fully wired
 *    correctly and are carried over unchanged.
 *
 *  • Clear Cache — moved out entirely, per Requirement 1, to
 *    DataStorageSheet (Data & Storage page). Not duplicated here.
 */
public class PrivacySecurityActivity extends AppCompatActivity {

    // ── SharedPreferences — same keys the rest of the app already reads,
    // unchanged so ChatActivity/ChatlistActivity/FCMNotificationSender don't
    // need to change what they look up, only whether they respect it.
    public static final String PREFS          = "minigram_privacy";
    public static final String KEY_APP_LOCK   = "app_lock_enabled";
    public static final String KEY_NOTIF_PREV = "notif_preview_enabled";
    public static final String KEY_READ_RCPT  = "read_receipts_enabled";
    public static final String KEY_ONLINE_VIS = "online_visible";
    public static final String KEY_LAST_SEEN  = "last_seen_visible";
    public static final String KEY_SCREENSHOT = "screenshot_disabled";

    private static final String PRIVACY_URL = "https://fireclashpro.com/minigram/privacy/index.html";

    private SharedPreferences prefs;
    private Switch swAppLock;

    private final ActivityResultLauncher<Intent> pinSetupLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) {
                prefs.edit().putBoolean(KEY_APP_LOCK, true).apply();
                if (swAppLock != null) swAppLock.setChecked(true);
                Toast.makeText(this, "App Lock enabled", Toast.LENGTH_SHORT).show();
            } else {
                // Setup cancelled/back-pressed mid-flow — don't leave the
                // switch showing ON for a lock that was never actually set.
                if (swAppLock != null) swAppLock.setChecked(false);
            }
        });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy_security);
        EdgeToEdgeHelper.setup(this);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        // ── Card backgrounds ───────────────────────────────────────────────
        for (int id : new int[]{R.id.card_security, R.id.card_privacy,
                                R.id.card_notif, R.id.card_account}) {
            applyCardBg(findViewById(id));
        }

        // ── Tip box background ─────────────────────────────────────────────
        GradientDrawable tipBg = new GradientDrawable();
        tipBg.setColor(Ink.c("#1A2744"));
        tipBg.setCornerRadius(dp(10));
        findViewById(R.id.tip_box).setBackground(tipBg);

        // ── Ripple on tappable rows ────────────────────────────────────────
        for (int id : new int[]{R.id.row_privacy_policy, R.id.row_delete_account}) {
            applyRipple(findViewById(id));
        }

        // ── Load saved pref values into switches ───────────────────────────
        swAppLock            = findViewById(R.id.sw_app_lock);
        Switch swScreenshot  = findViewById(R.id.sw_screenshot);
        Switch swOnline      = findViewById(R.id.sw_online);
        Switch swLastSeen    = findViewById(R.id.sw_last_seen);
        Switch swReadRcpt    = findViewById(R.id.sw_read_rcpt);
        Switch swNotifPrev   = findViewById(R.id.sw_notif_prev);

        // App Lock's checked state must reflect reality: ON only means
        // something if a PIN actually exists. If the pref says true but no
        // PIN was ever completed (e.g. setup was killed mid-flow in an
        // earlier session), treat it as off rather than show a lock that
        // can't be enforced.
        boolean appLockOn = prefs.getBoolean(KEY_APP_LOCK, false) && PinLockActivity.hasPinSet(this);
        swAppLock.setChecked(appLockOn);

        swScreenshot.setChecked(prefs.getBoolean(KEY_SCREENSHOT, false));
        swOnline    .setChecked(prefs.getBoolean(KEY_ONLINE_VIS, true));
        swLastSeen  .setChecked(prefs.getBoolean(KEY_LAST_SEEN,  true));
        swReadRcpt  .setChecked(prefs.getBoolean(KEY_READ_RCPT,  true));
        swNotifPrev .setChecked(prefs.getBoolean(KEY_NOTIF_PREV, true));

        // ── Switch listeners ───────────────────────────────────────────────

        // App Lock — real enforcement via PinLockActivity, not just a pref.
        swAppLock.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) {
                if (PinLockActivity.hasPinSet(this)) {
                    // PIN already exists from a previous session — just
                    // re-enable enforcement with it, no need to set it again.
                    prefs.edit().putBoolean(KEY_APP_LOCK, true).apply();
                    Toast.makeText(this, "App Lock enabled", Toast.LENGTH_SHORT).show();
                } else {
                    Intent i = new Intent(this, PinLockActivity.class);
                    i.putExtra(PinLockActivity.EXTRA_MODE, PinLockActivity.MODE_SETUP);
                    pinSetupLauncher.launch(i);
                }
            } else {
                prefs.edit().putBoolean(KEY_APP_LOCK, false).apply();
                Toast.makeText(this, "App Lock disabled", Toast.LENGTH_SHORT).show();
            }
        });

        // Screenshot Protection — applies FLAG_SECURE to THIS window
        // immediately (same as before), and now also to every chat/channel
        // screen going forward via the pref they already read on resume.
        swScreenshot.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(KEY_SCREENSHOT, isChecked).apply();
            if (isChecked) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            else            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            Toast.makeText(this,
                    isChecked ? "Screenshot protection ON" : "Screenshot protection OFF",
                    Toast.LENGTH_SHORT).show();
        });

        // Online Status — ChatActivity/ChatlistActivity now check this
        // before writing "online" on resume (see those files).
        swOnline.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(KEY_ONLINE_VIS, isChecked).apply();
            String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                    ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
            if (myUid != null && !isChecked) {
                FirebaseDatabase.getInstance().getReference("users")
                        .child(myUid).child("status").setValue("offline");
            }
            Toast.makeText(this,
                    isChecked ? "You appear online to others"
                              : "You appear offline to others",
                    Toast.LENGTH_SHORT).show();
        });

        // Last Seen — ChatActivity now reads last_active_ts and this pref
        // together to decide what (if anything) to show the peer.
        swLastSeen.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(KEY_LAST_SEEN, isChecked).apply();
            Toast.makeText(this,
                    isChecked ? "Last Seen visible to others"
                              : "Last Seen hidden",
                    Toast.LENGTH_SHORT).show();
        });

        // Read Receipts — ChatActivity now checks this before writing
        // read_at for messages it marks read.
        swReadRcpt.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(KEY_READ_RCPT, isChecked).apply();
            Toast.makeText(this,
                    isChecked ? "Read receipts ON"
                              : "Read receipts OFF",
                    Toast.LENGTH_SHORT).show();
        });

        // Notification Preview — FCMNotificationSender already reads this.
        swNotifPrev.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(KEY_NOTIF_PREV, isChecked).apply();
            Toast.makeText(this,
                    isChecked ? "Message preview ON in notifications"
                              : "Message preview OFF in notifications",
                    Toast.LENGTH_SHORT).show();
        });

        // ── Tappable row listeners ─────────────────────────────────────────

        findViewById(R.id.row_privacy_policy).setOnClickListener(x -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)));
            } catch (Exception e) {
                Toast.makeText(this, "Browser open nahi hua", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.row_delete_account).setOnClickListener(x -> confirmDeleteAccount());
    }

    // ─── Confirm dialogs ──────────────────────────────────────────────────────

    private void confirmDeleteAccount() {
        new AlertDialog.Builder(this)
                .setTitle("⚠️ Delete Account")
                .setMessage("This will PERMANENTLY delete your Minigram account, " +
                        "profile, username, and any channels you own.\n\n" +
                        "Chats you've had with others stay in THEIR chat list (same as " +
                        "Telegram/WhatsApp) — you'll just show as a deleted account there.\n\n" +
                        "This action CANNOT be undone.")
                .setPositiveButton("Delete My Account", (d, w) -> _startAccountDeletion())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Full account deletion, in the CORRECT order. Carried over from
     * PrivacySecuritySheet UNCHANGED (already correct — see the original
     * bug-fix notes below, kept for context since they still apply here).
     *
     * Bug fixed #1 (ordering): the old version deleted the RTDB profile
     * FIRST and the Firebase Auth account SECOND. If the Auth deletion
     * failed (very common — Firebase requires a *recent* sign-in for
     * account deletion, so it fails with FirebaseAuthRecentLoginRequired
     * for anyone who's been logged in a while), the user was left in a
     * broken state: authenticated, but with no profile — a ghost account
     * still able to poke around a half-deleted UI. Fixed by doing ALL data
     * cleanup first (while still authenticated, since the Firebase rules
     * require auth.uid == the acting user for these writes), and only
     * deleting the Auth account — the point of no return — LAST.
     *
     * Bug fixed #2 (incomplete cleanup): the old version only removed
     * users/{uid}. Left behind forever: their @handle (permanently
     * unusable by anyone, including them, ever again), their own chat/
     * channel list entries, their channel subscriptions, and — worst —
     * any channel they OWNED became ownerless and unmanageable forever.
     * All of that is now cleaned up. Chat/channel-post CONTENT involving
     * other people is deliberately left alone — deleting someone's own
     * account shouldn't erase messages out of someone else's chat history.
     */
    private void _startAccountDeletion() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        final String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        final DatabaseReference root = FirebaseDatabase.getInstance().getReference();

        _showDeletingProgress(true);

        root.child("users").child(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot userSnap) {
                String handle = userSnap.child("handle").getValue(String.class);

                root.child("user_channels").child(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot channelsSnap) {
                        java.util.List<String> ownedChannelIds = new java.util.ArrayList<>();
                        java.util.List<String> subscribedOnlyIds = new java.util.ArrayList<>();
                        int total = (int) channelsSnap.getChildrenCount();

                        if (total == 0) {
                            _cleanupUserRecordsAndDeleteAuth(uid, handle);
                            return;
                        }

                        final int[] remaining = {total};
                        for (DataSnapshot ch : channelsSnap.getChildren()) {
                            String channelId = ch.getKey();
                            root.child("channels").child(channelId).child("owner_uid")
                                .addListenerForSingleValueEvent(new ValueEventListener() {
                                    @Override public void onDataChange(DataSnapshot ownerSnap) {
                                        if (uid.equals(ownerSnap.getValue(String.class))) {
                                            ownedChannelIds.add(channelId);
                                        } else {
                                            subscribedOnlyIds.add(channelId);
                                        }
                                        if (--remaining[0] == 0) {
                                            _cleanupChannelsThenFinish(uid, handle, ownedChannelIds, subscribedOnlyIds);
                                        }
                                    }
                                    @Override public void onCancelled(DatabaseError e) {
                                        subscribedOnlyIds.add(channelId);
                                        if (--remaining[0] == 0) {
                                            _cleanupChannelsThenFinish(uid, handle, ownedChannelIds, subscribedOnlyIds);
                                        }
                                    }
                                });
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        _cleanupUserRecordsAndDeleteAuth(uid, handle);
                    }
                });
            }
            @Override public void onCancelled(DatabaseError e) {
                _finishDeletionFailed("Could not read your profile. Check your connection and try again.");
            }
        });
    }

    private void _cleanupChannelsThenFinish(String uid, String handle,
                                             java.util.List<String> ownedChannelIds,
                                             java.util.List<String> subscribedOnlyIds) {
        for (String channelId : subscribedOnlyIds) {
            FirebaseDatabase.getInstance().getReference("channel_subscribers")
                .child(channelId).child(uid).removeValue();
        }

        if (ownedChannelIds.isEmpty()) {
            _cleanupUserRecordsAndDeleteAuth(uid, handle);
            return;
        }

        final int[] remaining = {ownedChannelIds.size()};
        for (String channelId : ownedChannelIds) {
            _deleteOwnedChannel(channelId, () -> {
                if (--remaining[0] == 0) _cleanupUserRecordsAndDeleteAuth(uid, handle);
            });
        }
    }

    private void _deleteOwnedChannel(String channelId, Runnable onDone) {
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        root.child("channel_posts").child(channelId).removeValue();
        ChannelPostCacheHelper.get(this).clearChannel(channelId);
        root.child("channel_replies").child(channelId).removeValue();
        root.child("channel_presence").child(channelId).removeValue();

        root.child("channel_subscribers").child(channelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot subs) {
                    for (DataSnapshot sub : subs.getChildren())
                        root.child("user_channels").child(sub.getKey()).child(channelId).removeValue();
                    root.child("channel_subscribers").child(channelId).removeValue();

                    root.child("channels").child(channelId).child("handle")
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(DataSnapshot handleSnap) {
                                String chHandle = handleSnap.getValue(String.class);
                                if (chHandle != null && !chHandle.isEmpty())
                                    root.child("handles").child(chHandle).removeValue();
                                root.child("channels").child(channelId).removeValue()
                                    .addOnCompleteListener(t -> onDone.run());
                            }
                            @Override public void onCancelled(DatabaseError e) { onDone.run(); }
                        });
                }
                @Override public void onCancelled(DatabaseError e) { onDone.run(); }
            });
    }

    private void _cleanupUserRecordsAndDeleteAuth(String uid, String handle) {
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();

        root.child("user_channels").child(uid).removeValue();
        root.child("user_chats").child(uid).removeValue();
        root.child("blocked_users").child(uid).removeValue();
        root.child("user_media").child(uid).removeValue();
        root.child("e2ee_keys").child(uid).removeValue();
        root.child("fcm_tokens").child(uid).removeValue();
        root.child("user_private").child(uid).removeValue();
        try { com.silu.minigram.e2ee.E2ee.get(this, uid).wipe(); } catch (Exception ignored) { }
        root.child("user_links").child(uid).removeValue();
        if (handle != null && !handle.isEmpty()) root.child("handles").child(handle).removeValue();
        try { getSharedPreferences("minigram_drafts", 0).edit().clear().apply(); } catch (Exception ignored) {}

        root.child("users").child(uid).removeValue().addOnCompleteListener(task -> {
            if (FirebaseAuth.getInstance().getCurrentUser() == null) {
                _finishDeletionFailed("Session expired mid-delete. Sign in again and retry — your data is already cleaned up, so this will finish quickly.");
                return;
            }
            FirebaseAuth.getInstance().getCurrentUser().delete().addOnCompleteListener(authTask -> {
                if (authTask.isSuccessful()) {
                    _finishDeletionSuccess();
                } else {
                    try { FirebaseAuth.getInstance().signOut(); } catch (Exception ignored) {}
                    _finishDeletionFailed("For security, please sign in again and retry delete — " +
                        "your data is already removed, this last step just needs a fresh session.");
                }
            });
        });
    }

    private void _finishDeletionSuccess() {
        if (isFinishing()) return;
        try { FirebaseAuth.getInstance().signOut(); } catch (Exception ignored) {}
        _showDeletingProgress(false);
        Toast.makeText(this, "Account deleted", Toast.LENGTH_SHORT).show();
        Intent i = new Intent(this, LoginActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void _finishDeletionFailed(String message) {
        if (isFinishing()) return;
        _showDeletingProgress(false);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        Intent i = new Intent(this, LoginActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void _showDeletingProgress(boolean show) {
        if (isFinishing()) return;
        if (show) Toast.makeText(this, "Deleting account…", Toast.LENGTH_SHORT).show();
    }

    // ─── UI helpers ───────────────────────────────────────────────────────────

    private void applyCardBg(View view) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Ink.res(R.color.ink_surface));
        gd.setCornerRadius(dp(14));
        view.setBackground(gd);
        view.setClipToOutline(true);
    }

    private void applyRipple(View view) {
        StateListDrawable sl = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(Ink.c("#22253A"));
        sl.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sl.addState(new int[]{}, new GradientDrawable());
        view.setBackground(sl);
        view.setClickable(true);
        view.setFocusable(true);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
