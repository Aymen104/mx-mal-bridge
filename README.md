# MX-MAL Bridge

> **Declared: made with AI.** This application was designed, written and
> published entirely by an AI coding agent (OpenCode, model *Big Pickle*)
> under human direction.

**Description:** an Android background service that watches how MX Player
labels your episode files — *New*, *Last played*, and *Finished* (via one
calibrated screenshot) — and mirrors that state onto your MyAnimeList account,
the same list [DailyAL](https://github.com/) displays. One MAL login (identical
PKCE client id as DailyAL) = both apps see the same list.

```
MX Player list  --read-only-->  Bridge  --OAuth PKCE-->  MyAnimeList API v2
 (NEW badge,                     (parse episode,          (what DailyAL shows)
  Last played,                    match title,
  Finished color)                 dry-run plan)
```

## Download

The installable APK is published on the **[Releases](../../releases)** page:

- **Latest:** <https://github.com/Aymen104/mx-mal-bridge/releases/latest>
- File: `mx-mal-bridge-v*.apk` — download it on the phone and open it to
  install ("allow unknown sources"), or sideload with
  `adb install mx-mal-bridge-v*.apk`.

Works on **any phone, Android 5.0+ (API 21)**, with MX Player **Free** or
**Pro**.

## Interface

The app **has a full interface** — and every view of it is built from code
(no layout XML anywhere):

- status line (rows / service / MAL login / planned / auto / next-sync timer)
- button strip: **Login · Scan · Colors · Match · Plan · Apply · Auto**
- observed-episode list with state, episode, MAL match and planned action
- scrolling log; every error is also persisted (see below)

All interaction with other apps is likewise driven from code: the
accessibility parameters (packages, event types, flags, timeout) are applied
at runtime in `MxService.configureServiceInfo()` via `setServiceInfo()`.
The remaining accessibility XML is only Android's mandatory registration card
(plus the screenshot/window-content capabilities, which the platform accepts
exclusively there).

## What it reads (and what it never touches)

| Channel | How | States |
|---|---|---|
| Text | Accessibility events over `:id/list_item` rows | `new_title` → **new**, `tv_last_played` → **watching** |
| Color | one `AccessibilityService.takeScreenshot()` (API 30+), calibrated centroids | dim (167,149,132) → **finished**, dark → **none**, blue → watching |

The service has **no write path into MX Player**: no taps, no keyevents, no
scrolling, no DB access. Read-only by construction.

## Status mapping → MAL (`PUT /v2/anime/{id}/my_list_status`)

| MX state | MAL status | num_watched_episodes |
|---|---|---|
| new | `planned` | 0 |
| watching (Last played) | `watching` | episode − 1 |
| finished, ep < total | `watching` | episode |
| finished, ep ≥ total | `completed` | total |
| none / unknown | — (no write) | — |

**Dry-run by default.** Plan never sends anything; manual **Apply** asks for
confirmation first; the **Auto** toggle must be switched on explicitly before
the background service may write anything on its own.

## Background operation

- The accessibility service lives as long as Android keeps it enabled — no
  need to open the app.
- It re-reads the MX list automatically on every MX UI change (instant,
  local, read-only).
- Network work (MAL search for newly seen titles + optional auto-apply) runs
  on a **~20 minute cadence**, at most 4 new matches per tick, with dedupe so
  nothing is searched or pushed twice.
- A low-priority status notification shows rows / planned / auto / countdown
  to the next sync.

## Error reports

Every failure — collect, classify, login, search, apply, notification, and
any uncaught exception — is appended to:

```
Android/data/com.bridge.mx/files/error-report.log        (rotates at 512 KB)
Android/data/com.bridge.mx/files/error-report.old.log
```

Readable over `adb shell cat` (external files dir, no root / run-as needed).

## Workflow

1. **Login** — one-time PKCE OAuth (DailyAL's client id
   `4a2616916ea55dd75b7ad461bea38b08`), redirect caught by a local socket on
   `localhost:8585`.
2. Open the MX Player file list — rows are captured automatically (or tap
   **Scan**).
3. **Colors** — one screenshot classifies label-less rows (API 30+; light
   theme calibrated).
4. **Plan** — parses titles (`الحلقة X`, `EP 06`, `SxxEyy`, trailing ` - 12`),
   prints planned MAL writes. Nothing is sent.
5. **Match** — MAL search per unique title (levenshtein + containment
   scoring, cached).
6. **Apply** — confirmation dialog, then rate-limited writes (~700 ms apart) —
   or enable **Auto** for background sync.

## Build

Plain Gradle project — Java only, zero dependencies (AGP 8.7.0, JDK 17,
SDK 34). No wrapper committed:

```powershell
# local.properties must contain: sdk.dir=<your Android SDK path>
$env:JAVA_HOME = "<jdk17 dir>"
<gradle-8.9>\bin\gradle.bat -p <this dir> assembleDebug
# output: app\build\outputs\apk\debug\app-debug.apk
```

Enable once: **Settings ▸ Accessibility ▸ MX-MAL Bridge ▸ On**.

## Code-only conventions

- **No layout XML** — `MainActivity.buildUi()` constructs the entire
  interface; list rows are built in `EpisodeAdapter` (holder pattern).
- **Interaction from code** — `MxService.configureServiceInfo()` defines
  everything about how the app talks to other apps.
- The only XML left is Android-mandated registration (`AndroidManifest.xml`,
  accessibility meta-data with platform-only capabilities, one string).

## Files

- `MxService.java` — read-only observer, screenshot classifier, 20-min
  background pipeline, status notification, runtime service config
- `MainActivity.java` — code-only interface, manual workflow, confirm-and-apply
- `EpisodeParser.java` — filename → title + episode
- `Planner.java` — state + episode + match → planned MAL write (dry-run)
- `Matcher.java` — MyAnimeList search-result scoring
- `MalClient.java` — PKCE login, token refresh, search, status update
- `Store.java` — row persistence (SharedPreferences), change listeners
- `Report.java` / `BridgeApp.java` — error reports + uncaught-exception capture

## License

[GNU General Public License v3.0](LICENSE) — see [`LICENSE`](LICENSE).

## Declaration

**This project was made with AI.** It was conceived, coded, built and
published by an AI agent with a human directing it. There is no human-written
source code in this repository.
