package com.bridge.mx;

import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enumerates the anime episode videos installed on the device via MediaStore and
 * records them in the Store as state="present" rows (one row per parsed series,
 * carrying the highest episode number found).
 *
 * Why not the MX accessibility list? That only exposes the handful of rows
 * currently on screen, so it can never cover a whole library. MediaStore works
 * without MX Player being open and without root, which is what the ~24 minute
 * background tick needs.
 *
 * Only files whose name carries an explicit episode marker are taken
 * (Arabic "الحلقة N", "EP N", "Episode N", "SxxExx", or the release style
 * "Show - 03"). Music videos, openings, clips, Quran recitations and other
 * noise in the Download folder are ignored so they can never be matched to an
 * anime and pushed to MAL.
 *
 * The scan is scoped to built-in ("primary") storage: the removable SD card is
 * ignored on purpose.
 *
 * <p>It is additionally scoped to the ROOT of the Download tree. Only videos
 * that live directly under {@code /storage/emulated/0/Download} (or the
 * {@code Downloads} spelling) count; a file filed away in a sub-folder such as
 * {@code Download/2- Anime/...} or a per-series batch folder is ignored. That
 * keeps batch-downloaded, not-yet-watched collections from being written to
 * MAL as watched (2026-10-14).
 */
public class LibraryScanner {

    /**
     * Release-style episode marker: a hyphen or underscore acting as the
     * "episode slot" separator, e.g. {@code "Shikkoku no Shaga - 01 [1080p]"},
     * {@code "Youjo Shenki - 12 END"} or {@code "Show - 03.mkv"}. Unlike a bare
     * " - NN", the number must sit in the episode slot: after optional spaces it
     * must be followed by {@code END}, a bracket, the end of the name, a
     * resolution tag, {@code v<digit>} or the file extension. That keeps
     * numbered NON-episodes out (e.g. "… - 8D AUDIO", "… _ 100 Questions",
     * "____1 c sugar"), which a naive " - NN" rule wrongly admitted.
     */
    private static final String RELEASE_EP =
            "(?i)(?<![A-Za-z0-9])[-_]\\s*\\d{1,3}\\s*"
            + "(?=END\\b|\\[|\\(|$|\\d{3,4}p\\b|v\\d|\\.)";

    private static final Pattern HAS_EP = Pattern.compile(
            "الحلقة"
            + "|(?i)(?<![A-Za-z0-9])(episode|ep)\\s*[-_.]?\\s*\\d{1,4}(?!\\d)"
            + "|(?i)(?<![A-Za-z0-9])s\\d{1,2}\\s*[-_.]?\\s*e\\d{1,3}(?!\\d)"
            + "|" + RELEASE_EP);

    /** True when a filename carries an explicit episode marker (see {@link #HAS_EP}). */
    static boolean hasEpisodeMarker(String name) {
        return name != null && HAS_EP.matcher(name).find();
    }

    /**
     * Internal ("primary") video collection only. EXTERNAL_CONTENT_URI also
     * returns the removable SD card; the user asked to ignore that volume (it
     * holds intros/clips, not the watch library). VOLUME_EXTERNAL_PRIMARY
     * (API 29+) restricts the query to built-in storage.
     */
    private static android.net.Uri videoUri() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        }
        return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }

    /** True when we may read the video collection. */
    public static boolean canRead(Context ctx) {
        String perm = android.os.Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_VIDEO"
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        return ctx.checkSelfPermission(perm)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Absolute-lowercase path of a MediaStore row, or null when it cannot be
     * determined. API 29+ exposes {@code RELATIVE_PATH} ("Download/…/" relative
     * to primary storage); older releases expose the absolute {@code DATA}.
     */
    private static String rowPath(Cursor c, int col) {
        if (col < 0) return null;
        String v = c.getString(col);
        if (v == null || v.isEmpty()) return null;
        if (Build.VERSION.SDK_INT >= 29) {
            // RELATIVE_PATH is relative to the volume root and has no leading '/'.
            String rel = v.startsWith("/") ? v : "/" + v;
            return rel.toLowerCase(Locale.ROOT);
        }
        return v.toLowerCase(Locale.ROOT);
    }

    /**
     * True when the row's path is inside a Download tree (or undeterminable on a
     * platform where the column is unavailable). Both the canonical Android
     * "Download" folder and a "Downloads" folder are accepted, so downloads
     * written to either name are tracked.
     */
    private static boolean inDownload(Cursor c, int col) {
        if (col < 0) return true; // platform exposes no path column: cannot filter
        String p = rowPath(c, col);
        if (p == null) return false; // present column but no path: be strict
        if (Build.VERSION.SDK_INT >= 29) {
            // p = "/Download/…" on primary volume.
            return isDownloadRoot(p, "");
        }
        String base = Environment.getExternalStorageDirectory().getAbsolutePath().toLowerCase(Locale.ROOT);
        return isDownloadRoot(p, base);
    }

    /**
     * True when the row sits DIRECTLY in a Download folder — the root of the
     * Download tree, never inside a sub-folder (2026-10-14).
     *
     * <p>The previous whole-tree rule admitted batch downloads kept in
     * per-series sub-folders (e.g. {@code Download/Youjo Shenki 2 [1080p]/…})
     * that were only downloaded, never watched. The planner then read mere file
     * presence as "reached" and wrote the episode counts to MAL, so an
     * unwatched ONA batch appeared at "watching 11". Only files the user drops
     * straight into {@code Download} are the live download set now; anything
     * filed away under a sub-folder is out of scope.
     *
     * <p>On API 29+ the column is {@code RELATIVE_PATH}, the containing folder
     * with a trailing slash ({@code "Download/"} for the root, {@code
     * "Download/sub/"} for a sub-folder). On older releases it is the absolute
     * file path ({@code "…/Download/name.mkv"}).
     */
    static boolean isDownloadRoot(String lowerPath, String baseLower) {
        if (lowerPath == null) return false;
        for (String folder : new String[]{"/download", "/downloads"}) {
            String prefix = baseLower + folder;
            if (lowerPath.equals(prefix)) return true;         // the folder itself
            if (lowerPath.startsWith(prefix + "/")) {
                // Root means nothing but the file name (legacy absolute path) or
                // nothing at all (API 29+ RELATIVE_PATH already ends at the dir).
                return lowerPath.indexOf('/', prefix.length() + 1) < 0;
            }
        }
        return false;
    }

    /**
     * Scan MediaStore and upsert one "present" row per parsed series.
     * Returns the number of series recorded.
     */
    public static int scan(Context ctx) {
        Map<String, String> bestName = new HashMap<>(); // norm key -> filename of highest ep
        Map<String, Integer> bestEp = new HashMap<>();
        Set<String> allKeys = new HashSet<>(); // canonical key of EVERY Download episode file
        Cursor c = null;
        try {
            boolean modern = Build.VERSION.SDK_INT >= 29;
            String[] proj = {
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    modern ? MediaStore.Video.Media.RELATIVE_PATH : MediaStore.Video.Media.DATA
            };
            c = ctx.getContentResolver().query(
                    videoUri(), proj,
                    null, null, MediaStore.Video.Media._ID + " DESC");
            if (c == null) {
                Report.err("libscan", "MediaStore returned null cursor", null);
                return 0;
            }
            int nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int pathCol = modern
                    ? c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                    : c.getColumnIndex(MediaStore.Video.Media.DATA);
            while (c.moveToNext()) {
                if (!inDownload(c, pathCol)) continue;
                String name = c.getString(nameCol);
                if (!hasEpisodeMarker(name)) continue;
                EpisodeParser.Parsed p = EpisodeParser.parse(name);
                if (p.ep < 1 || p.title == null) continue;
                String title = p.title.trim();
                if (title.length() < 3) continue;
                String nk = norm(title);
                if (nk.length() < 3) continue;
                Integer cur = bestEp.get(nk);
                if (cur == null || p.ep > cur) {
                    bestEp.put(nk, p.ep);
                    bestName.put(nk, name);
                }
                // Every passing file is allowed, not just the highest of each
                // series: MX may show any episode file, and each one lives in
                // the Download tree, so each one must be admitted by the gate.
                allKeys.add(Store.canon(name));
            }
        } catch (Throwable t) {
            Report.err("libscan", "MediaStore query failed", t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }

        Set<String> keep = new HashSet<>();
        int added = 0;
        for (Map.Entry<String, Integer> e : bestEp.entrySet()) {
            String nk = e.getKey();
            String key = Store.canon(bestName.get(nk));
            keep.add(key);
            Store.Row r = new Store.Row();
            r.key = key;
            r.title = bestName.get(nk);
            r.duration = "";
            r.state = "present";
            // Set the parsed title now so Store.upsert can keep an existing MAL
            // match even when a newer (higher) episode file changes the title.
            r.parsedTitle = parseTitle(bestName.get(nk));
            r.ep = e.getValue();
            r.fromLib = true;
            Store.upsert(r);
            added++;
        }
        // Drop series whose files disappeared from the device.
        Store.sweepLib(keep);
        // Publish the Download allowlist, then purge observed rows that have
        // no place in it (SD card, other folders, or apps).
        Store.setLibKeys(allKeys);
        Store.sweepOutside();
        return added;
    }

    private static String parseTitle(String name) {
        return name == null ? "" : EpisodeParser.parse(name).title;
    }

    private static String norm(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9\u0600-\u06FF]+", "");
    }
}
