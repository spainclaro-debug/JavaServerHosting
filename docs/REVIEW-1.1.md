# Independent review — JavaHost 1.1.0 (focused follow-up)

Reviewer: `verifier` (read-only on production code; no Gradle run was needed).
Date: 2026-11-14. Source snapshot `23:46:36`; no source changes after the final
23:45 build (`find app/src -newermt 23:45:30` empty). Reviewed artifacts are
post-build.

Verdict: **no ship-blockers** in the v1.1.0 changes or artifacts. The two new
features (fastest-mirror probe/presets, SAF folder JRE import + bionic/arm64
validation) are implemented coherently and the advertised fixes from the 1.0.0
review are present. Four should-fix defects remain in the new folder-import /
console-history code, plus some doc leftovers.

## Artifact / version verification (PASS)

- `dist/JavaHost-1.1.0.apk` sha256 `3d451c5a…bad9a`, 9,060,452 B; package
  `dev.javahost`, **versionCode 2**, versionName `1.1.0`, minSdk 26/targetSdk 28.
  V2+V3 signed; certificate `CN=JavaHost Dev` sha256
  `674a77ab…510b13` — **same dev key as v1.0.0**, so in-place upgrade works.
  Not debuggable, `android:allowBackup="false"`, no compose tooling
  `PreviewActivity`; permissions unchanged (8: no MANAGE_EXTERNAL_STORAGE/root);
  `ForegroundServerService` exported=false/stopWithTask=false/specialUse.
- `dist/JavaHost-1.1.0-debug.apk` sha256 `d49f139f…7b7c` = `dev.javahost.debug`
  1.1.0-debug, V2 debug key. Both hashes match `dist/README.md`; both zips pass
  `unzip -t`.
- Assets/resources: `assets/jna/5.13+5.19/libjnidispatch.so` present,
  `res/raw/silence.wav` present in the release package (optimized path
  `res/y1.wav`, 16,044 B), and the new **monochrome `ic_stat_server`** is
  compiled into both APKs and used by both notification builders
  (`ServerNotifications.kt:144,209`). No JRE/deb/tar/zip bundled (0 entries).
- Build/test logs: `v110-build*.log` BUILD SUCCESSFUL; test XMLs
  **249 tests, 0 failures, 0 errors, 2 skipped** (same two environment-gated
  skips); new `JreRuntimeValidationTest` 5 tests, `TermuxMirrorsTest` 9 tests.

## 1. Mirror presets + parallel probe (PASS with a timing caveat)

Verified live from this device: **all six preset URLs** serve
`…/binary-aarch64/Packages.gz` with **GET 200** (~732 KB) and support
**HEAD 200** (so `OkHttpDownloader.probe()`'s HEAD assumption is valid):

| mirror | HEAD | GET |
|---|---|---|
| packages.termux.dev | 200, 0.65 s | 200, 732,452 B |
| TUNA | 200, 0.53 s | 200, 732,614 B |
| MWT | 200, 0.08 s | 200, 732,221 B |
| Grimler | 200, 0.67 s | 200, 732,452 B |
| USTC | 200, 1.14 s | 200, 732,614 B |
| BFSU | 200, 2.31 s | 200, 732,614 B |

Static review of `TermuxMirrors.candidates` (`TermuxMirrors.kt:73-79`) and
`JreManager.orderMirrors` (`JreManager.kt:707-735`):
- **user-pinned mirror keeps priority**: `orderMirrors` returns the list
  untouched when `settings.current().termuxMirror` is non-blank (line 710) and
  `candidates` puts the configured URL first; fallbacks still follow;
- probes are parallel (`async`/`awaitAll`) with a per-probe **4 s** bound
  (`probe(url, 4_000L)`; `OkHttpDownloader.probe` clamps and sets
  call/connect/read timeouts, `:193-218`), so a dead mirror cannot delay the
  start more than ~4 s;
- **no duplicate downloads**: probes are HEAD-only (no body), then exactly one
  index fetch and one download per package;
- **no cleartext regression**: presets and `normalizeMirror` are HTTPS-only;
  `probe()` returns null for non-HTTPS.
- Caveat (should-fix, low likelihood): after a fast HEAD, the index download
  uses the shared client with `readTimeout(120s)`/`callTimeout(0)`
  (`OkHttpDownloader.kt:236-238`), so a mirror that passes HEAD then stalls on
  the GET body can delay fallback to the next mirror by up to 120 s — more than
  the advertised "~4 s before fallback". A short per-request call timeout for
  `fetchBytes` would close this.
- Dead code: `TermuxMirrors.probeCandidates` (`:115-116`) is never called.

## 2. Folder import + runtime validation (PASS with should-fix defects)

Validation logic (`JreRuntimeValidation.kt`, `JreManager.validateRuntime`
`:492-540`) checked:
- java-home discovery is bounded (`MAX_SEARCH_DEPTH = 5`), BFS shallowest-first,
  symlinked children skipped and canonical-containment enforced
  (`JreRuntimeValidation.kt:20-40`). SAF folder copies are flat regular files,
  so archive symlink tricks do not apply to this path.
- bionic-vs-glibc: `bin/java` first 8 KiB must contain `/system/bin/linker`
  (`:46-57`); an Oracle/Adoptium Linux JRE (`/lib/ld-linux-aarch64.so.1`) is
  rejected with the actionable message in `JreManager.kt:492-505`.
- **No false reject on the target runtime**: the validated bionic JRE at
  `/data/data/com.termux/files/home/jre-debs/sim2/jre` has
  `/system/bin/linker64` in `bin/java` and `OS_ARCH="aarch64"` in `release`, so
  both new checks accept it. Synthetic tests cover accept/reject and the arm64
  check (`JreRuntimeValidationTest`).
- staging/rollback/chmod: import validates, renames (or copies) into
  `stage-jre<major>`, verifies `bin/java` + `lib/server/libjvm.so`, runs
  `JrePermissions.chmodTree`, checks execute bit, then reuses the existing
  `finalizeInstallation` swap/backup/rollback and marker logic
  (`JreManager.kt:370-465`). Archive import now applies the same validation
  (`JreManager.kt` `runImport` call site). Replacing an existing runtime reuses
  the proven rollback path.
- existing-report rows: progress/job keyed by major; `uninstall`, install and
  import are serialized by `opMutex`.

### Should-fix

1. **Folder-import temp copy leaks on failure/cancellation** —
   `ui/AppViewModel.kt:254-289`. `temp` is only assigned after
   `copyDocumentTree` returns (`:269`); if the copy throws (I/O error, disk
   full, unreadable child) the partially copied
   `cacheDir/javahost-downloads/jre-folder-<ts>` tree (potentially a full
   170–300 MB JRE) is never deleted, because `finally` only deletes `temp`
   (`:282`). The same happens if the coroutine is cancelled at the
   `withContext` boundary: `withContext(Dispatchers.IO)` in the `finally` is
   skipped for a cancelled job (cleanup must use `NonCancellable`). Additionally
   `copyDocumentTree` (`:292-315`) has no `ensureActive()` checks, so a
   multi-hundred-MB copy is not cancellable. Fix: create/assign the temp dir
   before the copy, clean it with `withContext(NonCancellable + Dispatchers.IO)`,
   and check cancellation in the copy loop.
2. **Historical console can show another instance's lines (incomplete 1.0
   fix)** — `ConsoleScreen.kt:125-134` calls `actions.readStartupTail(instance.id,
   800)`, but `AppViewModel.readStartupTail` (`:536-544`) ignores `instanceId`
   and calls `container.server.recentTail(lines)`; `ServerManager.recentTail`
   (`:454-465`) returns the **current session's** tail regardless of instance,
   then the last-active instance's disk tail. Scenario: server A running, open
   the console of a stopped server B → B's "history" is A's live output
   (contradicts CHANGELOG "never mixes another instance's live lines"). The
   VM's disk fallback also reads `paths.latestLog(instanceId)` (Minecraft's own
   log) instead of the app transcript, so generic/stopped instances often show
   nothing although `logs/console.log` exists. The correct instance-aware method
   already exists and is unused: `ServerManager.readStartupTail(instanceId,
   lines)` (`:541-567`, checks `session.instanceId`, transcript first). Use it.
   Related nit: history entries are created with `timestamp = 0L`
   (`ConsoleScreen.kt:129`), so every historical line renders
   `Date(0)`-based fake clock times; hide/format the timestamp for history.
3. **Folder import copies the whole selected tree, unbounded, and can run
   concurrently** — `copyDocumentTree` (`AppViewModel.kt:292-315`) recursively
   copies every child (depth ≤ 8) before any `bin/java` validation. If the user
   picks a parent such as `Download/` that holds other files, the app copies all
   of them into the cache first (no byte/entry/free-space cap). Also
   `JreScreen.kt:200-209` leaves "Choose folder…" enabled while `importingFolder`
   and `AppViewModel.importJreFolder` has no in-flight guard, so repeated taps
   start concurrent full copies (they are only serialized later on `opMutex`).
   Fix: disable the button while importing, guard duplicates, and either locate
   `bin/java` first (copy only that subtree) or cap copied bytes/entries.
4. **`sanitizeFileName("..")` returns `".."`** — `AppViewModel.kt:776-779`
   only replaces characters, so a pathological SAF child named `..` becomes
   `File(destination, "..")` in `copyDocumentTree` and escapes one directory
   level inside the cache tree (SAF providers do not normally expose such names,
   so low likelihood). The codebase already has `SafePaths.sanitizeFileName`
   (`core/storage/FileBrowser.kt`) which rejects `.`/`..`/separators — use it
   here instead of the VM-local helper.
5. **Cancel during a folder import is not honoured before finalize** —
   `JreManager.importDirectory` (`:370-402`) performs validation/rename/copy/
   chmod/finalize synchronously inside `withContext(Dispatchers.IO)`; coroutine
   cancellation is only observed after that block completes, so a cancel during
   a large import can finish installing the runtime and then publish the job as
   `CANCELLED` (finalize already committed). Add `ensureActive()` between phases
   and skip `finalizeInstallation` when cancelled. Same pattern pre-exists for
   archive import but a folder copy is much more likely to be long.
6. **Leftover 1.0 wording** — `ui/screens/JreScreen.kt:293` still says
   `Verification: "SHA-256 from signed index"`; every other place (README,
   wizard, docs) was corrected to "HTTPS package index" / no-signature-check.
   Also `docs/REVIEW-RESPONSE.md:4` says fixes were applied "before the final
   1.0.0 artifacts were rebuilt" (they shipped in 1.1.0) and `:36` tells users
   to use `JavaHost-1.0.0.apk`, which no longer exists in `dist/`. Stale
   `dist/JavaHost-1.0.0.apk.idsig` also remains. Low impact but user-facing.

### Nice-to-have

- `JreManager.validateRuntime` accepts `architecture == null` (no `release`
  file) and relies on the ELF string check (`JreRuntimeValidation.kt:63-65`);
  fine for an escape hatch, worth a comment.
- Folder import has no free-space pre-check (copy failure surfaces the error but
  can leave the cache leak above); `ensureEnoughSpace`-style check possible.
- The bionic check is a string scan, not an ELF `PT_INTERP` parse: a glibc ELF
  whose first 8 KiB happened to contain `/system/bin/linker` would be accepted
  and a bionic runtime whose `bin/java` is a shell wrapper would be rejected
  (not the case for Termux/Pojav builds). Parsing the ELF program headers would
  be more precise.
- README "Requirements" still says "~12 MB APK" (`README.md:110`) while the
  release is ~9.0 MB and debug ~12.8 MB; harmless but easy to align.
- `TermuxMirrors` is imported into the UI (`JreScreen.kt:27`) — layering nit.
- The folder picker can only see SAF-visible folders; the UI could say the JRE
  must first be extracted to shared storage (Downloads/Documents). The current
  copy says "Termux OpenJDK package extracted on-device", which a user may
  misread as the app being able to read Termux's private files.
- `dismissCrash` now clears the persisted crash summary for **all** instances
  (`AppViewModel.kt:148-160`), not just the affected one — acceptable, but
  broad.

## 3. UI wiring (`JreScreen`) — PASS

- Folder picker is `ActivityResultContracts.OpenDocumentTree` with
  `folderLauncher.launch(null)`, wired to `actions.importJreFolder(uri)`; busy
  spinner via `importingFolder` and reset on message/cancel (`JreScreen.kt:64-76,
  200-209`).
- Mirror dropdown lists "Automatic (probe fastest)" + the six presets, writes
  the chosen URL through `actions.setTermuxMirror` (custom URL remains in
  Settings), and displays preset labels via `TermuxMirrors.presetLabel`
  (`:139-187`). "A pinned mirror is tried first and fallbacks are still
  available" matches `JreManager.orderMirrors`.
- Oracle/Adoptium/glibc explanation and the `.deb`-vs-archive correction are
  present and honest (`:129-135, 190-215`); size/checksum wording fixed to
  ≈170 MB / HTTPS index except the one leftover noted above.
- No dead controls found in the new UI; both new pickers/dropdowns call real
  actions.

## 4. Docs / version consistency — mostly PASS

- README (249 tests, dist links, mirror + folder import + glibc warning,
  `docs/REVIEW-RESPONSE.md` and `REQUIREMENTS-MATRIX.md` links), CHANGELOG,
  TESTING (249 tests, mirror/folder manual checklist), ARCHITECTURE (probe,
  folder import, GPG nuance, corrected `START_STICKY` wording), `dist/README.md`
  (v1.1.0 hashes match; dev-key caveat), `.gitignore` (`.signing/`, `dist/`,
  `*.idsig`, `*.pid`, `.scratch/`) all match code. `allowBackup=false` is in the
  built manifest.
- Remaining inconsistencies are the small leftovers listed in Should-fix #6 /
  Nice-to-have (stale `REVIEW-RESPONSE.md` 1.0.0 references and `dist` idsig;
  one "signed index" string; "12 MB APK" wording).

## 5. Previously reported items — re-checked

Fixed and verified in code/artifacts: async instance delete with stop-wait and
IO deletion (`AppViewModel.kt:424-448`), expected stops no longer shown as
crashes (`:92-108`), dismiss clears persisted summary + `clearLastCrash`
(`:148-160`, `ServerController.kt:46-48`), resume refresh
(`MainActivity.kt:28-34`), console live-entry gating per instance
(`AppRoot.kt:164-168`), `.deb` copy correction, `jreReady` = any runtime,
`.gitignore`/stray-pid cleanup, silent-media wording, auto-restart inheritance
(`AppViewModel.kt:398`), monochrome notification icon, `allowBackup=false`,
size/checksum wording. The only incomplete fix is the console **history**
source (#2 above).

## Not executed here

On-device execution of the new SAF folder picker/copy and a real
Oracle-vs-Termux folder rejection, notification rendering, FGS/Doze, and the
manual checklist in `docs/TESTING.md` (1b/1c) — no device/emulator. Mirror
presets, HEAD/GET behaviour and the real JRE's bionic/arm64 markers were
verified live from this host as described above.
