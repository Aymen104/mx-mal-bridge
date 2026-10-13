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
 * MalRepair decision logic: which pushed rows are the artifact signature (a
 * push whose ink carries NO real MX tag, with no playback evidence), and for
 * those, when is it safe to withdraw the MAL write (the entry is still exactly
 * what we pushed and the user never rated it).
 *
 * <p>Recalibrated 2026-10-13 (MX 3.3.1): grey {@code 0x888888} is MX's real
 * <em>finished</em> tag, so a pushed grey row is a genuine watch and is NEVER
 * an artifact. Only the dark-indigo "no label" ink (None / NEW) is untagged.
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

    /** Real MX tags. */
    private static final int GREY = 0xFF888888; // finished
    private static final int BLUE = 0xFF1359D8; // last played (19,89,216)
    /** The untagged ink MX paints for None / NEW (53,52,76). */
    private static final int DARK = 0xFF35344C;

    // ---------------------------------------------------------------- decide

    @Test
    public void decide_reverts_exact_unrated_bogus_write() {
        assertEquals(MalRepair.Action.REVERT,
                MalRepair.decide("completed|12|0", "completed|12"));
    }

    @Test
    public void decide_reverts_watching_write_without_score() {
        assertEquals(MalRepair.Action.REVERT,
                MalRepair.decide("watching|2|0", "watching|2"));
    }

    @Test
    public void decide_keeps_rated_entry() {
        // "rated = genuine": the user scored it, never withdraw.
        assertEquals(MalRepair.Action.KEEP_RATED,
                MalRepair.decide("completed|12|8", "completed|12"));
    }

    @Test
    public void decide_hands_off_when_user_changed_status() {
        assertEquals(MalRepair.Action.KEEP_MOVED,
                MalRepair.decide("watching|5|0", "completed|12"));
    }

    @Test
    public void decide_hands_off_when_user_moved_episodes() {
        assertEquals(MalRepair.Action.KEEP_MOVED,
                MalRepair.decide("completed|4|0", "completed|12"));
    }

    @Test
    public void decide_keeps_absent_entry() {
        assertEquals(MalRepair.Action.KEEP_ABSENT, MalRepair.decide(null, "completed|12"));
    }

    @Test
    public void decide_skips_unparseable_mal_value() {
        assertEquals(MalRepair.Action.KEEP_UNKNOWN, MalRepair.decide("garbage", "completed|12"));
        assertEquals(MalRepair.Action.KEEP_UNKNOWN, MalRepair.decide("completed|x|0", "completed|12"));
        assertEquals(MalRepair.Action.KEEP_UNKNOWN, MalRepair.decide("completed|12|nan", "completed|12"));
    }

    @Test
    public void decide_skips_when_we_never_pushed() {
        assertEquals(MalRepair.Action.KEEP_UNKNOWN, MalRepair.decide("completed|12|0", ""));
    }

    // ------------------------------------------------------------- isArtifact

    @Test
    public void artifact_requires_pushed_untagged_row() {
        Map<String, Integer> empty = new HashMap<>();
        assertFalse(MalRepair.isArtifact(row(1, "", DARK, -1, "Dream", "Dream"), empty));
        assertFalse(MalRepair.isArtifact(row(1, "watching|2", GREY, -1, "Dream", "Dream"), empty));
        assertFalse(MalRepair.isArtifact(row(1, "watching|2", BLUE, -1, "Dream", "Dream"), empty));
        assertFalse(MalRepair.isArtifact(row(1, "watching|2", -1, -1, "Dream", "Dream"), empty));
        assertTrue(MalRepair.isArtifact(row(1, "watching|2", DARK, -1, "Dream", "Dream"), empty));
    }

    @Test
    public void artifact_rejected_by_playback_evidence() {
        Map<String, Integer> empty = new HashMap<>();
        assertFalse(MalRepair.isArtifact(row(1, "watching|2", DARK, 100, "Dream", "Dream"), empty));
        assertFalse(MalRepair.isArtifact(row(1, "watching|2", DARK, 60, "Dream", "Dream"), empty));
    }

    @Test
    public void artifact_rejected_by_watched_ledger() {
        Map<String, Integer> ledger = new HashMap<>();
        ledger.put(Watched.norm("Dream") + "|2", 100);
        assertFalse(MalRepair.isArtifact(row(1, "watching|2", DARK, -1, "Dream", "Dream"), ledger));
    }

    @Test
    public void artifact_ignores_withdrawn_rows() {
        Map<String, Integer> empty = new HashMap<>();
        assertFalse(MalRepair.isArtifact(row(1, "plan_to_watch|0", DARK, -1, "Dream", "Dream"), empty));
    }

    @Test
    public void artifact_requires_a_real_match() {
        Map<String, Integer> empty = new HashMap<>();
        assertFalse(MalRepair.isArtifact(row(0, "watching|2", DARK, -1, "Dream", "Dream"), empty));
    }

    // -------------------------------------------------------------- candidates

    @Test
    public void candidates_only_aggregate_artifact_series() {
        Map<String, Integer> empty = new HashMap<>();
        List<Store.Row> rows = new ArrayList<>();
        rows.add(row(10, "watching|2", DARK, -1, "Ghost", "Ghost"));       // artifact
        rows.add(row(10, "watching|1", DARK, -1, "Ghost", "Ghost"));       // same series
        rows.add(row(11, "completed|12", DARK, -1, "Bogus", "Bogus"));     // artifact
        rows.add(row(12, "watched|2", DARK, 100, "Real", "Real"));         // played -> no
        rows.add(row(13, "watched|2", GREY, -1, "Tagged", "Tagged"));      // real tag -> no
        List<Integer> ids = MalRepair.candidates(rows, empty);
        assertEquals(2, ids.size());
        assertTrue(ids.contains(10));
        assertTrue(ids.contains(11));
        assertFalse(ids.contains(12));
        assertFalse(ids.contains(13));
    }

    @Test
    public void candidates_keep_series_with_sibling_playback_evidence() {
        // Regression: an untagged artifact row pushed "watching|2" while a
        // SIBLING row of the same series genuinely played -> the series is real
        // and must never be withdrawn (this is the #Zombie Sagashitemasu case).
        Map<String, Integer> empty = new HashMap<>();
        List<Store.Row> rows = new ArrayList<>();
        rows.add(row(20, "watching|2", DARK, -1, "Zombie", "#Zombie Sagashitemasu")); // artifact push
        rows.add(row(20, "watching|2", DARK, 100, "Zombie", "#Zombie Sagashitemasu")); // real playback
        List<Integer> ids = MalRepair.candidates(rows, empty);
        assertTrue("sibling evidence must keep the series", ids.isEmpty());
    }

    @Test
    public void candidates_keep_series_with_sibling_ledger_evidence() {
        Map<String, Integer> ledger = new HashMap<>();
        ledger.put(Watched.norm("Marronnier Oukoku no Shichinin no Kishi") + "|2", 97);
        List<Store.Row> rows = new ArrayList<>();
        rows.add(row(21, "watching|2", DARK, -1, "Marronnier", "Marronnier Oukoku no Shichinin no Kishi"));
        rows.add(row(21, "watching|2", DARK, -1, "Marronnier 2", "Marronnier Oukoku no Shichinin no Kishi"));
        assertTrue(MalRepair.candidates(rows, ledger).isEmpty());
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

    // ---------------------------------------------------------- full pass (pure)

    @Test
    public void full_pass_withdraws_only_bogus_and_keeps_genuine() {
        Map<String, Integer> empty = new HashMap<>();
        List<Store.Row> rows = new ArrayList<>();
        // series 101: pushed bogus "completed|12", MAL identical, unrated -> revert
        rows.add(row(101, "completed|12", DARK, -1, "Liar", "Liar"));
        // series 102: pushed bogus but user rated it -> keep
        rows.add(row(102, "completed|12", DARK, -1, "Rated", "Rated"));
        // series 103: pushed bogus but user advanced to watching -> keep
        rows.add(row(103, "completed|12", DARK, -1, "Moved", "Moved"));
        // series 104: real playback -> keep
        rows.add(row(104, "completed|12", DARK, 100, "Real", "Real"));
        // series 105: untagged artifact push BUT sibling played -> not a candidate
        rows.add(row(105, "completed|12", DARK, -1, "Hybrid", "Hybrid"));
        rows.add(row(105, "completed|12", DARK, 100, "Hybrid", "Hybrid"));

        Map<Integer, String> mal = new HashMap<>();
        mal.put(101, "completed|12|0");  // exactly our bogus write
        mal.put(102, "completed|12|7");  // user scored
        mal.put(103, "watching|5|0");    // user moved it
        mal.put(104, "completed|12|0");  // played (not even a candidate)
        mal.put(105, "completed|12|0");  // sibling evidence -> keep

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
