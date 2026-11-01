package com.silu.minigram;

import android.app.Activity;

import com.google.android.gms.ads.MobileAds;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ads are initialised ONLY after the Google UMP consent flow says ads may be requested
 * (GDPR / UK / US-state rules). The app shows ads, so it does use the advertising ID:
 * do not describe it as "tracking-free".
 */
final class ConsentHelper {
    private static final AtomicBoolean adsStarted = new AtomicBoolean(false);
    private ConsentHelper() {}

    static void gatherThenInitAds(Activity a) {
        ConsentInformation ci = UserMessagingPlatform.getConsentInformation(a);
        ci.requestConsentInfoUpdate(a, new ConsentRequestParameters.Builder().build(),
            () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(a, err -> maybeInit(a, ci)),
            err -> maybeInit(a, ci));
        maybeInit(a, ci);   // consent from a previous session may already allow ads
    }

    private static void maybeInit(Activity a, ConsentInformation ci) {
        if (ci.canRequestAds() && adsStarted.compareAndSet(false, true)) {
            MobileAds.initialize(a.getApplicationContext(), s -> {});
        }
    }
}
