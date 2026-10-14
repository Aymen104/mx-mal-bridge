package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * The seven episodes actually watched on the device (2026-10-13), pushed
 * through the REAL planner with the MX states the app observed.
 *
 * <p>These tests exist to answer one question: do the detection methods
 * reproduce what the user watched? The answer they document is:
 * <ul>
 *   <li>series + episode detection — correct for all seven;</li>
 *   <li>watched-count detection — one behind, because the only signals that
 *       credit episode N are an MX "finished" tag (screenshot classification)
 *       or a &ge;95% entry in the playback ledger, and neither existed for
 *       these rows (the tracker service was off during the watches).</li>
 * </ul>
 *
 * <p>So each of the "watching"/"present" rows below is credited N-1 (or 0 for
 * episode 1), and the same rows with state "finished" are credited N — which is
 * exactly the switch the reliable tracker is meant to flip.
 *
 * <p><b>Update 2026-10-14:</b> a row whose only signal is "present" is no longer
 * credited at all. Downloading is not watching: the seven episodes really were
 * watched (the user reported them), so they surface as "watching" (or with a
 * ledger percent); a merely downloaded copy must never fabricate progress.
 */
public class RealWatchResultsTest {

    // (row key, real MX row title, observed state, MAL id, MAL total, watched by user)
    private void insertObserved(String key, String title, String state,
                                int id, int numEp) {
        Store.Row r = new Store.Row();
        r.key = key;
        r.title = title;
        r.state = state;
        r.matchId = id;
        r.numEp = numEp;
        r.parsedTitle = EpisodeParser.parse(title).title; // parse -> match, as on device
        Store.upsert(r);
    }

    @Before
    public void reset() {
        Store.clear();
        Store.setMalCur(null);
    }

    private static int planEps(int id) {
        for (Store.Row r : Store.all()) {
            if (r.matchId == id && !r.planStatus.isEmpty()) return r.planEps;
        }
        return -999;
    }

    private static String planStatus(int id) {
        for (Store.Row r : Store.all()) {
            if (r.matchId == id && !r.planStatus.isEmpty()) return r.planStatus;
        }
        return "";
    }

    private void insertTheSeven(String state) {
        insertObserved("u-ao", "الحلقة 2 Ao no Hako Season 2.mp4",
                state, 61323, 12);                 // user watched ep 2
        insertObserved("u-kanata", "الحلقة 2 Kanata kara.mp4",
                state, 63753, 0);                  // user watched ep 2
        insertObserved("u-hotel", "الحلقة 2 Hotel Inhumans 2nd Season.mp4",
                state, 62590, 13);                 // user watched ep 2
        insertObserved("u-tank", "الحلقة 2 Tank Chair.mp4",
                state, 63751, 0);                  // user watched ep 2
        insertObserved("u-ojisan", "الحلقة 2 Ojisan wa Kawaii Mono ga Osuki.mp4",
                state, 62907, 0);                  // user watched ep 2
        insertObserved("u-yoza",
                "الحلقة 1 Yozakura-san Chi no Daisakusen 2nd Season Part 2.mp4",
                state, 64503, 0);                  // user watched ep 1
        insertObserved("u-yowaki",
                "الحلقة 2 Yowaki Max Reijou nanoni, Ratsuwan Konyakusha-sama no"
                        + " Kake ni Notte Shimatta.mp4",
                state, 62753, 12);                 // user watched ep 2
    }

    /**
     * With the states actually observed, the planner credits one episode less
     * than what was watched. (This is the current, documented rule: a started or
     * merely-downloaded episode means "reached N", i.e. N-1 watched.)
     */
    @Test
    public void observedStatesCreditOneLessThanWasWatched() {
        insertTheSeven("watching");
        Planner.rebuild();
        assertEquals(1, planEps(61323));   // Ao no Hako S2   watched 2
        assertEquals(1, planEps(63753));   // Kanata kara      watched 2
        assertEquals(1, planEps(62590));   // Hotel Inhumans   watched 2
        assertEquals(1, planEps(63751));   // Tank Chair       watched 2
        assertEquals(1, planEps(62907));   // Ojisan           watched 2
        assertEquals(0, planEps(64503));   // Yozakura Part 2  watched 1
        assertEquals(1, planEps(62753));   // Yowaki           watched 2
    }

    @Test
    public void presentRowsAreNotCreditedAtAll() {
        // A file only on disk carries no watch evidence: under the rule of
        // 2026-10-14 it is planned, never watched, so it credits NOTHING.
        insertTheSeven("present");
        Planner.rebuild();
        int[] ids = {61323, 63753, 62590, 63751, 62907, 64503, 62753};
        for (int id : ids) {
            assertEquals("id " + id, 0, planEps(id));
            assertEquals("id " + id, "plan_to_watch", planStatus(id));
        }
    }

    /** The gap in one line: every one of the seven came out exactly N-1. */
    @Test
    public void everyRealWatchIsOffByExactlyOne() {
        insertTheSeven("watching");
        Planner.rebuild();
        int[] appWatched = {
            planEps(61323), planEps(63753), planEps(62590), planEps(63751),
            planEps(62907), planEps(64503), planEps(62753),
        };
        int[] userWatched = {2, 2, 2, 2, 2, 1, 2};
        for (int i = 0; i < userWatched.length; i++) {
            assertEquals("row " + i, userWatched[i] - 1, appWatched[i]);
        }
    }

    /**
     * The switch that fixes it: when the tracker DOES know the episode is
     * finished (MX grey tag, or playback ledger &ge;95%), the same rows credit
     * the episode the user watched.
     */
    @Test
    public void finishedStatesCreditExactlyWhatWasWatched() {
        insertTheSeven("finished");
        Planner.rebuild();
        assertEquals(2, planEps(61323));
        assertEquals(2, planEps(63753));
        assertEquals(2, planEps(62590));   // 13-ep entry, ep 2 finished
        assertEquals(2, planEps(63751));
        assertEquals(2, planEps(62907));
        assertEquals(1, planEps(64503));
        assertEquals(2, planEps(62753));
        assertEquals("watching", planStatus(63753));
    }
}
