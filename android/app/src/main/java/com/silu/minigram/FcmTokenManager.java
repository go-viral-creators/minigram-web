package com.silu.minigram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.HashMap;
import java.util.Map;

/**
 * FcmTokenManager  (v3 — firebase-messaging 19.0.0 / firebase-iid 19.0.0)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SDK VERSION LOCK — ISKO PADHE BINA API MAT BADALNA
 * ─────────────────────────────────────────────────────────────────────────────
 * Ye file JAAN-BOOJH KAR `FirebaseInstanceId.getInstance().getInstanceId()`
 * use karti hai. Sketchware Pro jo jars auto-add karta hai unke saath yehi
 * SAHI API hai. Maine 19.0.0 ke AAR khol kar verify kiya:
 *
 *   firebase-messaging-19.0.0.aar → FirebaseMessaging ke methods:
 *       getInstance, isAutoInitEnabled, setAutoInitEnabled,
 *       subscribeToTopic, unsubscribeFromTopic, send
 *       ❌ getToken() HAI HI NAHI — wo 22.0.0 mein add hua tha.
 *
 *   firebase-iid-19.0.0.aar → FirebaseInstanceId ke methods:
 *       getInstance, getInstanceId, getToken, getId,
 *       deleteToken, deleteInstanceId                     ✅
 *
 * Yani agar koi `FirebaseMessaging.getInstance().getToken()` likhega to
 * 19.0.0 par COMPILE HI NAHI HOGA. Aur agar kabhi jars 22+ par upgrade karo,
 * to `FirebaseInstanceId` class hi gayab ho jayegi (25.1.1 ke
 * com/google/firebase/iid/ package mein sirf FirebaseInstanceIdReceiver bachi
 * hai) — tab is file ko badalna padega, aur neeche wala SDK-mismatch log
 * turant bata dega ki wahi hua hai.
 *
 * MANIFEST NOTE (19.0.0 ke liye):
 *   `com.google.firebase.iid.Registrar` meta-data ZAROORI hai — yahi
 *   FirebaseInstanceId component register karta hai. 19.0.0 mein
 *   `FirebaseMessagingRegistrar` naam ki koi class hai hi nahi (maine AAR
 *   check kiya), wo sirf 22+ mein aayi. Manifest mein extra registrars pade
 *   rehne se crash nahi hota — firebase-common 18.0.0 ka ComponentDiscovery
 *   ClassNotFoundException ko sirf Log.w karke skip kar deta hai
 *   ("Class %s is not an found.") — isliye unhe chhod dena safe hai.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ASLI BUG JO v2 MEIN THA (API galat nahi thi — LOGIC galat tha)
 * ─────────────────────────────────────────────────────────────────────────────
 * v2 ka flow ye tha:
 *
 *     if (sameUser && sameToken) return;          // ← yahin sab mar gaya
 *     ...updateChildren(...)
 *         .addOnSuccessListener(v -> { cache save; resubscribeAllChannels(uid); })
 *
 * Do problem:
 *
 *   1. `resubscribeAllChannels()` DB WRITE ke success ke andar tha. DB write
 *      tabhi hoti hai jab token BADLA ho. FCM token mahino tak nahi badalta.
 *      Matlab pehli baar ke baad topic re-subscribe KABHI nahi chala.
 *
 *   2. Topic subscriptions DEVICE par rehti hain, database mein nahi. App
 *      reinstall, "Clear data", phone restore, ya naya device — sab par ye
 *      gayab ho jaati hain. DB mein user abhi bhi channel ka subscriber hai,
 *      par device kisi topic par nahi hai → channel post kabhi nahi pahunchti,
 *      aur kahin koi error bhi nahi aata.
 *
 * v3 mein topic self-heal token change se ALAG kar diya gaya hai: har 24 ghante
 * mein ek baar chalta hai, plus naye user / naye token / force par turant.
 *
 * BAAKI v3 FIXES
 *   • setAutoInitEnabled(true) — auto-init off ho to getInstanceId() kabhi
 *     resolve nahi hota aur koi error bhi nahi milta.
 *   • getInstanceId() fail hone par ek baar retry (SERVICE_NOT_AVAILABLE /
 *     AUTHENTICATION_FAILED 19.x par kaafi common hai, khaaskar pehle launch par).
 *   • clearTokenOnLogout() ab topics bhi unsubscribe karta hai, warna is device
 *     par next account ko purane user ke channel posts milte rehte the.
 *   • Har swallow kiye gaye Throwable ka LOUD log — v2 ek line log karke chup
 *     ho jaata tha, isliye months tak pata hi nahi chala.
 *
 * Saare public method signatures v2 jaise hi hain — ChatActivity,
 * ChannelActivity, SettingsActivity, ChatlistActivity aur FCMNotificationSender
 * bina kisi badlaav ke compile honge.
 */
public final class FcmTokenManager {

    private static final String TAG       = "MinigramFCM";
    private static final String PREFS     = "fcm_state";
    private static final String KEY_TOKEN = "last_synced_token";
    private static final String KEY_UID   = "last_synced_uid";
    private static final String KEY_RESUB = "last_resubscribe_at";

    /** Nothing-changed case mein topic self-heal kitni der baad dobara chale. */
    private static final long RESUBSCRIBE_INTERVAL_MS = 24L * 60 * 60 * 1000; // 24h

    /** getInstanceId() fail hone par retry se pehle wait. */
    private static final long RETRY_DELAY_MS = 8000L;

    /** Diagnostic ek hi baar log karo, har call par nahi. */
    private static boolean messagingFailureLogged = false;

    private FcmTokenManager() { }

    // ─────────────────────────────────────────────────────────────────────────
    // SAFE ACCESSORS — saare Firebase Messaging/IID calls yahin se jaate hain
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * FirebaseMessaging deta hai, ya null agar SDK wired nahi hai. Kabhi throw
     * nahi karta — ek missing component ko poori Activity nahi giraani chahiye.
     *
     * 19.0.0 mein FirebaseMessaging.getInstance() andar se
     * FirebaseInstanceId.getInstance() call karta hai, jo FirebaseApp aur
     * com.google.firebase.iid.Registrar dono par depend karta hai. Dono mein se
     * kuch bhi missing ho to yahan NullPointerException aata hai.
     */
    @Nullable
    private static FirebaseMessaging messaging() {
        try {
            FirebaseMessaging fm = FirebaseMessaging.getInstance();
            // Auto-init OFF ho to getInstanceId() kabhi complete nahi hota.
            // Idempotent aur sasta hai.
            try { fm.setAutoInitEnabled(true); } catch (Throwable ignored) { }
            return fm;
        } catch (Throwable t) {
            logSdkFailure("FirebaseMessaging", t);
            return null;
        }
    }

    private static void logSdkFailure(String what, Throwable t) {
        if (messagingFailureLogged) return;
        messagingFailureLogged = true;
        Log.e(TAG, "=========================================================");
        Log.e(TAG, "FCM SDK UNAVAILABLE: " + what + " -> " + t);
        if (t instanceof NoClassDefFoundError || t instanceof ClassNotFoundException) {
            Log.e(TAG, "SDK MISMATCH: ye class project ke jars mein hai hi nahi.");
            Log.e(TAG, "  FirebaseInstanceId sirf firebase-messaging <= 21.x ke saath");
            Log.e(TAG, "  aata hai (firebase-iid). 22.0.0 mein wo delete ho chuki hai.");
            Log.e(TAG, "  Ya to 19.0.0 wale jars wapas lagao, ya is file ko");
            Log.e(TAG, "  FirebaseMessaging.getInstance().getToken() par shift karo.");
        } else {
            Log.e(TAG, "AndroidManifest ke ComponentDiscoveryService mein");
            Log.e(TAG, "  com.google.firebase.iid.Registrar meta-data hona ZAROORI hai.");
        }
        Log.e(TAG, "Jab tak ye theek nahi hota: token save aur topic subscribe");
        Log.e(TAG, "(matlab chat aur channel dono notifications) band rahenge.");
        Log.e(TAG, "=========================================================");
    }

    /** True agar Messaging SDK usable hai. */
    public static boolean isMessagingAvailable() {
        return messaging() != null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Current FCM token fetch karke logged-in user ke neeche save karta hai,
     * aur zaroorat padne par saare channel topics dobara subscribe karta hai.
     * Har app start par call karna safe hai. Kabhi throw nahi karta.
     *
     * Ise LAUNCHER (ChatlistActivity) se call karo, sirf ChatActivity se nahi —
     * warna jo user kabhi 1-to-1 chat nahi kholta uska token DB mein aata hi
     * nahi, aur koi use push bhej hi nahi sakta.
     */
    public static void syncToken(final Context context) {
        syncToken(context, false);
    }

    /**
     * @param force true = local cache ignore karke token dobara likho aur saare
     *              topics dobara subscribe karo. Login ke turant baad,
     *              reinstall ke baad, ya kisi "Fix notifications" debug button
     *              se use karo.
     */
    public static void syncToken(final Context context, final boolean force) {
        try {
            final Context appCtx = context.getApplicationContext();

            // Defensive: Sketchware Pro ke manifests mein FirebaseInitProvider
            // kabhi-kabhi missing hota hai. Ye call idempotent hai.
            try { FirebaseApp.initializeApp(appCtx); } catch (Throwable ignored) { }

            final FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) {
                Log.d(TAG, "syncToken: no logged-in user, skipping");
                return;
            }
            final String uid = user.getUid();

            if (messaging() == null) return;

            fetchTokenAndStore(appCtx, uid, force, true);

        } catch (Throwable t) {
            // Ye method kabhi kisi Activity ko nahi girayega.
            Log.e(TAG, "syncToken crashed (swallowed): " + Log.getStackTraceString(t));
        }
    }

    /**
     * Asli kaam. 19.0.0 par token FirebaseInstanceId se aata hai.
     *
     * @param allowRetry pehli koshish ke liye true; retry ke liye false, taaki
     *                   infinite loop na bane.
     */
    private static void fetchTokenAndStore(final Context appCtx, final String uid,
                                           final boolean force, final boolean allowRetry) {
        final FirebaseMessaging fm = messaging();
        if (fm == null) return;

        fm.getToken()
            .addOnSuccessListener(token -> {
                if (TextUtils.isEmpty(token)) {
                    Log.e(TAG, "syncToken: getInstanceId() returned empty token");
                    return;
                }

                final SharedPreferences sp =
                        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

                final boolean sameUser  = uid.equals(sp.getString(KEY_UID, null));
                final boolean sameToken = token.equals(sp.getString(KEY_TOKEN, null));
                final long lastResub    = sp.getLong(KEY_RESUB, 0L);
                final boolean resubDue  =
                        System.currentTimeMillis() - lastResub > RESUBSCRIBE_INTERVAL_MS;

                // ── TOPIC SELF-HEAL ──────────────────────────────────────────
                // YE AB DB WRITE SE ALAG HAI. v2 mein ye updateChildren() ke
                // success listener ke andar tha, jo sirf token badalne par
                // chalta tha — matlab practically kabhi nahi. Topic
                // subscriptions device par rehti hain aur reinstall/restore par
                // gayab ho jaati hain, isliye inhe periodically repair karna
                // hi padta hai. subscribeToTopic() idempotent hai.
                if (force || !sameUser || !sameToken || resubDue) {
                    resubscribeAllChannels(uid);
                    sp.edit().putLong(KEY_RESUB, System.currentTimeMillis()).apply();
                }

                // DB mein token pehle se sahi hai — dobara likhne ki zaroorat nahi.
                if (!force && sameUser && sameToken) {
                    Log.d(TAG, "syncToken: token unchanged for " + uid);
                    return;
                }

                // Tokens live under fcm_tokens/{uid}: write-only for the client, readable
                // only by Cloud Functions (see database.rules.json).
                FirebaseDatabase.getInstance().getReference("fcm_tokens").child(uid)
                    .setValue(token)
                    .addOnSuccessListener(v -> {
                        sp.edit().putString(KEY_UID, uid)
                                 .putString(KEY_TOKEN, token).apply();
                        Log.d(TAG, "syncToken: token saved for " + uid);
                    })
                    .addOnFailureListener(e -> {
                        // Cache MAT update karo — warna agli baar "sameToken"
                        // dikhega aur ye failed write kabhi retry hi nahi hogi.
                        Log.e(TAG, "syncToken: DB write FAILED (cache not updated, "
                                + "will retry next launch): " + e.getMessage());
                    });
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "syncToken: getInstanceId() failed: " + e.getMessage());
                // 19.x par pehle launch / kharaab network par SERVICE_NOT_AVAILABLE
                // aur AUTHENTICATION_FAILED common hain. Ek baar retry karo,
                // warna us session mein token kabhi register hi nahi hoga.
                if (allowRetry) {
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> fetchTokenAndStore(appCtx, uid, force, false),
                            RETRY_DELAY_MS);
                }
            });
    }

    /**
     * User jitne channels follow karta hai, sab ke topics dobara subscribe
     * karta hai.
     *
     * Topic subscription DEVICE par rehti hai, database mein nahi — reinstall,
     * "Clear data", restore ya device change par gayab ho jaati hai. DB mein
     * user abhi bhi subscriber dikhta hai par device kisi topic par nahi hota,
     * isliye us channel ki post use kabhi nahi milti aur koi error bhi nahi aata.
     */
    public static void resubscribeAllChannels(final String uid) {
        if (TextUtils.isEmpty(uid)) return;
        if (messaging() == null) return;

        try {
            DatabaseReference ref = FirebaseDatabase.getInstance()
                    .getReference("user_channels").child(uid);

            ref.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    int n = 0;
                    for (DataSnapshot child : snap.getChildren()) {
                        String channelId = child.getKey();
                        if (TextUtils.isEmpty(channelId)) continue;
                        subscribeChannel(channelId);
                        n++;
                    }
                    Log.d(TAG, "resubscribeAllChannels: " + n + " topic(s) for " + uid);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    Log.e(TAG, "resubscribeAllChannels cancelled: " + e.getMessage());
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "resubscribeAllChannels crashed (swallowed): " + t);
        }
    }

    /** Ek channel ka topic subscribe karo. */
    public static void subscribeChannel(final String channelId) {
        if (TextUtils.isEmpty(channelId)) return;
        subscribeTopic(topicFor(channelId));
    }

    /** Ek channel ka topic unsubscribe karo. */
    public static void unsubscribeChannel(final String channelId) {
        if (TextUtils.isEmpty(channelId)) return;
        unsubscribeTopic(topicFor(channelId));
    }

    /** Kisi bhi raw topic ko subscribe karo (e.g. "all"). Kabhi throw nahi karta. */
    public static void subscribeTopic(final String topic) {
        if (TextUtils.isEmpty(topic)) return;
        FirebaseMessaging fm = messaging();
        if (fm == null) return;
        try {
            fm.subscribeToTopic(topic).addOnCompleteListener(task ->
                    Log.d(TAG, "subscribe " + topic + " -> "
                            + (task.isSuccessful() ? "OK" : "FAILED: " + task.getException())));
        } catch (Throwable t) {
            Log.e(TAG, "subscribeTopic(" + topic + ") crashed (swallowed): " + t);
        }
    }

    /** Kisi bhi raw topic ko unsubscribe karo. Kabhi throw nahi karta. */
    public static void unsubscribeTopic(final String topic) {
        if (TextUtils.isEmpty(topic)) return;
        FirebaseMessaging fm = messaging();
        if (fm == null) return;
        try {
            fm.unsubscribeFromTopic(topic).addOnCompleteListener(task ->
                    Log.d(TAG, "unsubscribe " + topic + " -> "
                            + (task.isSuccessful() ? "OK" : "FAILED: " + task.getException())));
        } catch (Throwable t) {
            Log.e(TAG, "unsubscribeTopic(" + topic + ") crashed (swallowed): " + t);
        }
    }

    /**
     * FirebaseAuth.signOut() se PEHLE call karo.
     *
     * Token account se hata deta hai AUR har channel topic unsubscribe karta
     * hai, taaki is device par agle user ko purane account ke channel posts na
     * milte rahein.
     *
     * NOTE: yahan FirebaseInstanceId.deleteInstanceId() JAAN-BOOJH KAR nahi
     * use kiya — wo 19.0.0 mein BLOCKING hai (IOException throw karta hai) aur
     * main thread par NetworkOnMainThreadException de deta hai. Token ko DB se
     * hata dena hi kaafi hai.
     */
    public static void clearTokenOnLogout(final Context context) {
        try {
            final FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null) {
                final String uid = user.getUid();

                // Topics pehle drop karo — iske liye DB read chahiye jab user
                // abhi bhi authenticated hai.
                try {
                    FirebaseDatabase.getInstance().getReference("user_channels").child(uid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                                for (DataSnapshot c : snap.getChildren()) {
                                    if (!TextUtils.isEmpty(c.getKey())) unsubscribeChannel(c.getKey());
                                }
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) { }
                        });
                } catch (Throwable ignored) { }

                FirebaseDatabase.getInstance().getReference("fcm_tokens")
                        .child(uid).removeValue();
                // legacy location (pre-1.1.0)
                FirebaseDatabase.getInstance().getReference("users")
                        .child(uid).child("fcm_token").removeValue();
            }

            context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().clear().apply();
        } catch (Throwable t) {
            Log.e(TAG, "clearTokenOnLogout crashed (swallowed): " + t);
        }
    }

    /**
     * FCM topic names sirf [a-zA-Z0-9-_.~%] allow karte hain (19.0.0 ka regex
     * maine AAR mein verify kiya: {1,900} length limit ke saath). Invalid topic
     * par subscribeToTopic() IllegalArgumentException phenkta hai aur send bhi
     * chup-chaap kisi tak nahi pahunchta.
     *
     * IMPORTANT: sender (ChannelActivity) aur har subscriber (ChannelActivity,
     * ChatlistActivity, UserSearchActivity, DeepLinkActivity) — sab ko topic
     * ISI method se banana hai. Jo jagah abhi bhi haath se "channel_" + id
     * likhti hai, wo jis din id mein koi aisa character aayega jise ye method
     * badal deta hai, us din chup-chaap alag topic par chali jayegi.
     */
    public static String topicFor(String channelId) {
        if (channelId == null) return "channel_invalid";
        return "channel_" + channelId.replaceAll("[^a-zA-Z0-9\\-_.~%]", "_");
    }
}
