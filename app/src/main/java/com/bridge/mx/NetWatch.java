package com.bridge.mx;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * Single source of truth for "is the phone online right now", plus one
 * cheap default-network callback used by the background service.
 *
 * <p>The bridge must never confuse "MAL rejected a request" with "there is no
 * network", and it must not spend the 15 s connect timeout on every one of up
 * to 40 titles while the phone is offline. Work is paused, local tracking
 * continues, and a network callback wakes the service as soon as connectivity
 * returns. Auto-update is deliberately <b>not</b> changed while offline: a
 * user's setting must survive a temporary disconnect.
 */
public final class NetWatch {

    public interface Listener {
        void onOnlineChanged(boolean online);
    }

    private static ConnectivityManager.NetworkCallback callback;
    private static Listener listener;
    private static Context app;
    private static Boolean lastOnline;

    private NetWatch() {
    }

    /**
     * True only when a default network exists and advertises working internet.
     * On any inspection failure this fails open (returns true): an unknown
     * network state must not permanently block a user's sync.
     */
    public static boolean isOnline(Context c) {
        if (c == null) return true;
        try {
            Context appCtx = c.getApplicationContext();
            if (appCtx == null) appCtx = c;
            ConnectivityManager cm = (ConnectivityManager)
                    appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return false;
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return false;
            }
            // VALIDATED is Android's own "this network really reaches the
            // internet" flag. Without it a connected-but-dead Wi-Fi network
            // still reports as available and would start the timeout storm.
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable t) {
            Report.warn("net", "connectivity check failed: " + t);
            return true;
        }
    }

    /** Start observing the default network. Replaces any previous listener. */
    public static synchronized void start(Context c, Listener l) {
        stop();
        if (c == null || l == null) return;
        app = c.getApplicationContext();
        if (app == null) app = c;
        listener = l;
        lastOnline = isOnline(app);
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    notifyState();
                }

                @Override
                public void onLost(Network network) {
                    notifyState();
                }

                @Override
                public void onCapabilitiesChanged(Network network,
                                                  NetworkCapabilities capabilities) {
                    notifyState();
                }
            };
            cm.registerDefaultNetworkCallback(callback);
        } catch (Throwable t) {
            Report.warn("net", "network callback unavailable: " + t);
            callback = null;
        }
    }

    /** Stop observing. Safe to call when never started. */
    public static synchronized void stop() {
        if (callback != null && app != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager)
                        app.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(callback);
            } catch (Throwable ignored) {
            }
        }
        callback = null;
        listener = null;
        app = null;
        lastOnline = null;
    }

    private static void notifyState() {
        final Listener l;
        final boolean online;
        synchronized (NetWatch.class) {
            if (listener == null || app == null) return;
            online = isOnline(app);
            if (lastOnline != null && lastOnline == online) return;
            lastOnline = online;
            l = listener;
        }
        try {
            l.onOnlineChanged(online);
        } catch (Throwable t) {
            Report.err("net", "connectivity listener failed", t);
        }
    }
}
