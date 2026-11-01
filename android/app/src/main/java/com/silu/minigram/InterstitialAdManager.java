package com.silu.minigram;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

/**
 * Frequency-capped interstitial loader/shower.
 *
 * Each instance gets its OWN counter key (e.g. "chat_exit", "image_view_exit")
 * so different screens can have independent caps without interfering with
 * each other. The ad is preloaded ahead of time (call preload() in onCreate)
 * so it's instantly ready the moment the cap threshold is hit.
 *
 * Usage:
 *   private final InterstitialAdManager exitAd = InterstitialAdManager.forChatExit();
 *
 *   onCreate()      → exitAd.preload(this);
 *   onBackPressed() → exitAd.maybeShow(this, () -> { ... actual exit logic ... });
 *
 * Debugging: filter Logcat by tag "InterstitialAd" to see exactly what's
 * happening — load success/failure (with AdMob's real error code+message),
 * why a show was skipped (cap not reached / ad not ready / user is Pro), etc.
 */
public class InterstitialAdManager {

    private static final String TAG = "InterstitialAd";
    private static final String PREFS = "minigram_ad_counters";

    // ── App-wide singletons ─────────────────────────────────────────────────
    // IMPORTANT: these must NOT be created fresh per-Activity. A ChatActivity
    // (or ChannelActivity) instance is destroyed every time the user backs
    // out, and with it any InterstitialAdManager that was a plain instance
    // field on that Activity — throwing away a perfectly good preloaded ad
    // that just hadn't finished loading in time. Real-world load times run
    // 700ms–1200ms; a user backing out faster than that (very plausible when
    // testing) meant the ad NEVER got a chance to show, no matter how many
    // times they tried, because every visit restarted the load from zero.
    // Keeping one shared instance per flow means a load begun on visit #1
    // can finish and be ready by visit #2, #3, etc. — the ad's progress
    // now survives across Activity instances for as long as the app process
    // is alive.
    private static InterstitialAdManager chatExitInstance;
    private static InterstitialAdManager channelExitInstance;
    private static InterstitialAdManager imageViewExitInstance;
    private static InterstitialAdManager channelCreatedInstance;

    public static synchronized InterstitialAdManager forChatExit() {
        if (chatExitInstance == null)
            chatExitInstance = new InterstitialAdManager("chat_exit", AdConfig.CHAT_EXIT_FREQUENCY_CAP);
        return chatExitInstance;
    }

    public static synchronized InterstitialAdManager forChannelExit() {
        if (channelExitInstance == null)
            channelExitInstance = new InterstitialAdManager("channel_exit", AdConfig.CHAT_EXIT_FREQUENCY_CAP);
        return channelExitInstance;
    }

    public static synchronized InterstitialAdManager forImageViewExit() {
        if (imageViewExitInstance == null)
            imageViewExitInstance = new InterstitialAdManager("image_view_exit", AdConfig.IMAGE_VIEW_FREQUENCY_CAP);
        return imageViewExitInstance;
    }

    public static synchronized InterstitialAdManager forChannelCreated() {
        if (channelCreatedInstance == null)
            channelCreatedInstance = new InterstitialAdManager("channel_created", 1);
        return channelCreatedInstance;
    }

    private InterstitialAd interstitialAd;
    private boolean isLoading = false;
    private final String counterKey;
    private final int frequencyCap;

    private InterstitialAdManager(String counterKey, int frequencyCap) {
        this.counterKey = counterKey;
        this.frequencyCap = Math.max(1, frequencyCap);
    }

    /**
     * Call in onCreate() so an ad is ready by the time the user might exit.
     * Always starts the actual ad fetch immediately — it does NOT wait on
     * any network round-trip first (e.g. a Pro-status check), because any
     * delay/hiccup there would silently leave no ad ready later. The Pro
     * check happens separately, only at the moment we'd actually show it.
     */
    public void preload(Context context) {
        if (interstitialAd != null || isLoading) {
            Log.d(TAG, "[" + counterKey + "] preload skipped — already have one or already loading");
            return;
        }
        isLoading = true;
        Log.d(TAG, "[" + counterKey + "] requesting ad, unit=" + AdConfig.interstitialUnitId());
        InterstitialAd.load(context, AdConfig.interstitialUnitId(),
            new AdRequest.Builder().build(),
            new InterstitialAdLoadCallback() {
                @Override public void onAdLoaded(@NonNull InterstitialAd ad) {
                    interstitialAd = ad;
                    isLoading = false;
                    Log.d(TAG, "[" + counterKey + "] ✅ loaded and ready");
                }
                @Override public void onAdFailedToLoad(@NonNull LoadAdError error) {
                    interstitialAd = null;
                    isLoading = false;
                    // Common causes: no fill (test/low traffic), invalid ad unit ID,
                    // AdMob App ID missing from AndroidManifest, no network.
                    Log.e(TAG, "[" + counterKey + "] ❌ load FAILED — code=" + error.getCode()
                        + " domain=" + error.getDomain() + " message=" + error.getMessage());
                }
            });
    }

    /**
     * Call at the moment the user performs the "exit" action this instance is
     * tied to. Increments a persisted counter; shows the ad ONLY once the
     * frequency cap is reached, the user isn't Pro, AND a preloaded ad is
     * actually ready — never makes the user wait for a network fetch.
     *
     * onDone is ALWAYS called exactly once, whether or not an ad was shown,
     * so the caller can chain its normal navigation/finish() after it.
     */
    public void maybeShow(Activity activity, Runnable onDone) {
        Log.d(TAG, "[" + counterKey + "] maybeShow() called, checking Pro status...");
        BillingManager.isCurrentUserPro(isPro -> {
            Log.d(TAG, "[" + counterKey + "] Pro check returned: isPro=" + isPro);
            if (isPro) {
                Log.d(TAG, "[" + counterKey + "] skipped — user is Pro");
                if (onDone != null) onDone.run();
                return;
            }
            _maybeShowForFreeUser(activity, onDone);
        });
    }

    private void _maybeShowForFreeUser(Activity activity, Runnable onDone) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int count = prefs.getInt(counterKey, 0) + 1;

        boolean capReached = count >= frequencyCap;
        boolean adReady = interstitialAd != null;
        Log.d(TAG, "[" + counterKey + "] exit #" + count + "/" + frequencyCap
            + " capReached=" + capReached + " adReady=" + adReady);

        if (capReached && adReady) {
            prefs.edit().putInt(counterKey, 0).apply();
            interstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override public void onAdDismissedFullScreenContent() {
                    Log.d(TAG, "[" + counterKey + "] dismissed by user");
                    interstitialAd = null;
                    preload(activity); // get the next one ready in the background
                    if (onDone != null) onDone.run();
                }
                @Override public void onAdFailedToShowFullScreenContent(@NonNull AdError e) {
                    Log.e(TAG, "[" + counterKey + "] ❌ show FAILED — " + e.getMessage());
                    interstitialAd = null;
                    preload(activity);
                    if (onDone != null) onDone.run();
                }
            });
            interstitialAd.show(activity);
        } else {
            if (capReached && !adReady) {
                Log.w(TAG, "[" + counterKey + "] cap reached but NO ad was ready — "
                    + "check earlier logs for a load failure, or preload() wasn't called in onCreate()");
                // IMPORTANT: don't reset to 0 here. A fresh InterstitialAdManager
                // is created every time the Activity is (re)created, and its
                // preload() often hasn't finished loading yet if the user exits
                // quickly — especially likely on the very exit that just hit
                // the cap. Resetting to 0 in that case meant losing all
                // progress and needing a whole new cycle of exits before the
                // ad could ever show. Instead we hold right at the threshold
                // so the NEXT exit (even from a different chat) retries
                // immediately, giving the preload a real chance to finish.
                prefs.edit().putInt(counterKey, frequencyCap - 1).apply();
                preload(activity);
                if (onDone != null) onDone.run();
                return;
            }
            // Not time yet — the ad simply isn't due, never block the user.
            prefs.edit().putInt(counterKey, count).apply();
            if (onDone != null) onDone.run();
        }
    }
}
