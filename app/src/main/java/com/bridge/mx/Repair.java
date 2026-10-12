package com.bridge.mx;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Escalation when the matcher/LLM makes <b>too many mistakes</b>.
 *
 * <p>When the bridge fails its own exam (below a C, i.e. accuracy &lt; 0.70) it
 * stops pretending it can work unattended and asks for a repair instead:
 * <ol>
 *   <li>writes a self-contained incident report (Markdown) with the exact
 *       mistakes, app/device context and catalogue state to the app's external
 *       files dir, so a host can pull it with adb,</li>
 *   <li>turns on wireless debugging when it is allowed to (the one-time
 *       {@code adb shell pm grant com.bridge.mx android.permission.WRITE_SECURE_SETTINGS}
 *       unlocks this), otherwise deep-links to Developer Options,</li>
 *   <li>posts a notification,</li>
 *   <li>and, if a bridge URL is configured ({@code repair/bridge.txt}), POSTs
 *       the report to the desktop watcher.</li>
 * </ol>
 *
 * <p>The desktop side, {@code tools/opencode_repair.ps1}, pulls the report,
 * enables adb over Wi-Fi ({@code adb tcpip 5555} + {@code adb connect}) and
 * opens a fresh OpenCode session ({@code opencode run --auto}) primed with the
 * incident to fix the bug and reinstall. Nothing here ever throws.
 */
public final class Repair {

    /** Grade C or better — the same bar the reward system uses. */
    public static final double PASS = Selftest.PASS;

    private static final String CH = "mx-repair";
    private static final int NOTIFY_ID = 1002;
    private static final String PREF = "mxmeta";
    private static final String KEY_AT = "repair_at";
    private static final long THROTTLE_MS = 6L * 60 * 60 * 1000; // don't spam

    private Repair() {
    }

    // ---------------------------------------------------------------- verdict

    /** True when a score is below a C: too many mistakes to trust. */
    public static boolean tooManyMistakes(int correct, int total) {
        return total > 0 && (double) correct / total < PASS;
    }

    /** True when an incident was already raised recently (avoid storms). */
    public static boolean reportedRecently(Context c) {
        long at = c.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getLong(KEY_AT, 0);
        return at != 0 && System.currentTimeMillis() - at < THROTTLE_MS;
    }

    // ---------------------------------------------------------------- reports

    public static File dir(Context c) {
        File base = c.getExternalFilesDir(null);
        File d = new File(base == null ? c.getFilesDir() : base, "repair");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /**
     * Persist and broadcast an incident raised by the self-test.
     * Returns the report file, or null on failure. Never throws.
     */
    public static File report(Context c, String reason, Selftest.Result test, String extra) {
        return write(c, reason, test == null ? null : test.report, extra,
                test == null ? "" : test.line());
    }

    /** Persist an incident raised by a manually-run exam. */
    public static File reportExam(Context c, String reason, Exam.Report rep) {
        return write(c, reason, rep, null,
                rep == null ? "" : ("exam " + rep.correct() + "/" + rep.total()));
    }

    private static File write(Context c, String reason, Exam.Report rep, String extra, String score) {
        try {
            String md = build(c, reason, rep, extra, score);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File f = new File(dir(c), "incident-" + stamp + ".md");
            writeFile(f, md);
            writeFile(new File(dir(c), "LATEST.md"), md);
            c.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_AT, System.currentTimeMillis()).apply();
            Report.warn("repair", "incident written: " + f.getName() + " (" + reason + ")");
            android.util.Log.w("Repair", "incident " + f.getName() + ": " + reason);

            String net = enableWirelessDebug(c);
            notifyUser(c, "Repair requested: " + reason + " (" + score + ") \u2014 " + net);
            post(c, md);
            return f;
        } catch (Throwable t) {
            Report.err("repair", "incident failed: " + reason, t);
            return null;
        }
    }

    private static String build(Context c, String reason, Exam.Report rep, String extra,
                                String score) {
        StringBuilder b = new StringBuilder();
        b.append("# MX-MAL Bridge repair request\n\n");
        b.append("- when: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        b.append("- reason: ").append(reason).append('\n');
        b.append("- result: ").append(score).append('\n');
        b.append("- app: ").append(version(c)).append('\n');
        b.append("- device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (Android ").append(Build.VERSION.RELEASE)
                .append(", sdk ").append(Build.VERSION.SDK_INT).append(")\n");
        b.append("- catalogue: ").append(Catalogue.ageLine(c)).append('\n');
        try {
            b.append("- catalogue-file: ").append(Catalogue.titles(c).size()).append(" titles\n");
        } catch (Throwable ignore) {
            // best effort only
        }

        List<Exam.Item> fails = rep == null ? null : rep.failures();
        b.append("\n## Mistakes (").append(fails == null ? "n/a" : String.valueOf(fails.size()))
                .append(")\n\n");
        if (fails != null) {
            for (Exam.Item it : fails) {
                b.append("- `").append(it.q.label).append("` want `").append(it.q.want)
                        .append("` ep ").append(it.q.ep)
                        .append(" -> got ").append(it.gotId == 0 ? "abstained" : it.gotId)
                        .append(", expected ").append(it.q.expectId == 0 ? "abstain" : it.q.expectId)
                        .append(", score ").append(String.format(Locale.US, "%.3f", it.score))
                        .append('\n');
            }
        }
        if (extra != null && !extra.isEmpty()) {
            b.append("\n## Context\n\n").append(extra).append('\n');
        }
        b.append("\n## Suggested action\n\n")
                .append("OpenCode: fix the matcher/alias/planner regression evidenced above, ")
                .append("run the unit tests, rebuild and reinstall com.bridge.mx.\n");
        return b.toString();
    }

    private static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "(unknown)";
        }
    }

    // ------------------------------------------------------- wireless debug

    /**
     * Best-effort "turn on wireless debugging". With WRITE_SECURE_SETTINGS the
     * toggle is flipped directly; without it we deep-link to Developer Options.
     * The authoritative switch is still the host's `adb tcpip 5555`.
     */
    public static String enableWirelessDebug(Context c) {
        try {
            boolean granted = c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                Settings.Global.putInt(c.getContentResolver(), "adb_enabled", 1);
                Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1);
                int port = 0;
                try {
                    port = Settings.Global.getInt(c.getContentResolver(), "adb_wifi_port");
                } catch (Throwable ignore) {
                    // not set on this build
                }
                return "wireless debug ON" + (port > 0 ? " (port " + port + ")" : "");
            }
            openDeveloperOptions(c);
            return "wireless debug needs WRITE_SECURE_SETTINGS (opened Developer Options)";
        } catch (Throwable t) {
            return "wireless debug failed: " + t.getMessage();
        }
    }

    private static void openDeveloperOptions(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            Report.warn("repair", "cannot open developer options: " + t);
        }
    }

    // -------------------------------------------------------------- bridges

    /** POST the report to the desktop watcher, if one is configured. */
    private static void post(Context c, String md) {
        String url = bridgeUrl(c);
        if (url == null) return;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/markdown; charset=utf-8");
            conn.setRequestProperty("X-Mx-Bridge", "repair");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(md.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            Report.info("repair", "bridge POST " + code + " -> " + url);
        } catch (Throwable t) {
            Report.warn("repair", "bridge POST failed: " + t);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Read the configured bridge URL from repair/bridge.txt (first line). */
    private static String bridgeUrl(Context c) {
        File f = new File(dir(c), "bridge.txt");
        if (!f.isFile()) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            if (line == null) return null;
            line = line.trim();
            return line.startsWith("http") ? line : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ notification

    private static void notifyUser(Context c, String text) {
        try {
            NotificationManager mgr = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (mgr == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH,
                        "MX-MAL repair", NotificationManager.IMPORTANCE_HIGH);
                mgr.createNotificationChannel(ch);
            }
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, 0, i,
                    PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CH)
                    : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("MX-MAL Bridge: repair needed")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            boolean ok = Build.VERSION.SDK_INT < 33
                    || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                       == PackageManager.PERMISSION_GRANTED;
            if (ok) mgr.notify(NOTIFY_ID, b.build());
        } catch (Throwable t) {
            Report.warn("repair", "repair notification failed: " + t);
        }
    }

    private static void writeFile(File f, String s) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
