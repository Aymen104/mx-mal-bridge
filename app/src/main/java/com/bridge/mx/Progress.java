package com.bridge.mx;

/**
 * Reflect an observed watched percent onto the matching {@link Store} rows.
 *
 * <p>Shared by the two independent progress readers — the accessibility player
 * sample ({@link MxService#probePlayer}) and the MediaSession reader
 * ({@code MediaWatchService}) — so both update rows the same way.
 *
 * <p>Playback is the strongest single-episode evidence, so a row's state is
 * resolved from its observed percent rather than from MX's list text/colour:
 * anything played at all is at least "watching" (even while MX still shows its
 * NEW badge — the badge clears only when MX re-reads the item, which may never
 * happen for a file the user opened straight from the player), and
 * {@link PlaybackTracker#FINISH_PCT}% or more is "finished".
 */
public final class Progress {

    private Progress() {
    }

    /**
     * Raise the watched percent of every row of this series/episode and resolve
     * its state from that percent (see {@link #promote}).
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
            if (promote(r)) any = true;
        }
        return any;
    }

    /**
     * Resolve one row's state from its observed watched percent, which outranks
     * the list's badge/tag:
     *
     * <pre>
     *   percent &ge; FINISH_PCT   -> finished
     *   percent &gt; 0              -> watching   (opened at least once)
     *   else                      -> unchanged
     * </pre>
     *
     * <p>Only weak states (new/none/unknown/present) are raised: a row already
     * "watching"/"finished" is never lowered here, and a "finished" row is only
     * cleared by a later classify pass that re-reads the ink.
     *
     * @return true when the row's state changed
     */
    public static boolean promote(Store.Row r) {
        if (r == null) return false;
        if (PlaybackTracker.isFinished(r.watchedPct)) {
            if (!"finished".equals(r.state)) {
                r.state = "finished";
                return true;
            }
            return false;
        }
        if (r.watchedPct > 0
                && ("new".equals(r.state) || "none".equals(r.state)
                    || "unknown".equals(r.state) || "present".equals(r.state))) {
            r.state = "watching";
            return true;
        }
        return false;
    }

    /**
     * Pull each row's playback evidence out of the {@link Watched} ledger and
     * resolve its state. This is what credits an episode the sampler watched
     * while its row did not exist yet — a freshly downloaded file opened before
     * the list reader ever saw it, or one observed from a folder MX is not
     * currently showing.
     *
     * @return true when any row changed
     */
    public static boolean hydrate() {
        boolean any = false;
        for (Store.Row r : Store.all()) {
            if (r.parsedTitle.isEmpty()) continue;
            int re = r.ep < 0 ? 0 : r.ep;
            int ledger = Watched.percent(r.parsedTitle, re);
            if (ledger > r.watchedPct) {
                r.watchedPct = ledger;
                any = true;
            }
            if (promote(r)) any = true;
        }
        return any;
    }
}
