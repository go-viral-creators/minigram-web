package com.silu.minigram;

import android.content.Context;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdOptions;
import com.google.android.gms.ads.AdLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads and caches a small pool of native ads for ONE channel's post feed.
 *
 * Design notes:
 *   • Native ads are the only AdMob format designed to sit inline inside a
 *     content feed and adopt the app's own visual style — that's the whole
 *     point of the format. Google still REQUIRES every native ad to show
 *     an explicit "Ad" label and the AdChoices icon; both are non-negotiable
 *     parts of the format and are handled in item_channel_native_ad.xml /
 *     ChannelPostAdapter, not something this class can (or should) skip.
 *   • Channels only, by design — chat is private 1:1 messaging, a much
 *     worse (and more policy-risky) place for ads than a public feed.
 *   • Pro users never trigger a single ad request here — checked before
 *     load() does anything.
 *
 * Usage:
 *   nativeAdManager = new NativeAdManager(this);
 *   nativeAdManager.preload(4); // load a handful up front
 *   ... later, per ad slot in the adapter ...
 *   NativeAd ad = nativeAdManager.getAdForSlot(slotIndex);
 *
 *   onDestroy() → nativeAdManager.destroyAll();
 */
public class NativeAdManager {

    private static final String TAG = "NativeAdInFeed";

    private final Context context;
    private final SparseArray<NativeAd> loadedAds = new SparseArray<>();
    private final List<NativeAd> pool = new ArrayList<>();
    private boolean isPro = false;
    private boolean proChecked = false;
    private Runnable onAdsReadyCallback;

    /** Called (on the main thread) each time a new ad finishes loading into the pool. */
    public void setOnAdsReadyCallback(Runnable callback) { this.onAdsReadyCallback = callback; }

    public NativeAdManager(Context context) {
        this.context = context.getApplicationContext();
        BillingManager.isCurrentUserPro(result -> {
            isPro = result;
            proChecked = true;
        });
    }

    /** Call once when the channel screen opens. No-ops entirely for Pro users. */
    public void preload(int count) {
        if (isPro) return; // fast path if the Pro check already resolved
        BillingManager.isCurrentUserPro(result -> {
            isPro = result;
            if (isPro) {
                Log.d(TAG, "skipping — user is Pro");
                return;
            }
            AdLoader adLoader = new AdLoader.Builder(context, AdConfig.nativeUnitId())
                .forNativeAd((NativeAd nativeAd) -> {
                    pool.add(nativeAd);
                    Log.d(TAG, "loaded, pool size=" + pool.size());
                    if (onAdsReadyCallback != null) onAdsReadyCallback.run();
                })
                .withAdListener(new AdListener() {
                    @Override public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        Log.e(TAG, "load FAILED — code=" + error.getCode()
                            + " message=" + error.getMessage());
                    }
                })
                .withNativeAdOptions(new NativeAdOptions.Builder().build())
                .build();
            adLoader.loadAds(new AdRequest.Builder().build(), count);
        });
    }

    /**
     * Returns a loaded ad for this ad-slot index (stable per slot — the
     * same slot always gets the same ad instance once assigned, so
     * scrolling back up doesn't reshuffle which ad is where). Returns null
     * if nothing's loaded yet for that slot (adapter should just skip
     * rendering that slot as an ad until a real one is ready — never shows
     * a blank/broken card).
     */
    public NativeAd getAdForSlot(int slotIndex) {
        if (isPro) return null;
        NativeAd existing = loadedAds.get(slotIndex);
        if (existing != null) return existing;
        if (!pool.isEmpty()) {
            NativeAd next = pool.remove(0);
            loadedAds.put(slotIndex, next);
            return next;
        }
        return null;
    }

    public boolean isPro() { return isPro; }

    /** Call from the hosting activity's onDestroy() — native ads MUST be destroyed. */
    public void destroyAll() {
        for (int i = 0; i < loadedAds.size(); i++) {
            NativeAd ad = loadedAds.valueAt(i);
            if (ad != null) ad.destroy();
        }
        loadedAds.clear();
        for (NativeAd ad : pool) ad.destroy();
        pool.clear();
    }
}
