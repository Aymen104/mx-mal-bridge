package com.bridge.mx;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background worker + read-only observer of MX Player's file list.
 *
 * Local channel (instant, event driven, never touches MX):
 *   list_item rows -> title / NEW badge / last-played label
 *   one screen capture (API 30+, only while MX is foreground) classifies
 *   label-less rows against the calibrated centroids:
 *     blue (103,125,179) = Last played, dim (167,149,132) = Finished,
 *     dark (123,105,101) = normal (no label)
 *
 * Network channel (background, ~20 minute cadence):
 *   collect -> parse -> MAL search for new titles -> plan -> auto-apply ONLY
 *   if the user enabled Auto (default off = dry-run).
 *
 * All failures are written to error-report.log (see Report).
 */
public class MxService extends AccessibilityService {

    /** MX Player variants observed (free + paid). */
    public static boolean isMx(String pkg) {
        return "com.mxtech.videoplayer.ad".equals(pkg)
                || "com.mxtech.videoplayer.pro".equals(pkg);
    }
    /** Search cadence for newly seen info. */
    public static final long PIPELINE_PERIOD_MS = 20 * 60 * 1000L;
    private static final String TAG = "MxService";
    private static final String CH = "mxbridge_status";
    private static final int NOTIFY_ID = 1001;

    // Calibrated centroids (light theme, bg ~241,241,241).
    private static final int C_PLAYED[] = {103, 125, 179};
    private static final int C_FINISHED[] = {167, 149, 132};
    private static final int C_NORMAL[] = {123, 105, 101};
    private static final int CENTROID_MAX_DIST = 45;

    private static MxService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService shotExec = Executors.newSingleThreadExecutor();
    private final ExecutorService pipeline = Executors.newSingleThreadExecutor();
    private boolean scanPending = false;
    private long lastClassify = 0;
    private String lastNotify = "";
    private NotificationManager notifMgr;
    volatile long nextRunAt = 0;

    public static MxService get() {
        return instance;
    }

    /** Milliseconds until the next background sync tick (0 = unknown/off). */
    public long nextRunInMs() {
        return nextRunAt == 0 ? 0 : Math.max(0, nextRunAt - System.currentTimeMillis());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Store.init(this);
        notifMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notifMgr != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH,
                    "MX-MAL Bridge status", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            notifMgr.createNotificationChannel(c);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Store.init(this);
        configureServiceInfo(); // interaction with other apps = code only
        Report.info("service", "accessibility service connected");
        updateNotification(null);
        // first background sync shortly after connect, then every ~20 min
        armNext(8_000);
        Log.i(TAG, "service connected");
    }

    /**
     * All interaction with other apps is defined here, in code: which app we
     * observe, which events we receive, which flags are active. The XML meta
     * data resource stays only because Android requires it as the registration
     * card and as the ONLY place the platform accepts window-content /
     * screenshot capabilities; every behavioral knob is set from this method.
     */
    private void configureServiceInfo() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) info = new AccessibilityServiceInfo();
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 300;
            info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.packageNames = new String[]{
                    "com.mxtech.videoplayer.ad",
                    "com.mxtech.videoplayer.pro"};
            setServiceInfo(info);
            Report.info("service", "interaction configured from code: "
                    + "MX free+pro, window events, report view ids");
            Log.i(TAG, "service info configured from code");
        } catch (Throwable t) {
            Report.err("service", "setServiceInfo failed", t);
        }
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        try {
            if (notifMgr != null) notifMgr.cancel(NOTIFY_ID);
        } catch (Throwable ignored) {
        }
        Report.info("service", "accessibility service destroyed");
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent ev) {
        CharSequence pkg = ev.getPackageName();
        if (pkg == null || !isMx(pkg.toString())) return;
        if (scanPending) return;
        scanPending = true;
        handler.postDelayed(() -> {
            scanPending = false;
            collect("event");
        }, 500);
    }

    @Override
    public void onInterrupt() {
    }

    // ------------------------------------------------------------- local IO

    /** Read the current MX list window into the store (read-only). */
    public void collect(String why) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                List<AccessibilityWindowInfo> wins = getWindows();
                if (wins != null) {
                    for (AccessibilityWindowInfo w : wins) {
                        AccessibilityNodeInfo r = w.getRoot();
                        if (r != null && isMx(String.valueOf(r.getPackageName()))) {
                            root = r;
                            break;
                        }
                    }
                }
            }
            if (root == null) return;

            List<AccessibilityNodeInfo> all = flatten(root);
            int found = 0;
            for (AccessibilityNodeInfo item : all) {
                String vid = item.getViewIdResourceName();
                if (vid == null || !vid.endsWith(":id/list_item")) continue;
                Store.Row row = readRow(item);
                if (row != null) {
                    Store.upsert(row);
                    found++;
                }
            }
            if (found > 0) {
                Store.save();
                Store.notifyChanged();
                Log.i(TAG, "collect(" + why + "): " + found + " rows");
                updateNotification(null);
                int unknown = 0;
                for (Store.Row r : Store.all()) {
                    if ("unknown".equals(r.state)) unknown++;
                }
                if (unknown > 0 && System.currentTimeMillis() - lastClassify > 4000) {
                    requestClassify();
                }
            }
        } catch (Throwable t) {
            Report.err("collect", "reading MX list failed (" + why + ")", t);
        }
    }

    private Store.Row readRow(AccessibilityNodeInfo item) {
        String title = null, badge = null, played = null, dur = null, status = null;
        Rect tb = null;

        Deque<AccessibilityNodeInfo> st = new ArrayDeque<>();
        st.push(item);
        while (!st.isEmpty()) {
            AccessibilityNodeInfo n = st.pop();
            String v = n.getViewIdResourceName();
            if (v != null) {
                String id = v.substring(v.indexOf(":id/") + 4);
                String t = n.getText() == null ? null : n.getText().toString();
                String cd = n.getContentDescription() == null
                        ? null : n.getContentDescription().toString();
                switch (id) {
                    case "title":
                        if (t != null) title = t;
                        else if (cd != null) title = cd;
                        tb = new Rect();
                        n.getBoundsInScreen(tb);
                        break;
                    case "new_title":
                        if (t != null) badge = t;
                        else if (cd != null) badge = cd;
                        break;
                    case "tv_last_played":
                        if (t != null) played = t;
                        else if (cd != null) played = cd;
                        break;
                    case "duration":
                        if (t != null) dur = t;
                        break;
                    case "tv_status":
                        if (t != null) status = t;
                        else if (cd != null) status = cd;
                        break;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) st.push(c);
            }
        }
        if (title == null) return null;

        Store.Row r = new Store.Row();
        r.key = title + "|" + (dur == null ? "" : dur);
        r.title = title;
        r.duration = dur == null ? "" : dur;
        r.status = status == null ? "" : status;
        if (badge != null && !badge.isEmpty()) r.state = "new";
        else if (played != null && !played.isEmpty()) r.state = "watching";
        else r.state = "unknown";
        if (tb != null) {
            r.left = tb.left;
            r.top = tb.top;
            r.right = tb.right;
            r.bottom = tb.bottom;
        }
        return r;
    }

    /** One screen capture to classify label-less rows (API 30+, MX focused). */
    public void requestClassify() {
        if (Build.VERSION.SDK_INT < 30) return;
        if (!mxFocused()) {
            Log.i(TAG, "classify skipped: MX Player not in foreground");
            return;
        }
        lastClassify = System.currentTimeMillis();
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExec, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    Bitmap screenshot = null;
                    try {
                        HardwareBuffer hb = result.getHardwareBuffer();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                        if (hw == null) {
                            Report.err("classify", "wrapHardwareBuffer returned null");
                            return;
                        }
                        // hardware bitmap -> software so we can read pixels
                        try {
                            screenshot = hw.copy(Bitmap.Config.ARGB_8888, false);
                        } catch (Throwable ignored) {
                            screenshot = null;
                        }
                        if (screenshot == null) {
                            screenshot = Bitmap.createBitmap(
                                    hw.getWidth(), hw.getHeight(), Bitmap.Config.ARGB_8888);
                            new Canvas(screenshot).drawBitmap(hw, 0, 0, null);
                        }
                        hw.recycle();
                        classify(screenshot);
                    } catch (Throwable t) {
                        Report.err("classify", "screenshot classification failed", t);
                    } finally {
                        if (screenshot != null) screenshot.recycle();
                        try {
                            result.getHardwareBuffer().close();
                        } catch (Throwable ignored) {
                        }
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    Report.err("classify", "screenshot failed, errorCode=" + errorCode);
                }
            });
        } catch (Throwable t) {
            Report.err("classify", "takeScreenshot threw", t);
        }
    }

    private boolean mxFocused() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        return r != null && isMx(String.valueOf(r.getPackageName()));
    }

    private void classify(Bitmap bmp) {
        boolean any = false;
        int bw = bmp.getWidth(), bh = bmp.getHeight();
        for (Store.Row r : Store.all()) {
            if (!"unknown".equals(r.state)) continue;
            int l = Math.max(0, r.left), t = Math.max(0, r.top);
            int rr = Math.min(bw, r.right), bb = Math.min(bh, r.bottom);
            int w = rr - l, h = bb - t;
            if (w < 8 || h < 8) continue;

            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, l, t, w, h);

            long br = 0, bg = 0, bl = 0;
            int bn = 0;
            for (int c : px) {
                if (Color.red(c) > 200 && Color.green(c) > 200 && Color.blue(c) > 200) {
                    br += Color.red(c);
                    bg += Color.green(c);
                    bl += Color.blue(c);
                    bn++;
                }
            }
            if (bn < px.length / 12) continue; // dark theme / off-screen: skip
            int BGr = (int) (br / bn), BGg = (int) (bg / bn), BGb = (int) (bl / bn);

            Map<Integer, int[]> bins = new HashMap<>(); // key -> [count, rSum, gSum, bSum]
            for (int c : px) {
                int delta = Math.max(
                        Math.abs(BGr - Color.red(c)),
                        Math.max(Math.abs(BGg - Color.green(c)),
                                Math.abs(BGb - Color.blue(c))));
                if (delta < 90) continue;
                int key = ((Color.red(c) >> 4) << 8) | ((Color.green(c) >> 4) << 4)
                        | (Color.blue(c) >> 4);
                int[] b = bins.get(key);
                if (b == null) {
                    b = new int[4];
                    bins.put(key, b);
                }
                b[0]++;
                b[1] += Color.red(c);
                b[2] += Color.green(c);
                b[3] += Color.blue(c);
            }
            if (bins.isEmpty()) continue;

            int bestC = 0;
            int[] best = null;
            for (int[] b : bins.values()) {
                if (b[0] > bestC) {
                    bestC = b[0];
                    best = b;
                }
            }
            int mr = best[1] / best[0], mg = best[2] / best[0], mb = best[3] / best[0];
            r.rgb = (mr << 16) | (mg << 8) | mb;

            String verdict = nearest(mr, mg, mb);
            if (verdict != null) {
                r.state = verdict;
                any = true;
            }
        }
        if (any) {
            Store.save();
            Store.notifyChanged();
            updateNotification(null);
            Log.i(TAG, "classify: verdicts updated");
        }
    }

    private static String nearest(int r, int g, int b) {
        int dp = dist2(r, g, b, C_PLAYED);
        int df = dist2(r, g, b, C_FINISHED);
        int dn = dist2(r, g, b, C_NORMAL);
        int limit = CENTROID_MAX_DIST * CENTROID_MAX_DIST;
        int min = Math.min(dp, Math.min(df, dn));
        if (min > limit) return null;
        if (min == dp) return "watching";
        if (min == df) return "finished";
        return "none";
    }

    private static int dist2(int r, int g, int b, int[] c) {
        int dr = r - c[0], dg = g - c[1], db = b - c[2];
        return dr * dr + dg * dg + db * db;
    }

    private static List<AccessibilityNodeInfo> flatten(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        Deque<AccessibilityNodeInfo> st = new ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            AccessibilityNodeInfo n = st.pop();
            out.add(n);
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) st.push(c);
            }
        }
        return out;
    }

    // ----------------------------------------------------- background sync

    private final Runnable tickR = this::tick;

    /** Arm the next network tick after delayMs (replaces any pending tick). */
    private void armNext(long delayMs) {
        handler.removeCallbacks(tickR);
        nextRunAt = System.currentTimeMillis() + delayMs;
        handler.postDelayed(tickR, delayMs);
        updateNotification(null);
    }

    private void tick() {
        pipeline.execute(() -> {
            try {
                runPipeline();
            } finally {
                armNext(PIPELINE_PERIOD_MS); // ~20 min until the next search
            }
        });
    }

    /**
     * Network pass: refresh rows, parse, search MAL for not-yet-matched
     * titles (rate-limited internally), plan, and auto-apply only when the
     * Auto toggle is on. Never touches MX Player.
     */
    private void runPipeline() {
        try {
            collect("tick");
            Planner.rebuild();
            boolean loggedIn = MalClient.hasSession(this);
            boolean auto = Store.autoApply();
            int matched = 0;

            if (loggedIn) {
                for (Store.Row r : Store.all()) {
                    if (matched >= 4) break; // keep each tick light
                    if (!actionable(r) || r.matchId >= 0 || r.parsedTitle.isEmpty()) continue;
                    if (r.parsedTitle.equals(r.matchTried)) continue;
                    try {
                        JSONArray data = MalClient.search(this, r.parsedTitle);
                        Matcher.Result b = Matcher.best(data, r.parsedTitle);
                        r.matchTried = r.parsedTitle;
                        if (b != null) {
                            r.matchId = b.id;
                            r.matchTitle = b.title;
                            r.numEp = b.numEp;
                            matched++;
                            Log.i(TAG, "matched '" + r.parsedTitle + "' -> " + b.title);
                        }
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        break;
                    } catch (Throwable t) {
                        Report.err("sync.match", "search failed for '" + r.parsedTitle + "'", t);
                    }
                }
                Planner.rebuild(); // plans can now use num_episodes
            }

            int applied = 0;
            if (auto && loggedIn) {
                for (Store.Row r : Store.all()) {
                    if (!actionable(r) || r.planStatus.isEmpty() || r.matchId < 0) continue;
                    String sig = r.planStatus + "|" + r.planEps;
                    if (sig.equals(r.applied)) continue;
                    try {
                        MalClient.updateStatus(this, r.matchId, r.planStatus, r.planEps);
                        r.applied = sig;
                        applied++;
                        Report.info("sync.apply", r.title + " -> " + sig);
                        Store.save();
                        Thread.sleep(700);
                    } catch (InterruptedException ie) {
                        break;
                    } catch (Throwable t) {
                        Report.err("sync.apply", "update failed for '" + r.title + "'", t);
                    }
                }
            }

            Store.save();
            Store.notifyChanged();
            updateNotification(null);
            if (matched > 0 || applied > 0) {
                Report.info("sync", "tick: matched=" + matched + " applied=" + applied
                        + (auto ? "" : " (auto off - dry-run)"));
            }
        } catch (Throwable t) {
            Report.err("sync", "background pipeline failed", t);
        }
    }

    private static boolean actionable(Store.Row r) {
        return !("unknown".equals(r.state) || "none".equals(r.state));
    }

    // ---------------------------------------------------------- notification

    public void refreshNotification() {
        lastNotify = "";
        updateNotification(null);
    }

    private void updateNotification(String force) {
        try {
            if (notifMgr == null) return;
            String text = force;
            if (text == null) {
                int planned = 0;
                List<Store.Row> rows = Store.all();
                for (Store.Row r : rows) {
                    if (!r.planStatus.isEmpty()) planned++;
                }
                text = rows.size() + " rows | " + planned + " planned | auto "
                        + (Store.autoApply() ? "ON" : "off");
                long in = nextRunInMs();
                if (in > 0) text += " | sync in " + ((in + 59_000) / 60_000) + "m";
            }
            if (text.equals(lastNotify)) return;
            lastNotify = text;

            Intent i = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CH)
                    : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("MX-MAL Bridge")
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true);
            boolean notifOk = Build.VERSION.SDK_INT < 33
                    || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                       == PackageManager.PERMISSION_GRANTED;
            if (notifOk) notifMgr.notify(NOTIFY_ID, b.build());
        } catch (Throwable t) {
            Report.err("notify", "status notification failed", t);
        }
    }
}
