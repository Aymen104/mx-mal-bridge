package com.bridge.mx;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * MyAnimeList client using the same OAuth PKCE setup DailyAL uses:
 *   client id 4a2616916ea55dd75b7ad461bea38b08 (no secret, plain challenge)
 *   redirect http://localhost:8585/callback (a local ServerSocket catches it)
 *   token endpoint https://myanimelist.net/v1/oauth2/token
 * Writes mirror DailyAL: X-MAL-Client-ID + Bearer, form body, PUT to
 * /v2/anime/{id}/my_list_status (PATCH fallback).
 */
public class MalClient {

    public static final String CLIENT_ID = "4a2616916ea55dd75b7ad461bea38b08";
    // DailyAL's registered MAL client secret (confidential client - MAL 401s without it)
    public static final String CLIENT_SECRET = "eda146d87caf3408468d0b1b8dd6d84bad3ae5522a4597c495496e743a38bbce";
    public static final String REDIRECT = "http://localhost:8585/callback";
    private static final String AUTHORIZE = "https://myanimelist.net/v1/oauth2/authorize";
    private static final String TOKEN_URL = "https://myanimelist.net/v1/oauth2/token";
    private static final String API = "https://api.myanimelist.net/v2/";
    private static final String TAG = "MalClient";

    public interface Cb {
        void done(String err, String ok);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("mal", Context.MODE_PRIVATE);
    }

    public static boolean hasSession(Context c) {
        return !prefs(c).getString("refresh", "").isEmpty();
    }

    // ---------------------------------------------------------------- login

    /** Full PKCE round-trip: local socket on 8585 + system browser. */
    public static void startLogin(final Context ctx, final Cb cb) {
        final String verifier = randomString(127);
        prefs(ctx).edit().putString("verifier", verifier).apply();
        final Handler main = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.bind(new InetSocketAddress("127.0.0.1", 8585));
                ss.setSoTimeout(600_000); // 10 minutes for the user to log in

                String authUrl = AUTHORIZE
                        + "?response_type=code"
                        + "&client_id=" + CLIENT_ID
                        + "&code_challenge=" + URLEncoder.encode(verifier, "UTF-8")
                        + "&state=OAuthLogin"
                        + "&redirect_uri=" + URLEncoder.encode(REDIRECT, "UTF-8");

                final String url = authUrl;
                main.post(() -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(i);
                    } catch (Exception e) {
                        Log.w(TAG, "browser launch failed", e);
                    }
                });

                Socket s = ss.accept();
                String code = null, state = null;
                try {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    String req = in.readLine(); // GET /callback?code=..&state=.. HTTP/1.1
                    if (req != null && req.startsWith("GET ")) {
                        int sp = req.indexOf(' ');
                        int sp2 = req.indexOf(' ', sp + 1);
                        String target = req.substring(sp + 1, sp2 > 0 ? sp2 : req.length());
                        int q = target.indexOf('?');
                        if (q >= 0) {
                            Map<String, String> params = parseQuery(target.substring(q + 1));
                            code = params.get("code");
                            state = params.get("state");
                        }
                    }
                    OutputStream out = s.getOutputStream();
                    byte[] body = ("<html><body style=\"font-family:sans-serif\">"
                            + "<h3>MX-MAL Bridge: login OK</h3>"
                            + "You can close this tab and return to the app."
                            + "</body></html>").getBytes(StandardCharsets.UTF_8);
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8"
                            + "\r\nContent-Length: " + body.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(body);
                    out.flush();
                } finally {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                }

                if (code == null || !"OAuthLogin".equals(state)) {
                    finish(main, cb, "redirect carried no code", null);
                    return;
                }
                exchange(ctx, code, verifier, main, cb);
            } catch (Exception e) {
                Log.w(TAG, "login failed", e);
                finish(main, cb, "login error: " + e, null);
            } finally {
                if (ss != null) {
                    try {
                        ss.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "mal-login").start();
    }

    private static void exchange(Context ctx, String code, String verifier,
                                 Handler main, Cb cb) {
        try {
            Map<String, String> form = new HashMap<>();
            form.put("client_id", CLIENT_ID);
            form.put("client_secret", CLIENT_SECRET);
            form.put("grant_type", "authorization_code");
            form.put("code", code);
            form.put("code_verifier", verifier);
            form.put("redirect_uri", REDIRECT);
            String body = postForm(TOKEN_URL, form);
            saveToken(ctx, new JSONObject(body));
            prefs(ctx).edit().remove("verifier").apply();
            finish(main, cb, null, "logged in");
        } catch (Exception e) {
            Log.w(TAG, "exchange failed", e);
            finish(main, cb, "token exchange failed: " + e, null);
        }
    }

    private static void finish(Handler main, Cb cb, String err, String ok) {
        main.post(() -> cb.done(err, ok));
    }

    // --------------------------------------------------------------- tokens

    /** Serializes token refresh so two threads never race the refresh token. */
    private static final Object TOKEN_LOCK = new Object();

    /** Valid access token, transparently refreshed. Call from a bg thread. */
    public static String token(Context c) {
        SharedPreferences p = prefs(c);
        String access = p.getString("access", "");
        long exp = p.getLong("expires_at", 0);
        if (!access.isEmpty() && System.currentTimeMillis() < exp - 60_000L) return access;

        // The sync pipeline and the mal-repair thread both call this. MAL
        // rotates the refresh token, so two concurrent refreshes can invalidate
        // each other and leave one thread with no token - which surfaces as an
        // "empty" list guard. Serialize, and re-check once inside the lock.
        synchronized (TOKEN_LOCK) {
            access = p.getString("access", "");
            exp = p.getLong("expires_at", 0);
            if (!access.isEmpty() && System.currentTimeMillis() < exp - 60_000L) return access;

            String refresh = p.getString("refresh", "");
            if (refresh.isEmpty()) return null;
            try {
                Map<String, String> form = new HashMap<>();
                form.put("client_id", CLIENT_ID);
                form.put("client_secret", CLIENT_SECRET);
                form.put("grant_type", "refresh_token");
                form.put("redirect_uri", REDIRECT);
                form.put("refresh_token", refresh);
                String body = postForm(TOKEN_URL, form);
                saveToken(c, new JSONObject(body));
                return prefs(c).getString("access", null);
            } catch (Exception e) {
                Log.w(TAG, "refresh failed", e);
                return null;
            }
        }
    }

    private static void saveToken(Context c, JSONObject j) throws Exception {
        String access = j.getString("access_token");
        String refresh = j.optString("refresh_token", "");
        long expiresIn = j.optLong("expires_in", 3600L);
        SharedPreferences.Editor e = prefs(c).edit().putString("access", access)
                .putLong("expires_at", System.currentTimeMillis() + expiresIn * 1000L)
                .putString("token_type", j.optString("token_type", "Bearer"));
        if (!refresh.isEmpty()) e.putString("refresh", refresh);
        e.apply();
    }

    // ----------------------------------------------------------------- api

    public static JSONObject getJson(Context c, String path) throws Exception {
        String t = token(c);
        if (t == null) throw new Exception("not logged in");
        HttpURLConnection conn = (HttpURLConnection) new URL(API + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Authorization", "Bearer " + t);
        conn.setRequestProperty("X-MAL-Client-ID", CLIENT_ID);
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (code != 200) throw new Exception("GET HTTP " + code + " " + resp);
        return new JSONObject(resp);
    }

    /**
     * Top search results for a query (needs login).
     *
     * <p>Runs {@link #queryLadder}: the full cleaned title first, then shorter
     * word prefixes when MAL returns nothing. Long subtitles and leftover
     * release tags ("...OV ESub", "...The Dark Summoner is Trapped in a Room")
     * otherwise defeat the search entirely.
     */
    public static JSONArray search(Context c, String q) throws Exception {
        JSONArray first = null;
        for (String cand : queryLadder(q)) {
            JSONArray data = rawSearch(c, cand);
            if (data != null && data.length() > 0) return data;
            if (first == null) first = data;
            try {
                Thread.sleep(350); // stay under MAL's ~3 requests/second
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return first;
    }

    /** One raw MAL anime search (no laddering). */
    private static JSONArray rawSearch(Context c, String query) throws Exception {
        if (query == null || query.isEmpty()) return null;
        // nsfw=true is required: without it MAL hides flagged titles from the
        // search index entirely (e.g. "Ayakashi Triangle" returns 0 results).
        String path = "anime?q=" + URLEncoder.encode(query, "UTF-8")
                + "&nsfw=true&limit=10&fields=id,title,num_episodes,alternative_titles,"
                + "media_type,status";
        return getJson(c, path).optJSONArray("data");
    }

    /**
     * Cleaned query variants, longest first: the full title then 6/4/3-word
     * prefixes. Deduplicated and never empty.
     */
    static List<String> queryLadder(String q) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String base = cleanQuery(q);
        if (base.isEmpty()) return new ArrayList<>(out);
        out.add(base);
        String[] w = base.split("\\s+");
        for (int n : new int[]{6, 4, 3}) {
            if (w.length > n) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    if (i > 0) sb.append(' ');
                    sb.append(w[i]);
                }
                out.add(sb.toString());
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * MAL rejects a "q" longer than 64 chars (HTTP 400 "invalid q"), and the
     * device filenames carry release-group / site / quality noise that makes
     * the search miss. Strip that noise and hard-cap the length.
     */
    static String cleanQuery(String q) {
        if (q == null) return "";
        String s = q;
        // Arabic-script tails from Arabic release sites ("... مترجم عربي").
        s = s.replaceAll("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF"
                + "\\uFB50-\\uFDFF\\uFE70-\\uFEFF]+", " ");
        // season/episode markers that survived parsing ("S01E02", "1x02", "E02").
        s = s.replaceAll("(?i)\\bS\\d{1,2}\\s?E\\d{1,3}\\b", " ");
        s = s.replaceAll("(?i)\\b\\d{1,2}x\\d{1,3}\\b", " ");
        s = s.replaceAll("(?i)\\bE\\d{1,3}\\b", " ");
        // release-tag noise (dotted or spaced)
        s = s.replaceAll("(?i)\\b(uncensored|adn|jpn|msub\\w*|esub|toonshub|subsplease|"
                + "erai|judas|horriblesubs|hidive|baha|abema|iqiyi|muse|anione|aniplus|"
                + "web-?dl|blu-?ray|bd|amzn|cr|funimation|"
                + "aac[0-9.]*|ac3|eac3|x26[45]|h\\.?26[45]|hevc|avc|vp[89]|"
                + "10bit|8bit|hdr|proper|repack|dual|audio|multi|subs?|ov|ova)\\b", " ");
        // quality / state noise that is not part of a title
        s = s.replaceAll("(?i)\\b(end|sd|fhd|uhd|hd|source)\\b", " ");
        // site junk + arabic "translated online" tails
        s = s.replaceAll("(?i)\\s*[-–|]\\s*(blkom|www\\.[a-z0-9.-]+).*$", " ");
        s = s.replaceAll("(مترجم\\s*)?(أون|اون)\\s*لاين.*$", " ");
        s = s.replaceAll("(?i)(blkom|\\.co|\\.com|\\.net).*$", " ");
        // leftover separators / duplicate markers
        s = s.replaceAll("[()\\[\\]{}._★☆]", " ");
        s = s.replaceAll("\\s{2,}", " ").replaceAll("[\\s\\-–_.|!]+$", "").trim();
        if (s.length() > 64) {          // MAL rejects q longer than 64 chars
            s = s.substring(0, 64);
            int sp = s.lastIndexOf(' ');   // cut on a word boundary, never mid-word
            if (sp > 0) s = s.substring(0, sp);
            s = s.trim();
        }
        return s;
    }

    /**
     * The user's whole list as animeId -> "status|num_watched". Used before an
     * apply so a file-based estimate can never walk a manual completion (or a
     * higher episode count) backwards.
     *
     * <p><b>All or nothing.</b> Returns {@code null} when the walk cannot be
     * COMPLETED — a page failed even after a retry, or the safety cap was
     * reached — so no caller can mistake a partial page-set for the user's
     * whole list. Treating a truncated read as complete is exactly what let the
     * bridge "add" (overwrite) an entry it had simply not read yet: on
     * 2026-10-14 a short read missed a completed show and re-wrote it as
     * "watching 12". A non-null but empty map means the user genuinely has no
     * entries.
     */
    public static Map<Integer, String> myListStatus(Context c) {
        return walkList(c, false);
    }

    /**
     * The user's whole list as animeId -> "status|num_watched|score". Same
     * walk as {@link #myListStatus} but also carries the user's score, the
     * "rated = genuine" discriminator used by {@link MalRepair} so a bogus
     * write is never withdrawn from an entry the user actually engaged with.
     * {@code null} when the walk could not be completed.
     */
    public static Map<Integer, String> myListScores(Context c) {
        return walkList(c, true);
    }

    /**
     * Paginated walk of {@code users/@me/animelist}, sorted by {@code
     * anime_title}. The default sort ({@code list_updated_at}) is re-ordered by
     * our own writes, which makes offset pagination skip entries and silently
     * drop them from the guard; a title-keyed order is stable across the walk.
     * Each page is retried once, and any failure past that makes the whole walk
     * "unknown" (null) rather than silently short.
     *
     * @return the complete map, or {@code null} when completion is not provable
     */
    private static Map<Integer, String> walkList(Context c, boolean withScore) {
        final int page = 300;
        Map<Integer, String> out = new HashMap<>();
        int offset = 0;
        while (offset < 6000) {
            JSONObject j = null;
            for (int attempt = 0; attempt < 2 && j == null; attempt++) {
                try {
                    j = getJson(c, "users/@me/animelist?nsfw=true&sort=anime_title"
                            + "&limit=" + page + "&offset=" + offset + "&fields=list_status");
                } catch (Exception e) {
                    Log.w(TAG, "animelist offset=" + offset + " attempt " + (attempt + 1)
                            + " failed", e);
                    sleep(800);
                }
            }
            if (j == null) return null;                 // incomplete -> unknown
            JSONArray data = j.optJSONArray("data");
            if (data == null) return null;
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o == null) continue;
                JSONObject node = o.optJSONObject("node");
                JSONObject ls = o.optJSONObject("list_status");
                if (node == null || ls == null) continue;
                String v = ls.optString("status", "") + "|"
                        + ls.optInt("num_episodes_watched", 0);
                if (withScore) v = v + "|" + ls.optInt("score", 0);
                out.put(node.optInt("id", -1), v);
            }
            if (data.length() < page) return out;       // natural end -> complete
            offset += page;
            sleep(200);                                 // gentle between pages
        }
        return null;                                    // cap hit -> incomplete
    }

    /**
     * The signed-in user's status for ONE anime, read directly (no snapshot):
     * {@code "status|num_watched"} when the anime is on their list, {@code ""}
     * when it is confirmed NOT on the list, or {@code null} when the answer
     * could not be obtained. Called before any "not on the list" write so a
     * flaky or partial list read can never turn into an overwrite — the caller
     * MUST skip the write when this is {@code null}.
     */
    public static String myStatusFor(Context c, int animeId) {
        try {
            JSONObject j = getJson(c, "anime/" + animeId + "?fields=my_list_status");
            JSONObject ls = j.optJSONObject("my_list_status");
            if (ls == null) return "";
            String st = ls.optString("status", "");
            if (st.isEmpty()) return "";
            return st + "|" + ls.optInt("num_episodes_watched", 0);
        } catch (Exception e) {
            Log.w(TAG, "myStatusFor " + animeId + " failed", e);
            return null;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public static String me(Context c) throws Exception {
        return getJson(c, "users/@me?fields=id,name").optString("name", "?");
    }

    /** Update list status; mirrors DailyAL (form body, PUT, PATCH fallback). */
    public static void updateStatus(Context c, int animeId, String status, int eps)
            throws Exception {
        Log.i(TAG, "updateStatus: animeId=" + animeId + " status=" + status + " eps=" + eps);
        String t = token(c);
        if (t == null) throw new Exception("not logged in");
        Map<String, String> form = new HashMap<>();
        form.put("status", status);
        if (eps >= 0) form.put("num_watched_episodes", String.valueOf(eps));
        String body = encodeForm(form);
        int code = request("PUT", API + "anime/" + animeId + "/my_list_status", t, body);
        if (code == 405 || code == 501) {
            code = request("PATCH", API + "anime/" + animeId + "/my_list_status", t, body);
        }
        if (code < 200 || code >= 300) throw new Exception("update HTTP " + code);
        Log.i(TAG, "updateStatus success: " + code);
    }

    /**
     * Remove the anime from the user's list entirely (MAL v2 DELETE). Used to
     * undo a write the bridge itself fabricated from file presence — parking it
     * as plan_to_watch only left it "showing" on the user's list, so a fabricated
     * entry is deleted instead.
     */
    public static void deleteStatus(Context c, int animeId) throws Exception {
        Log.i(TAG, "deleteStatus: animeId=" + animeId);
        String t = token(c);
        if (t == null) throw new Exception("not logged in");
        int code = request("DELETE", API + "anime/" + animeId + "/my_list_status", t, null);
        if (code < 200 || code >= 300) throw new Exception("delete HTTP " + code);
        Log.i(TAG, "deleteStatus success: " + code);
    }

    /** Add anime to user's list (PUT - same endpoint as update, MAL handles both). */
    public static void addToList(Context c, int animeId, String status, int eps)
            throws Exception {
        Log.i(TAG, "addToList: animeId=" + animeId + " status=" + status + " eps=" + eps);
        String t = token(c);
        if (t == null) throw new Exception("not logged in");
        Map<String, String> form = new HashMap<>();
        form.put("status", status);
        if (eps >= 0) form.put("num_watched_episodes", String.valueOf(eps));
        String body = encodeForm(form);
        int code = request("PUT", API + "anime/" + animeId + "/my_list_status", t, body);
        if (code < 200 || code >= 300) throw new Exception("add HTTP " + code);
        Log.i(TAG, "addToList success: " + code);
    }

    private static int request(String method, String url, String token, String body)
            throws Exception {
        Log.i(TAG, "MAL API request: " + method + " " + url + " on thread=" + Thread.currentThread().getName() + " isMain=" + (Looper.getMainLooper() == Looper.myLooper()));
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("X-MAL-Client-ID", CLIENT_ID);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        if (body != null) {
            conn.setDoOutput(true);
            OutputStream out = conn.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
        }
        int code = conn.getResponseCode();
        String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        Log.i(TAG, "MAL API response: " + code + " " + resp);
        conn.disconnect();
        return code;
    }

    // -------------------------------------------------------------- helpers

    private static String postForm(String url, Map<String, String> form) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);
        OutputStream out = conn.getOutputStream();
        out.write(encodeForm(form).getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.close();
        int code = conn.getResponseCode();
        String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " " + resp);
        return resp;
    }

    private static String encodeForm(Map<String, String> form) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8"));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> out = new HashMap<>();
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(URLDecoder.decode(part.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(part.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static String randomString(int len) {
        final String cs = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(cs.charAt(r.nextInt(cs.length())));
        return sb.toString();
    }
}
