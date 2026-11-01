package com.silu.minigram;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class UserSearchActivity extends AppCompatActivity {

    /** Pass this extra from ChatlistActivity to choose mode */
    public static final String EXTRA_MODE    = "search_mode";
    public static final String MODE_USER     = "user";
    public static final String MODE_CHANNEL  = "channel";

    private static final long DEBOUNCE_MS = 350;

    private EditText     etSearch;
    private TextView     btnCancel;
    private ListView     lvResults;
    private TextView     txtEmpty;
    private View         layoutRecent;
    // Recent-searches + popular-channels default view (shown while the
    // query box is empty). Previously this area was a hardcoded
    // "Start typing to search people" label and nothing else.
    private View         layoutHistoryHeader, txtPopularHeader;
    private TextView     txtRecentHint;
    private LinearLayout containerHistory, containerPopular;
    private TextView     btnClearHistory;
    private LinearLayout layoutLoading;

    private Handler  debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable debounceRunnable;

    private String myUid;
    private String mode; // MODE_USER or MODE_CHANNEL

    // ── User mode data ────────────────────────────────────────────────────────
    private List<User>    userResults  = new ArrayList<>();
    private UserAdapter   userAdapter;

    // ── Channel mode data ─────────────────────────────────────────────────────
    private List<Channel>   channelResults = new ArrayList<>();
    private ChannelAdapter  channelAdapter;
    private com.google.android.gms.ads.AdView bannerAd;

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdgeHelper.setup(this);
        setContentView(R.layout.user_search);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));

        myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        mode  = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = MODE_USER; // default fallback

        initializeViews();
        setupListeners();
        _renderDefaultView();

        // Search is now on-demand (server-side prefix query per keystroke,
        // debounced) — no more downloading the entire users/channels table
        // just to open this screen. See performUserSearch/performChannelSearch.

        bannerAd = BannerAdHelper.attachBottomBanner(this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (bannerAd != null) bannerAd.resume();
    }

    @Override protected void onPause() {
        super.onPause();
        if (bannerAd != null) bannerAd.pause();
    }

    // ─────────────────────────────────────────────────────────────────────────
    private void initializeViews() {
        etSearch     = findViewById(R.id.et_search);
        btnCancel    = findViewById(R.id.btn_cancel);
        lvResults    = findViewById(R.id.lv_results);
        txtEmpty     = findViewById(R.id.txt_empty);
        layoutRecent = findViewById(R.id.layout_recent);
        layoutLoading= findViewById(R.id.layout_loading);
        layoutHistoryHeader = findViewById(R.id.layout_history_header);
        containerHistory    = findViewById(R.id.container_history);
        txtPopularHeader    = findViewById(R.id.txt_popular_header);
        containerPopular    = findViewById(R.id.container_popular);
        txtRecentHint       = findViewById(R.id.txt_recent_hint);
        btnClearHistory     = findViewById(R.id.btn_clear_history);

        btnClearHistory.setOnClickListener(v -> _confirmClearHistory());

        // Update title & hint based on mode
        if (MODE_CHANNEL.equals(mode)) {
            etSearch.setHint("Search channels...");

            channelAdapter = new ChannelAdapter();
            lvResults.setAdapter(channelAdapter);
        } else {
            etSearch.setHint("Search people...");

            userAdapter = new UserAdapter();
            lvResults.setAdapter(userAdapter);
        }

        etSearch.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    private void setupListeners() {
        btnCancel.setOnClickListener(v -> finish());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim();
                if (debounceRunnable != null)
                    debounceHandler.removeCallbacks(debounceRunnable);

                if (query.isEmpty()) {
                    clearResults();
                    layoutRecent.setVisibility(View.VISIBLE);
                    layoutLoading.setVisibility(View.GONE);
                    _renderDefaultView();
                    return;
                }

                layoutRecent.setVisibility(View.GONE);
                layoutLoading.setVisibility(View.VISIBLE);

                debounceRunnable = () -> {
                    if (MODE_CHANNEL.equals(mode)) performChannelSearch(query);
                    else                           performUserSearch(query);
                };
                debounceHandler.postDelayed(debounceRunnable, DEBOUNCE_MS);
            }
        });

        lvResults.setOnItemClickListener((parent, view, position, id) -> {
            if (MODE_CHANNEL.equals(mode)) onChannelClicked(position);
            else                           onUserClicked(position);
        });
    }

    // ═════════════════════════════ USER MODE ══════════════════════════════════

    /**
     * Server-side prefix search — replaces the old "download every user,
     * filter in memory" approach, which meant every single search-screen
     * open pulled the ENTIRE users table regardless of whether the person
     * typed anything. That's fine at a few hundred users; at scale it's a
     * multi-MB download on every open and gets worse forever.
     *
     * Runs TWO bounded Firebase queries in parallel — one matching the
     * start of someone's @handle, one matching the start of their name —
     * and merges the (small, capped) results. Firebase only sends back
     * matching rows, not the whole table, PROVIDED the fields are indexed
     * (see database.rules.json ".indexOn": ["handle", "fullname_lower"]).
     *
     * Trade-off vs the old behavior: this only matches from the START of
     * the name/handle (e.g. "ram" finds "Raman" but not "Sharma") — the
     * same prefix-search model Telegram/Instagram/WhatsApp use at scale,
     * rather than old the "contains anywhere" substring search. Accounts
     * that haven't been re-saved since fullname_lower was introduced won't
     * be name-searchable until their profile is next saved (handle-search
     * still works for anyone with a handle, which is everyone with a
     * completed profile) — run the one-time backfill in the admin panel to
     * fix this instantly for all existing accounts.
     */
    private void performUserSearch(String query) {
        layoutLoading.setVisibility(View.VISIBLE);
        txtEmpty.setVisibility(View.GONE);

        final String lq = query.toLowerCase(java.util.Locale.ENGLISH);
        final java.util.LinkedHashMap<String, User> merged = new java.util.LinkedHashMap<>();
        final int[] pending = {2};
        final String forQuery = query;

        Runnable finishSearch = () -> {
            if (!forQuery.equals(etSearch.getText().toString().trim())) return; // stale response, query changed since
            userResults.clear();
            for (User u : merged.values()) { u.searchQuery = forQuery; userResults.add(u); }
            layoutLoading.setVisibility(View.GONE);
            txtEmpty.setVisibility(userResults.isEmpty() ? View.VISIBLE : View.GONE);
            userAdapter.notifyDataSetChanged();
        };

        DatabaseReference usersRef = FirebaseDatabase.getInstance().getReference("users");

        // Query 1 — @handle prefix (handle is always lowercase already)
        usersRef.orderByChild("handle").startAt(lq).endAt(lq + "\uf8ff").limitToFirst(25)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot child : snap.getChildren()) {
                        String uid = child.getKey();
                        if (uid == null || uid.equals(myUid) || merged.containsKey(uid)) continue;
                        merged.put(uid, _userFromSnapshot(child));
                    }
                    if (--pending[0] == 0) finishSearch.run();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { if (--pending[0] == 0) finishSearch.run(); }
            });

        // Query 2 — full name prefix (needs the maintained lowercase index field)
        usersRef.orderByChild("fullname_lower").startAt(lq).endAt(lq + "\uf8ff").limitToFirst(25)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot child : snap.getChildren()) {
                        String uid = child.getKey();
                        if (uid == null || uid.equals(myUid) || merged.containsKey(uid)) continue;
                        merged.put(uid, _userFromSnapshot(child));
                    }
                    if (--pending[0] == 0) finishSearch.run();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { if (--pending[0] == 0) finishSearch.run(); }
            });
    }

    private User _userFromSnapshot(DataSnapshot child) {
        String handle = child.child("handle").getValue(String.class);
        if (handle == null || handle.isEmpty())
            handle = child.child("username").getValue(String.class); // legacy field fallback
        return new User(
            child.getKey(),
            child.child("fullname") .getValue(String.class),
            handle,
            child.child("photo_url").getValue(String.class),
            child.child("bio")      .getValue(String.class)
        );
    }

    private void onUserClicked(int position) {
        User user = userResults.get(position);
        if (user.uid.equals(myUid)) {
            Toast.makeText(this, "That's you!", Toast.LENGTH_SHORT).show();
            return;
        }
        _addToHistory(etSearch.getText().toString());
        String chatId = myUid.compareTo(user.uid) < 0
                ? myUid + "_" + user.uid
                : user.uid + "_" + myUid;

        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("chat_id",    chatId);
        intent.putExtra("peer_uid",   user.uid);
        intent.putExtra("peer_name",  user.fullname != null ? user.fullname : "User");
        intent.putExtra("peer_photo", user.photoUrl != null ? user.photoUrl : "");
        startActivity(intent);
        finish();
    }

    // ═════════════════════════════ CHANNEL MODE ═══════════════════════════════

    /**
     * Same server-side prefix-search approach as performUserSearch() — see
     * that method's comment for the full reasoning. One extra note here:
     * the old version also matched query text ANYWHERE inside a channel's
     * description ("contains" search) — that's dropped, since substring
     * search across free text genuinely requires a full-text search engine
     * (Algolia etc.), not something a plain Firebase query can do without
     * downloading everything. It was already the lowest-priority match tier,
     * so this mainly affects edge cases like "find channels that mention X
     * somewhere in their bio" rather than normal name/handle search.
     */
    private void performChannelSearch(String query) {
        layoutLoading.setVisibility(View.VISIBLE);
        txtEmpty.setVisibility(View.GONE);

        final String lq = query.toLowerCase(java.util.Locale.ENGLISH);
        final java.util.LinkedHashMap<String, Channel> merged = new java.util.LinkedHashMap<>();
        final int[] pending = {2};
        final String forQuery = query;

        Runnable finishSearch = () -> {
            if (!forQuery.equals(etSearch.getText().toString().trim())) return; // stale response
            channelResults.clear();
            for (Channel c : merged.values()) { c.searchQuery = forQuery; channelResults.add(c); }
            // More popular channels surface first among equally-relevant matches.
            Collections.sort(channelResults, (a, b) -> {
                long as = a.subscriberCount != null ? a.subscriberCount : 0;
                long bs = b.subscriberCount != null ? b.subscriberCount : 0;
                return Long.compare(bs, as);
            });
            layoutLoading.setVisibility(View.GONE);
            txtEmpty.setVisibility(channelResults.isEmpty() ? View.VISIBLE : View.GONE);
            channelAdapter.notifyDataSetChanged();
        };

        DatabaseReference channelsRef = FirebaseDatabase.getInstance().getReference("channels");

        // Query 1 — @handle prefix
        channelsRef.orderByChild("handle").startAt(lq).endAt(lq + "\uf8ff").limitToFirst(25)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot child : snap.getChildren()) _addChannelIfPublic(child, merged);
                    if (--pending[0] == 0) finishSearch.run();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { if (--pending[0] == 0) finishSearch.run(); }
            });

        // Query 2 — channel name prefix (needs the maintained lowercase index field)
        channelsRef.orderByChild("name_lower").startAt(lq).endAt(lq + "\uf8ff").limitToFirst(25)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot child : snap.getChildren()) _addChannelIfPublic(child, merged);
                    if (--pending[0] == 0) finishSearch.run();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { if (--pending[0] == 0) finishSearch.run(); }
            });
    }

    private void _addChannelIfPublic(DataSnapshot child, java.util.Map<String, Channel> merged) {
        String id = child.getKey();
        if (id == null || merged.containsKey(id)) return;
        // Treat missing/blank privacy as public (discoverable) — only an
        // explicit "private" is hidden from search.
        String privacy = child.child("privacy").getValue(String.class);
        if ("private".equalsIgnoreCase(privacy)) return;
        merged.put(id, new Channel(
            id,
            child.child("name")            .getValue(String.class),
            child.child("handle")          .getValue(String.class),
            child.child("description")     .getValue(String.class),
            child.child("photo_url")       .getValue(String.class),
            child.child("subscriber_count").getValue(Long.class)
        ));
    }

    private void onChannelClicked(int position) {
        Channel ch = channelResults.get(position);
        _addToHistory(etSearch.getText().toString());
        Intent intent = new Intent(this, ChannelActivity.class);
        intent.putExtra("channel_id",   ch.id);
        intent.putExtra("channel_name", ch.name != null ? ch.name : "Channel");
        startActivity(intent);
        // don't finish — user may want to go back to search
    }

    // ═══════════════ RECENT SEARCHES + POPULAR CHANNELS ═══════════════════════
    //
    // Shown whenever the query box is empty. Before this, that area was a
    // single hardcoded "Start typing to search people" label — no history,
    // no suggestions, nothing to act on.
    //
    // History is stored per-mode (people searches and channel searches are
    // separate lists — mixing them would surface irrelevant suggestions),
    // locally in SharedPreferences: it's this device's own typing history,
    // not account data other people should be able to read, so there's no
    // reason to put it in Firebase.

    private static final String HISTORY_PREFS   = "minigram_search_history";
    private static final int    HISTORY_MAX     = 8;
    private static final String HISTORY_SEP     = "\n";
    private static final int    POPULAR_LIMIT   = 15;

    private String _historyKey() {
        return MODE_CHANNEL.equals(mode) ? "channel_history" : "user_history";
    }

    private List<String> _loadHistory() {
        String raw = getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE)
            .getString(_historyKey(), "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String s : raw.split(HISTORY_SEP)) {
            if (!s.trim().isEmpty()) out.add(s);
        }
        return out;
    }

    private void _saveHistory(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(HISTORY_SEP);
            sb.append(items.get(i));
        }
        getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE).edit()
            .putString(_historyKey(), sb.toString()).apply();
    }

    /** Called when a search actually leads somewhere (a result is opened) —
     *  not on every keystroke, so the list stays meaningful rather than
     *  filling up with half-typed fragments. */
    private void _addToHistory(String query) {
        if (query == null) return;
        String q = query.trim();
        if (q.isEmpty()) return;
        List<String> items = _loadHistory();
        // Re-searching something moves it back to the top instead of
        // creating a duplicate entry.
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).equalsIgnoreCase(q)) items.remove(i);
        }
        items.add(0, q);
        while (items.size() > HISTORY_MAX) items.remove(items.size() - 1);
        _saveHistory(items);
    }

    private void _confirmClearHistory() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Clear search history?")
            .setMessage("This removes your recent searches on this device.")
            .setPositiveButton("Clear", (d, w) -> {
                _saveHistory(new ArrayList<>());
                _renderDefaultView();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Rebuilds the empty-query view: history rows, then popular channels
     *  (channel mode only). */
    private void _renderDefaultView() {
        List<String> history = _loadHistory();
        containerHistory.removeAllViews();
        layoutHistoryHeader.setVisibility(history.isEmpty() ? View.GONE : View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (String q : history) {
            View row = inflater.inflate(R.layout.item_search_history, containerHistory, false);
            ((TextView) row.findViewById(R.id.txt_history_query)).setText(q);
            row.setOnClickListener(v -> {
                etSearch.setText(q);
                etSearch.setSelection(q.length()); // caret at the end, ready to edit
            });
            row.findViewById(R.id.btn_remove_history).setOnClickListener(v -> {
                List<String> items = _loadHistory();
                for (int i = items.size() - 1; i >= 0; i--) {
                    if (items.get(i).equalsIgnoreCase(q)) items.remove(i);
                }
                _saveHistory(items);
                _renderDefaultView();
            });
            containerHistory.addView(row);
        }

        if (MODE_CHANNEL.equals(mode)) {
            _loadPopularChannels();
        } else {
            txtPopularHeader.setVisibility(View.GONE);
            containerPopular.removeAllViews();
        }

        // Only show the "start typing" hint when there is genuinely nothing
        // else on screen — otherwise it's just noise under real content.
        boolean nothingElse = history.isEmpty()
            && (!MODE_CHANNEL.equals(mode) || containerPopular.getChildCount() == 0);
        txtRecentHint.setVisibility(nothingElse ? View.VISIBLE : View.GONE);
        txtRecentHint.setText(MODE_CHANNEL.equals(mode)
            ? "Start typing to search channels" : "Start typing to search people");
    }

    /** Most popular = highest subscriber_count. Sorted server-side via the
     *  existing subscriber_count ordering, so this is one bounded query
     *  rather than pulling the whole channels table down to sort locally. */
    private void _loadPopularChannels() {
        FirebaseDatabase.getInstance().getReference("channels")
            .orderByChild("subscriber_count").limitToLast(POPULAR_LIMIT)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    containerPopular.removeAllViews();

                    List<Channel> popular = new ArrayList<>();
                    for (DataSnapshot c : snap.getChildren()) {
                        // Private channels aren't discoverable — same rule
                        // the normal channel search already applies.
                        String privacy = c.child("privacy").getValue(String.class);
                        if ("private".equals(privacy)) continue;
                        Long subs = c.child("subscriber_count").getValue(Long.class);
                        Channel ch = new Channel(
                            c.getKey(),
                            c.child("name").getValue(String.class),
                            c.child("handle").getValue(String.class),
                            c.child("description").getValue(String.class),
                            c.child("photo_url").getValue(String.class),
                            subs);
                        ch.isVerified = Boolean.TRUE.equals(c.child("is_verified").getValue(Boolean.class));
                        popular.add(ch);
                    }
                    // limitToLast returns ASCENDING by subscriber_count —
                    // flip it so the biggest channels are on top.
                    Collections.reverse(popular);

                    if (popular.isEmpty()) {
                        txtPopularHeader.setVisibility(View.GONE);
                        return;
                    }
                    txtPopularHeader.setVisibility(View.VISIBLE);

                    LayoutInflater inflater = LayoutInflater.from(UserSearchActivity.this);
                    for (Channel ch : popular) {
                        View row = inflater.inflate(R.layout.item_popular_channel, containerPopular, false);
                        ((TextView) row.findViewById(R.id.txt_channel_name))
                            .setText(ch.name != null ? ch.name : "Channel");
                        long n = ch.subscriberCount != null ? ch.subscriberCount : 0;
                        ((TextView) row.findViewById(R.id.txt_channel_subs))
                            .setText(_formatSubs(n) + " subscribers");
                        row.findViewById(R.id.img_verified)
                            .setVisibility(ch.isVerified ? View.VISIBLE : View.GONE);

                        ImageView img = row.findViewById(R.id.img_channel);
                        Glide.with(UserSearchActivity.this)
                            .load(ch.imageUrl)
                            .placeholder(R.drawable.ic_channel)
                            .error(R.drawable.ic_channel)
                            .circleCrop()
                            .into(img);

                        row.setOnClickListener(v -> {
                            Intent intent = new Intent(UserSearchActivity.this, ChannelActivity.class);
                            intent.putExtra("channel_id",   ch.id);
                            intent.putExtra("channel_name", ch.name != null ? ch.name : "Channel");
                            startActivity(intent);
                        });
                        containerPopular.addView(row);
                    }

                    txtRecentHint.setVisibility(View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (isFinishing() || isDestroyed()) return;
                    txtPopularHeader.setVisibility(View.GONE);
                }
            });
    }

    private String _formatSubs(long n) {
        if (n >= 1_000_000) return String.format(java.util.Locale.getDefault(), "%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format(java.util.Locale.getDefault(), "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    // ─────────────────────────────────────────────────────────────────────────
    private void clearResults() {
        userResults.clear();
        channelResults.clear();
        txtEmpty.setVisibility(View.GONE);
        if (userAdapter    != null) userAdapter.notifyDataSetChanged();
        if (channelAdapter != null) channelAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bannerAd != null) bannerAd.destroy();
        debounceHandler.removeCallbacksAndMessages(null);
    }

    // ══════════════════════════ USER ADAPTER ══════════════════════════════════
    private class UserAdapter extends BaseAdapter {
        @Override public int    getCount()              { return userResults.size(); }
        @Override public Object getItem(int pos)        { return userResults.get(pos); }
        @Override public long   getItemId(int pos)      { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null)
                convertView = LayoutInflater.from(UserSearchActivity.this)
                        .inflate(R.layout.item_search_user, parent, false);

            User u = userResults.get(position);
            ImageView img  = convertView.findViewById(R.id.img_avatar);
            TextView  name = convertView.findViewById(R.id.txt_name);
            TextView  user = convertView.findViewById(R.id.txt_username);
            TextView  bio  = convertView.findViewById(R.id.txt_bio);

            name.setText(highlight(u.fullname != null ? u.fullname : "User", u.searchQuery));
            user.setText(highlight("@" + (u.username != null ? u.username : ""), u.searchQuery));
            if (bio != null)
                bio.setText(u.bio != null && !u.bio.isEmpty() ? u.bio : "");

            if (u.photoUrl != null && !u.photoUrl.isEmpty())
                Glide.with(UserSearchActivity.this).load(u.photoUrl).circleCrop().into(img);
            else
                img.setImageResource(R.drawable.ic_person);

            return convertView;
        }
    }

    // ══════════════════════════ CHANNEL ADAPTER ═══════════════════════════════
    private class ChannelAdapter extends BaseAdapter {
        @Override public int    getCount()              { return channelResults.size(); }
        @Override public Object getItem(int pos)        { return channelResults.get(pos); }
        @Override public long   getItemId(int pos)      { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            // Reuse user_search item layout — same fields map cleanly
            if (convertView == null)
                convertView = LayoutInflater.from(UserSearchActivity.this)
                        .inflate(R.layout.item_search_user, parent, false);

            Channel c    = channelResults.get(position);
            ImageView img= convertView.findViewById(R.id.img_avatar);
            TextView name= convertView.findViewById(R.id.txt_name);
            TextView user= convertView.findViewById(R.id.txt_username);
            TextView bio = convertView.findViewById(R.id.txt_bio);

            name.setText(highlight(c.name != null ? c.name : "Channel", c.searchQuery));

            // Handle + subscriber count
            String handleStr = (c.handle != null ? "@" + c.handle : "");
            long subs = c.subscriberCount != null ? c.subscriberCount : 0;
            String subStr = subs > 0 ? "  ·  " + formatCount(subs) + " subscribers" : "";
            user.setText(highlight(handleStr + subStr, c.searchQuery));

            if (bio != null)
                bio.setText(c.desc != null && !c.desc.isEmpty() ? c.desc : "");

            // Channel avatar
            if (c.imageUrl != null && !c.imageUrl.isEmpty())
                Glide.with(UserSearchActivity.this).load(c.imageUrl).circleCrop().into(img);
            else {
                img.setImageResource(R.drawable.ic_person); // fallback
                img.setColorFilter(Ink.c("#2196F3"));
            }

            return convertView;
        }

        private String formatCount(long n) {
            if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
            if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
            return String.valueOf(n);
        }
    }

    // ── Text highlight helper ─────────────────────────────────────────────────
    private SpannableString highlight(String text, String query) {
        SpannableString sp = new SpannableString(text);
        if (query == null || query.isEmpty()) return sp;
        String lText = text.toLowerCase();
        String lQuery= query.toLowerCase();
        int idx = lText.indexOf(lQuery);
        if (idx >= 0) {
            sp.setSpan(new BackgroundColorSpan(Ink.c("#FFF59D")),
                    idx, idx + query.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new StyleSpan(Typeface.BOLD),
                    idx, idx + query.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sp;
    }

    // ══════════════════════════ MODEL CLASSES ═════════════════════════════════

    private static class User {
        String uid, fullname, username, photoUrl, bio, searchQuery;
        int matchRank; // 0=exact, 1=starts-with, 2=contains — used to sort results

        User(String uid, String fullname, String username, String photoUrl, String bio) {
            this.uid = uid; this.fullname = fullname; this.username = username;
            this.photoUrl = photoUrl; this.bio = bio;
        }
        User(User o) {
            this.uid = o.uid; this.fullname = o.fullname; this.username = o.username;
            this.photoUrl = o.photoUrl; this.bio = o.bio; this.searchQuery = o.searchQuery;
            this.matchRank = o.matchRank;
        }
    }

    private static class Channel {
        String id, name, handle, desc, imageUrl, searchQuery;
        Long   subscriberCount;
        boolean isVerified;
        int matchRank; // 0=exact, 1=starts-with, 2=contains, 3=desc-only — used to sort results

        Channel(String id, String name, String handle, String desc,
                String imageUrl, Long subscriberCount) {
            this.id = id; this.name = name; this.handle = handle;
            this.desc = desc; this.imageUrl = imageUrl;
            this.subscriberCount = subscriberCount;
        }
        Channel(Channel o) {
            this.id = o.id; this.name = o.name; this.handle = o.handle;
            this.desc = o.desc; this.imageUrl = o.imageUrl;
            this.subscriberCount = o.subscriberCount;
            this.searchQuery = o.searchQuery;
            this.matchRank = o.matchRank;
        }
    }
}