package com.silu.minigram;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Updates tab — a genuine androidx Fragment (per the requirement that this
 * be Fragment-based, not a new Activity), hosted inside ChatlistActivity's
 * frame_updates_container and shown/hidden the same way the Chats/Channels
 * content already is.
 *
 * Shows official Minigram announcements from the updates_feed/ node,
 * newest first. Read-only for everyone except the admin panel (rules:
 * anyone logged in can read, only the service account can write — same
 * pattern as app_update).
 */
public class UpdatesFragment extends Fragment {

    private static final int PAGE_SIZE = 20;

    private RecyclerView   recyclerUpdates;
    private LinearLayout   layoutEmpty;
    private ProgressBar    progressLoading;
    private UpdatesAdapter adapter;
    private final ArrayList<HashMap<String, Object>> items = new ArrayList<>();

    private DatabaseReference updatesRef;
    private ValueEventListener updatesListener;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_updates, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerUpdates = view.findViewById(R.id.recycler_updates);
        layoutEmpty     = view.findViewById(R.id.layout_updates_empty);
        progressLoading = view.findViewById(R.id.progress_updates);

        recyclerUpdates.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerUpdates.setOverScrollMode(View.OVER_SCROLL_NEVER); // no edge-glow "scroll line" (some OEM skins ignore the XML attribute)
        adapter = new UpdatesAdapter(requireContext(), items);
        recyclerUpdates.setAdapter(adapter);

        _listenUpdates();
    }

    private void _listenUpdates() {
        updatesRef = FirebaseDatabase.getInstance().getReference("updates_feed");
        Query q = updatesRef.orderByChild("timestamp").limitToLast(PAGE_SIZE);
        updatesListener = q.addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded()) return;
                items.clear();
                for (DataSnapshot child : snap.getChildren()) {
                    HashMap<String, Object> item = new HashMap<>();
                    item.put("id",        child.getKey());
                    item.put("title",     child.child("title").getValue(String.class));
                    item.put("body",      child.child("body").getValue(String.class));
                    item.put("image_url", child.child("image_url").getValue(String.class));
                    item.put("link_url",  child.child("link_url").getValue(String.class));
                    item.put("timestamp", child.child("timestamp").getValue(Long.class));
                    items.add(0, item); // newest first (Firebase returns oldest→newest)
                }
                progressLoading.setVisibility(View.GONE);
                layoutEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.notifyDataSetChanged();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (!isAdded()) return;
                progressLoading.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (updatesRef != null && updatesListener != null) {
            updatesRef.removeEventListener(updatesListener);
        }
    }
}
