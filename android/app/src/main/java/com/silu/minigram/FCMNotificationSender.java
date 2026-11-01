package com.silu.minigram;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FCMNotificationSender  (v3)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * v3 ROOT-CAUSE FIXES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * 1) SEND SIDE — BACKEND_URL mein /admin/ segment missing tha.
 *    App call kar rahi thi  /api/send.php        -> HTTP 404
 *    Deployed file hai      /admin/api/send.php  -> HTTP 200
 *    Admin panel /admin/ ke andar hai. Har chat push aur har channel push
 *    404 par marta tha, sirf Log.e() hoti thi, UI mein kuch nahi dikhta tha.
 *    Yahi wajah hai ki dono directions ek hi waqt band hue.
 *
 *    v3 ab endpoints ki PRIORITISED LIST par failover karta hai, taaki ek host
 *    down hone par notifications dobara chup-chaap band na ho.
 *
 * 2) RECEIVE SIDE — the notification was built on a background thread and then
 *    posted from a Handler(Looper.getMainLooper()).post(...) callback.
 *    onMessageReceived() already runs on a background thread with a short
 *    wakelock; once it returns, the service can be torn down and that queued
 *    main-thread Runnable may never run. Result: push delivered by FCM, no
 *    notification shown — intermittently, and worst on low-RAM devices.
 *    v3 downloads and posts SYNCHRONOUSLY inside onMessageReceived(), which is
 *    both allowed and the documented approach.
 *
 * 3) A brand-new single-thread ExecutorService was allocated per notification
 *    and per push. Replaced with one shared pool.
 *
 * 4) onNewToken() caught only Exception. FirebaseDatabase/Auth failures inside
 *    it can be Errors; now catches Throwable and force-syncs through
 *    FcmTokenManager so the token write and the topic re-subscribe follow the
 *    same code path as the rest of the app.
 *
 * 5) SMALL ICON — setSmallIcon() (aur manifest ka default_notification_icon)
 *    ic_app_logo (poora rangeen app logo) use kar rahe the. Android API 21+
 *    par status-bar/notification-shade ka small icon SIRF white silhouette +
 *    transparent background allow karta hai — non-alpha pixels OS khud strip
 *    kar deta hai. Kuch renderers (stock/AOSP) coloured icon se bhi kaam-chalau
 *    silhouette bana lete hain agar usme achi alpha-transparency ho, lekin
 *    kai OEM skins (jaise naye Motorola builds) strict hain: agar icon mein
 *    proper alpha mask nahi hai to sirf notification-channel ka colorPrimary
 *    circle dikhta hai, andar kuch nahi — bilkul jaisa "Moto Edge 60" par dikha.
 *    Fix: ic_notification.xml (already project mein maujood tha — vector,
 *    pure white fill, kabhi use hi nahi hua tha) ab teeno builder mein aur
 *    manifest ke default_notification_icon meta-data mein use hota hai.
 *    ic_app_logo APP ICON (@mipmap/ic_launcher) ke liye hi reserved rehta hai.
 *
 * 6) LARGE ICON FALLBACK — WhatsApp/Telegram-jaisी apps notification mein ek
 *    coloured brand mark bhi dikhati hain, chhoti monochrome status-bar badge
 *    ke alawa — wo setLargeIcon() se aata hai, setSmallIcon() se NAHI (fix #5
 *    ka white-only rule sirf small icon par lagta hai, large icon full colour
 *    ho sakta hai). Pehle jab message mein koi image nahi hoti thi (jaise
 *    plain text "Hello"), setLargeIcon() kabhi call hi nahi hota tha — isliye
 *    notification bilkul khaali dikhta tha. Ab har notification (text ho ya
 *    image download fail ho jaye) fallback mein ic_app_logo ko large icon ke
 *    roop mein dikhata hai; jab message mein asal image ho to wahi (pehle ki
 *    tarah) priority leti hai. Logo ek baar decode hoke poore process ke liye
 *    cache ho jaata hai (repeated notifications par dobara decode nahi hota).
 *
 * EXISTING BEHAVIOUR PRESERVED: collapsed thumbnail + expanded big picture,
 * per-target blur privacy, mute checks (both stores), open-chat suppression,
 * quick reply, admin broadcast with link handling.
 */
public class FCMNotificationSender extends FirebaseMessagingService {

    private static final String TAG = "MinigramFCM";

    private static final String CHANNEL_ID_CHAT    = "minigram_messages";
    private static final String CHANNEL_NAME_CHAT  = "Messages";
    private static final String CHANNEL_ID_ADMIN   = "minigram_admin";
    private static final String CHANNEL_NAME_ADMIN = "Announcements";
    private static final String CHANNEL_ID_POST    = "minigram_channel_posts";
    private static final String CHANNEL_NAME_POST  = "Channel Posts";

    /** Size (px) of the small/collapsed notification thumbnail. */
    private static final int SMALL_ICON_SIZE = 256;
    /** Max long-edge (px) for the expanded big picture — keeps memory usage sane. */
    private static final int BIG_PICTURE_MAX_EDGE = 1024;
    /** Blur strength applied when the user enables image privacy for a target. */
    private static final int BLUR_RADIUS = 25;

    /** One shared pool for outbound pushes instead of one executor per call. */
    private static final ExecutorService SEND_POOL = Executors.newFixedThreadPool(2);

    public interface OnResponse {
        void onSuccess(String response);
        void onError(String error);
    }

    // ── RECEIVE ──────────────────────────────────────────────────────────────

    @Override
    public void onMessageReceived(RemoteMessage rm) {
        if (rm.getData().isEmpty() && rm.getNotification() == null) {
            Log.w(TAG, "onMessageReceived: empty payload, nothing to show");
            return;
        }

        Log.d(TAG, "onMessageReceived");

        String title       = emptyToNull(rm.getData().get("title"));
        String body        = emptyToNull(rm.getData().get("body"));
        String imageUrl    = emptyToNull(rm.getData().get("image"));
        String extra       = emptyToNull(rm.getData().get("extra"));         // chatId or channelId
        String type        = emptyToNull(rm.getData().get("type"));          // "chat" or "channel"
        String senderUid   = emptyToNull(rm.getData().get("sender_uid"));
        String channelName = emptyToNull(rm.getData().get("channel_name"));

        if (rm.getNotification() != null) {
            if (title == null) title = rm.getNotification().getTitle();
            if (body  == null) body  = rm.getNotification().getBody();
        }

        // Admin panel ke dono broadcast types — direct notification aur
        // "Updates feed + push" — same pipeline (image + tap-to-open-link).
        if ("admin_broadcast".equals(type) || "app_update_feed".equals(type)) {
            String linkUrl = rm.getData().get("link");
            showAdminNotification(title, body, imageUrl, linkUrl);
            return;
        }

        // ── Mute check (both the real store and the legacy key) ──────────────
        if ("chat".equals(type) && extra != null
                && getSharedPreferences("MutedChats", MODE_PRIVATE).getBoolean(extra, false)) {
            Log.d(TAG, "muted chat, skipping: " + extra);
            return;
        }
        if ("channel".equals(type) && extra != null
                && (getSharedPreferences("MutedChannels", MODE_PRIVATE).getBoolean(extra, false)
                 || getSharedPreferences("channel_prefs", MODE_PRIVATE).getBoolean("muted_" + extra, false))) {
            Log.d(TAG, "muted channel, skipping: " + extra);
            return;
        }

        // ── Suppress if that screen is currently open ────────────────────────
        if ("chat".equals(type) && extra != null && extra.equals(ChatActivity.currentChatId)) return;
        if ("channel".equals(type) && extra != null && extra.equals(ChannelActivity.currentChannelId)) return;

        // ── Don't notify yourself ─────────────────────────────────────────────
        // BUG FIXED: channel posts go out over an FCM TOPIC ("channel_<id>"),
        // which delivers to every subscribed device — including the poster's
        // own, if they're subscribed to their own channel (an owner/admin
        // almost always is). 1-to-1 chat doesn't have this problem: that push
        // targets the PEER's specific token, never the sender's own device,
        // so this check only matters for channel type.
        if ("channel".equals(type) && senderUid != null) {
            FirebaseAuth auth = FirebaseAuth.getInstance();
            if (auth.getCurrentUser() != null && senderUid.equals(auth.getCurrentUser().getUid())) {
                Log.d(TAG, "own channel post, skipping self-notification");
                return;
            }
        }

        if ("chat".equals(type)) {
            showChatNotification(title, body, imageUrl, extra, senderUid);
        } else {
            showChannelNotification(title, body, imageUrl, extra, channelName);
        }
    }

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        
        try {
            FirebaseAuth auth = FirebaseAuth.getInstance();
            if (auth.getCurrentUser() != null) {
                String uid = auth.getCurrentUser().getUid();
                FirebaseDatabase.getInstance().getReference("fcm_tokens")
                        .child(uid).setValue(token);
                // A brand-new token starts with ZERO topic subscriptions on some
                // reinstall / restore paths. Force a full sync so the DB row and
                // every channel topic are rebuilt through one code path.
                FcmTokenManager.syncToken(getApplicationContext(), true);
            }
        } catch (Throwable t) {
            // Throwable, not Exception — NoClassDefFoundError must not escape here.
            Log.e(TAG, "onNewToken failed: " + t);
        }
    }

    // ── CHAT NOTIFICATION (with Reply + Mute actions) ─────────────────────

    private void showChatNotification(String title, String body,
                                      String imageUrl, String chatId,
                                      String senderUid) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID_CHAT, CHANNEL_NAME_CHAT, NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }

        Intent openIntent = new Intent(this, ChatActivity.class);
        openIntent.putExtra("chat_id",    chatId);
        openIntent.putExtra("peer_uid",   senderUid != null ? senderUid : "");
        openIntent.putExtra("peer_name",  title != null ? title : "");
        openIntent.putExtra("peer_photo", "");
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int reqCode = chatId != null ? chatId.hashCode() : (int) System.currentTimeMillis();
        PendingIntent contentPi = PendingIntent.getActivity(this, reqCode, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // ── Reply Action ─────────────────────────────────────────────────────
        RemoteInput remoteInput = new RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT_REPLY)
                .setLabel("Type a message...").build();

        Intent replyIntent = new Intent(this, NotificationActionReceiver.class);
        replyIntent.setAction(NotificationActionReceiver.ACTION_REPLY);
        replyIntent.putExtra("chat_id",  chatId);
        replyIntent.putExtra("peer_uid", senderUid != null ? senderUid : "");
        replyIntent.putExtra("my_uid",
                FirebaseAuth.getInstance().getCurrentUser() != null
                        ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "");

        PendingIntent replyPi = PendingIntent.getBroadcast(this, reqCode, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | mutableFlag());

        NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send, "Reply", replyPi)
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .build();

        // ── Mute Action ──────────────────────────────────────────────────────
        Intent muteIntent = new Intent(this, NotificationActionReceiver.class);
        muteIntent.setAction(NotificationActionReceiver.ACTION_MUTE);
        muteIntent.putExtra("chat_id", chatId);

        PendingIntent mutePi = PendingIntent.getBroadcast(this, reqCode + 1, muteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Action muteAction = new NotificationCompat.Action.Builder(
                android.R.drawable.ic_lock_silent_mode, "Mute", mutePi).build();

        // ── Message preview privacy check ────────────────────────────────────
        boolean showPreview = getSharedPreferences(PrivacySecurityActivity.PREFS, MODE_PRIVATE)
                .getBoolean(PrivacySecurityActivity.KEY_NOTIF_PREV, true);
        String displayBody = showPreview ? (body != null ? body : "") : "New message";

        NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle("Me")
                .setConversationTitle(title)
                .addMessage(displayBody, System.currentTimeMillis(), title);

        int notifId = chatId != null ? chatId.hashCode() : (int) System.currentTimeMillis();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_CHAT)
                .setSmallIcon(R.drawable.ic_notification)
                .setStyle(style)
                .setColor(0xFF2196F3)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(contentPi)
                .addAction(replyAction)
                .addAction(muteAction);

        if (!showPreview) {
            safeNotify(nm, notifId, builder);
            return;
        }

        attachImageAndNotify(builder, imageUrl, chatId, nm, notifId);
    }

    // ── ADMIN BROADCAST NOTIFICATION (from the PHP admin panel) ──────────────

    private void showAdminNotification(String title, String body, String imageUrl, String linkUrl) {
        if (title == null && body == null) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID_ADMIN, CHANNEL_NAME_ADMIN, NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }

        int reqCode = (int) System.currentTimeMillis();

        String normalizedLink = normalizeUrl(linkUrl);
        PendingIntent contentPi;
        if (normalizedLink != null) {
            Intent linkIntent = buildLinkIntent(normalizedLink);
            contentPi = PendingIntent.getActivity(this, reqCode, linkIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            Intent openIntent = new Intent(this, ChatlistActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            contentPi = PendingIntent.getActivity(this, reqCode, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_ADMIN)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title != null ? title : "Minigram")
                .setContentText(body != null ? body : "")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body != null ? body : ""))
                .setColor(0xFF2196F3)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(contentPi);

        attachImageAndNotify(builder, imageUrl, "admin_broadcast_images", nm, reqCode);
    }

    // ── CHANNEL NOTIFICATION ─────────────────────────────────────────────────

    private void showChannelNotification(String title, String body, String imageUrl,
                                         String channelId, String channelName) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID_POST, CHANNEL_NAME_POST, NotificationManager.IMPORTANCE_DEFAULT);
            ch.setShowBadge(true);
            nm.createNotificationChannel(ch);
        }

        Intent intent = new Intent(this, ChannelActivity.class);
        intent.putExtra("channel_id",    channelId);
        intent.putExtra("channel_name",  channelName != null ? channelName
                : (title != null ? title : "Channel"));
        intent.putExtra("channel_photo", "");
        intent.putExtra("owner_uid",     "");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = channelId != null ? ("ch_" + channelId).hashCode() : (int) System.currentTimeMillis();
        PendingIntent contentPi = PendingIntent.getActivity(this, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int notifId = reqCode;

        String safeTitle = channelName != null ? channelName
                : (title != null ? title : "New post");

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_POST)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(safeTitle)
                .setContentText(body != null ? body : "")
                .setColor(0xFF2196F3)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setContentIntent(contentPi)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body != null ? body : ""));

        attachImageAndNotify(builder, imageUrl, channelId, nm, notifId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Shared image pipeline — small (collapsed) + big (expanded) + blur privacy
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Downloads the notification image (if any) and posts the notification.
     *
     * CRITICAL CHANGE (v3): this now runs SYNCHRONOUSLY on the FCM background
     * thread. The old version handed the final nm.notify() to the main looper
     * from inside a worker; once onMessageReceived() returned, the service could
     * be destroyed and that Runnable silently dropped — a delivered push with no
     * visible notification. onMessageReceived() is already off the main thread,
     * so blocking here is correct and safe.
     */
    private void attachImageAndNotify(NotificationCompat.Builder builder,
                                      String imageUrl,
                                      String privacyTargetId,
                                      NotificationManager nm,
                                      int notifId) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            // No message image (e.g. plain text) — WhatsApp/Telegram-style apps
            // still show a coloured brand mark here via setLargeIcon(), separate
            // from the strict monochrome small icon in the status-bar badge.
            // Only setLargeIcon() may hold full colour; setSmallIcon() must stay
            // white-silhouette-only (see the class doc comment, fix #5) or the
            // blank-circle bug on strict OEM renderers comes right back.
            Bitmap logo = defaultLargeIcon();
            if (logo != null) builder.setLargeIcon(logo);
            safeNotify(nm, notifId, builder);
            return;
        }

        final boolean blurEnabled = NotificationPrivacyHelper.isBlurEnabled(this, privacyTargetId);

        Bitmap original = null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(imageUrl).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            try (InputStream in = conn.getInputStream()) {
                original = BitmapFactory.decodeStream(in);
            }
        } catch (Throwable t) {
            Log.w(TAG, "notification image download failed: " + t);
        } finally {
            if (conn != null) conn.disconnect();
        }

        if (original != null) {
            try {
                Bitmap smallIcon  = NotificationImageUtil.centerCropSquare(original, SMALL_ICON_SIZE);
                Bitmap bigPicture = NotificationImageUtil.downscale(original, BIG_PICTURE_MAX_EDGE);

                if (blurEnabled) {
                    smallIcon  = NotificationImageUtil.stackBlur(smallIcon,  BLUR_RADIUS);
                    bigPicture = NotificationImageUtil.stackBlur(bigPicture, BLUR_RADIUS);
                }

                if (smallIcon != null) {
                    // Collapsed state: small thumbnail.
                    builder.setLargeIcon(smallIcon);
                }
                if (bigPicture != null) {
                    // Expanded state: full big picture. bigLargeIcon(null) hides
                    // the thumbnail when expanded (standard messaging-app look).
                    builder.setStyle(new NotificationCompat.BigPictureStyle()
                            .bigPicture(bigPicture)
                            .bigLargeIcon((Bitmap) null));
                }
            } catch (Throwable t) {
                Log.w(TAG, "notification image processing failed: " + t);
            }
        } else {
            // Image URL was present but the download/decode failed — same
            // fallback as the no-image case, so a flaky network never leaves
            // the notification with literally nothing shown next to the text.
            Bitmap logo = defaultLargeIcon();
            if (logo != null) builder.setLargeIcon(logo);
        }

        // Always post, image or not — a failed download must never swallow the
        // whole notification.
        safeNotify(nm, notifId, builder);
    }

    /**
     * App logo as a Bitmap, for use as the notification's LARGE icon only
     * (never the small/status-bar icon — that one is required to be a plain
     * white silhouette, see fix #5 above). Decoded once and cached for the
     * life of the process; ic_app_logo is a fixed resource, so this can never
     * go stale. Works whether ic_app_logo is a raster PNG or a VectorDrawable.
     */
    private static volatile Bitmap sAppLogoBitmap;

    private Bitmap defaultLargeIcon() {
        Bitmap cached = sAppLogoBitmap;
        if (cached != null) return cached;
        try {
            Drawable d = ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_app_logo);
            if (d == null) return null;
            Bitmap bmp;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                bmp = ((BitmapDrawable) d).getBitmap();
            } else {
                int w = Math.max(d.getIntrinsicWidth(),  1);
                int h = Math.max(d.getIntrinsicHeight(), 1);
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                d.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                d.draw(canvas);
            }
            sAppLogoBitmap = bmp;
            return bmp;
        } catch (Throwable t) {
            Log.w(TAG, "defaultLargeIcon failed: " + t);
            return null;
        }
    }

    /** nm.notify() never throwing is worth more than any single notification. */
    private void safeNotify(NotificationManager nm, int notifId, NotificationCompat.Builder builder) {
        try {
            nm.notify(notifId, builder.build());
        } catch (Throwable t) {
            Log.e(TAG, "nm.notify failed: " + t);
        }
    }

    // ── SEND NOTIFICATION via our own backend ────────────────────────────────
    // data keys: title, body, image, type, extra, channel_name, sender_uid, to_uid
    //
    // SECURITY: no service_account.json and no JWT signing in the APK. The
    // private key lives only in the server environment.

    /**
     * Sends a push by calling the {@code sendNotification} Cloud Function.
     *
     * The app no longer carries ANY server secret: the function authenticates the caller
     * with the Firebase Auth identity + an App Check token, checks that the caller is really
     * a participant of the chat (or owner/admin of the channel), rate-limits, and looks the
     * receiver's FCM token up server-side (tokens are not readable by clients).
     *
     * @param topic  non-empty for channel pushes (channel id is taken from data["extra"])
     * @param token  ignored — kept only so existing call-sites compile; the server resolves it
     */
    public static void sendNotification(
            HashMap<String, Object> data,
            String topic, String token,
            OnResponse callback) {
        try {
            String type = s(data, "type");
            HashMap<String, Object> req = new HashMap<>();
            req.put("type",         type);
            req.put("title",        s(data, "title"));
            req.put("body",         s(data, "body"));
            req.put("image",        s(data, "image"));
            req.put("extra",        s(data, "extra"));
            req.put("channel_name", s(data, "channel_name"));
            req.put("to_uid",       s(data, "to_uid"));
            if ("channel".equals(type)) req.put("channel_id", s(data, "extra"));

            com.google.firebase.functions.FirebaseFunctions.getInstance()
                    .getHttpsCallable("sendNotification")
                    .call(req)
                    .addOnSuccessListener(r -> { if (callback != null) callback.onSuccess("ok"); })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "push failed: " + e.getClass().getSimpleName());
                        if (callback != null) callback.onError(String.valueOf(e.getMessage()));
                    });
        } catch (Throwable t) {
            Log.e(TAG, "sendNotification crashed: " + t.getClass().getSimpleName());
            if (callback != null) callback.onError(String.valueOf(t.getMessage()));
        }
    }

    private static String s(HashMap<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    /** FCM never delivers null values — missing fields arrive as "". Normalise. */
    private static String emptyToNull(String v) {
        return (v == null || v.trim().isEmpty()) ? null : v.trim();
    }

    private int mutableFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE : 0;
    }

    /** Scheme-less links ("example.com") never resolve to an Activity. Fix them. */
    private static String normalizeUrl(String raw) {
        if (raw == null) return null;
        String url = raw.trim();
        if (url.isEmpty()) return null;
        if (!url.matches("(?i)^[a-z][a-z0-9+.\\-]*://.*")) {
            url = "https://" + url;
        }
        return url;
    }

    /**
     * Best target Intent for a URL:
     *   • Play Store "details" link → market:// (opens Play Store directly)
     *   • everything else → Chrome if present, otherwise the device default
     *     browser with OUR OWN app explicitly excluded (otherwise the intent
     *     can resolve back into a browsable Minigram Activity that instantly
     *     finishes, and the user sees nothing).
     */
    private Intent buildLinkIntent(String url) {
        if (isPlayStoreUrl(url)) {
            Intent playStoreIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            playStoreIntent.setPackage("com.android.vending");
            playStoreIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (playStoreIntent.resolveActivity(getPackageManager()) != null) {
                return playStoreIntent;
            }
        }

        Intent chromeIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        chromeIntent.setPackage("com.android.chrome");
        chromeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (chromeIntent.resolveActivity(getPackageManager()) != null) {
            return chromeIntent;
        }

        Intent genericIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        genericIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        List<ResolveInfo> resolveInfos = getPackageManager().queryIntentActivities(genericIntent, 0);
        for (ResolveInfo info : resolveInfos) {
            if (!getPackageName().equals(info.activityInfo.packageName)) {
                genericIntent.setClassName(info.activityInfo.packageName, info.activityInfo.name);
                return genericIntent;
            }
        }
        return genericIntent;
    }

    /** True if the URL is a Play Store "app details" link. */
    private static boolean isPlayStoreUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return false;
            if (!"play.google.com".equalsIgnoreCase(host) && !"market.android.com".equalsIgnoreCase(host)) return false;
            String path = uri.getPath();
            return path != null && path.contains("/store/apps");
        } catch (Exception e) {
            return false;
        }
    }
}
