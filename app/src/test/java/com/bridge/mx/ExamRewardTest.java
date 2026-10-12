package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The exam answer key and the reward payout rules. */
public class ExamRewardTest {

    @Test
    public void deterministicExamScoresPerfectButStillAbstainsOnNoise() {
        Exam.Report r = Exam.runDeterministic();
        assertEquals(Exam.bank().size(), r.total());
        assertEquals("study + alias pipeline must answer every question", 15, r.correct());
        assertEquals(15, r.total());
        // The two "must abstain" noise questions must still be left unmatched.
        for (Exam.Item it : r.items) {
            if (it.q.expectId == 0) {
                assertEquals("must abstain: " + it.q.label, 0, it.gotId);
            }
        }
    }

    @Test
    public void perfectRunWithGroundedBrain() {
        // A grounded stub brain: answer by the parsed title, exactly like the
        // real on-device model is allowed to (pick only among real candidates).
        final Map<String, Integer> key = new HashMap<>();
        key.put("Gin S1", 918);
        key.put("Friren", 52991);
        key.put("Koi wo Shitai", -1);
        key.put("Random Home Video", -1);
        MatchBrain brain = new MatchBrain() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public int pick(String want, int ep, List<Candidate> cands) {
                Integer want0 = key.get(want);
                if (want0 == null) return 0;
                if (want0 < 0) return -1;
                for (int i = 0; i < cands.size(); i++) {
                    if (cands.get(i).id == want0) return i;
                }
                return -1;
            }
        };

        Exam.Report r = Exam.run(brain);
        assertEquals(r.total(), r.correct());
        assertEquals(1.0, r.accuracy(), 1e-9);

        Reward.Result res = Reward.compute(r, 0);
        assertTrue(res.perfect);
        assertTrue(res.passed);
        assertEquals("S", res.grade);
        assertEquals(15 * Reward.POINT_PER_CORRECT + Reward.PERFECT_BONUS, res.earned);
    }

    @Test
    public void rewardRules() {
        Reward.Result perfect = Reward.compute(15, 15, 0, 0, 0);
        assertTrue(perfect.perfect);
        assertEquals("S", perfect.grade);
        assertEquals(1500 + Reward.PERFECT_BONUS, perfect.earned);
        assertEquals(1, perfect.streak);

        Reward.Result passB = Reward.compute(15, 12, 3, 0, 0);
        assertTrue(passB.passed);
        assertEquals("B", passB.grade);
        assertEquals(1200, passB.earned);
        assertEquals(1, passB.streak);

        Reward.Result fail = Reward.compute(15, 10, 5, 0, 0);
        assertFalse(fail.passed);
        assertEquals("D", fail.grade);
        assertEquals(0, fail.earned);
        assertEquals(0, fail.streak);

        Reward.Result streak = Reward.compute(15, 15, 0, 0, 2);
        assertEquals(3, streak.streak);
        assertEquals(1500 + Reward.PERFECT_BONUS + 2 * Reward.STREAK_STEP, streak.earned);
    }

    @Test
    public void streakResetsOnFailureAndGradesAreOrdered() {
        Reward.Result r = Reward.compute(15, 4, 11, 0, 5);
        assertEquals(0, r.streak);
        assertEquals(0, r.earned);

        assertEquals("F", Reward.grade(0.10));
        assertEquals("D", Reward.grade(0.60));
        assertEquals("C", Reward.grade(0.70));
        assertEquals("B", Reward.grade(0.80));
        assertEquals("A", Reward.grade(0.90));
        assertEquals("S", Reward.grade(0.99));
    }
}
