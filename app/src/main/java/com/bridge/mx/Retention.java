package com.bridge.mx;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Weekly history retention.
 *
 * <p>The user asked for the bridge's history to be cleared automatically every
 * seven days. "History" means everything the bridge has learned about what is
 * on the device: the observed {@link Store} rows (and the cached MAL snapshot)
 * plus the per-episode {@link Watched} ledger. Learned corrections
 * ({@code AliasStore}) and the error log are intentionally preserved.
 *
 * <p>The clock starts the first time the app runs: the first-ever call to
 * {@link #init(Context)} stamps "now" as the last purge. A purge then happens
 * as soon as {@code now - lastPurge >= 7 days}, and the stamp is advanced, so
 * the cycle repeats weekly rather than only once.
 *
 * <p>All state lives in the {@code mxmeta} preferences file under
 * {@code last_purge} (epoch millis).
 */
public final class Retention {

    /** One week, in milliseconds. */
    public static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String PREF = "mxmeta";
    private static final String KEY = "last_purge";

    private static SharedPreferences prefs;
    private static long lastPurge;

    private Retention() {
    }

    /** Load the cycle, stamping "now" on the very first run. Idempotent. */
    public static synchronized void init(Context ctx) {
        if (prefs == null) {
            prefs = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        }
        long v = prefs.getLong(KEY, 0L);
        if (v <= 0L) {
            v = System.currentTimeMillis();
            prefs.edit().putLong(KEY, v).apply();
        }
        lastPurge = v;
    }

    /** Epoch millis of the last purge (or first-run stamp). */
    public static synchronized long lastPurge() {
        return lastPurge;
    }

    /** Millis until the next scheduled purge (0 when due/overdue). */
    public static synchronized long nextPurgeInMs() {
        if (lastPurge <= 0L) return WEEK_MS;
        long due = lastPurge + WEEK_MS - System.currentTimeMillis();
        return due > 0 ? due : 0;
    }

    /** Compact human text such as "6d 3h" / "42m" / "due now". */
    public static String humanize(long ms) {
        if (ms <= 0) return "due now";
        long min = ms / 60000L;
        long h = min / 60L;
        long d = h / 24L;
        if (d > 0) return d + "d " + (h % 24) + "h";
        if (h > 0) return h + "h " + (min % 60) + "m";
        return Math.max(1, min) + "m";
    }

    /** True when a purge is due. Does not purge. */
    public static synchronized boolean due() {
        return lastPurge > 0L && System.currentTimeMillis() - lastPurge >= WEEK_MS;
    }

    /**
     * Purge now: wipe rows + MAL cache + watched ledger, then reset the clock.
     *
     * @return number of Store rows removed
     */
    public static synchronized int purgeNow(Context ctx) {
        if (prefs == null) init(ctx);
        int n = Store.wipe();
        Watched.clear();
        lastPurge = System.currentTimeMillis();
        if (prefs != null) prefs.edit().putLong(KEY, lastPurge).apply();
        Store.notifyChanged();
        Report.info("retention", "weekly purge: " + n + " rows cleared");
        return n;
    }

    /**
     * Purge only when the seven-day cycle has elapsed. Called on every service
     * connect and pipeline tick, so the purge happens on schedule in the
     * background.
     *
     * @return true when a purge actually ran
     */
    public static synchronized boolean purgeIfDue(Context ctx) {
        if (prefs == null) init(ctx);
        if (due()) {
            purgeNow(ctx);
            return true;
        }
        return false;
    }

    /** Short status for the UI, e.g. "purge in 6d 3h". */
    public static String statusLine() {
        return "purge in " + humanize(nextPurgeInMs()).toLowerCase(Locale.ROOT);
    }
}