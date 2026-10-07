package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** MAL search-result scoring: exact, synonym, multi-candidate, threshold. */
public class MatcherTest {

    private static JSONObject node(int id, String title, int numEp, String en)
            throws Exception {
        JSONObject n = new JSONObject();
        n.put("id", id);
        n.put("title", title);
        if (numEp > 0) n.put("num_episodes", numEp);
        JSONObject alt = new JSONObject();
        if (en != null) alt.put("en", en);
        n.put("alternative_titles", alt);
        JSONObject wrap = new JSONObject();
        wrap.put("node", n);
        return wrap;
    }

    private static JSONArray arr(JSONObject... items) {
        JSONArray a = new JSONArray();
        for (JSONObject o : items) a.put(o);
        return a;
    }

    @Test
    public void exactTitle() throws Exception {
        JSONArray data = arr(node(100, "Shingeki no Kyojin", 25, "Attack on Titan"));
        Matcher.Result b = Matcher.best(data, "Shingeki no Kyojin");
        assertEquals(100, b.id);
        assertEquals(25, b.numEp);
    }

    @Test
    public void englishSynonym() throws Exception {
        JSONArray data = arr(node(100, "Shingeki no Kyojin", 25, "Attack on Titan"));
        Matcher.Result b = Matcher.best(data, "Attack on Titan");
        assertEquals(100, b.id);
        assertEquals(25, b.numEp);
    }

    @Test
    public void picksBestOfMany() throws Exception {
        JSONArray data = arr(
                node(1, "Naruto", 220, null),
                node(2, "Naruto Shippuuden", 500, null));
        Matcher.Result b = Matcher.best(data, "Naruto Shippuuden");
        assertEquals(2, b.id);
    }

    @Test
    public void thresholdRejectsUnrelated() throws Exception {
        JSONArray data = arr(node(9, "Berserk", 25, null));
        assertNull(Matcher.best(data, "zzzz qqqq unrelated"));
    }

    @Test
    public void arabicTitleMatch() throws Exception {
        JSONArray data = arr(node(55, "\u0627\u0644\u0645\u062d\u0642\u0642 \u0643\u0648\u0646\u0627\u0646", 1100, null));
        Matcher.Result b = Matcher.best(data, "\u0627\u0644\u0645\u062d\u0642\u0642 \u0643\u0648\u0646\u0627\u0646");
        assertEquals(55, b.id);
    }

    @Test
    public void nullDataSafe() throws Exception {
        assertNull(Matcher.best(null, "anything"));
    }

    @Test
    public void emptyQuerySafe() throws Exception {
        JSONArray data = arr(node(1, "Anything", 12, null));
        assertNull(Matcher.best(data, ""));
    }
}
