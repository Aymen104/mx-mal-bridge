package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * Query cleaning / fallback ladder, calibrated on the four titles the bridge
 * actually failed to match on the device (each returned 0 MAL results).
 * {@code cleanQuery} and {@code queryLadder} are Android-free, so this runs on
 * the plain JVM.
 */
public class MalClientTest {

    @Test
    public void stripsArabicReleaseTail() {
        String q = MalClient.cleanQuery("Sora Wa Akai Kawa No Hotori - مترجم عربي");
        assertTrue(q.contains("Sora Wa Akai Kawa No Hotori"));
        assertFalse("no Arabic left: " + q, q.matches(".*[\\u0600-\\u06FF].*"));
        assertFalse("no trailing dash: " + q, q.endsWith("-"));
    }

    @Test
    public void stripsOvEsubAndUncensoredNoise() {
        assertEquals("Seitokai ni mo Ana wa Aru",
                MalClient.cleanQuery("Seitokai ni mo Ana wa Aru! . . .UNCENSORED.OV. .JPN.AAC2.0. .ESub-ToonsHub"));
    }

    @Test
    public void stripsSeasonEpisodeMarker() {
        assertEquals("Kanojo no Tomodachi",
                MalClient.cleanQuery("Kanojo no Tomodachi S01E02. . .UNCENSORED.OV. .JPN.AAC2.0. .ESub-ToonsHub"));
    }

    @Test
    public void longTitleIsCutOnAWordBoundary() {
        String q = MalClient.cleanQuery("Dark Summoner to Dekiteiru .The.Dark.Summoner.is.Trapped.in.a.Room. .UNCENSORED.HIDIVE. .JPN.AAC2.0. .ESub-ToonsHub");
        assertTrue(q.length() <= 64);
        assertFalse("must not end mid-word: " + q, q.endsWith("Roo"));
        assertTrue(q.startsWith("Dark Summoner to Dekiteiru"));
    }

    @Test
    public void ladderFallsBackToTheCoreTitle() {
        List<String> ladder = MalClient.queryLadder(
                "Dark Summoner to Dekiteiru .The.Dark.Summoner.is.Trapped.in.a.Room. .UNCENSORED.HIDIVE. .JPN.AAC2.0. .ESub-ToonsHub");
        assertTrue(ladder.contains("Dark Summoner to Dekiteiru"));
        // longest first
        assertTrue(ladder.get(0).length() >= ladder.get(ladder.size() - 1).length());
        assertFalse(ladder.isEmpty());
    }

    @Test
    public void cleanQueryHandlesNullAndBlank() {
        assertEquals("", MalClient.cleanQuery(null));
        assertEquals("", MalClient.cleanQuery("   "));
    }
}
