package com.bridge.mx;

/**
 * Maps a sampled MX Player row colour to a tag verdict.
 *
 * <p>Pure (no Android types) so the three calibrated centroids and the
 * neutral-grey guard are fully unit-testable, exactly like the rest of the
 * bridge's decision logic.
 *
 * <p>The centroids are <em>tinted</em> (blue = "last played", warm tan =
 * "finished", warm brown = "no label"). A plain neutral grey is not one of
 * MX's tags. It used to be read as "finished" anyway, because a mid-grey
 * (0x888888) sits nearer the warm finished centroid (167,149,132) than the
 * "normal" one (123,105,101) — 33.9 vs 48.5, inside the 45 limit. That single
 * mistake marked 52 label-less rows as watched and pushed 31 of them to MAL.
 * {@link #isNeutral} rejects that whole class before any distance is taken.
 */
public final class ColorClassify {

    /** Calibrated centroids (light theme, background ~241,241,241). */
    static final int[] C_PLAYED = {103, 125, 179};   // blue  -> last played
    static final int[] C_FINISHED = {167, 149, 132}; // warm  -> watched tag
    static final int[] C_NORMAL = {123, 105, 101};   // brown -> no label
    static final int MAX_DIST = 45;

    /**
     * Largest per-channel spread still counted as neutral grey. Every real
     * centroid spreads its channels by 22 (normal), 35 (finished) or 76
     * (played), so 14 leaves clear headroom while collapsing 0x888888 and
     * friends to zero.
     */
    static final int NEUTRAL_SPREAD = 14;

    private ColorClassify() {
    }

    /**
     * @return "watching" | "finished" | "none", or null when the colour is too
     *         far from every centroid to judge at all.
     */
    public static String classify(int r, int g, int b) {
        if (isNeutral(r, g, b)) return "none"; // a plain grey is no tag, never "finished"
        int dp = dist2(r, g, b, C_PLAYED);
        int df = dist2(r, g, b, C_FINISHED);
        int dn = dist2(r, g, b, C_NORMAL);
        int limit = MAX_DIST * MAX_DIST;
        int min = Math.min(dp, Math.min(df, dn));
        if (min > limit) return null;
        if (min == dp) return "watching";
        if (min == df) return "finished";
        return "none";
    }

    /** True when the channels are close enough to grey to carry no tag ink. */
    static boolean isNeutral(int r, int g, int b) {
        int spread = Math.max(Math.abs(r - g), Math.max(Math.abs(g - b), Math.abs(r - b)));
        return spread <= NEUTRAL_SPREAD;
    }

    static int dist2(int r, int g, int b, int[] c) {
        int dr = r - c[0], dg = g - c[1], db = b - c[2];
        return dr * dr + dg * dg + db * db;
    }
}
