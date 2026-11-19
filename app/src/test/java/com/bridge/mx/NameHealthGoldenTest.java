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

    // ---- MAL alternative titles: en / ja / synonyms travel with the candidate ----

    private static JSONObject nodeWithAlts(int id, String title, int numEp,
                                           String en, String ja, String... syn)
            throws Exception {
        JSONObject wrapper = node(id, title, numEp);
        JSONObject n = wrapper.getJSONObject("node");
        JSONObject alt = new JSONObject();
        if (en != null && !en.isEmpty()) alt.put("en", en);
        if (ja != null && !ja.isEmpty()) alt.put("ja", ja);
        if (syn.length > 0) {
            JSONArray a = new JSONArray();
            for (String s : syn) a.put(s);
            alt.put("synonyms", a);
        }
        n.put("alternative_titles", alt);
        return wrapper;
    }

    /** MAL 64340: canonical "Tempal: Item no Chikara", English "Overgeared". */
    @Test
    public void englishAliasWithUnrelatedCanonicalStillMatches() throws Exception {
        JSONArray a = new JSONArray().put(nodeWithAlts(64340, "Tempal: Item no Chikara", 12,
                "Overgeared", "テムパル ～アイテムの力～", "템빨"));
        Matcher.Result r = Matcher.best(a, "Overgeared", 8);
        assertNotNullResult(r);
        assertEquals(64340, r.id);
        assertTrue("alts=" + r.alts, r.alts.contains("Overgeared"));
    }

    /** MAL 62524: the '#' and the English alias are both needed. */
    @Test
    public void englishAliasMatchesHashPrefixedSeries() throws Exception {
        JSONArray a = new JSONArray().put(nodeWithAlts(62524, "#Zombie Sagashitemasu", 12,
                "#I'm Looking For a Zombie", "#ゾンビさがしてます"));
        Matcher.Result r = Matcher.best(a, "I'm Looking For a Zombie", 6);
        assertNotNullResult(r);
        assertEquals(62524, r.id);
    }

    /**
     * MAL 59978: the season marker lives in the canonical name AND in both
     * aliases. All three must survive ranking so the season guard and the LLM
     * see the same evidence.
     */
    @Test
    public void rankKeepsEveryMalAliasOnTheResult() throws Exception {
        JSONArray a = new JSONArray().put(nodeWithAlts(59978,
                "Sousou no Frieren 2nd Season", 28,
                "Frieren: Beyond Journey's End Season 2", "葬送のフリーレン 第2期",
                "Frieren at the Funeral Season 2"));
        Matcher.Scored s = Matcher.rank(a, "Frieren: Beyond Journey's End", 3, 2,
                "Frieren Beyond Journey's End S2").get(0);
        assertTrue("alts=" + s.r.alts,
                s.r.alts.contains("Frieren: Beyond Journey's End Season 2"));
        assertTrue("alts=" + s.r.alts, s.r.alts.contains("葬送のフリーレン 第2期"));
        assertTrue("alts=" + s.r.alts, s.r.alts.contains("Frieren at the Funeral Season 2"));
        assertEquals(2, Matcher.seasonOfAny(s.r));
    }

    /**
     * The canonical name may carry NO season marker at all ("Gintama"); MAL's
     * English alias ("Gintama Season 2") is then the only season evidence. The
     * guard must use it, or an S2 file is accepted onto the base series.
     */
    @Test
    public void aliasSuppliesSeasonToTheGuard() {
        Matcher.Result r = new Matcher.Result();
        r.title = "Gintama";
        r.alts = "Gintama Season 2 | 銀魂'";
        assertEquals(2, Matcher.seasonOfNames(r.title, r.alts));
        assertFalse(Matcher.seasonMismatch("Gintama S2", r, 2));
        assertTrue(Matcher.seasonMismatch("Gintama S3", r, 3));

        // MAL 53913: canonical "Tensei shitara Ken deshita II" already carries
        // season 2, and the English alias agrees - both channels must work.
        r.title = "Tensei shitara Ken deshita II";
        r.alts = "Reincarnated as a Sword Season 2 | 転生したら剣でした II";
        assertEquals(2, Matcher.seasonOfAny(r));
        assertFalse(Matcher.seasonMismatch("Reincarnated as a Sword S2", r, 2));
    }

    @Test
    public void aliasSeasonPenalisesBaseSeriesForS2File() throws Exception {
        JSONArray a = new JSONArray()
                .put(nodeWithAlts(4001, "Example Show", 12, "Example Show", "", ""))
                .put(nodeWithAlts(4002, "Example Show", 12,
                        "Example Show 2nd Season", "例のショー 第2期"));
        Matcher.Result r = Matcher.bestWithBrain(a, "Example Show S2", 5, null, 2,
                "Example Show S2");
        assertNotNullResult(r);
        assertEquals(4002, r.id);
        // With no season evidence in the filename, the file belongs to the
        // base series - the alias-only season on 4002 must not steal it.
        Matcher.Result base = Matcher.best(a, "Example Show", 5);
        assertNotNullResult(base);
        assertEquals(4001, base.id);
    }

    /**
     * A "Season 1" alias must NOT make the base series look like a sequel: an
     * unmarked file has to keep matching it. Aliases now feed the season guard,
     * so this distinction is what keeps them safe on the real catalogue.
     */
    @Test
    public void seasonOneAliasDoesNotHideTheBaseSeries() throws Exception {
        JSONArray a = new JSONArray()
                .put(nodeWithAlts(5001, "Example Show", 12,
                        "Example Show Season 1", "例のショー 第1期"));
        Matcher.Result r = Matcher.best(a, "Example Show", 5);
        assertNotNullResult(r);
        assertEquals(5001, r.id);
        assertEquals(1, Matcher.seasonOfAny(r));
    }

    @Test
    public void groundingPromptIncludesMalAlternativeTitles() {
        MatchBrain.Candidate c = new MatchBrain.Candidate(
                64340, "Tempal: Item no Chikara", 12, "tv", "finished_airing",
                "Overgeared | テムパル ～アイテムの力～ | 템빨");
        String p = Grounding.buildPrompt("Overgeared", 8,
                java.util.Collections.singletonList(c), 1, "Overgeared S01E08");
        assertTrue("prompt=" + p, p.contains("also known as: Overgeared"));
        assertTrue("prompt=" + p, p.contains("テムパル"));
        assertTrue("prompt=" + p, p.contains("템빨"));
    }
}
