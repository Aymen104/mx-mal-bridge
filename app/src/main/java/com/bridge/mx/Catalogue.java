package com.bridge.mx;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The anime-title knowledge base that {@link Educate} studies before every exam
 * and that the matcher's shorthand table is built from.
 *
 * <p>The source is <b>animeTosho</b> (the same site the corpus was scraped
 * from), not MyAnimeList. The app ships with a snapshot
 * ({@code assets/anime_titles.txt}) and refreshes it on a <b>~2-3 month</b>
 * cadence ({@link #STALE_MS} = 75 days) by walking animeTosho's
 * {@code /animes?page=N} listing (pages 0..249). The refreshed list is cached on
 * device ({@code filesDir/catalogue.txt}) and merged onto the current list, so a
 * refresh can only ever <em>add</em> titles. When the site is unreachable or the
 * cache is unreadable, {@link #titles} falls back to the bundled asset and the
 * bridge keeps working exactly as before.
 *
 * <p>{@link #parseListing}, {@link #htmlDecode}, {@link #merge},
 * {@link #stale} and {@link #ageDays} are Android-free and unit tested; the
 * rest is thin I/O.
 */
public final class Catalogue {

    /** Bundled fallback catalogue (one title per line). */
    public static final String ASSET = "anime_titles.txt";
    /** Refreshed copy written under the app's private files dir. */
    public static final String CACHE = "catalogue.txt";
    /** Refresh when older than ~2.5 months (a 2-3 month cadence). */
    public static final long STALE_MS = 75L * 24 * 60 * 60 * 1000;
    /** Do not retry a failed refresh more than once a day. */
    static final long RETRY_MS = 24L * 60 * 60 * 1000;

    /** The source is animeTosho — the .net mirror only, per directive (no .org). */
    static final String[] BASES = {"https://animetosho.net"};

    /**
     * QA hook: a one-shot base-URL override so the crawl can be pointed at a
     * local mirror (saved page bodies) for testing the full pipeline without
     * hitting the live site. Set from the {@code at_base} intent extra;
     * cleared on the next launch when the extra is absent.
     */
    static volatile String baseOverride = null;
    /** The listing ends at page 249 today; leave headroom. */
    static final int MAX_PAGES = 260;
    /** Past this page an empty listing is the real end, not a block. */
    static final int KNOWN_END_PAGE = 245;
    /** Politeness delay between listing pages. */
    static final long PAGE_DELAY_MS = 180;

    /**
     * One listing row: an anchor to a {@code /series/} URL whose id tail is a
     * <em>number</em> — the classic numeric form ({@code /series/12345}, served
     * by {@code .net}) or a slug form ending in an id
     * ({@code /series/foo-bar.12345}). Non-numeric pseudo-categories such as
     * {@code /series/unsorted} ("Unsorted Files") are rejected; a real series
     * id is always numeric. The anchor's first child is a {@code <strong>}
     * title that may itself carry a {@code title=} attribute.
     */
    private static final Pattern ROW = Pattern.compile(
            "href=\"[^\"]*?/series/(?:\\d+|[^\"/]*\\.\\d+)\"[^>]*><strong[^>]*>(.*?)</strong>",
            Pattern.DOTALL);

    private static final String UA =
            "Mozilla/5.0 (Linux; Android) MxSyncBridge/1.4 (personal; polite)";

    private static final String PREF = "mxmeta";
    private static final String KEY_AT = "catalogue_at";
    private static final String KEY_N = "catalogue_n";
    private static final String KEY_TRY = "catalogue_try";

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private Catalogue() {
    }

    /** Outcome of a refresh attempt. */
    public static final class Result {
        public final boolean ok;
        public final String line;

        Result(boolean ok, String line) {
            this.ok = ok;
            this.line = line;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static File cacheFile(Context c) {
        return new File(c.getFilesDir(), CACHE);
    }

    public static long lastUpdated(Context c) {
        return prefs(c).getLong(KEY_AT, 0);
    }

    public static int cachedCount(Context c) {
        return prefs(c).getInt(KEY_N, 0);
    }

    // -------------------------------------------------------------- pure core

    /** True when a catalogue last refreshed at {@code at} is due for a refresh. */
    static boolean stale(long now, long at) {
        return at == 0 || now - at >= STALE_MS;
    }

    /** Whole days since {@code at}, or -1 when never refreshed. */
    static long ageDays(long now, long at) {
        return at == 0 ? -1 : (now - at) / (24L * 60 * 60 * 1000);
    }

    /** Titles on one animeTosho /animes listing page. Pure and unit tested. */
    static List<String> parseListing(String html) {
        List<String> out = new ArrayList<>();
        if (html == null) return out;
        Matcher m = ROW.matcher(html);
        while (m.find()) {
            // Strip any inline markup first, then decode entities, so a decoded
            // "&lt;OAV&gt;" is not mistaken for a tag and eaten.
            String t = m.group(1).replaceAll("<[^>]+>", " ").trim();
            t = htmlDecode(t).trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Minimal HTML entity decoder (&amp;, &lt;, &quot;, &#39;, &#x..;). */
    static String htmlDecode(String s) {
        if (s == null) return "";
        if (s.indexOf('&') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '&') {
                b.append(ch);
                continue;
            }
            int sc = s.indexOf(';', i + 1);
            if (sc < 0 || sc - i > 12) {
                b.append(ch);
                continue;
            }
            String ent = s.substring(i + 1, sc);
            String rep = null;
            if (ent.startsWith("#")) {
                try {
                    int cp = (ent.length() > 1 && (ent.charAt(1) == 'x' || ent.charAt(1) == 'X'))
                            ? Integer.parseInt(ent.substring(2), 16)
                            : Integer.parseInt(ent.substring(1));
                    rep = new String(Character.toChars(cp));
                } catch (Exception ignore) {
                    // leave the entity untouched
                }
            } else {
                switch (ent) {
                    case "amp": rep = "&"; break;
                    case "lt": rep = "<"; break;
                    case "gt": rep = ">"; break;
                    case "quot": rep = "\""; break;
                    case "apos": rep = "'"; break;
                    case "nbsp": rep = " "; break;
                    case "mdash": rep = "\u2014"; break;
                    case "ndash": rep = "\u2013"; break;
                    case "hellip": rep = "\u2026"; break;
                    default: break;
                }
            }
            if (rep != null) {
                b.append(rep);
                i = sc;
            } else {
                b.append(ch);
            }
        }
        return b.toString();
    }

    /**
     * Append every new title from {@code add} to {@code into}: trims, drops
     * blanks and control characters, and de-dupes case-insensitively. Returns
     * how many titles were newly added.
     */
    static int merge(List<String> into, Iterable<String> add) {
        Set<String> seen = new HashSet<>();
        for (String s : into) {
            if (s != null) seen.add(s.toLowerCase(Locale.ROOT));
        }
        int n = 0;
        for (String s : add) {
            if (s == null) continue;
            String t = s.trim().replaceAll("\\p{Cntrl}+", " ").trim();
            if (t.isEmpty() || t.length() > 200) continue;
            if (seen.add(t.toLowerCase(Locale.ROOT))) {
                into.add(t);
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ state

    public static boolean isStale(Context c) {
        return stale(System.currentTimeMillis(), lastUpdated(c));
    }

    /** Compact status fragment for the main screen / notification. */
    public static String ageLine(Context c) {
        long at = lastUpdated(c);
        long d = ageDays(System.currentTimeMillis(), at);
        if (d < 0) return "titles: builtin";
        return "titles: " + cachedCount(c) + " (" + d + "d)";
    }

    /** Titles to study: the refreshed cache when present, else the asset. */
    public static List<String> titles(Context c) {
        List<String> out = readLines(cacheFile(c));
        if (out.isEmpty()) out = readAsset(c);
        return out;
    }

    // ---------------------------------------------------------------- refresh

    /**
     * Refresh the catalogue from animeTosho when it is stale (or {@code force}).
     * Blocking; call from a worker thread. Never throws.
     */
    public static Result refresh(Context c, boolean force) {
        long now = System.currentTimeMillis();
        SharedPreferences p = prefs(c);
        if (!force && !stale(now, lastUpdated(c))) {
            return new Result(true, "titles fresh (" + ageDays(now, lastUpdated(c)) + "d)");
        }
        if (!force && now - p.getLong(KEY_TRY, 0) < RETRY_MS) {
            return new Result(false, "titles: refresh deferred (retried <1d ago)");
        }
        if (!RUNNING.compareAndSet(false, true)) {
            return new Result(false, "titles: refresh already running");
        }
        p.edit().putLong(KEY_TRY, now).apply();
        try {
            List<String> cat = new ArrayList<>(titles(c)); // merged onto current cache/asset
            int before = cat.size();

            Fetch best = null;
            for (String base : baseOverride != null ? new String[]{baseOverride} : BASES) {
                Fetch r = fetchListing(base, cat);
                if (r.reachedEnd) {
                    best = r;
                    break;
                }
                if (best == null || r.pages > best.pages) best = r;
            }
            if (best == null || best.blocked || (!best.reachedEnd && best.pages == 0)) {
                String why = best == null || best.pages == 0 ? "unreachable"
                        : (best.blocked ? "blocked" : "no data");
                String line = "titles: animeTosho.net " + why + " (kept " + before + ")";
                Report.warn("catalogue", line);
                return new Result(false, line);
            }
            if (!best.reachedEnd && best.added == 0) {
                String line = "titles: animeTosho.net partial read, " + best.pages
                        + " pages (kept " + before + ")";
                Report.warn("catalogue", line);
                return new Result(false, line);
            }
            try {
                writeAtomic(cacheFile(c), cat);
                p.edit().putLong(KEY_AT, now).putInt(KEY_N, cat.size()).apply();
            } catch (Exception e) {
                Report.err("catalogue", "catalogue write failed", e);
                return new Result(false, "titles: write failed (" + e.getMessage() + ")");
            }
            String line = "titles: " + cat.size() + " total (" + best.pages + " pages"
                    + (best.added > 0 ? ", +" + best.added + " new" : ", up to date") + ")";
            // Every new bulk of titles must be studied and pass the test before
            // it is trusted. If the pipeline regresses, say so loudly — the new
            // list stays (titles are additive) but the failure is not hidden.
            if (best.added > 0) {
                Educate.Result study = Educate.study(c);
                Selftest.Result test = Selftest.run();
                Selftest.record(c, test);
                line += " | " + test.line();
                if (!test.pass()) {
                    Report.warn("catalogue", "self-test regressed after refresh ("
                            + study.line() + "): " + test.line());
                    // Too many mistakes: escalate to the desktop repair watcher.
                    if (!Repair.reportedRecently(c)) {
                        Repair.report(c, "self-test failed after title refresh", test,
                                study.line() + "\n" + line);
                    }
                }
            }
            Report.info("catalogue", line);
            return new Result(true, line);
        } finally {
            RUNNING.set(false);
        }
    }

    /** Outcome of walking one base URL's listing. */
    static final class Fetch {
        boolean reachedEnd;
        boolean blocked;
        int added;
        int pages;
    }

    private static Fetch fetchListing(String base, List<String> into) {
        Fetch f = new Fetch();
        int empty = 0, fails = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            if (page == 1) continue; // animeTosho repeats page 0 for page=1
            String html = httpGet(base + "/animes?page=" + page);
            if (html == null) {
                if (++fails >= 5) return f;
                continue;
            }
            fails = 0;
            f.pages++;
            List<String> titles = parseListing(html);
            if (titles.isEmpty()) {
                if (++empty >= 2) {
                    f.reachedEnd = true;
                    return f;
                }
                continue;
            }
            empty = 0;
            f.added += merge(into, titles);
            try {
                Thread.sleep(PAGE_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return f;
            }
        }
        f.reachedEnd = true; // hit the page cap with data
        return f;
    }

    // -------------------------------------------------------------------- i/o

    private static String httpGet(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "text/html");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            int code = conn.getResponseCode();
            if (code == 429) {
                Thread.sleep(6000); // throttled: back off, caller may retry
                return null;
            }
            if (code != 200) return null;
            InputStream in = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    static void writeAtomic(File f, List<String> lines) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        StringBuilder sb = new StringBuilder(lines.size() * 32);
        for (String s : lines) sb.append(s).append('\n');
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new Exception("rename failed");
        }
    }

    static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        if (f == null || !f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) out.add(line);
            }
        } catch (Exception e) {
            Report.warn("catalogue", "cache unreadable: " + e);
            return new ArrayList<>();
        }
        return out;
    }

    static List<String> readAsset(Context c) {
        List<String> out = new ArrayList<>();
        try (InputStream in = c.getAssets().open(ASSET);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) out.add(line);
            }
        } catch (Throwable t) {
            Report.warn("catalogue", "asset unavailable: " + t);
        }
        return out;
    }
}
