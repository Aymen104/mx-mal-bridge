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
import java.util.HashMap;
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
            form.put("client_secret", "");
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

    /** Valid access token, transparently refreshed. Call from a bg thread. */
    public static String token(Context c) {
        SharedPreferences p = prefs(c);
        String access = p.getString("access", "");
        long exp = p.getLong("expires_at", 0);
        if (!access.isEmpty() && System.currentTimeMillis() < exp - 60_000L) return access;

        String refresh = p.getString("refresh", "");
        if (refresh.isEmpty()) return null;
        try {
            Map<String, String> form = new HashMap<>();
            form.put("client_id", CLIENT_ID);
            form.put("client_secret", "");
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

    /** Top search results for a query (needs login). */
    public static JSONArray search(Context c, String q) throws Exception {
        String path = "anime?q=" + URLEncoder.encode(q, "UTF-8")
                + "&limit=10&fields=id,title,num_episodes,alternative_titles";
        return getJson(c, path).optJSONArray("data");
    }

    public static String me(Context c) throws Exception {
        return getJson(c, "users/@me?fields=id,name").optString("name", "?");
    }

    /** Update list status; mirrors DailyAL (form body, PUT, PATCH fallback). */
    public static void updateStatus(Context c, int animeId, String status, int eps)
            throws Exception {
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
    }

    private static int request(String method, String url, String token, String body)
            throws Exception {
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
        readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
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
