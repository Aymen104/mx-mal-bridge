package com.bridge.mx;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Automatic repair of bogus MAL writes.
 *
 * <p>Origin: a color-classifier bug pushed watch progress to the user's live
 * MAL account for rows that carried no genuine watch evidence. MAL has no undo,
 * and the account is the user's proof-of-watch, so the repair is deliberately
 * conservative.
 *
 * <p>IMPORTANT (2026-10-13): the original trigger — "pushed + neutral grey +
 * no playback" — proved to be a FALSE signature. The grey ink is MX's real
 * <em>finished</em> tag on 3.3.1, and the user confirmed all 18 such reverts
 * were genuine watches. A row that carries a real tag
 * ({@link ColorClassify#hasTag}) is therefore never an artifact.
 *
 * <p>IMPORTANT (2026-10-14): the follow-on trigger — "pushed + untagged ink +
 * no playback" — was ALSO a false signature. Six rows it withdrew (Gensou
 * Suikoden, FX Senshi Kurumi-chan, Tsuihou sareta Tensei Juukishi, Hotaru no
 * Yomeiri, Sora wa Akai Kawa no Hotori, Shuiro no Kamen) were all shows the
 * user was actively watching; MX simply paints no tag on a row it is mid-way
 * through. The repair is therefore narrowed to its one PROVEN fabrication:
 * a {@code fromLib} row — one the MX list reader never saw, whose only claim
 * to progress was file presence (the "downloaded is not watched" case).
 *
 * <ul>
 *   <li>Only series whose rows are STILL the artifact signature qualify:
 *       pushed ({@code applied != ""}), library-only ({@code fromLib}), with
 *       no playback evidence (row watched %, watched ledger, real tag or a
 *       last-played date). An MX-observed row is never a candidate.
 *   <li>A write is withdrawn only when MAL currently holds EXACTLY what we
 *       pushed (same status and episode count) and the entry has no user
 *       score ({@code score == 0}). Anything the user rated or has since
 *       moved on is left untouched.
 *   <li>Withdrawing = DELETE the entry from the user's list (MAL v2
 *       {@code DELETE /anime/{id}/my_list_status}). Parking it as
 *       {@code plan_to_watch|0} still left the fabricated show "showing" on
 *       the account, which the user reported on 2026-10-14; a fabricated
 *       entry is removed outright.
 *   <li>Reverted ids are remembered; the bridge will not re-push them until
 *       real evidence appears (playback or a real tag), which clears the
 *       mark. Rows are also neutralized to {@code none}/{@code plan_to_watch|0}.
 * </ul>
 *
 * <p>Runs automatically once a day (see {@link #autoRepair}); {@link #dryRun}
 * is the QA hook that plans without writing.
 */
public final class MalRepair {

    private MalRepair() {
    }

    /** Auto-cooldown between repair passes. */
    static final long COOLDOWN_MS = 86_400_000L;

    private static final String NAME = "mxrepair";

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static final class Result {
        public final boolean ok;
        public final String line;

        Result(boolean ok, String line) {
            this.ok = ok;
            this.line = line;
        }
    }

    /** Keep/withdraw verdict for one candidate id. */
    enum Action {
        REVERT,      // delete the fabricated entry from the user's list
        KEEP_RATED,  // user gave it a score -> genuine
        KEEP_MOVED,  // user has since changed status/episodes -> hands off
        KEEP_ABSENT, // not on the MAL list anymore
        KEEP_UNKNOWN // not pushed by us / unparseable -> skip
    }

    /**
     * Pure verdict for one candidate given the current MAL value.
     *
     * @param current MAL "status|eps|score" or null when not on the list
     * @param pushed  the exact "status|eps" we last wrote for the series, or ""
     * @return the action to take
     */
    static Action decide(String current, String pushed) {
        if (current == null) return Action.KEEP_ABSENT;
        String[] c = current.split("\\|", -1);
        if (c.length < 3) return Action.KEEP_UNKNOWN;
        int curEps, curScore;
        try {
            curEps = Integer.parseInt(c[1]);
            curScore = Integer.parseInt(c[2]);
        } catch (NumberFormatException e) {
            return Action.KEEP_UNKNOWN;
        }
        if (curScore > 0) return Action.KEEP_RATED;
        if (pushed == null || pushed.isEmpty()) return Action.KEEP_UNKNOWN;
        String[] p = pushed.split("\\|", -1);
        if (!c[0].equals(p[0])) return Action.KEEP_MOVED;
        if (p.length >= 2) {
            try {
                int pushedEps = Integer.parseInt(p[1]);
                if (pushedEps >= 0 && curEps != pushedEps) return Action.KEEP_MOVED;
            } catch (NumberFormatException ignored) {
                // non-numeric pushed eps: trust the status match alone
            }
        }
        return Action.REVERT;
    }

    /**
     * Is this row the artifact signature? Pushed, a library-only row
     * ({@code fromLib}: never seen by the MX list reader), with no playback
     * evidence on the row itself.
     *
     * <p>An MX-observed row ({@code !fromLib}) is NEVER an artifact: the MX
     * list is the user's own playing record, and an untagged ink simply means
     * MX painted no label — not that the watch is fake (2026-10-14, after six
     * actively-watched shows were wrongly withdrawn). Real evidence (a grey
     * finished tag, blue played ink, a last-played date, playback %, or the
     * watched ledger) also rules a row out.
     *
     * <p>Note this is row-level evidence; {@link #candidates} additionally
     * drops an id when a SIBLING row of the same series carries evidence —
     * such a series is genuinely being watched and must never be withdrawn.
     *
     * @param ledger the watched-ledger snapshot ({@code "norm title|ep" -> pct})
     */
    static boolean isArtifact(Store.Row r, Map<String, Integer> ledger) {
        if (r.matchId <= 0) return false;
        if (r.applied == null || r.applied.isEmpty()) return false;
        if ("plan_to_watch|0".equals(r.applied)) return false;
        if (r.watchedPct >= 0) return false;                        // real playback
        if (ColorClassify.hasTag(r.rgb)) return false;              // real tag -> genuine
        if (r.playedAt != null && !r.playedAt.isEmpty()) return false; // MX last-played
        if (matchesLedger(ledger, r.parsedTitle) || matchesLedger(ledger, r.matchTitle)) {
            return false;                                           // watched ledger has it
        }
        // ONLY a library-only row (fromLib) can be an artifact: the MX list
        // reader never saw it, so its only claim to progress was file presence.
        // Under the "downloaded is not watched" rule (2026-10-14) any watch
        // progress we pushed from it is bogus.
        //
        // An MX-observed row is TRUSTED, whatever its ink. The MX list is the
        // user's own playing record: on 2026-10-14 the user confirmed six
        // "untagged ink" rows the repair had withdrawn (Gensou Suikoden,
        // FX Senshi Kurumi-chan, Tsuihou…, Hotaru no Yomeiri, Sora wa Akai
        // Kawa no Hotori, Shuiro no Kamen) were all shows they were actively
        // watching. MX paints no tag for a row it is mid-way through, so an
        // untagged MX row is NOT evidence of a bogus push. Never revert one.
        return r.fromLib;
    }

    /** Any positive watch evidence on one row: playback, a real tag, a
     *  last-played date, or the watched ledger. */
    static boolean evidence(Store.Row o, Map<String, Integer> ledger) {
        if (o.watchedPct >= 0) return true;
        if (o.rgb != -1 && ColorClassify.hasTag(o.rgb)) return true;
        if (o.playedAt != null && !o.playedAt.isEmpty()) return true;
        if (matchesLedger(ledger, o.parsedTitle) || matchesLedger(ledger, o.matchTitle)) {
            return true;
        }
        return false;
    }

    static boolean seriesHasEvidence(List<Store.Row> rows, int matchId, Map<String, Integer> ledger) {
        for (Store.Row r : rows) {
            if (r.matchId == matchId && evidence(r, ledger)) return true;
        }
        return false;
    }

    /** True when any row of the series still carries the artifact signature. */
    static boolean hasArtifactRow(List<Store.Row> rows, int matchId, Map<String, Integer> ledger) {
        for (Store.Row r : rows) {
            if (r.matchId == matchId && isArtifact(r, ledger)) return true;
        }
        return false;
    }

    private static boolean matchesLedger(Map<String, Integer> ledger, String title) {
        if (ledger == null || ledger.isEmpty()) return false;
        if (title == null || title.isEmpty()) return false;
        String pre = Watched.norm(title) + "|";
        for (String k : ledger.keySet()) {
            if (k.startsWith(pre)) return true;
        }
        return false;
    }

    /**
     * Distinct candidate series ids among the given rows, after dropping any
     * series that has real watch evidence on a sibling row.
     */
    static List<Integer> candidates(List<Store.Row> rows, Map<String, Integer> ledger) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Store.Row r : rows) {
            if (isArtifact(r, ledger)) ids.add(r.matchId);
        }
        Iterator<Integer> it = ids.iterator();
        while (it.hasNext()) {
            int id = it.next();
            if (seriesHasEvidence(rows, id, ledger)) it.remove(); // genuine series
        }
        return new ArrayList<>(ids);
    }

    /** The exact pushed signature for a series (highest episode count wins). */
    static String pushedApplied(List<Store.Row> rows, int matchId) {
        String best = null;
        int bestEps = -1;
        for (Store.Row r : rows) {
            if (r.matchId != matchId || r.applied == null || r.applied.isEmpty()) continue;
            int e = -1;
            String[] p = r.applied.split("\\|", -1);
            try {
                e = Integer.parseInt(p[p.length - 1]);
            } catch (Exception ignored) {
            }
            if (e > bestEps) {
                bestEps = e;
                best = r.applied;
            }
        }
        return best == null ? "" : best;
    }

    /** Automatic pass with a once-a-day cooldown. Blocking; worker thread only. */
    public static Result autoRepair(Context c) {
        return repair(c, false, false);
    }

    /** Explicit/QA pass: no cooldown — repairs immediately. Blocking; worker thread only. */
    public static Result repairNow(Context c) {
        return repair(c, false, true);
    }

    /** QA hook: plan the repair with no network writes and no state change. */
    public static Result dryRun(Context c) {
        return repair(c, true, true);
    }

    static Result repair(Context c, boolean dry, boolean force) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (!dry && !force) {
            if (now - p.getLong("last_try", 0) < COOLDOWN_MS) {
                return new Result(false, "mal repair: not due yet");
            }
            p.edit().putLong("last_try", now).apply();
        }
        if (!dry && force) {
            p.edit().putLong("last_try", now).apply();
        }
        if (!MalClient.hasSession(c)) return new Result(false, "mal repair: not logged in");

        List<Store.Row> rows = Store.all();
        Map<String, Integer> ledger = Watched.snapshot();
        List<Integer> ids = candidates(rows, ledger);
        if (!dry) {
            // Self-heal: a series we withdrew earlier that now shows real
            // evidence (the user watched it, or tagged it) is un-shielded and
            // its rows restored, so the bridge serves it normally again.
            //
            // A shield is ALSO stale once the series no longer matches the
            // artifact signature at all — e.g. the 2026-10-14 rows behind
            // shields that were only ever MX-observed, which the narrowed rule
            // (library-only) no longer considers artifacts. Dropping those lets
            // the shows track normally again.
            for (String k : new ArrayList<>(p.getAll().keySet())) {
                if (!k.startsWith("r:")) continue;
                try {
                    int id = Integer.parseInt(k.substring(2));
                    if (seriesHasEvidence(rows, id, ledger) || !hasArtifactRow(rows, id, ledger)) {
                        unshield(c, id);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            rows = Store.all(); // unshield may have restored rows
        }
        if (ids.isEmpty()) {
            if (!dry) p.edit().putLong("last_run", now).apply();
            return new Result(true, "mal repair: nothing to repair");
        }

        Map<Integer, String> cur = MalClient.myListScores(c); // id -> "status|eps|score"
        int reverted = 0, keptRated = 0, keptMoved = 0, absent = 0, keptUnknown = 0;
        for (int id : ids) {
            String pushed = pushedApplied(rows, id);
            Action a = decide(cur.get(id), pushed);
            Report.info("malrepair", "candidate " + id + " pushed=" + pushed
                    + " mal=" + cur.get(id) + " -> " + a);
            switch (a) {
                case REVERT:
                    if (!dry) revert(c, id, pushed);
                    reverted++;
                    break;
                case KEEP_RATED:
                    keptRated++;
                    break;
                case KEEP_MOVED:
                    keptMoved++;
                    break;
                case KEEP_ABSENT:
                    absent++;
                    break;
                default:
                    keptUnknown++;
                    break;
            }
        }
        String line = "mal repair: " + (dry ? "DRY " : "") + reverted + " reverted, "
                + keptRated + " kept-rated, " + keptMoved + " kept-user-moved, "
                + absent + " absent, " + keptUnknown + " unknown";
        Report.info("malrepair", line);
        if (!dry) p.edit().putLong("last_run", now).apply();
        return new Result(true, line);
    }

    /**
     * Undo a fabricated write: DELETE the entry from the user's list. Merely
     * parking it as plan_to_watch left the fabricated show "still showing" on
     * the user's account (2026-10-14). The bridge created the entry from file
     * presence, so removing it is the true undo.
     */
    private static void revert(Context c, int id, String pushed) {
        try {
            MalClient.deleteStatus(c, id);
        } catch (Exception e) {
            Report.err("malrepair", "revert failed for " + id, e);
            return; // keep the ledger clean: nothing happened on MAL
        }
        prefs(c).edit().putString("r:" + id, pushed + "|" + System.currentTimeMillis()).apply();
        neutralize(c, id);
        Report.info("malrepair", "deleted fabricated write: " + id + " (" + pushed
                + ") - entry removed from MAL");
    }

    /**
     * A reverted series must never be re-pushed from artifact-derived rows.
     * Real evidence appearing on the series (playback, a real tag, or the
     * watched ledger) un-shields it and restores its rows so the bridge serves
     * that series normally again.
     */
    static boolean pushBlocked(Context c, Store.Row r) {
        if (r.matchId <= 0) return false;
        if (prefs(c).getString("r:" + r.matchId, null) == null) return false;
        if (seriesHasEvidence(Store.all(), r.matchId, Watched.snapshot())) {
            unshield(c, r.matchId);
            return false;
        }
        return true;
    }

    /**
     * Remove the shield for a series and hand its rows back to the normal
     * pipeline: evidence rows (played / real tag) become actionable again with
     * a clean {@code applied} so the planner re-writes them; untagged artifact
     * rows stay suppressed ({@code none}{@code plan_to_watch|0}).
     */
    private static void unshield(Context c, int id) {
        prefs(c).edit().remove("r:" + id).apply();
        for (Store.Row o : Store.all()) {
            if (o.matchId != id) continue;
            if (o.watchedPct >= 0) {
                o.state = PlaybackTracker.isFinished(o.watchedPct) ? "finished" : "watching";
                o.applied = "";
            } else if (o.rgb != -1 && ColorClassify.hasTag(o.rgb)) {
                String s = ColorClassify.classify((o.rgb >> 16) & 0xFF,
                        (o.rgb >> 8) & 0xFF, o.rgb & 0xFF);
                o.state = (s == null) ? "none" : s;
                o.applied = "";
            }
            // else: untagged, evidence-free row -> keep "none"/plan_to_watch|0
        }
        Store.save();
    }

    /** Count of series currently shielded from re-push (diagnostic). */
    static int shieldedCount(Context c) {
        int n = 0;
        for (String k : new TreeMap<>(prefs(c).getAll()).keySet()) {
            if (k.startsWith("r:")) n++;
        }
        return n;
    }

    private static void neutralize(Context c, int id) {
        for (Store.Row o : Store.all()) {
            if (o.matchId == id) {
                // "none" is the true verdict for an untagged row (the classifier
                // fix) and is not actionable, so no future plan can re-push it.
                o.state = "none";
                o.applied = "plan_to_watch|0";
            }
        }
        Store.save();
    }
}