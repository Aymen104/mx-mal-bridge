package com.bridge.mx;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests for the "may this plan be written to MAL" gate (PushGate). */
public class PushGateTest {

    private static Store.Row row(String plan, int eps, int matchId, String applied) {
        Store.Row r = new Store.Row();
        r.title = "Something";
        r.planStatus = plan;
        r.planEps = eps;
        r.matchId = matchId;
        r.applied = applied == null ? "" : applied;
        return r;
    }

    @Test
    public void aHoldIsNeverPushed() {
        // "MAL is ahead": the number is already on the user's list, so a write is
        // a no-op that re-stamps `applied` and claims authorship of a value the
        // local evidence does not support (2026-11-13).
        Store.Row r = new Store.Row();
        r.matchId = 10;
        r.planStatus = "watching";
        r.planEps = 6;
        r.applied = "watching|5";
        assertTrue("a real evidence-backed plan is sent", PushGate.needsPush(r));
        r.hold = true;
        assertFalse("the same plan, held for MAL, is not", PushGate.needsPush(r));
    }

    @Test
    public void matchedPlanThatWasNeverPushedIsSent() {
        assertTrue(PushGate.needsPush(row("watching", 5, 42, "")));
        assertTrue(PushGate.needsPush(row("completed", 12, 42, "")));
    }

    @Test
    public void planAlreadyAppliedIsNotSentAgain() {
        // The 2026-10-14 "53 ready to send" bug: header must not count in-sync rows.
        assertFalse(PushGate.needsPush(row("watching", 5, 42, "watching|5")));
        assertFalse(PushGate.needsPush(row("completed", 12, 42, "completed|12")));
    }

    @Test
    public void unmatchedRowIsNeverSent() {
        assertFalse(PushGate.needsPush(row("watching", 5, 0, "")));
        assertFalse(PushGate.needsPush(row("watching", 5, -1, "")));
    }

    @Test
    public void emptyPlanIsNeverSent() {
        assertFalse(PushGate.needsPush(row("", -1, 42, "")));
    }

    @Test
    public void bogusCompletionIsNeverSent() {
        assertFalse(PushGate.needsPush(row("completed", -1, 42, "")));
    }

    @Test
    public void downloadedButUnwatchedSeriesIsNeverRegistered() {
        // plan_to_watch = "the file is on disk, nothing was played". Pushing it
        // adds the show to the user's MAL list, which is the 2026-11-08
        // regression ("registers anything downloaded"); MalRepair then has to
        // delete that entry again. Local hint only.
        assertFalse(PushGate.needsPush(row("plan_to_watch", 0, 42, "")));
        // ...even though it never reached MAL, it must not be re-armed either.
        assertFalse(PushGate.needsPush(row("plan_to_watch", 0, 42, "plan_to_watch|0")));
    }

    @Test
    public void realProgressStillWinsOverAPlannedRow() {
        // Once playback evidence exists the plan is "watching"/"completed" and
        // must be pushed as before.
        assertTrue(PushGate.needsPush(row("watching", 1, 42, "plan_to_watch|0")));
    }
}
