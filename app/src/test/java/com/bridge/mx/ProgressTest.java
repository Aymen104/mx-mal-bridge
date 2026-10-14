package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Playback-evidence state resolution ({@link Progress}).
 *
 * <p>These pin down the fix for "a freshly downloaded episode that was watched
 * is still shown as NEW / gets no plan": playback percent outranks MX's list
 * badge, a row watched before it existed is credited from the ledger, and MX's
 * NEW badge can never walk a watched row back to "new".
 *
 * <p>Runs on the JVM; persistence is a no-op (prefs == null). Each test uses a
 * unique series name so the static ledger cannot cross-contaminate.
 */
public class ProgressTest {

    @Before
    public void reset() {
        Store.clear();
        Store.setMalCur(null);
        Store.setLibKeys(null); // empty allowlist -> observation gate is lenient
    }

    private static Store.Row insert(String series, int ep, String state) {
        Store.Row r = new Store.Row();
        r.key = "k:" + series + "|" + ep;
        r.title = series + " - " + ep + ".mp4";
        r.state = state;
        r.parsedTitle = series;
        r.ep = ep;
        Store.upsert(r);
        return Store.all().get(Store.all().size() - 1);
    }

    private static Store.Row only() {
        return Store.all().get(0);
    }

    // ------------------------------------------------------------ promote()

    @Test
    public void partialWatchPromotesNewRowToWatching() {
        insert("Progress Partial", 2, "new");
        assertTrue(Progress.apply("Progress Partial", 2, 31));
        assertEquals("watching", only().state);
        assertEquals(31, only().watchedPct);
    }

    @Test
    public void fullWatchPromotesNewRowToFinished() {
        insert("Progress Full", 2, "new");
        assertTrue(Progress.apply("Progress Full", 2, 100));
        assertEquals("finished", only().state);
        assertEquals(100, only().watchedPct);
    }

    @Test
    public void presentRowIsAlsoPromoted() {
        insert("Progress Present", 1, "present");
        Progress.apply("Progress Present", 1, 60);
        assertEquals("watching", only().state);
    }

    @Test
    public void promoteNeverLowersAStrongerState() {
        Store.Row r = new Store.Row();
        r.state = "watching";
        r.watchedPct = 0; // no playback evidence
        assertFalse(Progress.promote(r));
        assertEquals("watching", r.state);
    }

    @Test
    public void promoteLeavesFinishedFinished() {
        Store.Row r = new Store.Row();
        r.state = "finished";
        r.watchedPct = 40;
        assertFalse(Progress.promote(r));
        assertEquals("finished", r.state);
    }

    // ------------------------------------------------------------ hydrate()

    @Test
    public void hydrateCreditsAnEpisodeWatchedBeforeItsRowExisted() {
        // The sampler saw the episode long before the list reader did.
        Watched.record("Hydrate Late", 3, 100);
        insert("Hydrate Late", 3, "present");
        assertTrue(Progress.hydrate());
        assertEquals(100, only().watchedPct);
        assertEquals("finished", only().state);
    }

    @Test
    public void hydrateRaisesPartialWatchToWatching() {
        Watched.record("Hydrate Partial", 5, 42);
        insert("Hydrate Partial", 5, "new");
        assertTrue(Progress.hydrate());
        assertEquals(42, only().watchedPct);
        assertEquals("watching", only().state);
    }

    // ----------------------------------------- fresh-download-then-watch flow

    /**
     * The whole reported flow in one test: a new download becomes a "new" row,
     * the user watches part of it, MX then re-reads the row still showing its
     * NEW badge. The row must stay "watching", not fall back to "new", and the
     * planner must credit the reached episode.
     */
    @Test
    public void watchedThenStaleNewBadgeKeepsWatchingAndPlans() {
        Store.Row first = insert("Download Watch", 2, "new");
        first.matchId = 4242;
        first.numEp = 0;
        Progress.apply("Download Watch", 2, 30);
        assertEquals("watching", only().state);

        // MX re-reads the same row with its NEW badge still present.
        Store.Row re = new Store.Row();
        re.key = "k:Download Watch|2";
        re.title = "Download Watch - 2.mp4";
        re.state = "new";
        re.parsedTitle = "Download Watch";
        re.ep = 2;
        Store.upsert(re);
        assertEquals("watching", only().state);

        Planner.rebuild();
        assertEquals(1, only().planEps); // reached ep 2 -> 1 watched
        assertEquals("watching", only().planStatus);
    }

    /**
     * Once the row exists, the ledger still supplies the true percent even if
     * the row's own copy was never raised (e.g. the observation arrived first).
     */
    @Test
    public void plannerHydratesFinishedEpisodeFromLedger() {
        Watched.record("Ledger Finish", 4, 100);
        Store.Row r = insert("Ledger Finish", 4, "present");
        r.matchId = 5150;
        r.numEp = 0;
        Planner.rebuild();
        assertEquals("finished", only().state);
        assertEquals(4, only().planEps);
    }
}
