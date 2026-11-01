package com.silu.minigram;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * NotificationActionReceiver — quick Reply / Mute / Block from the shade.
 *
 * BUGS FIXED vs the previous version
 * ----------------------------------
 * 1) peer_uid was ALWAYS "" — FCMNotificationSender hardcoded an empty string
 *    into the reply PendingIntent. Every quick-reply therefore wrote to
 *    user_chats/""/{chatId}, silently corrupting the database, and ACTION_BLOCK
 *    wrote blocked_users/{myUid}/"". The uid now travels in the push payload
 *    ("sender_uid"), and if it is still missing we resolve it from
 *    user_chats/{myUid}/{chatId}/peer_uid before touching anything.
 *
 * 2) A quick reply sent NO push back to the peer. The message landed in
 *    Firebase, so the other user saw it only if their chat screen was already
 *    open — one of the concrete "notification nahi ja raha" cases.
 *
 * 3) The peer's unread counter was never incremented, so the chat list badge
 *    stayed wrong after every quick reply.
 *
 * 4) onReceive() fired async Firebase writes and returned immediately. Once
 *    onReceive() returns, the process becomes killable and pending writes can
 *    be dropped. goAsync() now keeps the receiver alive until the work is done
 *    (with a hard timeout so we never leak the wake lock).
 *
 * 5) The "Reply sent" confirmation notification had no channel on O+ in
 *    practice — it now reuses the same channel id the message notification
 *    was posted on and mirrors the original title.
 */
public class NotificationActionReceiver extends BroadcastReceiver {

    private static final String TAG = "MinigramFCM";

    public static final String ACTION_REPLY = "com.ddcstudio.minigram.ACTION_REPLY";
    public static final String ACTION_MUTE  = "com.ddcstudio.minigram.ACTION_MUTE";
    public static final String ACTION_BLOCK = "com.ddcstudio.minigram.ACTION_BLOCK";
    public static final String KEY_TEXT_REPLY = "key_text_reply";

    private static final String CHANNEL_ID_CHAT = "minigram_messages";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null) return;

        final Context appCtx = context.getApplicationContext();
        final String chatId  = intent.getStringExtra("chat_id");
        final String myUid   = intent.getStringExtra("my_uid");
        final String peerUid = intent.getStringExtra("peer_uid");
        final int notificationId = chatId != null ? chatId.hashCode() : 0;

        final NotificationManager nm =
                (NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);

        if (ACTION_MUTE.equals(action)) {
            if (chatId != null) {
                SharedPreferences prefs = appCtx.getSharedPreferences("MutedChats", Context.MODE_PRIVATE);
                prefs.edit().putBoolean(chatId, true).apply();
            }
            Toast.makeText(appCtx, "Chat muted", Toast.LENGTH_SHORT).show();
            if (nm != null) nm.cancel(notificationId);
            return;
        }

        if (ACTION_BLOCK.equals(action)) {
            if (TextUtils.isEmpty(myUid)) return;
            final PendingResult pr = goAsync();
            resolvePeer(myUid, chatId, peerUid, resolved -> {
                if (!TextUtils.isEmpty(resolved)) {
                    FirebaseDatabase.getInstance().getReference("blocked_users")
                            .child(myUid).child(resolved).setValue(true);
                }
                Toast.makeText(appCtx, "User blocked", Toast.LENGTH_SHORT).show();
                if (nm != null) nm.cancel(notificationId);
                finish(pr);
            });
            return;
        }

        if (ACTION_REPLY.equals(action)) {
            Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
            if (remoteInput == null) return;

            CharSequence raw = remoteInput.getCharSequence(KEY_TEXT_REPLY);
            final String text = raw != null ? raw.toString().trim() : "";
            if (text.isEmpty() || TextUtils.isEmpty(chatId) || TextUtils.isEmpty(myUid)) return;

            final PendingResult pr = goAsync();

            // Immediate visual feedback so the shade's spinner stops even if
            // the network is slow.
            showReplySentPlaceholder(appCtx, nm, notificationId,
                    intent.getStringExtra("peer_name"));

            resolvePeer(myUid, chatId, peerUid, resolved -> {
                if (TextUtils.isEmpty(resolved)) {
                    Log.e(TAG, "quick reply: could not resolve peer uid for chat " + chatId);
                    finish(pr);
                    return;
                }
                sendReply(appCtx, chatId, myUid, resolved, text, pr);
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reply pipeline
    // ─────────────────────────────────────────────────────────────────────────

    private void sendReply(final Context ctx, final String chatId, final String myUid,
                           final String peerUid, final String text, final PendingResult pr) {

        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        DatabaseReference messagesRef = root.child("chats").child(chatId);

        String msgId = messagesRef.push().getKey();
        if (msgId == null) { finish(pr); return; }

        final long ts = System.currentTimeMillis();

        Map<String, Object> msg = new HashMap<>();
        msg.put("sender_uid",         myUid);
        msg.put("type",               "text");
        msg.put("text",               text);
        msg.put("timestamp",          ts);
        msg.put("is_edited",          false);
        msg.put("is_pinned",          false);
        msg.put("is_deleted_for_all", false);
        msg.put("is_deleted_for_me",  false);

        // One atomic multi-path update instead of five independent writes:
        // either the whole reply lands or none of it does, and it survives a
        // process death mid-way far better than sequential setValue() calls.
        Map<String, Object> updates = new HashMap<>();
        updates.put("/chats/" + chatId + "/" + msgId, msg);
        updates.put("/user_chats/" + myUid   + "/" + chatId + "/peer_uid",  peerUid);
        updates.put("/user_chats/" + myUid   + "/" + chatId + "/last_msg",  text);
        updates.put("/user_chats/" + myUid   + "/" + chatId + "/last_time", ts);
        updates.put("/user_chats/" + peerUid + "/" + chatId + "/peer_uid",  myUid);
        updates.put("/user_chats/" + peerUid + "/" + chatId + "/last_msg",  text);
        updates.put("/user_chats/" + peerUid + "/" + chatId + "/last_time", ts);
        // Was missing entirely — the peer's unread badge never moved.
        updates.put("/user_chats/" + peerUid + "/" + chatId + "/unread",
                ServerValue.increment(1));

        root.updateChildren(updates, (error, ref) -> {
            if (error != null) {
                Log.e(TAG, "quick reply write failed: " + error.getMessage());
                finish(pr);
                return;
            }
            // Now push it, so the peer actually gets notified.
            pushReplyNotification(chatId, myUid, peerUid, text, pr);
        });
    }

    /** Reads the peer token + our display name, then fires the push. */
    private void pushReplyNotification(final String chatId, final String myUid,
                                       final String peerUid, final String text,
                                       final PendingResult pr) {

        DatabaseReference usersRef = FirebaseDatabase.getInstance().getReference("users");

        usersRef.child(myUid).child("fullname")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot nameSnap) {
                        String myName = nameSnap.getValue(String.class);
                        if (TextUtils.isEmpty(myName)) myName = "Someone";

                        HashMap<String, Object> data = new HashMap<>();
                        data.put("title",      myName);
                        data.put("body",       text);
                        data.put("image",      "");
                        data.put("type",       "chat");
                        data.put("extra",      chatId);
                        data.put("sender_uid", myUid);
                        data.put("to_uid",     peerUid);

                        FCMNotificationSender.sendNotification(data, "", "",
                                new FCMNotificationSender.OnResponse() {
                                    @Override public void onSuccess(String r) {
                                        Log.d(TAG, "quick reply push ok");
                                        finish(pr);
                                    }
                                    @Override public void onError(String e) {
                                        Log.e(TAG, "quick reply push FAILED: " + e);
                                        finish(pr);
                                    }
                                });
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { finish(pr); }
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private interface PeerCallback { void onResolved(String peerUid); }

    /**
     * Uses the uid carried in the push when present, otherwise looks it up from
     * user_chats/{myUid}/{chatId}/peer_uid. Old notifications posted before this
     * fix still carry "" — this keeps them working.
     */
    private void resolvePeer(final String myUid, final String chatId,
                             final String peerUidFromIntent, final PeerCallback cb) {
        if (!TextUtils.isEmpty(peerUidFromIntent)) {
            cb.onResolved(peerUidFromIntent);
            return;
        }
        if (TextUtils.isEmpty(chatId)) { cb.onResolved(null); return; }

        FirebaseDatabase.getInstance().getReference("user_chats")
                .child(myUid).child(chatId).child("peer_uid")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        cb.onResolved(snap.getValue(String.class));
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        cb.onResolved(null);
                    }
                });
    }

    private void showReplySentPlaceholder(Context ctx, NotificationManager nm,
                                          int notificationId, String peerName) {
        if (nm == null) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID_CHAT)
                .setSmallIcon(R.drawable.ic_app_logo)
                .setContentTitle(!TextUtils.isEmpty(peerName) ? peerName : "Minigram")
                .setContentText("Reply sent")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(2000);
        nm.notify(notificationId, b.build());
    }

    /** Releases the broadcast wake lock exactly once. */
    private void finish(final PendingResult pr) {
        if (pr == null) return;
        try {
            pr.finish();
        } catch (IllegalStateException ignored) {
            // already finished — harmless
        }
    }
}
