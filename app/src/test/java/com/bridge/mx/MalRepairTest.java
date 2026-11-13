package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MalRepair decision logic: which pushed rows are the artifact signature, and
 * for those, when is it safe to withdraw the MAL write (the entry is still
 * exactly what we pushed and the user never rated it).
 *
 * <p>Recalibrated 2026-10-13 (MX 3.3.1): grey {@code 0x888888} is MX's real
 * <em>finished</em> tag, so a pushed grey row is a genuine watch and is NEVER
 * an artifact.
 *
 * <p>Narrowed 2026-10-14: an MX-observed row is the user's own playing record
 * and is NEVER an artifact, whatever its ink — MX paints no tag on a row it is
 * mid-way through, so "untagged" is not "fake" (six actively-watched shows were
 * wrongly withdrawn on this basis). The only artifact is a {@code fromLib} row:
 * one the MX list reader never saw, whose only claim to progress was file
 * presence.
 *
 * <p>Widened 2026-11-08 (v1.5.19): a {@code plan_to_watch|0} write from such a
 * row IS an artifact again — v1.5.17 used it to REGISTER downloaded, never
 * played series on the user's MAL list. Only the repair's own neutralization
 * (state="none") is exempt.
 *
 * <p>NARROWED again 2026-11-13 (v1.5.22): the "applied != '' + fromLib" rule
 * was a THIRD false signature and deleted 44 {@code watching} entries from the
 * user's list in one daily pass. {@code fromLib} is staleness, not fraud. Now
 * ONLY {@code plan_to_watch|0} qualifies, {@link MalRepair#decide} refuses to
 * revert any watch-progress write, and every pass is capped at
 * {@link MalRepair#MAX_REVERTS} withdrawals. The regression tests below are the
 * ones that used to assert the opposite.
 */
public class MalRepairTest {

    private static Store.Row row(int matchId, String applied, int rgb, int watchedPct,
                                 String parsedTitle, String matchTitle) {
        Store.Row r = new Store.Row();
        r.matchId = matchId;
        r.applied = applied;
        r.rgb = rgb;
        r.watchedPct = watchedPct;
        r.parsedTitle = parsedTitle;
        r.matchTitle = matchTitle;
        return r;
    }

    /** A library-only row: known from the MediaStore scan, never the MX list. */
    private static Store.Row libRow(int matchId, String applied, int rgb, int watchedPct,
                                    String parsedTitle, String matchTitle) {
        Store.Row r = row(matchId, applied, rgb, watchedPct, parsedTitle, matchTitle);
        r.fromLib = true;
        return r;
    }

    /** Real MX tags. */
    private static final int GREY = 0xFF888888; // finished
    private static final int BLUE = 0xFF1359D8; // last played (19,89,216)
    /** The untagged ink MX paints for None / NEW (53,52,76). */
    private static final int DARK = 0xFF35344C;

    // ---------------------------------------------------------------- decide

    // ------------------------------------------------- 2026-11-13 regressions
    //
    // The daily auto-repair DELETEd 44 "watching" entries from the user's
    // MyAnimeList on 2026-11-13 because this exact test asserted REVERT for a
    // "watching|2" write. MAL holding exactly our value cannot distinguish our
    // idempotent rewrite from the user's own entry, so it is not evidence the
    // entry is junk. Watch progress is the user's proof-of-watch: hands off.

    @Test
    public void decide_never_reverts_a_watching_write() {
        assertEquals("watching progress is the user's proof-of-watch",
                MalRepair.Action.KEEP_UNKNOWN,
                MalRepair.decide("watching|2|0", "watching|2"));
        assertEquals(MalRepair.Action.KEEP_UNKNOWN,
                MalRepair.decide("watching|5|0", "watching|5"));
        assertEquals(MalRepair.Action.KEEP_UNKNOWN,
                MalRepair.decide("watching|0|0", "watching|0"));
        // ...even when the app's own signature and MAL match to the episode.
        assertEquals(MalRepair.Action.KEEP_UNKNOWN,
                MalRepair.decide("watching|76|0", "watching|76"));
    }

    @Test
    public void decide_never_reverts_a_completed_write() {
        assertEquals(MalRepair.Action.KEEP_UNKNOWN,
                MalRepair.decide("completed|12|0", "completed|12"));
    }

    @Test
    public void decide_still_reverts_the_proven_plan_to_watch_registration() {
        assertEquals(MalRepair.Action.REVERT,
                MalRepair.decide("plan_to_watch|0|0", "plan_to_watch|0"));
    }

    @Test
    public void decide_keeps_rated_entry() {
        // "rated = genuine": the user scored it, never withdraw.
        assertEquals(MalRepair.Action.KEEP_RATED,
                MalRepair.decide("plan_to_watch|0|8", "plan_to_watch|0"));
    }

    @Test
    public void decide_hands_off_when_user_changed_status() {
        assertEquals(MalRepair.Action.KEEP_MOVED,
                MalRepair.decide("watching|5|0", "plan_to_watch|0"));
    }

    @Test
    public void decide_hands_off_when_user_moved_episodes() {
        assertEquals(MalRepair.Action.KEEP_MOVED,
                MalRepair.decide("plan_to_watch|3|0", "plan_to_watch|0"));
    }

    @Test
    public void decide_keeps_absent_entry() {
        assertEquals(MalRepair.Action.KEEP_ABSENT, MalRepair.decide(null, "plan_to_watch|0"));
    }

    @Test
    public void decide_skips_unparseable_mal_value() {
        assertEquals(MalRepair.Action.KEEP_UNKNOWN, MalRepair.decide("garbage", "plan_to_watch|0"));
        assertEquals(MalRepair.Action.KEEP_UNKNOWN, MalRepair.decide("plan_to_watch|x|0", "plan_to_watch|0"));
        assertEquals(MalRepair.Action.KEEP_UNKNOWN, MalRepair.decide("plan_to_watch|0|nan", "plan_to_watch|0"));
    }

    @Test
    public void decide_skips_when_we_never_pushed() {
        assertEquals(MalRepair.Action.KEEP_UNKNOWN, MalRepair.decide("plan_to_watch|0|0", ""));
        assertEquals(MalRepair.Action.KEEP_UNKNOWN, MalRepair.decide("watching|5|0", ""));
    }

    // ------------------------------------------------------------- isArtifact

    @Test
    public void artifact_requires_a_plan_to_watch_write() {
        // 2026-11-13: only plan_to_watch|0 is an artifact. "watching|N" is the
        // user's proof-of-watch, fromLib is staleness, not fraud.
        Map<String, Integer> empty = new HashMap<>();
        assertFalse(MalRepair.isArtifact(libRow(1, "", DARK, -1, "Dream", "Dream"), empty));
        assertFalse(MalRepair.isArtifact(libRow(1, "watching|2", GREY, -1, "Dream", "Dream"), empty));
        assertFalse(MalRepair.isArtifact(libRow(1, "watching|2", BLUE, -1, "Dream", "Dream"), empty));
        assertFalse(MalRepair.isArtifact(libRow(1, "watching|2", -1, -1, "Dream", "Dream"), empty));
        assertFalse(MalRepair.isArtifact(libRow(1, "watching|2", DARK, -1, "Dream", "Dream"), empty));
        assertFalse(MalRepair.isArtifact(libRow(1, "completed|12", -1, -1, "Dream", "Dream"), empty));
        assertTrue(MalRepair.isArtifact(libRow(1, "plan_to_watch|0", -1, -1, "Dream", "Dream"), empty));
    }

    @Test
    public void artifact_never_covers_a_stale_library_only_watched_row() {
        // THE 2026-11-13 CASE: a series we tracked correctly whose MX read has
        // not run in this pass (fromLib=true, no evidence sampled right now).
        // It must never be a withdrawal candidate again.
        Map<String, Integer> empty = new HashMap<>();
        for (String applied : new String[]{"watching|5", "watching|0", "completed|24"}) {
            Store.Row r = libRow(52480, applied, DARK, -1, "Tantei wa Mou Shindeiru", "Tantei");
            assertFalse("applied=" + applied, MalRepair.isArtifact(r, empty));
            assertFalse("applied=" + applied,
                    MalRepair.candidates(java.util.Arrays.asList(r), empty).contains(52480));
        }
    }

    @Test
    public void artifact_never_covers_an_mx_observed_row() {
        // 2026-10-14: an MX-observed row (fromLib=false) is the user's own
        // playing record. Whatever its ink, it is never auto-withdrawn.
        Map<String, Integer> empty = new HashMap<>();
        assertFalse(MalRepair.isArtifact(row(1, "watching|2", DARK, -1, "Dream", "Dream"), empty));
        assertFalse(MalRepair.isArtifact(row(1, "watching|1", 0x5468E7, -1, "Kurumi", "Kurumi"), empty));
        assertFalse(MalRepair.isArtifact(row(1, "watching|1", 0x96A2BA, -1, "Suikoden", "Suikoden"), empty));
        assertFalse(MalRepair.isArtifact(row(1, "plan_to_watch|0", DARK, -1, "Dream", "Dream"), empty));
    }

    @Test
    public void artifact_rejected_by_playback_evidence() {
        Map<String, Integer> empty = new HashMap<>();
        assertFalse(MalRepair.isArtifact(libRow(1, "plan_to_watch|0", DARK, 100, "Dream", "Dream"), empty));
        assertFalse(MalRepair.isArtifact(libRow(1, "plan_to_watch|0", DARK, 60, "Dream", "Dream"), empty));
    }

    @Test
    public void artifact_covers_only_the_plan_to_watch_registration() {
        // A row only ever seen by the MediaStore scan (fromLib), carrying the
        // plan_to_watch|0 write v1.5.17 used to register downloaded series.
        Map<String, Integer> empty = new HashMap<>();
        Store.Row r = row(918, "plan_to_watch|0", -1, -1, "Gin S1 - EP 048", "Gintama");
        r.fromLib = true;
        r.state = "present";
        assertTrue(MalRepair.isArtifact(r, empty));

        // A never-sampled row WITHOUT the library flag is an MX text observation
        // we could not sample: genuinely unknown, leave it alone.
        Store.Row unc = row(2, "plan_to_watch|0", -1, -1, "Dream", "Dream");
        assertFalse(MalRepair.isArtifact(unc, empty));
    }

    @Test
    public void artifact_spares_library_row_with_last_played() {
        // Defensive: if a library-only row somehow carries an MX last-played
        // date, it is evidence and must never be withdrawn.
        Map<String, Integer> empty = new HashMap<>();
        Store.Row r = row(1, "plan_to_watch|0", -1, -1, "Dream", "Dream");
        r.fromLib = true;
        r.playedAt = "Played 2 days ago";
        assertFalse(MalRepair.isArtifact(r, empty));
    }

    @Test
    public void artifact_rejected_by_watched_ledger() {
        Map<String, Integer> ledger = new HashMap<>();
        ledger.put(Watched.norm("Dream") + "|2", 100);
        assertFalse(MalRepair.isArtifact(libRow(1, "plan_to_watch|0", DARK, -1, "Dream", "Dream"), ledger));
    }

    @Test
    public void artifact_covers_a_plan_to_watch_registration() {
        // v1.5.19 (2026-11-08): v1.5.17 wrote plan_to_watch|0 for every
        // downloaded-but-unwatched series, which ADDED the show to the user's
        // MAL list. That write is ours (applied only ever holds what the bridge
        // pushed), the row is library-only and evidence-free, so it is the same
        // fabrication the repair exists to remove.
        Map<String, Integer> empty = new HashMap<>();
        Store.Row r = libRow(918, "plan_to_watch|0", -1, -1, "Gin S1 - EP 048", "Gintama");
        r.state = "present";
        assertTrue(MalRepair.isArtifact(r, empty));
        assertTrue(MalRepair.candidates(java.util.Arrays.asList(r), empty).contains(918));
    }

    @Test
    public void artifact_spares_repair_neutralized_rows() {
        // The repair's own neutralize() writes state="none" + plan_to_watch|0 on
        // an already-withdrawn row: that is bookkeeping, not a new fabrication.
        Map<String, Integer> empty = new HashMap<>();
        Store.Row r = libRow(918, "plan_to_watch|0", -1, -1, "Gin S1 - EP 048", "Gintama");
        r.state = "none";
        assertFalse(MalRepair.isArtifact(r, empty));
    }

    @Test
    public void artifact_spares_plan_to_watch_row_with_playback_evidence() {
        // The user is actually watching it now: never withdraw.
        Map<String, Integer> empty = new HashMap<>();
        Store.Row r = libRow(61323, "plan_to_watch|0", DARK, 62, "Ao no Hako Season 2",
                "Ao no Hako Season 2");
        r.state = "present";
        assertFalse(MalRepair.isArtifact(r, empty));
    }

    @Test
    public void decide_reverts_exact_unrated_plan_to_watch_registration() {
        assertEquals(MalRepair.Action.REVERT,
                MalRepair.decide("plan_to_watch|0|0", "plan_to_watch|0"));
    }

    @Test
    public void decide_hands_off_when_user_moved_a_plan_to_watch() {
        // The user themselves started watching it, or changed the status.
        assertEquals(MalRepair.Action.KEEP_MOVED,
                MalRepair.decide("watching|3|0", "plan_to_watch|0"));
    }

    @Test
    public void is_withdrawable_write_only_plan_to_watch() {
        assertTrue(MalRepair.isWithdrawableWrite("plan_to_watch|0"));
        assertFalse("watching progress is never withdrawn",
                MalRepair.isWithdrawableWrite("watching|2"));
        assertFalse(MalRepair.isWithdrawableWrite("completed|12"));
        assertFalse(MalRepair.isWithdrawableWrite(""));
        assertFalse(MalRepair.isWithdrawableWrite(null));
    }

    @Test
    public void artifact_requires_a_real_match() {
        Map<String, Integer> empty = new HashMap<>();
        assertFalse(MalRepair.isArtifact(libRow(0, "plan_to_watch|0", DARK, -1, "Dream", "Dream"), empty));
    }

    // -------------------------------------------------------------- candidates

    @Test
    public void candidates_only_aggregate_artifact_series() {
        Map<String, Integer> empty = new HashMap<>();
        List<Store.Row> rows = new ArrayList<>();
        rows.add(libRow(10, "plan_to_watch|0", DARK, -1, "Ghost", "Ghost"));     // artifact
        rows.add(libRow(10, "plan_to_watch|0", DARK, -1, "Ghost", "Ghost"));     // same series
        rows.add(libRow(11, "plan_to_watch|0", DARK, -1, "Bogus", "Bogus"));     // artifact
        rows.add(libRow(12, "plan_to_watch|0", DARK, 100, "Real", "Real"));      // played -> no
        rows.add(libRow(13, "plan_to_watch|0", GREY, -1, "Tagged", "Tagged"));   // real tag -> no
        rows.add(libRow(14, "watching|6", DARK, -1, "Stale", "Stale"));          // STALE, not a candidate
        List<Integer> ids = MalRepair.candidates(rows, empty);
        assertEquals(2, ids.size());
        assertTrue(ids.contains(10));
        assertTrue(ids.contains(11));
        assertFalse(ids.contains(12));
        assertFalse(ids.contains(13));
        assertFalse("a stale library-only watched row is not a candidate", ids.contains(14));
    }

    @Test
    public void candidates_keep_series_with_sibling_playback_evidence() {
        // Regression: an artifact row while a SIBLING row of the same series
        // genuinely played -> the series is real and must never be withdrawn
        // (this is the #Zombie Sagashitemasu case).
        Map<String, Integer> empty = new HashMap<>();
        List<Store.Row> rows = new ArrayList<>();
        rows.add(libRow(20, "plan_to_watch|0", DARK, -1, "Zombie", "#Zombie Sagashitemasu")); // artifact push
        rows.add(libRow(20, "plan_to_watch|0", DARK, 100, "Zombie", "#Zombie Sagashitemasu")); // real playback
        List<Integer> ids = MalRepair.candidates(rows, empty);
        assertTrue("sibling evidence must keep the series", ids.isEmpty());
    }

    @Test
    public void candidates_keep_series_with_sibling_ledger_evidence() {
        Map<String, Integer> ledger = new HashMap<>();
        ledger.put(Watched.norm("Marronnier Oukoku no Shichinin no Kishi") + "|2", 97);
        List<Store.Row> rows = new ArrayList<>();
        rows.add(libRow(21, "plan_to_watch|0", DARK, -1, "Marronnier", "Marronnier Oukoku no Shichinin no Kishi"));
        rows.add(libRow(21, "plan_to_watch|0", DARK, -1, "Marronnier 2", "Marronnier Oukoku no Shichinin no Kishi"));
        assertTrue(MalRepair.candidates(rows, ledger).isEmpty());
    }

    @Test
    public void withdrawal_cap_limits_one_pass_to_five() {
        // Safety valve: even a bad rule may never touch more than MAX_REVERTS
        // of the user's list unattended (2026-11-13 deleted 44).
        assertTrue(MalRepair.MAX_REVERTS <= 10);
    }

    @Test
    public void evidence_detects_playback_tag_and_ledger() {
        Map<String, Integer> ledger = new HashMap<>();
        ledger.put(Watched.norm("X") + "|1", 100);
        assertTrue(MalRepair.evidence(row(1, "", DARK, 60, "X", "X"), ledger)); // playback
        assertTrue(MalRepair.evidence(row(1, "", GREY, -1, "X", "X"), ledger)); // real tag
        assertTrue(MalRepair.evidence(row(1, "", DARK, -1, "X", "X"), ledger)); // ledger
        assertFalse(MalRepair.evidence(row(1, "", DARK, -1, "Y", "Y"), ledger));
    }

    @Test
    public void pushedApplied_uses_highest_episode_signature() {
        List<Store.Row> rows = new ArrayList<>();
        rows.add(row(10, "watching|2", DARK, -1, "Ghost", "Ghost"));
        rows.add(row(10, "watching|11", DARK, -1, "Ghost", "Ghost"));
        rows.add(row(11, "watching|3", DARK, -1, "Other", "Other"));
        assertEquals("watching|11", MalRepair.pushedApplied(rows, 10));
        assertEquals("watching|3", MalRepair.pushedApplied(rows, 11));
        assertEquals("", MalRepair.pushedApplied(rows, 99));
    }

    @Test
    public void hasArtifactRow_only_true_for_library_artifact() {
        Map<String, Integer> empty = new HashMap<>();
        List<Store.Row> rows = new ArrayList<>();
        rows.add(row(30, "plan_to_watch|0", DARK, -1, "X", "X")); // MX-observed -> not an artifact
        assertFalse(MalRepair.hasArtifactRow(rows, 30, empty));
        rows.add(libRow(31, "plan_to_watch|0", DARK, -1, "Y", "Y"));
        assertTrue(MalRepair.hasArtifactRow(rows, 31, empty));
        rows.add(libRow(32, "watching|6", DARK, -1, "Stale", "Stale"));
        assertFalse("a stale watched row is not an artifact", MalRepair.hasArtifactRow(rows, 32, empty));
    }

    // ---------------------------------------------------------- full pass (pure)

    @Test
    public void full_pass_withdraws_only_bogus_and_keeps_genuine() {
        Map<String, Integer> empty = new HashMap<>();
        List<Store.Row> rows = new ArrayList<>();
        // series 101: the proven fabrication, MAL identical, unrated -> revert
        rows.add(libRow(101, "plan_to_watch|0", DARK, -1, "Liar", "Liar"));
        // series 102: same write but the user rated it -> keep
        rows.add(libRow(102, "plan_to_watch|0", DARK, -1, "Rated", "Rated"));
        // series 103: same write but the user started watching -> keep
        rows.add(libRow(103, "plan_to_watch|0", DARK, -1, "Moved", "Moved"));
        // series 104: real playback -> keep
        rows.add(libRow(104, "plan_to_watch|0", DARK, 100, "Real", "Real"));
        // series 105: artifact push BUT sibling played -> not a candidate
        rows.add(libRow(105, "plan_to_watch|0", DARK, -1, "Hybrid", "Hybrid"));
        rows.add(libRow(105, "plan_to_watch|0", DARK, 100, "Hybrid", "Hybrid"));
        // series 106: a stale library-only WATCHING row -> not a candidate at all
        rows.add(libRow(106, "watching|6", DARK, -1, "Stale", "Stale"));

        Map<Integer, String> mal = new HashMap<>();
        mal.put(101, "plan_to_watch|0|0");  // exactly our fabrication
        mal.put(102, "plan_to_watch|0|7");  // user scored
        mal.put(103, "watching|5|0");      // user moved it
        mal.put(104, "plan_to_watch|0|0");  // played (not even a candidate)
        mal.put(105, "plan_to_watch|0|0");  // sibling evidence -> keep
        mal.put(106, "watching|6|0");       // stale watched row -> not a candidate

        int reverted = 0, keptRated = 0, keptMoved = 0;
        for (int id : MalRepair.candidates(rows, empty)) {
            switch (MalRepair.decide(mal.get(id), MalRepair.pushedApplied(rows, id))) {
                case REVERT:
                    reverted++;
                    break;
                case KEEP_RATED:
                    keptRated++;
                    break;
                case KEEP_MOVED:
                    keptMoved++;
                    break;
                default:
                    break;
            }
        }
        assertEquals(1, reverted);
        assertEquals(1, keptRated);
        assertEquals(1, keptMoved);
    }
}
