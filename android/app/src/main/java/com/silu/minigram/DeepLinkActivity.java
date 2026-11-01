package com.silu.minigram;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.List;

public class DeepLinkActivity extends AppCompatActivity {

    /** Primary web host — apni hosting par. Naye share links yahin bante hain. */
    private static final String WEB_HOST    = "minigram.fireclashpro.com";
    /** Legacy host — pehle se share ho chuke links kaam karte rahein isliye
     *  ise hataya nahi gaya. Jab confident ho jao ki purane links circulation
     *  mein nahi hain, tab hata dena. */
    private static final String LEGACY_HOST = "minigram-messaging.vercel.app";

    /** Dono hosts accept karo — backward compatible. */
    private static boolean isKnownHost(String host) {
        return WEB_HOST.equalsIgnoreCase(host) || LEGACY_HOST.equalsIgnoreCase(host);
    }

    private DatabaseReference handlesRef;
    private FirebaseAuth      auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FirebaseApp.initializeApp(this);
        auth       = FirebaseAuth.getInstance();
        handlesRef = FirebaseDatabase.getInstance().getReference("handles");

        Uri data = getIntent().getData();
        if (data == null) {
            _abort("Invalid link");
            return;
        }

        _route(data);
    }

    // ─── Route based on scheme ────────────────────────────────────────────────

    private void _route(Uri data) {
        String scheme = data.getScheme();

        if ("minigram".equals(scheme)) {

            String type   = data.getHost();
            String handle = data.getLastPathSegment();
            _dispatch(type, handle);

        } else if ("https".equals(scheme) && isKnownHost(data.getHost())) {

            List<String> segments = data.getPathSegments();

            // FIX: minigram.fireclashpro.com par ab links /admin/u/<handle> aur
            // /admin/c/<handle> hain (pehle /u/ /c/ the, domain root par) —
            // isliye ab 3 segments hain: ["admin", "u"|"c", "<handle>"].
            //
            // Legacy Vercel host (minigram-messaging.vercel.app) abhi bhi
            // purane /u/<handle> /c/<handle> format (2 segments) par hai —
            // uska apna /admin/ folder nahi hai — isliye dono format yahan
            // parallel handle kiye gaye hain taaki purane share ho chuke Vercel
            // links bhi kaam karte rahein.
            String prefix, handle;
            if (segments != null && segments.size() >= 3 && "admin".equals(segments.get(0))) {
                prefix = segments.get(1);
                handle = segments.get(2);
            } else if (segments != null && segments.size() >= 2) {
                prefix = segments.get(0);
                handle = segments.get(1);
            } else {
                _abort("Malformed web link");
                return;
            }

            String type = "u".equals(prefix) ? "user"
                        : "c".equals(prefix) ? "channel"
                        : null;

            _dispatch(type, handle);

        } else {
            _abort("Unknown link format");
        }
    }

    // ─── Unified dispatcher ───────────────────────────────────────────────────

    private void _dispatch(String type, String handle) {
        if (type == null || handle == null || handle.isEmpty()) {
            _abort("Invalid link");
            return;
        }

        String cleanHandle = handle.toLowerCase().trim();

        handlesRef.child(cleanHandle).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                if (!snap.exists()) {
                    _abort("@" + cleanHandle + " not found");
                    return;
                }

                String dbType = snap.child("type").getValue(String.class);
                String id     = snap.child("id").getValue(String.class);

                if (dbType == null || id == null) {
                    _abort("Broken link data");
                    return;
                }

                if ("user".equals(dbType)) {
                    _openUserChat(id);
                } else if ("channel".equals(dbType)) {
                    _openChannel(id);
                } else {
                    _abort("Unknown link type");
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                _abort("Network error: " + error.getMessage());
            }
        });
    }

    // ─── Open chat with user ──────────────────────────────────────────────────

    private void _openUserChat(String peerUid) {
        String myUid = auth.getCurrentUser() != null
                ? auth.getCurrentUser().getUid() : null;

        if (myUid == null) { _abort("You must be logged in"); return; }
        if (myUid.equals(peerUid)) { _abort("That's your own profile"); return; }

        FirebaseDatabase.getInstance().getReference("users").child(peerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    String peerName  = snap.child("fullname").getValue(String.class);
                    String peerPhoto = snap.child("photo_url").getValue(String.class);
                    if (peerName  == null) peerName  = "";
                    if (peerPhoto == null) peerPhoto = "";

                    // Deterministic chat-id — smaller UID first
                    String chatId = myUid.compareTo(peerUid) < 0
                        ? myUid + "_" + peerUid
                        : peerUid + "_" + myUid;

                    Intent intent = new Intent(DeepLinkActivity.this, ChatActivity.class);
                    intent.putExtra("chat_id",    chatId);
                    intent.putExtra("peer_uid",   peerUid);
                    intent.putExtra("peer_name",  peerName);
                    intent.putExtra("peer_photo", peerPhoto);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    _abort("Failed to load user: " + error.getMessage());
                }
            });
    }

    // ─── Open channel ─────────────────────────────────────────────────────────

    private void _openChannel(String channelId) {
        FirebaseDatabase.getInstance().getReference("channels").child(channelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!snap.exists()) { _abort("Channel not found"); return; }

                    String name  = snap.child("name").getValue(String.class);
                    String photo = snap.child("photo_url").getValue(String.class);
                    String owner = snap.child("owner_uid").getValue(String.class);
                    if (name  == null) name  = "";
                    if (photo == null) photo = "";
                    if (owner == null) owner = "";

                    Intent intent = new Intent(DeepLinkActivity.this, ChannelActivity.class);
                    intent.putExtra("channel_id",    channelId);
                    intent.putExtra("channel_name",  name);
                    intent.putExtra("channel_photo", photo);
                    intent.putExtra("owner_uid",     owner);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    _abort("Failed to load channel: " + error.getMessage());
                }
            });
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private void _abort(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        finish();
    }
}