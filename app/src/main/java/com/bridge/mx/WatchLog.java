package com.bridge.mx;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Append-only audit log of the whole tracking pipeline: every watch observation,
 * every title match attempt, and every write sent to MyAnimeList.
 *
 * <p>{@link Report} records <em>failures</em>; this records the <em>flow</em>, so
 * the question "did my progress actually reach MAL, and what exactly was sent?"
 * has a concrete answer on disk. Lives beside error-report.log in the app's
 * external files dir (adb-readable without root) and rotates at 512&nbsp;KB.
 */
public final class WatchLog {

    private static final String TAG = "WatchLog";
    private static final Object lock = new Object();
    private static final long MAX_BYTES = 512 * 1024;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static File file;

    private WatchLog() {
    }

    public static void init(Context ctx) {
        if (file != null) return;
        try {
            File dir = ctx.getApplicationContext().getExternalFilesDir(null);
            if (dir == null) dir = ctx.getApplicationContext().getFilesDir();
            file = new File(dir, "watch.log");
        } catch (Exception e) {
            Log.w(TAG, "init failed", e);
        }
    }

    /** Absolute path for the UI/log to display. */
    public static String path() {
        return file == null ? "(not initialized)" : file.getAbsolutePath();
    }

    // ------------------------------------------------------------- events

    /** A watch position was observed for one episode. */
    public static void watch(String series, int ep, int pct, String src) {
        event("watch", src + " " + series + " ep" + ep + " " + pct + "%"
                + (PlaybackTracker.isFinished(pct) ? " FINISHED" : ""));
    }

    /** A MAL search returned a chosen match for a parsed title. */
    public static void match(String parsed, String query, String malTitle, int id, int results) {
        event("match", "\"" + parsed + "\" q=\"" + query + "\" -> " + malTitle
                + " (" + id + ") from " + results + " result(s)");
    }

    /** A MAL search produced no acceptable match. */
    public static void miss(String parsed, String query, int results, int ep) {
        event("miss", "\"" + parsed + "\" q=\"" + query + "\" ep" + ep + " (" + results
                + " result(s)) - no acceptable match");
    }

    /** A MAL search threw. */
    public static void searchError(String parsed, String msg) {
        event("error", "\"" + parsed + "\" search failed: " + msg);
    }

    /** A write was sent to MyAnimeList (ok = accepted by the API). */
    public static void push(int animeId, String status, int eps, boolean ok, String detail) {
        event(ok ? "push" : "push!", "anime=" + animeId + " " + status
                + (eps >= 0 ? " " + eps + "ep" : "") + " " + (ok ? "OK" : "FAILED")
                + (detail == null || detail.isEmpty() ? "" : " - " + detail));
    }

    /** A pending write was held back because it would downgrade the MAL value. */
    public static void hold(int animeId, String status, int eps) {
        event("hold", "anime=" + animeId + " kept (would downgrade to " + status
                + (eps >= 0 ? " " + eps + "ep" : "") + ")");
    }

    /** Free-form one-line event. */
    public static void event(String kind, String msg) {
        if (file == null) return;
        String line = FMT.format(new Date()) + "  " + pad(kind) + "  " + msg;
        synchronized (lock) {
            try {
                if (file.length() > MAX_BYTES) {
                    File old = new File(file.getParentFile(), "watch.log.old");
                    if (old.exists() && !old.delete()) Log.w(TAG, "rotate: old delete failed");
                    if (!file.renameTo(old)) Log.w(TAG, "rotate: rename failed");
                }
                try (PrintWriter w = new PrintWriter(new FileWriter(file, true))) {
                    w.println(line);
                }
            } catch (Exception e) {
                Log.w(TAG, "write failed", e);
            }
        }
    }

    /** The last {@code maxLines} lines of the log (for in-app display). */
    public static String tail(int maxLines) {
        if (file == null || !file.exists()) return "(no watch log yet)";
        synchronized (lock) {
            try {
                List<String> lines = new ArrayList<>();
                try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                    String ln;
                    while ((ln = br.readLine()) != null) {
                        lines.add(ln);
                        if (lines.size() > maxLines) lines.remove(0);
                    }
                }
                StringBuilder sb = new StringBuilder();
                for (String l : lines) sb.append(l).append('\n');
                return sb.length() == 0 ? "(watch log is empty)" : sb.toString();
            } catch (Exception e) {
                return "(read failed: " + e.getMessage() + ")";
            }
        }
    }

    private static String pad(String k) {
        String s = k == null ? "?" : k;
        while (s.length() < 7) s = s + " ";
        return s;
    }
}
