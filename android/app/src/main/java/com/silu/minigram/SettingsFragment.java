package com.silu.minigram;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.silu.minigram.databinding.ActivitySettingsBinding;
import com.silu.minigram.databinding.RowSettingBinding;

/**
 * Settings (Ink &amp; Paper redesign, ViewBinding). Same behaviour as before: live profile card,
 * Pro card hidden for Pro members, privacy/data/about entry points, logout clears the FCM token first.
 */
public class SettingsFragment extends Fragment {

    private ActivitySettingsBinding b;
    private DatabaseReference profileRef;
    private ValueEventListener profileListener;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle st) {
        b = ActivitySettingsBinding.inflate(inf, c, false);
        return b.getRoot();
    }

    @Override public void onViewCreated(@NonNull View root, @Nullable Bundle st) {
        super.onViewCreated(root, st);
        ChatlistActivity.bindOverlayPadding(requireActivity(), b.scrollRoot, 12);

        row(b.rowEditProfile, "people_outline", "Edit profile", "Name, bio, username",
                () -> startActivity(new Intent(requireContext(), EditProfileActivity.class)));
        row(b.btnPrivacy, "shield", "Privacy & Security", "Encryption, App Lock, screenshots",
                () -> startActivity(new Intent(requireContext(), PrivacySecurityActivity.class)));
        row(b.rowNotifications, "notifications_none", "Notifications", "Sounds, previews, channels",
                this::openSystemNotificationSettings);
        row(b.rowAppearance, "palette", "Appearance", "Theme, text size, bubbles, wallpaper",
                () -> startActivity(new Intent(requireContext(), AppearanceActivity.class)));
        row(b.rowLanguage, "translate", "Language & Translation", languageSummary(),
                () -> startActivity(new Intent(requireContext(), LanguageSettingsActivity.class)));
        row(b.rowModels, "memory", "Offline AI models", "Translation packs, voice clean-up",
                () -> startActivity(new Intent(requireContext(), LanguageSettingsActivity.class)));
        row(b.btnDataStorage, "data_usage", "Data & Storage", "Auto-download, clear cache",
                () -> new DataStorageSheet().show(getParentFragmentManager(), "data_storage"));
        row(b.btnAbout, "info_outline", "About Minigram", "Version, support, licences",
                () -> startActivity(new Intent(requireContext(), AboutActivity.class)));

        b.rowMyProfile.setOnClickListener(v -> startActivity(new Intent(requireContext(), MyProfileActivity.class)));
        b.btnMinigramPro.setOnClickListener(v -> startActivity(new Intent(requireContext(), SubscriptionActivity.class)));
        b.btnLogout.setOnClickListener(v -> {
            // Remove the FCM token BEFORE signing out: afterwards the uid is gone and a logged-out
            // phone would keep receiving the old account's pushes.
            FcmTokenManager.clearTokenOnLogout(requireContext());
            FirebaseAuth.getInstance().signOut();
            Intent i = new Intent(requireContext(), LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            requireActivity().finish();
        });

        b.tvDisplayAppVersion.setText("Minigram v" + appVersion());
        bindProfile();
    }

    private interface Action { void run(); }

    private void row(RowSettingBinding r, String glyph, String title, String sub, Action action) {
        r.rowIcon.setText(glyph);
        r.rowTitle.setText(title);
        r.rowSubtitle.setText(sub);
        r.getRoot().setContentDescription(title + ", " + sub);
        r.getRoot().setOnClickListener(v -> action.run());
    }

    private String languageSummary() {
        return "Auto-translate: " + (com.silu.minigram.translate.LangPrefs.auto(requireContext()) ? "ON" : "OFF") + " \u00b7 "
            + com.silu.minigram.translate.TranslationManager.displayName(
                com.silu.minigram.translate.LangPrefs.myLang(requireContext()), java.util.Locale.getDefault());
    }

    private void openSystemNotificationSettings() {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
        startActivity(i);
    }

    private void bindProfile() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;
        profileRef = FirebaseDatabase.getInstance().getReference("users").child(uid);
        profileListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded() || b == null) return;   // live listener: Glide rejects destroyed Activities
                String fullname = snap.child("fullname").getValue(String.class);
                String photo = snap.child("photo_url").getValue(String.class);
                String handle = snap.child("handle").getValue(String.class);
                String shown = (fullname != null && !fullname.isEmpty()) ? fullname : "You";
                b.txtSettingsName.setText(shown);
                if (handle != null && !handle.isEmpty()) b.txtSettingsUsername.setText("@" + handle);
                AvatarHelper.load(requireContext(), b.imgSettingsAvatar, photo, shown);
                boolean isPro = Boolean.TRUE.equals(snap.child("is_pro").getValue(Boolean.class));
                b.imgSettingsBadge.setVisibility(isPro ? View.VISIBLE : View.GONE);
                b.btnMinigramPro.setVisibility(isPro ? View.GONE : View.VISIBLE);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        profileRef.addValueEventListener(profileListener);
    }

    private String appVersion() {
        try { return requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(), 0).versionName; }
        catch (Exception e) { return "0.0"; }
    }

    @Override public void onResume() {
        super.onResume();
        if (b != null) b.rowLanguage.rowSubtitle.setText(languageSummary());
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (profileRef != null && profileListener != null) profileRef.removeEventListener(profileListener);
        b = null;
    }
}
