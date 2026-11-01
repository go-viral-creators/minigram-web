package com.silu.minigram;

/**
 * Central AdMob configuration.
 *
 * IMPORTANT: The *_TEST ids below are Google's OFFICIAL test ad unit IDs —
 * safe to use during development (they always fill, and clicking them never
 * risks your AdMob account getting flagged for invalid traffic). Before you
 * publish a release build:
 *   1. Create your ad units in the AdMob console (Banner + Interstitial).
 *   2. Paste your real IDs into *_REAL below.
 *   3. Flip USE_TEST_ADS to false.
 *
 * Shipping test IDs to production = zero revenue.
 * Shipping real IDs during development = risk of a policy strike on your
 * AdMob account from your own repeated test impressions/clicks.
 */
public class AdConfig {

    // ⚠️ USE_TEST_ADS is now FALSE — real ads will show. Do NOT tap/click
    // your own ads while testing the app from here on; repeated self-clicks
    // on real ad units risk an AdMob policy strike (can suspend your whole
    // account). Test functional flows only (does the ad load, does it show
    // at the right time) — never click through to the advertiser.
    public static final boolean USE_TEST_ADS = false;

    // ── Banner ─────────────────────────────────────────────────────────────
    public static final String BANNER_TEST = "ca-app-pub-3940256099942544/6300978111";
    public static final String BANNER_REAL = "ca-app-pub-5237566165900598/8597822077";

    // ── Interstitial ───────────────────────────────────────────────────────
    public static final String INTERSTITIAL_TEST = "ca-app-pub-3940256099942544/1033173712";
    public static final String INTERSTITIAL_REAL = "ca-app-pub-5237566165900598/5984221317";

    // ── Native (in-feed, channel posts only) ────────────────────────────────
    public static final String NATIVE_TEST = "ca-app-pub-3940256099942544/2247696110";
    public static final String NATIVE_REAL = "ca-app-pub-5237566165900598/7169310475";

    public static String nativeUnitId() {
        return USE_TEST_ADS ? NATIVE_TEST : NATIVE_REAL;
    }

    // 1 native ad after every N organic posts in a channel feed (Google's own
    // guidance + most feed apps land around 1-in-6 to 1-in-10 for good UX).
    public static final int NATIVE_AD_POST_INTERVAL = 8;

    public static String bannerUnitId() {
        return USE_TEST_ADS ? BANNER_TEST : BANNER_REAL;
    }

    public static String interstitialUnitId() {
        return USE_TEST_ADS ? INTERSTITIAL_TEST : INTERSTITIAL_REAL;
    }

    // How many "exit" events happen before an interstitial is allowed to show
    // again for that flow. e.g. 4 = show at most once every 4 chat exits.
    public static final int CHAT_EXIT_FREQUENCY_CAP  = 4;
    public static final int IMAGE_VIEW_FREQUENCY_CAP = 3;
}
