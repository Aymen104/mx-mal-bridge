package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * The relation between the two independent observers of one episode:
 *
 * <ul>
 *   <li>the <b>MX list reader</b> — qualitative + persistent: a NEW badge, a
 *       "last played" date ({@link Store.Row#playedAt}), or neither;</li>
 *   <li>the <b>playback sampler</b> — quantitative + live: the ledger percent
 *       ({@link Watched}), i.e. how far the episode was played.</li>
 * </ul>
 *
 * {@link Planner#fuseState(String, String, int)} joins them, strongest evidence
 * first; {@link Planner#episodeValue(String, int)} then turns the fused state
 * into a watched-episode contribution. These tests pin that relation down, both
 * directly and through the real planner.
 */
public class ObserverRelationTest {

    @Before
    public void reset() {
        Store.clear();
        Store.setMalCur(null);
    }

    private static int value(String state, String playedAt, int ledger, int ep) {
        return Planner.episodeValue(Planner.fuseState(state, playedAt, ledger), ep);
    }

    // ------------------------------------------- the relation, purely

    @Test
    public void newBadgeWithNoPlayEvidenceCreditsNothing() {
        assertEquals(0, value("new", "", -1, 5));
    }

    @Test
    public void unknownWithNoEvidenceCreditsNothing() {
        assertEquals(0, value("unknown", "", -1, 5));
    }

    @Test
    public void playedDateAloneMakesAnEpisodeReached() {
        assertEquals(4, value("unknown", "Played 1 day ago", -1, 5));
    }

    @Test
    public void fileOnDiskAloneIsNotWatched() {
        // A downloaded file with no play evidence is not progress (2026-10-14):
        // it must never be credited as a reached episode.
        assertEquals(0, value("present", "", -1, 5));
    }

    @Test
    public void partialLedgerIsReached() {
        assertEquals(4, value("unknown", "", 50, 5));
    }

    @Test
    public void samplerCompletionWinsWhenTheListIsBlank() {
        // The list reader saw nothing (row "unknown"), but the sampler knows the
        // episode was played past the finish threshold -> the episode itself.
        assertEquals(5, value("unknown", "", 97, 5));
    }

    @Test
    public void greyTagIsFinished() {
        assertEquals(5, value("finished", "", -1, 5));
    }

    @Test
    public void ledgerCompletionBeatsAStaleNewBadge() {
        assertEquals(5, value("new", "", 100, 5));
    }

    @Test
    public void aPlayedDateDoesNotImplyFinished() {
        // The date proves it was opened, never that it was watched to the end.
        assertEquals(4, value("present", "Played 2 days ago", -1, 5));
    }

    @Test
    public void evidenceOrderIsFinishedThenReachedThenNew() {
        assertEquals("finished", Planner.fuseState("watching", "", 95));
        assertEquals("finished", Planner.fuseState("finished", "", 12));
        assertEquals("watching", Planner.fuseState("new", "Played 1 day ago", -1));
        assertEquals("watching", Planner.fuseState("present", "", 1));
        assertEquals("present", Planner.fuseState("present", "", -1));
        assertEquals("new", Planner.fuseState("new", "", -1));
        assertEquals("unknown", Planner.fuseState("unknown", "", -1));
    }

    // ------------------------------------------- the relation, through the planner

    private void insert(String key, String title, String state, String playedAt,
                        int id, int numEp) {
        Store.Row r = new Store.Row();
        r.key = key;
        r.title = title;
        r.state = state;
        r.playedAt = playedAt;
        r.matchId = id;
        r.numEp = numEp;
        r.parsedTitle = EpisodeParser.parse(title).title;
        Store.upsert(r);
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

    /**
     * The exact rescue the date provides: a row whose list text was hidden (state
     * "unknown") is still credited as reached because MX reported a play date.
     */
    @Test
    public void playedDateRescuesARowWhoseListStateWasHidden() {
        insert("d-tank", "الحلقة 2 Tank Chair.mp4", "unknown", "Played 1 day ago", 63751, 0);
        Planner.rebuild();
        assertEquals(1, planEps(63751));          // reached ep 2 -> 1 watched
        assertEquals("watching", planStatus(63751));
    }

    /** Without any evidence at all the same row is skipped, not credited. */
    @Test
    public void unknownRowWithoutEvidenceIsSkipped() {
        insert("d-tank", "الحلقة 2 Tank Chair.mp4", "unknown", "", 63751, 0);
        Planner.rebuild();
        assertEquals("", planStatus(63751));
        assertEquals(-999, planEps(63751));
    }

    /** The date is kept across a re-observation that lacks it (MX hiding text). */
    @Test
    public void playedDateSurvivesALabelLessReObservation() {
        insert("d-tank", "الحلقة 2 Tank Chair.mp4", "watching", "Played 1 day ago", 63751, 0);
        // MX re-reads the row with no "last played" text this time.
        insert("d-tank", "الحلقة 2 Tank Chair.mp4", "unknown", "", 63751, 0);
        Planner.rebuild();
        assertEquals(1, planEps(63751));
        assertTrue(Store.all().get(0).playedAt.contains("Played"));
    }
}
