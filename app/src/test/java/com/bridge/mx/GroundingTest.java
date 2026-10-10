package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Grounded prompt construction and strict response parsing. */
public class GroundingTest {

    private List<MatchBrain.Candidate> cands() {
        return Arrays.asList(
                new MatchBrain.Candidate(918, "Gintama", 201),
                new MatchBrain.Candidate(9969, "Gintama'", 51));
    }

    @Test
    public void promptIsGroundedAndListsRealCandidates() {
        String p = Grounding.buildPrompt("Gin S1", 48, cands());
        assertTrue(p.contains("Gin S1"));
        assertTrue(p.contains("Highest episode number seen: 48"));
        assertTrue(p.contains("0. Gintama (id=918, episodes=201)"));
        assertTrue(p.contains("1. Gintama' (id=9969, episodes=51)"));
        assertTrue(p.endsWith("Answer:"));
    }

    @Test
    public void promptShowsMediaTypeAndStatusWhenPresent() {
        List<MatchBrain.Candidate> c = Arrays.asList(
                new MatchBrain.Candidate(52991, "Sousou no Frieren", 28,
                        "tv", "finished_airing"));
        String p = Grounding.buildPrompt("Friren", 28, c);
        assertTrue(p.contains("(id=52991, episodes=28) [tv finished_airing]"));
        assertTrue(p.contains("Never pick a candidate that has not aired yet"));
    }

    @Test
    public void parsesPlainNumber() {
        assertEquals(1, Grounding.parseChoice("1", 2));
    }

    @Test
    public void parsesAnswerCue() {
        assertEquals(0, Grounding.parseChoice("Answer: 0", 2));
    }

    @Test
    public void rejectsNoneAndOutOfRange() {
        assertEquals(-1, Grounding.parseChoice("-1", 2));
        assertEquals(-1, Grounding.parseChoice("5", 2));
        assertEquals(-1, Grounding.parseChoice("no idea", 2));
    }
}
