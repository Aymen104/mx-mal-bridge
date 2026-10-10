package com.bridge.mx;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;

/** Persists the learned alias table in the app's "cfg" preferences. */
public class AliasStore {

    private static final String KEY = "learned_aliases";

    public static void load(Context c) {
        try {
            SharedPreferences p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
            String s = p.getString(KEY, null);
            if (s == null || s.isEmpty()) return;
            JSONObject o = new JSONObject(s);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Aliases.putLearned(k, o.optString(k));
            }
            Report.info("alias", "loaded " + o.length() + " learned alias(es)");
        } catch (Throwable t) {
            Report.err("alias", "load failed", t);
        }
    }

    public static void persist(Context c) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : Aliases.learned().entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
            c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                    .edit().putString(KEY, o.toString()).apply();
        } catch (Throwable t) {
            Report.err("alias", "persist failed", t);
        }
    }
}
