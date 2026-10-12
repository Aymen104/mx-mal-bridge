package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The row-colour verdict, including the neutral-grey guard that ended the
 * "every label-less row looks watched" defect.
 */
public class ColorClassifyTest {

    @Test
    public void plainGreyIsNoTagNeverFinished() {
        // 0x888888 is the exact colour 51 rows sampled; 0x878a91 the Youjo
        // Shenki 2 row. All must read as "no tag", never "finished".
        assertEquals("none", ColorClassify.classify(0x88, 0x88, 0x88));
        assertEquals("none", ColorClassify.classify(0x85, 0x85, 0x85));
        assertEquals("none", ColorClassify.classify(0x87, 0x8a, 0x91));
    }

    @Test
    public void tintedCentroidsStillClassify() {
        assertEquals("watching", ColorClassify.classify(103, 125, 179)); // blue
        assertEquals("finished", ColorClassify.classify(167, 149, 132)); // warm
        assertEquals("none", ColorClassify.classify(123, 105, 101));     // brown = no label
    }

    @Test
    public void farColourIsUnknown() {
        assertNull(ColorClassify.classify(10, 200, 10));
    }

    @Test
    public void neutralSpreadBoundary() {
        assertTrue(ColorClassify.isNeutral(140, 136, 132));   // spread 8
        assertTrue(ColorClassify.isNeutral(136, 136, 136));   // spread 0
        assertTrue(!ColorClassify.isNeutral(167, 149, 132));  // the finished centroid
        assertTrue(!ColorClassify.isNeutral(103, 125, 179));  // the played centroid
    }

    @Test
    public void slightlyWarmGreyIsStillRejected() {
        // A warm-ish grey must not slide into the finished centroid.
        assertEquals("none", ColorClassify.classify(150, 146, 140));
    }
}
