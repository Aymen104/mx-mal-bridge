package com.bridge.mx;

import java.util.List;

/**
 * Grounded prompt construction and response parsing for the local-LLM match
 * fallback. Kept free of Android types so it can be unit tested on the JVM.
 *
 * <p>The model is never asked "what is the MAL id of X" (which invites
 * hallucination). It is handed the parsed title, the episode number and the
 * <em>real</em> candidates returned by MyAnimeList's own search, and asked only
 * to pick one index or reject them all.
 *
 * <p>Each candidate also carries MAL's own structure (media type, airing
 * status, episode count) so the model can tell a series apart from its
 * sequels/prequels/OVAs/movies.
 */
public class Grounding {

    /** Build the strict, short prompt a small instruction model can follow. */
    public static String buildPrompt(String want, int ep, List<MatchBrain.Candidate> cands) {
        StringBuilder sb = new StringBuilder();
        sb.append("You match a downloaded anime file to the correct MyAnimeList entry.\n");
        sb.append("Title: ").append(want == null ? "" : want).append('\n');
        if (ep > 0) {
            sb.append("Highest episode number seen: ").append(ep)
                    .append(" (the correct entry must have at least this many episodes)\n");
        }
        sb.append("Rules:\n");
        sb.append("- Pick the number of the single best candidate below.\n");
        sb.append("- Prefer the main TV series. A sequel, later season, part, movie, "
                + "OVA or special is correct only if the title above names it.\n");
        sb.append("- Never pick a candidate that has not aired yet, or one with fewer "
                + "episodes than the number above.\n");
        sb.append("Candidates:\n");
        for (int i = 0; i < cands.size(); i++) {
            MatchBrain.Candidate c = cands.get(i);
            sb.append(i).append(". ").append(c.title);
            sb.append(" (id=").append(c.id);
            if (c.numEp > 0) sb.append(", episodes=").append(c.numEp);
            sb.append(")");
            if (!c.mediaType.isEmpty() || !c.status.isEmpty()) {
                sb.append(" [");
                if (!c.mediaType.isEmpty()) sb.append(c.mediaType);
                if (!c.status.isEmpty()) {
                    if (!c.mediaType.isEmpty()) sb.append(' ');
                    sb.append(c.status);
                }
                sb.append(']');
            }
            sb.append('\n');
        }
        sb.append("Reply with ONLY the number of the best match, or -1 if none fit. Answer:");
        return sb.toString();
    }

    /**
     * Parse the model's choice. Accepts "2", "Answer: 2", " 2." etc.
     *
     * @return index in [0, n), or -1 if the reply is missing/out of range.
     */
    public static int parseChoice(String response, int n) {
        if (response == null || n <= 0) return -1;
        String r = response.trim();
        int a = r.toLowerCase().lastIndexOf("answer");
        String tail = a >= 0 ? r.substring(a + "answer".length()) : r;
        Integer v = firstInt(tail);
        if (v == null) v = firstInt(r); // model dropped the "Answer:" cue
        if (v == null || v < 0 || v >= n) return -1;
        return v;
    }

    /** First integer token in a string (supports a leading minus sign). */
    static Integer firstInt(String s) {
        int i = 0, len = s.length();
        while (i < len) {
            char ch = s.charAt(i);
            if (Character.isDigit(ch) || (ch == '-' && i + 1 < len
                    && Character.isDigit(s.charAt(i + 1)))) {
                int j = i + 1;
                while (j < len && Character.isDigit(s.charAt(j))) j++;
                try {
                    return Integer.parseInt(s.substring(i, j));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            i++;
        }
        return null;
    }
}
