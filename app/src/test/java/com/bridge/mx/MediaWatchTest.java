package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** MediaSession progress math: live position interpolation and completion. */
public class MediaWatchTest {

    @Test
    public void pausedPositionIsExact() {
        assertEquals(30_000L,
                MediaWatch.livePosition(30_000L, 1_000L, 9_999L, 1f, false));
    }

    @Test
    public void playingPositionAdvancesBySpeed() {
        // 10s reported, 5s of wall clock at 1x -> 15s
        assertEquals(15_000L,
                MediaWatch.livePosition(10_000L, 1_000L, 6_000L, 1f, true));
        // 2x speed advances twice as fast
        assertEquals(20_000L,
                MediaWatch.livePosition(10_000L, 1_000L, 6_000L, 2f, true));
    }

    @Test
    public void unknownPositionStaysUnknown() {
        assertEquals(MediaWatch.NONE,
                MediaWatch.livePosition(-1L, 1_000L, 5_000L, 1f, true));
    }

    @Test
    public void clockGoingBackIsIgnored() {
        // nowValue before the base timestamp must not move backwards
        assertEquals(30_000L,
                MediaWatch.livePosition(30_000L, 9_000L, 5_000L, 1f, true));
    }

    @Test
    public void percentAndFinishUseTheSharedThreshold() {
        assertEquals(50, MediaWatch.percent(60_000L, 120_000L));
        assertFalse(MediaWatch.isFinished(60_000L, 120_000L));
        // threshold is PlaybackTracker.FINISH_PCT (95)
        assertFalse(MediaWatch.isFinished(113_000L, 120_000L)); // 94.2% -> not yet
        assertTrue(MediaWatch.isFinished(114_000L, 120_000L));  // 95%
        assertTrue(MediaWatch.percent(0, 0) < 0);
    }
}
