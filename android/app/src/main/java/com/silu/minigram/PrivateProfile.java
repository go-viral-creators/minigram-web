package com.silu.minigram;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;

/**
 * The signed-in user's private profile fields (phone, e-mail).
 *
 * They used to live in users/{uid}, which every signed-in user can read. They now live in
 * user_private/{uid}, which only the owner can read (database.rules.json). Reads fall back to
 * the legacy location once and migrate on the spot, so accounts that have not yet been touched
 * by functions/scripts/migrate_private_fields.js keep working.
 */
final class PrivateProfile {
    private PrivateProfile() {}

    interface Callback { void onResult(String phoneE164, String phoneCc, String phoneNumber); }

    static DatabaseReference ref(String uid) {
        return FirebaseDatabase.getInstance().getReference("user_private").child(uid);
    }

    /** Path prefix for multi-path updates: "user_private/{uid}/". */
    static String prefix(String uid) { return "user_private/" + uid + "/"; }

    static void readOwn(final String uid, final Callback cb) {
        ref(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot p) {
                String phone = p.child("phone").getValue(String.class);
                if (phone != null && !phone.isEmpty()) {
                    cb.onResult(phone, p.child("phone_cc").getValue(String.class),
                            p.child("phone_number").getValue(String.class));
                    return;
                }
                migrateLegacy(uid, cb);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { cb.onResult(null, null, null); }
        });
    }

    private static void migrateLegacy(final String uid, final Callback cb) {
        FirebaseDatabase.getInstance().getReference("users").child(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot u) {
                    String phone = u.child("phone").getValue(String.class);
                    String cc = u.child("phone_cc").getValue(String.class);
                    String num = u.child("phone_number").getValue(String.class);
                    String email = u.child("email").getValue(String.class);
                    HashMap<String, Object> mv = new HashMap<>();
                    for (String f : new String[]{"phone", "phone_cc", "phone_number", "email"}) {
                        String v = u.child(f).getValue(String.class);
                        if (v != null && !v.isEmpty()) mv.put(prefix(uid) + f, v);
                        if (u.hasChild(f)) mv.put("users/" + uid + "/" + f, null);
                    }
                    if (!mv.isEmpty()) FirebaseDatabase.getInstance().getReference().updateChildren(mv);
                    cb.onResult(phone, cc, num);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { cb.onResult(null, null, null); }
            });
    }
}
