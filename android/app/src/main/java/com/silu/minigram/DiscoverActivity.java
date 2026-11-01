package com.silu.minigram;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.google.android.material.chip.Chip;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.silu.minigram.databinding.ActivityDiscoverBinding;
import com.silu.minigram.databinding.ItemDiscoverChannelBinding;
import com.silu.minigram.databinding.ItemDiscoverPersonBinding;
import com.silu.minigram.discover.InterestStore;
import com.silu.minigram.discover.InterestTags;
import com.silu.minigram.discover.Recommender;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interest-based Discover (design 06). Ranking is computed here, on the phone. The only network reads are
 * generic ones that do not depend on the interests (the most popular channels, the channels I already follow).
 */
public class DiscoverActivity extends AppCompatActivity {
    private ActivityDiscoverBinding b;
    private String myUid;
    private Set<String> interests;
    private final Set<String> subscribed = new HashSet<>();
    private final List<Recommender.Candidate> pool = new ArrayList<>();
    private boolean loadedSubs, loadedPool;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        b = ActivityDiscoverBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        EdgeToEdgeHelper.applyInsets(b.main);
        myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) { finish(); return; }
        InkNav.bind(this, b.bottomNav.getRoot(), InkNav.DISCOVER);

        interests = InterestStore.get(this);
        if (!InterestStore.seen(this) && interests.isEmpty()) { InterestStore.markSeen(this); editInterests(); }

        b.btnUpdates.setOnClickListener(v -> startActivity(new Intent(this, ChatlistActivity.class)
            .putExtra(InkNav.EXTRA_TAB, 2).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        b.cardSearch.setOnClickListener(v -> startActivity(new Intent(this, UserSearchActivity.class)
            .putExtra(UserSearchActivity.EXTRA_MODE, UserSearchActivity.MODE_CHANNEL)));
        b.btnEditInterests.setOnClickListener(v -> editInterests());
        renderChips();
        loadSubscribed();
        loadPool();
    }

    private void renderChips() {
        b.chipsInterests.removeAllViews();
        List<String> shown = new ArrayList<>(interests);
        for (String t : InterestTags.all()) if (shown.size() < 10 && !shown.contains(t)) shown.add(t);
        for (String t : shown) {
            Chip c = (Chip) LayoutInflater.from(this).inflate(R.layout.chip_interest, b.chipsInterests, false);
            c.setText(t);
            c.setChecked(interests.contains(t));
            c.setOnCheckedChangeListener((v, on) -> {
                if (on) interests.add(t); else interests.remove(t);
                InterestStore.set(this, interests);
                renderReco();
            });
            b.chipsInterests.addView(c);
        }
    }

    private void editInterests() {
        final List<String> all = InterestTags.all();
        final boolean[] on = new boolean[all.size()];
        for (int i = 0; i < on.length; i++) on[i] = interests.contains(all.get(i));
        new AlertDialog.Builder(this).setTitle("Your interests")
            .setMultiChoiceItems(all.toArray(new String[0]), on, (d, which, checked) -> on[which] = checked)
            .setPositiveButton("Save", (d, w) -> {
                interests.clear();
                for (int i = 0; i < on.length; i++) if (on[i]) interests.add(all.get(i));
                InterestStore.set(this, interests);
                renderChips(); renderReco();
            }).setNegativeButton("Cancel", null).show();
    }

    private void loadSubscribed() {
        FirebaseDatabase.getInstance().getReference("user_channels").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot c : snap.getChildren()) subscribed.add(c.getKey());
                    loadedSubs = true; renderReco();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { loadedSubs = true; renderReco(); }
            });
    }

    /** Generic fetch (most popular channels): does not depend on the interests. */
    private void loadPool() {
        FirebaseDatabase.getInstance().getReference("channels").orderByChild("subscriber_count").limitToLast(120)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot c : snap.getChildren()) {
                        if ("private".equals(c.child("privacy").getValue(String.class))) continue;
                        Recommender.Candidate k = new Recommender.Candidate();
                        k.id = c.getKey();
                        k.name = c.child("name").getValue(String.class);
                        k.description = c.child("description").getValue(String.class);
                        k.photoUrl = c.child("photo_url").getValue(String.class);
                        Long n = c.child("subscriber_count").getValue(Long.class);
                        k.subscribers = n == null ? 0 : n;
                        k.verified = Boolean.TRUE.equals(c.child("is_verified").getValue(Boolean.class));
                        for (DataSnapshot t : c.child("tags").getChildren()) k.tags.add(t.getKey());
                        if (k.name != null) pool.add(k);
                    }
                    loadedPool = true; renderReco();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { loadedPool = true; renderReco(); }
            });
    }

    private void renderReco() {
        if (!loadedSubs || !loadedPool || isFinishing() || isDestroyed()) return;
        b.listReco.removeAllViews();
        List<Recommender.Candidate> ranked = Recommender.rank(interests, pool, subscribed, 8);
        b.txtRecoEmpty.setVisibility(ranked.isEmpty() ? View.VISIBLE : View.GONE);
        b.txtRecoEmpty.setText("No channels to suggest right now.");
        for (Recommender.Candidate c : ranked) {
            ItemDiscoverChannelBinding r = ItemDiscoverChannelBinding.inflate(LayoutInflater.from(this), b.listReco, false);
            r.txtChName.setText(c.name);
            r.txtChMeta.setText(Recommender.formatCount(c.subscribers) + " subscribers" + (c.matchedTag.isEmpty() ? "" : " · " + c.matchedTag));
            AvatarHelper.load(this, r.imgCh, c.photoUrl, c.name);
            r.getRoot().setContentDescription(c.name + ", " + r.txtChMeta.getText());
            r.getRoot().setOnClickListener(v -> startActivity(new Intent(this, ChannelProfileActivity.class)
                .putExtra("channel_id", c.id)));
            r.btnJoin.setContentDescription("Join " + c.name);
            r.btnJoin.setOnClickListener(v -> {
                join(c.id);
                r.btnJoin.setText("Joined ✓");
                r.btnJoin.setEnabled(false);
                subscribed.add(c.id);
            });
            b.listReco.addView(r.getRoot());
        }
        loadPeople(ranked);
    }

    private void join(String channelId) {
        FirebaseDatabase db = FirebaseDatabase.getInstance();
        db.getReference("channel_subscribers").child(channelId).child(myUid).setValue(true);
        db.getReference("user_channels").child(myUid).child(channelId).setValue(true);
        db.getReference("channels").child(channelId).child("subscriber_count").setValue(ServerValue.increment(1));
        FcmTokenManager.subscribeChannel(channelId);
        Toast.makeText(this, "Joined ✓", Toast.LENGTH_SHORT).show();
    }

    private boolean peopleLoaded;
    /** "Similar people" = people who follow the same channels as me. Uses only public subscription lists. */
    private void loadPeople(List<Recommender.Candidate> ranked) {
        if (peopleLoaded) return;
        peopleLoaded = true;
        final List<String> source = new ArrayList<>();
        for (String id : subscribed) { if (source.size() < 4) source.add(id); }
        for (Recommender.Candidate c : ranked) { if (source.size() < 4) source.add(c.id); }
        if (source.isEmpty()) return;
        final Map<String, Integer> shared = new LinkedHashMap<>();
        final int[] left = {source.size()};
        for (String cid : source) {
            FirebaseDatabase.getInstance().getReference("channel_subscribers").child(cid).limitToFirst(40)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        for (DataSnapshot u : snap.getChildren())
                            if (!myUid.equals(u.getKey())) shared.merge(u.getKey(), 1, Integer::sum);
                        if (--left[0] == 0) showPeople(shared);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { if (--left[0] == 0) showPeople(shared); }
                });
        }
    }

    private void showPeople(Map<String, Integer> shared) {
        if (isFinishing() || isDestroyed() || shared.isEmpty()) return;
        List<Map.Entry<String, Integer>> top = new ArrayList<>(shared.entrySet());
        java.util.Collections.sort(top, (x, y) -> y.getValue() - x.getValue());
        if (top.size() > 8) top = top.subList(0, 8);
        for (Map.Entry<String, Integer> e : top) {
            final String uid = e.getKey(); final int n = e.getValue();
            FirebaseDatabase.getInstance().getReference("users").child(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot u) {
                        if (isFinishing() || isDestroyed() || !u.exists()) return;
                        String name = u.child("fullname").getValue(String.class);
                        String photo = u.child("photo_url").getValue(String.class);
                        ItemDiscoverPersonBinding p = ItemDiscoverPersonBinding.inflate(LayoutInflater.from(DiscoverActivity.this), b.listPeople, false);
                        p.txtPersonName.setText(name == null ? "User" : name.split(" ")[0]);
                        p.txtPersonShared.setText(n + " shared");
                        AvatarHelper.load(DiscoverActivity.this, p.imgPerson, photo, name);
                        p.getRoot().setContentDescription((name == null ? "User" : name) + ", " + n + " shared channels");
                        p.getRoot().setOnClickListener(v -> UserProfileSheet.show(uid, "")
                            .show(getSupportFragmentManager(), "user_profile"));
                        b.listPeople.addView(p.getRoot());
                        b.eyebrowPeople.setVisibility(View.VISIBLE);
                        b.scrollPeople.setVisibility(View.VISIBLE);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError er) {}
                });
        }
    }
}
