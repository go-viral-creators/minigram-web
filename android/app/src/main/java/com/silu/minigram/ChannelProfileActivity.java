package com.silu.minigram;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Requirement 4 — Channel Profile, Telegram-style.
 *
 * Second redesign pass, after feedback on the first:
 *  • FIXED: switching tabs then coming back showed the WRONG content
 *    (e.g. voice items in the Media tab). Root cause: _restoreCached()
 *    checked whatever adapter happened to be attached to the single
 *    shared RecyclerView, which was whatever tab was visited LAST, not
 *    necessarily the one being switched back to — each tab now keeps its
 *    own adapter reference and re-attaches the correct one.
 *  • FIXED: no side margins on the description/blur/tab cards made the
 *    screen look flat and cramped — added consistent 16dp margins and
 *    rounded card backgrounds (activity_channel_profile.xml).
 *  • ADDED: long-press multi-select on Media/Links/Voice — View (jumps to
 *    and briefly highlights the exact original post — only for a single
 *    selection), Forward, Delete — same pattern as MyProfileActivity's
 *    tabs already had.
 *  • REDESIGNED voice: no separate download button anymore. One icon
 *    carries the whole state (download → spinner → auto-playing pause
 *    icon → paused play icon), and it downloads into the app's own
 *    private cache (VoiceCacheHelper) instead of the public Downloads
 *    folder — playable only inside Minigram, nothing exported.
 *  • ADDED: owner support. The owner now lands on this same screen too
 *    (previously went straight to ChannelSettingsActivity), sees the
 *    icon-action row like any subscriber, but with an "Edit Channel"
 *    action in place of Join/Leave (opens ChannelSettingsActivity).
 */
public class ChannelProfileActivity extends AppCompatActivity {

    private String channelId;
    private String myUid;
    private DatabaseReference channelRef;

    private String channelName, channelHandle, channelPhoto, channelDesc, channelPrivacy, ownerUid;
    private boolean isSubscribed = false;
    private boolean isMuted = false;
    private boolean isOwner = false;
    private boolean isChannelAdmin = false;

    private ImageView imgPhoto, imgVerified;
    private TextView  txtName, txtSubCount, txtBio, txtHandle;
    private TextView  btnJoinBig;
    private LinearLayout rowIconActions, rowBio;
    private TextView  iconMute, txtMuteLabel;
    private TextView  iconLeaveEdit, txtLeaveEditLabel;
    private Switch    swBlur;
    private View      rowBlur;
    private View      rowSubscribers;
    private TextView  txtSubscribersCount;

    private LinearLayout tabMedia, tabLinks, tabVoice;
    private TextView     txtTabMedia, txtTabLinks, txtTabVoice;
    private View         tabIndicator;
    private RecyclerView recyclerTabContent;
    private View         progressTabContent, layoutTabEmpty;
    private TextView     txtTabEmptyIcon, txtTabEmptyLabel;

    private int activeTab = 0; // 0=Media 1=Links 2=Voice
    private boolean mediaLoadedOnce, linksLoadedOnce, voiceLoadedOnce;
    // BUG FIX — each tab now keeps its OWN adapter. Before, _restoreCached()
    // just checked "whatever's on the RecyclerView right now", which after
    // switching tabs was whichever tab was visited most recently, not
    // necessarily the one being switched back to.
    private RecyclerView.Adapter<?> mediaAdapter, linksAdapter, voiceAdapter;

    // ── Selection mode — same View/Forward/Delete pattern as MyProfileActivity ──
    private boolean selectionMode = false;
    private final LinkedHashSet<String> selectedKeys = new LinkedHashSet<>();
    private final HashMap<String, HashMap<String, Object>> itemsByKey = new HashMap<>();
    private View layoutSelectionBar;
    private TextView txtSelectionCount;
    private View btnSelectionView, btnSelectionForward, btnSelectionDelete;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_profile);
        EdgeToEdgeHelper.setup(this);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));

        channelId = getIntent().getStringExtra("channel_id");
        if (channelId == null) { finish(); return; }

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        channelRef = FirebaseDatabase.getInstance().getReference("channels").child(channelId);

        _bindViews();
        findViewById(R.id.btn_back).setOnClickListener(v -> {
            if (selectionMode) _exitSelectionMode(); else finish();
        });
        _loadChannelInfo();
        _loadMuteAndBlurState();
        _selectTab(0);
    }

    @Override
    public void onBackPressed() {
        if (selectionMode) { _exitSelectionMode(); return; }
        super.onBackPressed();
    }

    private void _bindViews() {
        imgPhoto       = findViewById(R.id.img_channel_photo);
        imgVerified    = findViewById(R.id.img_verified_badge);
        txtName        = findViewById(R.id.txt_channel_name);
        txtSubCount    = findViewById(R.id.txt_sub_count);
        txtHandle      = findViewById(R.id.txt_handle);
        btnJoinBig     = findViewById(R.id.btn_join_big);
        rowIconActions = findViewById(R.id.row_icon_actions);
        rowBio         = findViewById(R.id.row_bio);
        txtBio         = findViewById(R.id.txt_bio);
        iconMute       = findViewById(R.id.icon_mute);
        txtMuteLabel   = findViewById(R.id.txt_mute_label);
        iconLeaveEdit     = findViewById(R.id.icon_leave_edit);
        txtLeaveEditLabel = findViewById(R.id.txt_leave_edit_label);
        swBlur         = findViewById(R.id.sw_blur);
        rowBlur        = findViewById(R.id.row_blur);
        rowSubscribers      = findViewById(R.id.row_subscribers);
        txtSubscribersCount = findViewById(R.id.txt_subscribers_count);

        tabMedia    = findViewById(R.id.tab_media);
        tabLinks    = findViewById(R.id.tab_links);
        tabVoice    = findViewById(R.id.tab_voice);
        txtTabMedia = findViewById(R.id.txt_tab_media);
        txtTabLinks = findViewById(R.id.txt_tab_links);
        txtTabVoice = findViewById(R.id.txt_tab_voice);
        tabIndicator = findViewById(R.id.tab_indicator);
        recyclerTabContent  = findViewById(R.id.recycler_tab_content);
        // The tab content now lives inside the page's ScrollView (see
        // activity_channel_profile.xml). Disabling nested scrolling makes it
        // measure to its full content height and let the parent do the
        // scrolling, instead of trapping scroll gestures in a short window —
        // the same setup MyProfileActivity already uses.
        recyclerTabContent.setNestedScrollingEnabled(false);
        recyclerTabContent.setHasFixedSize(false);
        progressTabContent  = findViewById(R.id.progress_tab_content);
        layoutTabEmpty      = findViewById(R.id.layout_tab_empty);
        txtTabEmptyIcon     = findViewById(R.id.txt_tab_empty_icon);
        txtTabEmptyLabel    = findViewById(R.id.txt_tab_empty_label);

        layoutSelectionBar  = findViewById(R.id.layout_selection_bar);
        txtSelectionCount   = findViewById(R.id.txt_selection_count);
        btnSelectionView    = findViewById(R.id.btn_selection_view);
        btnSelectionForward = findViewById(R.id.btn_selection_forward);
        btnSelectionDelete  = findViewById(R.id.btn_selection_delete);
        findViewById(R.id.btn_selection_close).setOnClickListener(v -> _exitSelectionMode());
        btnSelectionView.setOnClickListener(v -> _viewSelected());
        btnSelectionForward.setOnClickListener(v -> _forwardSelected());
        btnSelectionDelete.setOnClickListener(v -> _confirmDeleteSelected());

        btnJoinBig.setOnClickListener(v -> _toggleSubscribe());
        findViewById(R.id.action_search).setOnClickListener(v -> _openChannel(true));
        findViewById(R.id.action_share).setOnClickListener(v -> _shareChannel());
        findViewById(R.id.action_mute).setOnClickListener(v -> _toggleMute());
        findViewById(R.id.action_leave).setOnClickListener(v -> {
            if (isOwner) _openEditChannel(); else _confirmLeave();
        });

        swBlur.setOnCheckedChangeListener((btn, checked) ->
            NotificationPrivacyHelper.setBlurEnabled(this, channelId, checked));
        rowSubscribers.setOnClickListener(v -> {
            Intent i = new Intent(this, ChannelSubscribersActivity.class);
            i.putExtra("channel_id", channelId);
            startActivity(i);
        });

        tabMedia.setOnClickListener(v -> _selectTab(0));
        tabLinks.setOnClickListener(v -> _selectTab(1));
        tabVoice.setOnClickListener(v -> _selectTab(2));
    }

    private void _loadChannelInfo() {
        channelRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                channelName    = snap.child("name").getValue(String.class);
                channelDesc    = snap.child("description").getValue(String.class);
                channelPhoto   = snap.child("photo_url").getValue(String.class);
                channelHandle  = snap.child("handle").getValue(String.class);
                channelPrivacy = snap.child("privacy").getValue(String.class);
                ownerUid       = snap.child("owner_uid").getValue(String.class);
                isOwner        = myUid != null && myUid.equals(ownerUid);
                // Blur Notification Image only matters to someone who might
                // actually RECEIVE a notification for this channel's posts —
                // on your OWN channel, that's moot (see the self-notification
                // fix: you never get notified for your own posts), so it's
                // hidden here rather than shown with no real effect.
                rowBlur.setVisibility(isOwner ? View.GONE : View.VISIBLE);
                Long subCount  = snap.child("subscriber_count").getValue(Long.class);
                boolean verified = Boolean.TRUE.equals(snap.child("is_verified").getValue(Boolean.class));

                txtName.setText(channelName != null ? channelName : "Channel");
                imgVerified.setVisibility(verified ? View.VISIBLE : View.GONE);

                String privacyLabel = "private".equals(channelPrivacy) ? "Private Channel" : "Public Channel";
                long count = subCount != null ? subCount : 0;
                txtSubCount.setText(privacyLabel + " · " + _formatCount(count) + " subscribers");
                txtSubscribersCount.setText(_formatCount(count));

                boolean hasBio = channelDesc != null && !channelDesc.isEmpty();
                rowBio.setVisibility(hasBio ? View.VISIBLE : View.GONE);
                if (hasBio) {
                    _renderDescription(channelDesc, true);
                }

                txtHandle.setText(channelHandle != null && !channelHandle.isEmpty()
                    ? "@" + channelHandle : "");

                if (channelPhoto != null && !channelPhoto.isEmpty()) {
                    Glide.with(ChannelProfileActivity.this).load(channelPhoto).circleCrop().into(imgPhoto);
                } else {
                    imgPhoto.setImageResource(R.drawable.ic_channel);
                }

                // Owner is never in channel_subscribers (see Phase 2's admin
                // system notes) — skip that lookup entirely and go straight
                // to the subscribed-style UI so an owner never sees "Join
                // Channel" on their own channel.
                if (isOwner) {
                    _updateSubscribedUI();
                    rowSubscribers.setVisibility(View.VISIBLE);
                } else {
                    _loadSubscriptionState();
                }
                if (myUid != null && !isOwner) {
                    FirebaseDatabase.getInstance().getReference("channel_admins")
                        .child(channelId).child(myUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(DataSnapshot adminSnap) {
                                isChannelAdmin = Boolean.TRUE.equals(adminSnap.getValue(Boolean.class));
                                // Requirement 4 follow-up — regular subscribers
                                // never see this row at all; only owner/admin.
                                if (isChannelAdmin) rowSubscribers.setVisibility(View.VISIBLE);
                            }
                            @Override public void onCancelled(DatabaseError e) {}
                        });
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    /**
     * Renders the channel description with @handles, web links, emails and
     * phone numbers all clickable, and the text selectable.
     *
     * @handles go through the app's existing MentionHelper so tapping one
     * routes exactly like a mention inside a message does, rather than a
     * second divergent path. allowResolve is false on the repaint pass so a
     * handle that resolves can't kick off an endless resolve→repaint loop.
     */
    private void _renderDescription(String desc, boolean allowResolve) {
        if (desc == null || txtBio == null) return;
        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(desc);
        java.util.List<String> unknown = MentionHelper.applyMentions(this, ssb);

        // BufferType.SPANNABLE is required — without it a Spanned carrying no
        // URLSpan is silently downgraded to an immutable SpannedString and the
        // clickable spans never receive taps (the same trap already fixed in
        // MsgRenderer).
        txtBio.setText(ssb, TextView.BufferType.SPANNABLE);
        txtBio.setTextIsSelectable(true);
        android.text.util.Linkify.addLinks(txtBio,
            android.text.util.Linkify.WEB_URLS
            | android.text.util.Linkify.EMAIL_ADDRESSES
            | android.text.util.Linkify.PHONE_NUMBERS);
        SmartLinkMovementMethod.apply(txtBio);

        if (allowResolve && unknown != null && !unknown.isEmpty()) {
            MentionHelper.resolveAll(this, unknown, txtBio, ssb,
                () -> {
                    if (isFinishing() || isDestroyed()) return;
                    _renderDescription(desc, false);
                });
        }
    }

    private void _loadSubscriptionState() {
        if (myUid == null) return;
        FirebaseDatabase.getInstance().getReference("channel_subscribers")
            .child(channelId).child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    isSubscribed = snap.exists();
                    _updateSubscribedUI();
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    private void _updateSubscribedUI() {
        boolean showIconRow = isOwner || isSubscribed;
        btnJoinBig.setVisibility(showIconRow ? View.GONE : View.VISIBLE);
        rowIconActions.setVisibility(showIconRow ? View.VISIBLE : View.GONE);

        if (isOwner) {
            iconLeaveEdit.setText("edit");
            iconLeaveEdit.setTextColor(Color.parseColor("#229ED9"));
            iconLeaveEdit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#E8F5FC")));
            txtLeaveEditLabel.setText("Edit");
            txtLeaveEditLabel.setTextColor(Color.parseColor("#229ED9"));
        } else {
            iconLeaveEdit.setText("logout");
            iconLeaveEdit.setTextColor(Color.parseColor("#FF3B30"));
            iconLeaveEdit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FDEAEA")));
            txtLeaveEditLabel.setText("Leave");
            txtLeaveEditLabel.setTextColor(Color.parseColor("#FF3B30"));
        }
    }

    private void _loadMuteAndBlurState() {
        isMuted = getSharedPreferences("MutedChannels", MODE_PRIVATE).getBoolean(channelId, false);
        _updateMuteUI();
        swBlur.setChecked(NotificationPrivacyHelper.isBlurEnabled(this, channelId));
    }

    private void _updateMuteUI() {
        iconMute.setText(isMuted ? "notifications_off" : "notifications");
        txtMuteLabel.setText(isMuted ? "Unmute" : "Mute");
    }

    private void _toggleMute() {
        isMuted = !isMuted;
        getSharedPreferences("MutedChannels", MODE_PRIVATE).edit()
            .putBoolean(channelId, isMuted).apply();
        _updateMuteUI();
        Toast.makeText(this, isMuted ? "Channel muted" : "Channel unmuted", Toast.LENGTH_SHORT).show();
    }

    private void _toggleSubscribe() {
        if (myUid == null) return;
        DatabaseReference subRef    = FirebaseDatabase.getInstance().getReference("channel_subscribers").child(channelId).child(myUid);
        DatabaseReference userChRef = FirebaseDatabase.getInstance().getReference("user_channels").child(myUid).child(channelId);

        subRef.setValue(true);
        userChRef.setValue(true);
        channelRef.child("subscriber_count").setValue(ServerValue.increment(1));
        FcmTokenManager.subscribeChannel(channelId);
        isSubscribed = true;
        Toast.makeText(this, "Subscribed ✓", Toast.LENGTH_SHORT).show();
        _updateSubscribedUI();
    }

    private void _confirmLeave() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Leave " + (channelName != null ? channelName : "channel") + "?")
            .setMessage("You'll stop receiving posts from this channel.")
            .setPositiveButton("Leave", (d, w) -> _leave())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void _leave() {
        if (myUid == null) return;
        FirebaseDatabase.getInstance().getReference("channel_subscribers").child(channelId).child(myUid).removeValue();
        FirebaseDatabase.getInstance().getReference("user_channels").child(myUid).child(channelId).removeValue();
        channelRef.child("subscriber_count").setValue(ServerValue.increment(-1));
        FcmTokenManager.unsubscribeChannel(channelId);
        Toast.makeText(this, "Left channel", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void _openEditChannel() {
        Intent i = new Intent(this, ChannelSettingsActivity.class);
        i.putExtra("channel_id", channelId);
        startActivity(i);
    }

    private void _openChannel(boolean withSearch) {
        Intent i = new Intent(this, ChannelActivity.class);
        i.putExtra("channel_id", channelId);
        i.putExtra("channel_name", channelName);
        i.putExtra("channel_photo", channelPhoto);
        if (withSearch) i.putExtra("open_search", true);
        startActivity(i);
        if (!withSearch) finish();
    }

    private static final String WEB_BASE = "https://minigram.fireclashpro.com";

    private void _shareChannel() {
        if (channelHandle == null || channelHandle.isEmpty()) {
            Toast.makeText(this, "This channel has no public link yet", Toast.LENGTH_SHORT).show();
            return;
        }
        String link = WEB_BASE + "/admin/c/" + channelHandle;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, link);
        startActivity(Intent.createChooser(share, "Share channel via"));
    }

    private String _formatCount(long n) {
        if (n >= 1_000_000) return String.format(Locale.getDefault(), "%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format(Locale.getDefault(), "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Media / Links / Voice tabs
    // ═════════════════════════════════════════════════════════════════════

    private void _selectTab(int tab) {
        if (selectionMode) _exitSelectionMode();
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

        if (tab == 0) _loadMedia();
        else if (tab == 1) _loadLinks();
        else _loadVoice();
    }

    private void _showEmpty(String icon, String label) {
        txtTabEmptyIcon.setText(icon);
        txtTabEmptyLabel.setText(label);
        layoutTabEmpty.setVisibility(View.VISIBLE);
    }

    private void _loadMedia() {
        recyclerTabContent.setLayoutManager(new GridLayoutManager(this, 3));
        if (mediaLoadedOnce) { _attachCached(mediaAdapter, "photo_library", "No shared photos yet"); return; }
        progressTabContent.setVisibility(View.VISIBLE);
        _loadFromChannelPosts("image", items -> {
            mediaLoadedOnce = true;
            progressTabContent.setVisibility(View.GONE);
            if (items.isEmpty()) { _showEmpty("photo_library", "No shared photos yet"); return; }
            mediaAdapter = new ChannelMediaAdapter(items);
            recyclerTabContent.setAdapter(mediaAdapter);
            recyclerTabContent.setVisibility(View.VISIBLE);
        });
    }

    private void _loadLinks() {
        recyclerTabContent.setLayoutManager(new LinearLayoutManager(this));
        if (linksLoadedOnce) { _attachCached(linksAdapter, "link", "No shared links yet"); return; }
        progressTabContent.setVisibility(View.VISIBLE);
        _loadFromChannelPosts("link", items -> {
            linksLoadedOnce = true;
            progressTabContent.setVisibility(View.GONE);
            if (items.isEmpty()) { _showEmpty("link", "No shared links yet"); return; }
            linksAdapter = new ChannelLinkAdapter(items);
            recyclerTabContent.setAdapter(linksAdapter);
            recyclerTabContent.setVisibility(View.VISIBLE);
        });
    }

    private void _loadVoice() {
        recyclerTabContent.setLayoutManager(new LinearLayoutManager(this));
        if (voiceLoadedOnce) { _attachCached(voiceAdapter, "mic", "No shared voice messages yet"); return; }
        progressTabContent.setVisibility(View.VISIBLE);
        _loadFromChannelPosts("voice", items -> {
            voiceLoadedOnce = true;
            progressTabContent.setVisibility(View.GONE);
            if (items.isEmpty()) { _showEmpty("mic", "No shared voice messages yet"); return; }
            voiceAdapter = new ChannelVoiceAdapter(items);
            recyclerTabContent.setAdapter(voiceAdapter);
            recyclerTabContent.setVisibility(View.VISIBLE);
        });
    }

    /** BUG FIX — re-attach THIS tab's own remembered adapter, instead of
     *  trusting whatever the RecyclerView happened to still be showing
     *  from the last tab visited. */
    private void _attachCached(RecyclerView.Adapter<?> adapter, String icon, String emptyLabel) {
        boolean has = adapter != null && adapter.getItemCount() > 0;
        if (has) recyclerTabContent.setAdapter(adapter);
        recyclerTabContent.setVisibility(has ? View.VISIBLE : View.GONE);
        if (!has) _showEmpty(icon, emptyLabel);
    }

    private String _keyFor(HashMap<String, Object> item) {
        return "p_" + item.get("post_id");
    }

    private void _loadFromChannelPosts(String filterType, java.util.function.Consumer<ArrayList<HashMap<String, Object>>> onItems) {
        FirebaseDatabase.getInstance().getReference("channel_posts").child(channelId)
            .orderByChild("timestamp").limitToLast(200)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) { onItems.accept(new ArrayList<>()); return; }
                    ArrayList<HashMap<String, Object>> items = new ArrayList<>();
                    for (DataSnapshot p : snap.getChildren()) {
                        if (Boolean.TRUE.equals(p.child("is_deleted_for_all").getValue(Boolean.class))) continue;
                        String postId = p.getKey();
                        String type   = p.child("type").getValue(String.class);
                        Long   ts     = p.child("timestamp").getValue(Long.class);

                        if ("image".equals(filterType) && "image".equals(type)) {
                            HashMap<String, Object> item = new HashMap<>();
                            item.put("type", "image");
                            item.put("url", p.child("image_url").getValue(String.class));
                            item.put("post_id", postId); item.put("timestamp", ts);
                            items.add(0, item);
                        } else if ("voice".equals(filterType) && "voice".equals(type)) {
                            HashMap<String, Object> item = new HashMap<>();
                            item.put("type", "voice");
                            item.put("url", p.child("voice_url").getValue(String.class));
                            item.put("post_id", postId); item.put("timestamp", ts);
                            items.add(0, item);
                        } else if ("link".equals(filterType)) {
                            String text = p.child("text").getValue(String.class);
                            String url = text != null ? _extractFirstUrl(text) : null;
                            if (url != null) {
                                HashMap<String, Object> item = new HashMap<>();
                                item.put("type", "link");
                                item.put("url", url);
                                item.put("text_preview", text.length() > 100 ? text.substring(0, 100) + "…" : text);
                                item.put("post_id", postId); item.put("timestamp", ts);
                                items.add(0, item);
                            }
                        }
                    }
                    onItems.accept(items);
                }
                @Override public void onCancelled(DatabaseError e) { onItems.accept(new ArrayList<>()); }
            });
    }

    private String _extractFirstUrl(String text) {
        java.util.regex.Matcher m = android.util.Patterns.WEB_URL.matcher(text);
        return m.find() ? m.group() : null;
    }

    private void _openPost(String postId) {
        Intent i = new Intent(this, ChannelActivity.class);
        i.putExtra("channel_id", channelId);
        i.putExtra("scroll_to_post_id", postId);
        startActivity(i);
    }

    // ── Selection mode ──────────────────────────────────────────────────────

    private void _onItemLongPress(HashMap<String, Object> item) {
        if (!selectionMode) { selectionMode = true; layoutSelectionBar.setVisibility(View.VISIBLE); }
        _toggleSelected(item);
    }

    private void _onItemTap(HashMap<String, Object> item) {
        if (selectionMode) { _toggleSelected(item); return; }
        _openPost((String) item.get("post_id"));
    }

    private void _toggleSelected(HashMap<String, Object> item) {
        String key = _keyFor(item);
        if (selectedKeys.contains(key)) selectedKeys.remove(key);
        else { selectedKeys.add(key); itemsByKey.put(key, item); }

        if (selectedKeys.isEmpty()) { _exitSelectionMode(); return; }
        _updateSelectionBar();
        RecyclerView.Adapter<?> current = recyclerTabContent.getAdapter();
        if (current != null) current.notifyDataSetChanged();
    }

    private void _updateSelectionBar() {
        int n = selectedKeys.size();
        txtSelectionCount.setText(n + " selected");
        btnSelectionView.setVisibility(n == 1 ? View.VISIBLE : View.GONE);
        // Delete removes the post for everyone in the channel — only the
        // owner/an admin should ever see this option; a regular subscriber
        // gets View/Forward/Cancel only.
        btnSelectionDelete.setVisibility((isOwner || isChannelAdmin) ? View.VISIBLE : View.GONE);
    }

    private void _exitSelectionMode() {
        selectionMode = false;
        selectedKeys.clear();
        itemsByKey.clear();
        layoutSelectionBar.setVisibility(View.GONE);
        RecyclerView.Adapter<?> current = recyclerTabContent.getAdapter();
        if (current != null) current.notifyDataSetChanged();
    }

    /** View — jumps into the channel at the exact post and highlights it
     *  (ChannelActivity.scrollToPost(), already built when this screen's
     *  own Search/View-from-tap features were added). */
    private void _viewSelected() {
        if (selectedKeys.size() != 1) return;
        HashMap<String, Object> item = itemsByKey.get(selectedKeys.iterator().next());
        if (item == null) return;
        _openPost((String) item.get("post_id"));
        _exitSelectionMode();
    }

    private void _forwardSelected() {
        if (selectedKeys.isEmpty()) return;
        List<HashMap<String, Object>> toForward = new ArrayList<>();
        for (String key : selectedKeys) {
            HashMap<String, Object> item = itemsByKey.get(key);
            if (item == null) continue;
            HashMap<String, Object> msg = new HashMap<>();
            String type = (String) item.get("type");
            if ("image".equals(type)) {
                msg.put("type", "image"); msg.put("image_url", item.get("url")); msg.put("caption", "");
            } else if ("voice".equals(type)) {
                msg.put("type", "voice"); msg.put("voice_url", item.get("url")); msg.put("duration", 0);
            } else {
                msg.put("type", "text"); msg.put("text", item.get("url"));
            }
            toForward.add(msg);
        }
        if (toForward.isEmpty()) return;
        Intent i = new Intent(this, ForwardChatListActivity.class);
        i.putExtra(ForwardChatListActivity.EXTRA_MESSAGES_JSON, new com.google.gson.Gson().toJson(toForward));
        startActivity(i);
        _exitSelectionMode();
    }

    /** Delete here means "for everyone" (unlike the profile tabs) since
     *  these ARE the channel's real posts, not a personal tracking index —
     *  only allowed for owner/admin, matching ChannelActivity's own
     *  moderation permissions; a plain subscriber never gets Delete since
     *  they never see this action row on someone else's/the channel's
     *  content in the first place (no long-press selection UI difference
     *  needed — the server-side rule is the real gate regardless). */
    private void _confirmDeleteSelected() {
        if (selectedKeys.isEmpty()) return;
        int n = selectedKeys.size();
        new android.app.AlertDialog.Builder(this)
            .setTitle(n == 1 ? "Delete post?" : "Delete " + n + " posts?")
            .setMessage("This removes " + (n == 1 ? "it" : "them") + " from the channel for everyone.")
            .setPositiveButton("Delete", (d, w) -> _deleteSelected())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void _deleteSelected() {
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        for (String key : selectedKeys) {
            HashMap<String, Object> item = itemsByKey.get(key);
            if (item == null) continue;
            String postId = (String) item.get("post_id");
            if (postId != null) {
                root.child("channel_posts").child(channelId).child(postId)
                    .child("is_deleted_for_all").setValue(true);
            }
        }
        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
        mediaLoadedOnce = false; linksLoadedOnce = false; voiceLoadedOnce = false;
        _exitSelectionMode();
        _selectTab(activeTab);
    }

    // ── Adapters ─────────────────────────────────────────────────────────────

    private class ChannelMediaAdapter extends RecyclerView.Adapter<ChannelMediaAdapter.VH> {
        private final ArrayList<HashMap<String, Object>> items;
        ChannelMediaAdapter(ArrayList<HashMap<String, Object>> items) { this.items = items; }
        class VH extends RecyclerView.ViewHolder {
            ImageView img; View dim; TextView check;
            VH(View v) {
                super(v);
                img   = v.findViewById(R.id.img_media_thumb);
                dim   = v.findViewById(R.id.view_media_selected_dim);
                check = v.findViewById(R.id.txt_media_check);
            }
        }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_profile_media, parent, false);
            // Same two fixes as MyProfileActivity's media grid: parent.getWidth()
            // is 0 before the RecyclerView is measured (cells got height 0 and
            // the grid looked empty until the tab was tapped again), and a plain
            // ViewGroup.LayoutParams has no margin support so it silently
            // discarded the item layout's margins, leaving no gaps between
            // thumbnails.
            int available = parent.getWidth();
            if (available <= 0) {
                available = getResources().getDisplayMetrics().widthPixels
                    - recyclerTabContent.getPaddingLeft()
                    - recyclerTabContent.getPaddingRight();
            }
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            int cell = available / 3;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                cell -= (mlp.leftMargin + mlp.rightMargin);
            }
            lp.height = Math.max(cell, 1);
            v.setLayoutParams(lp);
            return new VH(v);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int position) {
            HashMap<String, Object> item = items.get(position);
            Glide.with(ChannelProfileActivity.this).load((String) item.get("url"))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.img_placeholder).into(h.img);
            boolean selected = selectedKeys.contains(_keyFor(item));
            h.dim.setVisibility(selected ? View.VISIBLE : View.GONE);
            h.check.setVisibility(selected ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> _onItemTap(item));
            h.itemView.setOnLongClickListener(v -> { _onItemLongPress(item); return true; });
        }
        @Override public int getItemCount() { return items.size(); }
    }

    private class ChannelLinkAdapter extends RecyclerView.Adapter<ChannelLinkAdapter.VH> {
        private final ArrayList<HashMap<String, Object>> items;
        ChannelLinkAdapter(ArrayList<HashMap<String, Object>> items) { this.items = items; }
        class VH extends RecyclerView.ViewHolder {
            TextView txtUrl, txtPreview, check;
            VH(View v) {
                super(v);
                txtUrl = v.findViewById(R.id.txt_link_url);
                txtPreview = v.findViewById(R.id.txt_link_preview);
                check = v.findViewById(R.id.txt_link_check);
            }
        }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_profile_link, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int position) {
            HashMap<String, Object> item = items.get(position);
            h.txtUrl.setText((String) item.get("url"));
            h.txtPreview.setText((String) item.get("text_preview"));
            h.check.setVisibility(selectedKeys.contains(_keyFor(item)) ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> {
                if (selectionMode) { _onItemTap(item); return; }
                String url = (String) item.get("url");
                if (url == null || url.isEmpty()) return;
                try {
                    String withScheme = url.startsWith("http") ? url : "https://" + url;
                    h.itemView.getContext().startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(withScheme)));
                } catch (Exception ignored) {}
            });
            h.itemView.setOnLongClickListener(v -> { _onItemLongPress(item); return true; });
        }
        @Override public int getItemCount() { return items.size(); }
    }

    /** Voice — no separate download button anymore. Tap: not cached →
     *  spinner while VoiceCacheHelper downloads into the app's private
     *  cache → auto-plays the moment it's ready (icon becomes pause).
     *  Already cached → plays immediately. Tap again → pause/resume. */
    private class ChannelVoiceAdapter extends RecyclerView.Adapter<ChannelVoiceAdapter.VH> {
        private final ArrayList<HashMap<String, Object>> items;
        private android.media.MediaPlayer player;
        private int playingPosition = -1;
        private int downloadingPosition = -1;

        ChannelVoiceAdapter(ArrayList<HashMap<String, Object>> items) { this.items = items; }

        class VH extends RecyclerView.ViewHolder {
            TextView btnPlay, txtDuration, check;
            ProgressBar spinner;
            VH(View v) {
                super(v);
                btnPlay = v.findViewById(R.id.btn_voice_play);
                spinner = v.findViewById(R.id.progress_voice_download);
                txtDuration = v.findViewById(R.id.txt_voice_duration);
                check = v.findViewById(R.id.txt_voice_check);
            }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_profile_voice, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int position) {
            HashMap<String, Object> item = items.get(position);
            String url = (String) item.get("url");
            boolean isPlaying = position == playingPosition;
            boolean isDownloading = position == downloadingPosition;
            boolean isCached = url != null && VoiceCacheHelper.isCached(ChannelProfileActivity.this, url);

            h.spinner.setVisibility(isDownloading ? View.VISIBLE : View.GONE);
            h.btnPlay.setVisibility(isDownloading ? View.INVISIBLE : View.VISIBLE);
            h.btnPlay.setText(isPlaying ? "pause" : (isCached ? "play_arrow" : "download"));

            h.btnPlay.setOnClickListener(v -> {
                if (isDownloading) return;
                if (isPlaying) { _pauseVoice(); notifyItemChanged(position); return; }
                _playOrDownload(url, position);
            });

            h.check.setVisibility(selectedKeys.contains(_keyFor(item)) ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> _onItemTap(item));
            h.itemView.setOnLongClickListener(v -> { _onItemLongPress(item); return true; });
        }

        private void _playOrDownload(String url, int position) {
            if (url == null || url.isEmpty()) return;
            if (VoiceCacheHelper.isCached(ChannelProfileActivity.this, url)) {
                _playLocal(VoiceCacheHelper.cacheFileFor(ChannelProfileActivity.this, url), position);
                return;
            }
            downloadingPosition = position;
            notifyItemChanged(position);
            VoiceCacheHelper.ensureDownloaded(ChannelProfileActivity.this, url, new VoiceCacheHelper.Callback() {
                @Override public void onReady(File localFile) {
                    downloadingPosition = -1;
                    _playLocal(localFile, position); // auto-play right after download
                }
                @Override public void onError() {
                    downloadingPosition = -1;
                    notifyItemChanged(position);
                    Toast.makeText(ChannelProfileActivity.this, "Couldn't download voice message", Toast.LENGTH_SHORT).show();
                }
            });
        }

        private void _playLocal(File file, int position) {
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
                Toast.makeText(ChannelProfileActivity.this, "Couldn't play voice message", Toast.LENGTH_SHORT).show();
            }
        }

        private void _pauseVoice() {
            if (player != null) { try { player.pause(); } catch (Exception ignored) {} }
            int old = playingPosition; playingPosition = -1;
            if (old != -1 && old < items.size()) notifyItemChanged(old);
        }

        private void _stopVoice() {
            if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
            int old = playingPosition; playingPosition = -1;
            if (old != -1 && old < items.size()) notifyItemChanged(old);
        }

        @Override public int getItemCount() { return items.size(); }
    }
}
