package com.bridge.mx;

/**
 * Maps a sampled MX Player row colour to a watch-state verdict.
 *
 * <p>Pure (no Android types) so the three calibrated centroids are fully
 * unit-testable, exactly like the rest of the bridge's decision logic.
 *
 * <p>MX encodes the row's watch state in the filename's ink colour (light
 * theme). Re-measured on MX <b>3.3.1</b> (2026-10-13) with a controlled
 * "Mark as" round-trip on a single file, then confirmed against the live list:
 *
 * <pre>
 *   NEW / None   -&gt; dark indigo (53,52,76)   no tag
 *   Last played  -&gt; blue        (19,89,216) in progress
 *   Finished     -&gt; grey        (136,136,136) watched to the end
 * </pre>
 *
 * <p>The grey "finished" ink is the important one: MX shows a finished row with
 * NO text of its own (it hides both the NEW badge and the "Played" line), so
 * the colour is the only signal that credits episode N instead of N-1.
 *
 * <p>History: the earlier calibration was taken on MX 3.3.0, whose palette was
 * tinted and whose neutral grey was treated as "no tag". The 3.3.1 update
 * changed the palette to blue/grey/dark-indigo; the old rule then suppressed
 * every finished row (and the old "grey" was neutral-grey 0x888888, which is
 * now the <em>finished</em> ink). This class reflects the live values,
 * confirmed against the user's own watched ledger (grey rows = real watches).
 */
public final class ColorClassify {

    /** Calibrated centroids (MX 3.3.1, light theme, background ~248). */
    static final int[] C_PLAYED = {19, 89, 216};     // blue        -> last played / in progress
    static final int[] C_FINISHED = {136, 136, 136}; // grey        -> finished / watched to the end
    static final int[] C_NORMAL = {53, 52, 76};      // dark indigo -> no label / unwatched

    /**
     * Distance limit. The three centroids are ~132-149 apart, so 60 leaves a
     * comfortable margin around each while keeping a clear "unknown" gap: a
     * colour roughly halfway between grey and dark indigo is ~66 from both and
     * abstains rather than guessing.
     */
    static final double MAX_DIST = 60;

    private ColorClassify() {
    }

    /**
     * @return "watching" | "finished" | "none", or null when the colour is too
     *         far from every centroid to judge at all.
     */
    public static String classify(int r, int g, int b) {
        int dp = dist2(r, g, b, C_PLAYED);
        int df = dist2(r, g, b, C_FINISHED);
        int dn = dist2(r, g, b, C_NORMAL);
        double limit = MAX_DIST * MAX_DIST;
        int min = Math.min(dp, Math.min(df, dn));
        if (min > limit) return null;
        if (min == dp) return "watching";
        if (min == df) return "finished";
        return "none";
    }

    /**
     * True when the sampled colour carries a real MX tag: blue (last played) or
     * grey (finished). The dark-indigo "none" ink is untagged, and an unknown
     * colour is not a tag either.
     */
    public static boolean hasTag(int r, int g, int b) {
        String v = classify(r, g, b);
        return "watching".equals(v) || "finished".equals(v);
    }

    /** Packed-ARGB convenience ({@code 0x00RRGGBB} or {@code 0xAARRGGBB}). */
    public static boolean hasTag(int rgb) {
        return hasTag((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    static int dist2(int r, int g, int b, int[] c) {
        int dr = r - c[0], dg = g - c[1], db = b - c[2];
        return dr * dr + dg * dg + db * db;
    }
}
