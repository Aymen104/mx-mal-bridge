package com.bridge.mx;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * Watchdog that restores the accessibility service when Android (realme's
 * background management) releases it while the phone is idle/offline.
 *
 * <p>Android does NOT rebind an accessibility service on its own after it has
 * been unbound - it stays dead until the user opens the app or toggles the
 * switch, which is why the bridge could sit for ~42 h doing nothing after the
 * phone spent the night offline (measured 2026-11-09 04:29 -> 2026-11-10
 * 23:04). This receiver re-arms the setting every {@link #PERIOD_MS} (inexact,
 * so the OS can batch it into a doze window - it costs nothing) and the system
 * rebinds the service the moment the value changes.
 *
 * <p>Nothing else happens here: no network, no scan, no plan. If everything is
 * healthy the receiver does two settings reads and returns.
 */
public class AutoRecoverReceiver extends BroadcastReceiver {

    private static final String TAG = "AutoRecover";
    private static final String ACTION = "com.bridge.mx.AUTO_RECOVER";
    /** 20 minutes: cheap enough to be free, fast enough to feel automatic. */
    static final long PERIOD_MS = 20 * 60 * 1000L;

    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            boolean ok = AccessibilityHelper.isEnabled(c);
            if (!ok) {
                ok = AccessibilityHelper.ensureEnabled(c);
                Report.info("service", ok
                        ? "watchdog re-enabled the accessibility service"
                        : "watchdog: cannot re-enable (needs WRITE_SECURE_SETTINGS;"
                          + " granted once with: adb shell pm grant com.bridge.mx"
                          + " android.permission.WRITE_SECURE_SETTINGS)");
            }
            schedule(c);
        } catch (Throwable t) {
            Log.w(TAG, "onReceive failed", t);
            try { schedule(c); } catch (Throwable ignored) { }
        }
    }

    /** (Re)arm the periodic check. Idempotent. */
    public static void schedule(Context c) {
        try {
            Intent i = new Intent(c, AutoRecoverReceiver.class);
            i.setAction(ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(c, 0, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + PERIOD_MS, PERIOD_MS, pi);
        } catch (Throwable t) {
            Log.w(TAG, "schedule failed", t);
        }
    }
}
