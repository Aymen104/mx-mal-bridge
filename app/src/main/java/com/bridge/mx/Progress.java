package com.bridge.mx;

/**
 * Reflect an observed watched percent onto the matching {@link Store} rows.
 *
 * <p>Shared by the two independent progress readers — the accessibility player
 * sample ({@link MxService#probePlayer}) and the MediaSession reader
 * ({@code MediaWatchService}) — so both update rows the same way.
 */
public final class Progress {

    private Progress() {
    }

    /**
     * Raise the watched percent of every row of this series/episode and mark it
     * "finished" once it passes {@link PlaybackTracker#FINISH_PCT}%.
     *
     * @param series parsed series title (normalised on the way in)
     * @param ep     episode number; 0 = unnumbered
     * @param pct    watched percent 0..100
     * @return true when any row changed (caller should save + notify)
     */
    public static boolean apply(String series, int ep, int pct) {
        if (series == null || series.isEmpty() || pct < 0) return false;
        boolean any = false;
        String sn = Watched.norm(series);
        for (Store.Row r : Store.all()) {
            String rn = Watched.norm(r.parsedTitle.isEmpty() ? r.title : r.parsedTitle);
            if (!rn.equals(sn)) continue;
            if (ep > 0 && r.ep > 0 && r.ep != ep) continue;
            if (pct > r.watchedPct) {
                r.watchedPct = pct;
                any = true;
            }
            if (PlaybackTracker.isFinished(pct) && !"finished".equals(r.state)) {
                r.state = "finished";
                any = true;
            }
        }
        return any;
    }
}
