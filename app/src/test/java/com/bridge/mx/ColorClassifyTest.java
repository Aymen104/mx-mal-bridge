package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The row-colour verdict, calibrated on MX 3.3.1 (2026-10-13 "Mark as"
 * round-trip on a single file, confirmed against the live list and the user's
 * watched ledger).
 *
 * <p>Grey is now <em>finished</em> — MX hides all text for a finished row, so
 * the ink colour is the only signal that credits episode N instead of N-1.
 */
public class ColorClassifyTest {

    @Test
    public void measuredCentroidsClassify() {
        assertEquals("watching", ColorClassify.classify(19, 89, 216));   // blue  = last played
        assertEquals("finished", ColorClassify.classify(136, 136, 136)); // grey  = finished
        assertEquals("none", ColorClassify.classify(53, 52, 76));        // dark  = no label
    }

    @Test
    public void greyIsFinishedNotNone() {
        // 0x888888 is the exact grey MX paints a finished title; its
        // neighbours must all read "finished", never "none".
        assertEquals("finished", ColorClassify.classify(0x88, 0x88, 0x88));
        assertEquals("finished", ColorClassify.classify(0x85, 0x85, 0x85));
        assertEquals("finished", ColorClassify.classify(0x8a, 0x88, 0x86));
    }

    @Test
    public void darkIndigoIsUntagged() {
        // The unwatched "no label" ink (None / NEW share it).
        assertEquals("none", ColorClassify.classify(53, 52, 76));
        assertEquals("none", ColorClassify.classify(60, 58, 80));
    }

    @Test
    public void farColourIsUnknown() {
        assertNull(ColorClassify.classify(10, 200, 10)); // green: no MX state
        assertNull(ColorClassify.classify(94, 94, 106)); // halfway grey/dark: abstain
    }

    @Test
    public void hasTagOnlyForPlayedAndFinished() {
        assertTrue(ColorClassify.hasTag(19, 89, 216));   // blue  = tag
        assertTrue(ColorClassify.hasTag(136, 136, 136)); // grey  = tag
        assertTrue(ColorClassify.hasTag((136 << 16) | (136 << 8) | 136)); // packed grey
        assertFalse(ColorClassify.hasTag(53, 52, 76));   // dark  = no tag
        assertFalse(ColorClassify.hasTag(10, 200, 10));  // unknown = no tag
    }
}
