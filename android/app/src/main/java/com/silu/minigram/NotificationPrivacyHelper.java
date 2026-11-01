package com.silu.minigram;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * NotificationPrivacyHelper
 *
 * Stores a PER-TARGET (per channel OR per individual chat) preference that controls
 * whether the image attached to a push notification should be blurred before display.
 *
 * Why per-target and not a single global switch:
 *   Kuch channels/groups adult ya sensitive content post karte hain — user un specific
 *   channels/chats ke notification image ko blur rakhna chahta hai, lekin baaki normal
 *   channels/chats ka image clearly dikhe. Isliye key hamesha targetId (channelId ya
 *   chatId) ke saath stored hoti hai — global flag nahi.
 *
 * Storage:
 *   SharedPreferences file "minigram_notif_image_privacy"
 *   key  = "blur_" + targetId   (targetId = channelId for channel posts, chatId for DMs)
 *   value= boolean (default false → image NOT blurred by default)
 */
public final class NotificationPrivacyHelper {

    public static final String PREFS      = "minigram_notif_image_privacy";
    private static final String KEY_PREFIX = "blur_";

    private NotificationPrivacyHelper() {}

    /** Returns true if notification images for this channel/chat should be blurred. */
    public static boolean isBlurEnabled(Context context, String targetId) {
        if (context == null || targetId == null || targetId.isEmpty()) return false;
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PREFIX + targetId, false);
    }

    /** Enable/disable image blur for this specific channel/chat's notifications. */
    public static void setBlurEnabled(Context context, String targetId, boolean enabled) {
        if (context == null || targetId == null || targetId.isEmpty()) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PREFIX + targetId, enabled)
                .apply();
    }

    /** Removes the stored preference for a target (e.g. when user leaves a channel). */
    public static void clear(Context context, String targetId) {
        if (context == null || targetId == null || targetId.isEmpty()) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_PREFIX + targetId)
                .apply();
    }
}
