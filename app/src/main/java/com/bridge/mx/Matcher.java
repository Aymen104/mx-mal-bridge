package com.bridge.mx;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Title similarity matching against MyAnimeList search results. */
public class Matcher {

    /** A candidate name, score at which it was ranked. */
    public static class Result {
        public int id;
        public String title;
        public int numEp = -1;
        /** MAL media_type: "tv", "movie", "ova", "ona", "special", "music", ... */
        public String mediaType = "";
        /** MAL airing status: "finished_airing", "currently_airing", "not_yet_aired". */
        public String status = "";
    }

    /** A Result plus the deterministic score that produced it. */
    public static class Scored {
        public final Result r;
        public final double s;

        Scored(Result r, double s) {
            this.r = r;
            this.s = s;
        }
    }

    /** Score at/above which the deterministic result is trusted without the LLM. */
    private static final double CONFIDENT = 0.85;
    /** Minimum score for a fallback match when no brain answers. */
    private static final double ACCEPT = 0.50;
    /** How many top candidates to offer a brain. */
    private static final int BRAIN_TOP_N = 8;

    /** Backwards-compatible entry point (no episode hint). */
    public static Result best(JSONArray data, String want) throws Exception {
        return best(data, want, -1);
    }

    public static Result best(JSONArray data, String want, int ep) throws Exception {
        List<Scored> ranked = rank(data, want, ep);
        return (!ranked.isEmpty() && ranked.get(0).s >= ACCEPT) ? ranked.get(0).r : null;
    }

    /**
     * Deterministic match, falling back to a grounded {@link MatchBrain} when
     * the best score is not confident. The brain only ever chooses among the
     * real candidates produced here, so it can never fabricate a MAL id.
     */
    public static Result bestWithBrain(JSONArray data, String want, int ep,
                                       MatchBrain brain) throws Exception {
        List<Scored> ranked = rank(data, want, ep);
        if (ranked.isEmpty()) return null;

        if (ranked.get(0).s >= CONFIDENT && plausible(ranked.get(0).r, want, ep)) {
            return ranked.get(0).r;
        }

        // Only entries structurally compatible with what we see on disk are
        // offered to the brain: a file at episode N cannot belong to a shorter
        // season, an entry that has not aired, a movie, or a later
        // season/part/side-story the file's own title does not name. This is
        // how MyAnimeList's entry structure is enforced before the model votes.
        List<Scored> pool = new ArrayList<>();
        for (Scored sc : ranked) {
            if (plausible(sc.r, want, ep)) pool.add(sc);
        }
        if (pool.isEmpty()) pool = ranked; // nothing better to offer

        if (brain != null && brain.ready()) {
            List<MatchBrain.Candidate> cands = new ArrayList<>();
            int n = Math.min(pool.size(), BRAIN_TOP_N);
            for (int i = 0; i < n; i++) {
                Result r = pool.get(i).r;
                cands.add(new MatchBrain.Candidate(r.id, r.title, r.numEp, r.mediaType, r.status));
            }
            int pick = brain.pick(want, ep, cands);
            if (pick >= 0 && pick < n) return pool.get(pick).r;
        }

        // Deterministic fallback, preferring a structurally compatible entry
        // over the raw top score (which may be a sequel/movie/unaired entry).
        for (Scored sc : ranked) {
            if (plausible(sc.r, want, ep) && sc.s >= ACCEPT) return sc.r;
        }
        return ranked.get(0).s >= ACCEPT ? ranked.get(0).r : null;
    }

    /**
     * True when a candidate can actually be the series a file came from, given
     * the highest episode number seen. Encodes MAL's entry structure:
     *
     * <ul>
     *   <li>an entry that has not aired yet cannot contain a file we already have;</li>
     *   <li>an entry shorter than the episode we are holding is the wrong season;</li>
     *   <li>a multi-episode file is never a movie;</li>
     *   <li>a later season / part / side-story / movie is only valid when the
     *       file's own title names it too.</li>
     * </ul>
     */
    static boolean plausible(Result r, String want, int ep) {
        if ("not_yet_aired".equals(r.status)) return false;
        if (ep > 0 && r.numEp > 0 && r.numEp < ep) return false;
        if (ep > 1 && "movie".equals(r.mediaType)) return false;
        if (!hasEntryMarker(want) && hasEntryMarker(r.title)) return false;
        return true;
    }

    /**
     * Does a title carry a sequel / later-part / movie marker? Used to tell the
     * base series apart from its continuations when the two share a name.
     */
    static boolean hasEntryMarker(String title) {
        if (title == null || title.isEmpty()) return false;
        return ENTRY_MARKER.matcher(title).find();
    }

    private static final java.util.regex.Pattern ENTRY_MARKER =
            java.util.regex.Pattern.compile("(?i)"
                    + "(?:\\b(?:2nd|3rd|4th|5th|6th|second|third|fourth|final)\\s+season\\b)"
                    + "|(?:\\bseason\\s*[2-9]\\b)"
                    + "|(?:\\bpart\\s*[2-9]\\b)"
                    + "|(?:\\b(?:ii|iii|iv)\\b)"
                    + "|(?:\\bmovie\\b)"
                    + "|(?:\\bgekijouban\\b)"
                    + "|(?:\\brecap\\b)"
                    + "|(?:\\bgaiden\\b)");

    /**
     * Rank every candidate by similarity, best first. Signals combined per
     * candidate: edit distance, whole-string containment and token overlap
     * (so word order / extra words matter less). When the highest episode
     * number seen is known, a candidate whose total episode count is below it
     * is penalised, so a too-short season cannot win over the full series.
     */
    public static List<Scored> rank(JSONArray data, String want, int ep) throws Exception {
        List<Scored> out = new ArrayList<>();
        if (data == null) return out;
        String w = norm(want);
        for (int i = 0; i < data.length(); i++) {
            JSONObject node = data.getJSONObject(i).getJSONObject("node");
            String t = node.optString("title", "");
            int numEp = node.optInt("num_episodes", -1);
            String mediaType = node.optString("media_type", "");
            String status = node.optString("status", "");
            double s = score(want, w, t);
            JSONObject alt = node.optJSONObject("alternative_titles");
            if (alt != null) {
                JSONArray syn = alt.optJSONArray("synonyms");
                if (syn != null) {
                    for (int k = 0; k < syn.length(); k++) {
                        s = Math.max(s, score(want, w, syn.getString(k)));
                    }
                }
                s = Math.max(s, score(want, w, alt.optString("en", "")));
                s = Math.max(s, score(want, w, alt.optString("ja", "")));
            }
            s = adjust(s, want, t, ep, numEp, mediaType, status);
            Result r = new Result();
            r.id = node.getInt("id");
            r.title = t;
            r.numEp = numEp;
            r.mediaType = mediaType;
            r.status = status;
            out.add(new Scored(r, s));
        }
        Collections.sort(out, Comparator.comparingDouble((Scored x) -> x.s).reversed());
        return out;
    }

    /**
     * Nudge a raw similarity score using MyAnimeList's entry structure so the
     * main series wins over its own continuations/spin-offs when the file name
     * alone is ambiguous (e.g. "Friren" must land on the 28-episode TV series,
     * not its unaired "-hen" sequel).
     */
    static double adjust(double s, String want, String candTitle, int ep,
                         int numEp, String mediaType, String status) {
        // Cannot hold episode N of an entry that only has fewer than N.
        if (ep > 0 && numEp > 0 && numEp < ep) s *= 0.30;
        // An entry that has not aired cannot contain a file we already have.
        if ("not_yet_aired".equals(status)) s *= 0.20;
        // A multi-episode file is never a movie.
        if (ep > 1 && "movie".equals(mediaType)) s *= 0.30;
        // Later season / part / movie only when the file names it too.
        if (!hasEntryMarker(want) && hasEntryMarker(candTitle)) s *= 0.45;
        return s;
    }

    /** One comparison channel: edit distance, containment and token overlap. */
    private static double score(String wantRaw, String wNorm, String candidateRaw) {
        if (candidateRaw == null || candidateRaw.isEmpty()) return 0;
        double s = sim(wNorm, norm(candidateRaw));
        double tok = tokenDice(wantRaw, candidateRaw);
        if (tok >= 0.60) s = Math.max(s, tok);
        return s;
    }

    static String norm(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9\u0600-\u06FF]+", "");
    }

    static double sim(String a, String b) {
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

    /** Dice coefficient over word tokens (order-independent). */
    static double tokenDice(String a, String b) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        int inter = 0;
        for (String x : ta) {
            if (tb.contains(x)) inter++;
        }
        return 2.0 * inter / (ta.size() + tb.size());
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String t : s.toLowerCase().split("[^a-z0-9\u0600-\u06FF]+")) {
            if (t.length() >= 2) out.add(t);
        }
        return out;
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
