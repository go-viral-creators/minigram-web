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
 * Local SQLite cache for chat messages.
 *
 * Purpose:
 *   - Chat opens INSTANTLY from cache (no waiting for the network).
 *   - Basic offline reading — last-seen messages stay visible with no signal.
 *
 * Firebase remains the single source of truth. This cache is only a fast local
 * mirror: whenever the live listener fires, we overwrite the cache for that
 * chat. We never write user actions here directly — only mirror what Firebase
 * already confirmed.
 *
 * Pure android.database.sqlite (no Room) — matches the Sketchware Pro workflow.
 */
public class MessageCacheHelper extends SQLiteOpenHelper {

    private static final String DB_NAME    = "minigram_cache.db";
    private static final int    DB_VERSION = 1;
    private static final String TABLE      = "messages";

    // Columns mirror the HashMap keys used in ChatActivity's messageList.
    private static final String C_CHAT_ID       = "chat_id";
    private static final String C_MSG_ID        = "msg_id";
    private static final String C_SENDER_UID    = "sender_uid";
    private static final String C_TEXT          = "text";
    private static final String C_IMAGE_URL     = "image_url";
    private static final String C_VOICE_URL     = "voice_url";
    private static final String C_DURATION      = "duration";
    private static final String C_CAPTION       = "caption";
    private static final String C_TYPE          = "type";
    private static final String C_TIMESTAMP     = "timestamp";
    private static final String C_IS_EDITED     = "is_edited";
    private static final String C_IS_PINNED     = "is_pinned";
    private static final String C_IS_FORWARDED  = "is_forwarded";
    private static final String C_IS_DEL_ALL    = "is_deleted_for_all";
    private static final String C_REPLY_TO_ID   = "reply_to_id";
    private static final String C_REPLY_SENDER  = "reply_sender";
    private static final String C_REPLY_PREVIEW = "reply_preview";
    private static final String C_STATUS        = "status";

    // Keep the cache from growing forever — only the most recent N per chat.
    private static final int MAX_CACHED_PER_CHAT = 200;

    private static MessageCacheHelper instance;

    public static synchronized MessageCacheHelper get(Context ctx) {
        if (instance == null) instance = new MessageCacheHelper(ctx.getApplicationContext());
        return instance;
    }

    private MessageCacheHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
            + C_CHAT_ID + " TEXT, "
            + C_MSG_ID + " TEXT, "
            + C_SENDER_UID + " TEXT, "
            + C_TEXT + " TEXT, "
            + C_IMAGE_URL + " TEXT, "
            + C_VOICE_URL + " TEXT, "
            + C_DURATION + " INTEGER, "
            + C_CAPTION + " TEXT, "
            + C_TYPE + " TEXT, "
            + C_TIMESTAMP + " INTEGER, "
            + C_IS_EDITED + " INTEGER, "
            + C_IS_PINNED + " INTEGER, "
            + C_IS_FORWARDED + " INTEGER, "
            + C_IS_DEL_ALL + " INTEGER, "
            + C_REPLY_TO_ID + " TEXT, "
            + C_REPLY_SENDER + " TEXT, "
            + C_REPLY_PREVIEW + " TEXT, "
            + C_STATUS + " TEXT, "
            + "PRIMARY KEY (" + C_CHAT_ID + ", " + C_MSG_ID + ")"
            + ")");
        db.execSQL("CREATE INDEX idx_chat_time ON " + TABLE
            + " (" + C_CHAT_ID + ", " + C_TIMESTAMP + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    // ─── Helpers to bridge nullable boxed types ↔ SQLite ───────────────────────
    private static long boolToLong(Object b) { return Boolean.TRUE.equals(b) ? 1 : 0; }
    private static long numToLong(Object n)  { return (n instanceof Number) ? ((Number) n).longValue() : 0; }
    private static String str(Object o)      { return o == null ? null : o.toString(); }

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    /**
     * Overwrite the entire cached message set for one chat, then trim to the
     * most-recent MAX_CACHED_PER_CHAT. Skips temp_ (optimistic, not-yet-
     * confirmed) messages.
     *
     * Runs the actual disk I/O on a background thread — this used to run
     * synchronously on whatever thread called it, which in practice was
     * ALWAYS the Firebase listener callback (the main/UI thread), and it
     * fired on every single live update to a chat (every new message, every
     * read receipt, every reaction...). A full delete+reinsert of up to 200
     * rows on the main thread on every one of those was a real source of
     * UI jank/slowness while a chat was open. We snapshot the list here (on
     * the caller's thread — cheap, just copying references) so the caller's
     * live `messageList` can keep being safely mutated while the background
     * thread writes from its own stable copy.
     */
    public void cacheMessages(String chatId, List<HashMap<String, Object>> messages) {
        if (chatId == null || messages == null) return;
        final List<HashMap<String, Object>> snapshot = new ArrayList<>(messages);
        dbExecutor.execute(() -> _cacheMessagesSync(chatId, snapshot));
    }

    private void _cacheMessagesSync(String chatId, List<HashMap<String, Object>> messages) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE, C_CHAT_ID + "=?", new String[]{chatId});
            for (HashMap<String, Object> m : messages) {
                String mId = str(m.get("msgId"));
                if (mId == null || mId.startsWith("temp_")) continue;
                ContentValues cv = new ContentValues();
                cv.put(C_CHAT_ID,       chatId);
                cv.put(C_MSG_ID,        mId);
                cv.put(C_SENDER_UID,    str(m.get("sender_uid")));
                cv.put(C_TEXT,          str(m.get("text")));
                cv.put(C_IMAGE_URL,     str(m.get("image_url")));
                cv.put(C_VOICE_URL,     str(m.get("voice_url")));
                cv.put(C_DURATION,      numToLong(m.get("duration")));
                cv.put(C_CAPTION,       str(m.get("caption")));
                cv.put(C_TYPE,          str(m.get("type")));
                cv.put(C_TIMESTAMP,     numToLong(m.get("timestamp")));
                cv.put(C_IS_EDITED,     boolToLong(m.get("is_edited")));
                cv.put(C_IS_PINNED,     boolToLong(m.get("is_pinned")));
                cv.put(C_IS_FORWARDED,  boolToLong(m.get("is_forwarded")));
                cv.put(C_IS_DEL_ALL,    boolToLong(m.get("is_deleted_for_all")));
                cv.put(C_REPLY_TO_ID,   str(m.get("reply_to_id")));
                cv.put(C_REPLY_SENDER,  str(m.get("reply_sender")));
                cv.put(C_REPLY_PREVIEW, str(m.get("reply_preview")));
                cv.put(C_STATUS,        str(m.get("status")));
                db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            // Trim old rows beyond the cap (keep newest by timestamp)
            db.execSQL("DELETE FROM " + TABLE + " WHERE " + C_CHAT_ID + "=? AND " + C_MSG_ID
                + " NOT IN (SELECT " + C_MSG_ID + " FROM " + TABLE + " WHERE " + C_CHAT_ID
                + "=? ORDER BY " + C_TIMESTAMP + " DESC LIMIT " + MAX_CACHED_PER_CHAT + ")",
                new String[]{chatId, chatId});
            db.setTransactionSuccessful();
        } catch (Exception ignored) {
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Load cached messages for a chat, oldest→newest (same order the UI expects).
     * Returns an empty list if nothing is cached.
     */
    public ArrayList<HashMap<String, Object>> loadMessages(String chatId) {
        ArrayList<HashMap<String, Object>> out = new ArrayList<>();
        if (chatId == null) return out;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query(TABLE, null, C_CHAT_ID + "=?", new String[]{chatId},
                null, null, C_TIMESTAMP + " ASC");
            while (c.moveToNext()) {
                HashMap<String, Object> m = new HashMap<>();
                m.put("msgId",              c.getString(c.getColumnIndexOrThrow(C_MSG_ID)));
                m.put("sender_uid",         c.getString(c.getColumnIndexOrThrow(C_SENDER_UID)));
                m.put("text",               c.getString(c.getColumnIndexOrThrow(C_TEXT)));
                m.put("image_url",          c.getString(c.getColumnIndexOrThrow(C_IMAGE_URL)));
                m.put("voice_url",          c.getString(c.getColumnIndexOrThrow(C_VOICE_URL)));
                m.put("duration",           c.getLong(c.getColumnIndexOrThrow(C_DURATION)));
                m.put("caption",            c.getString(c.getColumnIndexOrThrow(C_CAPTION)));
                m.put("type",               c.getString(c.getColumnIndexOrThrow(C_TYPE)));
                m.put("timestamp",          c.getLong(c.getColumnIndexOrThrow(C_TIMESTAMP)));
                m.put("is_edited",          c.getLong(c.getColumnIndexOrThrow(C_IS_EDITED)) == 1);
                m.put("is_pinned",          c.getLong(c.getColumnIndexOrThrow(C_IS_PINNED)) == 1);
                m.put("is_forwarded",       c.getLong(c.getColumnIndexOrThrow(C_IS_FORWARDED)) == 1);
                m.put("is_deleted_for_all", c.getLong(c.getColumnIndexOrThrow(C_IS_DEL_ALL)) == 1);
                m.put("reply_to_id",        c.getString(c.getColumnIndexOrThrow(C_REPLY_TO_ID)));
                m.put("reply_sender",       c.getString(c.getColumnIndexOrThrow(C_REPLY_SENDER)));
                m.put("reply_preview",      c.getString(c.getColumnIndexOrThrow(C_REPLY_PREVIEW)));
                m.put("status",             c.getString(c.getColumnIndexOrThrow(C_STATUS)));
                out.add(m);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** Wipe a single chat's cache (e.g. when the user clears/deletes the chat). */
    public void clearChat(String chatId) {
        if (chatId == null) return;
        try { getWritableDatabase().delete(TABLE, C_CHAT_ID + "=?", new String[]{chatId}); }
        catch (Exception ignored) {}
    }
}
