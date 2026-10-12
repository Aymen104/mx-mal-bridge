package com.bridge.mx;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Reward system for the {@link Exam}.
 *
 * <p>Points are paid only for a successful exam (grade C or better). A perfect
 * paper pays a bonus, and consecutive passes build a streak bonus, so the
 * on-device matcher/LLM is nudged toward real accuracy rather than guesswork.
 * Everything is banked in the {@code mxreward} preferences file.
 *
 * <p>{@link #compute} is pure (no Android) so the scoring rules are unit
 * tested; {@link #award} persists and is called by the UI.
 */
public final class Reward {

    public static final int POINT_PER_CORRECT = 100;
    public static final int PERFECT_BONUS = 500;
    public static final int STREAK_STEP = 100;
    public static final int STREAK_CAP = 500;
    /** Accuracy at or above this counts as a pass and pays out. */
    public static final double PASS_ACCURACY = 0.70;

    private static final String PREF = "mxreward";
    private static SharedPreferences prefs;

    private Reward() {
    }

    /** Outcome of one graded exam. */
    public static final class Result {
        public final int total;
        public final int correct;
        public final int wrong;
        public final int abstained;
        public final double accuracy;
        public final String grade;    // S A B C D F
        public final String medal;    // gold | silver | bronze | none
        public final boolean passed;
        public final boolean perfect;
        public final int earned;      // points paid for this exam
        public final int bonus;       // part of earned explained by bonus
        public final int streak;      // streak after this exam

        Result(int total, int correct, int wrong, int abstained, double accuracy,
               String grade, String medal, boolean passed, boolean perfect,
               int earned, int bonus, int streak) {
            this.total = total;
            this.correct = correct;
            this.wrong = wrong;
            this.abstained = abstained;
            this.accuracy = accuracy;
            this.grade = grade;
            this.medal = medal;
            this.passed = passed;
            this.perfect = perfect;
            this.earned = earned;
            this.bonus = bonus;
            this.streak = streak;
        }

        public String line() {
            return "Exam " + correct + "/" + total
                    + " (" + Math.round(accuracy * 100) + "%)"
                    + " grade " + grade
                    + (perfect ? " PERFECT" : "")
                    + " | +" + earned + " pts"
                    + (streak > 1 ? " | streak " + streak : "");
        }
    }

    // --------------------------------------------------------------- scoring

    /** Grade letter for an accuracy in [0,1]. */
    public static String grade(double acc) {
        if (acc >= 0.98) return "S";
        if (acc >= 0.90) return "A";
        if (acc >= 0.80) return "B";
        if (acc >= 0.70) return "C";
        if (acc >= 0.60) return "D";
        return "F";
    }

    private static String medal(double acc, boolean perfect) {
        if (perfect) return "gold";
        if (acc >= 0.90) return "silver";
        if (acc >= 0.80) return "bronze";
        return "none";
    }

    /** Pure scoring: no persistence, no Android. */
    public static Result compute(int total, int correct, int wrong, int abstained, int streakBefore) {
        if (total < 0) total = 0;
        if (correct < 0) correct = 0;
        if (correct > total) correct = total;
        double acc = total == 0 ? 0 : (double) correct / total;
        boolean passed = total > 0 && acc >= PASS_ACCURACY;
        boolean perfect = total > 0 && correct == total;
        int streak = passed ? streakBefore + 1 : 0;

        int earned = 0;
        int bonus = 0;
        if (passed) {
            int base = correct * POINT_PER_CORRECT;
            bonus = (perfect ? PERFECT_BONUS : 0)
                    + Math.min(Math.max(0, streak - 1) * STREAK_STEP, STREAK_CAP);
            earned = base + bonus;
        }
        return new Result(total, correct, wrong, abstained, acc,
                grade(acc), medal(acc, perfect), passed, perfect, earned, bonus, streak);
    }

    /** Convenience wrapper for a full exam paper (wrong/abstained from the report). */
    public static Result compute(Exam.Report r, int streakBefore) {
        return compute(r.total(), r.correct(), r.wrong(), r.abstained(), streakBefore);
    }

    // ----------------------------------------------------------- persistence

    public static synchronized void init(Context ctx) {
        if (prefs == null) {
            prefs = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        }
    }

    public static synchronized int points() {
        return prefs == null ? 0 : prefs.getInt("points", 0);
    }

    public static synchronized int examsTaken() {
        return prefs == null ? 0 : prefs.getInt("taken", 0);
    }

    public static synchronized int examsPassed() {
        return prefs == null ? 0 : prefs.getInt("passed", 0);
    }

    public static synchronized int streak() {
        return prefs == null ? 0 : prefs.getInt("streak", 0);
    }

    public static synchronized String bestGrade() {
        return prefs == null ? "-" : prefs.getString("bestGrade", "-");
    }

    /** Grade the exam, bank the payout, and return the outcome. */
    public static synchronized Result award(Context ctx, Exam.Report r) {
        init(ctx);
        Result res = compute(r, streak());
        int best = prefs.getInt("bestPts", 0);
        String bestG = prefs.getString("bestGrade", "-");
        if (res.earned > best) best = res.earned;
        if (gradeRank(res.grade) > gradeRank(bestG)) bestG = res.grade;
        prefs.edit()
                .putInt("points", points() + res.earned)
                .putInt("taken", examsTaken() + 1)
                .putInt("passed", examsPassed() + (res.passed ? 1 : 0))
                .putInt("streak", res.streak)
                .putInt("bestPts", best)
                .putString("lastGrade", res.grade)
                .putString("bestGrade", bestG)
                .apply();
        return res;
    }

    private static int gradeRank(String g) {
        if (g == null || g.isEmpty()) return -1;
        switch (g.charAt(0)) {
            case 'S': return 6;
            case 'A': return 5;
            case 'B': return 4;
            case 'C': return 3;
            case 'D': return 2;
            case 'F': return 1;
            default: return 0;
        }
    }

    /** Flavour rank derived from banked points. */
    public static synchronized String rank() {
        int p = points();
        if (p >= 10000) return "Hokage";
        if (p >= 6000) return "Kage";
        if (p >= 3000) return "Jounin";
        if (p >= 1000) return "Chuunin";
        return "Genin";
    }

    /** One-line summary for status bars and dialogs. */
    public static synchronized String status() {
        return "\u2b50 " + points() + " pts"
                + " | " + examsPassed() + "/" + examsTaken() + " exams"
                + " | streak " + streak()
                + " | best " + bestGrade()
                + " | " + rank();
    }

    /** Longer multi-line summary for the exam dialog. */
    public static synchronized String detail() {
        return "Banked: " + points() + " pts (" + rank() + ")\n"
                + "Exams: " + examsPassed() + " passed / " + examsTaken() + " taken\n"
                + "Current streak: " + streak() + "\n"
                + "Best grade: " + bestGrade() + "\n"
                + "Rules: +" + POINT_PER_CORRECT + "/correct, +" + PERFECT_BONUS
                + " perfect, +" + STREAK_STEP + " per streak step (cap "
                + STREAK_CAP + "); pass \u2265 "
                + Math.round(PASS_ACCURACY * 100) + "%.";
    }

    /** Reset all banked rewards (used by the Wipe button's sibling action). */
    public static synchronized void reset() {
        if (prefs != null) prefs.edit().clear().apply();
    }

    public static String gradeInWords(String g) {
        switch (g == null ? "" : g) {
            case "S": return "excellent";
            case "A": return "great";
            case "B": return "good";
            case "C": return "passed";
            case "D": return "close";
            default: return "failed";
        }
    }

    public static String rankOf(int points) {
        if (points >= 10000) return "Hokage";
        if (points >= 6000) return "Kage";
        if (points >= 3000) return "Jounin";
        if (points >= 1000) return "Chuunin";
        return "Genin";
    }

    static String pct(double acc) {
        return Math.round(acc * 100) + "%";
    }
}
