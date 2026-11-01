package com.silu.minigram;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Requirement 5 — New Message screen. Built in three passes:
 *  1. Foundation: search-by-handle + a Recent Chats default view.
 *  2. Made the phone number that contact-matching needs mandatory at
 *     signup (CompleteProfileActivity + phone_index).
 *  3. THIS pass — the part those two were building towards: read the
 *     device's contacts, match their phone numbers against phone_index,
 *     and offer either "message them" (already on Minigram) or "invite
 *     them" (not yet) — same shape as WhatsApp/Telegram's own contact
 *     list.
 *
 * Matching approach: no phone-number library is assumed available in this
 * project's fixed Sketchware Pro library set (same constraint noted in
 * CompleteProfileActivity), so normalization is deliberately simple — a
 * contact number that already looks international (starts with "+", or
 * is long enough to plausibly include a country code) is used as-is;
 * anything shorter is assumed to be a LOCAL number in the same country as
 * the signed-in user's own phone (their own phone_cc), matching how most
 * people's saved contacts actually look (local numbers, no country code)
 * for contacts in their own country. This isn't perfect for a contact
 * list with numbers from many different countries, but it's the same
 * "good enough for matching, not billing-grade" bar phone_index itself
 * was already built to.
 */
public class NewMessageActivity extends AppCompatActivity {

    private String myUid;
    private String myPhoneCc = "+91"; // fallback default, overwritten from the user's own profile

    private EditText editSearch;
    private RecyclerView recyclerView;
    private TextView txtSectionLabel, txtEmpty;
    private View rowContactsPermission;
    private ProgressBar progressLoading;

    // Unified row list backing the adapter: each entry is either a String
    // (section header) or a PersonRow (an actual contact/result).
    private final List<Object> recentChatsView = new ArrayList<>();
    private final List<Object> contactsView = new ArrayList<>();
    private final List<Object> searchResultsView = new ArrayList<>();
    private RowAdapter adapter;

    private boolean recentChatsLoaded = false;
    private boolean contactsLoadedOnce = false;
    private String currentQuery = "";

    private final ActivityResultLauncher<String> contactsPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                rowContactsPermission.setVisibility(View.GONE);
                _loadDeviceContacts();
            } else {
                Toast.makeText(this, "Contacts access denied — you can still search by @handle", Toast.LENGTH_LONG).show();
            }
        });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_message);
        EdgeToEdgeHelper.setup(this);
        EdgeToEdgeHelper.applyInsets(findViewById(R.id.main));

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (myUid == null) { finish(); return; }

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        editSearch            = findViewById(R.id.edit_search);
        recyclerView          = findViewById(R.id.recycler_people);
        txtSectionLabel       = findViewById(R.id.txt_section_label);
        txtEmpty              = findViewById(R.id.txt_empty);
        rowContactsPermission = findViewById(R.id.row_contacts_permission);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowAdapter();
        recyclerView.setAdapter(adapter);

        findViewById(R.id.btn_allow_contacts).setOnClickListener(v ->
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS));

        editSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                currentQuery = s.toString().trim();
                if (currentQuery.isEmpty()) _showDefaultView();
                else _performSearch(currentQuery);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        _loadMyPhoneCc();
        _loadRecentChats();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            _loadDeviceContacts();
        } else {
            rowContactsPermission.setVisibility(View.VISIBLE);
        }
    }

    private void _loadMyPhoneCc() {
        PrivateProfile.readOwn(myUid, (e164, cc, num) -> {
            if (cc != null && !cc.isEmpty()) myPhoneCc = cc;
        });
    }

    // ── Recent Chats (unchanged from the foundation pass) ───────────────────

    private void _loadRecentChats() {
        FirebaseDatabase.getInstance().getReference("user_chats").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    recentChatsLoaded = true;
                    List<String> peerUids = new ArrayList<>();
                    for (DataSnapshot c : snap.getChildren()) {
                        String peerUid = c.child("peer_uid").getValue(String.class);
                        if (peerUid != null) peerUids.add(peerUid);
                    }
                    if (peerUids.isEmpty()) { _showDefaultView(); return; }

                    final int[] remaining = {peerUids.size()};
                    for (String uid : peerUids) {
                        FirebaseDatabase.getInstance().getReference("users").child(uid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot userSnap) {
                                    if (userSnap.exists()) {
                                        recentChatsView.add(_rowFromSnapshot(uid, userSnap));
                                    }
                                    if (--remaining[0] == 0) _showDefaultView();
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {
                                    if (--remaining[0] == 0) _showDefaultView();
                                }
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    recentChatsLoaded = true;
                    _showDefaultView();
                }
            });
    }

    // ── Device contacts — read, normalize, match ─────────────────────────────

    private void _loadDeviceContacts() {
        if (contactsLoadedOnce) { _showDefaultView(); return; }
        progressLoading = null; // reuse txtEmpty as the loading indicator instead of adding a new view
        if (currentQuery.isEmpty()) {
            txtEmpty.setText("Loading contacts…");
            txtEmpty.setVisibility(View.VISIBLE);
        }

        new Thread(() -> {
            // LinkedHashMap keyed by normalized E.164 — dedupes a contact
            // with the same number saved under multiple raw formats/entries.
            LinkedHashMap<String, String> phoneToName = new LinkedHashMap<>();
            ContentResolver cr = getContentResolver();
            try (Cursor cursor = cr.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    },
                    null, null, null)) {
                if (cursor != null) {
                    int nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                    int numIdx  = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                    // Cap how many raw rows get processed — a contact list
                    // in the low thousands of entries is unusual, and this
                    // keeps the one-time phone_index lookup pass bounded.
                    int rowsSeen = 0;
                    while (cursor.moveToNext() && rowsSeen < 1500) {
                        rowsSeen++;
                        String name = nameIdx >= 0 ? cursor.getString(nameIdx) : null;
                        String raw  = numIdx  >= 0 ? cursor.getString(numIdx)  : null;
                        String e164 = _normalizeToE164(raw, myPhoneCc);
                        if (e164 != null && name != null && !phoneToName.containsKey(e164)) {
                            phoneToName.put(e164, name);
                        }
                    }
                }
            } catch (Exception ignored) {
                // A device that denies the query some other way (rare OEM
                // quirks) — fail quietly into an empty contacts list rather
                // than crash; search-by-handle still works regardless.
            }
            runOnUiThread(() -> _matchContacts(phoneToName));
        }).start();
    }

    /**
     * Same deliberately simple approach as CompleteProfileActivity's E.164
     * builder — no phone-number library assumed available. A number that
     * already starts with "+" is trusted as-is; a long (11+ digit) number
     * without "+" is ALSO trusted as-is with a "+" prepended (very likely
     * already has a country code); anything shorter is treated as a LOCAL
     * number and given the signed-in user's own country code.
     */
    private String _normalizeToE164(String raw, String defaultCc) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9+]", "");
        if (digits.isEmpty()) return null;
        String e164;
        if (digits.startsWith("+")) {
            e164 = digits;
        } else if (digits.length() >= 11) {
            e164 = "+" + digits;
        } else {
            String cc = (defaultCc != null && !defaultCc.isEmpty()) ? defaultCc : "+91";
            e164 = cc + digits;
        }
        return e164.matches("\\+[1-9]\\d{7,14}") ? e164 : null;
    }

    private void _matchContacts(LinkedHashMap<String, String> phoneToName) {
        if (phoneToName.isEmpty()) {
            contactsLoadedOnce = true;
            _showDefaultView();
            return;
        }
        List<String> phones = new ArrayList<>(phoneToName.keySet());
        List<PersonRow> onApp = new ArrayList<>();
        List<PersonRow> notOnApp = new ArrayList<>();
        final int[] remaining = {phones.size()};

        for (String e164 : phones) {
            String contactName = phoneToName.get(e164);
            String key = e164.startsWith("+") ? e164.substring(1) : e164;
            FirebaseDatabase.getInstance().getReference("phone_index").child(key)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String uid = snap.getValue(String.class);
                        if (uid != null && !uid.equals(myUid)) {
                            FirebaseDatabase.getInstance().getReference("users").child(uid)
                                .addListenerForSingleValueEvent(new ValueEventListener() {
                                    @Override public void onDataChange(@NonNull DataSnapshot userSnap) {
                                        if (userSnap.exists()) onApp.add(_rowFromSnapshot(uid, userSnap));
                                        if (--remaining[0] == 0) _onContactsMatched(onApp, notOnApp);
                                    }
                                    @Override public void onCancelled(@NonNull DatabaseError e) {
                                        if (--remaining[0] == 0) _onContactsMatched(onApp, notOnApp);
                                    }
                                });
                        } else {
                            PersonRow row = new PersonRow();
                            row.fullname = contactName;
                            row.rawPhoneE164 = e164;
                            notOnApp.add(row);
                            if (--remaining[0] == 0) _onContactsMatched(onApp, notOnApp);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (--remaining[0] == 0) _onContactsMatched(onApp, notOnApp);
                    }
                });
        }
    }

    private void _onContactsMatched(List<PersonRow> onApp, List<PersonRow> notOnApp) {
        if (isFinishing() || isDestroyed()) return;
        contactsLoadedOnce = true;
        contactsView.clear();
        if (!onApp.isEmpty()) {
            contactsView.add("ON MINIGRAM");
            contactsView.addAll(onApp);
        }
        if (!notOnApp.isEmpty()) {
            contactsView.add("INVITE TO MINIGRAM");
            contactsView.addAll(notOnApp);
        }
        _showDefaultView();
    }

    // ── View switching ──────────────────────────────────────────────────────

    /** Default (no search query) view = Recent Chats first, then matched/
     *  invitable contacts below — same idea as Telegram's own New Message
     *  screen, which doesn't show a blank box either. */
    private void _showDefaultView() {
        if (!currentQuery.isEmpty()) return; // user has since started typing

        List<Object> combined = new ArrayList<>();
        if (!recentChatsView.isEmpty()) {
            combined.add("RECENT CHATS");
            combined.addAll(recentChatsView);
        }
        combined.addAll(contactsView);

        txtSectionLabel.setVisibility(View.GONE); // headers are now inline rows in the adapter
        adapter.setData(combined);

        boolean stillLoading = !recentChatsLoaded || (rowContactsPermission.getVisibility() != View.VISIBLE && !contactsLoadedOnce);
        if (combined.isEmpty() && stillLoading) {
            txtEmpty.setText("Loading…");
            txtEmpty.setVisibility(View.VISIBLE);
        } else if (combined.isEmpty()) {
            txtEmpty.setText("Search for a name or @handle to start a new chat");
            txtEmpty.setVisibility(View.VISIBLE);
        } else {
            txtEmpty.setVisibility(View.GONE);
        }
    }

    private void _performSearch(String query) {
        txtSectionLabel.setVisibility(View.GONE);
        txtEmpty.setVisibility(View.GONE);

        final String lq = query.toLowerCase(Locale.ENGLISH);
        final LinkedHashMap<String, PersonRow> merged = new LinkedHashMap<>();
        final int[] pending = {2};
        final String forQuery = query;

        Runnable finish = () -> {
            if (!forQuery.equals(currentQuery)) return; // stale
            searchResultsView.clear();
            if (!merged.isEmpty()) {
                searchResultsView.add("SEARCH RESULTS");
                searchResultsView.addAll(merged.values());
            }
            adapter.setData(searchResultsView);
            txtEmpty.setVisibility(searchResultsView.isEmpty() ? View.VISIBLE : View.GONE);
            txtEmpty.setText("No one found — check the spelling or @handle");
        };

        DatabaseReference usersRef = FirebaseDatabase.getInstance().getReference("users");

        usersRef.orderByChild("handle").startAt(lq).endAt(lq + "\uf8ff").limitToFirst(25)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot child : snap.getChildren()) {
                        String uid = child.getKey();
                        if (uid == null || uid.equals(myUid) || merged.containsKey(uid)) continue;
                        merged.put(uid, _rowFromSnapshot(uid, child));
                    }
                    if (--pending[0] == 0) finish.run();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { if (--pending[0] == 0) finish.run(); }
            });

        usersRef.orderByChild("fullname_lower").startAt(lq).endAt(lq + "\uf8ff").limitToFirst(25)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot child : snap.getChildren()) {
                        String uid = child.getKey();
                        if (uid == null || uid.equals(myUid) || merged.containsKey(uid)) continue;
                        merged.put(uid, _rowFromSnapshot(uid, child));
                    }
                    if (--pending[0] == 0) finish.run();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { if (--pending[0] == 0) finish.run(); }
            });
    }

    private PersonRow _rowFromSnapshot(String uid, DataSnapshot snap) {
        PersonRow row = new PersonRow();
        row.uid      = uid;
        row.fullname = snap.child("fullname").getValue(String.class);
        row.handle   = snap.child("handle").getValue(String.class);
        row.photoUrl = snap.child("photo_url").getValue(String.class);
        return row;
    }

    private void _openChat(PersonRow person) {
        String chatId = myUid.compareTo(person.uid) < 0
            ? myUid + "_" + person.uid
            : person.uid + "_" + myUid;
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("chat_id",    chatId);
        i.putExtra("peer_uid",   person.uid);
        i.putExtra("peer_name",  person.fullname != null ? person.fullname : "User");
        i.putExtra("peer_photo", person.photoUrl != null ? person.photoUrl : "");
        startActivity(i);
        finish();
    }

    private static final String PLAY_URL = "https://play.google.com/store/apps/details?id=com.silu.minigram";

    private void _inviteContact(PersonRow person) {
        String message = "Hey" + (person.fullname != null ? " " + person.fullname.split(" ")[0] : "")
            + ", I'm using Minigram — join me here: " + PLAY_URL;
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + person.rawPhoneE164));
            i.putExtra("sms_body", message);
            startActivity(i);
        } catch (Exception e) {
            // No SMS app / smsto not handled — fall back to a generic share
            // sheet so Invite still does something useful either way.
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, message);
            startActivity(Intent.createChooser(share, "Invite via"));
        }
    }

    private static class PersonRow {
        String uid, fullname, handle, photoUrl;
        String rawPhoneE164; // set only for not-yet-on-Minigram contacts, for Invite
    }

    // ── Adapter — two view types: section header (String) and person row ────

    private static final int TYPE_HEADER = 0, TYPE_PERSON = 1;

    private class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<Object> data = new ArrayList<>();
        void setData(List<Object> newData) {
            data = newData;
            notifyDataSetChanged();
        }

        @Override public int getItemViewType(int position) {
            return data.get(position) instanceof String ? TYPE_HEADER : TYPE_PERSON;
        }

        class HeaderVH extends RecyclerView.ViewHolder {
            TextView label;
            HeaderVH(View v) { super(v); label = (TextView) v; }
        }
        class PersonVH extends RecyclerView.ViewHolder {
            ImageView img; TextView name, handle, invite;
            PersonVH(View v) {
                super(v);
                img    = v.findViewById(R.id.img_avatar);
                name   = v.findViewById(R.id.txt_name);
                handle = v.findViewById(R.id.txt_handle);
                invite = v.findViewById(R.id.btn_invite);
            }
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_new_message_section_header, parent, false));
            }
            return new PersonVH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_new_message_person, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            Object item = data.get(position);
            if (h instanceof HeaderVH) {
                ((HeaderVH) h).label.setText((String) item);
                return;
            }
            PersonRow row = (PersonRow) item;
            PersonVH vh = (PersonVH) h;
            boolean isInvite = row.uid == null && row.rawPhoneE164 != null;

            vh.name.setText(row.fullname != null ? row.fullname : "Unknown");
            vh.handle.setText(!isInvite && row.handle != null && !row.handle.isEmpty() ? "@" + row.handle : "");
            vh.invite.setVisibility(isInvite ? View.VISIBLE : View.GONE);

            if (isInvite) {
                vh.img.setImageResource(R.drawable.ic_person);
                vh.itemView.setOnClickListener(v -> _inviteContact(row));
                vh.invite.setOnClickListener(v -> _inviteContact(row));
            } else {
                Glide.with(NewMessageActivity.this)
                    .load(row.photoUrl).placeholder(R.drawable.ic_person).error(R.drawable.ic_person)
                    .circleCrop().into(vh.img);
                vh.itemView.setOnClickListener(v -> _openChat(row));
            }
        }

        @Override public int getItemCount() { return data.size(); }
    }
}
