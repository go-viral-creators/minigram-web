package com.silu.minigram;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;

/**
 * Watches for the device regaining network connectivity after having lost
 * it, and fires onConnectionRestored() at that exact moment — used so a
 * failed upload doesn't have to wait out its full backoff timer once the
 * network is actually back; it retries right away instead.
 *
 * Instance-scoped on purpose (not static) — each Activity that uses this
 * creates its own, registers in onCreate/onResume, unregisters in
 * onDestroy/onPause. A static/shared monitor would have multiple screens
 * stomping on each other's callback registration.
 */
public class ConnectionStateMonitor {

    public interface Listener { void onConnectionRestored(); }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback callback;
    private boolean everLostConnection = false; // only fire on RESTORE, not on the very first "connected" event at startup

    public ConnectionStateMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (callback != null) return; // already started
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        NetworkRequest request = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build();

        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (everLostConnection) {
                    mainHandler.post(listener::onConnectionRestored);
                }
            }
            @Override public void onLost(Network network) {
                everLostConnection = true;
            }
        };

        try {
            cm.registerNetworkCallback(request, callback);
        } catch (Exception ignored) {
            callback = null; // fail closed — no crash if the platform ever rejects this
        }
    }

    public void stop() {
        if (callback == null) return;
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
        }
        callback = null;
    }
}
