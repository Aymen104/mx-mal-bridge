package com.bridge.mx;

/**
 * Decides whether a planned row may be written to MyAnimeList.
 *
 * <p>Kept out of {@code MainActivity} (an Activity cannot be unit-tested on the
 * plain JVM) so the rule itself is covered by {@code PushGateTest}.
 *
 * <p>A row whose plan already equals {@code applied} is in sync — counting it
 * as "ready to send" made the header read "53 ready" while nothing was actually
 * queued (2026-10-14).
 *
 * <p>{@code plan_to_watch} is NEVER pushed. That plan is what a
 * downloaded-but-unwatched series gets: the file is on disk and nothing
 * indicates it was ever played. Writing it registered the show on the user's
 * list as "Plan to Watch", so every folder that got downloaded showed up on
 * MyAnimeList (log evidence 2026-11-08:
 * {@code [sync.apply] added to list: ... -> plan_to_watch|0}). That is exactly
 * the fabricated entry {@code MalRepair} has to delete again, so it is treated
 * as a local hint only.
 */
final class PushGate {

    private PushGate() {
    }

    /** True when this row's plan still has to reach MyAnimeList. */
    static boolean needsPush(Store.Row r) {
        if (r.planStatus.isEmpty()) return false;
        if (r.matchId <= 0) return false;
        if ("completed".equals(r.planStatus) && r.planEps < 0) return false;
        if ("plan_to_watch".equals(r.planStatus)) return false;
        // A "MAL is ahead" hold: the number is already on the user's list, so a
        // write would be a no-op that re-stamps `applied` and claims authorship
        // of a value the local evidence does not support (2026-11-13).
        if (r.hold) return false;
        return !(r.planStatus + "|" + r.planEps).equals(r.applied);
    }
}
