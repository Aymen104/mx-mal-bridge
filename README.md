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
- button strip: **Login · Scan · Lib · Colors · Match · Plan · Apply · AI · Exam · Test · Titles · Wipe**
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
  nothing is searched or pushed twice. **LLM-first matching:** when the on-device
  LLM is loaded it decides every title match (it is consulted before the
  deterministic scorer, which stays as the fallback when the model is absent or
  abstains), so the strongest matcher is always given priority over heuristics.
  Rows whose best candidate stays below the acceptance floor are never guessed:
  they abstain, so false friends and clips are not pushed.
- **Download-only scope, enforced at observation:** accessibility rows expose
  only a display title (no path), so an observed row is admitted only when its
  canonical key also exists in the Download library allowlist built by the
  MediaStore scan. Rows outside that tree — SD card, other folders, other
  apps — are dropped on sight, and any already-stored offender is swept at the
  next scan (the allowlist is primed at service start).
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
3. **Lib** — scans the **Download folder on built-in storage only**
   (MediaStore, read-only) for episode files and reads the episode number from
   each filename. The SD card and every other folder are intentionally ignored —
   and that scope is enforced at observation time (see *Background operation*).
4. **Colors** — one screenshot classifies label-less rows (API 30+; light
   theme calibrated).
5. **Plan** — parses titles (`الحلقة X`, `EP 06`, `SxxEyy`, trailing ` - 12`),
   prints planned MAL writes. Nothing is sent.
6. **Match** — MAL search per unique title. Scoring combines edit distance,
   whole-string containment and token overlap, penalises entries whose episode
   count is below the highest episode seen (`Gin S1` **EP 48** → the 201-ep
   *Gintama*, never a 12-ep special), and consults a shorthand alias table
   (`Gin S1` → *Gintama*). Titles that carry a real signal are decided by the on-device LLM when it is
   loaded (see below); a title below the signal floor abstains.
7. **Apply** — confirmation dialog, then rate-limited writes (~700 ms apart) —
   or enable **Auto** for background sync. The user's current MAL list is
   snapshotted first, so a write can never downgrade a manual completion or
   lower a watched count.

## On-device AI matcher (priority, optional)

`Match` and every background tick run the **on-device LLM first**: when the
model is loaded it decides every title whose candidate pool carries a real
signal (best deterministic score at/above the acceptance floor) — including the
ones the heuristic scorer is already confident about — and it may only pick from
the real candidates MyAnimeList returned, so it can never invent an id. Its pick
wins; the deterministic scorer is the fallback when the model is absent or
abstains ("none fit"). Below the signal floor nothing is genuinely related to the
file's title, so the row **abstains** — the closest lookalike is never pushed,
which is what keeps false friends and clips out. Successful picks are remembered
as aliases.

- Model: **Qwen2.5-0.5B-Instruct**, Apache-2.0, run fully on the phone via
  MediaPipe LLM Inference on CPU. It is **not bundled** in the APK.
- Nothing is downloaded automatically. Tap **AI** to download it (~521 MB) or
  side-load it to `Android/data/com.bridge.mx/files/llm/model.task`.
- **Without the model everything still works** — the app silently falls back to
  the deterministic matcher, and the status line shows `ai no model`.

## Title catalogue (animeTosho) and the exam

Matching needs to know the *real* universe of anime titles, and it needs to
keep up with new seasons. The catalogue is refreshed from
**[animeTosho](https://animetosho.org)** — deliberately **not** from MAL, so
refreshing needs no login and never touches the user's list.

- **Bundled bulk:** `app/src/main/assets/anime_titles.txt` — the scraped series
  set (≈9,952 titles) ships inside the APK. First run always works offline.
- **Refresh:** `Catalogue.refresh()` walks `animetosho.net/animes?page=N`
  (animeTosho, **.net only** per directive — no `.org`), gzip-aware,
  ~40 rows/page, then **merges only-add** onto the current list — a refresh can
  never delete a title. Written atomically to `filesDir/catalogue.txt`.
- **Cadence:** considered stale after **75 days** (the "2–3 months" window),
  with a 1-day retry back-off on failure. The background service refreshes
  itself on its own thread when stale; the **Titles** button forces it.
- **Study, then is tested:** every time the catalogue grows, the app
  **studies** it (deriving shorthand aliases only where a shorthand maps to
  exactly one catalogue title) and then takes its own **exam** — the same
  deterministic pipeline the reward system uses. The exam is pure JVM, so it
  is unit-tested too (`CatalogueTest`, `BundledCatalogueTest`).

## Self-repair when the matcher makes too many mistakes

The bridge must **never** pretend it can work unattended when it *fails its own
exam* (grade below a C, accuracy < 0.70). When that happens — after a title
refresh, or from the **Test** button — it escalates instead (`Repair.java`):

1. writes a self-contained **incident report** (Markdown: exact mistakes,
   app/device context, catalogue state) to
   `Android/data/com.bridge.mx/files/repair/incident-*.md` (+`LATEST.md`), so a
   host can pull it over adb;
2. **turns on wireless debugging** — directly when it holds
   `WRITE_SECURE_SETTINGS` (a one-time
   `adb shell pm grant com.bridge.mx android.permission.WRITE_SECURE_SETTINGS`),
   otherwise deep-links to Developer Options;
3. posts a notification; and
4. POSTs the report to a host watcher if `repair/bridge.txt` names one.

The desktop companion, **`tools/opencode_repair.ps1`**, closes the loop:
it enables adb-over-Wi-Fi (`adb tcpip 5555` + `adb connect`), pulls the
incidents, and opens a **fresh OpenCode session** primed with the report —
`opencode run --auto --agent <agent> --prompt <incident>` — to diagnose the
root cause, fix it, rebuild and reinstall.

```powershell
# one-shot, over Wi-Fi, then open an OpenCode session to fix
powershell -ExecutionPolicy Bypass -File tools\opencode_repair.ps1 -Wireless
# or keep watching; or receive the app's direct POSTs
powershell -ExecutionPolicy Bypass -File tools\opencode_repair.ps1 -Wireless -Watch
powershell -ExecutionPolicy Bypass -File tools\opencode_repair.ps1 -Serve -Port 8765
```

## Automatic MAL repair of bogus writes

A 2026-10 bug made the color classifier read untagged neutral-grey rows
(label-less files, 0x888888 and friends) as "finished" and push watch progress
for them to the user's MAL account (31 writes / 23 series at the audit). MAL
has no undo and the account is the user's proof-of-watch, so the app now
repairs such writes **automatically** and conservatively:

> **Known correction (Oct 13 2026):** the first daily auto-repair run
> (Oct 12) withdrew **18 series** — and the user confirmed **every one was a
> real watch**. In this deployment "no playback evidence" is *not* proof of
> "not watched": (a) playback tracking (accessibility service) is off, so
> watched % / ledger rarely record; and (b) the scan scope is
> `/storage/emulated/0/Download` only, so files on the SD card (e.g. Uchi no
> Shishou, 49533) are invisible to the app by design. All 18 were restored to
> their true MAL states via API (the 14 still-flagged ones on Oct 13: watching
> counts 1–15 + Youjo Shenki completed). The grey→`none` classifier fix is what
> actually stops phantom writes; post-fix, grey rows never become pushable, so
> this false-positive class cannot recur. The auto-repair remains inert unless
> a future classifier regression re-creates grey-origin pushes.

- **What qualifies**: a series whose rows still look like the artifact —
  pushed (`applied != ""`), neutral-grey untagged, with no playback evidence
  (row watched %, watched ledger, or a real non-grey tag on ANY sibling row of
  the series). A series with any real evidence is never touched.
- **When it withdraws**: only when MAL currently holds *exactly* what we
  pushed (same status and episode count) **and** the entry has no user score
  ("rated = genuine"). Anything the user rated or has since moved on is left
  alone.
- **How**: `plan_to_watch|0` (MAL v2 has no delete endpoint; this erases the
  watched count/score). Rows are neutralized to `none` + `plan_to_watch|0`
  and the series id is shielded so it can never be re-pushed from artifact
  rows. If real evidence later appears (playback/tag/ledger), the shield lifts
  automatically and the rows are handed back to the normal pipeline.
- **When**: once a day from the background service (`MalRepair.autoRepair`), or
  on demand via the Repair button / QA hook below.

QA hooks (deterministic, no UI taps): `adb shell am start -n
com.bridge.mx/.MainActivity --ez self_test true` runs the exam + escalation on
launch.

MAL-repair QA hooks:

```powershell
# Dry run: plan the repair, write nothing (prints the would-be counts):
adb shell am start -n com.bridge.mx/.MainActivity --ez repair_dry true
# Live run: withdraw the bogus writes now (no 24h cooldown):
adb shell am start -n com.bridge.mx/.MainActivity --ez repair true
```

Title-refresh QA hooks (deterministic, no UI taps):

```powershell
# Force the animeTosho refresh + study + exam of any new bulk:
adb shell am start -n com.bridge.mx/.MainActivity --ez titles true
# Point the crawl at a local mirror of the listing (saved page bodies) instead
# of the live site — for exercising the full pipeline offline. Works behind
# `adb reverse tcp:8890 tcp:8890` with a server on 127.0.0.1:8890:
adb shell am start -n com.bridge.mx/.MainActivity --ez titles true --es at_base http://127.0.0.1:8890
```

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
- `Matcher.java` — deterministic MAL search-result scoring + LLM-first
  orchestration with the acceptance/signal floor
- `Aliases.java` / `AliasStore.java` — shorthand alias table + persistence
- `MatchBrain.java` / `LlmBrain.java` / `Grounding.java` — grounded on-device
  LLM matcher (prompt, parsing, MediaPipe engine)
- `MalClient.java` — PKCE login, token refresh, search, status update
- `Store.java` — row persistence (SharedPreferences), change listeners
- `Report.java` / `BridgeApp.java` — error reports + uncaught-exception capture
- `Catalogue.java` — animeTosho title refresh (75-day staleness, only-add merge)
- `Educate.java` — studies the catalogue into unambiguous shorthand aliases
- `Exam.java` / `Selftest.java` / `Reward.java` — the deterministic exam, its
  self-test wrapper and the grade/reward ledger
- `Repair.java` — too-many-mistakes escalation (incident report + wireless
  debugging + notification + host bridge)
- `tools/opencode_repair.ps1` — host watcher: pull incidents, enable adb over
  Wi-Fi, open an OpenCode repair session

## License

[GNU General Public License v3.0](LICENSE) — see [`LICENSE`](LICENSE).

## Declaration

**This project was made with AI.** It was conceived, coded, built and
published by an AI agent with a human directing it. There is no human-written
source code in this repository.
