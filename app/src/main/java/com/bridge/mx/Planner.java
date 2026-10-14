package com.bridge.mx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the rows observed in MX Player into planned MyAnimeList writes.
 *
 * <p>Per-episode tracking: every file is ONE episode and its evidence decides
 * how much of it counts as watched:
 *
 * <pre>
 *   finished (gray tag) -> episode watched to end (95%+ playback)  -> N watched
 *   watching (last played) -> episode reached, in progress         -> N-1 watched
 *   present (downloaded) -> file exists, NO play evidence          -> 0 watched
 *   new (NEW badge)       -> never opened                          -> 0 watched
 * </pre>
 *
 * <p>Only genuine play evidence advances progress. Merely having a downloaded
 * file ("present") is NOT watch evidence: a batch the user has not started is
 * "planned", never "watching N" (2026-10-14).
 *
 * <p><b>Two independent observers, one per-episode truth.</b> The MX list
 * reader and the playback sampler watch the same playback from two angles and
 * are joined per {@code (norm(series), episode)}:
 *
 * <ul>
 *   <li><b>list reader</b> ({@link MxService#readRow}) — qualitative and
 *       persistent: a NEW badge (never opened), a "last played" date (opened at
 *       least once, now kept as {@link Store.Row#playedAt}), or neither. It
 *       survives app restarts and gaps in sampling.</li>
 *   <li><b>player sampler</b> ({@link MxService#probePlayer} and the
 *       MediaSession reader → {@link Watched}) — quantitative and live: the max
 *       percent observed, i.e. how far the episode was played.</li>
 * </ul>
 *
 * <p>The relation, resolved strongest-evidence-first: a grey "finished" tag or
 * a ledger percent &ge; {@link PlaybackTracker#FINISH_PCT} makes the episode
 * itself watched (N); a last-played date or any ledger percent &gt; 0 makes it
 * "reached" (N-1); a file only on disk is "present" (0 — downloaded is not
 * watched); a NEW badge with no play evidence is nothing (0). The list answers
 * "was it touched?" when the sampler was silent, and the sampler answers "how
 * far?" when the list is blank — neither alone is complete.
 *
 * <p>The series progress is the MAXIMUM over its unique episodes: reaching
 * episode N (watching) means the user watched through N-1, so ep N contributes
 * N-1; a finished episode N contributes N; a merely downloaded (present) or
 * never-opened (new) episode contributes 0. No "highest episode = all previous
 * watched" assumption; a lower finished episode never inflates a higher
 * downloaded-but-unplayed one.
 *
 * <p>A series is "completed" ONLY when the FINAL episode (MAL total when
 * known) carries the "finished" tag. A downloaded final episode without the
 * finished tag is not progress at all, and when the total is unknown the series
 * is never auto-completed.
 *
 * <p>Rows are grouped by matched MAL id, so a multi-episode series produces
 * ONE write, carried by the row that achieved the highest progress (ties go to
 * the row with the strongest evidence state).
 *
 * <p>Plan is pure computation; nothing is sent from here, so rebuild() is a dry run.
 */
public class Planner {

    public static void rebuild() {
        // Playback is the strongest single-episode evidence: pull it onto the
        // rows first, so an episode the sampler watched before the list reader
        // ever saw its row still reaches the right state and percent (and so a
        // partially-watched episode is never left looking "new").
        Progress.hydrate();
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

        for (Map.Entry<Integer, List<Store.Row>> gentry : groups.entrySet()) {
            int groupMatchId = gentry.getKey();
            List<Store.Row> g = gentry.getValue();
            int malTotal = 0;            // MAL total episodes (0 = unknown/airing)
            int groupMaxEp = 0;          // highest episode number seen in MX
            int series = 0;              // max per-episode contribution
            boolean reachedAny = false;  // any finished/watching (real play) evidence
            boolean onlyNew = false;     // evidence is only "new"/"present" (no play)
            boolean finalFinished = false; // the final episode carries "finished"
            Store.Row rep = null;
            int repContrib = -1;

            // MAL total first: a single-entry title (movie) or a feature-length
            // file carries no episode numbering, so a digit in its name is a
            // title artefact, not an episode. This is what "Shibou Yuugi de Meshi
            // wo Kuu. 44: Cloudy Beach" showed: a 1-episode MOVIE whose "44" is
            // part of the title, wrongly written as "44 episodes watched".
            for (Store.Row r : g) {
                if (r.numEp > malTotal) malTotal = r.numEp;
            }

            for (Store.Row r : g) {
                int ef = effectiveEpisode(r, malTotal);
                if (ef <= 0) {
                    // Unnumbered (or feature-length) file: only meaning for an
                    // explicitly finished single-episode entry, handled below.
                    continue;
                }
                if (ef > groupMaxEp) groupMaxEp = ef;

                String state = fuseState(r.state, r.playedAt, r.watchedPct);
                int contrib = episodeValue(state, ef);
                if (contrib > series) series = contrib;
                if ("finished".equals(state) || "watching".equals(state)) {
                    reachedAny = true;
                } else if ("new".equals(state) || "present".equals(state)) {
                    // present = on disk only; like a NEW badge it carries no
                    // watch evidence, so the best it can mean is "planned".
                    onlyNew = true;
                }
                // Carrier row = the one that achieved the highest progress;
                // ties go to the strongest evidence state.
                if (contrib > repContrib
                        || (contrib == repContrib && statePriority(state)
                            > (rep == null ? -1 : statePriority(rep.state)))) {
                    rep = r;
                    repContrib = contrib;
                }
            }

            // Live playback evidence from the Watched ledger: an episode watched
            // in MX counts even when its file is not (or no longer) in the list.
            // >=95% -> the episode itself; partial -> the episodes before it.
            LinkedHashMap<String, Boolean> seriesTitles = new LinkedHashMap<>();
            for (Store.Row r : g) {
                if (!r.parsedTitle.isEmpty()) seriesTitles.put(Watched.norm(r.parsedTitle), true);
            }
            for (String st : seriesTitles.keySet()) {
                for (Map.Entry<Integer, Integer> en : Watched.episodes(st).entrySet()) {
                    int e = en.getKey();
                    if (e <= 0) continue;
                    if (e > groupMaxEp) groupMaxEp = e;
                    int contrib = PlaybackTracker.isFinished(en.getValue())
                            ? e : Math.max(e - 1, 0);
                    if (contrib > series) series = contrib;
                    reachedAny = true;
                    if (rep == null) rep = g.get(0);
                    if (contrib > repContrib) repContrib = contrib;
                }
            }

            if (rep == null) {
                // No numbered episode at all: an unnumbered "finished" file on a
                // single-episode MAL entry is a fully watched movie.
                if (malTotal == 1) {
                    Store.Row movie = null;
                    for (Store.Row r : g) {
                        if (effectiveEpisode(r, malTotal) <= 0 && "finished".equals(r.state)) {
                            movie = r;
                            break;
                        }
                    }
                    if (movie != null) {
                        movie.planStatus = "completed";
                        movie.planEps = 1;
                        movie.planText = "\u2192 completed, 1/1 (movie finished in MX)";
                    }
                }
                continue;
            }

            // Final episode = MAL total when known, else the highest MX episode.
            int finalEp = malTotal > 0 ? malTotal : groupMaxEp;
            if (finalEp > 0) {
                for (Store.Row r : g) {
                    if (r.ep == finalEp
                            && "finished".equals(fuseState(r.state, r.playedAt, r.watchedPct))) {
                        finalFinished = true;
                        break;
                    }
                }
                if (!finalFinished) {
                    for (String st : seriesTitles.keySet()) {
                        if (PlaybackTracker.isFinished(Watched.percent(st, finalEp))) {
                            finalFinished = true;
                            break;
                        }
                    }
                }
            }

            if (malTotal > 0 && finalFinished) {
                rep.planStatus = "completed";
                rep.planEps = malTotal;
                rep.planText = "\u2192 completed, " + malTotal + "/" + malTotal
                        + " (watched in MX)";
            } else if (reachedAny) {
                rep.planStatus = "watching";
                rep.planEps = series;
                rep.planText = "\u2192 watching, " + series
                        + (malTotal > 0 ? "/" + malTotal : "")
                        + (repContrib == 0 ? " (reached ep " + rep.ep + ")" : " (watched in MX)");
            } else if (onlyNew) {
                rep.planStatus = "plan_to_watch";
                rep.planEps = 0;
                rep.planText = "\u2192 planned (0 eps)";
            }
            // else: only unknown/none rows -> planStatus stays "" (skip)

            // MAL is the source of truth: a computed plan must never read lower
            // than what the user's own list already records. Clamp it (and say
            // so) so the bridge only ever advances, never walks them backwards.
            String malNow = Store.malCur(groupMatchId);
            if (malNow != null) {
                int bar = malNow.indexOf('|');
                if (bar > 0) {
                    String mst = malNow.substring(0, bar);
                    int mw = -1;
                    try {
                        mw = Integer.parseInt(malNow.substring(bar + 1));
                    } catch (Exception ignored) {
                    }
                    boolean below;
                    if ("completed".equals(mst) && !"completed".equals(rep.planStatus)) {
                        below = true;
                    } else if ("completed".equals(rep.planStatus) || mw < 0) {
                        below = false;
                    } else {
                        below = rep.planEps >= 0 && rep.planEps < mw;
                    }
                    if (below) {
                        rep.planStatus = mst;
                        rep.planEps = mw;
                        rep.planText = "\u2192 kept, " + mw
                                + (malTotal > 0 ? "/" + malTotal : "")
                                + " (MAL is ahead)";
                    }
                }
            }
        }
    }

    /**
     * Per-episode watched contribution:
     * finished -> the episode itself; watching -> the episode BEFORE it
     * (reached but not finished); present/new/unknown/none -> nothing.
     *
     * <p>"present" (a file on disk that MX never showed played) is NOT watch
     * evidence: downloading an episode is not watching it. A batch download the
     * user has not started must never be credited as reached (2026-10-14 — an
     * unwatched ONA batch was written as "watching 11" under the old ep-1 rule).
     */
    /**
     * The episode index to trust for progress. Returns 0 (no episode) when the
     * source file cannot plausibly be a numbered episode:
     * <ul>
     *   <li>it has no parsed number, or</li>
     *   <li>the matched entry is a single-episode title (a movie/special has no
     *       episode numbering), or</li>
     *   <li>the file is feature-length (&ge; 40 min) — an episode number is then
     *       almost certainly a digit inside the title, not an episode index.</li>
     * </ul>
     * Duration is the tell: a real TV episode is ~24 min, the "Youjo Shenki 2"
     * short-form ONA is ~2 min, and a movie is 40 min+. A short file is still a
     * normal episode; only feature-length files are treated as unnumbered.
     */
    private static int effectiveEpisode(Store.Row r, int malTotal) {
        if (r.ep <= 0) return 0;
        if (malTotal == 1) return 0;
        if (isFeatureLength(r.duration)) return 0;
        return r.ep;
    }

    /** True for a feature-length file (&ge; 40 min): a movie, not an episode. */
    static boolean isFeatureLength(String duration) {
        return seconds(duration) >= 40 * 60;
    }

    /** "h:mm:ss" or "m:ss" to seconds, or -1 when unknown/unparseable. */
    static int seconds(String duration) {
        if (duration == null) return -1;
        String[] p = duration.trim().split(":");
        if (p.length < 2 || p.length > 3) return -1;
        try {
            int s = 0;
            for (String x : p) s = s * 60 + Integer.parseInt(x.trim());
            return s;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Package-private so the scoring rule is unit-testable on the plain JVM. */
    static int episodeValue(String state, int ep) {
        if ("finished".equals(state)) return ep;
        if ("watching".equals(state)) return ep - 1;
        return 0;
    }

    /**
     * Fuse the two independent observers of one episode into a single state,
     * strongest evidence first. This is the relation between the MX list reader
     * (badge / last-played date, persistent) and the playback sampler (ledger
     * percent, live) — see the class doc.
     *
     * <pre>
     *   grey "finished" tag (state)          -> finished
     *   ledger percent &ge; 95               -> finished
     *   last-played date OR ledger pct &gt; 0  -> watching   (reached)
     *   file on disk only (present)          -> present
     *   NEW badge, no play evidence          -> new
     * </pre>
     *
     * @param state    list-reader state: new | watching | present | finished | unknown | none
     * @param playedAt MX "last played" date text ("" when MX showed none)
     * @param ledgerPct max percent observed by the sampler, or -1 when never sampled
     * @return the resolved state, suitable for {@link #episodeValue(String, int)}
     */
    static String fuseState(String state, String playedAt, int ledgerPct) {
        if ("finished".equals(state)) return "finished";
        if (PlaybackTracker.isFinished(ledgerPct)) return "finished";
        boolean played = ledgerPct > 0 || (playedAt != null && !playedAt.isEmpty());
        if (played) return "watching";
        if ("watching".equals(state)) return "watching";
        if ("present".equals(state)) return "present";
        if ("new".equals(state)) return "new";
        return state;
    }

    /**
     * Priority for picking representative row: finished > watching > present > new > unknown/none
     */
    private static int statePriority(String state) {
        if ("finished".equals(state)) return 4;
        if ("watching".equals(state)) return 3;
        if ("present".equals(state)) return 2;
        if ("new".equals(state)) return 1;
        return 0;
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