package com.silu.minigram;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

/**
 * Redesigned "my profile" view — reached from the header card at the top of
 * Settings (bottom-nav's old Profile tab was replaced by Updates, so this
 * screen needed a new entry point; Settings already lived one tap away).
 *
 * Set Photo does a real single-click upload right on this screen (via
 * UploadHelper/MultipartUploader → the Hostinger backend) — no more
 * bouncing through EditProfileActivity just to change the photo.
 * Edit Info still opens EditProfileActivity, for the actual name/bio form.
 *
 * Media / Links tabs are powered by ContentTrackingHelper, which records
 * what a user sends going forward (existing history isn't retroactively
 * populated — there was never anywhere that data could have come from).
 *
 * Posts tab aggregates recent posts from channels this user owns, using
 * data that already exists.
 */
public class MyProfileActivity extends AppCompatActivity {

    // Same Hostinger endpoint config as UploadHelper.java — keep both in
    // sync if you change one.

    private ImageView imgAvatar, imgProBadge;
    private TextView  txtFullname, txtStatus, txtStatusDot;
    private TextView  txtInfoName, txtInfoUsername, txtInfoBio;
    private LinearLayout tabMedia, tabLinks, tabVoice;
    private TextView  txtTabMedia, txtTabLinks, txtTabVoice;
    private View      tabIndicator;
    private RecyclerView recyclerTabContent;
    private LinearLayout layoutTabEmpty;
    private TextView  txtTabEmptyIcon, txtTabEmptyLabel;
    private ProgressBar progressTabContent;

    private String myUid;
    private String profileUid;      // whose profile is being shown (self or someone else)
    private boolean isOwnProfile;
    private DatabaseReference proBadgeRef;
    private ValueEventListener proBadgeListener;
    private String chatIdForContext; // for Block/Clear-History actions on someone else's profile
    private int    activeTab = 0; // 0=Media 1=Links 2=Voice
    private boolean mediaLoadedOnce = false;
    private boolean linksLoadedOnce = false;
    private boolean voiceLoadedOnce = false;
    // BUG FIX — each tab now keeps its own adapter reference. Before,
    // the cached-restore path just checked recyclerTabContent.getAdapter()
    // — whatever adapter happened to be attached from the LAST tab
    // visited, not necessarily the one being switched back to. Switching
    // Voice → Media → back to Voice, for example, showed Media's content
    // still on the RecyclerView because nothing re-attached Voice's own.
    private RecyclerView.Adapter<?> mediaAdapter, linksAdapter, voiceAdapter;

    // Requirement 3 — Share Profile. Cached from _loadProfile() so the
    // overflow menu doesn't need its own extra round trip.
    private String profileHandle;
    private String profileFullname;

    // Requirement 3 — long-press multi-select across Media/Links/Voice,
    // with View/Forward/Delete. One shared selection set per tab (cleared
    // on tab switch — Telegram does the same, selection doesn't carry
    // across tabs since they're different content types).
    private boolean selectionMode = false;
    private final java.util.LinkedHashSet<String> selectedKeys = new java.util.LinkedHashSet<>();
    // Full item data for whatever's currently selected, keyed the same way
    // as selectedKeys — needed by View/Forward/Delete without re-querying.
    private final HashMap<String, HashMap<String, Object>> itemsByKey = new HashMap<>();
    private View layoutSelectionBar;
    private TextView txtSelectionCount;
    private View btnSelectionView, btnSelectionForward, btnSelectionDelete;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdgeHelper.setup(this);
        setContentView(R.layout.my_profile);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));

        myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) { finish(); return; }

        String targetUidExtra = getIntent().getStringExtra("target_uid");
        chatIdForContext = getIntent().getStringExtra("chat_id");
        isOwnProfile = targetUidExtra == null || targetUidExtra.equals(myUid);
        profileUid = isOwnProfile ? myUid : targetUidExtra;

        _bindViews();
        _wireActions();
        _loadProfile();
        _listenProBadge();
        _selectTab(0);
    }

    @Override
    public void onBackPressed() {
        if (selectionMode) { _exitSelectionMode(); return; }
        super.onBackPressed();
    }

    private void _bindViews() {
        imgAvatar    = findViewById(R.id.img_avatar);
        imgProBadge  = findViewById(R.id.img_pro_badge);
        txtFullname  = findViewById(R.id.txt_fullname);
        txtStatus    = findViewById(R.id.txt_status);
        txtStatusDot = findViewById(R.id.txt_status_dot);

        txtInfoName     = findViewById(R.id.txt_info_name);
        txtInfoUsername = findViewById(R.id.txt_info_username);
        txtInfoBio      = findViewById(R.id.txt_info_bio);

        tabMedia = findViewById(R.id.tab_media);
        tabLinks = findViewById(R.id.tab_links);
        tabVoice = findViewById(R.id.tab_voice);
        txtTabMedia = findViewById(R.id.txt_tab_media);
        txtTabLinks = findViewById(R.id.txt_tab_links);
        txtTabVoice = findViewById(R.id.txt_tab_voice);
        tabIndicator = findViewById(R.id.tab_indicator);

        recyclerTabContent = findViewById(R.id.recycler_tab_content);
        layoutTabEmpty      = findViewById(R.id.layout_tab_empty);
        txtTabEmptyIcon     = findViewById(R.id.txt_tab_empty_icon);
        txtTabEmptyLabel    = findViewById(R.id.txt_tab_empty_label);
        progressTabContent  = findViewById(R.id.progress_tab_content);

        recyclerTabContent.setLayoutManager(new LinearLayoutManager(this));
        recyclerTabContent.setNestedScrollingEnabled(false);
        recyclerTabContent.setHasFixedSize(false);

        layoutSelectionBar   = findViewById(R.id.layout_selection_bar);
        txtSelectionCount    = findViewById(R.id.txt_selection_count);
        btnSelectionView     = findViewById(R.id.btn_selection_view);
        btnSelectionForward  = findViewById(R.id.btn_selection_forward);
        btnSelectionDelete   = findViewById(R.id.btn_selection_delete);
    }

    private void _wireActions() {
        findViewById(R.id.btn_back).setOnClickListener(v -> {
            if (selectionMode) _exitSelectionMode(); else finish();
        });

        // Was a Settings shortcut — redundant since this screen is only
        // ever reached FROM Settings (back press already returns there).
        // Now a 3-dot menu — its contents depend on whose profile this is.
        findViewById(R.id.btn_overflow_menu).setOnClickListener(this::_showOverflowMenu);

        View quickActionsRow = findViewById(R.id.layout_quick_actions_row);
        if (isOwnProfile) {
            // Single click, real upload — no more bouncing through
            // EditProfileActivity just to change the photo.
            findViewById(R.id.btn_set_photo).setOnClickListener(v -> _pickAndUploadPhoto());
            findViewById(R.id.btn_edit_info).setOnClickListener(v ->
                startActivity(new Intent(this, EditProfileActivity.class)));
        } else if (quickActionsRow != null) {
            // Someone else's profile — no photo/info editing controls at all.
            quickActionsRow.setVisibility(View.GONE);
        }

        tabMedia.setOnClickListener(v -> _selectTab(0));
        tabLinks.setOnClickListener(v -> _selectTab(1));
        tabVoice.setOnClickListener(v -> _selectTab(2));

        findViewById(R.id.btn_selection_close).setOnClickListener(v -> _exitSelectionMode());
        btnSelectionView.setOnClickListener(v -> _viewSelectedInChat());
        btnSelectionForward.setOnClickListener(v -> _forwardSelected());
        btnSelectionDelete.setOnClickListener(v -> _confirmDeleteSelected());
    }

    // ── Photo: single-click upload + delete (3-dot menu) ───────────────────

    private final androidx.activity.result.ActivityResultLauncher<String> pickImage =
        registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) _uploadPhoto(uri);
        });

    private void _pickAndUploadPhoto() {
        pickImage.launch("image/*");
    }

    private void _uploadPhoto(android.net.Uri uri) {
        Toast.makeText(this, "Uploading photo…", Toast.LENGTH_SHORT).show();
        UploadHelper.uploadImage(this, uri, new UploadHelper.UploadCallback() {
            @Override public void onSuccess(String url) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    FirebaseDatabase.getInstance().getReference("users").child(myUid)
                        .child("photo_url").setValue(url);
                    AvatarHelper.load(MyProfileActivity.this, imgAvatar, url, txtFullname.getText().toString());
                    Toast.makeText(MyProfileActivity.this, "Photo updated!", Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(MyProfileActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void _deletePhoto() {
        FirebaseDatabase.getInstance().getReference("users").child(myUid)
            .child("photo_url").addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    final String oldUrl = snap.getValue(String.class);

                    FirebaseDatabase.getInstance().getReference("users").child(myUid)
                        .child("photo_url").setValue("").addOnCompleteListener(task -> {
                            if (isFinishing() || isDestroyed()) return;
                            AvatarHelper.load(MyProfileActivity.this, imgAvatar, null, txtFullname.getText().toString());
                            Toast.makeText(MyProfileActivity.this, "Profile photo removed", Toast.LENGTH_SHORT).show();
                        });

                    // Physical file cleanup — best-effort, off the main
                    // thread, never blocks the UI update above. A failure
                    // here just leaves an orphaned file on disk; it never
                    // breaks what the user actually sees. (No separate
                    // Glide-cache-clear needed: upload.php always generates
                    // a fresh random filename, so this exact URL is never
                    // reused for a different photo — any stale cache entry
                    // is harmless and evicts naturally over time.)
                    if (oldUrl != null && !oldUrl.isEmpty()) {
                        new Thread(() -> {
                            try {
                                MediaStorage.deleteByUrl(oldUrl);
                            } catch (Exception e) {
                                android.util.Log.e("MyProfileActivity", "Old photo cleanup failed (non-fatal): " + e.getMessage());
                            }
                        }).start();
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void _showOverflowMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        if (isOwnProfile) {
            menu.getMenu().add(0, 1, 0, "Change Image");
            menu.getMenu().add(0, 2, 1, "Delete Image");
            menu.getMenu().add(0, 5, 2, "Share Profile");
        } else {
            menu.getMenu().add(0, 5, 0, "Share Profile");
            menu.getMenu().add(0, 3, 1, "Block User");
            if (chatIdForContext != null) menu.getMenu().add(0, 4, 2, "Clear History");
        }
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                _pickAndUploadPhoto();
                return true;
            } else if (item.getItemId() == 2) {
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Delete profile photo?")
                    .setMessage("This removes your current photo. You can set a new one anytime.")
                    .setPositiveButton("Delete", (d, w) -> _deletePhoto())
                    .setNegativeButton("Cancel", null)
                    .show();
                return true;
            } else if (item.getItemId() == 3) {
                _confirmBlockUser();
                return true;
            } else if (item.getItemId() == 4) {
                _confirmClearHistory();
                return true;
            } else if (item.getItemId() == 5) {
                _showShareProfileOptions();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private static final String WEB_BASE = "https://minigram.fireclashpro.com";

    private void _showShareProfileOptions() {
        if (profileHandle == null || profileHandle.isEmpty()) {
            Toast.makeText(this, "No handle set for this user", Toast.LENGTH_SHORT).show();
            return;
        }
        String link = WEB_BASE + "/admin/u/" + profileHandle;
        String name = profileFullname != null ? profileFullname : "this user";

        new android.app.AlertDialog.Builder(this)
            .setTitle("Share Profile")
            .setItems(new String[]{"Copy Link", "Share via..."}, (d, which) -> {
                if (which == 0) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Profile Link", link));
                        Toast.makeText(this, "Link copied!", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_SUBJECT, name + " on Minigram");
                    share.putExtra(Intent.EXTRA_TEXT,
                        (isOwnProfile ? "Chat with me on Minigram:\n" : "Chat with " + name + " on Minigram:\n") + link);
                    startActivity(Intent.createChooser(share, "Share profile via"));
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void _confirmBlockUser() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Block " + txtFullname.getText() + "?")
            .setMessage("They won't be able to message you. You can unblock them anytime from Privacy & Security settings.")
            .setPositiveButton("Block", (d, w) -> {
                FirebaseDatabase.getInstance().getReference("blocked_users")
                    .child(myUid).child(profileUid).setValue(true)
                    .addOnCompleteListener(task -> {
                        Toast.makeText(this, task.isSuccessful() ? "User blocked" : "Couldn't block — try again",
                            Toast.LENGTH_SHORT).show();
                        if (task.isSuccessful()) finish();
                    });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void _confirmClearHistory() {
        if (chatIdForContext == null) return;
        new android.app.AlertDialog.Builder(this)
            .setTitle("Clear chat history?")
            .setMessage("This deletes all messages for you only — " + txtFullname.getText() + " will still see them on their side.")
            .setPositiveButton("Clear", (d, w) -> _clearChatHistory())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void _clearChatHistory() {
        FirebaseDatabase.getInstance().getReference("chats").child(chatIdForContext)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    HashMap<String, Object> updates = new HashMap<>();
                    for (DataSnapshot msg : snap.getChildren()) {
                        updates.put(msg.getKey() + "/deleted_for_" + myUid, true);
                    }
                    if (updates.isEmpty()) {
                        Toast.makeText(MyProfileActivity.this, "Nothing to clear", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    FirebaseDatabase.getInstance().getReference("chats").child(chatIdForContext)
                        .updateChildren(updates)
                        .addOnCompleteListener(task -> Toast.makeText(MyProfileActivity.this,
                            task.isSuccessful() ? "Chat history cleared" : "Couldn't clear — try again",
                            Toast.LENGTH_SHORT).show());
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    /** Opens whatever a tapped Media/Posts item came from — a channel post
     *  opens that channel; a chat item opens the DM (this profile's own chat
     *  with us, since that's overwhelmingly the common case when browsing
     *  someone's shared media from their profile). */
    private void _openSource(String source, String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) return;
        if ("channel".equals(source)) {
            Intent i = new Intent(this, ChannelActivity.class);
            i.putExtra("channel_id", sourceId);
            startActivity(i);
        } else if ("chat".equals(source)) {
            Intent i = new Intent(this, ChatActivity.class);
            i.putExtra("chat_id", sourceId);
            i.putExtra("peer_uid", isOwnProfile ? null : profileUid);
            startActivity(i);
        }
    }

    /**
     * Bug fix — this screen never showed ANY indication of Pro status at
     * all, on either your own profile or someone else's (is_pro is what
     * BillingManager actually writes on purchase — every other place that
     * already shows a badge, like UserProfileSheet and ChatActivity's peer
     * header, reads this same field correctly; this screen just never did).
     * Live listener, not a one-time read, so buying Pro shows the badge
     * immediately without needing to leave and reopen this screen.
     */
    private void _listenProBadge() {
        if (imgProBadge == null || profileUid == null) return;
        proBadgeRef = FirebaseDatabase.getInstance().getReference("users").child(profileUid).child("is_pro");
        proBadgeListener = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    boolean isPro = Boolean.TRUE.equals(snap.getValue(Boolean.class));
                    imgProBadge.setVisibility(isPro ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            };
        proBadgeRef.addValueEventListener(proBadgeListener);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (proBadgeRef != null && proBadgeListener != null) {
            proBadgeRef.removeEventListener(proBadgeListener);
        }
    }

    private void _loadProfile() {
        FirebaseDatabase.getInstance().getReference("users").child(profileUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    String fullname = snap.child("fullname").getValue(String.class);
                    String handle   = snap.child("handle").getValue(String.class);
                    String bio      = snap.child("bio").getValue(String.class);
                    String photo    = snap.child("photo_url").getValue(String.class);
                    boolean online  = "online".equals(snap.child("status").getValue(String.class));

                    String displayName = (fullname != null && !fullname.isEmpty()) ? fullname : "You";
                    profileHandle   = handle;
                    profileFullname = displayName;
                    txtFullname.setText(displayName);
                    txtInfoName.setText(displayName);
                    txtInfoUsername.setText(handle != null ? "@" + handle : "");
                    txtStatus.setText(handle != null ? "@" + handle : "");
                    txtInfoBio.setText(bio != null && !bio.isEmpty() ? bio : "No bio yet — add one from Edit Info");

                    txtStatusDot.setBackgroundResource(R.drawable.circle_bg_light);
                    txtStatusDot.getBackground().setTint(online ? Color.parseColor("#4CAF50") : Color.parseColor("#BDBDBD"));

                    AvatarHelper.load(MyProfileActivity.this, imgAvatar, photo, displayName);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Tabs ───────────────────────────────────────────────────────────────

    private void _selectTab(int tab) {
        if (selectionMode) _exitSelectionMode(); // Telegram: switching tabs drops selection
        activeTab = tab;
        int grey = Color.parseColor("#9E9E9E");
        int blue = Color.parseColor("#229ED9");
        txtTabMedia.setTextColor(grey); txtTabMedia.setTypeface(null, android.graphics.Typeface.NORMAL);
        txtTabLinks.setTextColor(grey); txtTabLinks.setTypeface(null, android.graphics.Typeface.NORMAL);
        txtTabVoice.setTextColor(grey); txtTabVoice.setTypeface(null, android.graphics.Typeface.NORMAL);

        TextView selected = tab == 0 ? txtTabMedia : (tab == 1 ? txtTabLinks : txtTabVoice);
        selected.setTextColor(blue);
        selected.setTypeface(null, android.graphics.Typeface.BOLD);

        LinearLayout tabRow = (LinearLayout) tabMedia.getParent();
        tabRow.post(() -> {
            int tabWidth = tabRow.getWidth() / 3;
            ViewGroup.LayoutParams lp = tabIndicator.getLayoutParams();
            lp.width = tabWidth;
            tabIndicator.setLayoutParams(lp);
            tabIndicator.animate().translationX(tabWidth * tab).setDuration(200).start();
        });

        recyclerTabContent.setVisibility(View.GONE);
        layoutTabEmpty.setVisibility(View.GONE);
        progressTabContent.setVisibility(View.GONE);

        if (tab == 0) {
            _loadMedia();
        } else if (tab == 1) {
            _loadLinks();
        } else {
            _loadVoice();
        }
    }

    private void _showEmpty(String icon, String label) {
        txtTabEmptyIcon.setText(icon);
        txtTabEmptyLabel.setText(label);
        layoutTabEmpty.setVisibility(View.VISIBLE);
    }

    /** Stable key for tracking selection + looking items back up later
     *  (View/Forward/Delete). content_id is unique and stable when present;
     *  older items tracked before content_id existed fall back to a
     *  synthetic key that's at least unique within one load. */
    private String _keyFor(HashMap<String, Object> item) {
        Object cid = item.get("content_id");
        if (cid != null && !String.valueOf(cid).isEmpty()) return "c_" + cid;
        return "s_" + item.get("source_id") + "_" + item.get("timestamp");
    }

    // ── Media tab — images only (voice moved to its own tab below) ─────────

    private void _loadMedia() {
        recyclerTabContent.setLayoutManager(new GridLayoutManager(this, 3));
        if (mediaLoadedOnce) {
            boolean has = mediaAdapter != null && mediaAdapter.getItemCount() > 0;
            if (has) recyclerTabContent.setAdapter(mediaAdapter);
            recyclerTabContent.setVisibility(has ? View.VISIBLE : View.GONE);
            layoutTabEmpty.setVisibility(has ? View.GONE : View.VISIBLE);
            if (!has) _showEmpty("photo_library", _emptyMediaLabel());
            return;
        }
        progressTabContent.setVisibility(View.VISIBLE);

        if (chatIdForContext != null) {
            _loadFromChatMessages("image", items -> {
                mediaLoadedOnce = true;
                progressTabContent.setVisibility(View.GONE);
                if (items.isEmpty()) { _showEmpty("photo_library", _emptyMediaLabel()); return; }
                mediaAdapter = new ProfileMediaAdapter(items);
                recyclerTabContent.setAdapter(mediaAdapter);
                recyclerTabContent.setVisibility(View.VISIBLE);
            });
            return;
        }

        FirebaseDatabase.getInstance().getReference("user_media").child(profileUid)
            .orderByChild("timestamp").limitToLast(150)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    mediaLoadedOnce = true;
                    progressTabContent.setVisibility(View.GONE);
                    ArrayList<HashMap<String, Object>> items = new ArrayList<>();
                    for (DataSnapshot c : snap.getChildren()) {
                        // BUG FIXED: this tab used to show voice notes mixed in
                        // with images (user_media stores both types together) —
                        // filtered to images only now; voice has its own tab.
                        String type = c.child("type").getValue(String.class);
                        if (!"image".equals(type)) continue;
                        HashMap<String, Object> item = new HashMap<>();
                        item.put("type",       type);
                        item.put("url",        c.child("url").getValue(String.class));
                        item.put("source",     c.child("source").getValue(String.class));
                        item.put("source_id",  c.child("source_id").getValue(String.class));
                        item.put("content_id", c.child("content_id").getValue(String.class));
                        item.put("tracking_key", c.getKey());
                        item.put("sender_uid", profileUid);
                        item.put("timestamp",  c.child("timestamp").getValue(Long.class));
                        items.add(0, item); // newest first
                    }
                    if (items.isEmpty()) { _showEmpty("photo_library", _emptyMediaLabel()); return; }
                    mediaAdapter = new ProfileMediaAdapter(items);
                    recyclerTabContent.setAdapter(mediaAdapter);
                    recyclerTabContent.setVisibility(View.VISIBLE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (isFinishing() || isDestroyed()) return;
                    mediaLoadedOnce = true;
                    progressTabContent.setVisibility(View.GONE);
                    _showEmpty("photo_library", "Couldn't load — check your connection");
                }
            });
    }

    private String _emptyMediaLabel() {
        return chatIdForContext != null ? "No shared photos yet" : "Photos you share will appear here";
    }

    // ── Links tab ────────────────────────────────────────────────────────

    private void _loadLinks() {
        recyclerTabContent.setLayoutManager(new LinearLayoutManager(this));
        if (linksLoadedOnce) {
            boolean has = linksAdapter != null && linksAdapter.getItemCount() > 0;
            if (has) recyclerTabContent.setAdapter(linksAdapter);
            recyclerTabContent.setVisibility(has ? View.VISIBLE : View.GONE);
            layoutTabEmpty.setVisibility(has ? View.GONE : View.VISIBLE);
            if (!has) _showEmpty("link", _emptyLinksLabel());
            return;
        }
        progressTabContent.setVisibility(View.VISIBLE);

        if (chatIdForContext != null) {
            _loadFromChatMessages("link", items -> {
                linksLoadedOnce = true;
                progressTabContent.setVisibility(View.GONE);
                if (items.isEmpty()) { _showEmpty("link", _emptyLinksLabel()); return; }
                linksAdapter = new ProfileLinkAdapter(items);
                recyclerTabContent.setAdapter(linksAdapter);
                recyclerTabContent.setVisibility(View.VISIBLE);
            });
            return;
        }

        FirebaseDatabase.getInstance().getReference("user_links").child(profileUid)
            .orderByChild("timestamp").limitToLast(150)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    linksLoadedOnce = true;
                    progressTabContent.setVisibility(View.GONE);
                    ArrayList<HashMap<String, Object>> items = new ArrayList<>();
                    for (DataSnapshot c : snap.getChildren()) {
                        HashMap<String, Object> item = new HashMap<>();
                        item.put("type",          "link");
                        item.put("url",           c.child("url").getValue(String.class));
                        item.put("text_preview",  c.child("text_preview").getValue(String.class));
                        item.put("source",        c.child("source").getValue(String.class));
                        item.put("source_id",     c.child("source_id").getValue(String.class));
                        item.put("content_id",    c.child("content_id").getValue(String.class));
                        item.put("tracking_key",  c.getKey());
                        item.put("sender_uid",    profileUid);
                        item.put("timestamp",     c.child("timestamp").getValue(Long.class));
                        items.add(0, item); // newest first
                    }
                    if (items.isEmpty()) { _showEmpty("link", _emptyLinksLabel()); return; }
                    linksAdapter = new ProfileLinkAdapter(items);
                    recyclerTabContent.setAdapter(linksAdapter);
                    recyclerTabContent.setVisibility(View.VISIBLE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (isFinishing() || isDestroyed()) return;
                    linksLoadedOnce = true;
                    progressTabContent.setVisibility(View.GONE);
                    _showEmpty("link", "Couldn't load — check your connection");
                }
            });
    }

    private String _emptyLinksLabel() {
        return chatIdForContext != null ? "No shared links yet" : "Links you share will appear here";
    }

    // ── Voice tab — Requirement 3: replaces the old Posts tab entirely ─────

    private void _loadVoice() {
        recyclerTabContent.setLayoutManager(new LinearLayoutManager(this));
        if (voiceLoadedOnce) {
            boolean has = voiceAdapter != null && voiceAdapter.getItemCount() > 0;
            if (has) recyclerTabContent.setAdapter(voiceAdapter);
            recyclerTabContent.setVisibility(has ? View.VISIBLE : View.GONE);
            layoutTabEmpty.setVisibility(has ? View.GONE : View.VISIBLE);
            if (!has) _showEmpty("mic", _emptyVoiceLabel());
            return;
        }
        progressTabContent.setVisibility(View.VISIBLE);

        if (chatIdForContext != null) {
            _loadFromChatMessages("voice", items -> {
                voiceLoadedOnce = true;
                progressTabContent.setVisibility(View.GONE);
                if (items.isEmpty()) { _showEmpty("mic", _emptyVoiceLabel()); return; }
                voiceAdapter = new ProfileVoiceAdapter(items);
                recyclerTabContent.setAdapter(voiceAdapter);
                recyclerTabContent.setVisibility(View.VISIBLE);
            });
            return;
        }

        FirebaseDatabase.getInstance().getReference("user_media").child(profileUid)
            .orderByChild("timestamp").limitToLast(150)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    voiceLoadedOnce = true;
                    progressTabContent.setVisibility(View.GONE);
                    ArrayList<HashMap<String, Object>> items = new ArrayList<>();
                    for (DataSnapshot c : snap.getChildren()) {
                        String type = c.child("type").getValue(String.class);
                        if (!"voice".equals(type)) continue;
                        HashMap<String, Object> item = new HashMap<>();
                        item.put("type",       type);
                        item.put("url",        c.child("url").getValue(String.class));
                        item.put("source",     c.child("source").getValue(String.class));
                        item.put("source_id",  c.child("source_id").getValue(String.class));
                        item.put("content_id", c.child("content_id").getValue(String.class));
                        item.put("tracking_key", c.getKey());
                        item.put("sender_uid", profileUid);
                        item.put("timestamp",  c.child("timestamp").getValue(Long.class));
                        items.add(0, item);
                    }
                    if (items.isEmpty()) { _showEmpty("mic", _emptyVoiceLabel()); return; }
                    voiceAdapter = new ProfileVoiceAdapter(items);
                    recyclerTabContent.setAdapter(voiceAdapter);
                    recyclerTabContent.setVisibility(View.VISIBLE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (isFinishing() || isDestroyed()) return;
                    voiceLoadedOnce = true;
                    progressTabContent.setVisibility(View.GONE);
                    _showEmpty("mic", "Couldn't load — check your connection");
                }
            });
    }

    private String _emptyVoiceLabel() {
        return chatIdForContext != null ? "No shared voice messages yet" : "Voice messages you share will appear here";
    }

    /**
     * Requirement 3 — "when viewing a user's profile from a chat, display
     * only the shared Media/Links/Voice exchanged between those two users."
     *
     * Deliberately reads straight from chats/{chatId} rather than merging
     * both users' user_media/user_links tracking indexes: it's simpler (one
     * query, not two index reads that then need merging by source_id), and
     * it's correct even for content ContentTrackingHelper never saw (old
     * messages sent before that tracking existed) — the tracking indexes
     * explicitly cannot cover that, but the chat's own messages always can.
     */
    private void _loadFromChatMessages(String filterType, java.util.function.Consumer<ArrayList<HashMap<String, Object>>> onItems) {
        FirebaseDatabase.getInstance().getReference("chats").child(chatIdForContext)
            .orderByChild("timestamp").limitToLast(400)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) { onItems.accept(new ArrayList<>()); return; }
                    ArrayList<HashMap<String, Object>> items = new ArrayList<>();
                    for (DataSnapshot m : snap.getChildren()) {
                        if (Boolean.TRUE.equals(m.child("is_deleted_for_all").getValue(Boolean.class))) continue;
                        if (Boolean.TRUE.equals(m.child("deleted_for_" + myUid).getValue(Boolean.class))) continue;

                        String msgId  = m.getKey();
                        String type   = m.child("type").getValue(String.class);
                        String sender = m.child("sender_uid").getValue(String.class);
                        Long   ts     = m.child("timestamp").getValue(Long.class);

                        if ("image".equals(filterType) && "image".equals(type)) {
                            HashMap<String, Object> item = new HashMap<>();
                            item.put("type", "image");
                            item.put("url", m.child("image_url").getValue(String.class));
                            item.put("source", "chat"); item.put("source_id", chatIdForContext);
                            item.put("content_id", msgId); item.put("sender_uid", sender); item.put("timestamp", ts);
                            items.add(0, item);
                        } else if ("voice".equals(filterType) && "voice".equals(type)) {
                            HashMap<String, Object> item = new HashMap<>();
                            item.put("type", "voice");
                            item.put("url", m.child("voice_url").getValue(String.class));
                            item.put("source", "chat"); item.put("source_id", chatIdForContext);
                            item.put("content_id", msgId); item.put("sender_uid", sender); item.put("timestamp", ts);
                            items.add(0, item);
                        } else if ("link".equals(filterType)) {
                            String text = m.child("text").getValue(String.class);
                            String url = text != null ? _extractFirstUrlLocal(text) : null;
                            if (url != null) {
                                HashMap<String, Object> item = new HashMap<>();
                                item.put("type", "link");
                                item.put("url", url);
                                item.put("text_preview", text.length() > 100 ? text.substring(0, 100) + "…" : text);
                                item.put("source", "chat"); item.put("source_id", chatIdForContext);
                                item.put("content_id", msgId); item.put("sender_uid", sender); item.put("timestamp", ts);
                                items.add(0, item);
                            }
                        }
                    }
                    onItems.accept(items);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    onItems.accept(new ArrayList<>());
                }
            });
    }

    private String _extractFirstUrlLocal(String text) {
        java.util.regex.Matcher m = android.util.Patterns.WEB_URL.matcher(text);
        return m.find() ? m.group() : null;
    }

    // ── Selection mode — Requirement 3: long-press to select, View/Forward/
    // Delete, View only offered for a single item that has a content_id ────

    private void _onItemLongPress(HashMap<String, Object> item) {
        if (!selectionMode) { selectionMode = true; layoutSelectionBar.setVisibility(View.VISIBLE); }
        _toggleSelected(item);
    }

    private void _onItemTap(HashMap<String, Object> item) {
        if (selectionMode) { _toggleSelected(item); return; }
        // Not in selection mode — a plain tap opens the source (existing
        // behaviour, unchanged for Media; Links already had its own
        // open-URL tap behaviour handled in its adapter).
        _openSource((String) item.get("source"), (String) item.get("source_id"));
    }

    private void _toggleSelected(HashMap<String, Object> item) {
        String key = _keyFor(item);
        if (selectedKeys.contains(key)) {
            selectedKeys.remove(key);
        } else {
            selectedKeys.add(key);
            itemsByKey.put(key, item);
        }
        if (selectedKeys.isEmpty()) { _exitSelectionMode(); return; }
        _updateSelectionBar();
        if (recyclerTabContent.getAdapter() != null) recyclerTabContent.getAdapter().notifyDataSetChanged();
    }

    private void _updateSelectionBar() {
        int n = selectedKeys.size();
        txtSelectionCount.setText(n + " selected");
        // View only for exactly one item, and only if we actually know
        // which message it is (older pre-content_id items can still be
        // forwarded/deleted, just not jumped-to).
        boolean canView = n == 1 && itemsByKey.get(selectedKeys.iterator().next()).get("content_id") != null;
        btnSelectionView.setVisibility(canView ? View.VISIBLE : View.GONE);
    }

    private void _exitSelectionMode() {
        selectionMode = false;
        selectedKeys.clear();
        itemsByKey.clear();
        layoutSelectionBar.setVisibility(View.GONE);
        if (recyclerTabContent.getAdapter() != null) recyclerTabContent.getAdapter().notifyDataSetChanged();
    }

    /** View — opens the original message in its chat/channel, scrolls to
     *  it, and briefly highlights it (ChatActivity.scrollToMessage(), fixed
     *  earlier this phase so the highlight actually shows). */
    private void _viewSelectedInChat() {
        if (selectedKeys.size() != 1) return;
        HashMap<String, Object> item = itemsByKey.get(selectedKeys.iterator().next());
        if (item == null) return;
        String source   = (String) item.get("source");
        String sourceId = (String) item.get("source_id");
        String contentId = (String) item.get("content_id");
        if (contentId == null) return;

        if ("channel".equals(source)) {
            Intent i = new Intent(this, ChannelActivity.class);
            i.putExtra("channel_id", sourceId);
            i.putExtra("scroll_to_post_id", contentId);
            startActivity(i);
        } else {
            Intent i = new Intent(this, ChatActivity.class);
            i.putExtra("chat_id", sourceId);
            i.putExtra("peer_uid", isOwnProfile ? null : profileUid);
            i.putExtra("scroll_to_msg_id", contentId);
            startActivity(i);
        }
        _exitSelectionMode();
    }

    /** Forward — builds message-shaped HashMaps (matching what
     *  ForwardChatListActivity/ForwardHelper already expect from chat/
     *  channel sends) from the selected profile items and hands off to the
     *  same Forward screen used everywhere else in the app. */
    private void _forwardSelected() {
        if (selectedKeys.isEmpty()) return;
        List<HashMap<String, Object>> toForward = new ArrayList<>();
        for (String key : selectedKeys) {
            HashMap<String, Object> item = itemsByKey.get(key);
            if (item == null) continue;
            HashMap<String, Object> msg = new HashMap<>();
            String type = (String) item.get("type");
            if ("image".equals(type)) {
                msg.put("type", "image");
                msg.put("image_url", item.get("url"));
                msg.put("caption", "");
            } else if ("voice".equals(type)) {
                msg.put("type", "voice");
                msg.put("voice_url", item.get("url"));
                // Duration isn't tracked in user_media — the recipient still
                // gets a fully playable voice message, just without a
                // pre-filled duration label until playback starts.
                msg.put("duration", 0);
            } else { // link
                msg.put("type", "text");
                msg.put("text", item.get("url"));
            }
            toForward.add(msg);
        }
        if (toForward.isEmpty()) return;

        Intent i = new Intent(this, ForwardChatListActivity.class);
        i.putExtra(ForwardChatListActivity.EXTRA_MESSAGES_JSON, new com.google.gson.Gson().toJson(toForward));
        startActivity(i);
        _exitSelectionMode();
    }

    /** Delete — removes the item from view. Deliberately delete-for-me-only
     *  here (safe, always available, matches "remove from my history"); the
     *  heavier delete-for-everyone is already reachable via View → the
     *  normal in-chat/channel delete action, which already has its own
     *  owner/sender permission checks — not duplicating that logic here. */
    private void _confirmDeleteSelected() {
        if (selectedKeys.isEmpty()) return;
        int n = selectedKeys.size();
        new android.app.AlertDialog.Builder(this)
            .setTitle(n == 1 ? "Delete item?" : "Delete " + n + " items?")
            .setMessage("This removes " + (n == 1 ? "it" : "them") + " from your history. " +
                (isOwnProfile ? "The other side of any chat still keeps their copy." :
                 "This only affects your own view."))
            .setPositiveButton("Delete", (d, w) -> _deleteSelected())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void _deleteSelected() {
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        for (String key : selectedKeys) {
            HashMap<String, Object> item = itemsByKey.get(key);
            if (item == null) continue;
            String source      = (String) item.get("source");
            String sourceId    = (String) item.get("source_id");
            String contentId   = (String) item.get("content_id");
            String trackingKey = (String) item.get("tracking_key");

            if (trackingKey != null) {
                // Own-profile view (Media/Links/Voice driven by user_media /
                // user_links) — THIS is what the profile actually reads, so
                // removing the tracking entry is what makes it disappear.
                // Writing a per-user delete flag on the original content
                // instead wouldn't do that: channel_posts has no per-user
                // delete flag anything checks (only the global
                // is_deleted_for_all), so that write would've been inert.
                String node = "link".equals(item.get("type")) ? "user_links" : "user_media";
                root.child(node).child(myUid).child(trackingKey).removeValue();
            } else if (contentId != null && "chat".equals(source)) {
                // Chat-scoped view (viewing a peer's profile from a chat) —
                // items are the chat's own messages here, so mark THIS
                // message deleted-for-me the normal way; ChatActivity
                // already checks deleted_for_{uid} when rendering.
                root.child("chats").child(sourceId).child(contentId)
                    .child("deleted_for_" + myUid).setValue(true);
            }
            // Chat-scoped + channel source, or no contentId at all: nothing
            // precise/effective to do server-side (see comment above on why
            // channel_posts per-user delete isn't a real mechanism yet) —
            // the item still disappears from THIS screen via the reload
            // below, which is what matters for "remove from my history"
            // here.
        }
        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
        mediaLoadedOnce = false; linksLoadedOnce = false; voiceLoadedOnce = false;
        _exitSelectionMode();
        _selectTab(activeTab);
    }

    /** Minimal, read-only adapter — just previews, no interactions. */
    /** Grid of image thumbnails — read-only, tap does nothing yet. */
    private class ProfileMediaAdapter extends RecyclerView.Adapter<ProfileMediaAdapter.VH> {
        private final ArrayList<HashMap<String, Object>> items;
        ProfileMediaAdapter(ArrayList<HashMap<String, Object>> items) { this.items = items; }

        class VH extends RecyclerView.ViewHolder {
            ImageView imgThumb;
            View      viewDim;
            TextView  txtCheck;
            VH(View v) {
                super(v);
                imgThumb = v.findViewById(R.id.img_media_thumb);
                viewDim  = v.findViewById(R.id.view_media_selected_dim);
                txtCheck = v.findViewById(R.id.txt_media_check);
            }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_profile_media, parent, false);

            // BUG FIX 1: on the very first layout pass the RecyclerView hasn't
            // been measured yet, so parent.getWidth() is 0 — every cell got a
            // height of 0 and the grid rendered completely empty. Tapping the
            // tab a second time re-ran the load once measurement had happened,
            // which is exactly why content "only showed after one click".
            int available = parent.getWidth();
            if (available <= 0) {
                available = getResources().getDisplayMetrics().widthPixels
                    - recyclerTabContent.getPaddingLeft()
                    - recyclerTabContent.getPaddingRight();
            }

            // BUG FIX 2: this used to build a plain ViewGroup.LayoutParams,
            // which has NO margin support — so it threw away the margins the
            // item layout declares, and the thumbnails sat flush against each
            // other with no gaps. Reuse the inflated params (MarginLayoutParams)
            // and only override the height.
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            int cell = available / 3;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                // Subtract the horizontal margins so the visible tile stays
                // square rather than slightly taller than it is wide.
                cell -= (mlp.leftMargin + mlp.rightMargin);
            }
            lp.height = Math.max(cell, 1);
            v.setLayoutParams(lp);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int position) {
            HashMap<String, Object> item = items.get(position);
            String url = (String) item.get("url");
            Glide.with(MyProfileActivity.this).load(url)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.img_placeholder)
                .into(h.imgThumb);

            boolean selected = selectedKeys.contains(_keyFor(item));
            h.viewDim.setVisibility(selected ? View.VISIBLE : View.GONE);
            h.txtCheck.setVisibility(selected ? View.VISIBLE : View.GONE);

            h.itemView.setOnClickListener(v -> _onItemTap(item));
            h.itemView.setOnLongClickListener(v -> { _onItemLongPress(item); return true; });
        }

        @Override public int getItemCount() { return items.size(); }
    }

    private class ProfileLinkAdapter extends RecyclerView.Adapter<ProfileLinkAdapter.VH> {
        private final ArrayList<HashMap<String, Object>> items;
        ProfileLinkAdapter(ArrayList<HashMap<String, Object>> items) { this.items = items; }

        class VH extends RecyclerView.ViewHolder {
            TextView txtUrl, txtPreview, txtCheck;
            VH(View v) {
                super(v);
                txtUrl     = v.findViewById(R.id.txt_link_url);
                txtPreview = v.findViewById(R.id.txt_link_preview);
                txtCheck   = v.findViewById(R.id.txt_link_check);
            }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_profile_link, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int position) {
            HashMap<String, Object> item = items.get(position);
            String url = (String) item.get("url");
            h.txtUrl.setText(url);
            h.txtPreview.setText((String) item.get("text_preview"));
            h.txtCheck.setVisibility(selectedKeys.contains(_keyFor(item)) ? View.VISIBLE : View.GONE);

            h.itemView.setOnClickListener(v -> {
                if (selectionMode) { _onItemTap(item); return; }
                if (url == null || url.isEmpty()) return;
                try {
                    String withScheme = url.startsWith("http") ? url : "https://" + url;
                    h.itemView.getContext().startActivity(
                        new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(withScheme)));
                } catch (Exception ignored) {}
            });
            h.itemView.setOnLongClickListener(v -> { _onItemLongPress(item); return true; });
        }

        @Override public int getItemCount() { return items.size(); }
    }

    /** Voice tab — Requirement 3: play (single MediaPlayer, one item at a
     *  time — starting a new one stops whatever was already playing) and
     *  download (via Android's DownloadManager, straight to the device's
     *  Downloads folder — no new dependency needed). */
    /** Voice — no separate download button anymore (misunderstanding
     *  earlier: it used Android's system DownloadManager, saving into the
     *  public Downloads folder — the actual ask was to keep voice playable
     *  only inside Minigram). One icon carries the whole state: download
     *  (not cached) → spinner (downloading into the app's private cache
     *  via VoiceCacheHelper) → auto-plays the moment it's ready (pause
     *  icon) → play icon once paused. Already-cached items play instantly,
     *  no re-download. */
    private class ProfileVoiceAdapter extends RecyclerView.Adapter<ProfileVoiceAdapter.VH> {
        private final ArrayList<HashMap<String, Object>> items;
        private android.media.MediaPlayer player;
        private int playingPosition = -1;
        private int downloadingPosition = -1;

        ProfileVoiceAdapter(ArrayList<HashMap<String, Object>> items) { this.items = items; }

        class VH extends RecyclerView.ViewHolder {
            TextView btnPlay, txtDuration, txtCheck;
            ProgressBar spinner;
            VH(View v) {
                super(v);
                btnPlay     = v.findViewById(R.id.btn_voice_play);
                spinner     = v.findViewById(R.id.progress_voice_download);
                txtDuration = v.findViewById(R.id.txt_voice_duration);
                txtCheck    = v.findViewById(R.id.txt_voice_check);
            }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_profile_voice, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int position) {
            HashMap<String, Object> item = items.get(position);
            String url = (String) item.get("url");
            boolean isPlaying = position == playingPosition;
            boolean isDownloading = position == downloadingPosition;
            boolean isCached = url != null && VoiceCacheHelper.isCached(MyProfileActivity.this, url);

            h.spinner.setVisibility(isDownloading ? View.VISIBLE : View.GONE);
            h.btnPlay.setVisibility(isDownloading ? View.INVISIBLE : View.VISIBLE);
            h.btnPlay.setText(isPlaying ? "pause" : (isCached ? "play_arrow" : "download"));

            h.btnPlay.setOnClickListener(v -> {
                if (isDownloading) return;
                if (isPlaying) { _pauseVoice(); notifyItemChanged(position); return; }
                _playOrDownload(url, position);
            });

            h.txtCheck.setVisibility(selectedKeys.contains(_keyFor(item)) ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> _onItemTap(item));
            h.itemView.setOnLongClickListener(v -> { _onItemLongPress(item); return true; });
        }

        private void _playOrDownload(String url, int position) {
            if (url == null || url.isEmpty()) return;
            if (VoiceCacheHelper.isCached(MyProfileActivity.this, url)) {
                _playLocal(VoiceCacheHelper.cacheFileFor(MyProfileActivity.this, url), position);
                return;
            }
            downloadingPosition = position;
            notifyItemChanged(position);
            VoiceCacheHelper.ensureDownloaded(MyProfileActivity.this, url, new VoiceCacheHelper.Callback() {
                @Override public void onReady(java.io.File localFile) {
                    downloadingPosition = -1;
                    _playLocal(localFile, position); // auto-play right after download
                }
                @Override public void onError() {
                    downloadingPosition = -1;
                    notifyItemChanged(position);
                    Toast.makeText(MyProfileActivity.this, "Couldn't download voice message", Toast.LENGTH_SHORT).show();
                }
            });
        }

        private void _playLocal(java.io.File file, int position) {
            _stopVoice();
            try {
                player = new android.media.MediaPlayer();
                player.setDataSource(file.getAbsolutePath());
                player.setOnCompletionListener(mp -> { _stopVoice(); notifyItemChanged(position); });
                player.setOnPreparedListener(android.media.MediaPlayer::start);
                player.prepareAsync();
                playingPosition = position;
                notifyItemChanged(position);
            } catch (Exception e) {
                Toast.makeText(MyProfileActivity.this, "Couldn't play voice message", Toast.LENGTH_SHORT).show();
            }
        }

        void stopVoicePlayback() { _stopVoice(); }

        private void _pauseVoice() {
            if (player != null) { try { player.pause(); } catch (Exception ignored) {} }
            int old = playingPosition;
            playingPosition = -1;
            if (old != -1 && old < items.size()) notifyItemChanged(old);
        }

        private void _stopVoice() {
            if (player != null) {
                try { player.release(); } catch (Exception ignored) {}
                player = null;
            }
            int old = playingPosition;
            playingPosition = -1;
            if (old != -1 && old < items.size()) notifyItemChanged(old);
        }

        @Override public int getItemCount() { return items.size(); }
    }
}
