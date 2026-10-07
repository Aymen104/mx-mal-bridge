package com.bridge.mx;

import org.json.JSONArray;
import org.json.JSONObject;

/** Title similarity matching against MyAnimeList search results. */
public class Matcher {

    public static class Result {
        public int id;
        public String title;
        public int numEp = -1;
    }

    /** Best candidate scoring >= 0.50, or null. */
    public static Result best(JSONArray data, String want) throws Exception {
        if (data == null) return null;
        String w = norm(want);
        Result b = null;
        double bs = -1;
        for (int i = 0; i < data.length(); i++) {
            JSONObject node = data.getJSONObject(i).getJSONObject("node");
            String t = node.optString("title", "");
            double s = sim(w, norm(t));
            JSONObject alt = node.optJSONObject("alternative_titles");
            if (alt != null) {
                JSONArray syn = alt.optJSONArray("synonyms");
                if (syn != null) {
                    for (int k = 0; k < syn.length(); k++) {
                        s = Math.max(s, sim(w, norm(syn.getString(k))));
                    }
                }
                String en = alt.optString("en", "");
                if (!en.isEmpty()) s = Math.max(s, sim(w, norm(en)));
            }
            if (s > bs) {
                bs = s;
                b = new Result();
                b.id = node.getInt("id");
                b.title = t;
                b.numEp = node.optInt("num_episodes", -1);
            }
        }
        return bs >= 0.50 ? b : null;
    }

    private static String norm(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9\u0600-\u06FF]+", "");
    }

    private static double sim(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1.0;
        double base = 1.0 - ((double) levenshtein(a, b)) / Math.max(a.length(), b.length());
        double contain = 0;
        if (a.contains(b) || b.contains(a)) {
            int min = Math.min(a.length(), b.length());
            int max = Math.max(a.length(), b.length());
            contain = 0.75 + 0.25 * ((double) min / max);
        }
        return Math.max(base, contain);
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
