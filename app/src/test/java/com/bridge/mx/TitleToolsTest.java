package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Comprehension channels and their gates (pure JVM). */
public class TitleToolsTest {

    @Test
    public void nfkcFoldsFullWidth() {
        assertEquals("air", TitleTools.normLoose("\uff21\uff29\uff32"));
    }

    @Test
    public void katakanaFoldsToHiragana() {
        assertEquals("\u3075\u308a\u30fc\u308c\u3093",
                TitleTools.katakanaToHiragana("\u30d5\u30ea\u30fc\u30ec\u30f3"));
    }

    @Test
    public void kanaToHepburnRomaji() {
        assertEquals("furiren", TitleTools.romajiKey("\u3075\u308a\u30fc\u308c\u3093"));
    }

    @Test
    public void halfWidthKatakanaAlsoRomaji() {
        assertEquals("furiren", TitleTools.romajiKey("\uff8c\uff98\uff70\uff9a\uff9d"));
    }

    @Test
    public void kanjiDroppedLeavingKanaKey() {
        // 葬送のフリーレン -> kanji dropped, の -> "no", kana -> romaji
        assertEquals("nofuriren", TitleTools.romajiKey("\u846c\u9001\u306e\u30d5\u30ea\u30fc\u30ec\u30f3"));
    }

    @Test
    public void arabicVariantsFoldTogether() {
        assertEquals(TitleTools.normLoose("\u0647\u062c\u0648\u0645 \u0627\u0644\u0639\u0645\u0627\u0644\u0642\u0629"),
                TitleTools.normLoose("\u0647\u062c\u0648\u0645 \u0627\u0644\u0639\u0645\u0627\u0644\u0642\u0647"));
        assertEquals(TitleTools.normLoose("\u0627\u062d\u0645\u062f"),
                TitleTools.normLoose("\u0623\u062d\u0645\u062f"));
    }

    @Test
    public void containmentScoresHigh() {
        assertTrue(TitleTools.sim("baseshow", "baseshowep10") >= 0.90);
    }

    @Test
    public void tokenSortDefeatsWordOrder() {
        assertTrue(TitleTools.tokenSortRatio("frieren sousou no", "sousou no frieren") >= 0.99);
    }

    @Test
    public void jaroWinklerHandlesTypo() {
        assertTrue(TitleTools.jaroWinkler("gintama", "gintma") >= 0.85);
    }

    @Test
    public void bigramDiceHandlesTypo() {
        assertTrue(TitleTools.bigramDice("gintama", "gintma") >= 0.60);
    }

    @Test
    public void sharedWordNoiseStaysBelowAccept() {
        assertTrue(TitleTools.score("Koi wo Shitai", "Chuunibyou demo Koi ga Shitai!") < 0.50);
    }

    @Test
    public void kanaMatchesJapaneseAlternativeTitle() {
        assertTrue(TitleTools.score("\u30d5\u30ea\u30fc\u30ec\u30f3",
                "\u846c\u9001\u306e\u30d5\u30ea\u30fc\u30ec\u30f3") >= 0.90);
    }

    @Test
    public void exactScoresOne() {
        assertEquals(1.0, TitleTools.score("Gintama", "Gintama"), 1e-9);
    }
}
