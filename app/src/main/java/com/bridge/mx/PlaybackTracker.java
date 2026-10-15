package com.bridge.mx;

/**
 * Pure helpers that turn on-screen playback labels / seek-bar values into a
 * watched percentage. No Android dependency, so it is fully unit-testable.
 *
 * <p>MX Player's player screen exposes:
 * <pre>
 *   VideoTimeCurrent  "00:05"        (elapsed, or remaining if the user set it)
 *   VideoTimeTotal    "00:11"
 *   VideoSeekbar      a SeekBar whose RangeInfo gives current/max directly
 * </pre>
 *
 * <p>Completion rule (user spec): an episode is finished when playback reached
 * >= {@link #FINISH_PCT}% of its total duration.
 */
public final class PlaybackTracker {

    /** Watched % at or above which an episode counts as finished. */
    public static final int FINISH_PCT = 95;

    private static final java.util.regex.Pattern CLOCK_DIGITS =
            java.util.regex.Pattern.compile("\\d{1,6}");

    private PlaybackTracker() {
    }

    /**
     * Parse a clock label into whole seconds.
     *
     * <p>Accepts "SS", "M:SS", "H:MM:SS", an optional leading '-' (remaining
     * time), and ignores a trailing "/ total" or other noise. Returns -1 when
     * the string is not a clock.
     */
    public static int parseTime(String raw) {
        if (raw == null) return -1;
        String s = raw.trim();
        if (s.isEmpty()) return -1;
        // "00:05 / 00:11" -> keep the first clock
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash).trim();
        if (s.startsWith("-")) s = s.substring(1).trim(); // remaining time -> magnitude
        if (s.startsWith("+")) s = s.substring(1).trim();
        if (s.isEmpty()) return -1;

        String[] parts = s.split(":");
        if (parts.length < 1 || parts.length > 3) return -1;
        long total = 0;
        for (String p : parts) {
            p = p.trim();
            if (p.isEmpty() || !CLOCK_DIGITS.matcher(p).matches()) return -1;
            total = total * 60 + Long.parseLong(p);
        }
        if (total < 0 || total > 24L * 3600L * 7L) return -1; // sanity: < 1 week
        return (int) total;
    }

    /**
     * Watched percent (0..100) from a position and a duration in the same unit.
     * Returns -1 when the fraction cannot be computed (no duration / no position).
     */
    public static int percent(long position, long duration) {
        if (position < 0 || duration <= 0) return -1;
        long p = Math.round(position * 100.0d / duration);
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        return (int) p;
    }

    /**
     * Watched percent from a {@code VideoTimeCurrent} / {@code VideoTimeTotal}
     * label pair.
     *
     * @param curText        current label ("00:05" or "-00:06" when remaining)
     * @param totText        total label ("00:11")
     * @param curIsRemaining true when the "current" label actually shows the
     *                       time left rather than elapsed
     */
    public static int percentFromLabels(String curText, String totText,
                                        boolean curIsRemaining) {
        int cur = parseTime(curText);
        int tot = parseTime(totText);
        if (cur < 0 || tot <= 0) return -1;
        long pos = curIsRemaining ? (long) (tot - cur) : cur;
        if (pos < 0) pos = 0;
        return percent(pos, tot);
    }

    /** True when this watched percent means the episode was watched to the end. */
    public static boolean isFinished(int percent) {
        return percent >= FINISH_PCT;
    }

    /**
     * Parse a combined "cur / total" label (MX's {@code elapsed_time}, e.g.
     * "22:38 / 26:18") into {@code {currentSeconds, totalSeconds}}, or null when
     * it is not a clock pair.
     */
    public static int[] parsePair(String raw) {
        if (raw == null) return null;
        int slash = raw.indexOf('/');
        if (slash < 0) return null;
        int a = parseTime(raw.substring(0, slash));
        int b = parseTime(raw.substring(slash + 1));
        if (a < 0 || b <= 0) return null;
        return new int[]{a, b};
    }
}
