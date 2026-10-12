package com.bridge.mx;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Persistent per-episode playback ledger.
 *
 * <p>Key = {@code normalize(parsedTitle) + "|" + ep}; value = the MAX watched
 * percent ever observed for that episode (0..100). Observation comes from the
 * live MX Player screen (see {@link MxService#probePlayer}), so it survives
 * quitting MX and even a phone reboot.
 *
 * <p>This is deliberately independent of the rows MX happens to be showing:
 * an episode is "finished" the moment it is watched past
 * {@link PlaybackTracker#FINISH_PCT}%, whether or not its file still appears
 * in the list and regardless of how MX colours its tag.
 */
public final class Watched {

    private static SharedPreferences prefs;
    private static final Map<String, Integer> pct = new LinkedHashMap<>();

    private Watched() {
    }

    public static synchronized void init(Context ctx) {
        if (prefs != null) return;
        prefs = ctx.getApplicationContext()
                .getSharedPreferences("mxwatched", Context.MODE_PRIVATE);
        load();
    }

    /** Normalised parsed-series title used as the ledger's first key half. */
    static String norm(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    static String key(String parsedTitle, int ep) {
        return norm(parsedTitle) + "|" + ep;
    }

    /**
     * Record an observation for one episode, keeping the maximum.
     *
     * @param parsedTitle series title parsed out of the media filename
     * @param ep          episode number (use 0 for an unnumbered/movie file)
     * @param percent     watched percent 0..100 (ignored when &lt; 0)
     * @return true when this raised the stored maximum (or created the entry)
     */
    public static synchronized boolean record(String parsedTitle, int ep, int percent) {
        if (percent < 0) return false;
        if (parsedTitle == null || parsedTitle.isEmpty()) return false;
        if (ep < 0) ep = 0;
        String k = key(parsedTitle, ep);
        Integer old = pct.get(k);
        if (old != null && percent <= old) return false;
        pct.put(k, percent);
        save();
        return true;
    }

    /** Stored watched percent for an episode, or -1 when never observed. */
    public static synchronized int percent(String parsedTitle, int ep) {
        Integer v = pct.get(key(parsedTitle, ep < 0 ? 0 : ep));
        return v == null ? -1 : v;
    }

    /** Convenience: has this episode been watched to completion? */
    public static synchronized boolean finished(String parsedTitle, int ep) {
        return PlaybackTracker.isFinished(percent(parsedTitle, ep));
    }

    /** Every recorded episode of a series: episode number -&gt; watched percent. */
    public static synchronized Map<Integer, Integer> episodes(String parsedTitle) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        if (parsedTitle == null || parsedTitle.isEmpty()) return out;
        String pre = norm(parsedTitle) + "|";
        for (Map.Entry<String, Integer> e : pct.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(pre)) continue;
            try {
                out.put(Integer.parseInt(k.substring(pre.length())), e.getValue());
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static synchronized int size() {
        return pct.size();
    }

    /**
     * Wipe the whole watched ledger (used by the 7-day history purge).
     *
     * @return number of episode entries removed
     */
    public static synchronized int clear() {
        int n = pct.size();
        pct.clear();
        save();
        return n;
    }

    private static void save() {
        if (prefs == null) return;
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Integer> e : pct.entrySet()) o.put(e.getKey(), e.getValue());
            prefs.edit().putString("pct", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static void load() {
        if (prefs == null) return;
        try {
            String s = prefs.getString("pct", "");
            if (s.isEmpty()) return;
            JSONObject o = new JSONObject(s);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                pct.put(k, o.optInt(k, -1));
            }
        } catch (Exception ignored) {
        }
    }
}
