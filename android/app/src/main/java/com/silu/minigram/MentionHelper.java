package com.silu.minigram;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MentionHelper — @handle mentions ke liye single source of truth.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * KYA KARTA HAI
 * ─────────────────────────────────────────────────────────────────────────────
 * Chat message, channel post, aur image caption — kahin bhi "@someusername"
 * likha ho to:
 *   • agar wo handle app mein EXIST karta hai → blue + bold link jaisa dikhta
 *     hai aur tap karne par app ke andar hi khulta hai
 *   • agar EXIST nahi karta → bilkul normal text rehta hai (koi blue nahi,
 *     koi tap nahi) — taaki "@bhai kya haal hai" jaisa aam text link na bane
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * TUMHARE EXISTING SCHEMA PAR HI BANA HAI — koi naya DB node nahi
 * ─────────────────────────────────────────────────────────────────────────────
 * CompleteProfileActivity / CreateChannelActivity / EditProfileActivity pehle se
 * ye likhte hain:
 *
 *     handles/<username>  =  { "type": "user"|"channel", "id": "<uid|channelId>" }
 *
 * Yehi node lookup ke liye use hota hai. Aur username ka rule bhi wahi rakha
 * hai jo CompleteProfileActivity._isValidUsername() mein hai:
 *
 *     [a-z0-9_]{4,20}
 *
 * Isliye "@ok" (chhota) ya "@bahut_lamba_naam_jo_20_se_bada_hai" match hi nahi
 * karte — jo galat-fahmi wale mentions apne aap filter kar deta hai.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * TAP PAR KYA HOTA HAI
 * ─────────────────────────────────────────────────────────────────────────────
 * DeepLinkActivity ko explicit Intent se launch karta hai
 * (minigram://user/<handle>). Matlab @mention aur share-link DONO bilkul ek
 * jaisa behave karte hain, aur wo logic sirf EK jagah likhi hai. User handle
 * → ChatActivity, channel handle → ChannelActivity. Apna khud ka handle tap
 * karo to MyProfileActivity.
 *
 * Agar tum chahte ho ki user mention tap par CHAT ki jagah UserProfileSheet
 * khule, to neeche OPEN_PROFILE_SHEET_FOR_USERS = true kar do — bas ek line.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * PERFORMANCE — ye RecyclerView ke andar chalta hai, isliye zaroori hai
 * ─────────────────────────────────────────────────────────────────────────────
 * Har row par DB read karna scroll ko maar dega. Isliye:
 *   • in-memory cache (handle → target) — positive 6 ghante, negative 5 minute
 *   • ek hi handle ke liye ek hi request in-flight rehti hai, chahe wo 50 rows
 *     mein dikhe (pendingLookups set)
 *   • pehle render par handle unknown ho to mention OPTIMISTICALLY blue dikhta
 *     hai aur background mein resolve hota hai; agar wo exist nahi karta to
 *     us row ko ek baar dobara render karke plain text kar diya jaata hai
 *   • cache LRU hai (max 500) taaki lambi chat mein memory na badhe
 */
public final class MentionHelper {

    private static final String TAG = "MinigramMention";

    // ── Tuning ───────────────────────────────────────────────────────────────

    /** Link ka colour — app ke baaki accent blue se match karta hai. */
    private static final int MENTION_COLOR = Color.parseColor("#1A73E8");

    /** true = user mention tap par UserProfileSheet; false = seedha chat. */
    private static final boolean OPEN_PROFILE_SHEET_FOR_USERS = false;

    /** Handle exist karta hai — itni der tak dobara mat poochho. */
    private static final long POSITIVE_TTL_MS = 6L * 60 * 60 * 1000;  // 6h
    /** Handle nahi mila — thodi der baad dobara try karo (naya user ban sakta hai). */
    private static final long NEGATIVE_TTL_MS = 5L * 60 * 1000;       // 5 min

    private static final int CACHE_MAX = 500;

    /**
     * Mention pattern.
     *
     *  (?<![A-Za-z0-9_@])  → "abc@gmail" ya "@@x" ke beech wale @ ko mat pakdo.
     *                        Email address ka @ isi se bach jaata hai.
     *  @([A-Za-z0-9_]{4,20}) → handle. Uppercase bhi accept karte hain aur
     *                        neeche lowercase kar dete hain, kyunki username
     *                        DB mein hamesha lowercase hi save hota hai.
     *  (?![A-Za-z0-9_])    → 20 se lambe word ko partially match hone se roko.
     */
    private static final Pattern MENTION_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9_@])@([A-Za-z0-9_]{4,20})(?![A-Za-z0-9_])");

    // ── Cache ────────────────────────────────────────────────────────────────

    /** Ek resolved handle. id == null matlab "exist nahi karta". */
    private static final class Target {
        final String type;   // "user" | "channel" | null
        final String id;     // uid | channelId | null
        final long   at;
        Target(String type, String id) {
            this.type = type; this.id = id; this.at = System.currentTimeMillis();
        }
        boolean found() { return id != null && !id.isEmpty(); }
        boolean expired() {
            long ttl = found() ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS;
            return System.currentTimeMillis() - at > ttl;
        }
    }

    /** accessOrder=true → sabse purana istemaal hua entry pehle nikalta hai. */
    private static final LinkedHashMap<String, Target> CACHE =
            new LinkedHashMap<String, Target>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Target> e) {
                    return size() > CACHE_MAX;
                }
            };

    /** Jo handles abhi DB se aa rahe hain — duplicate request rokne ke liye. */
    private static final Set<String> PENDING = new HashSet<>();

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private MentionHelper() { }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC — MsgRenderer isko call karta hai
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ssb ke andar har VALID mention par blue + bold + clickable span lagata hai.
     *
     * Jo handle cache mein "not found" hai, uspar kuch nahi lagta — wo plain
     * text hi rehta hai.
     *
     * @return un handles ki list jo abhi tak resolve nahi hue (caller inhe
     *         {@link #resolveAll} se warm kar sakta hai)
     */
    public static List<String> applyMentions(final Context ctx, SpannableStringBuilder ssb) {
        List<String> unknown = new ArrayList<>();
        if (ssb == null || ssb.length() == 0) return unknown;

        Matcher m = MENTION_PATTERN.matcher(ssb);
        while (m.find()) {
            final int start = m.start();
            final int end   = m.end();
            final String handle = m.group(1).toLowerCase(Locale.ENGLISH);

            Target t = cacheGet(handle);

            // Pakka pata hai ki ye handle exist nahi karta → plain text chhod do.
            if (t != null && !t.found()) continue;

            if (t == null) unknown.add(handle);

            // Style: blue + bold. Underline jaan-boojh kar nahi — messaging apps
            // mein mention underline nahi hota, sirf highlight hota hai.
            ssb.setSpan(new ForegroundColorSpan(MENTION_COLOR),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new StyleSpan(Typeface.BOLD),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            // ClickableSpan — SmartLinkMovementMethod ka OnTouchListener isko
            // apne aap uthata hai, isliye kisi adapter mein koi badlaav nahi
            // chahiye. Wahi press-highlight feedback aur long-press-copy dono
            // free mein mil jaate hain (MentionSpan CopyableSpan implement
            // karta hai — dekho neeche).
            ssb.setSpan(new MentionSpan(ctx, handle),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return unknown;
    }

    /**
     * Jo handles abhi unknown hain unhe background mein resolve karta hai.
     * Jab koi handle "exist nahi karta" nikalta hai, tabhi onNeedsRepaint
     * chalta hai — taaki blue text wapas normal ho jaye.
     *
     * Sirf tab repaint hota hai jab TextView par abhi bhi WAHI text hai jo
     * render ke waqt tha. RecyclerView row recycle ho gayi ho to kuch nahi
     * hota — galat row par galat text likhne ka koi chance nahi.
     */
    public static void resolveAll(final Context ctx,
                                  final List<String> handles,
                                  final TextView tv,
                                  final CharSequence renderedSnapshot,
                                  final Runnable onNeedsRepaint) {
        if (handles == null || handles.isEmpty()) return;

        final String snapshot = renderedSnapshot != null ? renderedSnapshot.toString() : null;

        for (final String handle : handles) {
            synchronized (PENDING) {
                if (PENDING.contains(handle)) continue;  // koi aur row already la raha hai
                PENDING.add(handle);
            }

            FirebaseDatabase.getInstance().getReference("handles").child(handle)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String type = snap.child("type").getValue(String.class);
                        String id   = snap.child("id").getValue(String.class);

                        boolean found = snap.exists() && id != null && !id.isEmpty();
                        cachePut(handle, new Target(found ? type : null, found ? id : null));

                        synchronized (PENDING) { PENDING.remove(handle); }

                        // Sirf NOT-FOUND par repaint chahiye. Found case mein
                        // jo pehle se blue dikh raha hai wo already sahi hai.
                        if (!found) maybeRepaint(tv, snapshot, onNeedsRepaint);
                    }

                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        synchronized (PENDING) { PENDING.remove(handle); }
                        // Network/permission fail — cache mat karo, warna ek
                        // temporary error 5 minute tak sahi mention ko bhi
                        // plain text bana dega. Agli render par phir try hoga.
                        Log.w(TAG, "handle lookup failed for @" + handle + ": " + e.getMessage());
                    }
                });
        }
    }

    private static void maybeRepaint(final TextView tv, final String snapshot,
                                     final Runnable onNeedsRepaint) {
        if (tv == null || onNeedsRepaint == null || snapshot == null) return;
        UI.post(() -> {
            CharSequence now = tv.getText();
            // Row recycle ho chuki hai to text alag hoga → chhod do.
            if (now != null && snapshot.equals(now.toString())) {
                onNeedsRepaint.run();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OPEN
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Handle ko app ke andar kholta hai.
     *
     * DeepLinkActivity ko EXPLICIT intent se launch karta hai — implicit
     * ACTION_VIEW nahi, warna theoretically koi doosri app minigram:// scheme
     * register karke chooser dikha sakti thi.
     *
     * DeepLinkActivity khud hi handles/ padh kar decide karta hai ki user hai
     * ya channel, isliye yahan "user" host bhejna channel handles ke liye bhi
     * theek kaam karta hai (wo host ko sirf null-check ke liye use karta hai,
     * routing DB ke "type" field se hoti hai). Not-found ka toast bhi wahin
     * se aata hai.
     */
    public static void open(Context ctx, String rawHandle) {
        if (ctx == null || rawHandle == null) return;
        final String handle = rawHandle.trim().toLowerCase(Locale.ENGLISH)
                                       .replaceFirst("^@", "");
        if (handle.isEmpty()) return;

        Target t = cacheGet(handle);

        // Apna khud ka handle → apni profile, chat nahi.
        if (t != null && t.found() && "user".equals(t.type)) {
            String myUid = currentUid();
            if (myUid != null && myUid.equals(t.id)) {
                ctx.startActivity(new Intent(ctx, MyProfileActivity.class));
                return;
            }
            if (OPEN_PROFILE_SHEET_FOR_USERS && showProfileSheet(ctx, t.id)) return;
        }

        try {
            Intent i = new Intent(ctx, DeepLinkActivity.class);
            i.setData(Uri.parse("minigram://user/" + handle));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "open(@" + handle + ") failed: " + e);
            Toast.makeText(ctx, "Couldn't open @" + handle, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * UserProfileSheet dikhata hai agar context ek AppCompatActivity hai.
     * @return true agar sheet dikh gayi
     */
    private static boolean showProfileSheet(Context ctx, String uid) {
        try {
            if (ctx instanceof androidx.appcompat.app.AppCompatActivity) {
                androidx.appcompat.app.AppCompatActivity act =
                        (androidx.appcompat.app.AppCompatActivity) ctx;
                if (act.isFinishing() || act.isDestroyed()) return false;
                UserProfileSheet.show(uid, null)
                        .show(act.getSupportFragmentManager(), "mention_profile");
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "profile sheet failed, falling back to deep link: " + t);
        }
        return false;
    }

    @Nullable
    private static String currentUid() {
        try {
            return FirebaseAuth.getInstance().getCurrentUser() != null
                    ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTIL — dusri jagah kaam aa sakta hai (e.g. mention notification bhejne mein)
    // ─────────────────────────────────────────────────────────────────────────

    /** Kisi text mein maujood saare valid handles (lowercase, bina duplicate). */
    public static List<String> extractMentions(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        Set<String> seen = new HashSet<>();
        Matcher m = MENTION_PATTERN.matcher(text);
        while (m.find()) {
            String h = m.group(1).toLowerCase(Locale.ENGLISH);
            if (seen.add(h)) out.add(h);
        }
        return out;
    }

    /**
     * True agar [start,end) kisi mention ke upar aata hai.
     *
     * MsgRenderer ka italic pattern "_text_" hai, aur username mein underscore
     * allowed hai. Iske bina "@rahul_kumar ne @amit_ok ko bola" jaisa text
     * "_kumar ne @amit_" ko italic bana kar DONO mentions tod deta.
     */
    public static boolean overlapsMention(CharSequence text, int start, int end) {
        if (text == null) return false;
        Matcher m = MENTION_PATTERN.matcher(text);
        while (m.find()) {
            if (start < m.end() && m.start() < end) return true;
        }
        return false;
    }

    /** Naye handle ke turant baad cache saaf karne ke liye (profile edit etc). */
    public static void invalidate(String handle) {
        if (handle == null) return;
        synchronized (CACHE) {
            CACHE.remove(handle.toLowerCase(Locale.ENGLISH).replaceFirst("^@", ""));
        }
    }

    // ── Cache helpers ────────────────────────────────────────────────────────

    @Nullable
    private static Target cacheGet(String handle) {
        synchronized (CACHE) {
            Target t = CACHE.get(handle);
            if (t == null) return null;
            if (t.expired()) { CACHE.remove(handle); return null; }
            return t;
        }
    }

    private static void cachePut(String handle, Target t) {
        synchronized (CACHE) { CACHE.put(handle, t); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MentionSpan
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @mention ke liye ClickableSpan.
     *
     * SmartLinkMovementMethod.CopyableSpan bhi implement karta hai, isliye long
     * press par ye khud batata hai ki kya copy hona chahiye — "@handle" (jo
     * user ne type kiya waisa hi), na ki us waqt jo underlying URL/target ho.
     * Isse copy-paste karke doosri jagah dobara mention karna natural rehta hai
     * (jaise Telegram/Discord mein hota hai).
     */
    private static final class MentionSpan extends ClickableSpan
            implements SmartLinkMovementMethod.CopyableSpan {

        private final Context ctx;
        private final String handle;

        MentionSpan(Context ctx, String handle) {
            this.ctx = ctx;
            this.handle = handle;
        }

        @Override public void onClick(@NonNull View widget) {
            open(widget.getContext() != null ? widget.getContext() : ctx, handle);
        }

        @Override public void updateDrawState(TextPaint ds) {
            // Default blue+underline mat lagao — humne khud colour diya hai
            // (applyMentions() mein ForegroundColorSpan + StyleSpan.BOLD).
            ds.setUnderlineText(false);
        }

        @Override public String getCopyText() { return "@" + handle; }
        @Override public String getCopyLabel() { return "Handle copied"; }
    }
}
