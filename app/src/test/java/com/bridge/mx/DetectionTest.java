package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * Detection methods tested against the seven episodes actually watched on the
 * device (2026-10-13). Every raw string below is a real MX Player row title
 * pulled from {@code shared_prefs/mxrows.xml}; the expected values are what the
 * user reported watching.
 *
 * <p>Covers the two Android-free detectors:
 * <ul>
 *   <li>{@link EpisodeParser#parse} — filename to (series, episode);</li>
 *   <li>{@link MalClient#cleanQuery}/{@link MalClient#queryLadder} — the MAL
 *       search query (the full file title carries Arabic prefixes and release
 *       tags that would otherwise defeat the search);</li>
 *   <li>plus the planner's per-episode scoring rule.</li>
 * </ul>
 */
public class DetectionTest {

    private static EpisodeParser.Parsed parsed(String raw) {
        return EpisodeParser.parse(raw);
    }

    private static void assertDetected(String raw, String series, int ep) {
        EpisodeParser.Parsed p = parsed(raw);
        assertEquals("series detected from " + raw, series, p.title);
        assertEquals("episode detected from " + raw, ep, p.ep);
    }

    // ---------------------------------------------------- series + episode

    @Test
    public void aoNoHakoSeason2() {
        assertDetected("الحلقة 2 Ao no Hako Season 2.mp4", "Ao no Hako Season 2", 2);
    }

    @Test
    public void kanataKara() {
        assertDetected("الحلقة 2 Kanata kara.mp4", "Kanata kara", 2);
    }

    @Test
    public void hotelInhumansSecondSeason() {
        assertDetected("الحلقة 2 Hotel Inhumans 2nd Season.mp4",
                "Hotel Inhumans 2nd Season", 2);
    }

    @Test
    public void tankChair() {
        assertDetected("الحلقة 2 Tank Chair.mp4", "Tank Chair", 2);
    }

    @Test
    public void ojisanWaKawaiiMonoGaOsuki() {
        assertDetected("الحلقة 2 Ojisan wa Kawaii Mono ga Osuki.mp4",
                "Ojisan wa Kawaii Mono ga Osuki", 2);
    }

    @Test
    public void yozakuraSecondSeasonPartTwo() {
        assertDetected("الحلقة 1 Yozakura-san Chi no Daisakusen 2nd Season Part 2.mp4",
                "Yozakura-san Chi no Daisakusen 2nd Season Part 2", 1);
    }

    @Test
    public void longLightNovelTitle() {
        assertDetected(
                "الحلقة 2 Yowaki Max Reijou nanoni, Ratsuwan Konyakusha-sama no Kake"
                        + " ni Notte Shimatta.mp4",
                "Yowaki Max Reijou nanoni, Ratsuwan Konyakusha-sama no Kake ni Notte Shimatta",
                2);
    }

    /** A season marker ("2nd Season", "Part 2") must never be read as an episode. */
    @Test
    public void seasonMarkersAreNotEpisodes() {
        assertEquals(1, parsed(
                "الحلقة 1 Yozakura-san Chi no Daisakusen 2nd Season Part 2.mp4").ep);
        assertEquals(2, parsed("الحلقة 2 Hotel Inhumans 2nd Season.mp4").ep);
    }

    // ------------------------------------------------------------- queries

    @Test
    public void cleanQueriesAreMalSafe() {
        String[] titles = {
            "Ao no Hako Season 2",
            "Kanata kara",
            "Hotel Inhumans 2nd Season",
            "Tank Chair",
            "Ojisan wa Kawaii Mono ga Osuki",
            "Yozakura-san Chi no Daisakusen 2nd Season Part 2",
            "Yowaki Max Reijou nanoni, Ratsuwan Konyakusha-sama no Kake ni Notte Shimatta",
        };
        for (String t : titles) {
            String q = MalClient.cleanQuery(t);
            assertFalse("empty query for " + t, q.isEmpty());
            assertTrue("too long: " + q, q.length() <= 64);
            assertFalse("arabic left: " + q, q.matches(".*[\\u0600-\\u06FF].*"));
        }
    }

    @Test
    public void shortTitlesSurviveTheCleanerIntact() {
        assertEquals("Ao no Hako Season 2", MalClient.cleanQuery("Ao no Hako Season 2"));
        assertEquals("Tank Chair", MalClient.cleanQuery("Tank Chair"));
        assertEquals("Kanata kara", MalClient.cleanQuery("Kanata kara"));
        assertEquals("Hotel Inhumans 2nd Season",
                MalClient.cleanQuery("Hotel Inhumans 2nd Season"));
        assertEquals("Yozakura-san Chi no Daisakusen 2nd Season Part 2",
                MalClient.cleanQuery("Yozakura-san Chi no Daisakusen 2nd Season Part 2"));
    }

    /** The 78-char light-novel title must be cut on a word boundary for MAL. */
    @Test
    public void longTitleIsCutOnAWordBoundary() {
        String raw = "Yowaki Max Reijou nanoni, Ratsuwan Konyakusha-sama no Kake"
                + " ni Notte Shimatta";
        String q = MalClient.cleanQuery(raw);
        assertTrue(q.length() <= 64);
        assertTrue(q.startsWith("Yowaki Max Reijou nanoni, Ratsuwan"));
        assertFalse("must not end mid-word: " + q, q.endsWith("Konyakusha-sam"));

        List<String> ladder = MalClient.queryLadder(raw);
        assertFalse(ladder.isEmpty());
        assertEquals(q, ladder.get(0));
        assertTrue("longest first", ladder.get(0).length() >= ladder.get(ladder.size() - 1).length());
    }

    // ------------------------------------------------- planner scoring rule

    @Test
    public void finishedEpisodeCreditsItsOwnNumber() {
        assertEquals(2, Planner.episodeValue("finished", 2));
        assertEquals(1, Planner.episodeValue("finished", 1));
    }

    @Test
    public void newEpisodeCreditsNothing() {
        assertEquals(0, Planner.episodeValue("new", 2));
        assertEquals(0, Planner.episodeValue("unknown", 2));
    }

    /**
     * Documents the conservative rule that produced the device results: a file
     * the user has ("present") or has started ("watching") credits N-1, so an
     * episode the user watched counts as the one before it whenever the live
     * playback ledger has no entry for it. This is exactly the off-by-one the
     * seven real watches exposed (they all had an empty ledger).
     */
    @Test
    public void uncapturedWatchIsCreditedAsThePreviousEpisode() {
        assertEquals(1, Planner.episodeValue("watching", 2));
        assertEquals(1, Planner.episodeValue("present", 2));
        assertEquals(0, Planner.episodeValue("present", 1));
    }
}
