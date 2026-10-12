package com.bridge.mx;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The bridge's comprehension self-test.
 *
 * <p>Run whenever the title knowledge changes — i.e. with <b>every new bulk of
 * anime titles</b> ingested from animeTosho — so a refresh can never silently
 * degrade the pipeline. It is exactly the graded {@link Exam} run
 * deterministically through the production path ({@link Educate} study →
 * {@link Aliases#apply} → {@link Matcher}); no reward is paid here, the manual
 * "Exam" button remains the only thing that pays out.
 *
 * <p>{@link #run()} is Android-free and unit tested; the rest is a tiny pref.
 */
public final class Selftest {

    /** Grade C or better: the same bar the reward system uses. */
    public static final double PASS = 0.70;

    private static final String PREF = "mxmeta";
    private static final String KEY_AT = "selftest_at";
    private static final String KEY_OK = "selftest_ok";
    private static final String KEY_N = "selftest_n";

    private Selftest() {
    }

    /** One self-test outcome. */
    public static final class Result {
        public final int correct;
        public final int total;
        /** The graded paper, so a repair request can name the exact mistakes. */
        final Exam.Report report;

        Result(Exam.Report report) {
            this.report = report;
            this.correct = report.correct();
            this.total = report.total();
        }

        public double accuracy() {
            return total == 0 ? 0 : (double) correct / total;
        }

        public boolean pass() {
            return total > 0 && accuracy() >= PASS;
        }

        /** One-line summary, e.g. "self-test 15/15 PASS". */
        public String line() {
            return "self-test " + correct + "/" + total + (pass() ? " PASS" : " FAIL");
        }
    }

    /** Score every exam question through the production pipeline (pure JVM). */
    public static Result run() {
        return new Result(Exam.runDeterministic());
    }

    public static void record(Context c, Result r) {
        prefs(c).edit()
                .putLong(KEY_AT, System.currentTimeMillis())
                .putInt(KEY_OK, r.correct)
                .putInt(KEY_N, r.total)
                .apply();
    }

    public static long lastRun(Context c) {
        return prefs(c).getLong(KEY_AT, 0);
    }

    /** Compact status fragment for the main screen. */
    public static String statusLine(Context c) {
        int n = prefs(c).getInt(KEY_N, 0);
        if (n == 0) return "test: n/a";
        return "test: " + prefs(c).getInt(KEY_OK, 0) + "/" + n;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
