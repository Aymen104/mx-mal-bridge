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
 * (Arabic "الحلقة N", "EP N", "Episode N" or "SxxExx"). Music videos, openings,
 * clips, Quran recitations and other noise in the Download folder are ignored
 * so they can never be matched to an anime and pushed to MAL.
 *
 * The scan is scoped to built-in ("primary") storage: the removable SD card is
 * ignored on purpose.
 *
 * <p>It is additionally scoped to the Download folder. Only videos that live
 * under {@code /storage/emulated/0/Download} (the tree, so sub-folders such as
 * {@code Download/2- Anime/...} are included) are considered; any video stored
 * anywhere else on the device is ignored, so this bridge only ever reflects the
 * user's downloaded library.
 */
public class LibraryScanner {

    private static final Pattern HAS_EP = Pattern.compile(
            "الحلقة"
            + "|(?i)(?<![A-Za-z0-9])(episode|ep)\\s*[-_.]?\\s*\\d{1,4}(?!\\d)"
            + "|(?i)(?<![A-Za-z0-9])s\\d{1,2}\\s*[-_.]?\\s*e\\d{1,3}(?!\\d)");

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
     * True when the row's path is inside the Download tree (or undeterminable
     * on a platform where the column is unavailable).
     */
    private static boolean inDownload(Cursor c, int col) {
        if (col < 0) return true; // platform exposes no path column: cannot filter
        String p = rowPath(c, col);
        if (p == null) return false; // present column but no path: be strict
        if (Build.VERSION.SDK_INT >= 29) {
            // p = "/Download/…" on primary volume.
            return p.startsWith("/download/");
        }
        String base = Environment.getExternalStorageDirectory().getAbsolutePath().toLowerCase(Locale.ROOT);
        return p.startsWith(base + "/download/");
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
                if (name == null || !HAS_EP.matcher(name).find()) continue;
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
