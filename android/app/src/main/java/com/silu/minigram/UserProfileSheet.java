package com.silu.minigram;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class UserProfileSheet extends BottomSheetDialogFragment {

    private static final String WEB_BASE  = "https://minigram.fireclashpro.com";
    private static final String A_UID     = "uid";
    private static final String A_CHAT_ID = "chat_id";

    public static UserProfileSheet show(String uid, String chatId) {
        UserProfileSheet s = new UserProfileSheet();
        Bundle b = new Bundle();
        b.putString(A_UID, uid);
        b.putString(A_CHAT_ID, chatId);
        s.setArguments(b);
        return s;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inf, ViewGroup cont, Bundle state) {
        View view = inf.inflate(R.layout.sheet_user_profile, cont, false);

        Bundle args = getArguments();
        if (args == null) {
            dismissAllowingStateLoss();
            return view;
        }

        String uid = args.getString(A_UID);
        String chatId = args.getString(A_CHAT_ID);

        if (uid == null || getContext() == null) {
            dismissAllowingStateLoss();
            return view;
        }

        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) {
            dismissAllowingStateLoss();
            return view;
        }

        String myUid = auth.getCurrentUser().getUid();

        ImageView imgAv = view.findViewById(R.id.img_avatar);
        TextView txtName = view.findViewById(R.id.txt_name);
        TextView txtUsername = view.findViewById(R.id.txt_username);
        ImageView imgVerifiedBadge = view.findViewById(R.id.img_verified_badge);
        TextView txtBio = view.findViewById(R.id.txt_bio);
        TextView txtStatus = view.findViewById(R.id.txt_status);
        LinearLayout btnMessage = view.findViewById(R.id.btn_message);
        LinearLayout btnShare = view.findViewById(R.id.btn_share);
        TextView btnClose = view.findViewById(R.id.btn_close);

        btnClose.setOnClickListener(v -> dismiss());

        FirebaseDatabase.getInstance().getReference("users").child(uid)
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        if (!snap.exists() || getContext() == null) return;

                        String name = snap.child("fullname").getValue(String.class);

                        String handle = snap.child("handle").getValue(String.class);
                        if (handle == null || handle.isEmpty()) {
                            handle = snap.child("username").getValue(String.class);
                        }

                        String bio = snap.child("bio").getValue(String.class);
                        String photo = snap.child("photo_url").getValue(String.class);
                        String status = snap.child("status").getValue(String.class);

                        txtName.setText(name != null && !name.isEmpty() ? name : "User");
                        txtUsername.setText("@" + (handle != null ? handle : ""));
                        txtBio.setText(bio != null && !bio.isEmpty() ? bio : "No bio");

                        boolean isPro = Boolean.TRUE.equals(snap.child("is_pro").getValue(Boolean.class));
                        if (imgVerifiedBadge != null)
                            imgVerifiedBadge.setVisibility(isPro ? View.VISIBLE : View.GONE);

                        boolean online = "online".equals(status);
                        txtStatus.setText(online ? "● Online" : "● Offline");
                        txtStatus.setTextColor(online
                                ? Color.parseColor("#4CAF50")
                                : Color.parseColor("#9E9E9E"));

                        AvatarHelper.load(requireContext(), imgAv, photo, name);

                        final String resolvedHandle = handle;

                        btnShare.setOnClickListener(v -> {
                            if (getContext() == null) return;
                            showShareOptions(
                                    name != null && !name.isEmpty() ? name : "User",
                                    resolvedHandle
                            );
                        });
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError e) { }
                });

        // Send Message button removed entirely — redundant since the profile
        // sheet is always opened from within a chat/context that already
        // has a way to message the person.
        btnMessage.setVisibility(View.GONE);
        if (myUid.equals(uid)) {
            btnShare.setVisibility(View.VISIBLE);
        }

        return view;
    }

    private void showShareOptions(String name, String handle) {
        if (handle == null || handle.isEmpty()) {
            Toast.makeText(getContext(), "No handle set for this user", Toast.LENGTH_SHORT).show();
            return;
        }

        // FIX: "/admin/u/" hai, sirf "/u/" nahi — dekho
        // ChannelSettingsActivity._buildChannelWebLink() ka comment.
        String link = WEB_BASE + "/admin/u/" + handle;

        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("Share Profile")
                .setItems(new String[]{"Copy Link", "Share via..."}, (d, which) -> {
                    if (which == 0) {
                        ClipboardManager cm = (ClipboardManager)
                                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("Profile Link", link));
                            Toast.makeText(getContext(), "Link copied!", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("text/plain");
                        share.putExtra(Intent.EXTRA_SUBJECT, name + " on Minigram");
                        share.putExtra(Intent.EXTRA_TEXT,
                                "Chat with " + name + " on Minigram:\n" + link);
                        startActivity(Intent.createChooser(share, "Share profile via"));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}