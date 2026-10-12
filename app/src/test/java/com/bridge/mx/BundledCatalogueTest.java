package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Runs against <b>whatever bulk of titles is bundled</b>.
 *
 * <p>The catalogue asset ({@code assets/anime_titles.txt}) is regenerated from
 * animeTosho every few months (and the app refreshes its own copy on the same
 * cadence). This test is the guard that must accompany every new bulk: it
 * checks the list is well-formed, studies it exactly as the app does, and then
 * proves the very same exam still passes. A bad bulk — duplicates, control
 * characters, an empty file, or a list that degrades the matcher — fails here
 * before it can ever ship.
 */
public class BundledCatalogueTest {

    /** Locate the bundled asset whether tests run from the module or the root. */
    private static Path asset() {
        Path a = Paths.get("src", "main", "assets", "anime_titles.txt");
        if (Files.isRegularFile(a)) return a;
        Path b = Paths.get("app", "src", "main", "assets", "anime_titles.txt");
        if (Files.isRegularFile(b)) return b;
        throw new AssertionError("bundled catalogue not found from " + Paths.get("").toAbsolutePath());
    }

    private static List<String> titles() throws Exception {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(asset(), StandardCharsets.UTF_8)) {
            line = line.trim();
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    @Test
    public void bundledBulkIsWellFormed() throws Exception {
        List<String> t = titles();
        assertTrue("catalogue suspiciously small: " + t.size(), t.size() >= 5000);

        Set<String> seen = new HashSet<>();
        for (String s : t) {
            assertFalse("control char in: " + s, s.matches(".*\\p{Cntrl}.*"));
            assertTrue("over-long title (" + s.length() + "): " + s, s.length() <= 300);
            assertTrue("blank title", !s.isEmpty());
            assertTrue("duplicate title: " + s, seen.add(s.toLowerCase(Locale.ROOT)));
        }
    }

    @Test
    public void studyingTheBundledBulkKeepsTheExamPassing() throws Exception {
        // Study the bulk exactly as production primes its shorthand table...
        Educate.Result study = Educate.studyTitles(titles());
        assertEquals("study must consume the whole bulk", titles().size(), study.titles);
        assertTrue("a real bulk should teach some shorthand", study.aliases > 0);

        // ...then run the app's own test through the same pipeline. The full
        // bulk must not just pass it — it must stay perfect (the learned
        // shorthand table is only additive and must never clobber a question).
        Selftest.Result t = Selftest.run();
        assertEquals("exam bank size", Exam.bank().size(), t.total);
        assertEquals("bulk degraded the pipeline: " + t.line(), t.total, t.correct);
    }

    @Test
    public void parsedLiveListingIsWhatGetsStudied() {
        // The on-device refresh studies exactly what parseListing extracts from
        // animeTosho, so a change to that parser is covered here too.
        List<String> parsed = Catalogue.parseListing(
                "<a href=\"/series/1\"><strong>Alpha no Beta</strong></a>"
                        + "<a href=\"/series/2\"><strong>Gamma &amp; Delta</strong></a>");
        assertEquals(java.util.Arrays.asList("Alpha no Beta", "Gamma & Delta"), parsed);
        Educate.Result r = Educate.studyTitles(parsed);
        assertEquals(2, r.titles);
    }
}
