package com.bridge.mx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The full player-vs-list / progress decision, exercised without a device. */
public class PlayerScrapeTest {

    private static List<PlayerScrape.Node> nodes(PlayerScrape.Node... n) {
        List<PlayerScrape.Node> l = new ArrayList<>();
        for (PlayerScrape.Node x : n) l.add(x);
        return l;
    }

    @Test
    public void emptyScreenIsNothing() {
        PlayerScrape.Result r = PlayerScrape.from(nodes());
        assertFalse(r.isPlayer);
        assertFalse(r.isList);
        assertEquals(-1, r.percent);
    }

    @Test
    public void seekbarGivesExactPercent() {
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.of("VideoTitle", "Show S01E03.mp4", "android.widget.TextView"),
                PlayerScrape.Node.range("VideoSeekbar", "android.widget.SeekBar", 5000, 10000)));
        assertTrue(r.isPlayer);
        assertFalse(r.isList);
        assertEquals(50, r.percent);
        assertEquals(5000, r.posMs);
        assertEquals(10000, r.durMs);
        assertEquals("Show S01E03.mp4", r.media);
    }

    @Test
    public void timeLabelsGivePercent() {
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.of("VideoTimeCurrent", "00:05", "android.widget.TextView"),
                PlayerScrape.Node.of("VideoTimeTotal", "00:11", "android.widget.TextView")));
        assertTrue(r.isPlayer);
        assertEquals(45, r.percent);
    }

    @Test
    public void seekbarWinsOverLabels() {
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.of("VideoTimeCurrent", "00:01", "android.widget.TextView"),
                PlayerScrape.Node.of("VideoTimeTotal", "00:11", "android.widget.TextView"),
                PlayerScrape.Node.range("VideoSeekbar", "android.widget.SeekBar", 9000, 10000)));
        assertEquals(90, r.percent);
    }

    @Test
    public void titleOnlyStillIsPlayer() {
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.of("VideoTitle", "Show EP03.mkv", "android.widget.TextView")));
        assertTrue(r.isPlayer);
        assertEquals(-1, r.percent);
        assertEquals("Show EP03.mkv", r.media);
    }

    @Test
    public void playerMarkerWithoutProgressIsPlayer() {
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.of("VideoTitleContainer", null, "android.widget.FrameLayout")));
        assertTrue(r.isPlayer);
        assertEquals(-1, r.percent);
        assertNull(r.media);
    }

    @Test
    public void endOfVideoIsHundred() {
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.range("VideoSeekbar", "android.widget.SeekBar", 10000, 10000)));
        assertEquals(100, r.percent);
        assertTrue(PlaybackTracker.isFinished(r.percent));
    }

    @Test
    public void fileListIsNotPlayer() {
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.of("title", "Show S01E01.mp4", "android.widget.TextView"),
                PlayerScrape.Node.of("new_title", "Show S01E01.mp4", "android.widget.TextView"),
                PlayerScrape.Node.of("tv_last_played", "Yesterday", "android.widget.TextView"),
                PlayerScrape.Node.of("tv_status", "51:23", "android.widget.TextView")));
        assertTrue(r.isList);
        assertFalse(r.isPlayer);
        assertEquals(-1, r.percent);
    }

    @Test
    public void genericSeekbarWorks() {
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.range(null, "android.widget.SeekBar", 0, 200)));
        assertTrue(r.isPlayer);
        assertEquals(0, r.percent);
    }

    @Test
    public void zeroDurationIsNoPercent() {
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.range("VideoSeekbar", "android.widget.SeekBar", 5000, 0)));
        assertEquals(-1, r.percent);
        assertTrue(r.isPlayer);
    }

    @Test
    public void listMarkerBeatsStrayRangeOnList() {
        // A list row that happens to expose a range must not be read as the player.
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.of("list_item", null, "android.widget.LinearLayout"),
                PlayerScrape.Node.of("title", "Show S01E02.mp4", "android.widget.TextView"),
                PlayerScrape.Node.range("progress", null, 30, 100)));
        assertTrue(r.isList);
        assertFalse(r.isPlayer);
    }

    @Test
    public void exoLabelsGivePercent() {
        // Immersive player: ExoPlayer's own ids.
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.of("exo_position", "00:05", "android.widget.TextView"),
                PlayerScrape.Node.of("exo_duration", "00:11", "android.widget.TextView"),
                PlayerScrape.Node.range("exo_progress", "android.widget.SeekBar", 0, 0)));
        assertTrue(r.isPlayer);
        assertEquals(45, r.percent);
    }

    @Test
    public void exoProgressRangeWins() {
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.of("exo_position", "00:01", "android.widget.TextView"),
                PlayerScrape.Node.of("exo_duration", "00:11", "android.widget.TextView"),
                PlayerScrape.Node.range("exo_progress", "android.widget.SeekBar", 8000, 10000)));
        assertEquals(80, r.percent);
    }

    @Test
    public void timeBarTextStandsInForCurrent() {
        // DefaultTimeBar exposes a SeekBar class + its own content description,
        // but no RangeInfo; the total comes from exo_duration.
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.of("exo_progress", "00:05", "android.widget.SeekBar"),
                PlayerScrape.Node.of("exo_duration", "00:10", "android.widget.TextView")));
        assertTrue(r.isPlayer);
        assertEquals(50, r.percent);
    }

    @Test
    public void genericTimeBarWithRangeIsRead() {
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.range("exo_progress",
                        "com.google.android.exoplayer2.ui.DefaultTimeBar", 7000, 10000)));
        assertTrue(r.isPlayer);
        assertEquals(70, r.percent);
    }

    @Test
    public void realMxControlsShownReadsPosDurAndTitle() {
        // Exact live tree: posText/durationText/progressBar + a toolbar TextView
        // whose text is the file name (no resource id).
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.of("statusText", "64%   6:40 AM", "android.widget.TextView"),
                PlayerScrape.Node.of(null,
                        "\u0627\u0644\u062d\u0644\u0642\u0629 2 Marronnier Oukoku no Shichinin no Kishi.mp4",
                        "android.widget.TextView"),
                PlayerScrape.Node.of("posText", "23:10", "android.widget.TextView"),
                PlayerScrape.Node.of("durationText", "26:18", "android.widget.TextView"),
                PlayerScrape.Node.of("progressBar", null, "android.widget.ProgressBar"),
                PlayerScrape.Node.of("playpause", null, "android.widget.ImageView")));
        assertTrue(r.isPlayer);
        assertFalse(r.isList);
        assertEquals(88, r.percent); // 1390s / 1578s
        assertEquals("\u0627\u0644\u062d\u0644\u0642\u0629 2 Marronnier Oukoku no Shichinin no Kishi.mp4",
                r.media);
    }

    @Test
    public void realMxControlsHiddenReadsElapsedPair() {
        // Exact live tree with controls hidden: only elapsed_time + surface.
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.of("statusText", "64%   6:39 AM", "android.widget.TextView"),
                PlayerScrape.Node.of("elapsed_time", "22:38 / 26:18", "android.widget.TextView"),
                PlayerScrape.Node.of("double_tap_seek_container", null, "android.widget.FrameLayout"),
                PlayerScrape.Node.of("tap_middle_view", null, "android.view.View"),
                PlayerScrape.Node.of("surfaceView", null, "android.view.View")));
        assertTrue(r.isPlayer);
        assertEquals(86, r.percent); // 1358s / 1578s
        assertNull(r.media); // title is hidden with the controls
    }

    @Test
    public void titleFromFilenameWithoutId() {
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.of(null, "Show S01E03.mkv", "android.widget.TextView")));
        assertTrue(r.isPlayer);
        assertEquals("Show S01E03.mkv", r.media);
        assertEquals(-1, r.percent);
    }

    @Test
    public void progressBarRangeIsRead() {
        PlayerScrape.Result r = PlayerScrape.from(nodes(
                PlayerScrape.Node.range("progressBar", "android.widget.ProgressBar", 9000, 10000)));
        assertTrue(r.isPlayer);
        assertEquals(90, r.percent);
    }
}
