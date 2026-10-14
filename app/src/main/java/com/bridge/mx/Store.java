package com.bridge.mx;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the rows last observed in MX Player's file list plus the derived
 * parse/match/plan data. All writes are in-app only; nothing here ever writes
 * back into MX Player.
 *
 * States: new | watching (Last played) | finished (calibrated color) |
 * none (checked, no label) | unknown (not yet observed/classified)
 */
public class Store {

    public static class Row {
        public String key = "";
        public String title = "";
        public String duration = "";
        public String status = "";          // raw tv_status text
        public String state = "unknown";
        public int rgb = -1;                // sampled glyph color
        public int left, top, right, bottom; // title bounds in screen px
        public int ep = -1;
        public String parsedTitle = "";
        public int matchId = -1;
        public String matchTitle = "";
        public int numEp = -1;
        public String planStatus = "";
        public int planEps = -1;
        public String planText = "";
        public String applied = "";    // "status|eps" last pushed to MAL
        public String matchTried = ""; // parsedTitle we already searched for
        public int watchedPct = -1;    // max % of the episode played in MX (>=95 finished)
        public String playedAt = "";   // MX's "last played" label, e.g. "Played 1 day ago"
        public boolean fromLib = false; // true while only known from the MediaStore scan
    }

    public interface Listener {
        void onChanged();
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Map<String, Row> rows = new LinkedHashMap<>();
    /** Canonical keys of every episode video under Download (the allowlist). */
    private static final Set<String> libKeys = new HashSet<>();
    private static SharedPreferences prefs;

    /**
     * Snapshot of the user's own MAL list (matchId -> "status|eps"), refreshed
     * each tick. It is the source of truth: a computed plan must never read
     * LOWER than this, so the bridge only ever advances the user's list.
     */
    private static final Map<Integer, String> malCur = new LinkedHashMap<>();

    public static void init(Context ctx) {
        if (prefs != null) return;
        prefs = ctx.getApplicationContext().getSharedPreferences("mxrows", Context.MODE_PRIVATE);
        load();
    }

    public static List<Row> all() {
        return new ArrayList<>(rows.values());
    }

    /** Conservative identity normalization. */
    public static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\u0600-\u06FF]+", "");
    }

    /**
     * Canonical row identity: parsed series title + episode number when possible,
     * falling back to the whole filename. This prevents the same file appearing as
     * two rows (when MX does/doesn't show its duration label).
     */
    public static String canon(String filename) {
        return canon(EpisodeParser.parse(filename), filename);
    }

    public static String canon(EpisodeParser.Parsed p, String filename) {
        String nk = p == null ? "" : norm(p.title);
        if (nk.length() >= 3) return "ep:" + nk + "|" + (p.ep > 0 ? p.ep : 0);
        String fk = norm(filename);
        return "f:" + (fk.isEmpty() ? (filename == null ? "" : filename.trim()) : fk);
    }

    /** Drop all observed rows (test/maintenance helper; listeners untouched). */
    public static synchronized void clear() {
        rows.clear();
    }

    /**
     * Wipe all bridge history: observed rows and the cached MAL snapshot.
     *
     * <p>Used by the weekly retention purge. Aliases ({@link AliasStore}) and the
     * error log are deliberately left intact: they are learned corrections, not
     * history, and throwing them away would make the bridge re-mistake titles it
     * has already been taught. The MAL snapshot is simply re-fetched on the next
     * sync.
     *
     * @return number of rows removed
     */
    public static synchronized int wipe() {
        int n = rows.size();
        rows.clear();
        malCur.clear();
        if (prefs != null) {
            try {
                prefs.edit().putString("rows", "[]").putString("malcur", "").apply();
            } catch (Exception ignored) {
            }
        }
        return n;
    }

    /** Remove library rows (key prefix "lib:") not seen in the latest scan. */
    public static synchronized void sweepLib(Set<String> keep) {
        Iterator<Map.Entry<String, Row>> it = rows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Row> e = it.next();
            if (e.getValue().fromLib && !keep.contains(e.getKey())) it.remove();
        }
    }

    /** Replace the Download allowlist (canonical keys of every episode file). */
    public static synchronized void setLibKeys(Set<String> keys) {
        libKeys.clear();
        if (keys != null) libKeys.addAll(keys);
        if (prefs == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (String k : libKeys) arr.put(k);
            prefs.edit().putString("libkeys", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Drop observed (non-library) rows that have no place in the Download tree. */
    public static synchronized void sweepOutside() {
        Iterator<Map.Entry<String, Row>> it = rows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Row> e = it.next();
            if (!e.getValue().fromLib && ScopeGate.outside(libKeys, e.getKey())) it.remove();
        }
    }

    /** Insert or refresh a row observed from the live MX list. */
    public static synchronized void upsert(Row r) {
        // Download-only scope: accessibility observations expose only a display
        // title (no path), so an observed row must prove itself against the
        // Download library allowlist before it enters the store. Library rows
        // always pass — the scanner itself only ever walks the Download tree.
        if (!r.fromLib && ScopeGate.outside(libKeys, r.key)) return;
        Row old = rows.get(r.key);
        if (old != null) {
            r.applied = old.applied;
            r.matchTried = old.matchTried;
            // A library-only re-scan must never weaken a live MX verdict.
            if (r.fromLib && !old.fromLib && stateRank(old.state) > stateRank(r.state)) {
                r.state = old.state;
                r.rgb = old.rgb;
            }
            // A text-only re-observation must not wipe a color-based verdict.
            if ("unknown".equals(r.state)
                    && ("finished".equals(old.state) || "none".equals(old.state))) {
                r.state = old.state;
            }
            // Nor may the persistent "Played X ago" line downgrade a grey
            // "finished": on MX 3.3.1 a finished row still shows its played
            // date, so the text would otherwise stomp the tag the classifier
            // just read. The colour stays authoritative until the next
            // classify pass re-reads it (which can also move it back to
            // "watching" when the ink is blue again).
            if ("finished".equals(old.state)
                    && ("watching".equals(r.state) || "unknown".equals(r.state)
                        || "new".equals(r.state))) {
                r.state = "finished";
                if (r.rgb < 0) r.rgb = old.rgb;
            }
            if (r.rgb < 0) r.rgb = old.rgb;
            if (r.watchedPct < old.watchedPct) r.watchedPct = old.watchedPct;
            // Playback evidence outranks MX's NEW badge: the badge clears only
            // when MX re-reads the item, so a list re-read can arrive after the
            // sampler already saw the episode play. Never walk a watched row
            // back to "new" (that is what left freshly downloaded, watched
            // episodes looking undetected).
            if ("new".equals(r.state) && r.watchedPct > 0
                    && !"finished".equals(old.state)) {
                r.state = PlaybackTracker.isFinished(r.watchedPct)
                        ? "finished" : "watching";
            }
            // The played date is the list reader's persistent "touched" evidence:
            // an observation without a date must never erase one already known.
            if (r.playedAt.isEmpty()) r.playedAt = old.playedAt;
            // ...and a label-less re-read (MX hides the "last played" text while
            // scrolling) must not walk a known-played row back to "unknown".
            if (!r.playedAt.isEmpty() && "unknown".equals(r.state)
                    && ("watching".equals(old.state) || "finished".equals(old.state))) {
                r.state = old.state;
            }
            r.fromLib = r.fromLib && old.fromLib;
            // Keep derived data unless the source title changed.
            boolean sameSeries = r.title.equals(old.title)
                    || (!r.parsedTitle.isEmpty() && r.parsedTitle.equals(old.parsedTitle));
            if (sameSeries) {
                r.parsedTitle = old.parsedTitle;
                r.ep = old.ep;
                r.matchId = old.matchId;
                r.matchTitle = old.matchTitle;
                r.numEp = old.numEp;
                r.planStatus = old.planStatus;
                r.planEps = old.planEps;
                r.planText = old.planText;
            }
        }
        rows.put(r.key, r);
    }

    public static synchronized void save() {
        if (prefs == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Row r : rows.values()) {
                JSONObject o = new JSONObject();
                o.put("key", r.key);
                o.put("title", r.title);
                o.put("duration", r.duration);
                o.put("status", r.status);
                o.put("state", r.state);
                o.put("rgb", r.rgb);
                o.put("ep", r.ep);
                o.put("parsedTitle", r.parsedTitle);
                o.put("matchId", r.matchId);
                o.put("matchTitle", r.matchTitle);
                o.put("numEp", r.numEp);
                o.put("planStatus", r.planStatus);
                o.put("planEps", r.planEps);
                o.put("planText", r.planText);
                o.put("applied", r.applied);
                o.put("matchTried", r.matchTried);
                o.put("watchedPct", r.watchedPct);
                o.put("playedAt", r.playedAt);
                o.put("fromLib", r.fromLib);
                arr.put(o);
            }
            prefs.edit().putString("rows", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static void load() {
        try {
            String mc = prefs.getString("malcur", "");
            if (!mc.isEmpty()) {
                JSONObject o = new JSONObject(mc);
                for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    try {
                        malCur.put(Integer.parseInt(k), o.getString(k));
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            String s = prefs.getString("rows", "");
            if (s.isEmpty()) return;
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Row r = new Row();
                r.key = o.getString("key");
                r.title = o.optString("title", "");
                r.duration = o.optString("duration", "");
                r.status = o.optString("status", "");
                r.state = o.optString("state", "unknown");
                r.rgb = o.optInt("rgb", -1);
                r.ep = o.optInt("ep", -1);
                r.parsedTitle = o.optString("parsedTitle", "");
                r.matchId = o.optInt("matchId", -1);
                r.matchTitle = o.optString("matchTitle", "");
                r.numEp = o.optInt("numEp", -1);
                r.planStatus = o.optString("planStatus", "");
                r.planEps = o.optInt("planEps", -1);
                r.planText = o.optString("planText", "");
                r.applied = o.optString("applied", "");
                r.matchTried = o.optString("matchTried", "");
                r.watchedPct = o.optInt("watchedPct", -1);
                r.playedAt = o.optString("playedAt", "");
                r.fromLib = o.optBoolean("fromLib", false);
                rows.put(r.key, r);
            }
            dedupe();
            // Restore the Download allowlist so the observation gate works
            // before the first scan of this process.
            String lk = prefs.getString("libkeys", "");
            if (!lk.isEmpty()) {
                JSONArray lkArr = new JSONArray(lk);
                for (int i = 0; i < lkArr.length(); i++) libKeys.add(lkArr.getString(i));
            }
        } catch (Exception ignored) {
        }
    }

    public static void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    public static void notifyChanged() {
        for (Listener l : listeners) l.onChanged();
    }

    /** Background auto-sync opt-in (default off = dry-run). */
    public static boolean autoApply() {
        return prefs != null && prefs.getBoolean("auto_apply", false);
    }

    public static void setAutoApply(boolean v) {
        if (prefs != null) prefs.edit().putBoolean("auto_apply", v).apply();
    }

    /** Replace the stored MAL snapshot (matchId -> "status|eps") and persist it. */
    public static synchronized void setMalCur(Map<Integer, String> cur) {
        malCur.clear();
        if (cur != null) malCur.putAll(cur);
        if (prefs == null) return;
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<Integer, String> e : malCur.entrySet()) {
                o.put(String.valueOf(e.getKey()), e.getValue());
            }
            prefs.edit().putString("malcur", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Current MAL value for an anime as "status|eps", or null when unknown. */
    public static synchronized String malCur(int matchId) {
        return malCur.get(matchId);
    }

    /** finished > watching > present > new > none/unknown. */
    private static int stateRank(String st) {
        if ("finished".equals(st)) return 4;
        if ("watching".equals(st)) return 3;
        if ("present".equals(st)) return 2;
        if ("new".equals(st)) return 1;
        if ("none".equals(st)) return 1;
        return 0;
    }

    /**
     * Re-key every row onto its canonical identity and merge collisions (same
     * episode seen with/without duration, or lib+mx). Idempotent.
     */
    public static synchronized int dedupe() {
        int before = rows.size();
        Map<String, Row> out = new LinkedHashMap<>();
        for (Row r : rows.values()) {
            String k = canon(r.title);
            r.key = k;
            Row keep = out.get(k);
            if (keep == null) out.put(k, r);
            else merge(keep, r);
        }
        rows.clear();
        rows.putAll(out);
        return before - out.size();
    }

    private static void merge(Row keep, Row add) {
        if (stateRank(add.state) > stateRank(keep.state)) keep.state = add.state;
        if ((keep.duration == null || keep.duration.isEmpty())
                && add.duration != null && !add.duration.isEmpty()) {
            keep.duration = add.duration;
        }
        if (keep.rgb < 0 && add.rgb >= 0) keep.rgb = add.rgb;
        if (add.watchedPct > keep.watchedPct) keep.watchedPct = add.watchedPct;
        if ((keep.playedAt == null || keep.playedAt.isEmpty())
                && add.playedAt != null && !add.playedAt.isEmpty()) {
            keep.playedAt = add.playedAt;
        }
        if ((keep.parsedTitle == null || keep.parsedTitle.isEmpty())
                && add.parsedTitle != null && !add.parsedTitle.isEmpty()) {
            keep.parsedTitle = add.parsedTitle;
        }
        if (keep.ep < 0 && add.ep >= 0) keep.ep = add.ep;
        if (keep.matchId < 0 && add.matchId >= 0) {
            keep.matchId = add.matchId;
            keep.matchTitle = add.matchTitle;
            keep.numEp = add.numEp;
        }
        if (keep.applied.isEmpty()) keep.applied = add.applied;
        if (keep.matchTried.isEmpty()) keep.matchTried = add.matchTried;
        keep.fromLib = keep.fromLib && add.fromLib;
    }
}
