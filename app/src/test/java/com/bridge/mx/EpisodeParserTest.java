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
}
