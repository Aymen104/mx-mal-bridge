package com.bridge.mx;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The bridge's drill: lessons, then the {@link Exam}, repeated until a perfect
 * paper (or a retry cap).
 *
 * <p>A student does not sit a paper once. They study, sit it, study the
 * mistakes, and sit it again until they can score it perfectly. This class does
 * exactly that for the matcher:
 * <ol>
 *   <li><b>Lesson</b> — before every attempt {@link Educate#study} primes the
 *       shorthand table from the whole anime catalogue, exactly as production
 *       does before real work.</li>
 *   <li><b>Sit the paper</b> — {@link Exam#run} answers every question through
 *       the real pipeline, with the on-device LLM as the priority matcher.</li>
 *   <li><b>Study the mistakes</b> — every still-wrong question becomes one
 *       targeted lesson: the file's parsed title is remembered as a shorthand
 *       for the title it <em>should</em> have matched ({@link Aliases#learn}),
 *       so the next attempt resolves it.</li>
 * </ol>
 * The loop stops the instant a paper is perfect, and gives up after
 * {@link #DEFAULT_TRIES} attempts (raising a repair incident if it never gets
 * there).
 *
 * <p>{@link #drill} is Android-free — the paper is a {@link Supplier} and the
 * lesson a {@link Function} — so the loop itself is unit tested. The
 * {@link #drill(Context, MatchBrain, int)} overload wires it to
 * {@link Educate}, {@link Exam} and {@link AliasStore}.
 */
public final class Bootcamp {

    /** Attempts allowed before the drill gives up. */
    public static final int DEFAULT_TRIES = 25;

    private Bootcamp() {
    }

    /** One sitting of the paper. */
    public static final class Attempt {
        public final int n;          // 1-based attempt number
        public final int correct;
        public final int total;
        public final boolean brain;  // the on-device LLM answered

        Attempt(int n, int correct, int total, boolean brain) {
            this.n = n;
            this.correct = correct;
            this.total = total;
            this.brain = brain;
        }

        public boolean perfect() {
            return total > 0 && correct == total;
        }

        /** e.g. {@code try 3: 15/15 (with AI) - perfect}. */
        public String line() {
            return "try " + n + ": " + correct + "/" + total
                    + (brain ? " (with AI)" : "")
                    + (perfect() ? "  \u2705 PERFECT" : "");
        }
    }

    /** The whole drill. */
    public static final class Outcome {
        public final List<Attempt> attempts;
        public final int lessonsLearned;
        public final Exam.Report finalReport;

        Outcome(List<Attempt> attempts, int lessonsLearned, Exam.Report finalReport) {
            this.attempts = attempts;
            this.lessonsLearned = lessonsLearned;
            this.finalReport = finalReport;
        }

        public int tries() {
            return attempts.size();
        }

        /** A perfect paper was reached within the cap. */
        public boolean perfect() {
            return !attempts.isEmpty() && attempts.get(attempts.size() - 1).perfect();
        }

        /** Best score across the attempts. */
        public Attempt best() {
            Attempt b = null;
            for (Attempt a : attempts) {
                if (b == null || a.correct > b.correct) b = a;
            }
            return b;
        }

        /** One-line verdict for the status/log line. */
        public String line() {
            Attempt last = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
            if (last == null) return "Drill: no attempts";
            if (perfect()) {
                return "Drill perfect: " + last.correct + "/" + last.total
                        + " on try " + tries() + "/" + " ("
                        + lessonsLearned + " lesson(s) learned)";
            }
            return "Drill not perfect after " + tries() + " tries (best "
                    + (best() == null ? "?" : best().correct + "/" + best().total) + ")";
        }
    }

    // ------------------------------------------------------------------ core

    /**
     * Sit the paper up to {@code maxTries} times. After each imperfect sitting
     * {@code lesson} is applied (and returns how many targeted lessons it
     * taught); the moment a sitting is perfect the drill stops.
     *
     * @param maxTries 1..N attempts (values &lt; 1 are clamped to 1)
     * @param sit      takes one paper
     * @param lesson   studies the mistakes of a paper, returns the count taught
     */
    public static Outcome drill(int maxTries,
                                Supplier<Exam.Report> sit,
                                Function<Exam.Report, Integer> lesson) {
        if (maxTries < 1) maxTries = 1;
        List<Attempt> attempts = new ArrayList<>();
        int learned = 0;
        Exam.Report last = null;
        for (int n = 1; n <= maxTries; n++) {
            last = sit.get();
            attempts.add(new Attempt(n, last.correct(), last.total(), last.brainUsed));
            if (last.total() > 0 && last.correct() == last.total()) break;
            Integer k = lesson.apply(last);
            learned += k == null ? 0 : k;
        }
        return new Outcome(attempts, learned, last);
    }

    // -------------------------------------------------------------- Android

    /**
     * The Android drill: study the catalogue, sit the paper with the on-device
     * LLM as priority matcher, and turn each miss into a lesson, up to
     * {@code maxTries} times.
     *
     * <p>The model is loaded up front (blocking, so call this off the main
     * thread), so the drill exercises the <em>AI</em> matcher — the LLM is the
     * priority and decides every match. When no model is installed the drill
     * falls back to the deterministic matcher.
     */
    public static Outcome drill(Context ctx, LlmBrain brain, int maxTries) {
        if (brain != null && !brain.ready()) {
            brain.ensureLoaded(); // make the LLM the priority before the first sitting
        }
        return drill(maxTries,
                () -> {
                    Educate.study(ctx);       // the general lesson
                    return Exam.run(brain);   // the paper, with AI as in production
                },
                r -> {
                    int k = learnLessons(r);  // targeted lessons from the mistakes
                    AliasStore.persist(ctx);  // remember them for real work too
                    return k;
                });
    }

    /**
     * Turn a wrong paper into targeted lessons: for every miss that expected a
     * real match, remember the file's parsed title as a shorthand for the title
     * it should have matched. A "must abstain" question has no title to learn
     * (nothing was supposed to be matched), so it is skipped.
     *
     * @return the number of lessons taught
     */
    public static int learnLessons(Exam.Report paper) {
        int learned = 0;
        for (Exam.Item it : paper.failures()) {
            if (it.q.expectId == 0) continue;
            String canonical = Exam.titleOf(it.q, it.q.expectId);
            if (canonical == null || canonical.isEmpty()) continue;
            Aliases.learn(it.q.want, canonical);
            learned++;
        }
        return learned;
    }
}
