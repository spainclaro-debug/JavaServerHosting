# Response to independent review (`docs/REVIEW.md`)

Reviewer verdict: **no ship-blockers**. The 11 should-fix items were triaged and
the following fixes were applied. They shipped in the **1.1.x** artifacts (the
1.0.0 artifacts were the reviewed baseline).

| # | Finding | Resolution |
|---|---|---|
| 1 | Instance delete on UI thread + race with running JVM | `AppViewModel.deleteInstance` now stops the server, waits up to 20 s for the runtime to leave the active state, then deletes on `Dispatchers.IO`; refuses with a message if the stop times out. |
| 2 | Normal Stop shown as crash / stale banner | `AppViewModel` only surfaces `lastExit` when `expected == false`, clears the banner when a run starts, and shows expected stops as normal. |
| 3 | “Dismiss report” no-op | Added `clearLastCrash()` to `ServerController`; the ViewModel clears persisted `lastCrashSummary`/exit fields for all instances and calls the controller. |
| 4 | Power/notification state stale after system dialogs | `MainActivity.onResume` calls `AppViewModel.onResume()` → `refreshPowerChecks()` + device info. |
| 5 | Console buffer global; no historical tail | `AppRoot` only passes live entries when the runtime belongs to the opened instance; `ConsoleScreen` loads `readStartupTail(800)` from disk for stopped instances (tagged as history). |
| 6 | JRE import UI claimed `.deb` support | Copy corrected: archives only; `.deb` is installed by the built-in installer (matching `JreArchiveImporter`). |
| 7 | `jreReady` false negative | `jreReady` now means “any runtime installed”; per-major checks remain on the start gate and pickers. |
| 8 | Signing key / dist not git-ignored; stray pid | `.gitignore` covers `.signing/`, `dist/`, `*.idsig`, `*.pid`, `.scratch/`; stray `sample.pid` removed. The keystore remains local only. |
| 9 | Silent-media copy mentioned audio focus | Copy now says it deliberately does **not** take audio focus. |
| 10 | “New instances start with auto-restart enabled” false | `createInstance` now copies `settings.autoRestartOnCrash` into the new instance; copy reworded to reflect inheritance. |
| 11 | Notification icon = full-bleed launcher vector | Added monochrome `ic_stat_server.xml` (transparent background) and switched both notification builders to it. |

Additional honesty/cleanup taken from the nice-to-have list:
- UI and docs now say installed JRE ≈170 MB (not 250–300 MB), downloads are
  cancelled and **retried** (not resumed), and checksum wording was tightened:
  the app verifies each package's SHA-256 from the HTTPS index but does not
  verify the index's GPG signature.
- `docs/ARCHITECTURE.md` no longer implies cross-process `START_STICKY` adoption.
- `android:allowBackup="false"` (server worlds/JREs are not swept into cloud
  backups).
- Release keystore and unsigned intermediates are excluded from the repo; the
  signed APKs in `dist/` are development-key builds and `dist/README.md` says so.

Remaining known limitations (unchanged and documented):
- On-device foreground-service/Doze/phantom-killer/notification behaviour cannot
  be executed in this environment; `docs/TESTING.md` contains the manual matrix.
- The debug APK is a development artifact (debuggable, exported tooling
  activity); use `JavaHost-1.1.4.apk` (or newer) for normal sideloading.
- The `FileProvider` is declared for future share/export use but not currently
  referenced by a screen; it is non-exported and harmless.
