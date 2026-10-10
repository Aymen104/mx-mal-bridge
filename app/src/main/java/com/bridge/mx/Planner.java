package com.bridge.mx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the rows observed in MX Player into planned MyAnimeList writes.
 *
 * <p>How far a series has been <em>watched</em> is taken ONLY from MX Player's
 * own state, never from the fact that files happen to sit on the device:
 *
 * <pre>
 *   finished -> that episode was watched to the end  -> counts as ep
 *   watching -> the user is currently ON that episode -> counts as (ep - 1)
 *   new      -> never opened                          -> counts as nothing
 *   present  -> a file exists on the device (MediaStore scan) -> NOT watched
 * </pre>
 *
 * <p>"present" (downloaded) rows exist so a series the user has on disk is at
 * least put on the list, but a downloaded file is not an episode watched. So a
 * fully downloaded, never-opened series becomes "watching, 0" - and it is only
 * marked "completed" once MX Player shows it watched through the final episode.
 * This is the fix for a fully-downloaded series being pushed as completed while
 * it was still unfinished in MX Player.
 *
 * <p>Rows are grouped by matched MAL id, so a multi-episode series produces ONE
 * write. The highest-watched row carries the plan.
 *
 * <p>Plan is pure computation; nothing is sent from here, so rebuild() is a dry run.
 */
public class Planner {

    public static void rebuild() {
        // ---- per-row parse + stale-match reset ----
        for (Store.Row r : Store.all()) {
            EpisodeParser.Parsed p = EpisodeParser.parse(r.title);
            if (!p.title.equals(r.parsedTitle)) {
                // source title changed -> stale match
                r.matchId = -1;
                r.matchTitle = "";
                r.numEp = -1;
                r.parsedTitle = p.title;
            }
            r.ep = p.ep;

            r.planStatus = "";
            r.planEps = -1;
            r.planText = "";
        }

        // ---- group matched rows by MAL id ----
        Map<Integer, List<Store.Row>> groups = new LinkedHashMap<>();
        for (Store.Row r : Store.all()) {
            if (r.matchId <= 0) continue;
            List<Store.Row> g = groups.get(r.matchId);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(r.matchId, g);
            }
            g.add(r);
        }

        for (List<Store.Row> g : groups.values()) {
            boolean anyNew = false;
            boolean anyPresent = false;
            boolean anyWatch = false;
            boolean sawFinished = false;
            boolean sawWatching = false;
            int watched = -1;
            Store.Row repWatch = null;
            Store.Row repPresent = null;

            for (Store.Row r : g) {
                if ("new".equals(r.state)) anyNew = true;
                if ("present".equals(r.state)) {
                    anyPresent = true;
                    if (repPresent == null || r.ep > repPresent.ep) repPresent = r;
                }
                if ("finished".equals(r.state)) sawFinished = true;
                if ("watching".equals(r.state)) sawWatching = true;

                int w = watchedFrom(r);
                if (w >= 0) {
                    anyWatch = true;
                    if (w > watched) {
                        watched = w;
                        repWatch = r;
                    }
                } else if (isWatchedState(r.state)) {
                    anyWatch = true; // played/watched but no episode number in the name
                }
            }

            if (!anyWatch && !anyPresent && !anyNew) continue; // no reliable signal

            Store.Row rep = repWatch != null ? repWatch
                    : (repPresent != null ? repPresent : g.get(0));
            int numEp = rep.numEp;

            if (watched >= 0 && numEp > 0 && watched >= numEp) {
                // Completed ONLY on real watch evidence reaching the last episode.
                rep.planStatus = "completed";
                rep.planEps = numEp;
                rep.planText = "\u2192 completed, " + numEp + "/" + numEp
                        + " (watched in MX)";
            } else if (watched >= 0) {
                rep.planStatus = "watching";
                rep.planEps = Math.max(0, watched);
                rep.planText = "\u2192 watching, " + rep.planEps
                        + (numEp > 0 ? "/" + numEp : "")
                        + " (watched in MX)";
            } else if (numEp == 1 && sawFinished) {
                // A finished single-episode work (movie/special) is done even
                // when its filename carries no episode number.
                rep.planStatus = "completed";
                rep.planEps = 1;
                rep.planText = "\u2192 completed, 1/1 (watched in MX)";
            } else if (numEp == 1 && sawWatching) {
                // Started but not finished.
                rep.planStatus = "watching";
                rep.planEps = 0;
                rep.planText = "\u2192 watching, 0/1 (watched in MX)";
            } else if (anyWatch) {
                // Played/watched with no episode number: cannot say how far the
                // series got, so never push (this created a bogus 0-episode MAL
                // entry in an earlier build).
                rep.planText = "watched, no episode # \u2014 not pushed";
            } else if (anyPresent) {
                // Files on disk but nothing watched: on the list, zero watched.
                rep.planStatus = "watching";
                rep.planEps = 0;
                rep.planText = "\u2192 watching, 0" + (numEp > 0 ? "/" + numEp : "")
                        + " (downloaded, not watched)";
            } else {
                // MAL API enum is plan_to_watch (shown as "Planning" in the UI)
                rep.planStatus = "plan_to_watch";
                rep.planEps = 0;
                rep.planText = "\u2192 planned (0 eps)";
            }
        }
    }

    /**
     * Episodes provably watched, from MX Player's per-row state alone.
     * "finished" was watched to the end; "watching" is the episode the user is
     * currently on, so only the ones before it are done. Returns -1 when the
     * row carries no usable watch signal / episode number.
     */
    private static int watchedFrom(Store.Row r) {
        if (r.ep <= 0) return -1;
        if ("finished".equals(r.state)) return r.ep;
        if ("watching".equals(r.state)) return r.ep - 1;
        return -1;
    }

    /** MX states that mean "this file was opened/played" (watch evidence). */
    private static boolean isWatchedState(String state) {
        return "finished".equals(state) || "watching".equals(state);
    }

    /**
     * True when pushing (status, eps) would REDUCE what MAL already records:
     * a manually completed anime, or a higher watched count. The estimate must
     * never undo the user's own edits.
     *
     * @param mal current MAL value "status|num_watched" (nullable)
     */
    public static boolean wouldDowngrade(String mal, String status, int eps) {
        if (mal == null) return false;
        int bar = mal.indexOf('|');
        if (bar < 0) return false;
        String st = mal.substring(0, bar);
        int nw;
        try {
            nw = Integer.parseInt(mal.substring(bar + 1));
        } catch (Exception e) {
            return false;
        }
        if ("completed".equals(st) && !"completed".equals(status)) return true;
        if ("completed".equals(status)) return false;
        return eps >= 0 && eps < nw;
    }
}
