package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * Ledger semantics. Runs on the JVM with no Android prefs: persistence is a
 * no-op (prefs == null) but the in-memory max-keeping logic is fully exercised.
 * Each test uses a unique series name so the static map cannot cross-contaminate.
 */
public class WatchedTest {

    @Test
    public void keepsMaximum() {
        String s = "KeepMax Series";
        assertTrue(Watched.record(s, 3, 40));
        assertFalse(Watched.record(s, 3, 20)); // lower -> ignored
        assertEquals(40, Watched.percent(s, 3));
        assertTrue(Watched.record(s, 3, 70));
        assertEquals(70, Watched.percent(s, 3));
    }

    @Test
    public void finishIsNinetyFive() {
        String s = "Finish Series";
        assertTrue(Watched.record(s, 1, 94));
        assertFalse(Watched.finished(s, 1));
        assertTrue(Watched.record(s, 1, 95));
        assertTrue(Watched.finished(s, 1));
    }

    @Test
    public void unknownEpisodeIsMinusOne() {
        assertEquals(-1, Watched.percent("Never Seen", 1));
        assertFalse(Watched.finished("Never Seen", 1));
    }

    @Test
    public void episodesListedPerSeries() {
        String s = "Listed Series";
        Watched.record(s, 1, 100);
        Watched.record(s, 2, 50);
        Watched.record("Other Series", 1, 100);
        Map<Integer, Integer> eps = Watched.episodes(s);
        assertEquals(2, eps.size());
        assertEquals(Integer.valueOf(100), eps.get(1));
        assertEquals(Integer.valueOf(50), eps.get(2));
    }

    @Test
    public void nameMatchingIsNormalised() {
        Watched.record("  Case   Test  ", 1, 96);
        assertTrue(Watched.finished("case test", 1));
        assertTrue(Watched.finished("CASE   TEST", 1));
    }

    @Test
    public void unnumberedUsesZero() {
        String s = "Movie Only";
        assertTrue(Watched.record(s, -1, 99));
        assertEquals(99, Watched.percent(s, 0));
        assertTrue(Watched.finished(s, 0));
    }

    @Test
    public void rejectsInvalid() {
        assertFalse(Watched.record("", 1, 50));
        assertFalse(Watched.record("Reject Series", 1, -1));
    }
}
