package com.bridge.mx;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
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
    }

    public interface Listener {
        void onChanged();
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Map<String, Row> rows = new LinkedHashMap<>();
    private static SharedPreferences prefs;

    public static void init(Context ctx) {
        if (prefs != null) return;
        prefs = ctx.getApplicationContext().getSharedPreferences("mxrows", Context.MODE_PRIVATE);
        load();
    }

    public static List<Row> all() {
        return new ArrayList<>(rows.values());
    }

    /** Drop all observed rows (test/maintenance helper; listeners untouched). */
    public static synchronized void clear() {
        rows.clear();
    }

    /** Remove library rows (key prefix "lib:") not seen in the latest scan. */
    public static synchronized void sweepLib(Set<String> keep) {
        Iterator<Map.Entry<String, Row>> it = rows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Row> e = it.next();
            if (e.getKey().startsWith("lib:") && !keep.contains(e.getKey())) it.remove();
        }
    }

    /** Insert or refresh a row observed from the live MX list. */
    public static synchronized void upsert(Row r) {
        Row old = rows.get(r.key);
        if (old != null) {
            r.applied = old.applied;
            r.matchTried = old.matchTried;
            // A text-only re-observation must not wipe a color-based verdict.
            if ("unknown".equals(r.state)
                    && ("finished".equals(old.state) || "none".equals(old.state))) {
                r.state = old.state;
            }
            if (r.rgb < 0) r.rgb = old.rgb;
            // Keep derived data unless the source title changed. Library rows are
            // keyed on the parsed series, so a newer (higher) episode filename
            // must not invalidate an existing MAL match.
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
                arr.put(o);
            }
            prefs.edit().putString("rows", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static void load() {
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
                rows.put(r.key, r);
            }
        } catch (Exception ignored) {
        }
    }

    public static void addListener(Listener l) {
        listeners.add(l);
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
}
