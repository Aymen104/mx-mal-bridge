package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Map;

/** The pre-exam study pass and the shorthand table it feeds. */
public class EducateTest {

    @Test
    public void shorthandIsFirstDistinctiveWord() {
        assertEquals("gintama", Educate.shorthand("Gintama"));
        assertEquals("frieren", Educate.shorthand("Frieren: Beyond Journey's End"));
        // No usable leading word: leading punctuation, or too short.
        assertNull(Educate.shorthand(".hack//G.U."));
        assertNull(Educate.shorthand("Ai"));
        assertNull(Educate.shorthand(""));
    }

    @Test
    public void studyLearnsOnlyUniqueShorthands() {
        Educate.Result r = Educate.studyTitles(Arrays.asList(
                "Gintama", "Gintama'", "Gintama\u00b0",          // all collapse to "gintama"
                "Bleach", "Bleach: Thousand-Year Blood War"));     // both collapse to "bleach"
        Map<String, String> learned = Aliases.learned();
        assertFalse("ambiguous shorthand must not be learned", learned.containsKey("gintama"));
        assertFalse("ambiguous shorthand must not be learned", learned.containsKey("bleach"));
        assertEquals(5, r.titles);
    }

    @Test
    public void studyLearnsUniqueShorthands() {
        Educate.studyTitles(Arrays.asList("Naruto", "Boruto"));
        assertTrue(Aliases.learned().containsKey("naruto"));
        assertTrue(Aliases.learned().containsKey("boruto"));
    }

    @Test
    public void builtinShorthandStillResolves() {
        // The built-in table (independent of the study pass) maps the
        // Anime-Sanka shorthand "Gin S1" onto Gintama, and ignores a season
        // suffix. A title that merely starts with "gin" is never rewritten.
        assertEquals("Gintama", Aliases.apply("Gin S1"));
        assertEquals("Gintama", Aliases.apply("Gin S2"));
        assertEquals("Gin no Saji", Aliases.apply("Gin no Saji"));
    }
}
