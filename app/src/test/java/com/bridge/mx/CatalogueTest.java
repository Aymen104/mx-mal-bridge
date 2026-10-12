package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The animeTosho-backed title refresh: listing parsing, entity decoding, the
 * merge that can only ever add titles, and the 2-3 month staleness window.
 */
public class CatalogueTest {

    @Test
    public void parsesListingRowsInOrder() {
        String html = "<div class=\"home_list_entry\">\n"
                + "<a href=\"/series/19942\"><strong>Tensei Goblin Dakedo Shitsumon Aru?</strong></a>\n"
                + "<a href=\"/series/20040\"><strong>Kanojo no Tomodachi</strong></a>\n"
                + "<span>not a row</span>\n"
                + "<a class=\"x\" href=\"/series/5\"><strong>Seikai no Senki II</strong></a>\n"
                + "</div>";
        List<String> got = Catalogue.parseListing(html);
        assertEquals(Arrays.asList(
                "Tensei Goblin Dakedo Shitsumon Aru?",
                "Kanojo no Tomodachi",
                "Seikai no Senki II"), got);
    }

    @Test
    public void decodesEntitiesInTitles() {
        assertEquals("Foo & Bar 'X'", Catalogue.htmlDecode("Foo &amp; Bar &#39;X&#39;"));
        assertEquals("A\u2014B", Catalogue.htmlDecode("A&mdash;B"));
        assertEquals("caf\u00e9", Catalogue.htmlDecode("caf&#233;"));
        assertEquals("keep &unknown; as-is", Catalogue.htmlDecode("keep &unknown; as-is"));
    }

    @Test
    public void decodesEntitiesInListing() {
        List<String> got = Catalogue.parseListing(
                "<a href=\"/series/9\"><strong>Foo &amp; Bar &lt;OAV&gt;</strong></a>");
        assertEquals(Arrays.asList("Foo & Bar <OAV>"), got);
    }

    @Test
    public void mergeAddsOnlyNewTitlesCaseInsensitively() {
        List<String> into = new ArrayList<>(Arrays.asList("Naruto", "Bleach"));
        int added = Catalogue.merge(into, Arrays.asList(
                "naruto",       // case-insensitive duplicate
                "Bleach",       // exact duplicate
                "  One Piece ", // trimmed and added
                "",             // blank, skipped
                null,           // null, skipped
                "Bad\u0000Ctrl" // control chars flattened
        ));
        assertEquals(2, added);
        assertEquals(Arrays.asList("Naruto", "Bleach", "One Piece", "Bad Ctrl"), into);
    }

    @Test
    public void mergeDropsOverlongTitles() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 250; i++) big.append('x');
        List<String> into = new ArrayList<>();
        assertEquals(0, Catalogue.merge(into, Arrays.asList(big.toString())));
    }

    @Test
    public void stalenessFollowsTheTwoToThreeMonthWindow() {
        long day = 24L * 60 * 60 * 1000;
        long now = 1_000_000_000_000L;
        assertTrue("never refreshed => stale", Catalogue.stale(now, 0));
        assertFalse("fresh (< 75d)", Catalogue.stale(now, now - 30 * day));
        assertFalse("still fresh at 74d", Catalogue.stale(now, now - 74 * day));
        assertTrue("due at 75d", Catalogue.stale(now, now - 75 * day));
        assertTrue("overdue at 100d", Catalogue.stale(now, now - 100 * day));
    }

    @Test
    public void ageDaysReportsMinusOneWhenNeverRefreshed() {
        long day = 24L * 60 * 60 * 1000;
        assertEquals(-1, Catalogue.ageDays(1000L, 0));
        assertEquals(10, Catalogue.ageDays(1_000_000_000_000L,
                1_000_000_000_000L - 10 * day));
    }
}
