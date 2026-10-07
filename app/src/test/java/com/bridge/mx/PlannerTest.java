package com.bridge.mx;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * (state, episode, MAL episode count) -> planned my_list_status write.
 * Uses the same mapping table documented in the README.
 */
public class PlannerTest {

    private static Store.Row row(String key, String title, String state, int numEp) {
        Store.Row r = new Store.Row();
        r.key = key;
        r.title = title;
        r.state = state;
        r.numEp = numEp;
        // The real flow is parse -> match (numEp comes with the match) -> plan;
        // pre-parsing means the first rebuild won't treat numEp as stale.
        r.parsedTitle = EpisodeParser.parse(title).title;
        Store.upsert(r);
        return r;
    }

    @Test
    public void newGoesPlannedWithZeroEps() {
        Store.Row r = row("t-new", "Fresh Show EP 03", "new", 12);
        Planner.rebuild();
        assertEquals("Fresh Show", r.parsedTitle);
        assertEquals(3, r.ep);
        assertEquals("planned", r.planStatus);
        assertEquals(0, r.planEps);
    }

    @Test
    public void watchingIsEpisodeMinusOne() {
        Store.Row r = row("t-watch", "Show Two EP 05", "watching", 12);
        Planner.rebuild();
        assertEquals("watching", r.planStatus);
        assertEquals(4, r.planEps);
    }

    @Test
    public void finishedLastEpisodeCompletes() {
        Store.Row r = row("t-fin-last", "Show Three EP 25", "finished", 25);
        Planner.rebuild();
        assertEquals("completed", r.planStatus);
        assertEquals(25, r.planEps);
    }

    @Test
    public void finishedMidSeriesStaysWatching() {
        Store.Row r = row("t-fin-mid", "Show Four EP 07", "finished", 25);
        Planner.rebuild();
        assertEquals("watching", r.planStatus);
        assertEquals(7, r.planEps);
    }

    @Test
    public void finishedWithoutEpisodeNumberWatches() {
        Store.Row r = row("t-fin-noep", "Plain Show", "finished", 25);
        Planner.rebuild();
        assertEquals("watching", r.planStatus);
    }

    @Test
    public void finishedSingleEpisodeAnimeCompletes() {
        Store.Row r = row("t-fin-movie", "Plain Movie", "finished", 1);
        Planner.rebuild();
        assertEquals("completed", r.planStatus);
    }

    @Test
    public void unknownWritesNothing() {
        Store.Row r = row("t-unk", "Mystery Show EP 02", "unknown", 12);
        Planner.rebuild();
        assertEquals("", r.planStatus);
        assertEquals(-1, r.planEps);
    }

    @Test
    public void noneWritesNothing() {
        Store.Row r = row("t-none", "Checked Show EP 04", "none", 12);
        Planner.rebuild();
        assertEquals("", r.planStatus);
    }

    @Test
    public void staleMatchResetWhenParsedTitleChanges() {
        Store.Row r = row("t-stale", "Renamed Show EP 01", "watching", 12);
        r.matchId = 42;      // match made against a previous (old) title
        r.numEp = 50;
        r.parsedTitle = "Old Name";
        Planner.rebuild();
        assertEquals("Renamed Show", r.parsedTitle);
        assertEquals(-1, r.matchId); // stale match dropped
        assertEquals(-1, r.numEp);   // stale episode count dropped with it

        // once parsed, a later rebuild keeps the match
        r.matchId = 42;
        r.numEp = 50;
        Planner.rebuild();
        assertEquals(42, r.matchId);
        assertEquals(50, r.numEp);
    }
}
