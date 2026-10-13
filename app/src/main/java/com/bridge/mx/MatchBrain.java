package com.bridge.mx;

import java.util.List;

/**
 * The priority title matcher of the app: a grounded on-device {@link LlmBrain}
 * consulted on every match when it is loaded.
 *
 * <p>The deterministic {@link Matcher} ranks the real MAL search candidates;
 * when this brain is ready it is asked to choose among them (each match, not
 * just the low-score leftovers) and its pick wins. The deterministic scorer is
 * the fallback only when no brain is available or it abstains.
 * Implementations are grounded: they may only pick from the candidate list
 * they are given and can never invent a MAL id.
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
