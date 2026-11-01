package com.silu.minigram;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * NotificationPermissionHelper
 *
 * ROOT CAUSE #2 OF "NOTIFICATION NAHI AA RAHA":
 * On Android 13 (API 33) and above, POST_NOTIFICATIONS is a RUNTIME permission.
 * If it is not granted, NotificationManager.notify() succeeds silently and
 * NOTHING is shown to the user. The push is delivered, onMessageReceived()
 * runs, and still no notification appears — which is exactly the symptom of
 * "sab kuch sahi lag raha hai par notification nahi dikh raha".
 *
 * The project never requested this permission anywhere, so every Android 13+
 * device that dismissed (or never saw) the system prompt gets zero
 * notifications.
 *
 * USAGE — call once from your launcher / home Activity onCreate():
 *     NotificationPermissionHelper.ensure(this);
 *
 * AndroidManifest.xml must also contain:
 *     <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
 */
public final class NotificationPermissionHelper {

    private static final String TAG = "MinigramFCM";
    public  static final int REQ_POST_NOTIFICATIONS = 9911;

    private NotificationPermissionHelper() { }

    /** True when the OS will actually display our notifications. */
    public static boolean canShowNotifications(Context ctx) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT < 33) return true;
        return ContextCompat.checkSelfPermission(ctx, "android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Requests POST_NOTIFICATIONS when needed. On devices where the user
     * already denied it permanently (or turned notifications off in Settings),
     * shows a short explanation with a shortcut to the app's notification
     * settings — the OS will not show the dialog a second time.
     */
    public static void ensure(final Activity activity) {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(activity, "android.permission.POST_NOTIFICATIONS")
                   != PackageManager.PERMISSION_GRANTED) {

            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, "android.permission.POST_NOTIFICATIONS")) {
                new AlertDialog.Builder(activity)
                        .setTitle("Turn on notifications")
                        .setMessage("Allow notifications so you never miss messages and channel posts.")
                        .setPositiveButton("Allow", (d, w) -> ActivityCompat.requestPermissions(
                                activity, new String[]{"android.permission.POST_NOTIFICATIONS"},
                                REQ_POST_NOTIFICATIONS))
                        .setNegativeButton("Not now", null)
                        .show();
            } else {
                ActivityCompat.requestPermissions(activity,
                        new String[]{"android.permission.POST_NOTIFICATIONS"},
                        REQ_POST_NOTIFICATIONS);
            }
            return;
        }

        // Permission granted (or pre-33) but the user disabled notifications
        // for the whole app in system settings.
        if (!NotificationManagerCompat.from(activity).areNotificationsEnabled()) {
            Log.w(TAG, "notifications are disabled for this app in system settings");
            new AlertDialog.Builder(activity)
                    .setTitle("Notifications are off")
                    .setMessage("Notifications for this app are turned off in system settings. "
                              + "Turn them on to receive messages and channel posts.")
                    .setPositiveButton("Open settings", (d, w) -> openAppNotificationSettings(activity))
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    /** Opens this app's notification settings screen, with a legacy fallback. */
    public static void openAppNotificationSettings(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        }
    }
}
