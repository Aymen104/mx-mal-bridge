package com.bridge.mx;

import android.content.Context;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The pre-exam "study" pass.
 *
 * <p>Before every exam the bridge educates itself on the bundled anime catalogue
 * (the animeTosho title corpus distilled into {@code assets/anime_titles.txt}).
 * It derives, for each catalogue title, the shorthand a release file would most
 * likely use — the title's first distinctive word, normalised — and learns that
 * shorthand only when it maps to <em>exactly one</em> title. Ambiguous words
 * ("shingeki", "tensei") are deliberately dropped, so the study pass can never
 * teach the matcher a wrong rewrite.
 *
 * <p>The learned shorthands are handed to {@link Aliases} (and persisted by
 * {@link AliasStore}), and the exam is then run through the very same
 * {@link Aliases#apply} + {@link Matcher} pipeline production uses.
 *
 * <p>{@link #studyTitles(List)} is Android-free so the study logic is unit
 * tested; {@link #study(Context)} only adds the asset read and persistence.
 */
public final class Educate {

    /** Bundled catalogue asset (one canonical title per line). */
    public static final String ASSET = "anime_titles.txt";

    private Educate() {
    }

    /** Outcome of one study pass. */
    public static final class Result {
        public final int titles;
        public final int aliases;
        public final String source;

        Result(int titles, int aliases, String source) {
            this.titles = titles;
            this.aliases = aliases;
            this.source = source;
        }

        public boolean empty() {
            return titles <= 0;
        }

        /** One-line summary for the exam dialog. */
        public String line() {
            if (empty()) return "Studied: corpus unavailable (ran anyway)";
            return "Studied " + titles + " anime titles, learned " + aliases + " shorthand(s)";
        }
    }

    /** Pure study core: learn a shorthand only when it names exactly one title. */
    public static Result studyTitles(List<String> titles) {
        Map<String, Integer> count = new HashMap<>();
        Map<String, String> pick = new LinkedHashMap<>(); // preserves catalogue order
        for (String t : titles) {
            if (t == null) continue;
            String key = shorthand(t);
            if (key == null) continue;
            count.merge(key, 1, Integer::sum);
            pick.putIfAbsent(key, t);
        }
        int learned = 0;
        for (Map.Entry<String, String> e : pick.entrySet()) {
            if (count.get(e.getKey()) == 1) {
                Aliases.putLearned(e.getKey(), e.getValue());
                learned++;
            }
        }
        return new Result(titles.size(), learned, "corpus");
    }

    /**
     * The release shorthand for a catalogue title: its first word, reduced to
     * base alphanumerics, when that word is at least four characters. Returns
     * null for titles with no usable leading word.
     */
    static String shorthand(String title) {
        if (title == null) return null;
        String first = title.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\u0600-\u06FF].*$", "");
        String key = TitleTools.norm(first);
        return key.length() >= 4 ? key : null;
    }

    /**
     * Android entry point: load the catalogue (a refreshed animeTosho snapshot
     * when present, else the bundled asset), study it, persist aliases.
     *
     * @see Catalogue for the ~2-3 month refresh cadence
     */
    public static Result study(Context ctx) {
        List<String> titles = Catalogue.titles(ctx);
        if (titles.isEmpty()) {
            Report.warn("educate", "corpus unavailable");
            return new Result(0, 0, "none");
        }
        Result res = studyTitles(titles);
        AliasStore.persist(ctx);
        Report.info("educate", res.line());
        return res;
    }
}
