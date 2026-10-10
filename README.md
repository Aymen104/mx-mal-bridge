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

Works on **any phone, Android 7.0+ (API 24)**, with MX Player **Free** or
**Pro**.

## Interface

The app **has a full interface** — and every view of it is built from code
(no layout XML anywhere):

- status line (rows / service / MAL login / planned / auto / ai / next-sync timer)
- button strip: **Login · Scan · Lib · Colors · Match · Plan · Apply · Auto · AI**
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
  on a **~24 minute cadence**, at most 40 new matches per tick, with dedupe so
  nothing is searched or pushed twice. Already-matched titles are skipped, so
  the on-device LLM only ever sees the unmatched leftovers.
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
3. **Lib** — scans **built-in storage only** (MediaStore, read-only) for
   episode files and reads the episode number from each filename. The SD card
   is intentionally ignored.
4. **Colors** — one screenshot classifies label-less rows (API 30+; light
   theme calibrated).
5. **Plan** — parses titles (`الحلقة X`, `EP 06`, `SxxEyy`, trailing ` - 12`),
   prints planned MAL writes. Nothing is sent.
6. **Match** — MAL search per unique title. Scoring combines edit distance,
   whole-string containment and token overlap, penalises entries whose episode
   count is below the highest episode seen (`Gin S1` **EP 48** → the 201-ep
   *Gintama*, never a 12-ep special), and consults a shorthand alias table
   (`Gin S1` → *Gintama*). Titles that stay unmatched are offered to the
   optional on-device LLM (see below).
7. **Apply** — confirmation dialog, then rate-limited writes (~700 ms apart) —
   or enable **Auto** for background sync. The user's current MAL list is
   snapshotted first, so a write can never downgrade a manual completion or
   lower a watched count.

## On-device AI match fallback (optional)

`Match` and the background tick first run the deterministic matcher. Only when
a title is **not matched confidently** is a small local LLM consulted, and it
may only pick from the real candidates MyAnimeList returned — it can never
invent an id. Successful picks are remembered as aliases.

- Model: **Qwen2.5-0.5B-Instruct**, Apache-2.0, run fully on the phone via
  MediaPipe LLM Inference on CPU. It is **not bundled** in the APK.
- Nothing is downloaded automatically. Tap **AI** to download it (~521 MB) or
  side-load it to `Android/data/com.bridge.mx/files/llm/model.task`.
- **Without the model everything still works** — the app silently falls back to
  the deterministic matcher, and the status line shows `ai no model`.

## Build

Plain Gradle project — Java only (AGP 8.7.0, JDK 17, SDK 34). The only runtime
dependency is MediaPipe LLM Inference
`com.google.mediapipe:tasks-genai:0.10.21` — pinned because 0.10.22+ ship Java
21 class files that JDK 17 javac cannot read. No wrapper committed:

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

- `MxService.java` — read-only observer, screenshot classifier, 24-min
  background pipeline, status notification, runtime service config
- `MainActivity.java` — code-only interface, manual workflow, confirm-and-apply
- `LibraryScanner.java` — built-in-storage MediaStore scan (SD card excluded)
- `EpisodeParser.java` — filename → title + episode
- `Planner.java` — state + episode + match → planned MAL write (dry-run)
- `Matcher.java` — deterministic MAL search-result scoring + score ranking
- `Aliases.java` / `AliasStore.java` — shorthand alias table + persistence
- `MatchBrain.java` / `LlmBrain.java` / `Grounding.java` — grounded on-device
  LLM fallback (prompt, parsing, MediaPipe engine)
- `MalClient.java` — PKCE login, token refresh, search, status update
- `Store.java` — row persistence (SharedPreferences), change listeners
- `Report.java` / `BridgeApp.java` — error reports + uncaught-exception capture

## License

[GNU General Public License v3.0](LICENSE) — see [`LICENSE`](LICENSE).

## Declaration

**This project was made with AI.** It was conceived, coded, built and
published by an AI agent with a human directing it. There is no human-written
source code in this repository.
