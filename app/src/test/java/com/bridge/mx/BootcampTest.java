package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** The drill loop and its lessons: study, sit, repeat until perfect. */
public class BootcampTest {

    private static final int TOTAL = 15;

    /** A synthetic paper with {@code correct} right out of {@code total}. */
    private static Exam.Report paper(int total, int correct) {
        List<Exam.Q> bank = Exam.bank();
        List<Exam.Item> items = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            Exam.Q q = bank.get(i % bank.size());
            int got = i < correct ? q.expectId
                    : (q.expectId == 0 ? 999999 : 0); // guaranteed wrong
            items.add(new Exam.Item(q, got, 1.0));
        }
        return new Exam.Report(items, false, 0L);
    }

    @Test
    public void perfectPaperStopsOnTheFirstTry() {
        final AtomicInteger lessons = new AtomicInteger();
        Bootcamp.Outcome o = Bootcamp.drill(25,
                () -> paper(TOTAL, TOTAL),
                r -> lessons.incrementAndGet());
        assertTrue(o.perfect());
        assertEquals(1, o.tries());
        assertEquals(0, lessons.get()); // nothing to study on a perfect paper
        assertEquals(0, o.lessonsLearned);
    }

    @Test
    public void givesLessonsAndStopsWhenPerfect() {
        final AtomicInteger sittings = new AtomicInteger();
        final AtomicInteger lessons = new AtomicInteger();
        Bootcamp.Outcome o = Bootcamp.drill(25,
                () -> sittings.incrementAndGet() == 1 ? paper(TOTAL, TOTAL - 1) : paper(TOTAL, TOTAL),
                r -> {
                    lessons.incrementAndGet();
                    return 1;
                });
        assertTrue(o.perfect());
        assertEquals(2, o.tries());          // failed once, then aced it
        assertEquals(1, lessons.get());      // studied the mistakes between
        assertEquals(1, o.lessonsLearned);
    }

    @Test
    public void givesUpAtTheCap() {
        final AtomicInteger lessons = new AtomicInteger();
        Bootcamp.Outcome o = Bootcamp.drill(25,
                () -> paper(TOTAL, TOTAL - 1),
                r -> {
                    lessons.incrementAndGet();
                    return 1;
                });
        assertFalse(o.perfect());
        assertEquals(25, o.tries());
        assertEquals(25, lessons.get());     // a lesson after every imperfect try
        assertNotNull(o.finalReport);
        assertEquals(TOTAL - 1, o.finalReport.correct());
    }

    @Test
    public void lessonsActuallyTeachTheMissedTitle() throws Exception {
        // A shorthand the app has never seen, mapped to a real title.
        String want = "Zyxwuvoir";
        String expected = "Mystery Show That Should Match";
        Exam.Q q = question(want, 4242, 1);

        List<Exam.Item> items = new ArrayList<>();
        items.add(new Exam.Item(q, 0, 0.1)); // missed (abstained)
        Exam.Report wrong = new Exam.Report(items, false, 0L);

        assertEquals(0, wrong.correct());
        int taught = Bootcamp.learnLessons(wrong);
        assertEquals(1, taught);

        // After the lesson the pipeline rewrites the shorthand to the title.
        assertEquals(expected, Aliases.apply(want));
    }

    @Test
    public void abstainQuestionsTeachNothing() throws Exception {
        // "Random noise" must abstain (expectId 0): there is no title to learn.
        Exam.Q q = question("Random Noise", 0, 5);
        List<Exam.Item> items = new ArrayList<>();
        items.add(new Exam.Item(q, 999999, 0.1)); // wrong: matched when it shouldn't
        Exam.Report wrong = new Exam.Report(items, false, 0L);

        assertEquals(0, Bootcamp.learnLessons(wrong));
    }

    @Test
    public void deterministicDrillIsPerfectImmediately() {
        // The real bank, run deterministically (no LLM): already perfect, so the
        // drill should stop on try 1 without teaching anything.
        Bootcamp.Outcome o = Bootcamp.drill(25, Exam::runDeterministic, r -> 0);
        assertTrue("deterministic bank should be perfect", o.perfect());
        assertEquals(1, o.tries());
        assertEquals(Exam.bank().size(), o.finalReport.total());
    }

    // ---------------------------------------------------------------- helpers

    /** A one-candidate question whose parsed title is {@code want}. */
    private static Exam.Q question(String want, int expectId, int ep) throws Exception {
        JSONObject n = new JSONObject();
        n.put("id", expectId);
        n.put("title", "Mystery Show That Should Match");
        n.put("num_episodes", 24);
        n.put("media_type", "tv");
        n.put("status", "finished_airing");
        JSONObject wrapped = new JSONObject();
        wrapped.put("node", n);
        JSONArray pool = new JSONArray();
        pool.put(wrapped);
        return new Exam.Q(want + " 01", want, ep, expectId, false, pool);
    }
}
