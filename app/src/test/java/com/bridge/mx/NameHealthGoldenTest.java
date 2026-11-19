package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Golden cases for the v1.5.28 name-identification fixes. Every case here is a
 * concrete failure measured by the offline NameHealth probe against the real
 * 2,555-entry MAL catalogue, so a regression is caught on a plain JVM.
 */
public class NameHealthGoldenTest {

    private static JSONObject node(int id, String title, int numEp) throws Exception {
        JSONObject n = new JSONObject();
        n.put("id", id);
        n.put("title", title);
        n.put("num_episodes", numEp);
        n.put("media_type", "tv");
        n.put("status", "finished_airing");
        JSONObject o = new JSONObject();
        o.put("node", n);
        return o;
    }

    // ---- EpisodeParser: season evidence is carried, episode arms are capped ----

    @Test
    public void sxxeyyKeepsSeasonAndEpisode() {
        EpisodeParser.Parsed p = EpisodeParser.parse(
                "Tensei shitara Slime Datta Ken S02E05 [1080p].mkv");
        assertEquals(2, p.season);
        assertEquals(5, p.ep);
        assertEquals("Tensei shitara Slime Datta Ken", p.title);
    }

    @Test
    public void seasonUnderscoreEpisodeKeepsSeason() {
        EpisodeParser.Parsed p = EpisodeParser.parse("Show 2_08.mkv");
        assertEquals(2, p.season);
        assertEquals(8, p.ep);
    }

    @Test
    public void trailingSeasonMarkerIsRecorded() {
        EpisodeParser.Parsed p = EpisodeParser.parse("Gintama S2 - 48.mkv");
        assertEquals(2, p.season);
        assertEquals(48, p.ep);
        assertTrue("parsedTitle=" + p.title, p.title.contains("Gintama"));
    }

    @Test
    public void qualityWordNoLongerSplitsSeriesKey() {
        EpisodeParser.Parsed p = EpisodeParser.parse("Otome Game Sekai desu FHD - 03.mkv");
        assertFalse("parsedTitle=" + p.title, p.title.contains("FHD"));
        assertEquals(3, p.ep);
    }

    @Test
    public void implausibleEpisodeIsRejected() {
        EpisodeParser.Parsed p = EpisodeParser.parse("Attack on Titan S01E999.mkv");
        assertNotEquals(999, p.ep);
    }

    /**
     * A real stored row: "Reincarnated.as.a.Sword.S02E08.REPACK.1080p.ADN.WEB-DL.
     * JPN.AAC2.0.H.264.MSubs-ToonsHub.mkv". The old parser left the source tags
     * and dots in the series key and the row was matched to the SEASON 1 entry
     * (49891) instead of the sequel (53913). Both halves must work: a clean
     * series key, and a season-aware match against the sequel's English alias.
     */
    @Test
    public void dottedReleaseNameYieldsCleanTitleAndSeason() {
        EpisodeParser.Parsed p = EpisodeParser.parse(
                "Reincarnated.as.a.Sword.S02E08.REPACK.1080p.ADN.WEB-DL.JPN.AAC2.0.H.264.MSubs-ToonsHub.mkv");
        assertEquals("title=" + p.title, "Reincarnated as a Sword", p.title);
        assertEquals(8, p.ep);
        assertEquals(2, p.season);
    }

    @Test
    public void englishAliasResolvesToTheNumberedSequel() throws Exception {
        JSONObject base = node(49891, "Tensei shitara Ken deshita", 12);
        JSONObject sequel = node(53913, "Tensei shitara Ken deshita II", 12);
        sequel.getJSONObject("node").put("status", "currently_airing");
        JSONObject alt = new JSONObject();
        alt.put("en", "Reincarnated as a Sword");
        alt.put("synonyms", new JSONArray().put("Reincarnated as a Sword"));
        sequel.getJSONObject("node").put("alternative_titles", alt);
        JSONArray a = new JSONArray().put(base).put(sequel);

        // Without season evidence the English alias only scores 0.45 after the
        // "sequel not named by the file" penalty: below ACCEPT, so the matcher
        // abstains instead of pushing the wrong season. This is the safe old
        // behaviour that left the v1.5.27 row unresolved.
        assertNull(Matcher.best(a, "Reincarnated as a Sword", 8));
        // With the season recovered from S02E08 the sequel wins.
        Matcher.Result r = Matcher.bestWithBrain(a, "Reincarnated as a Sword", 8,
                null, 2, "Reincarnated as a Sword");
        assertNotNullResult(r);
        assertEquals(53913, r.id);
    }

    // ---- Matcher: S2 / S02 / bare trailing 2 are real season evidence ----

    @Test
    public void seasonOfRecognisesAllFilenameSpellings() {
        assertEquals(2, Matcher.seasonOf("Gintama S2"));
        assertEquals(2, Matcher.seasonOf("Gintama S02"));
        assertEquals(2, Matcher.seasonOf("Gintama 2nd Season"));
        assertEquals(2, Matcher.seasonOf("Gintama Season 2"));
        assertEquals(2, Matcher.seasonOf("Otome Game Sekai desu 2"));
        assertEquals(-1, Matcher.seasonOf("Gintama"));
    }

    @Test
    public void sequelEntryIsNotConfusedWithBase() {
        assertTrue(Matcher.seasonMismatch("Gintama S2", "Gintama", 2));
        assertFalse(Matcher.seasonMismatch("Gintama S2", "Gintama 2nd Season", 2));
        assertTrue(Matcher.seasonMismatch("Gintama", "Gintama 2nd Season", -1));
    }

    @Test
    public void glyphContinuationIsASeasonButSideEntryIsNot() throws Exception {
        assertFalse(Matcher.seasonMismatch("Gintama S2", "Gintama'", 2));
        assertTrue(Matcher.seasonMismatch("Gintama S2", "Gintama Movie", 2));
        JSONArray a = new JSONArray();
        a.put(node(38793, "Tensei shitara Slime Datta Ken OVA", 5));
        a.put(node(39551, "Tensei shitara Slime Datta Ken 2nd Season", 24));
        String want = "Tensei shitara Slime Datta Ken S02E05";
        Matcher.Result r = Matcher.bestWithBrain(a, want, 5, null, 2, want);
        assertNotNullResult(r);
        assertEquals(39551, r.id);
    }

    @Test
    public void seasonAwareMatcherPicksSequelNotBase() throws Exception {
        JSONArray a = new JSONArray();
        a.put(node(37430, "Tensei shitara Slime Datta Ken", 24));
        a.put(node(39551, "Tensei shitara Slime Datta Ken 2nd Season", 24));
        String want = "Tensei shitara Slime Datta Ken S02E05";
        // A file with no season marker still lands on the base entry.
        assertEquals(37430, Matcher.best(a, "Tensei shitara Slime Datta Ken", 5).id);
        // Fed the season recovered from the filename, the matcher picks S2.
        Matcher.Result r = Matcher.bestWithBrain(a, want, 5, null, 2, want);
        assertNotNullResult(r);
        assertEquals(39551, r.id);
    }

    @Test
    public void bareTrailingSeasonNumberPicksSequel() throws Exception {
        JSONArray a = new JSONArray();
        a.put(node(50461, "Otome Game Sekai wa Mob ni Kibishii Sekai desu", 12));
        a.put(node(54000, "Otome Game Sekai wa Mob ni Kibishii Sekai desu 2", 12));
        String want = "Otome Game Sekai wa Mob ni Kibishii Sekai desu 2";
        Matcher.Result r = Matcher.bestWithBrain(a, want, -1, null,
                Matcher.seasonOf(want), want);
        assertNotNullResult(r);
        assertEquals(54000, r.id);
    }

    private static void assertNotNullResult(Matcher.Result r) {
        if (r == null) throw new AssertionError("expected a match, got null");
    }

    // ---- Aliases: learned shorthands are exact, never season-collapsed ----

    @Test
    public void learnedAliasDoesNotCollapseSeason() {
        Aliases.learn("Zzz Probe Show S1", "Zzz Probe Show");
        assertEquals("Zzz Probe Show", Aliases.apply("Zzz Probe Show S1"));
        // A learned S1 key must not silently rewrite an S2 file to the S1 entry.
        assertEquals("Zzz Probe Show S2", Aliases.apply("Zzz Probe Show S2"));
    }

    // ---- MAL search: Arabic titles remain searchable ----

    @Test
    public void arabicTitleSurvivesQueryCleaning() {
        assertEquals("هجوم العمالقة", MalClient.cleanQuery("هجوم العمالقة"));
        assertEquals("هجوم العمالقة",
                MalClient.cleanQuery("هجوم العمالقة مترجم عربي"));
    }

    // ---- TitleTools: kanji are no longer dropped from loose keys ----

    @Test
    public void kanjiAreKeptInLooseKey() {
        String k = TitleTools.normLoose("葬送のフリーレン");
        assertFalse("loose key=" + k, k.isEmpty());
        assertTrue("loose key=" + k, k.contains("葬送"));
        assertEquals(1.0, TitleTools.score("葬送のフリーレン", "葬送のフリーレン"), 0.0001);
    }

    // ---- Grounding: the model sees the original filename and season ----

    @Test
    public void groundingPromptCarriesSeasonEvidence() {
        String p = Grounding.buildPrompt("Tensei shitara Slime Datta Ken", 5,
                java.util.Collections.<MatchBrain.Candidate>emptyList(), 2,
                "Tensei shitara Slime Datta Ken S02E05");
        assertTrue(p.contains("Season named by the file: 2"));
        assertTrue(p.contains("On-device filename title:"));
    }
}
