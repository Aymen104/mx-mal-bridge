package com.bridge.mx;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entire UI is built from code (no layout XML): a plain-language status
 * header, a small set of action buttons, the observed-episode list and a
 * log that stays collapsed by default. The activity is a control panel /
 * dry-run view; the real work happens in MxService.
 */
public class MainActivity extends Activity implements Store.Listener {

    private TextView statusHead, statusLine, statusAuto, statusDetails, logView, logToggle;
    private ListView list;
    private ScrollView logScroll;
    private Button btnAuto, btnApply;
    private EpisodeAdapter adapter;
    private boolean logOpen;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private Handler ui;

    /** Small on-device model — the priority title matcher when loaded. */
    private static final String MODEL_URL =
            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/"
                    + "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Store.init(this);
        AliasStore.load(this);
        ui = new Handler(getMainLooper());
        buildUi();

        // Notification permission (Android 13+) for the status notification.
        if (Build.VERSION.SDK_INT >= 33) {
            SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED
                    && !cfg.getBoolean("asked_notif", false)) {
                cfg.edit().putBoolean("asked_notif", true).apply();
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
            }
        }

        // Video library read permission (used by the MediaStore library scan).
        if (!LibraryScanner.canRead(this)) {
            SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
            if (!cfg.getBoolean("asked_media", false)) {
                cfg.edit().putBoolean("asked_media", true).apply();
                requestMediaPermission();
            }
        }

        say("Welcome \u2014 tap \u201CSync now\u201D to read MX Player, scan your videos"
                + " and work out what you have watched.");
        say("Auto is OFF: nothing is written to MyAnimeList until you tap"
                + " \u201CApply to MAL\u201D (or turn Auto ON).");
        say("New here? Tap More \u22EE > \u201CHow it works\u201D.");
        say("Errors: " + safeReportPath());
        Retention.init(this);
        refreshStatus();
        // QA hook: `adb shell am start -n com.bridge.mx/.MainActivity --ez self_test true`
        // runs the deterministic self-test (and its repair escalation) on launch.
        if (getIntent() != null && getIntent().getBooleanExtra("self_test", false)) {
            getWindow().getDecorView().postDelayed(this::doTest, 800);
        }
        // QA hook: `--ez titles true` forces the animeTosho refresh + study + exam.
        if (getIntent() != null && getIntent().getBooleanExtra("titles", false)) {
            getWindow().getDecorView().postDelayed(this::doTitles, 1200);
        }
        // QA hook: `--ez repair true` runs the automatic MAL repair live
        // (withdraws bogus writes; see MalRepair) instead of waiting for the
        // next daily service pass.
        if (getIntent() != null && getIntent().getBooleanExtra("repair", false)) {
            getWindow().getDecorView().postDelayed(this::doRepair, 1200);
        }
        // QA hook: `--ez repair_dry true` plans the repair with NO writes.
        if (getIntent() != null && getIntent().getBooleanExtra("repair_dry", false)) {
            getWindow().getDecorView().postDelayed(this::doRepairDry, 1200);
        }
        // QA hook: `--ez bootcamp true` runs the drill — lessons + the exam,
        // repeated until a perfect paper (cap 25, or `--ei tries N`).
        if (getIntent() != null && getIntent().getBooleanExtra("bootcamp", false)) {
            getWindow().getDecorView().postDelayed(this::doDrill, 1200);
        }
        // QA hook: `--ez sync true` runs the one-tap Sync pipeline on launch.
        if (getIntent() != null && getIntent().getBooleanExtra("sync", false)) {
            getWindow().getDecorView().postDelayed(this::doSync, 1500);
        }
        // QA hook: `--ez watch_access true` opens the notification-access screen.
        if (getIntent() != null && getIntent().getBooleanExtra("watch_access", false)) {
            getWindow().getDecorView().postDelayed(this::doWatchAccess, 900);
        }
        // QA hook: `--es at_base http://<host>:<port>` points the crawl at a
        // local mirror of the listing (saved bodies) instead of the live site.
        if (getIntent() != null) {
            Catalogue.baseOverride = getIntent().getStringExtra("at_base");
        }
    }

    // ------------------------------------------------------- UI from code

    private void buildUi() {
        float d = getResources().getDisplayMetrics().density;
        int p12 = (int) (12 * d), p8 = (int) (8 * d), p4 = (int) (4 * d), p6 = (int) (6 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p12, p12, p12, p8);

        // Plain-language status: a headline, a one-line summary and the Auto
        // state. No pipe-separated jargon a first-time user has to decode.
        statusHead = new TextView(this);
        statusHead.setTextSize(17);
        statusHead.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(statusHead, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        statusLine = new TextView(this);
        statusLine.setTextSize(14);
        statusLine.setPadding(0, p4, 0, 0);
        root.addView(statusLine, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        statusAuto = new TextView(this);
        statusAuto.setTextSize(13);
        statusAuto.setPadding(0, p4, 0, 0);
        root.addView(statusAuto, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // The old cryptic line survives here, small and dim, for anyone who
        // wants the raw numbers.
        statusDetails = new TextView(this);
        statusDetails.setTextSize(10);
        statusDetails.setTextColor(0xFF888888);
        statusDetails.setPadding(0, p4, 0, p6);
        root.addView(statusDetails, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Two rows of clear controls. "Sync now" is the one tap that does the
        // whole safe pipeline; the rest are the few things a normal user needs.
        // Every other feature stays under More (nothing removed).
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);

        Button sync = btn("Sync now", v -> doSync());
        sync.setTextSize(15);
        sync.setTypeface(Typeface.DEFAULT_BOLD);
        row1.addView(sync, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2.4f));
        addBtn(row1, "More \u22EE", this::showMoreMenu);
        root.addView(row1, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        btnApply = btn("Apply to MAL", v -> doApply());
        row2.addView(btnApply, weight());
        btnAuto = btn("Auto: OFF", v -> doAuto());
        row2.addView(btnAuto, weight());
        root.addView(row2, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // observed episodes
        list = new ListView(this);
        adapter = new EpisodeAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Log: hidden behind a tap so the screen is calm by default.
        logToggle = new TextView(this);
        logToggle.setText("Log \u25B8  (tap to show)");
        logToggle.setTextSize(12);
        logToggle.setTypeface(Typeface.DEFAULT_BOLD);
        logToggle.setPadding(0, p4, 0, p4);
        logToggle.setOnClickListener(v -> {
            logOpen = !logOpen;
            logScroll.setVisibility(logOpen ? View.VISIBLE : View.GONE);
            logToggle.setText(logOpen
                    ? "Log \u25BE  (tap to hide)" : "Log \u25B8  (tap to show)");
        });
        root.addView(logToggle, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(0xFFF2F2F2);
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(p4, p4, p4, p4);
        logScroll.addView(logView, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        logScroll.setVisibility(View.GONE);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (130 * d)));

        setContentView(root);
    }

    private Button btn(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        int px = (int) (6 * getResources().getDisplayMetrics().density);
        b.setPadding(px, b.getPaddingTop(), px, b.getPaddingBottom());
        b.setOnClickListener(onClick);
        return b;
    }

    /**
     * The overflow menu: everything that is not part of the everyday flow
     * (Sync / Apply / Auto). Keeping them here makes the main screen simple
     * without removing any feature.
     */
    private void showMoreMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "How it works (help)");
        m.getMenu().add(0, 2, 1, "Login to MyAnimeList");
        m.getMenu().add(0, 3, 2, "Match titles on MAL");
        m.getMenu().add(0, 4, 3, "AI model (optional)");
        m.getMenu().add(0, 5, 4, "Scan MX only");
        m.getMenu().add(0, 6, 5, "Library scan");
        m.getMenu().add(0, 7, 6, "Colors (classify)");
        m.getMenu().add(0, 8, 7, "Plan");
        m.getMenu().add(0, 9, 8, "Watch: notification access");
        m.getMenu().add(0, 10, 9, "Watch log (audit)");
        m.getMenu().add(0, 11, 10, "Exam");
        m.getMenu().add(0, 12, 11, "Drill (lessons)");
        m.getMenu().add(0, 13, 12, "Self-test");
        m.getMenu().add(0, 14, 13, "Titles (update)");
        m.getMenu().add(0, 15, 14, "Repair");
        m.getMenu().add(0, 16, 15, "Wipe");
        m.setOnMenuItemClickListener((MenuItem item) -> {
            switch (item.getItemId()) {
                case 1: doHelp(); return true;
                case 2: doLogin(); return true;
                case 3: doMatch(); return true;
                case 4: doAi(); return true;
                case 5: doScan(); return true;
                case 6: doLibScan(); return true;
                case 7: doColors(); return true;
                case 8: doPlan(); return true;
                case 9: doWatchAccess(); return true;
                case 10: doWatchLog(); return true;
                case 11: doExam(); return true;
                case 12: doDrill(); return true;
                case 13: doTest(); return true;
                case 14: doTitles(); return true;
                case 15: doRepair(); return true;
                case 16: doWipe(); return true;
                default: return false;
            }
        });
        m.show();
    }

    /** Plain-language explanation for a first-time user. */
    private void doHelp() {
        new AlertDialog.Builder(this)
                .setTitle("How it works")
                .setMessage(
                        "1. Watch or download something in MX Player.\n\n"
                        + "2. Open this app and tap \u201CSync now\u201D. It reads MX Player,"
                        + " scans your Download folder, and works out which episodes"
                        + " you have watched.\n\n"
                        + "3. Tap \u201CApply to MAL\u201D to write those episodes to"
                        + " MyAnimeList \u2014 or turn Auto ON and it will write them for"
                        + " you whenever it syncs (about every 24 min).\n\n"
                        + "Auto is OFF by default, so nothing is ever written to"
                        + " MyAnimeList until you ask. Everything on the screen is"
                        + " just a preview.\n\n"
                        + "\u2022 Green line under an episode = what will be sent to MAL.\n"
                        + "\u2022 \u201CWatch progress\u201D (More) lets it read the exact"
                        + " playback position \u2014 optional.\n"
                        + "\u2022 \u201CAI model\u201D (More) is an optional on-device helper"
                        + " for matching odd filenames; the app works without it.")
                .setPositiveButton("Got it", null)
                .show();
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    /** Equal-weight slot so every button in the strip shares the row. */
    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void addBtn(LinearLayout row, String text, View.OnClickListener onClick) {
        row.addView(btn(text, onClick), weight());
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        Store.addListener(this);
        refreshStatus();
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Store.removeListener(this);
    }

    @Override
    public void onChanged() {
        ui.post(() -> {
            adapter.notifyDataSetChanged();
            refreshStatus();
        });
    }

    // ------------------------------------------------------------- actions

    private void doAuto() {
        boolean on = !Store.autoApply();
        Store.setAutoApply(on);
        say(on
                ? "Auto-update ON: new watches are written to MyAnimeList automatically"
                        + " on the next sync."
                : "Auto-update OFF: preview only. Tap \u201CApply to MAL\u201D when you"
                        + " want to write.");
        MxService s = MxService.get();
        if (s != null) s.refreshNotification();
        refreshStatus();
    }

    /**
     * Run the built-in exam over the real matching pipeline (plus the on-device
     * LLM when it is loaded) and pay out the reward. The questions and the
     * answer key live in {@link Exam}; scoring and points live in {@link Reward}.
     */
    private void doExam() {
        LlmBrain brain = LlmBrain.get(this);
        say("Studying, then exam (" + Exam.bank().size() + " questions, "
                + (brain.ready() ? "with AI" : "deterministic") + ")...");
        new Thread(() -> {
            // Study the catalogue first, every time, exactly as production
            // would prime its shorthand table before doing real work.
            final Educate.Result study = Educate.study(MainActivity.this);
            final Exam.Report rep = Exam.run(brain);
            final Reward.Result res = Reward.award(MainActivity.this, rep);
            // Too many mistakes: raise a repair incident for the desktop watcher.
            final boolean escalated = !res.passed && !Repair.reportedRecently(MainActivity.this);
            if (escalated) {
                Repair.reportExam(MainActivity.this, "exam failed (grade " + res.grade + ")", rep);
            }
            ui.post(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append(study.line()).append("\n\n");
                sb.append(res.line()).append("\n\n");
                List<Exam.Item> fails = rep.failures();
                if (!fails.isEmpty()) {
                    sb.append("Missed ").append(fails.size()).append(":\n");
                    int n = 0;
                    for (Exam.Item it : fails) {
                        if (n++ >= 8) {
                            sb.append("  ...\n");
                            break;
                        }
                        sb.append("  ").append(it.q.label)
                                .append(" -> ").append(it.gotId == 0 ? "abstained" : String.valueOf(it.gotId))
                                .append(" (want ")
                                .append(it.q.expectId == 0 ? "abstain" : String.valueOf(it.q.expectId))
                                .append(")\n");
                    }
                    sb.append("\n");
                }
                sb.append(Reward.detail());
                if (escalated) {
                    sb.append("\n\nToo many mistakes: repair incident written to\n")
                            .append(Repair.dir(MainActivity.this)).append("/LATEST.md");
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Exam \u2014 " + Reward.gradeInWords(res.grade))
                        .setMessage(sb.toString())
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Reset rewards", (d, w) -> {
                            Reward.reset();
                            refreshStatus();
                            say("Rewards reset.");
                        })
                        .show();
                refreshStatus();
                MxService s = MxService.get();
                if (s != null) s.refreshNotification();
            });
        }, "mx-exam").start();
    }

    /**
     * The drill: lessons + the exam, repeated until a perfect paper (or the
     * retry cap). Each miss becomes a lesson, so — like a student drilling a
     * paper — the app is pushed to 100%. No points are paid for a drill
     * (practice); the single-shot {@code Exam} button is the one that pays.
     */
    private void doDrill() {
        LlmBrain brain = LlmBrain.get(this);
        final int tries = drillTries();
        final boolean hasModel = LlmBrain.hasModel(this);
        say("Drill: study + exam until perfect (" + tries + " max, "
                + (brain.ready() ? "with AI"
                   : hasModel ? "loading AI model..." : "deterministic") + ")...");
        new Thread(() -> {
            final Bootcamp.Outcome o =
                    Bootcamp.drill(MainActivity.this, brain, tries);
            final boolean escalated = !o.perfect() && !Repair.reportedRecently(MainActivity.this);
            if (escalated && o.finalReport != null) {
                Repair.reportExam(MainActivity.this,
                        "drill not perfect after " + o.tries() + " tries", o.finalReport);
            }
            ui.post(() -> {
                StringBuilder sb = new StringBuilder();
                for (Bootcamp.Attempt a : o.attempts) {
                    sb.append(a.line()).append("\n");
                }
                sb.append("\n").append(o.line()).append("\n");
                sb.append(o.lessonsLearned).append(" lesson(s) learned from mistakes.\n");
                sb.append("Practice drill \u2014 no points paid (use Exam to bank).");
                if (escalated) {
                    sb.append("\n\nRepair incident written:\n")
                            .append(Repair.dir(MainActivity.this)).append("/LATEST.md");
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Drill \u2014 " + (o.perfect() ? "PERFECT" : "incomplete"))
                        .setMessage(sb.toString())
                        .setPositiveButton("OK", null)
                        .show();
                Report.info("drill", o.line() + " | attempts=" + o.tries()
                        + " lessons=" + o.lessonsLearned
                        + " brain=" + (o.finalReport != null && o.finalReport.brainUsed));
                say(o.line());
                refreshStatus();
            });
        }, "mx-drill").start();
    }

    /** Drill attempts: {@code --ei tries N}, else the default (25). */
    private int drillTries() {
        int t = getIntent() == null
                ? Bootcamp.DEFAULT_TRIES
                : getIntent().getIntExtra("tries", Bootcamp.DEFAULT_TRIES);
        return t < 1 ? Bootcamp.DEFAULT_TRIES : t;
    }

    /**
     * Refresh the anime-title knowledge base from animeTosho (the ~2-3 month
     * cadence, or on demand), then study the new titles immediately so the
     * matcher learns any shorthands they unlock.
     */
    private void doTitles() {
        say("Refreshing anime titles from animeTosho...");
        new Thread(() -> {
            // refresh() studies and self-tests any new bulk of titles.
            final Catalogue.Result r = Catalogue.refresh(MainActivity.this, true);
            ui.post(() -> {
                StringBuilder sb = new StringBuilder(r.line);
                sb.append("\n").append(Catalogue.ageLine(MainActivity.this));
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Anime titles")
                        .setMessage(sb.toString())
                        .setPositiveButton("OK", null)
                        .show();
                refreshStatus();
            });
        }, "mx-titles").start();
    }

    /**
     * Run the app's own test (the graded exam, deterministically, no reward).
     * If it fails — too many mistakes — a repair incident is raised for the
     * desktop OpenCode watcher.
     */
    private void doTest() {
        say("Studying, then self-test...");
        new Thread(() -> {
            final Educate.Result study = Educate.study(MainActivity.this);
            final Selftest.Result t = Selftest.run();
            Selftest.record(MainActivity.this, t);
            final boolean escalated = !t.pass() && !Repair.reportedRecently(MainActivity.this);
            if (escalated) {
                Repair.report(MainActivity.this, "manual self-test failed", t, study.line());
            }
            ui.post(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append(study.line()).append("\n").append(t.line());
                if (escalated) {
                    sb.append("\n\nRepair incident written:\n")
                            .append(Repair.dir(MainActivity.this)).append("/LATEST.md");
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Self-test")
                        .setMessage(sb.toString())
                        .setPositiveButton("OK", null)
                        .show();
                refreshStatus();
            });
        }, "mx-test").start();
    }

    /**
     * Automatic MAL repair: withdraws bogus writes (pushed rows whose MX ink
     * carries NO real tag) back to plan_to_watch|0, keeping anything the user
     * rated or has since moved on. See MalRepair for the exact rules.
     */
    private void doRepair() {
        say("Repairing bogus MAL writes...");
        new Thread(() -> {
            final MalRepair.Result r = MalRepair.repairNow(MainActivity.this);
            ui.post(() -> {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("MAL repair")
                        .setMessage(r.line
                                + "\nshielded series: " + MalRepair.shieldedCount(MainActivity.this)
                                + "\n\n(revert log lines are in error-report.log)")
                        .setPositiveButton("OK", null)
                        .show();
                refreshStatus();
            });
        }, "mx-repair").start();
    }

    /** QA: plan the repair without writing anything to MAL or apps state. */
    private void doRepairDry() {
        say("Planning MAL repair (dry)...");
        new Thread(() -> {
            final MalRepair.Result r = MalRepair.dryRun(MainActivity.this);
            ui.post(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle("MAL repair (dry)")
                    .setMessage(r.line)
                    .setPositiveButton("OK", null)
                    .show());
        }, "mx-repair-dry").start();
    }

    private void doWipe() {
        new AlertDialog.Builder(this)
                .setTitle("Wipe bridge history?")
                .setMessage("Clears all observed rows, the watched ledger and the cached "
                        + "MAL snapshot. Aliases and the error log are kept. "
                        + "The automatic weekly purge will then count seven days "
                        + "from now.")
                .setPositiveButton("Wipe now", (d, w) -> {
                    Retention.init(this);
                    int n = Retention.purgeNow(this);
                    say("Wipe done: " + n + " rows cleared. " + Retention.statusLine() + ".");
                    adapter.notifyDataSetChanged();
                    refreshStatus();
                    MxService s = MxService.get();
                    if (s != null) s.refreshNotification();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doAi() {
        final LlmBrain brain = LlmBrain.get(this);
        if (brain.ready()) {
            say("AI: local model loaded and ready (priority matcher).");
            refreshStatus();
            return;
        }
        if (LlmBrain.hasModel(this)) {
            say("AI: model found on device, loading (CPU, may take ~30s)...");
            exec.execute(() -> {
                brain.ensureLoaded();
                say("AI: " + brain.status());
                onChanged();
            });
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Download local AI model?")
                .setMessage("Qwen2.5-0.5B-Instruct (~521 MB) is downloaded to app storage "
                        + "and becomes the priority title matcher: it decides every match "
                        + "(only ever among real MAL candidates — it can never invent an id). "
                        + "Wi-Fi recommended. The app works without it.")
                .setPositiveButton("Download", (d, w) -> downloadModel())
                .setNegativeButton("Cancel", (d, w) -> say("AI: kept deterministic matcher."))
                .show();
    }

    private void downloadModel() {
        say("AI: downloading model (~521 MB)...");
        exec.execute(() -> {
            File f = LlmBrain.modelFile(MainActivity.this);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            File tmp = new File(dir, "model.task.part");
            HttpURLConnection c = null;
            InputStream in = null;
            OutputStream out = null;
            try {
                c = (HttpURLConnection) new URL(MODEL_URL).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(30000);
                c.setReadTimeout(60000);
                c.connect();
                long total = c.getContentLengthLong();
                in = c.getInputStream();
                out = new FileOutputStream(tmp);
                byte[] buf = new byte[1 << 16];
                long got = 0, last = 0;
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    got += r;
                    if (got - last > (25L << 20)) {
                        last = got;
                        say("AI: " + (got >> 20) + " MB"
                                + (total > 0 ? " / " + (total >> 20) + " MB" : ""));
                    }
                }
                out.close();
                out = null;
                if (f.exists() && !f.delete()) {
                    say("AI: could not replace old model file.");
                }
                if (!tmp.renameTo(f)) {
                    say("AI: rename failed.");
                }
                say("AI: download complete, loading model...");
                LlmBrain.get(MainActivity.this).ensureLoaded();
                say("AI: " + LlmBrain.get(MainActivity.this).status());
            } catch (Throwable t) {
                Report.err("ai", "model download failed", t);
                say("AI: download failed: " + t.getMessage());
            } finally {
                try {
                    if (out != null) out.close();
                } catch (Exception ignored) {
                }
                try {
                    if (in != null) in.close();
                } catch (Exception ignored) {
                }
                if (c != null) c.disconnect();
            }
            onChanged();
        });
    }

    /**
     * Open the system Notification-access screen so the user can enable
     * {@link MediaWatchService}; that is what lets the bridge read MX Player's
     * MediaSession for real watch progress.
     */
    private void doWatchAccess() {
        boolean on = isWatchAccessEnabled();
        say("Watch progress reads MX's MediaSession (no screen capture)."
                + " Notification access is " + (on ? "ON." : "OFF - enable it below."));
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Throwable t) {
            Report.err("watch", "cannot open notification access", t);
            say("Open Settings > Notifications > Notification access manually.");
        }
    }

    /** True when this app's notification listener is enabled by the user. */
    private boolean isWatchAccessEnabled() {
        try {
            MediaSessionManager mgr = (MediaSessionManager)
                    getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (mgr == null) return false;
            mgr.getActiveSessions(new ComponentName(this, MediaWatchService.class));
            return true;
        } catch (SecurityException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Show the tail of the {@link WatchLog}: every watch observation, match and
     * MyAnimeList write, so the whole tracking flow is auditable on-device.
     */
    private void doWatchLog() {
        final String path = WatchLog.path();
        exec.execute(() -> {
            String tail = WatchLog.tail(200);
            ui.post(() -> new AlertDialog.Builder(this)
                    .setTitle("Watch log")
                    .setMessage(tail + "\n" + path)
                    .setPositiveButton("OK", null)
                    .show());
        });
    }

    private void doLogin() {
        if (MalClient.hasSession(this)) {
            say("Already logged in (Clear app data to sign out).");
            return;
        }
        say("MAL login: browser opening. Approve, wait for 'login OK', return here.");
        MalClient.startLogin(this, (err, ok) -> {
            if (err != null) {
                fail("Login: " + err, null);
            } else {
                say("Login OK.");
                exec.execute(() -> {
                    try {
                        say("Logged in as " + MalClient.me(MainActivity.this) + ".");
                    } catch (Exception e) {
                        Report.err("login", "profile check failed", e);
                        say("Token saved (profile check: " + e.getMessage() + ")");
                    }
                    onChanged();
                });
            }
            refreshStatus();
        });
    }

    private void doScan() {
        MxService s = MxService.get();
        if (s == null) {
            say("Service not running: Settings > Accessibility > MX-MAL Bridge > On,"
                    + " then open MX Player and tap Scan again.");
            refreshStatus();
            return;
        }
        int found = s.collect("manual");
        if (found > 0) {
            say("Scan: " + found + " MX row(s) read (stored: "
                    + Store.all().size() + ", read-only).");
        } else {
            say("Scan: no MX window reachable — MX only exposes the rows currently"
                    + " on screen, so open MX Player first then tap Scan"
                    + " (stored: " + Store.all().size() + ").");
        }
        refreshStatus();
    }

    private void doLibScan() {
        if (!LibraryScanner.canRead(this)) {
            requestMediaPermission();
            say("Library scan: grant video access, then tap Lib again (or it is auto-granted).");
            return;
        }
        say("Library scan: reading device videos (MediaStore, read-only)...");
        exec.execute(() -> {
            int n = LibraryScanner.scan(MainActivity.this);
            Planner.rebuild();
            Store.save();
            onChanged();
            say("Library scan: " + n + " anime series found on device."
                    + " Use Match then Plan to preview.");
        });
    }

    private void requestMediaPermission() {
        String perm = Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_VIDEO"
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{perm}, 102);
        }
    }

    private void doColors() {
        MxService s = MxService.get();
        if (s == null) {
            say("Service not running.");
            return;
        }
        say("Colors: one screen capture (MX must be in foreground, no selection mode)...");
        s.requestClassify();
    }

    /**
     * One tap, many functions: read MX Player's list, scan the whole device
     * video library, rebuild the plan and match every new title on MAL — and,
     * when Auto is on, push the changes. With Auto off it stops after matching
     * (dry-run). Every individual step is still available under More.
     */
    private void doSync() {
        MxService s = MxService.get();
        // collect() touches accessibility nodes: it must run on the main thread.
        final int mxRows = s == null ? 0 : s.collect("sync");
        if (!LibraryScanner.canRead(this)) {
            say("Sync: MX " + mxRows + " row(s); library scan needs video access.");
            requestMediaPermission();
            refreshStatus();
            return;
        }
        final boolean loggedIn = MalClient.hasSession(this);
        final boolean auto = Store.autoApply();
        say("Sync: MX " + mxRows + " row(s) + library scan, then plan/match"
                + (auto ? "/apply..." : " (dry-run)..."));
        exec.execute(() -> {
            int lib = LibraryScanner.scan(this);
            Planner.rebuild();
            int ok = 0, miss = 0;
            if (loggedIn) {
                List<Store.Row> targets = matchTargets();
                if (!targets.isEmpty()) {
                    int[] r = matchAll(targets);
                    ok = r[0];
                    miss = r[1];
                }
            }
            Planner.rebuild();
            Store.save();
            AliasStore.persist(this);
            onChanged();
            final int fok = ok, fmiss = miss, flib = lib;
            ui.post(() -> {
                WatchLog.event("sync", "library " + flib + " series, matched " + fok
                        + ", missed " + fmiss + ", rows " + Store.all().size()
                        + (auto ? " (apply)" : " (dry-run)"));
                say("Sync: library " + flib + " series, matched " + fok
                        + (fmiss > 0 ? " (" + fmiss + " missed)" : "")
                        + ". Stored rows: " + Store.all().size() + ".");
                if (!loggedIn) {
                    say("Sync: not logged in to MAL — matched nothing. Use More > Login.");
                } else if (auto) {
                    List<Store.Row> q = pendingQueue();
                    if (q.isEmpty()) say("Sync: nothing to push (already up to date).");
                    else runApply(q);
                } else {
                    say("Sync: dry-run — tap Apply to review and push.");
                }
            });
        });
    }

    /** Rows worth searching on MAL: parsed, un-matched, and not unknown/none. */
    private List<Store.Row> matchTargets() {
        List<Store.Row> targets = new ArrayList<>();
        for (Store.Row r : Store.all()) {
            if (r.matchId < 0 && !r.parsedTitle.isEmpty()
                    && !("unknown".equals(r.state) || "none".equals(r.state))) {
                targets.add(r);
            }
        }
        return targets;
    }

    /**
     * Search + match every target on the calling thread.
     *
     * @return {@code {ok, missed}} counts
     */
    private int[] matchAll(List<Store.Row> targets) {
        int ok = 0, miss = 0;
        for (final Store.Row r : targets) {
            try {
                String query = Aliases.apply(r.parsedTitle);
                JSONArray data = MalClient.search(this, query);
                int results = data == null ? 0 : data.length();
                Matcher.Result b = Matcher.bestWithBrain(data, query, r.ep,
                        LlmBrain.get(this));
                r.matchTried = r.parsedTitle;
                if (b != null) {
                    r.matchId = b.id;
                    r.matchTitle = b.title;
                    r.numEp = b.numEp;
                    Aliases.learn(r.parsedTitle, b.title);
                    ok++;
                    WatchLog.match(r.parsedTitle, MalClient.cleanQuery(query), b.title, b.id, results);
                    say("match: '" + r.parsedTitle + "' -> " + b.title + " (" + b.id
                            + (b.numEp > 0 ? ", " + b.numEp + " eps" : "") + ")");
                } else {
                    miss++;
                    WatchLog.miss(r.parsedTitle, MalClient.cleanQuery(query), results, r.ep);
                    say("no match: " + r.parsedTitle);
                }
            } catch (Exception e) {
                miss++;
                Report.err("match", "search failed for '" + r.parsedTitle + "'", e);
                WatchLog.searchError(r.parsedTitle, e.getMessage());
                say("search error: " + e.getMessage());
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
            }
        }
        return new int[]{ok, miss};
    }

    private void doMatch() {
        if (!MalClient.hasSession(this)) {
            say("Match: log in to MAL first.");
            return;
        }
        Planner.rebuild();
        final List<Store.Row> targets = matchTargets();
        if (targets.isEmpty()) {
            say("Match: nothing to match (run Scan then Plan first).");
            onChanged();
            return;
        }
        say("Match: searching " + targets.size() + " titles...");
        exec.execute(() -> {
            int[] r = matchAll(targets);
            Planner.rebuild(); // plans can now use num_episodes
            Store.save();
            AliasStore.persist(this);
            onChanged();
            say("Match done: " + r[0] + " ok, " + r[1] + " missed.");
        });
    }

    /**
     * A row is only "pending" when pushing it would actually CHANGE something
     * on MAL: it has a plan, a real match, a concrete episode count for
     * "completed", and the plan differs from what we last wrote
     * ({@code applied}). A row whose plan already equals {@code applied} is in
     * sync — counting it as "ready to send" made the header read "53 ready"
     * while nothing was actually queued (2026-10-14).
     */
    private static boolean needsPush(Store.Row r) {
        if (r.planStatus.isEmpty()) return false;
        if (r.matchId <= 0) return false;
        if ("completed".equals(r.planStatus) && r.planEps < 0) return false;
        return !(r.planStatus + "|" + r.planEps).equals(r.applied);
    }

    /** Rows whose plan can be applied now (matched, and not a bogus completion). */
    private List<Store.Row> pendingQueue() {
        List<Store.Row> q = new ArrayList<>();
        for (Store.Row r : Store.all()) {
            if (needsPush(r)) q.add(r);
        }
        return q;
    }

    private void doPlan() {
        Planner.rebuild();
        Store.save();
        int n = 0;
        StringBuilder sb = new StringBuilder();
        for (Store.Row r : Store.all()) {
            if (!r.planStatus.isEmpty()) {
                n++;
                if (sb.length() < 400) {
                    sb.append("  ").append(shortName(r.title)).append(": ")
                            .append(r.planText).append("\n");
                }
            }
        }
        onChanged();
        say("Plan (dry-run): " + n + " row(s) have an action:");
        if (sb.length() > 0) say(sb.toString().trim());
    }

    private void doApply() {
        final List<Store.Row> q = pendingQueue();
        int unmatched = 0;
        int withPlan = 0;
        for (Store.Row r : Store.all()) {
            if (r.planStatus.isEmpty()) continue;
            withPlan++;
            boolean safe = r.matchId > 0
                    && !("completed".equals(r.planStatus) && r.planEps < 0);
            if (!safe) unmatched++;
        }
        if (q.isEmpty()) {
            if (withPlan == 0) {
                say("Apply: nothing ready. Run Scan > Plan > Match first.");
            } else {
                say("Apply: nothing to send \u2014 all " + withPlan
                        + " planned row(s) already match MyAnimeList"
                        + (unmatched > 0 ? " (" + unmatched + " have no MAL match)" : "")
                        + ".");
            }
            return;
        }
        StringBuilder msg = new StringBuilder();
        for (int i = 0; i < q.size() && i < 12; i++) {
            Store.Row r = q.get(i);
            msg.append("\u2022 ").append(shortName(r.title)).append(": ")
                    .append(r.planText).append("\n");
        }
        if (q.size() > 12) msg.append("\u2026 and ").append(q.size() - 12).append(" more\n");
        if (unmatched > 0) msg.append("\n(").append(unmatched).append(" skipped: no MAL match)");
        new AlertDialog.Builder(this)
                .setTitle("Apply " + q.size() + " change(s) to MyAnimeList?")
                .setMessage(msg.toString())
                .setPositiveButton("Apply", (d, w) -> runApply(q))
                .setNegativeButton("Cancel", (d, w) -> say("Apply cancelled - still dry-run."))
                .show();
    }

    private void runApply(final List<Store.Row> q) {
        say("Applying " + q.size() + " change(s)...");
        exec.execute(() -> {
            Map<Integer, String> cur = null;
            try {
                cur = MalClient.myListStatus(MainActivity.this);
            } catch (Exception e) {
                Report.err("apply", "MAL list guard unavailable", e);
            }
            int ok = 0, err = 0, held = 0;
            for (final Store.Row r : q) {
                String sig = r.planStatus + "|" + r.planEps;
                String mal = cur == null ? null : cur.get(r.matchId);
                if (mal == null) {
                    // Not in the list snapshot: read this one directly before any
                    // write, so a short/partial read can never overwrite it.
                    String one = MalClient.myStatusFor(MainActivity.this, r.matchId);
                    if (one == null) {
                        err++;
                        say("\u2717 " + shortName(r.title)
                                + ": couldn't read MAL status; skipped");
                        continue;
                    }
                    if (!one.isEmpty()) mal = one;
                }
                if (mal != null && Planner.wouldDowngrade(mal,
                        r.planStatus, r.planEps)) {
                    r.applied = sig; // remember it; never retry this downgrade
                    held++;
                    WatchLog.hold(r.matchId, r.planStatus, r.planEps);
                    Store.save();
                    say("\u21ba " + shortName(r.title) + ": kept MAL value (would downgrade)");
                    continue;
                }
                try {
                    MalClient.updateStatus(MainActivity.this, r.matchId, r.planStatus, r.planEps);
                    r.applied = sig; // background won't re-push
                    Store.save();
                    ok++;
                    WatchLog.push(r.matchId, r.planStatus, r.planEps, true, r.matchTitle);
                    say("\u2713 " + shortName(r.title) + " -> " + r.planStatus
                            + (r.planEps >= 0 ? " (" + r.planEps + " eps)" : ""));
                } catch (Exception e) {
                    err++;
                    Report.err("apply", "update failed for '" + r.title + "'", e);
                    WatchLog.push(r.matchId, r.planStatus, r.planEps, false, e.getMessage());
                    say("\u2717 " + shortName(r.title) + ": " + e.getMessage());
                }
                try {
                    Thread.sleep(700);
                } catch (InterruptedException ignored) {
                }
            }
            Store.notifyChanged();
            say("Apply done: " + ok + " ok, " + err + " failed, " + held + " held.");
        });
    }

    // ------------------------------------------------------------- helpers

    private void refreshStatus() {
        boolean live = MxService.get() != null;
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean svcOn = enabled != null
                && enabled.contains(getPackageName() + "/com.bridge.mx.MxService");
        boolean loggedIn = MalClient.hasSession(this);
        int rows = Store.all().size();
        int planned = 0;
        for (Store.Row r : Store.all()) {
            if (needsPush(r)) planned++;
        }
        boolean auto = Store.autoApply();

        // Headline: the one thing the user should know, in plain words.
        String head;
        if (!live && !svcOn) {
            head = "\u26A0  Turn on the accessibility service";
        } else if (!loggedIn) {
            head = "\u26A0  Sign in to MyAnimeList";
        } else if (planned > 0 && !auto) {
            head = planned + (planned == 1 ? " change is ready" : " changes are ready");
        } else {
            head = "All set";
        }
        statusHead.setText(head);

        statusLine.setText(rows + (rows == 1 ? " episode tracked" : " episodes tracked")
                + (planned > 0 ? "  \u00B7  " + planned + " ready to send" : ""));

        if (auto) {
            statusAuto.setText("Auto-update is ON \u2014 new watches are written to "
                    + "MyAnimeList automatically.");
            statusAuto.setTextColor(0xFF1B5E20);
        } else {
            statusAuto.setText("Auto-update is OFF \u2014 nothing is written until you "
                    + "tap \u201CApply to MAL\u201D.");
            statusAuto.setTextColor(0xFF8A6D00);
        }

        // Raw numbers, small and dim, for anyone who wants them.
        String sync = "";
        MxService s = MxService.get();
        if (s != null) {
            long in = s.nextRunInMs();
            if (in > 0) sync = " | next auto-sync " + ((in + 59_000) / 60_000) + "m";
        }
        statusDetails.setText("service " + (live ? "live" : (svcOn ? "enabled-idle" : "off"))
                + " | MAL " + (loggedIn ? "signed in" : "signed out")
                + " | ai " + LlmBrain.get(this).status()
                + " | " + Retention.statusLine()
                + " | " + Catalogue.ageLine(this)
                + " | " + Selftest.statusLine(this)
                + sync);

        btnApply.setText(planned > 0 ? "Apply to MAL (" + planned + ")" : "Apply to MAL");
        btnAuto.setText(auto ? "Auto: ON" : "Auto: OFF");
    }

    private String safeReportPath() {
        try {
            Report.init(this);
            return Report.path();
        } catch (Throwable t) {
            return "(unavailable)";
        }
    }

    /** Show an error on screen AND persist it to error-report.log. */
    private void fail(String msg, Throwable t) {
        Report.err("ui", msg, t);
        say(msg);
    }

    private void say(final String s) {
        ui.post(() -> {
            logView.append(s + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    /** Friendly wording for the internal row states. */
    private static String stateWord(String state) {
        if ("finished".equals(state)) return "Watched";
        if ("watching".equals(state)) return "Watching";
        if ("new".equals(state)) return "Not watched yet";
        if ("present".equals(state)) return "On device";
        if ("none".equals(state)) return "On device (no label)";
        return "Not classified yet";
    }

    private static String shortName(String s) {
        return s.length() > 46 ? s.substring(0, 44) + "\u2026" : s;
    }

    // -------------------------------------------------------------- adapter
    // Rows are also built from code (no row XML).

    private static class Holder {
        TextView title, meta, plan;
    }

    private class EpisodeAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return Store.all().size();
        }

        @Override
        public Object getItem(int position) {
            return Store.all().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            Holder h;
            if (v == null) {
                float d = getResources().getDisplayMetrics().density;
                int p6 = (int) (6 * d), p8 = (int) (8 * d);

                LinearLayout ll = new LinearLayout(MainActivity.this);
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setPadding(p6, p8, p6, p8);

                h = new Holder();
                h.title = new TextView(MainActivity.this);
                h.title.setTextSize(14);
                h.meta = new TextView(MainActivity.this);
                h.meta.setTextSize(12);
                h.meta.setTextColor(0xFF666666);
                h.meta.setPadding(0, (int) (2 * d), 0, 0);
                h.plan = new TextView(MainActivity.this);
                h.plan.setTextSize(12);
                h.plan.setTextColor(0xFF1B5E20);
                h.plan.setGravity(Gravity.START);
                h.plan.setPadding(0, (int) (2 * d), 0, 0);
                h.plan.setVisibility(View.GONE);

                ll.addView(h.title, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                ll.addView(h.meta, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                ll.addView(h.plan, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

                v = ll;
                v.setTag(h);
            } else {
                h = (Holder) v.getTag();
            }

            Store.Row r = Store.all().get(position);
            if (!r.parsedTitle.isEmpty()) {
                h.title.setText(r.parsedTitle + (r.ep > 0 ? "  \u00B7  episode " + r.ep : ""));
            } else {
                h.title.setText(r.title);
            }
            StringBuilder meta = new StringBuilder(stateWord(r.state));
            if (!r.duration.isEmpty()) meta.append("  \u00B7  ").append(r.duration);
            if (r.watchedPct >= 0) meta.append("  \u00B7  ").append(r.watchedPct).append("% played");
            if (!r.playedAt.isEmpty()) meta.append("  \u00B7  ").append(r.playedAt);
            if (r.matchId > 0) {
                meta.append("\nMyAnimeList: ").append(r.matchTitle);
                if (r.numEp > 0) meta.append(" (").append(r.numEp).append(" eps)");
            } else if (!r.parsedTitle.isEmpty()) {
                meta.append("\nMyAnimeList: not matched yet");
            }
            // The raw filename last, so unusual (e.g. Arabic) names do not
            // dominate the row.
            if (!r.title.equals(h.title.getText().toString())) {
                meta.append("\n").append(r.title);
            }
            h.meta.setText(meta.toString());

            if (!r.planText.isEmpty()) {
                h.plan.setVisibility(View.VISIBLE);
                String pt = r.planText;
                String text;
                if (pt.startsWith("\u2192 ")) {
                    String body = pt.substring(2);
                    if (body.startsWith("kept")) {
                        // Deliberate no-op: MAL is ahead, we never downgrade.
                        String detail = body.substring(4);
                        if (detail.startsWith(",")) detail = detail.substring(1).trim();
                        text = "\u2713 Kept as-is" + (detail.isEmpty() ? "" : ": " + detail);
                    } else if (needsPush(r)) {
                        text = "Will send to MAL: " + body;
                    } else {
                        // Already written — do not say "will send" (it won't).
                        text = "\u2713 In sync: " + body;
                    }
                } else {
                    text = pt;
                }
                h.plan.setText(text);
            } else {
                h.plan.setVisibility(View.GONE);
            }
            return v;
        }
    }
}
