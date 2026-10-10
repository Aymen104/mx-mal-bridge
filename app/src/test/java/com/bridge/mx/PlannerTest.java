package com.bridge.mx;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

/**
 * (state, episode, MAL episode count) -> planned my_list_status write.
 * Watched count comes only from MX Player: a "finished" episode was watched to
 * the end (counts as ep), a "watching" episode is the one currently on screen
 * (counts as ep - 1). Files merely present on disk are NOT watched, and a
 * series only completes when MX shows it watched through the last episode.
 * Rows of one anime aggregate to a single write.
 */
public class PlannerTest {

    private static int nextMatch = 1;

    private static Store.Row row(String key, String title, String state, int numEp) {
        Store.Row r = new Store.Row();
        r.key = key;
        r.title = title;
        r.state = state;
        r.numEp = numEp;
        r.matchId = nextMatch++; // unique MAL id by default (one group per row)
        // The real flow is parse -> match (numEp comes with the match) -> plan;
        // pre-parsing means the first rebuild won't treat numEp as stale.
        r.parsedTitle = EpisodeParser.parse(title).title;
        Store.upsert(r);
        return r;
    }

    @Before
    public void reset() {
        Store.clear();
    }

    @Test
    public void newGoesPlannedWithZeroEps() {
        Store.Row r = row("t-new", "Fresh Show EP 03", "new", 12);
        Planner.rebuild();
        assertEquals("Fresh Show", r.parsedTitle);
        assertEquals(3, r.ep);
        assertEquals("plan_to_watch", r.planStatus);
        assertEquals(0, r.planEps);
    }

    @Test
    public void watchingIsHighestPlayedMinusOne() {
        Store.Row r = row("t-watch", "Show Two EP 05", "watching", 12);
        Planner.rebuild();
        assertEquals("watching", r.planStatus);
        assertEquals(4, r.planEps);
    }

    @Test
    public void finishedLastEpisodeCompletes() {
        // MX Player marks the last episode finished -> the series is completed.
        Store.Row r = row("t-fin-last", "Show Three EP 25", "finished", 25);
        Planner.rebuild();
        assertEquals("completed", r.planStatus);
        assertEquals(25, r.planEps);
    }

    @Test
    public void finishedMidSeriesCountsWatchedEpisode() {
        // A finished episode was watched to the end, so it counts as watched.
        Store.Row r = row("t-fin-mid", "Show Four EP 07", "finished", 25);
        Planner.rebuild();
        assertEquals("watching", r.planStatus);
        assertEquals(7, r.planEps);
    }

    @Test
    public void playedWithoutEpisodeNumberIsNotPushed() {
        // A played row whose episode number is unknown must never be pushed:
        // pushing "completed" with no episode number is what created the bogus
        // 0-episode MAL entry.
        Store.Row r = row("t-fin-noep", "Plain Show", "finished", 25);
        Planner.rebuild();
        assertEquals("", r.planStatus);
        assertEquals(-1, r.planEps);
    }

    @Test
    public void playedSingleEpisodeAnimeCompletes() {
        Store.Row r = row("t-fin-movie", "Plain Movie", "finished", 1);
        Planner.rebuild();
        assertEquals("completed", r.planStatus);
        assertEquals(1, r.planEps); // completed always carries a real episode count
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
    public void sameAnimeEpisodesAggregateToOneHighestPlan() {
        // E01..E03 played + E04 NEW, all matching the same MAL anime.
        Store.Row e1 = row("agg-1", "Agg Show EP 01", "watching", 12);
        Store.Row e2 = row("agg-2", "Agg Show EP 02", "watching", 12);
        Store.Row e3 = row("agg-3", "Agg Show EP 03", "watching", 12);
        Store.Row e4 = row("agg-4", "Agg Show EP 04", "new", 12);
        int id = 9001;
        for (Store.Row r : new Store.Row[]{e1, e2, e3, e4}) r.matchId = id;
        Planner.rebuild();

        int planned = 0;
        Store.Row carrier = null;
        for (Store.Row r : new Store.Row[]{e1, e2, e3, e4}) {
            if (!r.planStatus.isEmpty()) {
                planned++;
                carrier = r;
            }
        }
        assertEquals(1, planned);
        assertEquals(e3, carrier);              // highest played episode carries the plan
        assertEquals("watching", carrier.planStatus);
        assertEquals(2, carrier.planEps);       // highest played (3) - 1
        assertEquals("", e4.planStatus);        // NEW must not clobber with plan_to_watch
    }

    @Test
    public void allNewEpisodesPlanToWatchOnce() {
        Store.Row e1 = row("allnew-1", "Brand New Show EP 01", "new", 12);
        Store.Row e2 = row("allnew-2", "Brand New Show EP 02", "new", 12);
        int id = 9002;
        e1.matchId = id;
        e2.matchId = id;
        Planner.rebuild();
        int planned = 0;
        for (Store.Row r : new Store.Row[]{e1, e2}) {
            if (!r.planStatus.isEmpty()) planned++;
        }
        assertEquals(1, planned);
        assertEquals("plan_to_watch", e1.planStatus);
        assertEquals(0, e1.planEps);
    }

    @Test
    public void downloadedButUnwatchedStaysWatchingZero() {
        // Files present on disk are NOT watched. Presence puts the series in
        // "watching" but the count must stay 0 until MX reports real progress.
        Store.Row e1 = row("lib-1", "Lib Show EP 01", "present", 12);
        Store.Row e5 = row("lib-5", "Lib Show EP 05", "present", 12);
        int id = 9100;
        e1.matchId = id;
        e5.matchId = id;
        Planner.rebuild();
        int planned = 0;
        Store.Row carrier = null;
        for (Store.Row r : new Store.Row[]{e1, e5}) {
            if (!r.planStatus.isEmpty()) {
                planned++;
                carrier = r;
            }
        }
        assertEquals(1, planned);
        assertEquals("watching", carrier.planStatus);
        assertEquals(0, carrier.planEps); // nothing watched yet
    }

    @Test
    public void downloadedAllButUnwatchedNeverCompletes() {
        // Every file is present (ep 9 > total 6) but nothing was watched in MX,
        // so this must NOT be pushed as completed.
        Store.Row e9 = row("lib-cap", "Capped Show EP 09", "present", 6);
        e9.matchId = 9101;
        Planner.rebuild();
        assertEquals("watching", e9.planStatus);
        assertEquals(0, e9.planEps);
    }

    @Test
    public void downloadedPlusMxFinishedCompletes() {
        // A present row and an MX-finished last episode belong to the same
        // series: MX evidence completes it.
        Store.Row lib = row("lib-fin", "Done Show EP 12", "present", 12);
        Store.Row fin = row("mx-fin", "Done Show EP 12", "finished", 12);
        lib.matchId = 9200;
        fin.matchId = 9200;
        Planner.rebuild();
        assertEquals("completed", fin.planStatus);
        assertEquals(12, fin.planEps);
    }

    @Test
    public void downloadedPlusMxWatchingMidStaysWatching() {
        // All 12 files present, but MX shows only ep 5 currently playing ->
        // watching 4, never completed.
        Store.Row lib = row("lib-mid", "Mid Show EP 12", "present", 12);
        Store.Row play = row("mx-mid", "Mid Show EP 05", "watching", 12);
        lib.matchId = 9201;
        play.matchId = 9201;
        Planner.rebuild();
        assertEquals("watching", play.planStatus);
        assertEquals(4, play.planEps);
    }

    @Test
    public void presentAiringStaysWatchingZeroWhenUnwatched() {
        // num_episodes == 0 (still airing) and nothing watched -> watching 0.
        Store.Row e3 = row("lib-air", "Airing Show EP 03", "present", 0);
        e3.matchId = 9102;
        Planner.rebuild();
        assertEquals("watching", e3.planStatus);
        assertEquals(0, e3.planEps);
    }

    @Test
    public void neverDowngradesManualCompletionOrHigherCount() {
        // MAL already completed -> a "watching" estimate must never lower it.
        assertEquals(true, Planner.wouldDowngrade("completed|10", "watching", 9));
        // MAL already watched 12 -> a lower file-based count is refused.
        assertEquals(true, Planner.wouldDowngrade("watching|12", "watching", 9));
        // MAL watched 0 -> adding an episode is fine.
        assertEquals(false, Planner.wouldDowngrade("watching|0", "watching", 5));
        // Equal or higher is fine.
        assertEquals(false, Planner.wouldDowngrade("watching|5", "watching", 5));
        assertEquals(false, Planner.wouldDowngrade("watching|5", "watching", 8));
        // Completing is never a downgrade.
        assertEquals(false, Planner.wouldDowngrade("watching|5", "completed", 12));
        // Not on the list / unknown -> allow the write.
        assertEquals(false, Planner.wouldDowngrade(null, "watching", 3));
        assertEquals(false, Planner.wouldDowngrade("bogus", "watching", 3));
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
