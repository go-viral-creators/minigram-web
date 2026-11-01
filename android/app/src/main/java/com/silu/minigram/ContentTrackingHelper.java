package com.silu.minigram;

import android.util.Patterns;

import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.regex.Matcher;

/**
 * Populates user_media/{uid} and user_links/{uid} — the data source for the
 * Media/Links/Voice tabs on the profile screen (MyProfileActivity), which
 * were empty-state placeholders until this existed.
 *
 * Called once per send, from every place a message/post actually gets
 * written: ChatActivity._sendMessage() (single, central send point for
 * chat) and ChannelActivity's three post-creation call sites (text, image,
 * voice). Fully async/fire-and-forget — never blocks or slows down the
 * send flow itself, and a failure here never affects message delivery.
 *
 * Each user_media entry also carries content_id — the exact msgId/postId
 * it came from — so the profile screen's "View" action can jump straight
 * to that message and highlight it, not just open the chat/channel.
 *
 * IMPORTANT — this only tracks things sent from now on. It can't retroactively
 * populate history for messages that already existed before this shipped;
 * that data was never recorded anywhere. Someone's Media/Links tabs will
 * fill in naturally as they keep using the app.
 */
public class ContentTrackingHelper {

    public static void trackSent(String myUid, String type, String text,
                                  String imageUrl, String voiceUrl,
                                  String source, String sourceId, String contentId) {
        if (myUid == null || myUid.isEmpty()) return;

        long ts = System.currentTimeMillis();
        FirebaseDatabase db = FirebaseDatabase.getInstance();

        boolean hasMedia = (imageUrl != null && !imageUrl.isEmpty())
                         || (voiceUrl != null && !voiceUrl.isEmpty());
        if (hasMedia) {
            String mediaUrl  = (imageUrl != null && !imageUrl.isEmpty()) ? imageUrl : voiceUrl;
            String mediaType = (imageUrl != null && !imageUrl.isEmpty()) ? "image" : "voice";

            HashMap<String, Object> entry = new HashMap<>();
            entry.put("type",       mediaType);
            entry.put("url",        mediaUrl);
            entry.put("source",     source);     // "chat" or "channel"
            entry.put("source_id",  sourceId);   // chatId or channelId
            entry.put("content_id", contentId);  // msgId or postId — the exact
                                                  // message/post this came from,
                                                  // so "View" can scroll straight
                                                  // to it instead of just opening
                                                  // the chat/channel generally.
            entry.put("timestamp",  ts);

            String key = db.getReference("user_media").child(myUid).push().getKey();
            if (key != null) db.getReference("user_media").child(myUid).child(key).setValue(entry);
        }

        String url = _extractFirstUrl(text);
        if (url != null) {
            HashMap<String, Object> entry = new HashMap<>();
            entry.put("url",          url);
            entry.put("text_preview", text.length() > 100 ? text.substring(0, 100) + "…" : text);
            entry.put("source",       source);
            entry.put("source_id",    sourceId);
            entry.put("content_id",   contentId);
            entry.put("timestamp",    ts);

            String key = db.getReference("user_links").child(myUid).push().getKey();
            if (key != null) db.getReference("user_links").child(myUid).child(key).setValue(entry);
        }
    }

    /** First URL found in the text, or null. Only takes the first — a message
     *  with multiple links is rare, and this only needs to power a "your
     *  shared links" tab, not be a complete extraction of every one. */
    private static String _extractFirstUrl(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = Patterns.WEB_URL.matcher(text);
        return m.find() ? m.group() : null;
    }
}
