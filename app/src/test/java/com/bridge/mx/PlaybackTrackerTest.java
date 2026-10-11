package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Clock parsing and the >=95% completion rule. */
public class PlaybackTrackerTest {

    @Test
    public void parsesMmSs() {
        assertEquals(11, PlaybackTracker.parseTime("00:11"));
        assertEquals(754, PlaybackTracker.parseTime("12:34"));
    }

    @Test
    public void parsesHhMmSs() {
        assertEquals(3723, PlaybackTracker.parseTime("1:02:03"));
        assertEquals(86399, PlaybackTracker.parseTime("23:59:59"));
    }

    @Test
    public void parsesBareSeconds() {
        assertEquals(45, PlaybackTracker.parseTime("45"));
        assertEquals(0, PlaybackTracker.parseTime("0"));
    }

    @Test
    public void parsesRemainingTimeAsMagnitude() {
        assertEquals(6, PlaybackTracker.parseTime("-00:06"));
        assertEquals(125, PlaybackTracker.parseTime("-2:05"));
    }

    @Test
    public void keepsFirstClockOfCombinedLabel() {
        assertEquals(5, PlaybackTracker.parseTime("00:05 / 00:11"));
    }

    @Test
    public void rejectsGarbage() {
        assertEquals(-1, PlaybackTracker.parseTime(null));
        assertEquals(-1, PlaybackTracker.parseTime(""));
        assertEquals(-1, PlaybackTracker.parseTime("  "));
        assertEquals(-1, PlaybackTracker.parseTime("abc"));
        assertEquals(-1, PlaybackTracker.parseTime("1:2:3:4"));
    }

    @Test
    public void percentBasics() {
        assertEquals(50, PlaybackTracker.percent(5000, 10000));
        assertEquals(0, PlaybackTracker.percent(0, 10000));
        assertEquals(100, PlaybackTracker.percent(10000, 10000));
        assertEquals(99, PlaybackTracker.percent(9900, 10000));
    }

    @Test
    public void percentClampsAndGuards() {
        assertEquals(100, PlaybackTracker.percent(11000, 10000));
        assertEquals(-1, PlaybackTracker.percent(-1, 10000));
        assertEquals(-1, PlaybackTracker.percent(5000, 0));
        assertEquals(-1, PlaybackTracker.percent(5000, -1));
    }

    @Test
    public void finishThresholdIsNinetyFive() {
        assertTrue(PlaybackTracker.isFinished(95));
        assertTrue(PlaybackTracker.isFinished(100));
        assertFalse(PlaybackTracker.isFinished(94));
        assertFalse(PlaybackTracker.isFinished(0));
    }

    @Test
    public void labelPairElapsed() {
        // 5 of 11 seconds
        assertEquals(45, PlaybackTracker.percentFromLabels("00:05", "00:11", false));
    }

    @Test
    public void labelPairRemaining() {
        // 6s left of 11s -> 5s watched
        assertEquals(45, PlaybackTracker.percentFromLabels("00:06", "00:11", true));
        // nothing watched yet
        assertEquals(0, PlaybackTracker.percentFromLabels("00:11", "00:11", true));
    }

    @Test
    public void labelPairGuards() {
        assertEquals(-1, PlaybackTracker.percentFromLabels("abc", "00:11", false));
        assertEquals(-1, PlaybackTracker.percentFromLabels("00:05", "0", false));
    }

    @Test
    public void parsePairReadsCombinedElapsed() {
        // MX's controls-hidden status line: "22:38 / 26:18".
        int[] p = PlaybackTracker.parsePair("22:38 / 26:18");
        assertEquals(22 * 60 + 38, p[0]);
        assertEquals(26 * 60 + 18, p[1]);
    }

    @Test
    public void parsePairRejectsSingleClockOrZeroTotal() {
        assertEquals(null, PlaybackTracker.parsePair("22:38"));
        assertEquals(null, PlaybackTracker.parsePair(null));
        assertEquals(null, PlaybackTracker.parsePair("22:38 / 0:00"));
        assertEquals(null, PlaybackTracker.parsePair("abc / def"));
    }
}
