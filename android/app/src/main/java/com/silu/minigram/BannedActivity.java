package com.silu.minigram;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/**
 * Shown when the account has been banned by an admin
 * (users/{uid}/is_banned == true, set from the PHP admin panel).
 *
 * The user stays SIGNED IN here (deliberately) so this screen can keep a
 * live listener on their is_banned flag — the moment the admin unbans them
 * from the panel, they're automatically let back into the app, realtime.
 * Actual enforcement while banned is server-side in the Firebase rules
 * (banned users' writes to chats/posts/replies are denied), so keeping the
 * session alive is safe.
 *
 * Back button is blocked — the only ways out are the Exit button or an
 * admin unban.
 */
public class BannedActivity extends AppCompatActivity {

    private DatabaseReference banRef;
    private ValueEventListener banListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdgeHelper.setup(this);
        setContentView(R.layout.banned);

        TextView btnExit = findViewById(R.id.btn_exit_app);
        if (btnExit != null) {
            btnExit.setOnClickListener(v -> finishAffinity());
        }

        // Live unban watch — admin unbans → user walks right back in.
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return; // signed out somehow — Exit is the only path

        banRef = FirebaseDatabase.getInstance()
            .getReference("users").child(uid).child("is_banned");
        banListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!Boolean.TRUE.equals(snap.getValue(Boolean.class))) {
                    // Unbanned! Back into the app.
                    Intent i = new Intent(BannedActivity.this, ChatlistActivity.class);
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        banRef.addValueEventListener(banListener);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (banRef != null && banListener != null) banRef.removeEventListener(banListener);
    }

    @Override
    public void onBackPressed() {
        // Banned users can't navigate back into the app — exit only.
        finishAffinity();
    }
}
