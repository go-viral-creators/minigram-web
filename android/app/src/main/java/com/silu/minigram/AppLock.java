package com.silu.minigram;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * Process-wide app lock + window security.
 *  - Re-locks after the app has been in the background for more than GRACE_MS.
 *  - FLAG_SECURE: always on for the PIN screen and encryption-verification screen, on for
 *    every screen while App Lock is enabled (hides the recents thumbnail), and for all
 *    screens when "Screenshot protection" is switched on.
 */
final class AppLock implements Application.ActivityLifecycleCallbacks {
    private static final long GRACE_MS = 30_000;
    private static volatile boolean unlocked = false;
    private int started = 0;
    private long backgroundedAt = 0;

    static void markUnlocked() { unlocked = true; }

    static void install(Application app) { app.registerActivityLifecycleCallbacks(new AppLock()); }

    private static boolean lockOn(Context c) {
        return c.getSharedPreferences(PrivacySecurityActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(PrivacySecurityActivity.KEY_APP_LOCK, false) && PinVault.hasPin(c);
    }

    private static boolean always(Activity a) {
        return a instanceof PinLockActivity || a.getClass().getSimpleName().equals("VerifyEncryptionActivity");
    }

    @Override public void onActivityCreated(Activity a, Bundle b) {
        boolean shot = a.getSharedPreferences(PrivacySecurityActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(PrivacySecurityActivity.KEY_SCREENSHOT, false);
        if (always(a) || shot || lockOn(a)) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    @Override public void onActivityStarted(Activity a) {
        if (started++ == 0 && backgroundedAt != 0 && System.currentTimeMillis() - backgroundedAt > GRACE_MS) {
            unlocked = false;
        }
        boolean gate = !(a instanceof PinLockActivity) && !(a instanceof MainActivity)
                && !(a instanceof LoginActivity) && !(a instanceof DebugActivity);
        if (gate && !unlocked && lockOn(a)) {
            Intent i = new Intent(a, PinLockActivity.class);
            i.putExtra(PinLockActivity.EXTRA_MODE, PinLockActivity.MODE_VERIFY);
            a.startActivity(i);
        }
    }

    @Override public void onActivityStopped(Activity a) {
        if (--started <= 0) { started = 0; backgroundedAt = System.currentTimeMillis(); }
    }

    @Override public void onActivityResumed(Activity a) {}
    @Override public void onActivityPaused(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) {}
}
