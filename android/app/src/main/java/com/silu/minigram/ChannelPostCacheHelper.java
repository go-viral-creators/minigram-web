package com.silu.minigram;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local SQLite cache for channel posts — same idea and architecture as
 * MessageCacheHelper (chat messages), applied to channels.
 *
 * Purpose:
 *   - Channel opens INSTANTLY from cache (no waiting for the network) —
 *     this is what fixes the 10-15s open time for channels with lots of
 *     posts: the user sees their last-seen posts immediately, and the
 *     live Firebase page (now only the latest 10, not 30) refreshes
 *     underneath moments later via a diffed update, not a jarring reload.
 *   - Basic offline reading — last-seen posts stay visible with no signal.
 *   - Re-opening the SAME channel a second time is instant too — nothing
 *     already cached gets re-downloaded from scratch.
 *
 * Firebase remains the single source of truth. This cache is only a fast
 * local mirror: whenever the live listener fires, we overwrite the cache
 * for that channel. Writes run on a background thread so they never block
 * the UI (same fix applied to MessageCacheHelper earlier).
 *
 * likes / reaction_counts are small maps that don't need byte-perfect
 * caching (Firebase refreshes them live moments after open anyway) — they're
 * stored as simple encoded strings rather than pulling in a JSON library.
 */
public class ChannelPostCacheHelper extends SQLiteOpenHelper {

    private static final String DB_NAME    = "minigram_channel_cache.db";
    private static final int    DB_VERSION = 2; // bumped: added missing "type" column
    private static final String TABLE      = "channel_posts";

    private static final String C_CHANNEL_ID   = "channel_id";
    private static final String C_POST_ID      = "post_id";
    private static final String C_SENDER_UID   = "sender_uid";
    private static final String C_TYPE         = "type";
    private static final String C_TEXT         = "text";
    private static final String C_IMAGE_URL    = "image_url";
    private static final String C_VOICE_URL    = "voice_url";
    private static final String C_DURATION     = "duration";
    private static final String C_CAPTION      = "caption";
    private static final String C_TIMESTAMP    = "timestamp";
    private static final String C_IS_EDITED    = "is_edited";
    private static final String C_IS_PINNED    = "is_pinned";
    private static final String C_IS_FORWARDED = "is_forwarded";
    private static final String C_IS_DEL_ALL   = "is_deleted_for_all";
    private static final String C_REPLY_TO_ID  = "reply_to_id";
    private static final String C_REPLY_SENDER = "reply_sender";
    private static final String C_REPLY_PREVIEW= "reply_preview";
    private static final String C_REPLY_COUNT  = "reply_count";
    private static final String C_VIEW_COUNT   = "view_count";
    private static final String C_LIKES        = "likes_encoded";      // "uid1,uid2,uid3"
    private static final String C_REACTIONS    = "reactions_encoded";  // "❤️:3,👍:1"
    private static final String C_MY_REACTION  = "my_reaction";

    private static final int MAX_CACHED_PER_CHANNEL = 150;

    private static ChannelPostCacheHelper instance;

    public static synchronized ChannelPostCacheHelper get(Context ctx) {
        if (instance == null) instance = new ChannelPostCacheHelper(ctx.getApplicationContext());
        return instance;
    }

    private ChannelPostCacheHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
            + C_CHANNEL_ID + " TEXT, "
            + C_POST_ID + " TEXT, "
            + C_SENDER_UID + " TEXT, "
            + C_TYPE + " TEXT, "
            + C_TEXT + " TEXT, "
            + C_IMAGE_URL + " TEXT, "
            + C_VOICE_URL + " TEXT, "
            + C_DURATION + " INTEGER, "
            + C_CAPTION + " TEXT, "
            + C_TIMESTAMP + " INTEGER, "
            + C_IS_EDITED + " INTEGER, "
            + C_IS_PINNED + " INTEGER, "
            + C_IS_FORWARDED + " INTEGER, "
            + C_IS_DEL_ALL + " INTEGER, "
            + C_REPLY_TO_ID + " TEXT, "
            + C_REPLY_SENDER + " TEXT, "
            + C_REPLY_PREVIEW + " TEXT, "
            + C_REPLY_COUNT + " INTEGER, "
            + C_VIEW_COUNT + " INTEGER, "
            + C_LIKES + " TEXT, "
            + C_REACTIONS + " TEXT, "
            + C_MY_REACTION + " TEXT, "
            + "PRIMARY KEY (" + C_CHANNEL_ID + ", " + C_POST_ID + ")"
            + ")");
        db.execSQL("CREATE INDEX idx_channel_time ON " + TABLE
            + " (" + C_CHANNEL_ID + ", " + C_TIMESTAMP + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    private static long boolToLong(Object b) { return Boolean.TRUE.equals(b) ? 1 : 0; }
    private static long numToLong(Object n)  { return (n instanceof Number) ? ((Number) n).longValue() : 0; }
    private static String str(Object o)      { return o == null ? null : o.toString(); }

    @SuppressWarnings("unchecked")
    private static String encodeLikes(Object likesObj) {
        if (!(likesObj instanceof HashMap)) return "";
        HashMap<String, Object> likes = (HashMap<String, Object>) likesObj;
        // NOTE: deliberately NOT String.join() — that's API 26+, and this app
        // ships minSdk 21. It throws NoSuchMethodError (an Error, not an
        // Exception), so the catch(Exception) around the caller would not
        // have caught it — it would have hard-crashed on API 21-25 devices.
        StringBuilder sb = new StringBuilder();
        for (String uid : likes.keySet()) {
            if (sb.length() > 0) sb.append(",");
            sb.append(uid);
        }
        return sb.toString();
    }

    private static HashMap<String, Object> decodeLikes(String s) {
        HashMap<String, Object> out = new HashMap<>();
        if (s == null || s.isEmpty()) return out;
        for (String uid : s.split(",")) {
            if (!uid.isEmpty()) out.put(uid, true);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String encodeReactions(Object reactionsObj) {
        if (!(reactionsObj instanceof HashMap)) return "";
        HashMap<String, Integer> reactions = (HashMap<String, Integer>) reactionsObj;
        StringBuilder sb = new StringBuilder(); // API 21 safe — see encodeLikes note
        for (HashMap.Entry<String, Integer> e : reactions.entrySet()) {
            if (sb.length() > 0) sb.append(",");
            sb.append(e.getKey()).append(":").append(e.getValue());
        }
        return sb.toString();
    }

    private static HashMap<String, Integer> decodeReactions(String s) {
        HashMap<String, Integer> out = new HashMap<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(",")) {
            int idx = part.lastIndexOf(':');
            if (idx <= 0) continue;
            try {
                out.put(part.substring(0, idx), Integer.parseInt(part.substring(idx + 1)));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    /**
     * Overwrite the cached post set for one channel, trimmed to the most
     * recent MAX_CACHED_PER_CHANNEL. Runs on a background thread — snapshot
     * the list on the caller's thread first (cheap) so the caller's live
     * list can keep being safely mutated while we write from our own copy.
     */
    public void cachePosts(String channelId, List<HashMap<String, Object>> posts) {
        if (channelId == null || posts == null) return;
        final List<HashMap<String, Object>> snapshot = new ArrayList<>(posts);
        dbExecutor.execute(() -> _cachePostsSync(channelId, snapshot));
    }

    private void _cachePostsSync(String channelId, List<HashMap<String, Object>> posts) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE, C_CHANNEL_ID + "=?", new String[]{channelId});
            for (HashMap<String, Object> p : posts) {
                String postId = str(p.get("postId"));
                if (postId == null || postId.startsWith("temp_")) continue;
                ContentValues cv = new ContentValues();
                cv.put(C_CHANNEL_ID,   channelId);
                cv.put(C_POST_ID,      postId);
                cv.put(C_SENDER_UID,   str(p.get("sender_uid")));
                cv.put(C_TYPE,         str(p.get("type")));
                cv.put(C_TEXT,         str(p.get("text")));
                cv.put(C_IMAGE_URL,    str(p.get("image_url")));
                cv.put(C_VOICE_URL,    str(p.get("voice_url")));
                cv.put(C_DURATION,     numToLong(p.get("duration")));
                cv.put(C_CAPTION,      str(p.get("caption")));
                cv.put(C_TIMESTAMP,    numToLong(p.get("timestamp")));
                cv.put(C_IS_EDITED,    boolToLong(p.get("is_edited")));
                cv.put(C_IS_PINNED,    boolToLong(p.get("is_pinned")));
                cv.put(C_IS_FORWARDED, boolToLong(p.get("is_forwarded")));
                cv.put(C_IS_DEL_ALL,   boolToLong(p.get("is_deleted_for_all")));
                cv.put(C_REPLY_TO_ID,  str(p.get("reply_to_id")));
                cv.put(C_REPLY_SENDER, str(p.get("reply_sender")));
                cv.put(C_REPLY_PREVIEW,str(p.get("reply_preview")));
                cv.put(C_REPLY_COUNT,  numToLong(p.get("reply_count")));
                cv.put(C_VIEW_COUNT,   numToLong(p.get("view_count")));
                cv.put(C_LIKES,        encodeLikes(p.get("likes")));
                cv.put(C_REACTIONS,    encodeReactions(p.get("reaction_counts")));
                cv.put(C_MY_REACTION,  str(p.get("my_reaction")));
                db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.execSQL("DELETE FROM " + TABLE + " WHERE " + C_CHANNEL_ID + "=? AND " + C_POST_ID
                + " NOT IN (SELECT " + C_POST_ID + " FROM " + TABLE + " WHERE " + C_CHANNEL_ID
                + "=? ORDER BY " + C_TIMESTAMP + " DESC LIMIT " + MAX_CACHED_PER_CHANNEL + ")",
                new String[]{channelId, channelId});
            db.setTransactionSuccessful();
        } catch (Exception ignored) {
        } finally {
            db.endTransaction();
        }
    }

    /** Load cached posts for a channel, oldest→newest (same order the UI expects). */
    public ArrayList<HashMap<String, Object>> loadPosts(String channelId) {
        ArrayList<HashMap<String, Object>> out = new ArrayList<>();
        if (channelId == null) return out;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query(TABLE, null, C_CHANNEL_ID + "=?", new String[]{channelId},
                null, null, C_TIMESTAMP + " ASC");
            while (c.moveToNext()) {
                HashMap<String, Object> p = new HashMap<>();
                p.put("postId",             c.getString(c.getColumnIndexOrThrow(C_POST_ID)));
                p.put("sender_uid",         c.getString(c.getColumnIndexOrThrow(C_SENDER_UID)));
                p.put("type",               c.getString(c.getColumnIndexOrThrow(C_TYPE)));
                p.put("text",               c.getString(c.getColumnIndexOrThrow(C_TEXT)));
                p.put("image_url",          c.getString(c.getColumnIndexOrThrow(C_IMAGE_URL)));
                p.put("voice_url",          c.getString(c.getColumnIndexOrThrow(C_VOICE_URL)));
                p.put("duration",           c.getLong(c.getColumnIndexOrThrow(C_DURATION)));
                p.put("caption",            c.getString(c.getColumnIndexOrThrow(C_CAPTION)));
                p.put("timestamp",          c.getLong(c.getColumnIndexOrThrow(C_TIMESTAMP)));
                p.put("is_edited",          c.getLong(c.getColumnIndexOrThrow(C_IS_EDITED)) == 1);
                p.put("is_pinned",          c.getLong(c.getColumnIndexOrThrow(C_IS_PINNED)) == 1);
                p.put("is_forwarded",       c.getLong(c.getColumnIndexOrThrow(C_IS_FORWARDED)) == 1);
                p.put("is_deleted_for_all", c.getLong(c.getColumnIndexOrThrow(C_IS_DEL_ALL)) == 1);
                p.put("reply_to_id",        c.getString(c.getColumnIndexOrThrow(C_REPLY_TO_ID)));
                p.put("reply_sender",       c.getString(c.getColumnIndexOrThrow(C_REPLY_SENDER)));
                p.put("reply_preview",      c.getString(c.getColumnIndexOrThrow(C_REPLY_PREVIEW)));
                p.put("reply_count",        c.getLong(c.getColumnIndexOrThrow(C_REPLY_COUNT)));
                p.put("view_count",         c.getLong(c.getColumnIndexOrThrow(C_VIEW_COUNT)));
                p.put("likes",              decodeLikes(c.getString(c.getColumnIndexOrThrow(C_LIKES))));
                p.put("reaction_counts",    decodeReactions(c.getString(c.getColumnIndexOrThrow(C_REACTIONS))));
                p.put("my_reaction",        c.getString(c.getColumnIndexOrThrow(C_MY_REACTION)));
                out.add(p);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** Wipe a single channel's cache (e.g. when the user leaves/the channel is deleted). */
    public void clearChannel(String channelId) {
        if (channelId == null) return;
        try { getWritableDatabase().delete(TABLE, C_CHANNEL_ID + "=?", new String[]{channelId}); }
        catch (Exception ignored) {}
    }
}
