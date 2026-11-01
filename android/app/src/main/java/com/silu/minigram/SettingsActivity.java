package com.silu.minigram;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

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
public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding b;
    private DatabaseReference profileRef;
    private ValueEventListener profileListener;
    private com.google.android.gms.ads.AdView bannerAd;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        b = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        EdgeToEdgeHelper.applyInsets(b.main);

        InkNav.bind(this, b.bottomNav.getRoot(), InkNav.SETTINGS);

        row(b.rowEditProfile, "people_outline", "Edit profile", "Name, bio, username",
                () -> startActivity(new Intent(this, EditProfileActivity.class)));
        row(b.btnPrivacy, "shield", "Privacy & Security", "Encryption, App Lock, screenshots",
                () -> startActivity(new Intent(this, PrivacySecurityActivity.class)));
        row(b.rowNotifications, "notifications_none", "Notifications", "Sounds, previews, channels",
                this::openSystemNotificationSettings);
        row(b.rowAppearance, "palette", "Appearance", "Theme, text size, bubbles, wallpaper",
                () -> startActivity(new Intent(this, AppearanceActivity.class)));
        row(b.rowLanguage, "translate", "Language & Translation", languageSummary(),
                () -> startActivity(new Intent(this, LanguageSettingsActivity.class)));
        row(b.rowModels, "memory", "Offline AI models", "Translation packs, voice clean-up",
                () -> startActivity(new Intent(this, LanguageSettingsActivity.class)));
        row(b.btnDataStorage, "data_usage", "Data & Storage", "Auto-download, clear cache",
                () -> new DataStorageSheet().show(getSupportFragmentManager(), "data_storage"));
        row(b.btnAbout, "info_outline", "About Minigram", "Version, support, licences",
                () -> startActivity(new Intent(this, AboutActivity.class)));

        b.rowMyProfile.setOnClickListener(v -> startActivity(new Intent(this, MyProfileActivity.class)));
        b.btnMinigramPro.setOnClickListener(v -> startActivity(new Intent(this, SubscriptionActivity.class)));
        b.btnLogout.setOnClickListener(v -> {
            // Remove the FCM token BEFORE signing out: afterwards the uid is gone and a logged-out
            // phone would keep receiving the old account's pushes.
            FcmTokenManager.clearTokenOnLogout(this);
            FirebaseAuth.getInstance().signOut();
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        });

        b.tvDisplayAppVersion.setText("Minigram v" + appVersion());
        bindProfile();
        bannerAd = BannerAdHelper.attachBottomBanner(this);
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
        return "Auto-translate: " + (com.silu.minigram.translate.LangPrefs.auto(this) ? "ON" : "OFF") + " \u00b7 "
            + com.silu.minigram.translate.TranslationManager.displayName(
                com.silu.minigram.translate.LangPrefs.myLang(this), java.util.Locale.getDefault());
    }

    private void openSystemNotificationSettings() {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(i);
    }

    private void bindProfile() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;
        profileRef = FirebaseDatabase.getInstance().getReference("users").child(uid);
        profileListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;   // live listener: Glide rejects destroyed Activities
                String fullname = snap.child("fullname").getValue(String.class);
                String photo = snap.child("photo_url").getValue(String.class);
                String handle = snap.child("handle").getValue(String.class);
                String shown = (fullname != null && !fullname.isEmpty()) ? fullname : "You";
                b.txtSettingsName.setText(shown);
                if (handle != null && !handle.isEmpty()) b.txtSettingsUsername.setText("@" + handle);
                AvatarHelper.load(SettingsActivity.this, b.imgSettingsAvatar, photo, shown);
                boolean isPro = Boolean.TRUE.equals(snap.child("is_pro").getValue(Boolean.class));
                b.imgSettingsBadge.setVisibility(isPro ? View.VISIBLE : View.GONE);
                b.btnMinigramPro.setVisibility(isPro ? View.GONE : View.VISIBLE);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        profileRef.addValueEventListener(profileListener);
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "0.0"; }
    }

    @Override protected void onResume() {
        super.onResume();
        b.rowLanguage.rowSubtitle.setText(languageSummary());
        if (bannerAd != null) bannerAd.resume();
    }
    @Override protected void onPause()  { super.onPause();  if (bannerAd != null) bannerAd.pause(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (bannerAd != null) bannerAd.destroy();
        if (profileRef != null && profileListener != null) profileRef.removeEventListener(profileListener);
    }
}
