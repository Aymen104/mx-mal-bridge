package com.bridge.mx;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure interpretation of a flattened MX Player screen: given every node's view
 * id / text / class / range, decide whether this is the player (vs. the file
 * list), read the playback position, and compute the watched percent.
 *
 * <p>Deliberately Android-free so the whole decision is unit-testable without a
 * device. {@code MxService} only flattens the live accessibility tree into
 * {@link Node}s and calls {@link #from}.
 *
 * <p>MX's real player ids were captured from the live tree (Android 15,
 * MX v3.3.0):
 * <pre>
 *   controls shown :  posText  durationText  progressBar   + a toolbar TextView
 *                     whose text is the file name (no resource id)
 *   controls hidden:  elapsed_time  =  "22:38 / 26:18"     (no title)
 * </pre>
 * Older/other skins are also handled: the classic {@code VideoSeekbar} /
 * {@code VideoTimeCurrent} / {@code VideoTimeTotal} / {@code VideoTitle} ids and
 * the immersive ExoPlayer ids ({@code exo_progress} / {@code exo_position} /
 * {@code exo_duration}).
 */
public final class PlayerScrape {

    private static final Pattern MEDIA = Pattern.compile(
            ".*\\.(mp4|mkv|avi|webm|m4v|mov|ts|flv|wmv|3gp|mpg|mpeg|m2ts|rmvb|ogv)$",
            Pattern.CASE_INSENSITIVE);

    private PlayerScrape() {
    }

    /** One flattened view node. */
    public static final class Node {
        public final String id;    // view id suffix, e.g. "VideoSeekbar" (or null)
        public final String text;  // getText() or contentDescription
        public final String cls;   // class name (a11y class where overridden)
        public final boolean hasRange;
        public final double rangeCur;
        public final double rangeMax;

        public Node(String id, String text, String cls,
                    boolean hasRange, double rangeCur, double rangeMax) {
            this.id = id;
            this.text = text;
            this.cls = cls;
            this.hasRange = hasRange;
            this.rangeCur = rangeCur;
            this.rangeMax = rangeMax;
        }

        public static Node of(String id, String text, String cls) {
            return new Node(id, text, cls, false, 0, 0);
        }

        public static Node range(String id, String cls, double cur, double max) {
            return new Node(id, null, cls, true, cur, max);
        }
    }

    /** Verdict for one screen snapshot. */
    public static final class Result {
        public boolean isPlayer;
        public boolean isList;
        public long posMs = -1;
        public long durMs = -1;
        public int percent = -1;
        public String media;
    }

    // ---- id classification (tolerant to MX's several player skins) ----

    private static boolean isCurrentId(String id) {
        String l = id.toLowerCase(Locale.ROOT);
        return l.equals("postext") || l.equals("videotimecurrent")
                || l.equals("exo_position") || l.equals("position")
                || l.equals("currenttime") || l.endsWith("_position")
                || l.endsWith("timecurrent") || l.endsWith("currenttime");
    }

    private static boolean isTotalId(String id) {
        String l = id.toLowerCase(Locale.ROOT);
        return l.equals("durationtext") || l.equals("videotimetotal")
                || l.equals("exo_duration") || l.equals("duration")
                || l.equals("totaltime") || l.endsWith("_duration")
                || l.endsWith("timetotal") || l.endsWith("durationtext");
    }

    private static boolean isSeekId(String id) {
        String l = id.toLowerCase(Locale.ROOT);
        return l.equals("progressbar") || l.equals("videoseekbar")
                || l.equals("exo_progress") || l.equals("progress")
                || l.contains("seekbar") || l.contains("seek_bar")
                || l.endsWith("progress");
    }

    private static boolean isTitleId(String id) {
        String l = id.toLowerCase(Locale.ROOT);
        return l.equals("videotitle") || l.equals("exo_title")
                || l.equals("player_title");
    }

    private static boolean isCombinedId(String id) {
        return "elapsed_time".equals(id);
    }

    private static boolean isListId(String id) {
        String l = id.toLowerCase(Locale.ROOT);
        return l.equals("list_item") || l.equals("new_title")
                || l.equals("tv_last_played") || l.equals("tv_status")
                || l.equals("tv_duration");
    }

    /** Ids that only ever appear on MX's player screen. */
    private static boolean isPlayerMarkerId(String id) {
        String l = id.toLowerCase(Locale.ROOT);
        return l.startsWith("video") || l.startsWith("containerseek")
                || l.startsWith("containertime") || l.startsWith("exo_")
                || l.equals("elapsed_time") || l.equals("postext")
                || l.equals("durationtext") || l.equals("progressbar")
                || l.equals("playpause") || l.equals("tap_middle_view")
                || l.equals("double_tap_seek_container") || l.equals("surfaceview")
                || l.equals("subnavibar");
    }

    private static boolean isSeekClass(String cls) {
        if (cls == null) return false;
        return cls.contains("SeekBar") || cls.contains("TimeBar");
    }

    private static boolean looksLikeMedia(String text) {
        return text != null && MEDIA.matcher(text.trim()).matches();
    }

    public static Result from(List<Node> nodes) {
        Result r = new Result();
        if (nodes == null || nodes.isEmpty()) return r;

        Node cur = null, tot = null, seek = null, title = null, generic = null;
        Node combined = null;
        boolean marker = false, list = false;

        for (Node n : nodes) {
            String id = n.id == null ? "" : n.id;
            if (isListId(id)) list = true;
            if (isPlayerMarkerId(id)) marker = true;
            if (isCombinedId(id) && combined == null) combined = n;
            if (isCurrentId(id)) cur = n;
            else if (isTotalId(id)) tot = n;
            else if (isSeekId(id) && seek == null) seek = n;
            if (title == null && isTitleId(id)) title = n;
            if (generic == null && n.hasRange && n.rangeMax > 0 && isSeekClass(n.cls)) {
                generic = n;
            }
        }

        // Title with no resource id: MX's toolbar shows the file name. Prefer an
        // explicit title id, then any text that looks like a media file.
        if (title == null) {
            for (Node n : nodes) {
                if (n.text != null && looksLikeMedia(n.text)) {
                    title = n;
                    break;
                }
            }
        }

        // 1. Seek bar gives position/duration directly (same unit; MX = ms).
        if (seek != null && seek.hasRange && seek.rangeMax > 0) {
            r.posMs = (long) seek.rangeCur;
            r.durMs = (long) seek.rangeMax;
        } else if (generic != null) {
            r.posMs = (long) generic.rangeCur;
            r.durMs = (long) generic.rangeMax;
        }

        // 2. Clock labels: posText/durationText, exo_position/exo_duration, or
        //    VideoTimeCurrent/VideoTimeTotal. The seek bar's own accessible text
        //    may stand in for the current label.
        if (r.posMs < 0) {
            String curT = cur != null ? cur.text : null;
            if (curT == null && seek != null) curT = seek.text;
            String totT = tot != null ? tot.text : null;
            int c = PlaybackTracker.parseTime(curT);
            int t = PlaybackTracker.parseTime(totT);
            if (t > 0 && c >= 0) {
                r.posMs = c * 1000L;
                r.durMs = t * 1000L;
            }
        }

        // 3. Combined "cur / total" label (elapsed_time) when controls are hidden.
        if (r.posMs < 0 && combined != null) {
            int[] pair = PlaybackTracker.parsePair(combined.text);
            if (pair != null) {
                r.posMs = pair[0] * 1000L;
                r.durMs = pair[1] * 1000L;
            }
        }

        if (r.posMs >= 0 && r.durMs > 0) {
            r.percent = PlaybackTracker.percent(r.posMs, r.durMs);
        }
        r.media = (title == null || title.text == null || title.text.isEmpty())
                ? null : title.text.trim();

        r.isList = list;
        r.isPlayer = marker || title != null || (r.posMs >= 0 && r.durMs > 0)
                || combined != null;
        if (list && !marker) r.isPlayer = false; // file list, not the player
        return r;
    }
}
