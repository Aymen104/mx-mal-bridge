package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/** Close-matching: edit distance, containment, token overlap, episode hint. */
public class MatcherTest {

    private static JSONObject node(int id, String title, int numEp) throws Exception {
        return node(id, title, numEp, "", "");
    }

    private static JSONObject node(int id, String title, int numEp,
                                   String mediaType, String status) throws Exception {
        JSONObject n = new JSONObject();
        n.put("id", id);
        n.put("title", title);
        n.put("num_episodes", numEp);
        n.put("media_type", mediaType);
        n.put("status", status);
        JSONObject o = new JSONObject();
        o.put("node", n);
        return o;
    }

    private static JSONArray gintama() throws Exception {
        JSONArray a = new JSONArray();
        a.put(node(918, "Gintama", 201));
        a.put(node(9969, "Gintama'", 51));
        a.put(node(28977, "Gintama\u00b0", 51));
        return a;
    }

    @Test
    public void exactTitleWins() throws Exception {
        Matcher.Result r = Matcher.best(gintama(), "Gintama", 48);
        assertEquals(918, r.id);
    }

    @Test
    public void episodeHintRejectsTooShortSeries() throws Exception {
        JSONArray a = new JSONArray();
        a.put(node(1, "Some Show", 12));
        a.put(node(2, "Some Show", 100));
        // Both names are identical; the episode hint must prefer the one long
        // enough to contain episode 48.
        Matcher.Result r = Matcher.best(a, "Some Show", 48);
        assertEquals(2, r.id);
        assertEquals(100, r.numEp);
    }

    @Test
    public void tokenOverlapMatchesExtraWords() throws Exception {
        JSONArray a = new JSONArray();
        a.put(node(10, "Seishun Buta Yarou wa Santa Claus no Yume wo Minai", 13));
        Matcher.Result r = Matcher.best(a,
                "Seishun Buta Yarou wa Santa Claus no Yume wo Minai END FHD", 13);
        assertEquals(10, r.id);
    }

    @Test
    public void nothingCloseEnoughReturnsNull() throws Exception {
        JSONArray a = new JSONArray();
        a.put(node(10, "Chuunibyou demo Koi ga Shitai!", 12));
        assertNull(Matcher.best(a, "Koi wo Shitai", 12));
    }

    @Test
    public void belowSignalRowsAbstainEvenWhenBrainWouldGrab() throws Exception {
        // "Gin S1" cannot be connected by the deterministic scorer (0.429 <
        // ACCEPT). In production the learned alias table rewrites shorthand
        // first (the self-test's 15/15 covers these via Aliases.apply), so a
        // below-signal row must mean "not this anime": the closest lookalike
        // must not win, and the brain is not even consulted (no inference is
        // burned on a row that has no genuine candidate).
        JSONArray a = new JSONArray();
        a.put(node(918, "Gintama", 201, "tv", "finished_airing"));
        a.put(node(9969, "Gintama'", 51, "tv", "finished_airing"));
        final boolean[] called = {false};
        MatchBrain brain = new MatchBrain() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public int pick(String want, int ep, List<Candidate> cands) {
                called[0] = true;
                for (int i = 0; i < cands.size(); i++) {
                    if (cands.get(i).id == 918) return i;
                }
                return -1;
            }
        };
        Matcher.Result r = Matcher.bestWithBrain(a, "Gin S1", 48, brain);
        assertNull(r);
        assertFalse("below-signal rows must never reach the brain", called[0]);
    }

    @Test
    public void falseFriendIsNeverPushed() throws Exception {
        // "Koi wo Shitai" shares words with "Chuunibyou demo Koi ga Shitai!"
        // (0.360 < ACCEPT) but is not that anime: the required answer is an
        // abstention. Even an eager brain must not push the lookalike — that
        // is exactly how noise got pushed before the repair system existed.
        JSONArray a = new JSONArray();
        a.put(node(14741, "Chuunibyou demo Koi ga Shitai!", 12, "tv", "finished_airing"));
        final boolean[] called = {false};
        MatchBrain brain = new MatchBrain() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public int pick(String want, int ep, List<Candidate> cands) {
                called[0] = true;
                return 0; // would grab the false friend
            }
        };
        Matcher.Result r = Matcher.bestWithBrain(a, "Koi wo Shitai", 12, brain);
        assertNull(r);
        assertFalse("false friends must never reach the brain", called[0]);
    }

    @Test
    public void onlyImplausibleCandidateAbstainsEvenWhenBrainWouldGrab() throws Exception {
        // The file is at episode 48 but the only MAL entry has just 12
        // episodes: structurally impossible. The plausible pool is empty, so the
        // brain must not be handed the raw top to grab.
        JSONArray a = new JSONArray();
        a.put(node(1, "Some Show", 12, "tv", "finished_airing"));
        final boolean[] called = {false};
        MatchBrain brain = new MatchBrain() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public int pick(String want, int ep, List<Candidate> cands) {
                called[0] = true;
                return 0;
            }
        };
        assertNull(Matcher.bestWithBrain(a, "Some Show", 48, brain));
        assertFalse("an impossible candidate must never reach the brain", called[0]);
    }

    @Test
    public void readyBrainWinsOverConfidentDeterministicMatch() throws Exception {
        // The LLM is the priority matcher: even when the deterministic scorer
        // is confident (0.85+), a ready brain is consulted and its grounded
        // pick (here the continuation named by the file's own title) wins.
        Matcher.Result r = Matcher.bestWithBrain(gintama(), "Gintama'", 48, grabs(9969));
        assertEquals(9969, r.id);
    }

    @Test
    public void brainAbstentionFallsBackToConfidentDeterministic() throws Exception {
        final boolean[] called = {false};
        MatchBrain brain = new MatchBrain() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public int pick(String want, int ep, List<Candidate> cands) {
                called[0] = true;
                return -1; // the model abstains: "none fit"
            }
        };
        Matcher.Result r = Matcher.bestWithBrain(gintama(), "Gintama", 48, brain);
        assertEquals(918, r.id);
        assertTrue("a ready brain must be consulted on every match", called[0]);
    }

    /** A brain that grabs a specific id when it can see it, else index 0. */
    private static MatchBrain grabs(final int idToGrab) {
        return new MatchBrain() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public int pick(String want, int ep, List<Candidate> cands) {
                for (int i = 0; i < cands.size(); i++) {
                    if (cands.get(i).id == idToGrab) return i;
                }
                return 0;
            }
        };
    }

    @Test
    public void unairedSequelIsNeverOfferedToBrain() throws Exception {
        JSONArray a = new JSONArray();
        a.put(node(52991, "Sousou no Frieren", 28, "tv", "finished_airing"));
        a.put(node(63816, "Sousou no Frieren: Ougonkyou-hen", 0, "tv", "not_yet_aired"));
        // Even though the brain would grab the "-hen" sequel, it is filtered
        // out (it has not aired), so the main 28-episode series must win.
        // "Frieren" scores 0.867 — a real signal, so the brain is consulted
        // with only the aired candidate on the table.
        Matcher.Result r = Matcher.bestWithBrain(a, "Frieren", 28, grabs(63816));
        assertEquals(52991, r.id);
    }

    @Test
    public void tooShortNextSeasonIsNeverOfferedToBrain() throws Exception {
        JSONArray a = new JSONArray();
        a.put(node(52991, "Sousou no Frieren", 28, "tv", "finished_airing"));
        a.put(node(59978, "Sousou no Frieren 2nd Season", 10, "tv", "finished_airing"));
        // Episode 28 cannot come from a 10-episode later season, so the brain
        // is offered only the aired 28-episode series.
        Matcher.Result r = Matcher.bestWithBrain(a, "Frieren", 28, grabs(59978));
        assertEquals(52991, r.id);
    }

    @Test
    public void sequelMarkerNotNamedByFileIsRejected() throws Exception {
        JSONArray a = new JSONArray();
        a.put(node(1, "Base Show", 24, "tv", "finished_airing"));
        a.put(node(2, "Base Show Movie", 0, "movie", "finished_airing"));
        Matcher.Result r = Matcher.bestWithBrain(a, "Base Show EP 10", 10, grabs(2));
        assertEquals(1, r.id);
    }
}
