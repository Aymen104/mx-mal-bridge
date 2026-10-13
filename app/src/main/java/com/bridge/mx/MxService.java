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
import android.os.StrictMode;
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
import java.util.LinkedHashMap;
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
 * Network channel (background, ~24 minute cadence):
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
    public static final long PIPELINE_PERIOD_MS = 24 * 60 * 1000L;
    private static final String TAG = "MxService";
    private static final String CH = "mxbridge_status";
    private static final int NOTIFY_ID = 1001;

    // Colour classification lives in ColorClassify (pure, unit-tested): the
    // calibrated centroids AND the neutral-grey guard are defined there.

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectNetwork()
                    .penaltyLog()
                    .build());
        }
        Store.init(this);
        Retention.init(this);
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
        Watched.init(this);
        Retention.init(this);
        if (Retention.purgeIfDue(this)) {
            Report.info("service", "weekly retention purge ran on connect");
        }
        AliasStore.load(this);
        configureServiceInfo(); // interaction with other apps = code only
        Report.info("service", "accessibility service connected");
        updateNotification(null);
        // Populate the Download allowlist early so the observation gate has
        // data before the first MX event (the ~24 min tick re-arms it later).
        kickLibScan();
        // first background sync shortly after connect, then every ~20 min
        armNext(8_000);
        Log.i(TAG, "service connected");
    }

    /** Prime the Download allowlist right after connect (non-blocking). */
    private void kickLibScan() {
        pipeline.execute(() -> {
            try {
                if (LibraryScanner.canRead(this)) {
                    LibraryScanner.scan(this);
                    Store.save();
                    Store.notifyChanged();
                }
            } catch (Throwable t) {
                Report.err("service", "initial library scan failed", t);
            }
        });
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
        ensurePlayerWatch();
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
                int watching = 0;
                for (Store.Row r : Store.all()) {
                    if ("unknown".equals(r.state)) unknown++;
                    else if ("watching".equals(r.state)) watching++;
                }
                // "watching" rows are re-checked too: MX shows a last-played
                // label AND a finished tag on the same row, so a text-derived
                // "watching" must still be allowed to become "finished".
                if ((unknown > 0 || watching > 0)
                        && System.currentTimeMillis() - lastClassify > 5000) {
                    requestClassify();
                }
            }
        } catch (Throwable t) {
            Report.err("collect", "reading MX list failed (" + why + ")", t);
        }
    }

    private Store.Row readRow(AccessibilityNodeInfo item) {
        String title = null, badge = null, played = null, dur = null, status = null, count = null;
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
                    case "count":
                        // Only folder rows expose a "N videos" count node.
                        if (t != null) count = t;
                        else if (cd != null) count = cd;
                        break;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) st.push(c);
            }
        }
        if (title == null) return null;

        // Folder rows (Download, Movies, ...) carry a "count" node ("N videos")
        // and are NOT watchable episodes. Skip them entirely so a folder's name
        // can never be matched to an anime or pushed to MAL.
        if (count != null && !count.isEmpty()) return null;

        Store.Row r = new Store.Row();
        EpisodeParser.Parsed parsed = EpisodeParser.parse(title);
        r.key = Store.canon(title);
        r.title = title;
        r.duration = dur == null ? "" : dur;
        r.status = status == null ? "" : status;
        r.parsedTitle = parsed.title;
        r.ep = parsed.ep;
        r.fromLib = false;
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
            if ("finished".equals(r.state) || "none".equals(r.state)) continue;
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

            String verdict = ColorClassify.classify(mr, mg, mb);
            if (verdict == null) continue;
            boolean textState = "watching".equals(r.state) || "new".equals(r.state)
                    || "present".equals(r.state);
            if (textState) {
                // A row MX gave a label/badge: only a colour verdict of
                // "finished" may override it (label + grey tag can coexist).
                if ("finished".equals(verdict)) {
                    r.state = "finished";
                    any = true;
                }
            } else {
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

    // ------------------------------------------- player progress (per episode)

    /**
     * While MX's player screen is foreground we sample the playback position
     * and keep the MAX watched percent per episode in {@link Watched}. When an
     * episode passes {@link PlaybackTracker#FINISH_PCT}% it becomes "finished",
     * exactly like MX's own grey tag, but driven by real playback rather than a
     * colour guess. Sampling runs on a short timer (and on every MX event), so
     * it also captures the persisted resume position after the user quits MX.
     *
     * <p>Signals, most precise first:
     * <pre>
     *   VideoSeekbar      RangeInfo getCurrent()/getMax()  -> exact
     *   VideoTimeCurrent/VideoTimeTotal  labels            -> "00:05"/"00:11"
     *   VideoTitle        the playing file name
     * </pre>
     */
    private boolean playerWatchOn;
    private long lastPlayerDump;
    /** Last file name seen on MX's player; MX hides the title when controls are hidden. */
    private volatile String lastMedia;
    private volatile long lastMediaAt;
    /** How long a cached title stays valid for attributing controls-hidden samples. */
    private static final long MEDIA_TTL_MS = 6 * 60 * 60_000L;

    private final Runnable playerWatchR = this::playerWatch;

    private void ensurePlayerWatch() {
        if (playerWatchOn) return;
        playerWatchOn = true;
        handler.postDelayed(playerWatchR, 1200);
    }

    private void playerWatch() {
        boolean active = false;
        try {
            active = probePlayer("watch");
        } catch (Throwable t) {
            Report.err("player", "watch failed", t);
        }
        if (active) {
            handler.postDelayed(playerWatchR, 2000);
        } else {
            playerWatchOn = false;
        }
    }

    /**
     * Sample MX's player screen when it is the active window.
     *
     * @return true when MX's player screen is foreground (so sampling should
     *         continue), false when MX is not (or not on the player).
     */
    public boolean probePlayer(String why) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !isMx(String.valueOf(root.getPackageName()))) return false;

        List<PlayerScrape.Node> nodes = new ArrayList<>();
        for (AccessibilityNodeInfo n : flatten(root)) {
            String id = null;
            String v = n.getViewIdResourceName();
            if (v != null) {
                int i = v.lastIndexOf('/');
                id = i < 0 ? v : v.substring(i + 1);
            }
            CharSequence cls = n.getClassName();
            String clsS = cls == null ? null : cls.toString();
            AccessibilityNodeInfo.RangeInfo ri = n.getRangeInfo();
            if (ri != null) {
                nodes.add(PlayerScrape.Node.range(id, clsS, ri.getCurrent(), ri.getMax()));
            } else {
                nodes.add(PlayerScrape.Node.of(id, textOf(n), clsS));
            }
        }

        PlayerScrape.Result r;
        try {
            r = PlayerScrape.from(nodes);
        } catch (Throwable t) {
            Report.err("player", "scrape failed", t);
            return false;
        }

        if (!r.isPlayer) {
            // MX is foreground but this is not the player (usually the file
            // list). Dump once so the real hierarchy is captured on-device.
            if (!r.isList) dumpPlayerTree(root, "mx-nonplayer");
            return false;
        }

        // MX only shows the file name while the controls are visible. Cache it so
        // a controls-hidden sample (elapsed_time only) still attributes correctly.
        String media = r.media;
        if (media != null && !media.isEmpty()) {
            lastMedia = media;
            lastMediaAt = System.currentTimeMillis();
        } else {
            media = lastMedia;
            if (media != null
                    && System.currentTimeMillis() - lastMediaAt > MEDIA_TTL_MS) {
                media = null;
            }
        }
        if (media == null || media.isEmpty()) {
            String wt = mxWindowTitle();
            if (looksMedia(wt)) media = wt;
        }
        if (r.percent < 0 || media == null || media.isEmpty()) {
            dumpPlayerTree(root, "player-no-progress");
            return true;
        }

        int pct = r.percent;
        EpisodeParser.Parsed p = EpisodeParser.parse(media);
        String series = p.title.isEmpty() ? media : p.title;
        int ep = p.ep < 0 ? 0 : p.ep;

        int before = Watched.percent(series, ep);
        boolean raised = Watched.record(series, ep, pct);
        applyProgress(series, ep, pct);
        if (raised) {
            Report.info("player", "watch " + series + " ep" + ep + " -> " + pct
                    + "% (" + r.posMs / 1000 + "/" + r.durMs / 1000 + "s) "
                    + (pct >= PlaybackTracker.FINISH_PCT ? "FINISHED " : "") + why);
        }
        if (PlaybackTracker.isFinished(pct) && !PlaybackTracker.isFinished(before)) {
            Report.info("player", "completed: " + series + " ep" + ep + " title=" + media);
        }
        return true;
    }

    /** Reflect an observed percent onto the store rows of that series/episode. */
    private void applyProgress(String series, int ep, int pct) {
        boolean any = false;
        String sn = Watched.norm(series);
        for (Store.Row r : Store.all()) {
            String rn = Watched.norm(r.parsedTitle.isEmpty() ? r.title : r.parsedTitle);
            if (!rn.equals(sn)) continue;
            if (ep > 0 && r.ep > 0 && r.ep != ep) continue;
            if (pct > r.watchedPct) {
                r.watchedPct = pct;
                any = true;
            }
            if (PlaybackTracker.isFinished(pct) && !"finished".equals(r.state)) {
                r.state = "finished";
                any = true;
            }
        }
        if (any) {
            Store.save();
            Store.notifyChanged();
            updateNotification(null);
        }
    }

    /** Title of MX's active window (often the playing file name). */
    private String mxWindowTitle() {
        List<AccessibilityWindowInfo> wins = getWindows();
        if (wins == null) return null;
        for (AccessibilityWindowInfo w : wins) {
            if (!w.isActive() && !w.isFocused()) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null || !isMx(String.valueOf(r.getPackageName()))) continue;
            CharSequence t = w.getTitle();
            if (t != null && t.length() > 0) return t.toString();
        }
        return null;
    }

    private static String textOf(AccessibilityNodeInfo n) {
        if (n == null) return null;
        CharSequence t = n.getText();
        if (t == null) t = n.getContentDescription();
        return t == null ? null : t.toString();
    }

    /** True when a window title looks like a playable media file name. */
    private static boolean looksMedia(String s) {
        return s != null && s.matches(
                "(?i).*\\.(mp4|mkv|avi|webm|m4v|mov|ts|flv|wmv|3gp|mpg|mpeg|m2ts|rmvb|ogv)$");
    }

    /**
     * One-shot diagnostic (at most every 5 min): log the player view tree so we
     * can see exactly which ids/texts MX exposes when no progress was read.
     */
    private void dumpPlayerTree(AccessibilityNodeInfo root, String reason) {
        long now = System.currentTimeMillis();
        if (now - lastPlayerDump < 5 * 60_000L) return;
        lastPlayerDump = now;
        try {
            StringBuilder sb = new StringBuilder("player tree (").append(reason).append(")\n");
            int n = 0;
            for (AccessibilityNodeInfo x : flatten(root)) {
                String v = x.getViewIdResourceName();
                String tx = textOf(x);
                if (v == null && tx == null) continue;
                sb.append("  ").append(x.getClassName()).append(" id=").append(v)
                        .append(" t=").append(tx);
                if (x.getRangeInfo() != null) {
                    sb.append(" range=").append(x.getRangeInfo().getCurrent())
                            .append('/').append(x.getRangeInfo().getMax());
                }
                sb.append('\n');
                if (++n >= 200) break;
            }
            Report.info("playerdump", sb.toString());
        } catch (Throwable ignored) {
        }
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
                armNext(PIPELINE_PERIOD_MS); // ~24 min until the next search
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
            Retention.purgeIfDue(this);
            if (Catalogue.isStale(this)) {
                // Quarterly-ish refresh of the anime-title knowledge base. Runs on
                // its own thread so the ~250-page animeTosho walk never stalls the
                // match/apply pass; Catalogue guards against overlapping runs.
                new Thread(() -> {
                    // refresh() studies and self-tests any new bulk of titles.
                    Catalogue.Result cr = Catalogue.refresh(this, false);
                    if (cr.ok) Report.info("service", "catalogue refresh: " + cr.line);
                }, "catalogue-refresh").start();
            }
            collect("tick");
            int lib = 0;
            if (LibraryScanner.canRead(this)) {
                lib = LibraryScanner.scan(this);
            }
            Planner.rebuild();
            boolean loggedIn = MalClient.hasSession(this);
            boolean auto = Store.autoApply();
            int matched = 0;

            if (loggedIn) {
                // One MAL search per distinct parsed title (a series has many
                // files); assign the result to every row carrying that title.
                Map<String, List<Store.Row>> byTitle = new LinkedHashMap<>();
                for (Store.Row r : Store.all()) {
                    if (!actionable(r) || r.matchId >= 0 || r.parsedTitle.isEmpty()) continue;
                    List<Store.Row> grp = byTitle.get(r.parsedTitle);
                    if (grp == null) {
                        grp = new ArrayList<>();
                        byTitle.put(r.parsedTitle, grp);
                    }
                    grp.add(r);
                }
                int attempts = 0;
                for (Map.Entry<String, List<Store.Row>> e : byTitle.entrySet()) {
                    if (attempts >= 40) break; // keep each tick bounded
                    String title = e.getKey();
                    List<Store.Row> grp = e.getValue();
                    boolean tried = false;
                    for (Store.Row r : grp) {
                        if (title.equals(r.matchTried)) {
                            tried = true;
                            break;
                        }
                    }
                    if (tried) continue;
                    attempts++;
                    String query = Aliases.apply(title);
                    int groupEp = -1;
                    for (Store.Row r : grp) {
                        if (r.ep > groupEp) groupEp = r.ep;
                    }
                    try {
                        JSONArray data = MalClient.search(this, query);
                        Matcher.Result b = Matcher.bestWithBrain(data, query, groupEp,
                                LlmBrain.get(this));
                        for (Store.Row r : grp) r.matchTried = title;
                        if (b != null) {
                            for (Store.Row r : grp) {
                                r.matchId = b.id;
                                r.matchTitle = b.title;
                                r.numEp = b.numEp;
                            }
                            Aliases.learn(title, b.title);
                            AliasStore.persist(this);
                            matched += grp.size();
                            Log.i(TAG, "matched '" + title + "' -> " + b.title);
                        }
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        break;
                    } catch (Throwable t) {
                        Report.err("sync.match", "search failed for '" + title + "'", t);
                    }
                }
                Planner.rebuild(); // plans can now use num_episodes
            }

            int applied = 0, held = 0;
            Map<Integer, String> cur = null;
            if (loggedIn) {
                // Snapshot the user's current MAL list; it is the source of
                // truth and is fed into the plan so the bridge never reads
                // lower than the user's own list (only ever advances).
                try {
                    cur = MalClient.myListStatus(this);
                } catch (Throwable t) {
                    Report.err("sync.apply", "MAL list guard unavailable", t);
                }
                if (cur != null && !cur.isEmpty()) {
                    Store.setMalCur(cur);
                    Planner.rebuild(); // clamp plans to the user's MAL values
                } else {
                    Report.err("sync.apply", "MAL list guard returned empty; proceeding without guard");
                }
            }
            if (auto && loggedIn && cur != null) {
                for (Store.Row r : Store.all()) {
                    if (!actionable(r) || !pushable(r) || MalRepair.pushBlocked(this, r)) continue;
                    String sig = r.planStatus + "|" + r.planEps;
                    String mal = cur == null ? null : cur.get(r.matchId);
                    // "mal" is exactly what WE last wrote, but the plan moved on
                    // (e.g. a presence-era "completed" that MX evidence now
                    // walks back): correcting it is safe AND required. The stale
                    // signature may sit on a sibling row of the same series, so
                    // the check is group-scoped, not just this row.
                    boolean ownStaleLive = mal != null && !mal.equals(sig)
                            && groupAppliedEquals(mal, r.matchId);
                    if (sig.equals(r.applied) && !ownStaleLive) continue; // in sync

                    if (mal != null && !ownStaleLive
                            && Planner.wouldDowngrade(mal, r.planStatus, r.planEps)) {
                        // A manual user edit or a higher MAL count: never walk
                        // it backwards (remember it so we do not retry).
                        r.applied = sig;
                        held++;
                        continue;
                    }
                    if (ownStaleLive) {
                        Report.info("sync.apply", "correcting own stale write: "
                                + r.title + " " + mal + " -> " + sig);
                    }
                    try {
                        // If anime not on user's list, add it first; else update.
                        Log.i("MxService", "Applying: " + r.title + " mal=" + mal + " sig=" + sig);
                        try {
                            if (mal == null) {
                                MalClient.addToList(this, r.matchId, r.planStatus, r.planEps);
                                Report.info("sync.apply", "added to list: " + r.title + " -> " + sig);
                            } else {
                                MalClient.updateStatus(this, r.matchId, r.planStatus, r.planEps);
                                Report.info("sync.apply", r.title + " -> " + sig);
                            }
                        } catch (Throwable netErr) {
                            Report.err("sync.apply", "Network error for '" + r.title + "': " + netErr.getMessage(), netErr);
                            // Don't mark as applied, will retry next tick
                            continue;
                        }
                        for (Store.Row o : Store.all()) {
                            if (o.matchId == r.matchId) o.applied = sig;
                        }
                        applied++;
                        Store.save();
                        Thread.sleep(700);
                    } catch (InterruptedException ie) {
                        break;
                    } catch (Throwable t) {
                        Report.err("sync.apply", "update failed for '" + r.title + "'", t);
                    }
                }
            }

            if (loggedIn) {
                // Automatic withdrawal of bogus MAL writes (see MalRepair): the
                // once-a-day cooldown makes this cheap on every tick, and it
                // runs on its own thread so a slow MAL pass never stalls the
                // rest of the sync.
                new Thread(() -> {
                    try {
                        MalRepair.autoRepair(this);
                    } catch (Throwable t) {
                        Report.err("sync.repair", "mal repair failed", t);
                    }
                }, "mal-repair").start();
            }

            Store.save();
            Store.notifyChanged();
            updateNotification(null);
            if (matched > 0 || applied > 0 || held > 0 || lib > 0) {
                Report.info("sync", "tick: lib=" + lib + " matched=" + matched
                        + " applied=" + applied + " held=" + held
                        + (auto ? "" : " (auto off - dry-run)"));
            }
        } catch (Throwable t) {
            Report.err("sync", "background pipeline failed", t);
        }
    }

    private static boolean actionable(Store.Row r) {
        return !("unknown".equals(r.state) || "none".equals(r.state));
    }

    /**
     * Safe to push to MAL: there must be a real match, and a "completed" mark
     * must carry a concrete episode count. This blocks the old bug where a row
     * with no episode number was sent as "completed" (MAL shows 0/0).
     */
    private static boolean pushable(Store.Row r) {
        if (r.planStatus.isEmpty() || r.matchId <= 0) return false;
        if ("completed".equals(r.planStatus) && r.planEps < 0) return false;
        return true;
    }

    /**
     * Any row of the same series still holding the given applied signature
     * (our own previous write, possibly on a now-non-actionable row).
     */
    private static boolean groupAppliedEquals(String mal, int matchId) {
        for (Store.Row o : Store.all()) {
            if (o.matchId == matchId && mal.equals(o.applied)) return true;
        }
        return false;
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
                text += " | " + Retention.statusLine();
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
