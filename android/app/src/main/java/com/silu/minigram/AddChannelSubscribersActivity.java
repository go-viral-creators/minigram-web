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
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

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
 * "Add Subscribers" — owner/admin picks people to directly add to the
 * channel, skipping the normal join-yourself flow. The candidate pool is
 * the viewer's own existing chats (people they've actually talked to in
 * Minigram) — there's no general "browse all app users" directory in this
 * app, and building one would be a much bigger, separate privacy/scale
 * concern, so this mirrors how Telegram's own "Add Members" pulls from
 * your contacts rather than the whole platform.
 */
public class AddChannelSubscribersActivity extends AppCompatActivity {

    private String channelId;
    private String myUid;

    private ListView listView;
    private EditText editSearch;
    private TextView txtEmpty, btnDone;

    private final List<HashMap<String, Object>> allCandidates = new ArrayList<>();
    private final List<HashMap<String, Object>> shown = new ArrayList<>();
    private final Set<String> selectedUids = new HashSet<>();
    private CandidateAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_channel_subscribers);
        EdgeToEdgeHelper.setup(this);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));

        channelId = getIntent().getStringExtra("channel_id");
        if (channelId == null) { finish(); return; }
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        listView   = findViewById(R.id.list_candidates);
        editSearch = findViewById(R.id.edit_search);
        txtEmpty   = findViewById(R.id.txt_empty);
        btnDone    = findViewById(R.id.btn_done);

        adapter = new CandidateAdapter();
        listView.setAdapter(adapter);

        editSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { _applyFilter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnDone.setOnClickListener(v -> _addSelected());
        _updateDoneLabel();
        _load();
    }

    private void _load() {
        if (myUid == null) return;
        FirebaseDatabase.getInstance().getReference("channel_subscribers").child(channelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot alreadySnap) {
                    Set<String> already = new HashSet<>();
                    for (DataSnapshot s : alreadySnap.getChildren()) already.add(s.getKey());
                    _loadChats(already);
                }
                @Override public void onCancelled(DatabaseError e) { _loadChats(new HashSet<>()); }
            });
    }

    private void _loadChats(Set<String> alreadySubscribed) {
        FirebaseDatabase.getInstance().getReference("user_chats").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot chatsSnap) {
                    List<String> peerUids = new ArrayList<>();
                    for (DataSnapshot c : chatsSnap.getChildren()) {
                        String peerUid = c.child("peer_uid").getValue(String.class);
                        if (peerUid != null && !alreadySubscribed.contains(peerUid) && !peerUid.equals(myUid)) {
                            peerUids.add(peerUid);
                        }
                    }
                    if (peerUids.isEmpty()) { _onAllLoaded(); return; }

                    final int[] remaining = {peerUids.size()};
                    for (String uid : peerUids) {
                        FirebaseDatabase.getInstance().getReference("users").child(uid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(DataSnapshot userSnap) {
                                    HashMap<String, Object> row = new HashMap<>();
                                    row.put("uid",    uid);
                                    row.put("name",   userSnap.child("fullname").getValue(String.class));
                                    row.put("handle", userSnap.child("handle").getValue(String.class));
                                    row.put("photo",  userSnap.child("photo_url").getValue(String.class));
                                    allCandidates.add(row);
                                    if (--remaining[0] == 0) _onAllLoaded();
                                }
                                @Override public void onCancelled(DatabaseError e) {
                                    if (--remaining[0] == 0) _onAllLoaded();
                                }
                            });
                    }
                }
                @Override public void onCancelled(DatabaseError e) { _onAllLoaded(); }
            });
    }

    private void _onAllLoaded() {
        _applyFilter(editSearch.getText() != null ? editSearch.getText().toString() : "");
    }

    private void _applyFilter(String query) {
        shown.clear();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        for (HashMap<String, Object> row : allCandidates) {
            String name   = String.valueOf(row.get("name"));
            String handle = String.valueOf(row.get("handle"));
            if (q.isEmpty()
                || name.toLowerCase(Locale.getDefault()).contains(q)
                || handle.toLowerCase(Locale.getDefault()).contains(q)) {
                shown.add(row);
            }
        }
        adapter.notifyDataSetChanged();
        txtEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        txtEmpty.setText(allCandidates.isEmpty()
            ? "Everyone you've chatted with is already subscribed"
            : "No matches");
    }

    private void _updateDoneLabel() {
        int n = selectedUids.size();
        btnDone.setText(n == 0 ? "Add" : "Add (" + n + ")");
        btnDone.setEnabled(n > 0);
        btnDone.setAlpha(n > 0 ? 1f : 0.5f);
    }

    private void _addSelected() {
        if (selectedUids.isEmpty()) return;
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        for (String uid : selectedUids) {
            root.child("channel_subscribers").child(channelId).child(uid).setValue(true);
            root.child("user_channels").child(uid).child(channelId).setValue(true);
        }
        root.child("channels").child(channelId).child("subscriber_count")
            .setValue(ServerValue.increment(selectedUids.size()));
        Toast.makeText(this, "Added " + selectedUids.size() + " subscriber(s)", Toast.LENGTH_SHORT).show();
        finish();
    }

    private class CandidateAdapter extends android.widget.BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(AddChannelSubscribersActivity.this)
                    .inflate(R.layout.item_add_subscriber_candidate, parent, false);
            }
            HashMap<String, Object> row = shown.get(position);
            String uid    = String.valueOf(row.get("uid"));
            String name   = row.get("name")   != null ? (String) row.get("name")   : "Unknown";
            String handle = row.get("handle") != null ? (String) row.get("handle") : "";
            String photo  = (String) row.get("photo");

            ImageView img = v.findViewById(R.id.img_avatar);
            TextView  txtName   = v.findViewById(R.id.txt_name);
            TextView  txtHandle = v.findViewById(R.id.txt_handle);
            TextView  check     = v.findViewById(R.id.txt_check);

            txtName.setText(name);
            txtHandle.setText(!handle.isEmpty() ? "@" + handle : "");
            check.setVisibility(selectedUids.contains(uid) ? View.VISIBLE : View.GONE);

            Glide.with(AddChannelSubscribersActivity.this)
                .load(photo).placeholder(R.drawable.ic_person).error(R.drawable.ic_person)
                .circleCrop().into(img);

            v.setOnClickListener(view -> {
                if (selectedUids.contains(uid)) selectedUids.remove(uid); else selectedUids.add(uid);
                notifyDataSetChanged();
                _updateDoneLabel();
            });

            return v;
        }
    }
}
