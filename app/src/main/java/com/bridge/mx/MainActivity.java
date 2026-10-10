package com.bridge.mx;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
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
 * Entire UI is built from code (no layout XML): a status line, a horizontal
 * button strip, the observed-episode list and a scrolling log. The activity
 * is a control panel / dry-run view; the real work happens in MxService.
 */
public class MainActivity extends Activity implements Store.Listener {

    private TextView status, logView;
    private ListView list;
    private ScrollView logScroll;
    private Button btnAuto;
    private EpisodeAdapter adapter;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private Handler ui;

    /** Small on-device model used as a grounded match fallback. */
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

        say("MX-MAL Bridge v1 - declared made with AI (see README).");
        say("Flow: open MX list > Scan > Colors > Plan > Match > Apply.");
        say("Background: service auto-scans MX; network sync every "
                + (MxService.PIPELINE_PERIOD_MS / 60000) + " min. Auto toggle = live writes.");
        say("AI: grounded fallback, runs ONLY on unmatched titles. Tap AI to load a small"
                + " on-device model (optional; app works without it).");
        say("Errors: " + safeReportPath());
        refreshStatus();
    }

    // ------------------------------------------------------- UI from code

    private void buildUi() {
        float d = getResources().getDisplayMetrics().density;
        int p8 = (int) (8 * d), p4 = (int) (4 * d), p6 = (int) (6 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p8, p8, p8, p8);

        // status line
        status = new TextView(this);
        status.setTextSize(13);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(0, 0, 0, p4);
        root.addView(status, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // horizontal button strip
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.addView(btn("Login", v -> doLogin()));
        strip.addView(btn("Scan", v -> doScan()));
        strip.addView(btn("Lib", v -> doLibScan()));
        strip.addView(btn("Colors", v -> doColors()));
        strip.addView(btn("Match", v -> doMatch()));
        strip.addView(btn("Plan", v -> doPlan()));
        strip.addView(btn("Apply", v -> doApply()));
        btnAuto = btn("Auto: off", v -> doAuto());
        strip.addView(btnAuto);
        strip.addView(btn("AI", v -> doAi()));
        hs.addView(strip, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(hs, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // observed episodes
        list = new ListView(this);
        adapter = new EpisodeAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // log section
        TextView logLabel = new TextView(this);
        logLabel.setText("Log");
        logLabel.setTextSize(12);
        logLabel.setTypeface(Typeface.DEFAULT_BOLD);
        logLabel.setPadding(0, p4, 0, 0);
        root.addView(logLabel, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(0xFFF2F2F2);
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(p4, p4, p4, p4);
        logScroll.addView(logView, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (130 * d)));

        setContentView(root);
    }

    private Button btn(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
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
                ? "Auto-sync ON: label changes will be pushed to MAL on the next sync tick."
                : "Auto-sync OFF: dry-run only (manual Apply still asks first).");
        MxService s = MxService.get();
        if (s != null) s.refreshNotification();
        refreshStatus();
    }

    private void doAi() {
        final LlmBrain brain = LlmBrain.get(this);
        if (brain.ready()) {
            say("AI: local model loaded and ready (grounded match fallback).");
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
                        + "and used only as a grounded fallback when title matching is "
                        + "ambiguous. Wi-Fi recommended. The app works without it.")
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
                    + " then open the MX list and tap Scan again.");
            refreshStatus();
            return;
        }
        s.collect("manual");
        say("Scan: " + Store.all().size() + " rows read from MX (read-only).");
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

    private void doMatch() {
        if (!MalClient.hasSession(this)) {
            say("Match: log in to MAL first.");
            return;
        }
        Planner.rebuild();
        final List<Store.Row> targets = new ArrayList<>();
        for (Store.Row r : Store.all()) {
            if (r.matchId < 0 && !r.parsedTitle.isEmpty()
                    && !("unknown".equals(r.state) || "none".equals(r.state))) {
                targets.add(r);
            }
        }
        if (targets.isEmpty()) {
            say("Match: nothing to match (run Scan then Plan first).");
            onChanged();
            return;
        }
        say("Match: searching " + targets.size() + " titles...");
        exec.execute(() -> {
            int ok = 0, miss = 0;
            for (final Store.Row r : targets) {
                try {
                    String query = Aliases.apply(r.parsedTitle);
                    JSONArray data = MalClient.search(MainActivity.this, query);
                    Matcher.Result b = Matcher.bestWithBrain(data, query, r.ep,
                            LlmBrain.get(MainActivity.this));
                    r.matchTried = r.parsedTitle;
                    if (b != null) {
                        r.matchId = b.id;
                        r.matchTitle = b.title;
                        r.numEp = b.numEp;
                        Aliases.learn(r.parsedTitle, b.title);
                        ok++;
                        say("match: '" + r.parsedTitle + "' -> " + b.title + " (" + b.id
                                + (b.numEp > 0 ? ", " + b.numEp + " eps" : "") + ")");
                    } else {
                        miss++;
                        say("no match: " + r.parsedTitle);
                    }
                } catch (Exception e) {
                    miss++;
                    Report.err("match", "search failed for '" + r.parsedTitle + "'", e);
                    say("search error: " + e.getMessage());
                }
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                }
            }
            Planner.rebuild(); // plans can now use num_episodes
            Store.save();
            AliasStore.persist(MainActivity.this);
            onChanged();
            say("Match done: " + ok + " ok, " + miss + " missed.");
        });
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
        final List<Store.Row> q = new ArrayList<>();
        int unmatched = 0;
        for (Store.Row r : Store.all()) {
            if (r.planStatus.isEmpty()) continue;
            boolean safe = r.matchId > 0
                    && !("completed".equals(r.planStatus) && r.planEps < 0);
            if (safe) q.add(r);
            else unmatched++;
        }
        if (q.isEmpty()) {
            say("Apply: nothing ready. Run Scan > Plan > Match first.");
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
                if (cur != null && Planner.wouldDowngrade(cur.get(r.matchId),
                        r.planStatus, r.planEps)) {
                    r.applied = sig; // remember it; never retry this downgrade
                    held++;
                    Store.save();
                    say("\u21ba " + shortName(r.title) + ": kept MAL value (would downgrade)");
                    continue;
                }
                try {
                    MalClient.updateStatus(MainActivity.this, r.matchId, r.planStatus, r.planEps);
                    r.applied = sig; // background won't re-push
                    Store.save();
                    ok++;
                    say("\u2713 " + shortName(r.title) + " -> " + r.planStatus
                            + (r.planEps >= 0 ? " (" + r.planEps + " eps)" : ""));
                } catch (Exception e) {
                    err++;
                    Report.err("apply", "update failed for '" + r.title + "'", e);
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
        String svc = live ? "live" : (svcOn ? "enabled-idle" : "OFF");
        int planned = 0;
        for (Store.Row r : Store.all()) {
            if (!r.planStatus.isEmpty()) planned++;
        }
        String sync = "";
        MxService s = MxService.get();
        if (s != null) {
            long in = s.nextRunInMs();
            if (in > 0) sync = " | sync in " + ((in + 59_000) / 60_000) + "m";
        }
        status.setText("rows " + Store.all().size()
                + " | service " + svc
                + " | MAL " + (MalClient.hasSession(this) ? "in" : "out")
                + " | planned " + planned
                + " | auto " + (Store.autoApply() ? "ON" : "off")
                + " | ai " + LlmBrain.get(this).status()
                + sync);
        btnAuto.setText(Store.autoApply() ? "Auto: ON" : "Auto: off");
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
            h.title.setText(r.title);

            StringBuilder meta = new StringBuilder(r.state);
            if (r.ep > 0) meta.append(" | ep ").append(r.ep);
            if (!r.duration.isEmpty()) meta.append(" | ").append(r.duration);
            if (r.matchId > 0) {
                meta.append("\nMAL: ").append(r.matchTitle);
                if (r.numEp > 0) meta.append(" (").append(r.numEp).append(" eps)");
            }
            h.meta.setText(meta.toString());

            if (!r.planText.isEmpty()) {
                h.plan.setVisibility(View.VISIBLE);
                h.plan.setText(r.planText);
            } else {
                h.plan.setVisibility(View.GONE);
            }
            return v;
        }
    }
}
