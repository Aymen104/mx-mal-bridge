package com.bridge.mx;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Ink verdicts must be corroborated by MX's own text before they can create
 * watch progress (2026-11-13).
 *
 * <p>History of this exact rule failing: 2026-10-13 (grey = the real finished
 * tag), 2026-10-14 (untagged ink), 2026-11-12 (Tantei S2 ep6) and 2026-11-13
 * 06:36 (Tantei S2 ep6 pushed to MAL 6 again, with {@code playedAt=""} and
 * {@code watchedPct=-1} — no evidence at all).
 */
public class InkVerdictTest {

    private static Store.Row row(String state, String playedAt, int pct) {
        Store.Row r = new Store.Row();
        r.state = state;
        r.playedAt = playedAt;
        r.watchedPct = pct;
        return r;
    }

    @Test
    public void greyInkAloneNeverCreatesFinished() {
        // The Tantei S2 ep6 case exactly: label-less row, no played date, no
        // playback percent, grey ink. The old code took the colour at face value.
        Store.Row r = row("present", "", -1);
        assertEquals("no corroboration -> no finished",
                "present", MxService.applyInkVerdict(r, "finished"));
        assertEquals("present", MxService.applyInkVerdict(r, "watching"));
    }

    @Test
    public void mxsPlayedLabelCorroborates() {
        Store.Row r = row("present", "Played 1 day ago", -1);
        assertEquals("finished", MxService.applyInkVerdict(r, "finished"));
        assertEquals("watching", MxService.applyInkVerdict(r, "watching"));
    }

    @Test
    public void samplerEvidenceCorroborates() {
        Store.Row r = row("present", "", 22);
        assertEquals("finished", MxService.applyInkVerdict(r, "finished"));
        assertEquals("watching", MxService.applyInkVerdict(r, "watching"));
    }

    @Test
    public void alreadyFinishedStaysFinished() {
        Store.Row r = row("finished", "", -1);
        assertEquals("finished", MxService.applyInkVerdict(r, "finished"));
    }

    @Test
    public void nonPositiveVerdictsAreAlwaysAccepted() {
        // "none" can only ever withhold a push, so it never needs corroboration.
        Store.Row r = row("finished", "", -1);
        assertEquals("none", MxService.applyInkVerdict(r, "none"));
        assertEquals("new", MxService.applyInkVerdict(r, "new"));
    }

    @Test
    public void nullVerdictChangesNothing() {
        Store.Row r = row("present", "Played 2 days ago", -1);
        assertEquals("present", MxService.applyInkVerdict(r, null));
    }
}