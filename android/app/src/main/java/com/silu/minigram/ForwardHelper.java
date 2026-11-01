package com.silu.minigram;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ForwardHelper — single source of truth for "what does a forwarded copy of
 * a message/post look like".
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * BUG YE FILE FIX KARTI HAI
 * ─────────────────────────────────────────────────────────────────────────────
 * Purane forward code (ChatActivity aur ChannelActivity dono ki
 * _showForwardDialog) mein ek HARDCODED field list thi:
 *
 *     m.put("type", ...); m.put("text", ...); m.put("image_url", ...);
 *     m.put("caption", ...);
 *     // bas itna hi
 *
 * Isse do real bugs the:
 *   1. ChannelActivity ke dono forward-write blocks mein "voice_url" aur
 *      "duration" hi nahi the — matlab channel se voice message forward karo
 *      to naya message type="voice" hoga par audio hi nahi hoga (broken player).
 *   2. link_preview_* (5 fields) kabhi copy nahi hote — forward kiya hua link
 *      hamesha plain text ban jaata, rich preview khatam.
 *
 * FIX: whitelist ki jagah BLOCKLIST approach. Poora source message copy hota
 * hai, sirf wo fields hataye/reset kiye jaate hain jo per-instance state hain
 * (sender, timestamp, flags, reply info, reactions, view counts wagera — in
 * mein se kai to sirf CLIENT-SIDE derived hote hain, DB mein exist hi nahi
 * karte asli field ke roop mein, jaise "reaction_counts"/"my_reaction"/
 * "view_count"/"reply_count" — ye ChatActivity/ChannelActivity ke apne
 * listener "reactions" child ginkar in-memory banate hain). Isse:
 *   - koi bhi CONTENT field (voice_url, duration, link_preview_*, aur future
 *     video_url/document_url jaisा kuch bhi) automatically carry hota hai,
 *     kabhi bhool nahi sakte
 *   - per-instance state kabhi galti se leak nahi hota (purani reaction count,
 *     purana view count, wrong reply_to_id jo naye chat mein exist hi nahi
 *     karta)
 */
public final class ForwardHelper {

    private ForwardHelper() { }

    /**
     * In-memory HashMap mein ye fields sirf UI ke liye derive/attach kiye
     * jaate hain (DB mein literal field ke roop mein exist nahi karte), ya
     * per-instance state hain jo forward hone par reset honi chahiye.
     * Forward karte waqt in sabko strip kiya jaata hai.
     */
    private static final Set<String> EXCLUDED_FIELDS = new HashSet<>();
    static {
        // DB key hai, field nahi
        EXCLUDED_FIELDS.add("msgId");
        EXCLUDED_FIELDS.add("postId");
        // Naya sender/time/state chahiye, purana nahi
        EXCLUDED_FIELDS.add("sender_uid");
        EXCLUDED_FIELDS.add("sender");
        EXCLUDED_FIELDS.add("timestamp");
        EXCLUDED_FIELDS.add("status");
        EXCLUDED_FIELDS.add("is_edited");
        EXCLUDED_FIELDS.add("is_pinned");
        EXCLUDED_FIELDS.add("is_forwarded");
        EXCLUDED_FIELDS.add("is_deleted_for_all");
        EXCLUDED_FIELDS.add("is_deleted_for_me");
        // Reply context purane chat/channel ki hai — naye target mein us
        // msgId ka koi wajood hi nahi, isliye carry karna galat hai
        EXCLUDED_FIELDS.add("reply_to_id");
        EXCLUDED_FIELDS.add("reply_preview");
        EXCLUDED_FIELDS.add("reply_sender");
        // Client-side derived (DB field nahi) — "reactions" child ginkar
        // banaye jaate hain listener mein. Blindly copy karoge to purani
        // count ek FAKE literal field ban kar hamesha ke liye chipak jaayegi.
        EXCLUDED_FIELDS.add("reaction_counts");
        EXCLUDED_FIELDS.add("my_reaction");
        EXCLUDED_FIELDS.add("view_count");
        EXCLUDED_FIELDS.add("reply_count");
        // Target-specific — naye write mein caller explicitly set karta hai
        EXCLUDED_FIELDS.add("channel_id");
        // Local-only / in-flight-upload state, kabhi DB mein jaana hi nahi chahiye
        EXCLUDED_FIELDS.add("local_uri");
        EXCLUDED_FIELDS.add("upload_state");
        // Per-recipient read state
        EXCLUDED_FIELDS.add("read_at");
        EXCLUDED_FIELDS.add("last_read_ts");
        EXCLUDED_FIELDS.add("unread");
    }

    /**
     * Ek forwarded copy banati hai — poora source content carry hota hai
     * (jo bhi fields ho, chahe abhi ka type ho ya future ka), sirf
     * per-instance state reset hoti hai.
     *
     * @param source     original message/post (jaisा UI mein render hua tha)
     * @param myUid      naya sender
     * @param forChannel true = target channel hai (channel posts "is_deleted_for_me"
     *                   use hi nahi karte, isliye wo field skip ho jaata hai)
     */
    public static HashMap<String, Object> buildForwardedCopy(
            HashMap<String, Object> source, String myUid, boolean forChannel) {

        HashMap<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            if (EXCLUDED_FIELDS.contains(e.getKey())) continue;
            if (e.getValue() == null) continue;
            copy.put(e.getKey(), e.getValue());
        }

        copy.put("sender_uid",         myUid);
        copy.put("timestamp",          System.currentTimeMillis());
        copy.put("is_edited",          false);
        copy.put("is_pinned",          false);
        copy.put("is_forwarded",       true);
        copy.put("is_deleted_for_all", false);
        if (!forChannel) copy.put("is_deleted_for_me", false);
        copy.put("status", "sent");

        // Har content type ke liye ek safe default rehna chahiye taaki koi
        // renderer null par crash na kare — source mein field na ho to bhi.
        if (!copy.containsKey("text"))      copy.put("text", "");
        if (!copy.containsKey("caption"))   copy.put("caption", "");
        if (!copy.containsKey("image_url")) copy.put("image_url", "");

        return copy;
    }

    /**
     * Chat-list preview / push-notification body ke liye ek chhota, safe
     * string banata hai — media type ke hisaab se (normal (non-forwarded)
     * send ke waqt ChatActivity/ChannelActivity jo preview banate hain waise
     * hi format), ya "Forwarded N messages" jab ek se zyada ho.
     */
    public static String previewFor(List<HashMap<String, Object>> items) {
        if (items == null || items.isEmpty()) return "";
        if (items.size() > 1) return "Forwarded " + items.size() + " messages";

        HashMap<String, Object> m = items.get(0);
        String type = (String) m.get("type");
        if ("image".equals(type)) return "\uD83D\uDCF7 Photo";
        if ("voice".equals(type)) return "\uD83C\uDFA4 Voice message";

        String text = (String) m.get("text");
        if (text == null || text.isEmpty()) text = (String) m.get("caption");
        return text != null && !text.isEmpty() ? text : "Forwarded message";
    }
}
