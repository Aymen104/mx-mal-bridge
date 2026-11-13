package com.bridge.mx;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

/**
 * A series' MAL match belongs to every one of its episode rows (2026-11-13).
 *
 * <p>Live failure: 22 "Gin S1" rows (eps 51-75) sat at matchId=-1 while ep76 was
 * matched to 918. The pipeline groups by <em>parsed title</em> and skips a title
 * it has already searched, so those episodes could never be searched again and
 * their watch state never reached MAL - everything watched before ep76 was
 * invisible.
 */
public class SeriesMatchTest {

    @Before
    public void reset() {
        Store.clear();
        Store.setMalCur(null);
    }

    private static Store.Row ep(String parsedTitle, int ep, int matchId) {
        Store.Row r = new Store.Row();
        r.key = "ep:" + parsedTitle + "|" + ep;
        r.title = parsedTitle + " EP " + ep;
        r.parsedTitle = parsedTitle;
        r.ep = ep;
        r.state = "present";
        r.matchId = matchId;
        Store.upsert(r);
        return r;
    }

    @Test
    public void aNewEpisodeRowInheritsItsSeriesMatch() {
        ep("Gin S1", 76, 918);
        Store.Row fresh = ep("Gin S1", 51, -1);
        assertEquals("a new episode of a matched series inherits the match",
                918, fresh.matchId);
        Store.Row newer = ep("Gin S1", 60, -1);
        assertEquals(918, newer.matchId);
    }

    @Test
    public void linkingAlsoRepairsRowsAlreadyStored() {
        ep("Gin S1", 76, 918);
        ep("Gin S1", 51, -1);
        ep("Gin S1", 60, -1);
        // simulate a reload: drop the in-memory inheritance, then link again
        Store.Row r = Store.rowByKey("ep:Gin S1|51");
        r.matchId = -1;
        int linked = Store.linkSeriesMatches();
        assertEquals(1, linked);
        assertEquals(918, r.matchId);
    }

    @Test
    public void aDifferentSeriesIsNeverLinkedAcross() {
        ep("Gin S1", 76, 918);
        Store.Row other = ep("Ao no Hako Season 2", 5, -1);
        assertEquals("no sibling of that series has a match", -1, other.matchId);
    }
}