package com.silu.minigram;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Requirement 4 follow-up — dedicated Subscribers screen, Telegram-style,
 * replacing ChannelSettingsActivity's old plain "N Subscriber(s)" AlertDialog
 * (just names in a list, a Close button, nothing else).
 *
 * Owner/admin only — this Activity is never reached by a regular
 * subscriber; the entry points into it (ChannelProfileActivity's
 * "Subscribers" row, ChannelSettingsActivity's row) are themselves gated
 * to isOwner || isChannelAdmin, and this Activity re-checks that itself on
 * open too (defense in depth, same pattern as the rest of this project's
 * permission-sensitive screens) rather than trusting the caller.
 *
 * Shows every subscriber with a role badge (Owner / Admin / nothing for a
 * plain subscriber), lets you tap through to a direct chat with them, and
 * — only for the owner/admin viewing — a per-row menu:
 *   • Promote to Admin / Remove Admin (toggles channel_admins)
 *   • Remove from Channel
 * plus an "Add Subscribers" entry at the top (opens
 * AddChannelSubscribersActivity).
 */
public class ChannelSubscribersActivity extends AppCompatActivity {

    private String channelId;
    private String myUid;
    private String ownerUid;
    private boolean viewerIsOwner = false;
    private boolean viewerIsAdmin = false;
    private final Set<String> adminUids = new HashSet<>();

    private RecyclerView recyclerView;
    private EditText editSearch;
    private TextView txtCount, txtEmpty;

    private final List<HashMap<String, Object>> allSubs = new ArrayList<>();
    private final List<HashMap<String, Object>> shownSubs = new ArrayList<>();
    private SubscriberAdapter adapter;

    // BUG FIX — subscribers were showing up duplicated (the whole list
    // appearing twice). Root cause: onCreate() kicks off _reload() via the
    // async owner/admin permission check, and onResume() ALSO called
    // _reload() unconditionally whenever the adapter was still empty —
    // which it always is on first launch, since onResume() always follows
    // onCreate() in the activity lifecycle. That fired two overlapping
    // _loadSubscribers() calls; each does its own allSubs.clear(), but their
    // async per-user fetches interleave, so the slower call's results often
    // land AFTER the faster call has already finished and displayed —
    // appending a second full copy instead of the clear() catching them.
    // hasLoadedOnce stops onResume from re-triggering the load that's
    // already in flight from onCreate; loadGeneration is a second, more
    // general guard — each _reload() bumps it, and any async callback whose
    // captured generation no longer matches the current one (a newer load
    // has since started) discards its results instead of appending them.
    private boolean hasLoadedOnce = false;
    private int loadGeneration = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_subscribers);
        EdgeToEdgeHelper.setup(this);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));

        channelId = getIntent().getStringExtra("channel_id");
        if (channelId == null) { finish(); return; }
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        recyclerView = findViewById(R.id.recycler_subscribers);
        editSearch   = findViewById(R.id.edit_search);
        txtCount     = findViewById(R.id.txt_sub_count);
        txtEmpty     = findViewById(R.id.txt_empty);
        View rowAddSubscribers = findViewById(R.id.row_add_subscribers);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SubscriberAdapter();
        recyclerView.setAdapter(adapter);

        editSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { _applyFilter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        rowAddSubscribers.setOnClickListener(v -> {
            Intent i = new Intent(this, AddChannelSubscribersActivity.class);
            i.putExtra("channel_id", channelId);
            startActivity(i);
        });

        _checkPermissionThenLoad();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // hasLoadedOnce guards against the double-load race described
        // above — onCreate's own permission-check chain is either already
        // running or already done by the time this first fires, so
        // reloading here too would only race it. Genuine returns to this
        // screen (e.g. back from "Add Subscribers") still refresh — that's
        // every onResume AFTER the first.
        if (hasLoadedOnce) _reload();
    }

    private void _checkPermissionThenLoad() {
        FirebaseDatabase.getInstance().getReference("channels").child(channelId).child("owner_uid")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    ownerUid = snap.getValue(String.class);
                    viewerIsOwner = myUid != null && myUid.equals(ownerUid);
                    if (viewerIsOwner) { _reload(); return; }
                    FirebaseDatabase.getInstance().getReference("channel_admins")
                        .child(channelId).child(myUid == null ? "_" : myUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(DataSnapshot adminSnap) {
                                viewerIsAdmin = Boolean.TRUE.equals(adminSnap.getValue(Boolean.class));
                                if (!viewerIsAdmin) {
                                    // Defense in depth — a regular subscriber
                                    // should never actually reach this screen
                                    // (entry points are gated), but if they
                                    // somehow do, don't show it.
                                    Toast.makeText(ChannelSubscribersActivity.this,
                                        "You don't have permission to view this", Toast.LENGTH_SHORT).show();
                                    finish();
                                    return;
                                }
                                _reload();
                            }
                            @Override public void onCancelled(DatabaseError e) { finish(); }
                        });
                }
                @Override public void onCancelled(DatabaseError e) { finish(); }
            });
    }

    private void _reload() {
        hasLoadedOnce = true;
        final int myGeneration = ++loadGeneration;
        findViewById(R.id.row_add_subscribers).setVisibility(View.VISIBLE);
        adminUids.clear();
        FirebaseDatabase.getInstance().getReference("channel_admins").child(channelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot adminsSnap) {
                    if (myGeneration != loadGeneration) return; // a newer _reload() has since started
                    for (DataSnapshot a : adminsSnap.getChildren())
                        if (Boolean.TRUE.equals(a.getValue(Boolean.class))) adminUids.add(a.getKey());
                    _loadSubscribers(myGeneration);
                }
                @Override public void onCancelled(DatabaseError e) {
                    if (myGeneration != loadGeneration) return;
                    _loadSubscribers(myGeneration);
                }
            });
    }

    private void _loadSubscribers(int myGeneration) {
        FirebaseDatabase.getInstance().getReference("channel_subscribers").child(channelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot subsSnap) {
                    if (myGeneration != loadGeneration) return;
                    allSubs.clear();
                    List<String> uids = new ArrayList<>();
                    for (DataSnapshot s : subsSnap.getChildren()) uids.add(s.getKey());

                    if (uids.isEmpty()) { _onAllLoaded(myGeneration); return; }

                    final int[] remaining = {uids.size()};
                    for (String uid : uids) {
                        FirebaseDatabase.getInstance().getReference("users").child(uid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(DataSnapshot userSnap) {
                                    if (myGeneration != loadGeneration) return;
                                    HashMap<String, Object> row = new HashMap<>();
                                    row.put("uid",    uid);
                                    row.put("name",   userSnap.child("fullname").getValue(String.class));
                                    row.put("handle", userSnap.child("handle").getValue(String.class));
                                    row.put("photo",  userSnap.child("photo_url").getValue(String.class));
                                    allSubs.add(row);
                                    if (--remaining[0] == 0) _onAllLoaded(myGeneration);
                                }
                                @Override public void onCancelled(DatabaseError e) {
                                    if (myGeneration != loadGeneration) return;
                                    if (--remaining[0] == 0) _onAllLoaded(myGeneration);
                                }
                            });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {
                    if (myGeneration != loadGeneration) return;
                    _onAllLoaded(myGeneration);
                }
            });
    }

    private void _onAllLoaded(int myGeneration) {
        if (myGeneration != loadGeneration) return;
        txtCount.setText(_formatCount(allSubs.size()) + " subscribers");
        _applyFilter(editSearch.getText() != null ? editSearch.getText().toString() : "");
    }

    private void _applyFilter(String query) {
        shownSubs.clear();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        for (HashMap<String, Object> row : allSubs) {
            String name   = String.valueOf(row.get("name"));
            String handle = String.valueOf(row.get("handle"));
            if (q.isEmpty()
                || name.toLowerCase(Locale.getDefault()).contains(q)
                || handle.toLowerCase(Locale.getDefault()).contains(q)) {
                shownSubs.add(row);
            }
        }
        adapter.notifyDataSetChanged();
        txtEmpty.setVisibility(shownSubs.isEmpty() ? View.VISIBLE : View.GONE);
        txtEmpty.setText(allSubs.isEmpty() ? "No subscribers yet" : "No matches");
    }

    private String _formatCount(long n) {
        if (n >= 1_000_000) return String.format(Locale.getDefault(), "%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format(Locale.getDefault(), "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private void _openChat(String uid, String name, String photo) {
        if (uid.equals(myUid)) return; // no self-chat from your own row
        // Without chat_id, ChatActivity ran getReference("chats").child(null)
        // and crashed. Same deterministic id every other launcher builds —
        // sorted so both participants derive the identical chat.
        String chatId = myUid.compareTo(uid) < 0 ? myUid + "_" + uid : uid + "_" + myUid;
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("chat_id", chatId);
        i.putExtra("peer_uid", uid);
        i.putExtra("peer_name", name);
        i.putExtra("peer_photo", photo);
        startActivity(i);
    }

    private void _showRowMenu(View anchor, String uid, String name) {
        if (uid.equals(myUid)) return; // can't act on your own row
        boolean targetIsAdmin = adminUids.contains(uid);

        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, targetIsAdmin ? "Remove Admin" : "Promote to Admin");
        menu.getMenu().add(0, 2, 1, "Remove from Channel");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                _toggleAdmin(uid, !targetIsAdmin);
            } else if (item.getItemId() == 2) {
                _confirmRemove(uid, name);
            }
            return true;
        });
        menu.show();
    }

    private void _toggleAdmin(String uid, boolean makeAdmin) {
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("channel_admins").child(channelId).child(uid);
        (makeAdmin ? ref.setValue(true) : ref.removeValue()).addOnCompleteListener(t -> {
            if (t.isSuccessful()) {
                if (makeAdmin) adminUids.add(uid); else adminUids.remove(uid);
                adapter.notifyDataSetChanged();
                Toast.makeText(this, makeAdmin ? "Promoted to admin" : "Admin rights removed", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Failed — try again", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void _confirmRemove(String uid, String name) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Remove " + (name != null ? name : "this subscriber") + "?")
            .setMessage("They'll stop receiving posts from this channel.")
            .setPositiveButton("Remove", (d, w) -> _removeSubscriber(uid))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void _removeSubscriber(String uid) {
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        root.child("channel_subscribers").child(channelId).child(uid).removeValue();
        root.child("user_channels").child(uid).child(channelId).removeValue();
        root.child("channel_admins").child(channelId).child(uid).removeValue(); // demote too, if they were admin
        root.child("channels").child(channelId).child("subscriber_count").setValue(ServerValue.increment(-1));
        adminUids.remove(uid);
        for (int i = 0; i < allSubs.size(); i++) {
            if (uid.equals(allSubs.get(i).get("uid"))) { allSubs.remove(i); break; }
        }
        _applyFilter(editSearch.getText() != null ? editSearch.getText().toString() : "");
        txtCount.setText(_formatCount(allSubs.size()) + " subscribers");
        Toast.makeText(this, "Removed", Toast.LENGTH_SHORT).show();
    }

    private class SubscriberAdapter extends RecyclerView.Adapter<SubscriberAdapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            ImageView img; TextView name, handle, role; View btnMore;
            VH(View v) {
                super(v);
                img     = v.findViewById(R.id.img_avatar);
                name    = v.findViewById(R.id.txt_name);
                handle  = v.findViewById(R.id.txt_handle);
                role    = v.findViewById(R.id.txt_role);
                btnMore = v.findViewById(R.id.btn_more);
            }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel_subscriber, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int position) {
            HashMap<String, Object> row = shownSubs.get(position);
            String uid    = String.valueOf(row.get("uid"));
            String name   = row.get("name")   != null ? (String) row.get("name")   : "Unknown";
            String handle = row.get("handle") != null ? (String) row.get("handle") : "";
            String photo  = (String) row.get("photo");
            boolean isSelf = uid.equals(myUid);

            h.name.setText(isSelf ? name + " (You)" : name);
            h.handle.setText(!handle.isEmpty() ? "@" + handle : "");

            if (adminUids.contains(uid)) {
                h.role.setText("Admin");
                h.role.setVisibility(View.VISIBLE);
            } else {
                h.role.setVisibility(View.GONE);
            }

            Glide.with(ChannelSubscribersActivity.this)
                .load(photo)
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .circleCrop()
                .into(h.img);

            h.itemView.setOnClickListener(v -> _openChat(uid, name, photo));
            // Owner/admin viewer only, and never on your own row.
            h.btnMore.setVisibility(isSelf ? View.GONE : View.VISIBLE);
            h.btnMore.setOnClickListener(v -> _showRowMenu(v, uid, name));
        }

        @Override public int getItemCount() { return shownSubs.size(); }
    }
}
