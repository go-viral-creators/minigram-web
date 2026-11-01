package com.silu.minigram;

import android.util.Log;

import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.AppCheckProviderFactory;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;

/**
 * Installs Firebase App Check. Release builds attest with Play Integrity.
 * For side-loaded test builds, build with -PappCheckDebug=true: the debug provider (if its
 * library is on the classpath) prints a one-time token in logcat that you register in
 * Firebase console -> App Check -> Manage debug tokens.
 */
final class AppCheckInstaller {
    private AppCheckInstaller() {}

    static void install() {
        try {
            AppCheckProviderFactory factory = null;
            if (BuildConfig.APP_CHECK_DEBUG) {
                try {
                    Class<?> c = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory");
                    factory = (AppCheckProviderFactory) c.getMethod("getInstance").invoke(null);
                } catch (Throwable t) {
                    Log.w("AppCheck", "debug provider not on classpath, using Play Integrity");
                }
            }
            if (factory == null) factory = PlayIntegrityAppCheckProviderFactory.getInstance();
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(factory);
            FirebaseAppCheck.getInstance().setTokenAutoRefreshEnabled(true);
        } catch (Throwable t) {
            Log.e("AppCheck", "install failed: " + t.getClass().getSimpleName());
        }
    }
}
