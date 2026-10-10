package com.bridge.mx;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shorthand aliases that MyAnimeList's search cannot recover on its own.
 *
 * <p>Some download sites name a series with an abbreviation, e.g. Anime-Sanka's
 * "Gin S1" = Gintama. Two sources are consulted:
 * <ul>
 *   <li>a built-in table of user-confirmed shorthands, and</li>
 *   <li>a learned table populated when the deterministic matcher or the local
 *       LLM successfully resolves a title (see {@link #learn}).</li>
 * </ul>
 *
 * <p>A trailing season marker ("S1", "S2", ...) on the shorthand is ignored, so
 * learning "Gin S1" also covers "Gin S2". An unrelated title that merely starts
 * with a key (e.g. "Gin no Saji") is never rewritten, because the whole
 * normalised title must equal the key or the key plus a season marker.
 *
 * <p>Kept free of Android types; persistence lives in {@link AliasStore}.
 */
public class Aliases {

    private static final Map<String, String> BUILTIN = new LinkedHashMap<>();
    private static final Map<String, String> LEARNED = new LinkedHashMap<>();

    static {
        // shorthand -> canonical MAL title
        BUILTIN.put("gin", "Gintama");
    }

    /** Canonical search title for a shorthand, or the input unchanged. */
    public static synchronized String apply(String title) {
        if (title == null) return "";
        String n = norm(title);
        String hit = lookup(LEARNED, n);
        if (hit == null) hit = lookup(BUILTIN, n);
        return hit != null ? hit : title;
    }

    /** Remember that a shorthand resolves to a canonical title. */
    public static synchronized void learn(String shorthand, String canonical) {
        if (shorthand == null || canonical == null || canonical.isEmpty()) return;
        String k = norm(shorthand).replaceAll("s\\d{1,2}$", "");
        if (k.length() < 3) return;
        LEARNED.put(k, canonical);
    }

    /** In-memory learned aliases, for persistence. */
    public static synchronized Map<String, String> learned() {
        return new LinkedHashMap<>(LEARNED);
    }

    /** Seed a learned alias (used when reloading from storage). */
    public static synchronized void putLearned(String key, String canonical) {
        if (key == null || key.isEmpty() || canonical == null || canonical.isEmpty()) return;
        LEARNED.put(key, canonical);
    }

    private static String lookup(Map<String, String> map, String n) {
        for (Map.Entry<String, String> e : map.entrySet()) {
            String k = e.getKey();
            if (n.equals(k) || n.matches(k + "s\\d{1,2}")) return e.getValue();
        }
        return null;
    }

    private static String norm(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9\u0600-\u06FF]+", "");
    }
}
