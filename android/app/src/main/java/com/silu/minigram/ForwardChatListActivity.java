package com.silu.minigram;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ForwardChatListActivity — Telegram-style "Forward to..." picker.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * REPLACES
 * ─────────────────────────────────────────────────────────────────────────────
 * The old in-place AlertDialog + ListView (_showForwardDialog, duplicated
 * separately in both ChatActivity and ChannelActivity). That dialog is gone —
 * both callers now just build a message list and launch this Activity.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * BUGS FIXED THAT THE OLD DIALOG HAD (see ChatActivity/ChannelActivity diffs
 * for the removed code, and ForwardHelper.java for the copy-logic fix)
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. Forwarding a message never updated user_chats (no chat-list preview
 *    update), never incremented unread, and NEVER sent a push notification —
 *    the recipient had no way to know a message arrived unless they happened
 *    to open the chat. Fixed in _forwardToChat() / _forwardToChannel() below,
 *    mirroring exactly what a normal send already does in
 *    ChatActivity._updateUserChats() / _sendPeerNotification() and
 *    ChannelActivity._sendChannelNotification().
 * 2. Forward-to-channel had NO ownership check — any subscriber of any
 *    channel could forward a post INTO that channel as if they owned it,
 *    because the old target list was built from user_channels (which stores
 *    owner and subscriber identically, no role field). Fixed: the channel
 *    section here is built from `channels.orderByChild("owner_uid")`, so only
 *    channels you actually own ever appear as forward targets.
 * 3. voice_url/duration/link_preview_* silently dropped on forward (hardcoded
 *    field whitelist). Fixed generically in ForwardHelper.buildForwardedCopy().
 * 4. ContentTrackingHelper.trackSent() — its own doc comment says forwarding
 *    is one of the call sites, but it was never actually wired in. Now it is,
 *    so forwarded media shows up in the sender's Media/Links profile tab like
 *    everything else they send.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CHANNEL FORWARD-RESTRICTION — DEFENSE IN DEPTH
 * ─────────────────────────────────────────────────────────────────────────────
 * The UI-level gate (hiding the Forward row) lives in ChannelActivity /
 * MsgActionSheet, BEFORE this Activity is ever launched. This Activity adds a
 * SECOND, independent check: if launched with a source channel id, it re-reads
 * that channel's owner_uid + restrict_forwarding fresh from the database right
 * before writing anything, and refuses to proceed if forwarding is restricted
 * and the current user isn't the owner — even if the UI gate was somehow
 * stale or bypassed. True "no bypass, anywhere" enforcement additionally
 * requires a Firebase Security Rule on top of this (a modified client could
 * still call the Firebase SDK directly, skipping this Activity entirely) —
 * that rule isn't something I can add without your rules file; see the
 * summary message for the rule to add.
 */
public class ForwardChatListActivity extends AppCompatActivity {

    public static final String EXTRA_MESSAGES_JSON      = "messages_json";
    public static final String EXTRA_EXCLUDE_CHAT_ID     = "exclude_chat_id";
    public static final String EXTRA_EXCLUDE_CHANNEL_ID  = "exclude_channel_id";
    /** Set only when forwarding FROM a channel post — enables the re-check above. */
    public static final String EXTRA_SOURCE_CHANNEL_ID   = "source_channel_id";

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM   = 1;

    /** One row in the merged (headers + items) target list. */
    private static class Row {
        int    viewType;
        String header;                          // TYPE_HEADER
        String targetId, targetType;             // TYPE_ITEM: "chat" | "channel"
        String name = "", photo = "";
        String peerUid = "", peerFcmToken = "";   // TYPE_ITEM (chat only)
    }

    private FirebaseDatabase database;
    private String myUid, myName = "Someone";
    private String excludeChatId, excludeChannelId, sourceChannelId;

    private List<HashMap<String, Object>> messagesToForward;

    private final List<Row> allRows     = new ArrayList<>();
    private final List<Row> visibleRows = new ArrayList<>();
    private final Set<String> selectedTargetIds = new LinkedHashSet<>();
    /** targetId → Row, for O(1) lookup when actually performing the forward. */
    private final HashMap<String, Row> rowsById = new HashMap<>();

    private ListView   lv;
    private EditText   etSearch;
    private TextView   btnClose, txtTitle, btnConfirm, txtNoResults;
    private View       layoutConfirm, layoutEmpty;
    private RowAdapter adapter;

    private boolean isSending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdgeHelper.setup(this);
        setContentView(R.layout.activity_forward_chat_list);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));

        if (FirebaseAuth.getInstance().getCurrentUser() == null) { finish(); return; }
        myUid    = FirebaseAuth.getInstance().getCurrentUser().getUid();
        database = FirebaseDatabase.getInstance();

        excludeChatId    = getIntent().getStringExtra(EXTRA_EXCLUDE_CHAT_ID);
        excludeChannelId = getIntent().getStringExtra(EXTRA_EXCLUDE_CHANNEL_ID);
        sourceChannelId  = getIntent().getStringExtra(EXTRA_SOURCE_CHANNEL_ID);

        String json = getIntent().getStringExtra(EXTRA_MESSAGES_JSON);
        if (json == null || json.isEmpty()) { finish(); return; }
        try {
            java.lang.reflect.Type t =
                new TypeToken<List<HashMap<String, Object>>>() {}.getType();
            messagesToForward = new Gson().fromJson(json, t);
        } catch (Exception e) {
            messagesToForward = null;
        }
        if (messagesToForward == null || messagesToForward.isEmpty()) {
            Toast.makeText(this, "Nothing to forward", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        loadTargets();
    }

    private void initViews() {
        lv            = findViewById(R.id.lv_forward_targets);
        etSearch      = findViewById(R.id.et_forward_search);
        btnClose      = findViewById(R.id.btn_forward_close);
        txtTitle      = findViewById(R.id.txt_forward_title);
        btnConfirm    = findViewById(R.id.btn_forward_confirm);
        txtNoResults  = findViewById(R.id.txt_forward_no_results);
        layoutConfirm = findViewById(R.id.layout_forward_confirm);
        layoutEmpty   = findViewById(R.id.layout_forward_empty);

        txtTitle.setText(messagesToForward.size() > 1
            ? "Forward " + messagesToForward.size() + " messages"
            : "Forward to...");

        btnClose.setOnClickListener(v -> finish());

        adapter = new RowAdapter();
        lv.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { applyFilter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnConfirm.setOnClickListener(v -> performForward());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LOAD TARGETS — my DM chats + channels I OWN (not "channels I'm in")
    // ─────────────────────────────────────────────────────────────────────────

    private void loadTargets() {
        // Own display name, for the push-notification title (fetched once,
        // same pattern ChatActivity._dispatchPeerPush already uses).
        database.getReference("users").child(myUid).child("fullname")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String n = snap.getValue(String.class);
                    if (n != null && !n.isEmpty()) myName = n;
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });

        final List<Row> chatRows    = new ArrayList<>();
        final List<Row> channelRows = new ArrayList<>();
        final boolean[] chatsDone    = {false};
        final boolean[] channelsDone = {false};

        Runnable maybeFinish = () -> {
            if (!chatsDone[0] || !channelsDone[0]) return;
            runOnUiThread(() -> assembleRows(chatRows, channelRows));
        };

        // ── DM chats ─────────────────────────────────────────────────────────
        database.getReference("user_chats").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    ArrayList<String> cIds     = new ArrayList<>();
                    ArrayList<String> peerUids = new ArrayList<>();
                    for (DataSnapshot child : snap.getChildren()) {
                        String cId  = child.getKey();
                        String pUid = child.child("peer_uid").getValue(String.class);
                        if (cId == null || cId.equals(excludeChatId)) continue;
                        cIds.add(cId);
                        peerUids.add(pUid);
                    }
                    if (cIds.isEmpty()) { chatsDone[0] = true; maybeFinish.run(); return; }

                    final int[] remaining = {cIds.size()};
                    for (int i = 0; i < cIds.size(); i++) {
                        final String cId  = cIds.get(i);
                        final String pUid = peerUids.get(i);
                        Row row = new Row();
                        row.viewType   = TYPE_ITEM;
                        row.targetType = "chat";
                        row.targetId   = cId;
                        row.peerUid    = pUid != null ? pUid : "";
                        row.name       = "User";

                        if (pUid == null) {
                            chatRows.add(row);
                            remaining[0]--;
                            if (remaining[0] == 0) { chatsDone[0] = true; maybeFinish.run(); }
                            continue;
                        }
                        // Single read gets name + photo + fcm_token together —
                        // no extra round trip needed at send time.
                        database.getReference("users").child(pUid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot us) {
                                    String n = us.child("fullname").getValue(String.class);
                                    String p = us.child("photo_url").getValue(String.class);
                                    if (n != null && !n.isEmpty()) row.name = n;
                                    if (p != null) row.photo = p;
                                    chatRows.add(row);
                                    remaining[0]--;
                                    if (remaining[0] == 0) { chatsDone[0] = true; maybeFinish.run(); }
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {
                                    chatRows.add(row);
                                    remaining[0]--;
                                    if (remaining[0] == 0) { chatsDone[0] = true; maybeFinish.run(); }
                                }
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    chatsDone[0] = true; maybeFinish.run();
                }
            });

        // ── Channels I OWN — SECURITY FIX: not "channels I'm in" ────────────
        // Same query ChatlistActivity already uses elsewhere to find a user's
        // own channels (database.getReference("channels")
        // .orderByChild("owner_uid").equalTo(uid)) — proven, indexed pattern.
        database.getReference("channels").orderByChild("owner_uid").equalTo(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot ch : snap.getChildren()) {
                        String chId = ch.getKey();
                        if (chId == null || chId.equals(excludeChannelId)) continue;
                        Row row = new Row();
                        row.viewType   = TYPE_ITEM;
                        row.targetType = "channel";
                        row.targetId   = chId;
                        String n = ch.child("name").getValue(String.class);
                        String p = ch.child("photo_url").getValue(String.class);
                        row.name  = (n != null && !n.isEmpty()) ? n : "Channel";
                        row.photo = p != null ? p : "";
                        channelRows.add(row);
                    }
                    channelsDone[0] = true; maybeFinish.run();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    channelsDone[0] = true; maybeFinish.run();
                }
            });
    }

    private void assembleRows(List<Row> chatRows, List<Row> channelRows) {
        allRows.clear();
        rowsById.clear();

        if (!channelRows.isEmpty()) {
            Row header = new Row();
            header.viewType = TYPE_HEADER;
            header.header   = "Your Channels";
            allRows.add(header);
            for (Row r : channelRows) { allRows.add(r); rowsById.put(r.targetId, r); }
        }
        if (!chatRows.isEmpty()) {
            Row header = new Row();
            header.viewType = TYPE_HEADER;
            header.header   = "Chats";
            allRows.add(header);
            for (Row r : chatRows) { allRows.add(r); rowsById.put(r.targetId, r); }
        }

        layoutEmpty.setVisibility((chatRows.isEmpty() && channelRows.isEmpty())
            ? View.VISIBLE : View.GONE);

        applyFilter(etSearch.getText().toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEARCH — small, already-loaded dataset (my chats + my channels), so a
    // plain in-memory filter on every keystroke is plenty fast; no separate
    // Firebase query needed (and none of the full-table-download problems the
    // old forward code's own comments already warned about elsewhere).
    // ─────────────────────────────────────────────────────────────────────────

    private void applyFilter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ENGLISH);
        visibleRows.clear();

        String pendingHeader = null;
        boolean pendingHeaderHasItems = false;
        int itemCount = 0;

        for (Row r : allRows) {
            if (r.viewType == TYPE_HEADER) {
                // Flush a previous header only if it ended up with ≥1 match.
                pendingHeader = r.header;
                pendingHeaderHasItems = false;
                continue;
            }
            boolean matches = q.isEmpty() || r.name.toLowerCase(Locale.ENGLISH).contains(q);
            if (!matches) continue;

            if (pendingHeader != null && !pendingHeaderHasItems) {
                Row h = new Row();
                h.viewType = TYPE_HEADER;
                h.header   = pendingHeader;
                visibleRows.add(h);
                pendingHeaderHasItems = true;
            }
            visibleRows.add(r);
            itemCount++;
        }

        txtNoResults.setVisibility(!q.isEmpty() && itemCount == 0 ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SELECTION
    // ─────────────────────────────────────────────────────────────────────────

    private void toggleSelection(Row r) {
        if (selectedTargetIds.contains(r.targetId)) selectedTargetIds.remove(r.targetId);
        else selectedTargetIds.add(r.targetId);
        adapter.notifyDataSetChanged();
        updateConfirmBar();
    }

    private void updateConfirmBar() {
        int n = selectedTargetIds.size();
        layoutConfirm.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        btnConfirm.setText(n > 1 ? "Forward (" + n + ")" : "Forward");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADAPTER
    // ─────────────────────────────────────────────────────────────────────────

    private class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleRows.size(); }
        @Override public Object getItem(int i) { return visibleRows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return visibleRows.get(i).viewType; }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            Row r = visibleRows.get(i);

            if (r.viewType == TYPE_HEADER) {
                if (cv == null) cv = LayoutInflater.from(ForwardChatListActivity.this)
                    .inflate(R.layout.item_forward_section_header, parent, false);
                ((TextView) cv.findViewById(R.id.txt_forward_section_label)).setText(r.header);
                return cv;
            }

            if (cv == null) cv = LayoutInflater.from(ForwardChatListActivity.this)
                .inflate(R.layout.item_forward_target, parent, false);

            android.widget.ImageView imgAv = cv.findViewById(R.id.img_forward_avatar);
            View     dim      = cv.findViewById(R.id.view_forward_selected_dim);
            TextView chk      = cv.findViewById(R.id.txt_forward_check);
            TextView txtName  = cv.findViewById(R.id.txt_forward_name);
            TextView txtSub   = cv.findViewById(R.id.txt_forward_subtitle);

            txtName.setText(r.name);
            boolean isChannel = "channel".equals(r.targetType);
            if (isChannel) {
                txtSub.setText("Channel");
                txtSub.setVisibility(View.VISIBLE);
            } else {
                txtSub.setVisibility(View.GONE);
            }

            boolean selected = selectedTargetIds.contains(r.targetId);
            dim.setVisibility(selected ? View.VISIBLE : View.GONE);
            chk.setVisibility(selected ? View.VISIBLE : View.GONE);

            int placeholder = isChannel ? R.drawable.ic_channel : R.drawable.ic_person;
            Glide.with(ForwardChatListActivity.this)
                .load(r.photo)
                .apply(new RequestOptions()
                    .placeholder(placeholder)
                    .error(placeholder)
                    .circleCrop())
                .into(imgAv);

            cv.setOnClickListener(v -> toggleSelection(r));
            return cv;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PERFORM FORWARD
    // ─────────────────────────────────────────────────────────────────────────

    private void performForward() {
        if (isSending || selectedTargetIds.isEmpty()) return;

        // ── Defense in depth: re-verify the SOURCE channel's forward
        // restriction fresh from the DB, right before writing anything. The
        // UI already prevented reaching this screen when forwarding is
        // restricted (see ChannelActivity/MsgActionSheet), but that check ran
        // when the action sheet opened — this one runs at the moment of the
        // actual write, against current data, closing any staleness gap.
        if (sourceChannelId != null && !sourceChannelId.isEmpty()) {
            isSending = true;
            btnConfirm.setEnabled(false);
            database.getReference("channels").child(sourceChannelId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String ownerUid = snap.child("owner_uid").getValue(String.class);
                        boolean restricted = Boolean.TRUE.equals(
                            snap.child("restrict_forwarding").getValue(Boolean.class));
                        boolean amOwner = myUid.equals(ownerUid);
                        if (restricted && !amOwner) {
                            isSending = false;
                            btnConfirm.setEnabled(true);
                            Toast.makeText(ForwardChatListActivity.this,
                                "The channel owner has disabled forwarding for this post",
                                Toast.LENGTH_LONG).show();
                            return;
                        }
                        doForwardWrites();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        isSending = false;
                        btnConfirm.setEnabled(true);
                        Toast.makeText(ForwardChatListActivity.this,
                            "Couldn't verify permissions — try again", Toast.LENGTH_SHORT).show();
                    }
                });
            return;
        }

        isSending = true;
        btnConfirm.setEnabled(false);
        doForwardWrites();
    }

    private void doForwardWrites() {
        int count = 0;
        for (String targetId : selectedTargetIds) {
            Row target = rowsById.get(targetId);
            if (target == null) continue;
            if ("chat".equals(target.targetType)) _forwardToChat(target);
            else                                  _forwardToChannel(target);
            count++;
        }

        Toast.makeText(this,
            "Forwarded to " + count + (count == 1 ? " recipient" : " recipients"),
            Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    /**
     * Forward to a DM chat — writes every selected message, THEN replicates
     * the same side effects a normal send already gets (see
     * ChatActivity._updateUserChats / _sendPeerNotification): chat-list
     * preview update on both sides, unread increment for the recipient, and
     * a push notification. The old dialog did none of this.
     */
    private void _forwardToChat(final Row target) {
        final com.silu.minigram.e2ee.E2ee e2 = com.silu.minigram.e2ee.E2ee.get(getApplicationContext(), myUid);
        if (target.peerUid.isEmpty()) { _forwardToChatNow(target, false); return; }
        e2.refreshPeer(target.targetId, target.peerUid,
            ok -> _forwardToChatNow(target, ok || e2.isEncrypted(target.targetId)));
    }

    private void _forwardToChatNow(final Row target, final boolean enc) {
        final com.silu.minigram.e2ee.E2ee e2 = com.silu.minigram.e2ee.E2ee.get(getApplicationContext(), myUid);
        DatabaseReference tRef = database.getReference("chats").child(target.targetId);
        for (HashMap<String, Object> om : messagesToForward) {
            final HashMap<String, Object> copy = ForwardHelper.buildForwardedCopy(om, myUid, false);
            final String nId = tRef.push().getKey();
            if (nId == null) continue;
            final DatabaseReference nRef = tRef.child(nId);
            if (enc) {
                if (om.get("e2ee_media_url") != null) {      // media that is already an encrypted blob: re-wrap the same pointer + key
                    String t = String.valueOf(om.get("type"));
                    copy.put("voice".equals(t) ? "voice_url" : "image_url", om.get("e2ee_media_url"));
                    copy.put("e2ee_media_key", om.get("e2ee_media_key"));
                }
                e2.prepareOutgoing(target.targetId, target.peerUid, nId, copy, new com.silu.minigram.e2ee.E2ee.OutCb() {
                    @Override public void ready(java.util.Map<String, Object> toStore) { nRef.setValue(toStore); }
                    @Override public void plainFallback() { nRef.setValue(copy); }
                    @Override public void error(String m) { }
                });
            } else {
                nRef.setValue(copy);
                ContentTrackingHelper.trackSent(myUid,
                    (String) copy.get("type"),
                    (String) copy.get("text"),
                    (String) copy.get("image_url"),
                    (String) copy.get("voice_url"),
                    "chat", target.targetId, nId);
            }
        }

        final String realPreview = ForwardHelper.previewFor(messagesToForward);
        String preview = enc ? com.silu.minigram.e2ee.E2ee.GENERIC_PREVIEW : realPreview;
        long ts = System.currentTimeMillis();
        if (enc) e2.setLocalPreview(target.targetId, realPreview, ts);

        DatabaseReference userChatsRef = database.getReference("user_chats");
        HashMap<String, Object> myMeta = new HashMap<>();
        myMeta.put("peer_uid",  target.peerUid);
        myMeta.put("last_msg",  preview);
        myMeta.put("last_time", ts);
        userChatsRef.child(myUid).child(target.targetId).updateChildren(myMeta);

        if (!target.peerUid.isEmpty()) {
            userChatsRef.child(target.peerUid).child(target.targetId).child("unread")
                .setValue(ServerValue.increment(1));

            HashMap<String, Object> peerMeta = new HashMap<>();
            peerMeta.put("peer_uid",  myUid);
            peerMeta.put("last_msg",  preview);
            peerMeta.put("last_time", ts);
            userChatsRef.child(target.peerUid).child(target.targetId).updateChildren(peerMeta);

            {
                HashMap<String, Object> data = new HashMap<>();
                data.put("title",      myName);
                data.put("body",       enc ? com.silu.minigram.e2ee.E2ee.GENERIC_PUSH : preview);
                data.put("image",      "");
                data.put("type",       "chat");
                data.put("extra",      target.targetId);
                data.put("sender_uid", myUid);
                data.put("to_uid",     target.peerUid);
                FCMNotificationSender.sendNotification(data, "", target.peerFcmToken,
                    new FCMNotificationSender.OnResponse() {
                        @Override public void onSuccess(String r) {}
                        @Override public void onError(String e) {}
                    });
            }
        }
    }

    /**
     * Forward to a channel I own — target list is already restricted to owned
     * channels (see loadTargets()), so no extra ownership check is needed
     * here. Mirrors ChannelActivity._sendChannelNotification for the push.
     */
    private void _forwardToChannel(Row target) {
        DatabaseReference chRef = database.getReference("channel_posts").child(target.targetId);
        for (HashMap<String, Object> om : messagesToForward) {
            HashMap<String, Object> copy = ForwardHelper.buildForwardedCopy(om, myUid, true);
            copy.put("channel_id", target.targetId);
            String nId = chRef.push().getKey();
            if (nId == null) continue;
            chRef.child(nId).setValue(copy);

            ContentTrackingHelper.trackSent(myUid,
                (String) copy.get("type"),
                (String) copy.get("text"),
                (String) copy.get("image_url"),
                (String) copy.get("voice_url"),
                "channel", target.targetId, nId);
        }

        String preview = ForwardHelper.previewFor(messagesToForward);
        HashMap<String, Object> data = new HashMap<>();
        data.put("title",        target.name);
        data.put("body",         preview);
        data.put("image",        "");
        data.put("type",         "channel");
        data.put("extra",        target.targetId);
        data.put("channel_name", target.name);
        data.put("sender_uid",   myUid);
        FCMNotificationSender.sendNotification(data, FcmTokenManager.topicFor(target.targetId), "",
            new FCMNotificationSender.OnResponse() {
                @Override public void onSuccess(String r) {}
                @Override public void onError(String e) {}
            });
    }
}
