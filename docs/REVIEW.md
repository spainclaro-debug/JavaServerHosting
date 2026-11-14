# Independent release review — JavaHost 1.0.0

Reviewer: `verifier` (independent; no production files modified).
Date: 2026-11-14 (Asia/Manila).
Verdict: **no ship-blockers found in the tested artifacts.** There are several
should-fix correctness/UX defects (none crash the app or break install/start),
and a set of documentation/claim fixes. On-device behaviour (foreground service,
Doze, phantom-process killer, notification rendering) could not be executed
here — no device/emulator — and is explicitly marked untested in
`docs/TESTING.md`, which is honest.

Source tree at review time: last source edit 2026-11-14 23:14:11
(`ui/AppRoot.kt`, cold-start deep-link fix). No source changes after the final
23:15–23:17 build (verified with `find app/src -newermt`). The reviewed artifacts
were built **after** that edit.

## Artifacts verified

| Artifact | SHA-256 | Size | Package | Signing |
|---|---|---|---|---|
| `dist/JavaHost-1.0.0.apk` (release) | `c853a4a3394b67682d8e643206753eee42257871559ec82805a17bff1f5e12de` | 9,044,012 B | `dev.javahost` 1.0.0 | V2+V3, RSA-4096, `CN=JavaHost Dev` |
| `dist/JavaHost-1.0.0-debug.apk` = `app/build/outputs/apk/debug/app-debug.apk` | `44b6b11f809dc8fe11a5a418fde7a514aa2f46ead6c3f8a113cb051e4d1914fe` | 12,835,692 B | `dev.javahost.debug` 1.0.0-debug | V2, Android debug key |

Hashes match `dist/README.md`. Both zips pass `unzip -t`; `apksigner verify`
passes for both. `app/build/outputs/apk/release/app-release-unsigned.apk`
(`3c1a93…`) is the pre-signing release package.

## Verification matrix

### APK / manifest (PASS)

- minSdk 26, targetSdk 28, `versionCode 1`, launcher `dev.javahost.MainActivity`
  (release badging; `.debug` suffix + `android:debuggable` only in the debug APK).
- Permissions are exactly: INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE,
  WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE,
  POST_NOTIFICATIONS, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS. **No
  MANAGE_EXTERNAL_STORAGE, no root/shell or other dangerous permissions.**
- `ForegroundServerService`: `exported=false`, `stopWithTask=false`,
  `foregroundServiceType=0x40000000` (specialUse) +
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` string present (release manifest xmltree).
- `FileProvider` (`${applicationId}.fileprovider`) `exported=false`,
  `grantUriPermissions=true`; `androidx.startup` provider non-exported.
- Release APK does **not** contain `androidx.compose.ui.tooling.PreviewActivity`
  (debug-only, exported there) and is not debuggable.
- `network_security_config`: `cleartextTrafficPermitted=false` compiled into the
  APK; all code URLs are HTTPS.
- Assets: `assets/jna/5.13/libjnidispatch.so` and `assets/jna/5.19/…` present
  (168,176 B each); `res/raw/silence.wav` present in debug
  (`res/raw/silence.wav`) and release (resource-optimizer renamed to
  `res/y1.wav`, same 16,044 B; `R.raw.silence` resolves). **No JRE archive / deb
  / tar / zip is bundled** (0 matching entries).

### JNA dispatcher selection — empirically validated (PASS)

Asset bytes were compared with the real JNA AARs present in the test fixtures:

- `assets/jna/5.13/libjnidispatch.so` == `jna-5.13.0.aar!/jni/arm64-v8a/…`
  (sha256 `95fd61…`).
- `assets/jna/5.19/libjnidispatch.so` == `jna-5.14.0.aar!/jni/arm64-v8a/…`
  (sha256 `3730ce…`).

Loaded each asset with JNA Java jars on the device's bionic JDK 21
(`-Djna.boot.library.path`, `Native.POINTER_SIZE`):

| JNA jar | 5.13 native (6.1.6) | 5.14 native (7.0.0) |
|---|---|---|
| 5.13.0 | LOADED | Error: incompatible (expected 6.1.6, found 7.0.0) |
| 5.14.0 | Error: incompatible | LOADED |
| 5.19.0 | Error: incompatible (expected 7.0.4) | **LOADED** |

This matches `JnaCompat.assetFor()` (≤5.13 → 5.13 binary, 5.14+ → 7.x binary)
and the rationale in `JnaCompat.kt:17-22`: the 5.x ABI is major-version-gated, so
one 7.x dispatcher serves 5.14–5.19. `detectJnaVersion` regex/`walkTopDown` and
the `.jna-version` marker are consistent with this.

### Tests (PASS)

`app/build/test-results/testDebugUnitTest` (23 classes): **242 tests,
0 failures, 0 errors, 2 skipped**. Both skips are environment-gated:
`JarInspectorTest."real vanilla wiremock and h2 jars…"` (needs
`JAVAHOST_TESTTARGETS`) and `RealTermuxArchiveSmokeTest` (needs its fixture var).
I independently ran `:app:testDebugUnitTest` after the last source edit:
**BUILD SUCCESSFUL** (compiled the current source tree; no failures).
The real-JAR skip was independently covered below.

### Integration seams / lifecycle (static review — no crash-level defects found)

- `ForegroundServerService`: `startForeground` is the first container-free action
  in `onStartCommand` (`:117`, `ensureForeground()` `:574-595`); Wake/Wi-Fi locks
  acquired before start and released on every finish path
  (`:189-243`, `:450-482`, `:670-681`); `START_STICKY` + null-intent
  `adoptExistingSession()`; crash/start-rejection alerts; auto-restart with
  attempt cap, delay and manual-stop guard (`:500-568`); `onDestroy` releases
  locks/media and calls `server.shutdown()`.
- `ServerManager`: single-JVM guard (`tryLock` + non-null session, `:140-158`),
  `ProcessBuilder(List<String>)` with no shell, env `JAVA_HOME`/
  `LD_LIBRARY_PATH`/`PATH`/`HOME`/`TMPDIR`, merged stderr→stdout reader,
  ring buffer + 100 ms publisher, transcript/crash persistence, graceful
  stop (Minecraft `stop` + 20 s, generic 2 s → SIGTERM → 3 s → SIGKILL),
  crash classification, STARTING→RUNNING fallback after 60 s.
- `JreManager`: mirror fallback, Packages integrity, dependency closure (skips
  `alsa-plugins`, includes `ca-certificates-java`), streaming ar/deb extraction,
  SHA-256 verification, reaper of stale staging, staging→home swap with backup
  rollback, marker written last, cacerts overlay, chmod tree, execute-bit
  self-heal, import path.
- `AppContainer` constructors match real classes (`JreManager(context, settings,
  downloader)`, `ServerManager(context, paths, instances, settings, jre)`,
  `ModrinthRepository(downloader, instances, paths)`).
- `AppViewModel` gates: JRE installed, Minecraft EULA accepted, one-JVM-at-a-time,
  jar copied into the instance dir, settings/instance persistence,
  start via foreground service. Cold-start notification deep-link race was fixed
  during the review (`AppRoot.kt:67-83`, now waits for the instance flow).

### UI reachability / honesty (PASS with defects listed below)

All `Route`s are reachable from `AppRoot`; Minecraft-only routes are guarded both
at the entry buttons (`ServerDetailScreen.kt:253-259`,
`FilesScreen.kt:223-227`) and at render time (`AppRoot.kt:182-202`). Generic
instances are labelled **Advanced / Experimental** (`CreateInstanceScreen.kt:450-464`,
`HomeScreen.kt:392`, wizard). No shader button exists (grep clean). The
silent-media feature flag is false in `BuildConfig`, the setting is disabled and
explained, and `SilentMediaKeepAlive.isEnabled()` gates on both. No dead
`onClick = {}` handlers were found (the crash-dismiss defect below is a state
bug, not an unwired handler).

### Security (PASS; notes below)

- No traversal: archive entries validated in `ArchivePaths.resolveChild`
  (textual `..`/absolute rejection + canonical containment, symlink targets
  checked); `FileBrowser`/`SafePaths` canonical containment for every
  read/write/delete/rename/import; `ServerInstance.jarFile` re-validated in
  `ServerManager.startInternal` (no separators + canonical containment).
- No shell: launch is `ProcessBuilder(List<String>)`; user args go through
  `ArgTokenizer` only.
- PendingIntents use `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`
  (`ForegroundServerService.kt:734`).
- Exported components: launcher Activity only (debug build additionally exports
  compose tooling `PreviewActivity`, as expected for a debug APK); the
  `ProfileInstallReceiver` is the standard androidx receiver protected by
  `android.permission.DUMP`.
- No API keys/secrets/tokens in the tree (grep over `.kt/.kts/.md/.xml/.json/
  .properties`); Apache-2.0 `LICENSE` + `NOTICE` with credits present.
- AGPL check (high level only, as required): TinyMCServer has analogous file
  names (JRE/server/JNA storage classes) but JavaHost is an independent English
  implementation; a long-line comparison found only generic Compose idioms in
  common, and `JnaCompat` differs materially (two versioned assets + ABI
  selection vs a single bundled 5.19 library; different API/comments). No
  evidence of copied AGPL code.

### Live smoke checks (PASS)

- **Sample generic server** (`tools/sample-generic-server/generic-server.jar`)
  booted with the validated Android/bionic JRE
  (`/data/data/com.termux/files/home/jre-debs/sim2/jre/bin/java`,
  `openjdk 21.0.13`): `GET /health` → `ok`,
  `GET /` → `{"status":"ok","server":"javahost-sample",…}`.
- **Modrinth API** (same UA/facets/URL shapes the app builds):
  `/search` HTTP 200 with hits; `/project/worldedit/version?game_versions=["1.20.4"]&loaders=["paper"]`
  HTTP 200 with files; downloaded the primary file → HTTP 200, 6,391,183 B
  (matches advertised size) and SHA-1 `d1bcbfed…` matches the API value.
- **Termux mirror** (primary `packages.termux.dev`): `Packages.gz` HTTP 200,
  732,452 B → 3,029 stanzas; `openjdk-17/21/25` all present with `Filename`,
  `Size`, `SHA256`; every dependency of `openjdk-21` (incl.
  `ca-certificates-java`, `libandroid-shmem`, `libandroid-spawn`, `libiconv`,
  `libjpeg-turbo`, `zlib`, `littlecms`) is present; fallback TUNA mirror also
  200. `openjdk-21` Windows-metadata: `Installed-Size: 249160` (pre-trim),
  download 106,011,440 B — consistent with README's ~110 MB download.
- **JarInspector on real jars** (harness over the compiled classes): vanilla
  1.20.4 → MINECRAFT/VANILLA, MC 1.20.4, Java 17; Paper 1.20.4 → PAPER (Paperclip);
  WireMock → GENERIC; H2 → GENERIC (multi-release base Java 8); sample server →
  GENERIC — matching `tools/DETECTION-NOTES.md`.

## Findings

### Blockers

**None.** Nothing found that crashes at launch, breaks install/signing, breaks
the foreground service contract, or leaves a documented primary feature
unreachable.

### Should-fix

1. **Instance deletion runs on the UI thread and races a running JVM.**
   `AppViewModel.deleteInstance` (`ui/AppViewModel.kt:346-353`) calls
   `container.instances.delete(id)` synchronously; `InstanceStore.delete` →
   `Paths.deleteInstanceFiles` → `File.deleteRecursively()` walks the whole
   instance dir on the main thread. A large world (thousands of files) can ANR.
   The stop request (`ForegroundServerService.stop(force=true)`) is
   asynchronous, so files are deleted while the child JVM may still be writing
   (world save/recreate), and the confirm dialog claims the server "will be
   stopped first". Fix: stop/await first and delete off the main thread.
2. **Normal stops are reported as crashes on Home, and the banner goes stale.**
   `ServerManager.finishSession` always publishes `runtime.lastExit`, including
   expected exits (`ServerManager.kt:890-924`); `AppViewModel.kt:96` stores
   `lastCrash = runtime.lastExit ?: it.lastCrash` without checking
   `expected`, and `HomeScreen.kt:108,183` shows `CrashBannerCard` for any
   non-null `lastCrash`. Result: after a user Stop the home screen says "Server
   stopped unexpectedly" (reason "Stopped by user"), and the banner survives
   into the next run until dismissed. Filter `expected` and clear on start.
3. **"Dismiss report" on the server screen is a no-op.**
   `ServerDetailScreen.kt:95` computes `showCrash` from persisted
   `live.lastCrashSummary`, but `AppViewModel.dismissCrash` (`:139-141`) only
   clears transient `state.lastCrash`; the existing `ServerManager.clearLastCrash()`
   (`core/server/ServerManager.kt:525-532`) is never called. The card stays
   (with its details gone) and reappears on cold start.
4. **Power/notification status is stale after returning from system screens.**
   `refreshPowerChecks()` is only called from VM init, the notification-permission
   callback and the media toggle (`AppViewModel.kt:69,159,459`); no screen
   refreshes on resume. After granting the battery exemption the wizard/Settings/
   PowerGuide keep showing "Not exempt" (and the Done summary is stale) until
   the process restarts. Add a lifecycle-resume refresh.
5. **Console buffer is global, not per-instance, and the on-disk tail is never
   loaded.** `ServerManager` keeps one in-memory console; `ConsoleScreen` renders
   it even when `attached=false` (`ConsoleScreen.kt:117-130,307`), so opening a
   stopped instance's console can show another server's lines. `readStartupTail`
   exists in the contract/VM (`ui/AppContract.kt:107`, `AppViewModel.kt:433`)
   but has no caller, so after a process restart the console is empty although
   `logs/console.log` exists. Tag entries with the instance id (or reset on
   start) and wire the historical tail.
6. **JRE import UI claims `.deb` support that the importer rejects.**
   `JreScreen.kt:117` says "Supported: Termux .deb packages and .zip / .tar.xz",
   but `JreArchiveImporter.detectFormat` returns UNKNOWN for ar/deb and the
   error says "If this is a .deb file, use the built-in installer instead"
   (`core/runtime/JreArchiveImporter.kt:68-72`). Fix the copy or implement deb
   import.
7. **`jreReady` false-negative.** `AppUiState.jreReady`
   (`ui/AppContract.kt:53`) requires the *default* major to be installed, so a
   device with only Java 17 installed still shows "No Java runtime is installed"
   on Home / wizard Done. Use "any installation" for the banner, and keep the
   per-major check only for the start gate.
8. **Release-key / artifact hygiene.** `.signing/javahost-release.jks` (the
   development release key) and `dist/` (22 MB of APKs) live in the project tree
   and neither is in `.gitignore`; a stray `sample.pid` sits at the repo root.
   Not a runtime bug, but before publishing the tree/repo: gitignore
   `.signing/`, `dist/`, `*.idsig`, `*.pid`, and keep the keystore out of any
   upload (the docs already say it is a development key, not a Play key).
9. **Silent-media description contradicts the code.**
   `SettingsScreen.kt:224` says it "Holds an audio focus", while
   `SilentMediaKeepAlive.kt:14-23,52-55` deliberately does **not** request audio
   focus. The feature flag is off in this build so the toggle is disabled, but
   the text should match the behaviour.
10. **"New instances start with automatic restart enabled" is false.**
    `SettingsScreen.kt:127`; `ServerInstance.autoRestart` defaults to false and
    `AppViewModel.createInstance` (`:309-324`) never copies
    `settings.autoRestartOnCrash`. Either implement or reword.
11. **Notification small icon is the launcher vector with a full-bleed
    background** (`ic_launcher.xml:8-12`; used at
    `core/service/ServerNotifications.kt:144,209`), so the status-bar icon
    renders as a solid block. Add a monochrome white-on-transparent
    notification icon.

### Nice-to-have

- **Installed-size claims disagree.** `JreScreen.kt:125` and
  `WizardScreen.kt:327` say "installed size is roughly 250–300 MB"; the measured
  trimmed JRE is ≈162 MiB/169.8 MB (`docs/TESTING.md`) and the live package's
  pre-trim `Installed-Size` is 249,160 KB. Align the UI with the docs.
- **"Downloads can be cancelled and resumed"** (`WizardScreen.kt:328`): cancel
  deletes the staging tree and downloaded debs (`JreManager.kt:326-331,326-331`);
  a retry restarts from scratch. Say "cancelled and retried", not "resumed".
- **"SHA-256 from signed index" wording** (README, `JreScreen.kt:126`,
  `WizardScreen.kt:281,326`, `docs/POLICY.md`): the app fetches the index over
  HTTPS and verifies each deb against the `SHA256` field, but does not verify
  the index's GPG signature, and does not compare the deb against the stanza
  `Size` beyond SHA-256/HTTP content-length. Clarify so the claim is exact.
- **`docs/ARCHITECTURE.md:68`** says `START_STICKY` "re-adopts a surviving
  session after a service restart". `ServerManager`'s session is in-memory and
  `onDestroy` calls `shutdown()`, so there is no cross-process pid adoption
  path; adoption only works while the same process still holds the session.
  Reword to describe the actual guarantee.
- `FileProvider` is declared (`AndroidManifest.xml:47-55`) but unused (no
  `FileProvider` reference in code) — remove or use it for export/share.
- `android:allowBackup="true"` (`AndroidManifest.xml:16`): with targetSdk 28
  adb backup can include server dirs/JRE. No secrets, but consider
  `allowBackup=false` or backup rules for a server host.
- `JreArchiveImporter.extractZip` (`:190-221`) has no entry-count/decompressed-size
  cap (tar path is bounded by entry size). User-selected local file, low risk.
- Heap-floor constants disagree (`MemoryMath.MIN_HEAP_MB=384` vs
  `LaunchPlanBuilder.MIN_HEAP_MB=256` vs UI slider minimum 256) — cosmetic.
- Console export reports success before the async export finishes
  (`ConsoleScreen.kt:153-164`); a later failure only surfaces as a VM message.
- `dist/README.md` is accurate about the debug APK being for development;
  don't ship `JavaHost-1.0.0-debug.apk` as "the release" (debuggable + exported
  tooling activity).

## Residual risk / not executed here

No Android device or emulator is available in this environment, so the following
remain design-reviewed only (and are marked as such in `docs/TESTING.md`):
actual FGS start/notification rendering, Doze/phantom-process survival, swipe
from Recents, lock behaviour on real hardware, first Paper boot on device, SAF
picker/export on a real provider, and `ProcessBuilder`+JRE `exec()` through the
app sandbox. The host-level evidence (JRE runs, sample server, real JAR
detection, Modrinth/Termux endpoints) supports the runtime design but is not a
substitute for the manual device checklist.
