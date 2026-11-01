package com.silu.minigram;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Requirement 2 — "who can post" (full admin system, as requested rather
 * than a simple owner-only/everyone toggle).
 *
 * Owner-only screen (only reachable from ChannelSettingsActivity, which is
 * itself already gated to the owner — see ChannelActivity._checkOwnership).
 * Lists every subscriber; each row has a switch that grants/revokes posting
 * rights by writing directly to channel_admins/{channelId}/{uid}. An admin
 * can then post in the channel exactly like the owner can (see
 * ChannelActivity._canPost() and its three call sites) — nothing else
 * (settings, pin, delete-moderation, forward/reply-restriction toggles)
 * changes for them; this is posting rights only, not a broader moderation
 * role, matching exactly what was asked for.
 *
 * SECURITY NOTE: same as restrict_forwarding/allow_replies elsewhere in
 * this project — the client-side gates (compose bar visibility,
 * _canPost()) are UX, not the real boundary. True enforcement needs a
 * Firebase rule on channel_posts checking owner_uid OR channel_admins
 * server-side:
 *
 *   "channel_posts": {
 *     "$channelId": {
 *       ".write": "auth != null && (root.child('channels').child($channelId).child('owner_uid').val() === auth.uid || root.child('channel_admins').child($channelId).child(auth.uid).exists())",
 *       "$postId": {
 *         ".write": "auth != null && (root.child('channels').child($channelId).child('owner_uid').val() === auth.uid || root.child('channel_admins').child($channelId).child(auth.uid).exists())"
 *       }
 *     }
 *   }
 *
 * If an earlier owner-only-write rule was already applied for this
 * project, it MUST be updated to this version — otherwise a newly granted
 * admin's posts will silently fail server-side even though the app lets
 * them try.
 */
public class ChannelAdminsActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID = "channel_id";

    private String channelId;
    private String myUid;
    private DatabaseReference channelAdminsRef;

    private ListView lv;
    private EditText editSearch;
    private TextView txtEmpty;

    private final List<HashMap<String, Object>> allSubscribers = new ArrayList<>();
    private final List<HashMap<String, Object>> shownSubscribers = new ArrayList<>();
    private final Set<String> adminUids = new HashSet<>();
    private RowAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_admins);
        EdgeToEdgeHelper.setup(this);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));

        channelId = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        if (channelId == null) { finish(); return; }

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        channelAdminsRef = FirebaseDatabase.getInstance().getReference("channel_admins").child(channelId);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        lv         = findViewById(R.id.lv_subscribers);
        editSearch = findViewById(R.id.edit_search);
        txtEmpty   = findViewById(R.id.txt_empty);

        adapter = new RowAdapter();
        lv.setAdapter(adapter);

        editSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { _applyFilter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        _loadAdminsThenSubscribers();
    }

    private void _loadAdminsThenSubscribers() {
        channelAdminsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot adminsSnap) {
                for (DataSnapshot a : adminsSnap.getChildren()) {
                    if (Boolean.TRUE.equals(a.getValue(Boolean.class))) adminUids.add(a.getKey());
                }
                _loadSubscribers();
            }
            @Override public void onCancelled(DatabaseError e) { _loadSubscribers(); }
        });
    }

    private void _loadSubscribers() {
        FirebaseDatabase.getInstance().getReference("channel_subscribers").child(channelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot subsSnap) {
                    List<String> uids = new ArrayList<>();
                    for (DataSnapshot s : subsSnap.getChildren()) uids.add(s.getKey());

                    if (uids.isEmpty()) {
                        if (txtEmpty != null) txtEmpty.setVisibility(View.VISIBLE);
                        return;
                    }

                    final int[] remaining = {uids.size()};
                    for (String uid : uids) {
                        // Owner isn't a "subscriber" row here — they already
                        // have full rights, listing them as a toggleable
                        // admin would be misleading (toggling it off
                        // wouldn't actually remove their access).
                        if (uid.equals(myUid)) {
                            if (--remaining[0] == 0) _onAllLoaded();
                            continue;
                        }
                        FirebaseDatabase.getInstance().getReference("users").child(uid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(DataSnapshot userSnap) {
                                    HashMap<String, Object> row = new HashMap<>();
                                    row.put("uid",    uid);
                                    row.put("name",   userSnap.child("fullname").getValue(String.class));
                                    row.put("handle", userSnap.child("handle").getValue(String.class));
                                    row.put("photo",  userSnap.child("photo_url").getValue(String.class));
                                    allSubscribers.add(row);
                                    if (--remaining[0] == 0) _onAllLoaded();
                                }
                                @Override public void onCancelled(DatabaseError e) {
                                    if (--remaining[0] == 0) _onAllLoaded();
                                }
                            });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {
                    if (txtEmpty != null) {
                        txtEmpty.setText("Couldn't load subscribers");
                        txtEmpty.setVisibility(View.VISIBLE);
                    }
                }
            });
    }

    private void _onAllLoaded() {
        if (allSubscribers.isEmpty() && txtEmpty != null) {
            txtEmpty.setText("No other subscribers yet");
            txtEmpty.setVisibility(View.VISIBLE);
        }
        _applyFilter(editSearch != null ? editSearch.getText().toString() : "");
    }

    private void _applyFilter(String query) {
        shownSubscribers.clear();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        for (HashMap<String, Object> row : allSubscribers) {
            String name   = String.valueOf(row.get("name"));
            String handle = String.valueOf(row.get("handle"));
            if (q.isEmpty()
                || name.toLowerCase(Locale.getDefault()).contains(q)
                || handle.toLowerCase(Locale.getDefault()).contains(q)) {
                shownSubscribers.add(row);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (txtEmpty != null && !allSubscribers.isEmpty()) {
            txtEmpty.setVisibility(shownSubscribers.isEmpty() ? View.VISIBLE : View.GONE);
            txtEmpty.setText("No matches");
        }
    }

    private void _toggleAdmin(String uid, boolean makeAdmin, Switch sw) {
        if (makeAdmin) {
            channelAdminsRef.child(uid).setValue(true).addOnCompleteListener(t -> {
                if (t.isSuccessful()) {
                    adminUids.add(uid);
                    Toast.makeText(this, "Given posting rights", Toast.LENGTH_SHORT).show();
                } else {
                    sw.setChecked(false);
                    Toast.makeText(this, "Failed — try again", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            channelAdminsRef.child(uid).removeValue().addOnCompleteListener(t -> {
                if (t.isSuccessful()) {
                    adminUids.remove(uid);
                    Toast.makeText(this, "Posting rights removed", Toast.LENGTH_SHORT).show();
                } else {
                    sw.setChecked(true);
                    Toast.makeText(this, "Failed — try again", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return shownSubscribers.size(); }
        @Override public Object getItem(int i) { return shownSubscribers.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(ChannelAdminsActivity.this)
                    .inflate(R.layout.item_channel_admin, parent, false);
            }
            HashMap<String, Object> row = shownSubscribers.get(position);
            String uid    = String.valueOf(row.get("uid"));
            String name   = row.get("name")   != null ? (String) row.get("name")   : "Unknown";
            String handle = row.get("handle") != null ? (String) row.get("handle") : "";
            String photo  = (String) row.get("photo");

            ImageView img = v.findViewById(R.id.img_avatar);
            TextView  txtName   = v.findViewById(R.id.txt_name);
            TextView  txtHandle = v.findViewById(R.id.txt_handle);
            Switch    sw        = v.findViewById(R.id.sw_admin);

            txtName.setText(name);
            txtHandle.setText(!handle.isEmpty() ? "@" + handle : "");

            Glide.with(ChannelAdminsActivity.this)
                .load(photo)
                .apply(new RequestOptions()
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person))
                .circleCrop()
                .into(img);

            // Clear any listener from a recycled row before setting state,
            // so setChecked() below doesn't fire a spurious write.
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(adminUids.contains(uid));
            sw.setOnCheckedChangeListener((btn, checked) -> _toggleAdmin(uid, checked, sw));

            return v;
        }
    }
}
