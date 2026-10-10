package com.bridge.mx;

import java.util.List;

/**
 * A pluggable "second opinion" for title matching.
 *
 * <p>The deterministic {@link Matcher} is the primary matcher. When its best
 * score is low or ambiguous, a brain is asked to choose among the real MAL
 * search candidates. Implementations are grounded: they may only pick from the
 * candidate list they are given and can never invent a MAL id.
 */
public interface MatchBrain {

    /** One real MyAnimeList search result offered to the brain. */
    class Candidate {
        public final int id;
        public final String title;
        public final int numEp;
        public final String mediaType;
        public final String status;

        public Candidate(int id, String title, int numEp) {
            this(id, title, numEp, "", "");
        }

        public Candidate(int id, String title, int numEp, String mediaType, String status) {
            this.id = id;
            this.title = title;
            this.numEp = numEp;
            this.mediaType = mediaType == null ? "" : mediaType;
            this.status = status == null ? "" : status;
        }
    }

    /** True when the brain is loaded and able to answer. */
    boolean ready();

    /** Short status for the UI ("ready", "no model", "loading", ...). */
    default String status() {
        return ready() ? "ready" : "off";
    }

    /**
     * Choose the best candidate for a parsed title.
     *
     * @param want       the parsed title as it appears on device
     * @param ep         highest episode number seen (-1 if unknown)
     * @param candidates real MAL candidates, in deterministic rank order
     * @return index into {@code candidates} (0-based), or -1 for "none fit"
     */
    int pick(String want, int ep, List<Candidate> candidates);
}
