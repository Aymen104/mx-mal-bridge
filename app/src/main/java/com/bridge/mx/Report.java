package com.bridge.mx;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persists error reports to
 *   Android/data/com.bridge.mx/files/error-report.log
 * (external files dir = readable over adb without root: run-as/debug not
 * needed). Failures survive the app process, with timestamps, origin and
 * full stack traces. File rotates at 512 KB to error-report.old.log.
 */
public class Report {

    private static final String TAG = "Report";
    private static File file;
    private static final Object lock = new Object();

    public static void init(Context ctx) {
        if (file != null) return;
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            file = new File(dir, "error-report.log");
        } catch (Exception e) {
            Log.w(TAG, "init failed", e);
        }
    }

    /** Path for the UI/log to display. */
    public static String path() {
        return file == null ? "(not initialized)" : file.getAbsolutePath();
    }

    public static void err(String where, String msg) {
        append("ERROR", where, msg, null);
    }

    public static void err(String where, String msg, Throwable t) {
        append("ERROR", where, msg, t);
    }

    public static void warn(String where, String msg) {
        append("WARN ", where, msg, null);
    }

    /** Session/context banner (kept in the file so reports self-describe). */
    public static void info(String where, String msg) {
        append("INFO ", where, msg, null);
    }

    private static void append(String level, String where, String msg, Throwable t) {
        try {
            if (file == null) return;
            synchronized (lock) {
                if (file.length() > 512_000L) {
                    File old = new File(file.getParentFile(), "error-report.old.log");
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    file.renameTo(old);
                }
                StringBuilder sb = new StringBuilder();
                sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(new Date()));
                sb.append(' ').append(level).append(" [").append(where).append("] ")
                        .append(msg == null ? "" : msg).append('\n');
                if (t != null) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    sb.append(sw.toString());
                }
                FileWriter fw = new FileWriter(file, true);
                fw.write(sb.toString());
                fw.close();
            }
        } catch (Throwable ignored) {
            // Never let error reporting itself crash the app.
        }
    }
}
