package com.bridge.mx;

/**
 * Pure helpers for MediaSession-based progress, the way real media apps work.
 *
 * <p>A player publishes an {@code android.media.session.MediaSession}. Its
 * {@code PlaybackState} carries a position, a playback speed and the
 * elapsed-realtime moment the position was set — not a continuously updating
 * value. To draw a moving progress bar (and to know how far something is
 * watched) apps interpolate the position to "now". This class holds that math,
 * with no Android dependency, so it is fully unit-testable.
 *
 * <p>Completion uses the same threshold as the rest of the bridge
 * ({@link PlaybackTracker#FINISH_PCT}).
 */
public final class MediaWatch {

    /** "No value" sentinel (matches PlaybackState.PLAYBACK_POSITION_UNKNOWN). */
    public static final long NONE = -1L;

    private MediaWatch() {
    }

    /**
     * Interpolate a live playback position.
     *
     * @param baseMs        position reported by the session
     * @param baseElapsedMs elapsed-realtime (ms) at which baseMs was reported
     * @param nowElapsedMs  elapsed-realtime (ms) now
     * @param speed         playback speed (1.0 = normal); values &le; 0 are treated as 1
     * @param playing       true while the session is actively playing
     * @return the position advanced to {@code nowElapsedMs}, or {@link #NONE}
     */
    public static long livePosition(long baseMs, long baseElapsedMs, long nowElapsedMs,
                                    float speed, boolean playing) {
        if (baseMs < 0) return NONE;
        if (!playing) return baseMs;                       // paused: position is exact
        if (baseElapsedMs <= 0 || nowElapsedMs < baseElapsedMs) return baseMs;
        float sp = speed <= 0f ? 1f : speed;
        double advanced = (nowElapsedMs - baseElapsedMs) * (double) sp;
        return baseMs + Math.round(advanced);
    }

    /** Watched percent 0..100, or -1 when it cannot be computed. */
    public static int percent(long posMs, long durMs) {
        return PlaybackTracker.percent(posMs, durMs);
    }

    /** True when the position is at/after the completion threshold of the duration. */
    public static boolean isFinished(long posMs, long durMs) {
        return PlaybackTracker.isFinished(percent(posMs, durMs));
    }
}
