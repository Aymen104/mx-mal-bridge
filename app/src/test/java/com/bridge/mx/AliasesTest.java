package com.bridge.mx;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Shorthand alias resolution (e.g. Anime-Sanka's "Gin S1" = Gintama). */
public class AliasesTest {

    @Test
    public void mapsConfirmedShorthand() {
        assertEquals("Gintama", Aliases.apply("Gin"));
        assertEquals("Gintama", Aliases.apply("Gin S1"));
        assertEquals("Gintama", Aliases.apply("gin s2"));
    }

    @Test
    public void leavesUnrelatedTitlesAlone() {
        assertEquals("Gin no Saji", Aliases.apply("Gin no Saji"));
        assertEquals("Gintama", Aliases.apply("Gintama"));
        assertEquals("Some Other Show", Aliases.apply("Some Other Show"));
    }

    @Test
    public void handlesNullAndBlank() {
        assertEquals("", Aliases.apply(null));
    }
}
