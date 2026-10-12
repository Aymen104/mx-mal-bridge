package com.bridge.mx;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure-JVM title comprehension for anime names.
 *
 * <p>Release filenames, MyAnimeList titles and Japanese/Arabic names disagree
 * in ways a single edit-distance cannot bridge: full-width vs half-width
 * characters, Japanese kana vs romaji, accents and Arabic diacritics, word
 * order, release tags, and typos. This class supplies the normalisation and a
 * small ensemble of similarity channels; {@link Matcher} turns them into a
 * match score.
 *
 * <p>It is deliberately Android-free so it can be unit-tested on a plain JVM
 * and reused by the {@link Exam} harness.
 */
public final class TitleTools {

    private TitleTools() {
    }

    // ------------------------------------------------------------------ keys

    /**
     * Legacy key: lower-cased, ASCII alphanumerics plus the base Arabic block,
     * everything else dropped. Kept byte-for-byte compatible with the original
     * {@code Matcher.norm} so existing behaviour is unchanged.
     */
    public static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\u0600-\u06FF]+", "");
    }

    /**
     * Rich key: NFKC (folds full/half-width, ligatures, Roman numerals), then
     * NFD with combining marks stripped (accents, Arabic harakat), katakana
     * folded to hiragana, Arabic letters folded, lower-cased, and reduced to
     * alphanumerics + kana + Arabic.
     */
    public static String normLoose(String s) {
        if (s == null) return "";
        String x = Normalizer.normalize(s, Normalizer.Form.NFKC);
        x = Normalizer.normalize(x, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        x = katakanaToHiragana(x);
        x = arabicFold(x);
        x = x.toLowerCase(Locale.ROOT);
        return x.replaceAll("[^a-z0-9\u0600-\u06FF\u3041-\u3096]+", "");
    }

    /** Full-width katakana (U+30A1..U+30F6) folded onto hiragana. */
    public static String katakanaToHiragana(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u30A1' && c <= '\u30F6') {
                b.append((char) (c - 0x60));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** Fold common Arabic orthographic variants so spelling differences collapse. */
    public static String arabicFold(String s) {
        if (s == null) return "";
        return s.replace('\u0622', '\u0627')   // آ -> ا
                .replace('\u0623', '\u0627')   // أ -> ا
                .replace('\u0625', '\u0627')   // إ -> ا
                .replace('\u0627', '\u0627')
                .replace('\u0629', '\u0647')   // ة -> ه
                .replace('\u0649', '\u064A')   // ى -> ي
                .replace("\u0640", "");        // tatweel
    }

    // ----------------------------------------------------------------- romaji

    private static final Map<Character, String> KANA = new HashMap<>();
    private static final Map<String, String> DIGRAPH = new HashMap<>();

    static {
        String[][] one = {
                {"あ", "a"}, {"い", "i"}, {"う", "u"}, {"え", "e"}, {"お", "o"},
                {"か", "ka"}, {"き", "ki"}, {"く", "ku"}, {"け", "ke"}, {"こ", "ko"},
                {"が", "ga"}, {"ぎ", "gi"}, {"ぐ", "gu"}, {"げ", "ge"}, {"ご", "go"},
                {"さ", "sa"}, {"し", "shi"}, {"す", "su"}, {"せ", "se"}, {"そ", "so"},
                {"ざ", "za"}, {"じ", "ji"}, {"ず", "zu"}, {"ぜ", "ze"}, {"ぞ", "zo"},
                {"た", "ta"}, {"ち", "chi"}, {"つ", "tsu"}, {"て", "te"}, {"と", "to"},
                {"だ", "da"}, {"ぢ", "ji"}, {"づ", "zu"}, {"で", "de"}, {"ど", "do"},
                {"な", "na"}, {"に", "ni"}, {"ぬ", "nu"}, {"ね", "ne"}, {"の", "no"},
                {"は", "ha"}, {"ひ", "hi"}, {"ふ", "fu"}, {"へ", "he"}, {"ほ", "ho"},
                {"ば", "ba"}, {"び", "bi"}, {"ぶ", "bu"}, {"べ", "be"}, {"ぼ", "bo"},
                {"ぱ", "pa"}, {"ぴ", "pi"}, {"ぷ", "pu"}, {"ぺ", "pe"}, {"ぽ", "po"},
                {"ま", "ma"}, {"み", "mi"}, {"む", "mu"}, {"め", "me"}, {"も", "mo"},
                {"や", "ya"}, {"ゆ", "yu"}, {"よ", "yo"},
                {"ら", "ra"}, {"り", "ri"}, {"る", "ru"}, {"れ", "re"}, {"ろ", "ro"},
                {"わ", "wa"}, {"ゐ", "i"}, {"ゑ", "e"}, {"を", "o"}, {"ん", "n"},
                {"ぁ", "a"}, {"ぃ", "i"}, {"ぅ", "u"}, {"ぇ", "e"}, {"ぉ", "o"},
                {"ゃ", "ya"}, {"ゅ", "yu"}, {"ょ", "yo"}, {"ゔ", "vu"},
                {"ー", ""}
        };
        for (String[] p : one) KANA.put(p[0].charAt(0), p[1]);
        String[][] two = {
                {"きゃ", "kya"}, {"きゅ", "kyu"}, {"きょ", "kyo"},
                {"しゃ", "sha"}, {"しゅ", "shu"}, {"しょ", "sho"},
                {"ちゃ", "cha"}, {"ちゅ", "chu"}, {"ちょ", "cho"},
                {"にゃ", "nya"}, {"にゅ", "nyu"}, {"にょ", "nyo"},
                {"ひゃ", "hya"}, {"ひゅ", "hyu"}, {"ひょ", "hyo"},
                {"みゃ", "mya"}, {"みゅ", "myu"}, {"みょ", "myo"},
                {"りゃ", "rya"}, {"りゅ", "ryu"}, {"りょ", "ryo"},
                {"ぎゃ", "gya"}, {"ぎゅ", "gyu"}, {"ぎょ", "gyo"},
                {"じゃ", "ja"}, {"じゅ", "ju"}, {"じょ", "jo"},
                {"びゃ", "bya"}, {"びゅ", "byu"}, {"びょ", "byo"},
                {"ぴゃ", "pya"}, {"ぴゅ", "pyu"}, {"ぴょ", "pyo"}
        };
        for (String[] p : two) DIGRAPH.put(p[0], p[1]);
    }

    /**
     * Best-effort Hepburn romaji for a string that may contain kana. Non-kana
     * characters pass through unchanged, so Latin titles are returned as-is.
     */
    public static String romaji(String s) {
        if (s == null || s.isEmpty()) return "";
        String x = katakanaToHiragana(Normalizer.normalize(s, Normalizer.Form.NFKC));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < x.length(); i++) {
            char c = x.charAt(i);
            // sokuon (small tsu): double the next consonant
            if (c == '\u3063') {
                if (i + 1 < x.length()) {
                    String nx = KANA.get(x.charAt(i + 1));
                    if (i + 1 < x.length() - 1 && DIGRAPH.containsKey(x.substring(i + 1, i + 3))) {
                        nx = DIGRAPH.get(x.substring(i + 1, i + 3));
                    }
                    if (nx != null && !nx.isEmpty() && "aeiou".indexOf(nx.charAt(0)) < 0) {
                        out.append(nx.charAt(0));
                    }
                }
                continue;
            }
            if (i + 1 < x.length()) {
                String dg = DIGRAPH.get(x.substring(i, i + 2));
                if (dg != null) {
                    out.append(dg);
                    i++;
                    continue;
                }
            }
            String r = KANA.get(c);
            if (r != null) {
                out.append(r);
            } else if (c == '\u3093' /* ん */) {
                out.append('n');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Romaji key: NFKC -> kana fold -> romaji -> {@link #norm}. */
    public static String romajiKey(String s) {
        if (s == null) return "";
        String k = arabicFold(katakanaToHiragana(Normalizer.normalize(s, Normalizer.Form.NFKC)));
        return norm(romaji(k));
    }

    // ------------------------------------------------------------ similarity

    /** Edit-distance ratio, upgraded to a containment floor when one key holds the other. */
    public static double sim(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1.0;
        double base = 1.0 - ((double) levenshtein(a, b)) / Math.max(a.length(), b.length());
        double contain = 0;
        if (a.contains(b) || b.contains(a)) {
            int min = Math.min(a.length(), b.length());
            int max = Math.max(a.length(), b.length());
            contain = 0.75 + 0.25 * ((double) min / max);
        }
        return Math.max(base, contain);
    }

    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    /** Jaro similarity over the two full-width/lower-case keys. */
    static double jaro(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int dist = Math.max(0, Math.max(a.length(), b.length()) / 2 - 1);
        boolean[] am = new boolean[a.length()];
        boolean[] bm = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            int lo = Math.max(0, i - dist);
            int hi = Math.min(b.length() - 1, i + dist);
            for (int j = lo; j <= hi; j++) {
                if (bm[j] || a.charAt(i) != b.charAt(j)) continue;
                am[i] = true;
                bm[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) return 0.0;
        int t = 0, k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (!am[i]) continue;
            while (!bm[k]) k++;
            if (a.charAt(i) != b.charAt(k)) t++;
            k++;
        }
        t /= 2;
        double m = matches;
        return (m / a.length() + m / b.length() + (m - t) / m) / 3.0;
    }

    /** Jaro-Winkler (prefix-boosted), length-gated by the caller. */
    public static double jaroWinkler(String a, String b) {
        double j = jaro(a, b);
        int l = 0;
        int max = Math.min(4, Math.min(a.length(), b.length()));
        while (l < max && a.charAt(l) == b.charAt(l)) l++;
        return j + l * 0.1 * (1.0 - j);
    }

    /** Dice coefficient over the sets of word tokens (order independent). */
    public static double tokenDice(String a, String b) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        int inter = 0;
        for (String x : ta) if (tb.contains(x)) inter++;
        return 2.0 * inter / (ta.size() + tb.size());
    }

    /** Ratio between the two token-sorted strings (defeats word-order changes). */
    static double tokenSortRatio(String a, String b) {
        String sa = sortTokens(a);
        String sb = sortTokens(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0;
        return sim(sa, sb);
    }

    /** Dice coefficient over character bigrams (catches typos and partial names). */
    static double bigramDice(String a, String b) {
        String x = normLoose(a);
        String y = normLoose(b);
        if (x.length() < 2 || y.length() < 2) return 0;
        Set<String> A = bigrams(x);
        Set<String> B = bigrams(y);
        int inter = 0;
        for (String s : A) if (B.contains(s)) inter++;
        return 2.0 * inter / (A.size() + B.size());
    }

    private static Set<String> bigrams(String s) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) out.add(s.substring(i, i + 2));
        return out;
    }

    public static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        String x = arabicFold(katakanaToHiragana(
                Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)));
        for (String t : x.split("[^a-z0-9\u0600-\u06FF\u3041-\u3096]+")) {
            if (t.length() >= 2) out.add(t);
        }
        return out;
    }

    private static String sortTokens(String s) {
        List<String> t = new ArrayList<>(tokens(s));
        Collections.sort(t);
        return String.join(" ", t);
    }

    // -------------------------------------------------------------- ensemble

    /**
     * Combined comprehension score in [0,1] for one candidate title against the
     * parsed on-device title.
     *
     * <p>Channels are additive best-of, each gated so that only a genuinely
     * strong signal can lift a candidate above the accept threshold. This is
     * what keeps a shared word ("koi shitai") from turning noise into a match
     * while still letting typos, word-order swaps, kana and Arabic through.
     */
    public static double score(String wantRaw, String candRaw) {
        if (candRaw == null || candRaw.isEmpty()) return 0;
        String w = norm(wantRaw);
        String c = norm(candRaw);
        double s = sim(w, c);

        double tok = tokenDice(wantRaw, candRaw);
        if (tok >= 0.60) s = Math.max(s, tok);

        double ts = tokenSortRatio(wantRaw, candRaw);
        if (ts >= 0.72) s = Math.max(s, ts * 0.98);

        double bg = bigramDice(wantRaw, candRaw);
        if (bg >= 0.55) s = Math.max(s, bg);

        String lw = normLoose(wantRaw);
        String lc = normLoose(candRaw);
        if (!lw.isEmpty() && !lc.isEmpty()) {
            double lr = (double) Math.max(lw.length(), lc.length())
                    / Math.max(1, Math.min(lw.length(), lc.length()));
            if (lr <= 1.5) {
                double jw = jaroWinkler(lw, lc);
                if (jw >= 0.85) s = Math.max(s, jw);
            }
            // kana -> romaji channel: 「葬送のフリーレン」 vs "Sousou no Frieren"
            String rw = romajiKey(wantRaw);
            String rc = romajiKey(candRaw);
            if (!rw.isEmpty() && !rc.isEmpty()) {
                double rs = sim(rw, rc);
                if (rs >= 0.60) s = Math.max(s, rs);
            }
        }
        return s;
    }
}
