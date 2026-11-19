package com.bridge.mx;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filename -> (series title, episode number).
 *
 * Handles the patterns seen in the phone's Download folders:
 *   "Arabic Show Name الحلقة 6" / "Name - EP 06" / "Name S01E05" /
 *   "Name - 12 [1080p]" / "06 - Name" / bare trailing numbers,
 * while stripping resolution, codec and fansub-bracket noise.
 */
public class EpisodeParser {

    public static class Parsed {
        public String title = "";
        public int ep = -1;   // 0 = movie / no numbering; -1 = not found
        /** Season taken from SxxEyy / "2_08" / trailing "S2"; -1 when unstated. */
        public int season = -1;
    }

    private static final Pattern EXT =
            Pattern.compile("(?i)\\.(mp4|mkv|avi|webm|mov|flv|wmv|ts|m4v|3gp|mpg|mpeg|ogm|rmvb)$");
    private static final Pattern BRACKETS =
            Pattern.compile("\\[[^\\]]{0,80}\\]");
    private static final Pattern RES_PAREN =
            Pattern.compile("(?i)\\((?=[^)]*(?:480|540|720|1080|1440|2160))[^)]*\\)");
    private static final Pattern RES =
            Pattern.compile("(?i)\\b(240|360|480|540|720|1080|1440|2160)p\\b");
    // The optional trailing channel spec is what turns "AAC2.0"/"AC35.1" into a
    // whole-token match; without it the "2.0" survived and kept two spellings
    // of one series apart (2026-11-13).
    private static final Pattern TAGS =
            Pattern.compile("(?i)\\b(x26[45]|h\\.?26[45]|hevc|avc|10bit|8bit|bdrip|webrip|web-?dl|hdtv|"
                    + "aac|ac3|eac3|dts(-hd)?|flac|opus|truehd|dual\\s*audio|hdr|proper|repack)"
                    + "(\\s*\\d+(\\.\\d+)?)?\\b");
    private static final Pattern SPACES = Pattern.compile("\\s{2,}");
    /** Quality words that survive TAGS but still split one series into two keys. */
    private static final Pattern QUALITY =
            Pattern.compile("(?i)(?<![A-Za-z0-9])(fhd|uhd|fullhd|hd|sd)(?![A-Za-z0-9])");
    /**
     * A season marker at the END of the parsed title ("Gintama S2", "Show Season 3").
     * Recorded in {@link Parsed#season} but deliberately NOT removed from the
     * title: the stored series key (and the watched ledger keyed on it) must stay
     * byte-for-byte stable, and the matcher needs the marker as evidence.
     */
    private static final Pattern SEASON_TAIL =
            Pattern.compile("(?i)(?<![A-Za-z0-9])(?:s|season)\\s*[-_.]?\\s*(\\d{1,2})(?!\\d)\\s*$");

    // Episode patterns, most specific first.
    // Lookbehind excludes letters AND digits (not underscore): filenames use
    // "_EP_12" where \b would fail after '_', and a trailing (?!\d) keeps the
    // full number when followed by '_' (e.g. "S01E02_extra").
    private static final Pattern ARABIC =
            Pattern.compile("الحلقة\\s*[-_.]?\\s*(\\d{1,4})");
    private static final Pattern EPISODE =
            Pattern.compile("(?i)(?<![A-Za-z0-9])episode\\s*[-_.]?\\s*(\\d{1,4})(?!\\d)");
    private static final Pattern EP =
            Pattern.compile("(?i)(?<![A-Za-z0-9])EP\\s*[-_.]?\\s*(\\d{1,4})(?!\\d)");
    private static final Pattern SXXEYY =
            Pattern.compile("(?i)(?<![A-Za-z0-9])S(\\d{1,2})\\s*[-_.]?\\s*E(\\d{1,3})(?!\\d)");
    /**
     * Bare {@code E<nn>} episode marker: {@code "Show E07.1080p..."} — the
     * Bilibili/WEB-DL style that carries only ONE letter. Without this the
     * number falls through to {@link #BARE}, which sets the episode but leaves
     * the release junk in the title, so the row never matches the same series
     * as the Arabic-subbed file and its progress is lost (2026-11-13:
     * "Tensei shitara Ken deshita II E07...VARYG" watched to 100% never reached
     * MAL 53913, which stayed at 6).
     *
     * <p>Placed AFTER {@link #SXXEYY} so "S01E05" keeps its season+episode form.
     */
    private static final Pattern EXX =
            Pattern.compile("(?i)(?<![A-Za-z0-9])E\\s*[-_.]?\\s*(\\d{1,3})(?!\\d)");
    /**
     * Release-group / source tokens that are not part of a series name. They
     * survive {@link #TAGS} (which only strips technical specs) and otherwise
     * split one series into two keys.
     */
    // Lookarounds, not consumed separators: a consuming suffix eats the separator
    // that belongs to the NEXT token and that token then survives (measured with
    // "E07. .BILI. . . -VARYG" -> "E07 VARYG").
    private static final Pattern GROUP =
            Pattern.compile("(?i)(?:^|(?<=[\\s._\\-]))(bilibili|bili|varyg|subsplease|subs\\s*please|"
                    + "erai-?raws|horriblesubs|judas|commie|golumpus|ffa|sally\\s*subs|animenp|"
                    + "psh|rmx|rmteam|tortuga|dae|ak|sms|nf|amzn|dsnp|hulu|cr|funimation|hidive|"
                    + "adn|jpn|msubs?(?:[-_.]?toonshub)?|toonshub|baha|abema|iqiyi|muse|anione|aniplus)"
                    + "(?=[\\s._\\-]|$)");
    // Season_Episode without S/E letters: "Show 2_08", "Show 01_03"
    private static final Pattern SEASON_EP =
            Pattern.compile("(?<![A-Za-z0-9])(\\d{1,2})\\s*[_-]\\s*(\\d{1,3})(?!\\d)");
    private static final Pattern TRAIL =
            Pattern.compile("[-\u2013]\\s*(\\d{1,3})\\s*$");
    /** " - 12 END" / " - 12 FIN" / " - 12 FINAL" (release final-episode markers). */
    private static final Pattern TRAIL_END =
            Pattern.compile("(?i)[-\u2013]\\s*(\\d{1,3})\\s*(?:END|FIN|FINAL)\\s*$");
    private static final Pattern LEAD =
            Pattern.compile("^\\s*(\\d{1,3})\\s*[-\u2013.]\\s+");
    private static final Pattern BARE =
            Pattern.compile("(?<!\\d)(\\d{1,3})(?!\\d)");

    private static final Pattern NOT_EP =
            Pattern.compile("^(0|240|360|480|540|720|1080|1440|2160)$");

    /** Leading/trailing separator runs left after the episode marker is cut. */
    private static final Pattern LEAD_TRIM = Pattern.compile("^[\\s\\-\\u2013.]+");
    private static final Pattern TAIL_TRIM = Pattern.compile("[\\s\\-\\u2013.]+$");

    public static Parsed parse(String raw) {
        Parsed p = new Parsed();
        if (raw == null) return p;
        String s = raw.trim();
        s = EXT.matcher(s).replaceAll("");
        s = BRACKETS.matcher(s).replaceAll(" ");
        s = RES_PAREN.matcher(s).replaceAll(" ");
        s = RES.matcher(s).replaceAll(" ");
        s = TAGS.matcher(s).replaceAll(" ");
        s = QUALITY.matcher(s).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ").trim();
        // Normalise dot separators BEFORE group stripping and episode detection.
        // Release names such as "Reincarnated.as.a.Sword.S02E08.REPACK.ADN..." use
        // dots as spaces; leaving them in place hid the source tags from GROUP
        // and left "Reincarnated.as.a.Sword. . . .ADN..." as the series key, so
        // the same show had different keys per release group. TAGS/RES have
        // already consumed dotted technical specs (AAC2.0, H.264) by this point.
        s = s.replace('.', ' ');
        s = SPACES.matcher(s).replaceAll(" ").trim();
        // Release-group / source tokens: strip them BEFORE episode detection so
        // they cannot be read as a bare episode number.
        s = GROUP.matcher(s).replaceAll(" ");

        int ep = -1;

        Matcher m = ARABIC.matcher(s);
        if (m.find() && ok(m.group(1))) {
            ep = Integer.parseInt(m.group(1));
            s = s.replace(m.group(), " ");
        } else if ((m = EPISODE.matcher(s)).find() && ok(m.group(1))) {
            ep = Integer.parseInt(m.group(1));
            s = s.replace(m.group(), " ");
        } else if ((m = EP.matcher(s)).find() && ok(m.group(1))) {
            ep = Integer.parseInt(m.group(1));
            s = s.replace(m.group(), " ");
        } else if ((m = SXXEYY.matcher(s)).find() && ok(m.group(2))) {
            p.season = clampSeason(m.group(1));
            ep = Integer.parseInt(m.group(2));
            s = s.replace(m.group(), " ");
        } else if ((m = EXX.matcher(s)).find() && ok(m.group(1))) {
            // "Show E07..." - one letter, not "EP". Must precede the generic
            // arms, otherwise BARE eats the number and leaves the junk behind.
            ep = Integer.parseInt(m.group(1));
            s = s.replace(m.group(), " ");
        } else if ((m = TRAIL_END.matcher(s)).find() && ok(m.group(1))) {
            ep = Integer.parseInt(m.group(1));
            s = s.replace(m.group(), " ");
        } else if ((m = SEASON_EP.matcher(s)).find() && ok(m.group(2))) {
            p.season = clampSeason(m.group(1));
            ep = Integer.parseInt(m.group(2));
            s = s.replace(m.group(), " ");
        } else if ((m = TRAIL.matcher(s)).find() && ok(m.group(1))) {
            ep = Integer.parseInt(m.group(1));
            s = s.replace(m.group(), " ");
        } else if ((m = LEAD.matcher(s)).find() && ok(m.group(1))) {
            ep = Integer.parseInt(m.group(1));
            s = s.replace(m.group(), " ");
        } else if ((m = BARE.matcher(s)).find() && ok(m.group(1))) {
            ep = Integer.parseInt(m.group(1));
            s = s.replace(m.group(), " ");
        }

        s = s.replace('_', ' ');
        s = SPACES.matcher(s).replaceAll(" ");
        s = LEAD_TRIM.matcher(s).replaceAll("");
        s = TAIL_TRIM.matcher(s).replaceAll("");
        s = s.trim();

        if (p.season < 0) {
            Matcher t = SEASON_TAIL.matcher(s);
            if (t.find()) p.season = clampSeason(t.group(1));
        }

        p.title = s.isEmpty() ? raw.trim() : s;
        p.ep = ep;
        return p;
    }

    /** Accept 0..300 but reject resolution numbers (treated as "no episode"). */
    private static boolean ok(String digits) {
        if (NOT_EP.matcher(digits).matches()) return false;
        return Integer.parseInt(digits) <= 300;
    }

    /** Seasons 1..12; anything else is noise, not a season marker. */
    private static int clampSeason(String digits) {
        int n;
        try { n = Integer.parseInt(digits); } catch (NumberFormatException e) { return -1; }
        return (n >= 1 && n <= 12) ? n : -1;
    }
}
