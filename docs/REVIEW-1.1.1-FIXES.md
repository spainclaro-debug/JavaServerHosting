# Independent verification — v1.1.1 review fixes

Reviewer: `verifier` (read-only). Date: 2026-11-14.
Scope: the six should-fix items from `docs/REVIEW-1.1.md`, plus the v1.1.1
artifact/version state. No Gradle run was needed/started.

**Verdict: no blockers.** All six fixes are implemented and behave as
described. One low-priority residual remains (folder import still copies a
whole incompatible runtime before failing validation). Artifact, version,
signing, tests and docs are consistent.

Source snapshot: newest source file 23:54:53; build log `v111-build2.log`
23:55:31 (`BUILD SUCCESSFUL`, `:app:testDebugUnitTest` executed); dist APKs
23:55:52. **No source/docs file is newer than the build.**

## Artifact / version check (PASS)

- `dist/JavaHost-1.1.1.apk` sha256
  `86856b57dd22fb3f08ef94373818173b597a0d330123c254eb581ecd4e8c9ed7`
  (9,076,836 B) — matches `dist/README.md` and the Lead's hash.
  Package `dev.javahost`, **versionCode 3**, versionName `1.1.1`, minSdk 26 /
  targetSdk 28, launcher `dev.javahost.MainActivity`.
- `dist/JavaHost-1.1.1-debug.apk` sha256
  `0c39c19420579d13344512085b3e8f2e5ffbee4ae8e155a85029aeeb6a2f61c5`
  (13,277,986 B), `dev.javahost.debug`, matches `dist/README.md`.
- Release signing: V2+V3 verified, certificate `CN=JavaHost Dev`
  sha256 `674a77ab…510b13` — **same dev key as 1.1.0/1.0.0**, so v1.1.1 installs
  as an in-place upgrade over v1.1.0.
- Security posture unchanged: not debuggable, `android:allowBackup="false"`,
  no compose `PreviewActivity`, same 8 permissions (+ dynamic-receiver
  permission), no MANAGE_EXTERNAL_STORAGE/root,
  `ForegroundServerService` `exported=false`/`stopWithTask=false`/
  specialUse + property; `assets/jna/5.13+5.19`, `silence` resource
  (`res/y1.wav`) and monochrome `ic_stat_server` present; no JRE/archive
  bundled; both zips pass `unzip -t`.
- `versionCode 3` / `versionName 1.1.1` in `app/build.gradle.kts`; BuildConfig
  user-agent bumped to `JavaHost/1.1.1`.
- Tests: `app/build/test-results/testDebugUnitTest` = **249 tests, 0 failures,
  0 errors, 2 skipped** (same two environment-gated skips; no new tests were
  added in 1.1.1).
- `dist/` is clean: only the two 1.1.1 APKs + README; no stale 1.0.0/
  1.1.0 APKs or `.idsig`.

## Fix-by-fix verification

### 1. Folder-import cleanup, limits, guard, sanitisation — PASS

`ui/AppViewModel.kt`:
- **Temp assigned before the copy** (`:280`, then `copyDocumentTree` `:281`), so
  the copy-failure case now has a non-null `temp`; cleanup runs in
  `withContext(NonCancellable + Dispatchers.IO)` (`:299-303`), so it also runs
  when the ViewModel scope is cancelled.
- **Only the detected Java home is copied**:
  `locateJavaHomeDocument(root)` (`:271`, impl `:313-332`) BFS-searches the SAF
  tree for a directory containing `bin/java` (depth ≤ 5) and
  `copyDocumentTree(home, …)` copies that subtree only — an accidentally
  selected `Download/` parent is no longer copied wholesale.
- **Caps**: `MAX_JRE_SEARCH_DEPTH=5` (`:862`), 1.5 GB byte budget
  (`:863`, enforced per chunk at `:368-371`), 20,000 files (`:864`, enforced
  `:355-357`); all inside `withContext(Dispatchers.IO)` — no UI-thread IO.
- **Duplicate-import guard**: `jreFolderImportJob` (`:72`) checked at `:261`
  and cleared in `invokeOnCompletion` (`:306-309`); UI buttons are also
  disabled during either import (see #5).
- **Sanitisation**: `copyDocumentTree` uses
  `SafePaths.sanitizeFileName(child.name, fallback = "file")` (`:345`), which
  rejects `.`/`..`/separators/control characters; the VM-local
  `sanitizeFileName` now delegates to `SafePaths` (`:840-841`).
- Cancellation checks: `currentCoroutineContext().ensureActive()` per child and
  per 64 KiB chunk (`:341, :363`) — a cancelled import throws, and the
  `finally` cleanup removes the partial copy.

### 2. Instance-aware console history — PASS

- `core/api/ServerController.kt:48` adds `readStartupTail(instanceId, lines)`
  (default `emptyList()`), and `ServerManager` now overrides it
  (`core/server/ServerManager.kt:541-555`): returns the live tail only when
  `session.instanceId == instanceId`, then the matching crash tail, then
  **per-instance** disk candidates — app transcript first
  (`logs/console.log`, `console.previous.log`), then the server's own
  `logs/latest.log`/`previous.log`, then `last-crash.txt`
  (`:562-567`). No other instance's live lines can be returned.
- `ui/AppViewModel.kt:603-608` now calls
  `container.server.readStartupTail(instanceId, lines)` (previously the
  instance-agnostic `recentTail` + Minecraft log path).
- `ui/screens/ConsoleScreen.kt:337` hides timestamps for history entries
  (`showTimestamps && entry.timestamp > 0L`), so synthetic `timestamp = 0L`
  history lines no longer render fake epoch clock times.

### 3. Cancellation before chmod/finalize — PASS

`core/runtime/JreManager.kt`: `runImportDirectory` and `runImport` are now
`suspend` and call `currentCoroutineContext().ensureActive()` before the
directory scan (`:414`), before staging (`:445`), before the completeness/chmod
phase (`:454`), and **before `finalizeInstallation`** (`:481`); archive import
has the same checks (`:536`, `:588`). A cancelled import therefore throws,
`finally` deletes the staging tree (`succeeded=false`), and
`importDirectory`/`importArchive` publish `CANCELLED` instead of committing.
Archive-import validation is unchanged: `validateRuntime(imported.home, …)` is
still applied to extracted archives (bionic/arm64 checks retained).

### 4. Bounded package-index fetch — PASS

`core/net/OkHttpDownloader.kt:152-155`: `fetchBytes` now uses a derived client
with `callTimeout(FETCH_CALL_TIMEOUT_SECONDS)` = **45 s** (`:231`), so a mirror
that passes the 4 s HEAD probe but stalls on the body fails after 45 s and the
fallback chain continues (previously the shared client had
`callTimeout(0)`/`readTimeout(120 s)`). `callTimeout` covers connect + headers +
full body.
- No regression for the other `fetchBytes`/`fetchText` users: JRE
  `Packages.gz` (~732 KB) and Modrinth JSON responses are small and complete far
  inside 45 s; the large-file `download()` path (JRE debs, Modrinth jars) is
  untouched and still uses the shared client (no 45 s cap on multi-MB
  downloads).
- `DEFAULT_USER_AGENT` bumped to 1.1.1 (`:232`).

### 5. `JreScreen` import-button state + wording — PASS

`ui/screens/JreScreen.kt`:
- "Choose archive…" `enabled = !importing && !importingFolder` (`:130`) and
  "Choose folder…" `enabled = !importingFolder && !importing` (`:209`) — the two
  long imports cannot be started concurrently from the UI (VM guard is a second
  line of defence).
- Added the SAF-visibility bullet ("The folder must be visible in the system
  file picker…", `:214`).
- Verification label corrected to `SHA-256 from HTTPS index` (`:296`).

### 6. Docs / stale artifacts — PASS

- `README.md`: dist links now 1.1.1; size line is "~9 MB release APK (~13 MB
  debug) + ~96–117 MB JRE download + ~170 MB JRE installed".
- `docs/ARCHITECTURE.md`: same size correction.
- `docs/REVIEW-RESPONSE.md`: notes the fixes shipped in 1.1.x (not 1.0.0) and
  tells users to use `JavaHost-1.1.1.apk` or newer.
- `CHANGELOG.md`: new 1.1.1 section describing the six fixes.
- `dist/README.md`: v1.1.1 hashes/table/changelog match the files; stale
  1.0.0 artifacts and `.idsig` are gone from `dist/`.
- No remaining `JavaHost-1.0.0` / `JavaHost-1.1.0` references in
  README/CHANGELOG/dist/docs (grep clean; historical `docs/REVIEW.md`/
  `REVIEW-1.1.md` intentionally describe the reviewed baselines).
- Dead `TermuxMirrors.probeCandidates` helper removed; no references remain.

## Residual (should-fix, low) — pre-copy bionic validation

`ui/AppViewModel.kt:271-281` copies the detected Java home into the cache
**before** the runtime is validated; validation (`JreManager.kt:492-517`,
`JreRuntimeValidation.looksLikeAndroidBionic`) then runs on the cache copy at
`JreManager.kt:422`. So selecting an Oracle/Adoptium **glibc** JDK — the exact
case the feature advertises rejecting — copies the whole ~250–300 MB runtime
into cache first and only then rejects it (cleanup does run, so no leak; this is
time/IO waste, most visible on a slow phone with little free cache).
Cheap fix: before `copyDocumentTree`, open the found `bin/java` via
`DocumentFile`/`ContentResolver` and read just the first 8 KiB to run the same
`/system/bin/linker` check, then reject before copying. Not release-blocking.

## Notes (not defects)

- No new hermetic tests were added for the 1.1.1 changes (still 249); the fixed
  paths are mostly Android/SAF/coroutine glue. `SafePaths.sanitizeFileName` and
  the ELF/arm64 validation are already covered from 1.1.0.
- `OkHttpDownloader.fetchBytes` derives a client per call (shares connection
  pool) — negligible overhead.
- On-device execution of SAF folder import, the glibc rejection flow and
  notification rendering remains manual-only (`docs/TESTING.md`), as documented.
