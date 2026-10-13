package com.bridge.mx;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The bridge's own exam paper.
 *
 * <p>A fixed bank of real anime questions (parsed title + episode hint +
 * MyAnimeList-shaped candidates). Each question is answered by the same
 * pipeline the bridge uses in production ({@link Aliases#apply} then
 * {@link Matcher}, optionally with the on-device {@link MatchBrain} /
 * {@link LlmBrain} as second opinion), then graded against the known answer.
 * The result drives the {@link Reward} system.
 *
 * <p>Before the paper is run, {@link Educate} studies the bundled anime
 * catalogue so the shorthand table is populated the way it is in production.
 *
 * <p>Questions deliberately exercise every comprehension channel the app ships:
 * exact names, release-tag noise, word-order swaps, typos, sequel/season
 * disambiguation, kana -> romaji and Arabic folding, plus "must abstain" noise.
 */
public final class Exam {

    private Exam() {
    }

    /** One exam question. */
    public static final class Q {
        public final String label;   // what the on-device file looked like
        public final String want;    // the parsed title fed to the matcher
        public final int ep;         // highest episode seen (-1 unknown)
        public final int expectId;   // expected MAL id, or 0 for "must abstain"
        public final boolean brain;  // true when only the brain can resolve it
        public final JSONArray pool;

        Q(String label, String want, int ep, int expectId, boolean brain, JSONArray pool) {
            this.label = label;
            this.want = want;
            this.ep = ep;
            this.expectId = expectId;
            this.brain = brain;
            this.pool = pool;
        }
    }

    /** Graded answer for one question. */
    public static final class Item {
        public final Q q;
        public final int gotId;      // matched MAL id, or 0 for "abstained"
        public final double score;   // deterministic top score (diagnostic)
        public final boolean correct;

        Item(Q q, int gotId, double score) {
            this.q = q;
            this.gotId = gotId;
            this.score = score;
            this.correct = gotId == q.expectId;
        }
    }

    /** Full exam result. */
    public static final class Report {
        public final List<Item> items;
        public final boolean brainUsed;
        public final long millis;

        Report(List<Item> items, boolean brainUsed, long millis) {
            this.items = items;
            this.brainUsed = brainUsed;
            this.millis = millis;
        }

        public int total() {
            return items.size();
        }

        public int correct() {
            int n = 0;
            for (Item i : items) if (i.correct) n++;
            return n;
        }

        public int wrong() {
            int n = 0;
            for (Item i : items) if (!i.correct && i.gotId != 0) n++;
            return n;
        }

        public int abstained() {
            int n = 0;
            for (Item i : items) if (i.gotId == 0) n++;
            return n;
        }

        /** Fraction correct in [0,1]. */
        public double accuracy() {
            int t = total();
            return t == 0 ? 0 : (double) correct() / t;
        }

        public List<Item> failures() {
            List<Item> f = new ArrayList<>();
            for (Item i : items) if (!i.correct) f.add(i);
            return f;
        }
    }

    // ---------------------------------------------------------------- runner

    public static Report runDeterministic() {
        return run(null);
    }

    /**
     * Answer every question. When {@code brain} is non-null it is the same
     * grounded second opinion production uses; it may only pick from the real
     * candidates of each question.
     */
    public static Report run(MatchBrain brain) {
        long t0 = System.currentTimeMillis();
        boolean used = brain != null && brain.ready();
        List<Item> out = new ArrayList<>();
        for (Q q : bank()) {
            int got = 0;
            double score = 0;
            try {
                // Production rewrites a shorthand through the alias table before
                // matching ("Gin S1" -> "Gintama"), whether the alias is the
                // built-in one or one learned by the study pass. The exam must
                // run the same pipeline, otherwise it grades a path the app
                // never actually takes.
                String want = Aliases.apply(q.want);
                List<Matcher.Scored> ranked = Matcher.rank(q.pool, want, q.ep);
                if (!ranked.isEmpty()) score = ranked.get(0).s;
                Matcher.Result r = used
                        ? Matcher.bestWithBrain(q.pool, want, q.ep, brain)
                        : Matcher.best(q.pool, want, q.ep);
                if (r != null) got = r.id;
            } catch (Exception e) {
                // Keep the exam pure-JVM: no Android logging here.
                System.err.println("exam: question failed: " + q.label + " (" + e + ")");
            }
            out.add(new Item(q, got, score));
        }
        return new Report(out, used, System.currentTimeMillis() - t0);
    }

    /**
     * The title of the candidate with {@code id} in a question's pool, or null.
     * Used by the {@link Bootcamp} to turn a miss into a lesson.
     */
    public static String titleOf(Q q, int id) {
        for (int i = 0; i < q.pool.length(); i++) {
            JSONObject o = q.pool.optJSONObject(i);
            if (o == null) continue;
            JSONObject n = o.optJSONObject("node");
            if (n != null && n.optInt("id", -1) == id) {
                return n.optString("title", null);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ bank

    private static JSONObject node(int id, String title, int eps, String media, String status,
                                   String... synonyms) {
        try {
            JSONObject n = new JSONObject();
            n.put("id", id);
            n.put("title", title);
            n.put("num_episodes", eps);
            n.put("media_type", media);
            n.put("status", status);
            if (synonyms != null && synonyms.length > 0) {
                JSONObject alt = new JSONObject();
                JSONArray syn = new JSONArray();
                for (String s : synonyms) syn.put(s);
                alt.put("synonyms", syn);
                n.put("alternative_titles", alt);
            }
            JSONObject o = new JSONObject();
            o.put("node", n);
            return o;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JSONArray pool(JSONObject... nodes) {
        JSONArray a = new JSONArray();
        for (JSONObject n : nodes) a.put(n);
        return a;
    }

    /** A pool with just one entry (common case). */
    private static JSONArray one(JSONObject n) {
        return pool(n);
    }

    private static final JSONObject FRIEREN =
            node(52991, "Sousou no Frieren", 28, "tv", "finished_airing", "\u846c\u9001\u306e\u30d5\u30ea\u30fc\u30ec\u30f3");
    private static final JSONObject FRIEREN2 =
            node(59978, "Sousou no Frieren 2nd Season", 10, "tv", "finished_airing");
    private static final JSONObject FRIEREN_OVA =
            node(63816, "Sousou no Frieren: Ougonkyou-hen", 0, "tv", "not_yet_aired");

    /** The fixed question bank. */
    public static List<Q> bank() {
        List<Q> q = new ArrayList<>();

        // 1. exact name
        q.add(new Q("Sousou no Frieren - 12",
                "Sousou no Frieren", 12, 52991, false, one(FRIEREN)));
        // 2. release tags / trailing noise
        q.add(new Q("[SubsPlease] Attack on Titan END FHD 1080p",
                "Attack on Titan END FHD", 5, 16498, false,
                one(node(16498, "Shingeki no Kyojin", 25, "tv", "finished_airing", "Attack on Titan"))));
        // 3. word-order swap
        q.add(new Q("Frieren Sousou no - 03",
                "Frieren Sousou no", 3, 52991, false, one(FRIEREN)));
        // 4. typo
        q.add(new Q("Gintma EP 48",
                "Gintma", 48, 918, false,
                pool(node(918, "Gintama", 201, "tv", "finished_airing"),
                     node(9969, "Gintama'", 51, "tv", "finished_airing"))));
        // 5. exact multi-candidate disambiguation
        q.add(new Q("Gintama 48",
                "Gintama", 48, 918, false,
                pool(node(918, "Gintama", 201, "tv", "finished_airing"),
                     node(9969, "Gintama'", 51, "tv", "finished_airing"),
                     node(28977, "Gintama\u00b0", 51, "tv", "finished_airing"))));
        // 6. kana -> romaji via ja alternative title
        q.add(new Q("\u30d5\u30ea\u30fc\u30ec\u30f3 05",
                "\u30d5\u30ea\u30fc\u30ec\u30f3", 5, 52991, false, one(FRIEREN)));
        // 7. Arabic spelling variant (taa marbuta folded to haa)
        q.add(new Q("\u0647\u062c\u0648\u0645 \u0627\u0644\u0639\u0645\u0627\u0644\u0642\u0647 01",
                "\u0647\u062c\u0648\u0645 \u0627\u0644\u0639\u0645\u0627\u0644\u0642\u0647", 1, 16498, false,
                one(node(16498, "Shingeki no Kyojin", 25, "tv", "finished_airing",
                        "\u0647\u062c\u0648\u0645 \u0627\u0644\u0639\u0645\u0627\u0644\u0642\u0629"))));
        // 8. episode hint rejects a too-short season
        q.add(new Q("Some Show EP 48",
                "Some Show", 48, 2, false,
                pool(node(1, "Some Show", 12, "tv", "finished_airing"),
                     node(2, "Some Show", 100, "tv", "finished_airing"))));
        // 9. sequel marker unnamed by the file is rejected
        q.add(new Q("Base Show EP 10",
                "Base Show EP 10", 10, 1, false,
                pool(node(1, "Base Show", 24, "tv", "finished_airing"),
                     node(2, "Base Show Movie", 0, "movie", "finished_airing"))));
        // 10. token overlap with extra release words
        q.add(new Q("Seishun Buta Yarou wa Santa Claus no Yume wo Minai END",
                "Seishun Buta Yarou wa Santa Claus no Yume wo Minai END FHD", 13, 37450, false,
                one(node(37450, "Seishun Buta Yarou wa Santa Claus no Yume wo Minai", 13, "tv", "finished_airing"))));
        // 11. must abstain: shares words with the candidate but is not it
        q.add(new Q("Koi wo Shitai 12",
                "Koi wo Shitai", 12, 0, false,
                one(node(14741, "Chuunibyou demo Koi ga Shitai!", 12, "tv", "finished_airing"))));
        // 12. must abstain: unrelated
        q.add(new Q("Random Home Video 05",
                "Random Home Video", 5, 0, false,
                pool(node(21, "One Piece", 1000, "tv", "currently_airing"),
                     node(20, "Naruto", 220, "tv", "finished_airing"))));

        // 13-15: shorthand and typo cases. The study pass plus the built-in
        // alias table resolve "Gin S1" -> "Gintama"; the typo and kana
        // channels resolve the other two. None should need the LLM.
        q.add(new Q("Gin S1 - 48",
                "Gin S1", 48, 918, false,
                pool(node(918, "Gintama", 201, "tv", "finished_airing"),
                     node(9969, "Gintama'", 51, "tv", "finished_airing"))));
        q.add(new Q("Friren 28",
                "Friren", 28, 52991, false,
                pool(FRIEREN, FRIEREN2, FRIEREN_OVA)));
        q.add(new Q("Shingeki no Kyoujin 10",
                "Shingeki no Kyoujin", 10, 16498, false,
                pool(node(16498, "Shingeki no Kyojin", 25, "tv", "finished_airing"),
                     node(36999, "Shingeki no Kyojin Season 2", 12, "tv", "finished_airing"))));

        return Collections.unmodifiableList(q);
    }
}
