package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Filename -> title/episode parsing, covering the patterns seen on device. */
public class EpisodeParserTest {

    @Test
    public void arabicEpisode() {
        EpisodeParser.Parsed p = EpisodeParser.parse("\u0627\u0644\u0645\u062d\u0642\u0642 \u0643\u0648\u0646\u0627\u0646 \u0627\u0644\u062d\u0644\u0642\u0629 6");
        assertEquals(6, p.ep);
        assertTrue("title=" + p.title, p.title.contains("\u0627\u0644\u0645\u062d\u0642\u0642"));
    }

    @Test
    public void epPrefix() {
        EpisodeParser.Parsed p = EpisodeParser.parse("Attack on Titan - EP 06");
        assertEquals(6, p.ep);
        assertEquals("Attack on Titan", p.title);
    }

    @Test
    public void episodeKeyword() {
        EpisodeParser.Parsed p = EpisodeParser.parse("Naruto Episode 12");
        assertEquals(12, p.ep);
        assertEquals("Naruto", p.title);
    }

    @Test
    public void seasonEpisode() {
        EpisodeParser.Parsed p = EpisodeParser.parse("Show Name S02E07");
        assertEquals(7, p.ep);
        assertEquals("Show Name", p.title);
    }

    @Test
    public void trailingNumberWithResolutionBracket() {
        EpisodeParser.Parsed p = EpisodeParser.parse("My Anime - 12 [1080p]");
        assertEquals(12, p.ep);
        assertEquals("My Anime", p.title);
    }

    @Test
    public void leadingNumber() {
        EpisodeParser.Parsed p = EpisodeParser.parse("06 - Cool Show");
        assertEquals(6, p.ep);
        assertEquals("Cool Show", p.title);
    }

    @Test
    public void underscoresAndEp() {
        EpisodeParser.Parsed p = EpisodeParser.parse("Fullmetal_Alchemist_EP_12");
        assertEquals(12, p.ep);
        assertEquals("Fullmetal Alchemist", p.title);
    }

    @Test
    public void resolutionNeverBecomesEpisode() {
        EpisodeParser.Parsed p = EpisodeParser.parse("Movie 720p.mkv");
        assertEquals(-1, p.ep);
        assertEquals("Movie", p.title);
    }

    @Test
    public void bareResolutionNumberRejected() {
        assertEquals(-1, EpisodeParser.parse("Show - 720").ep);
    }

    @Test
    public void zeroRejected() {
        assertEquals(-1, EpisodeParser.parse("Show - 0").ep);
    }

    @Test
    public void tooLargeRejected() {
        assertEquals(-1, EpisodeParser.parse("Show - 999").ep);
    }

    @Test
    public void plainTitleNoEpisode() {
        EpisodeParser.Parsed p = EpisodeParser.parse("Plain Title.mkv");
        assertEquals(-1, p.ep);
        assertEquals("Plain Title", p.title);
    }

    @Test
    public void fansubBracketsStripped() {
        EpisodeParser.Parsed p = EpisodeParser.parse("[SubsGroup] Some Show");
        assertEquals(-1, p.ep);
        assertEquals("Some Show", p.title);
    }

    // ------------------------------------------------- release-style names

    @Test
    public void releaseStyleWithBracketedNoise() {
        EpisodeParser.Parsed p = EpisodeParser.parse(
                "Shikkoku no Shaga The Animation - 01 [1080p-HEVC][hstream.moe][v2].mkv");
        assertEquals(1, p.ep);
        assertEquals("Shikkoku no Shaga The Animation", p.title);
    }

    @Test
    public void releaseStyleFinalEndIsTheEpisodeNumber() {
        EpisodeParser.Parsed p = EpisodeParser.parse(
                "[Rhythm] Youjo Shenki 2 - 12 END [1080p AAC][6D7DF97C].mkv");
        assertEquals(12, p.ep);
        assertEquals("Youjo Shenki 2", p.title);
    }

    @Test
    public void releaseStyleEndWithBdTags() {
        EpisodeParser.Parsed p = EpisodeParser.parse(
                "[Rhythm] Youjo Shenki - 12 END [BD 1080p Hi10p FLAC][1DD524AE].mkv");
        assertEquals(12, p.ep);
        assertEquals("Youjo Shenki", p.title);
    }

    // ------------------------------------------------- 2026-11-13 regressions

    @Test
    public void biliWebDlStyleSharesTheSeriesTitle() {
        // "E07" (one letter) + BILI/VARYG junk used to fall through to the bare
        // number arm: the episode was found but the junk stayed in the title, so
        // this file and the Arabic-subbed file of the same series parsed to two
        // DIFFERENT series keys and the watched episode never reached MAL
        // (MAL 53913 stayed at 6 while ep7 was watched to 100%).
        EpisodeParser.Parsed p = EpisodeParser.parse(
                "Tensei shitara Ken deshita II E07.1080p.BILI.WEB-DL.AAC2.0.H.264-VARYG.mkv");
        assertEquals(7, p.ep);
        assertEquals("Tensei shitara Ken deshita II", p.title);
    }

    @Test
    public void bothSpellingsOfOneSeriesAgree() {
        // The Arabic release and the BILI release must produce the SAME series
        // key, otherwise the two rows never aggregate into one progress write.
        EpisodeParser.Parsed ar = EpisodeParser.parse(
                "\u0627\u0644\u062d\u0644\u0642\u0629 6 Tensei shitara Ken deshita II.mp4");
        EpisodeParser.Parsed raw = EpisodeParser.parse(
                "Tensei shitara Ken deshita II E06.1080p.BILI.WEB-DL.AAC2.0.H.264-VARYG.mkv");
        assertEquals("Tensei shitara Ken deshita II", ar.title);
        assertEquals(ar.title, raw.title);
        assertEquals(6, ar.ep);
        assertEquals(6, raw.ep);
    }

    @Test
    public void sxxeyyStillWinsOverTheSingleLetterArm() {
        // Ordering guard: "S01E05" must keep the season+episode form.
        EpisodeParser.Parsed p = EpisodeParser.parse("Some Show S01E05 1080p WEB-DL.mkv");
        assertEquals(5, p.ep);
        assertEquals("Some Show", p.title);
    }

    @Test
    public void audioChannelsDoNotLeakIntoTheTitle() {
        EpisodeParser.Parsed p = EpisodeParser.parse(
                "Kanata Kara - 03 [Bilibili][AAC2.0][1080p].mkv");
        assertEquals(3, p.ep);
        assertEquals("Kanata Kara", p.title);
    }
}
