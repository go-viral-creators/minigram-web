package com.silu.minigram;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Automatic retry with exponential backoff for failed uploads (image/voice
 * sends). Plain Firebase writes (text messages, most database ops) already
 * get automatic offline queuing from the Firebase SDK itself — this exists
 * specifically for the gap Firebase doesn't cover: raw HTTP upload calls to
 * the Hostinger backend, which have no built-in retry of their own.
 *
 * Usage per screen (ChatActivity / ChannelActivity):
 *   retryQueue.enqueue(tempId, () -> _performRetry(tempId));  // on failure
 *   retryQueue.cancel(tempId);                                // on success or manual retry
 *   retryQueue.retryAllNow();                                 // on connection restored
 *   retryQueue.clear();                                       // in onDestroy
 *
 * Backoff schedule: 3s, 6s, 12s, 24s, 60s (capped), up to 5 attempts — after
 * that it stays in "failed" state for the user to manually retry, rather
 * than retrying forever in the background.
 */
public class UploadRetryQueue {

    public interface RetryAction { void retry(); }

    private static final int  MAX_ATTEMPTS   = 5;
    private static final long BASE_DELAY_MS  = 3000;
    private static final long MAX_DELAY_MS   = 60000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HashMap<String, Integer>     attemptCounts     = new HashMap<>();
    private final HashMap<String, RetryAction> pendingActions    = new HashMap<>();
    private final HashMap<String, Runnable>    scheduledRunnables = new HashMap<>();

    /** Call when an upload fails — schedules an automatic retry after a backoff delay. */
    public void enqueue(String id, RetryAction action) {
        int attempt = attemptCounts.containsKey(id) ? attemptCounts.get(id) : 0;
        if (attempt >= MAX_ATTEMPTS) return; // give up automatically retrying; stays failed for manual tap

        cancel(id); // clear any already-scheduled retry for this id first, avoid duplicates

        pendingActions.put(id, action);
        long delay = Math.min(BASE_DELAY_MS * (1L << attempt), MAX_DELAY_MS);

        Runnable r = () -> {
            scheduledRunnables.remove(id);
            RetryAction a = pendingActions.remove(id);
            if (a != null) {
                attemptCounts.put(id, attempt + 1);
                a.retry();
            }
        };
        scheduledRunnables.put(id, r);
        handler.postDelayed(r, delay);
    }

    /** Call when the item succeeds, or the user manually retries before the auto-retry — clears its scheduled retry. */
    public void cancel(String id) {
        Runnable r = scheduledRunnables.remove(id);
        if (r != null) handler.removeCallbacks(r);
        pendingActions.remove(id);
    }

    /** Call once an upload has genuinely succeeded — clears everything for this id, including the attempt count. */
    public void onSuccess(String id) {
        cancel(id);
        attemptCounts.remove(id);
    }

    /** Call when connectivity is restored — retries everything queued right now instead of waiting out timers. */
    public void retryAllNow() {
        for (String id : new ArrayList<>(pendingActions.keySet())) {
            Runnable r = scheduledRunnables.remove(id);
            if (r != null) handler.removeCallbacks(r);
            RetryAction a = pendingActions.remove(id);
            if (a != null) {
                int attempt = attemptCounts.containsKey(id) ? attemptCounts.get(id) : 0;
                attemptCounts.put(id, attempt + 1);
                a.retry();
            }
        }
    }

    /** Call from onDestroy() — cancels everything, no leaked callbacks. */
    public void clear() {
        for (Runnable r : scheduledRunnables.values()) handler.removeCallbacks(r);
        scheduledRunnables.clear();
        pendingActions.clear();
        attemptCounts.clear();
    }
}
