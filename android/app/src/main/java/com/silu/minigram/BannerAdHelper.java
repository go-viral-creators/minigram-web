package com.silu.minigram;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;

import androidx.annotation.NonNull;

/**
 * Attaches a bottom banner ad to any activity WITHOUT needing to edit that
 * activity's XML layout.
 *
 * How it works: it takes whatever setContentView() already put on screen,
 * wraps it in a new outer vertical LinearLayout, gives the ORIGINAL content
 * weight=1 (so it keeps filling all remaining space exactly as before, no
 * matter what layout type its root was — LinearLayout, RelativeLayout,
 * ConstraintLayout...), and adds the banner as a fixed-height row underneath.
 *
 * Call this ONCE, right after setContentView(...), in onCreate():
 *   bannerAd = BannerAdHelper.attachBottomBanner(this);
 *
 * Then forward lifecycle calls from the activity:
 *   onResume()  → if (bannerAd != null) bannerAd.resume();
 *   onPause()   → if (bannerAd != null) bannerAd.pause();
 *   onDestroy() → if (bannerAd != null) bannerAd.destroy();
 *
 * Debugging: filter Logcat by tag "BannerAd" to see load success/failure
 * with AdMob's real error code+message.
 */
public class BannerAdHelper {

    private static final String TAG = "BannerAd";

    public static AdView attachBottomBanner(Activity activity) {
        return attachBottomBanner(activity, AdConfig.bannerUnitId());
    }

    public static AdView attachBottomBanner(Activity activity, String adUnitId) {
        ViewGroup androidContent = activity.findViewById(android.R.id.content);
        if (androidContent == null || androidContent.getChildCount() == 0) return null;

        View originalRoot = androidContent.getChildAt(0);
        androidContent.removeView(originalRoot);

        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams rootParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wrapper.addView(originalRoot, rootParams);

        AdView adView = new AdView(activity);
        adView.setAdUnitId(adUnitId);
        adView.setAdSize(AdSize.BANNER);
        LinearLayout.LayoutParams adParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        adView.setLayoutParams(adParams);
        wrapper.addView(adView);

        androidContent.addView(wrapper);

        // Edge-to-edge fix: this wrapper is a NEW element inserted below
        // whatever the activity's own EdgeToEdgeHelper.applyInsets() already
        // padded — that call only knew about the ORIGINAL content, not this
        // banner row added afterward, so without this the banner rendered
        // underneath the navigation bar on every screen that uses it. Only
        // bottom padding is applied here (not top/left/right) since the
        // original content already handles those itself; this only needs to
        // protect the new bottom-most element — the ad.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(wrapper,
            (v, insets) -> {
                androidx.core.graphics.Insets sysBars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars());
                v.setPadding(0, 0, 0, sysBars.bottom);
                return insets;
            });

        adView.setAdListener(new AdListener() {
            @Override public void onAdLoaded() {
                Log.d(TAG, "✅ loaded, unit=" + adUnitId);
            }
            @Override public void onAdFailedToLoad(@NonNull LoadAdError error) {
                // Common causes: no fill (test/low traffic), invalid ad unit ID,
                // AdMob App ID missing from AndroidManifest, no network.
                Log.e(TAG, "❌ load FAILED — code=" + error.getCode()
                    + " domain=" + error.getDomain() + " message=" + error.getMessage());
            }
        });

        // Request the ad immediately — don't block it on the async Pro-status
        // check below, since any delay/hiccup there would just mean the ad
        // never even starts loading.
        adView.loadAd(new AdRequest.Builder().build());

        // REALTIME Pro gating: a live listener on our own is_pro flag, so the
        // instant the admin grants (or removes) Pro from the panel — or a
        // purchase completes — the banner disappears/reappears without the
        // user having to reopen the screen. Listener is detached when the ad
        // view leaves the window (activity destroyed) to avoid leaks.
        String myUid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
        if (myUid != null) {
            final com.google.firebase.database.DatabaseReference proRef =
                com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("users").child(myUid).child("is_pro");
            final com.google.firebase.database.ValueEventListener proListener =
                new com.google.firebase.database.ValueEventListener() {
                    @Override public void onDataChange(
                            @NonNull com.google.firebase.database.DataSnapshot snap) {
                        boolean isPro = Boolean.TRUE.equals(snap.getValue(Boolean.class));
                        Log.d(TAG, isPro ? "hiding — user is Pro" : "showing — user is not Pro");
                        adView.setVisibility(isPro ? View.GONE : View.VISIBLE);
                    }
                    @Override public void onCancelled(
                            @NonNull com.google.firebase.database.DatabaseError e) {}
                };
            proRef.addValueEventListener(proListener);
            adView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {}
                @Override public void onViewDetachedFromWindow(View v) {
                    proRef.removeEventListener(proListener);
                }
            });
        }

        return adView;
    }
}
