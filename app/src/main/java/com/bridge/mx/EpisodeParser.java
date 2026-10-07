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
    }

    private static final Pattern EXT =
            Pattern.compile("(?i)\\.(mp4|mkv|avi|webm|mov|flv|wmv|ts|m4v|3gp|mpg|mpeg|ogm|rmvb)$");
    private static final Pattern BRACKETS =
            Pattern.compile("\\[[^\\]]{0,80}\\]");
    private static final Pattern RES_PAREN =
            Pattern.compile("(?i)\\((?=[^)]*(?:480|540|720|1080|1440|2160))[^)]*\\)");
    private static final Pattern RES =
            Pattern.compile("(?i)\\b(240|360|480|540|720|1080|1440|2160)p\\b");
    private static final Pattern TAGS =
            Pattern.compile("(?i)\\b(x26[45]|h\\.?26[45]|hevc|avc|10bit|8bit|bdrip|webrip|web-?dl|hdtv|aac|ac3|eac3|dual\\s*audio|hdr|proper|repack)\\b");
    private static final Pattern SPACES = Pattern.compile("\\s{2,}");

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
    private static final Pattern TRAIL =
            Pattern.compile("[-\u2013]\\s*(\\d{1,3})\\s*$");
    private static final Pattern LEAD =
            Pattern.compile("^\\s*(\\d{1,3})\\s*[-\u2013.]\\s+");
    private static final Pattern BARE =
            Pattern.compile("(?<!\\d)(\\d{1,3})(?!\\d)");

    private static final Pattern NOT_EP =
            Pattern.compile("^(0|240|360|480|540|720|1080|1440|2160)$");

    public static Parsed parse(String raw) {
        Parsed p = new Parsed();
        if (raw == null) return p;
        String s = raw.trim();
        s = EXT.matcher(s).replaceAll("");
        s = BRACKETS.matcher(s).replaceAll(" ");
        s = RES_PAREN.matcher(s).replaceAll(" ");
        s = RES.matcher(s).replaceAll(" ");
        s = TAGS.matcher(s).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ").trim();

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
        } else if ((m = SXXEYY.matcher(s)).find()) {
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
        s = s.replaceAll("^[\\s\\-\u2013.]+", "").replaceAll("[\\s\\-\u2013.]+$", "").trim();

        p.title = s.isEmpty() ? raw.trim() : s;
        p.ep = ep;
        return p;
    }

    /** Accept 0..300 but reject resolution numbers (treated as "no episode"). */
    private static boolean ok(String digits) {
        if (NOT_EP.matcher(digits).matches()) return false;
        return Integer.parseInt(digits) <= 300;
    }
}
