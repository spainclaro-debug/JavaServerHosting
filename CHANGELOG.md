# Changelog

## 1.2.0 — 2026-11-15

Fixes the “server is running but LAN clients cannot connect / Minecraft never
lists the server” report.

### Root causes
- **The LAN address was a naive pick.** `ServerManager.emitLanAddress()` printed
  the first site-local IPv4 of any non-loopback interface. On real phones that is
  frequently the **cellular** interface (`ccmni*`, `rmnet*`) or a **VPN**
  (`tun*`), so the printed address was unreachable while `localhost` still worked.
- **Nothing verified the bind.** No test confirmed the server was listening on the
  LAN address; a leftover `server-ip=127.0.0.1` (or a server bound to loopback)
  made localhost work and every LAN client fail silently.
- **Dedicated servers never announce themselves.** Only worlds opened with
  Minecraft's “Open to LAN” broadcast via UDP multicast
  `224.0.2.60:4445` (`[MOTD]<motd>[/MOTD][AD]<port>[/AD]`). JavaHost did not
  announce, so the game's LAN list stayed empty.
- **No LAN UI or diagnostics.** No screen showed the actual LAN address, a
  reachability self-test, or the real blockers (VPN, mobile-data-only, AP
  isolation, loopback bind).

### Fixes
- **Correct LAN address resolution:** Wi-Fi, ethernet and hotspot interfaces are
  accepted; cellular, VPN and virtual interfaces and non-private IPv4 addresses
  are rejected. Every usable address is listed (multi-address card), deduplicated
  and labelled (`Wi-Fi`, `Ethernet`, `Hotspot`, `Network`).
- **TCP reachability self-test** from the phone to its own LAN IP:port
  (“Test LAN reachability”), with actionable `REFUSED` / `TIMEOUT` messages that
  point at the real cause instead of a silent failure.
- **Minecraft LAN discovery announcement:** the running Minecraft server is
  broadcast to the game's LAN list via UDP multicast
  `224.0.2.60:4445` every ~1.5 s on the Wi-Fi/hotspot interface, with a
  `MulticastLock` acquired when available (sending does not require it).
  MOTDs are sanitised; announcement errors are reported at most once per 30 s.
- **`server-ip` loopback normalization:** a loopback bind (`127.0.0.1`, `::1`,
  `localhost`) is cleared before start, and the server properties editor gains a
  typed **Bind address (server-ip)** field with validation and a warning for
  loopback values.
- **LAN card on the server screen** with every usable address, a copy button per
  address, the “Test LAN reachability” probe rows, a connectivity warning and
  the **Announce on the LAN (auto-discovery)** switch; **Settings** gains a
  **Minecraft LAN discovery announcement** toggle (on by default, live).
- New permissions: `CHANGE_WIFI_MULTICAST_STATE` (multicast announcements) and
  `ACCESS_LOCAL_NETWORK` (Android 17 local-network permission; forward
  compatibility, never requested at runtime on older releases).
- **Live controls and better diagnosis:** the “Announce on the LAN” switch applies to a
  running server immediately, the announcer re-binds when Wi-Fi/hotspot changes, the
  self-test reports “bound to this phone only” when `127.0.0.1` answers but the LAN address
  does not, and Doze/battery-optimization network suspension is surfaced as a warning.
- 327 hermetic unit tests (0 failures, 3 environment-gated skips; new LAN address
  selection, Minecraft announcement and TCP probe suites plus `server-ip` codec cases).
- **Release note:** the 1.2.0 APK is signed with the same development key as
  1.1.6, so it installs as an **in-place upgrade**; instances, settings and
  worlds are preserved.

### Known limitations
- **Dedicated-server discovery is best-effort.** The client must be on the same
  Wi-Fi/hotspot, and some clients/versions do not scan the LAN list at all; use
  the address from the server's LAN card with **Add Server** when that happens.
- **Android 16 opt-in / Android 17 local-network permission.** Android 17
  introduces a local-network runtime permission and Android 16 an opt-in
  equivalent; JavaHost declares `ACCESS_LOCAL_NETWORK` but the platform consent
  flow is still rolling out, so behaviour can differ by OS version.
- **AP/client isolation and guest networks block LAN regardless of the app.**
  Router isolation, hotspot client isolation and guest Wi-Fi separate clients
  from the phone; no app-side fix exists.
- **Bedrock clients still need Geyser/Floodgate** (UDP 19132) — the Java LAN
  announcement does not make a Java server visible to Bedrock.

## 1.1.6 — 2026-11-15

- **Silent-media keep-alive is now a normal in-app toggle**: the build feature
  flag is enabled, so Settings can switch it on/off without rebuilding or
  command-line flags. The service reconciles the setting live (~2 s) while a
  server is running.
- **Generic Java servers no longer show Minecraft player UI.** They show the
  server port and the number of established TCP connections on that port
  (read from `/proc/net/tcp`; `n/a` when the kernel hides it). Player counts and
  Minecraft-only rows remain confined to Minecraft instances.
- **Heap ceiling now scales with device RAM**: slider max is
  `device RAM − 1 GB` (6 GB phone → 5 GB, 8 GB phone → 7 GB, 12 GB → 11 GB),
  capped at 12 GB. The JVM clamp was raised from 8 GB to 16 GB so the UI limit
  is not silently reduced.
- 253 tests (new port-counter parser tests; memory tests updated for the RAM−1 GB ceiling).

## 1.1.5 — 2026-11-15

- **Duplicate-entry JARs now import** (Suwayomi/Spring Boot fat JARs): if the
  platform ZIP reader rejects a duplicate entry such as `LICENSE`,
  `JarInspector` retries with a tolerant Commons Compress central-directory
  reader and classifies the JAR normally. Regression tests include a real
  Suwayomi fixture (env-gated) and a synthetic duplicate-entry JAR.
- **Manual connection diagnostics:** the console prints the phone’s LAN IPv4
  address and Minecraft port, explains that Android Wi-Fi often blocks LAN
  multicast so the server must be added manually, and prints the Bedrock/Geyser
  UDP address when Geyser is installed.
- **Force Stop** is available from the server screen and from the ongoing
  notification when a shutdown wedges, so the user no longer has to force-stop
  the whole app. It kills the JVM by PID and clears the session.
- 253 tests (including a real Suwayomi fixture when `JAVAHOST_TESTTARGETS` is set).

## 1.1.4 — 2026-11-15

- Added actionable plugin-compatibility hints in the console and Mods screen:
  - **ViaVersion** does not add support for older clients; install
    **ViaBackwards** (and ViaRewind for 1.7/1.8) as well.
  - **Geyser without Floodgate**: Floodgate for Paper is distributed by
    GeyserMC, not Modrinth; import the JAR via Files.
  - Geyser's **NetherNet/WebRTC** native library cannot load on Android/bionic;
    this is a plugin-native limitation, Java clients should use port 25565 and
    Bedrock clients may need a RakNet-compatible version.
- Startup diagnostic now shows the detected Minecraft version alongside the
  server type and plugin/mod counts.
- No behavioural change to installs; this is diagnostics/guidance plus the
  1.1.3 false-crash fix.

## 1.1.3 — 2026-11-15

- **Fixed a false “Server exited unexpectedly: A required class or native
  library is missing” report after a normal stop.** Exit code 0 is now treated
  as a normal/expected exit before scanning shutdown warnings in the console
  tail, and `stopRequested` is set synchronously when Stop is pressed so a
  process that exits before the async stop coroutine runs cannot be
  misclassified as a crash.
- Added regression tests for exit-0-with-stale-class-load-warning and for an
  ordinary exit 0 with no stop flag.

## 1.1.2 — 2026-11-15

- **Stop/restart no longer wedges at “Shutting down…”**: the service now waits
  for the controller to publish a terminal state, the ticker no longer re-posts
  “Shutting down” every 2 seconds, and `ServerManager` escalates to PID-level
  SIGKILL plus a forced session reset if a JVM refuses to die. A
  `forceStop()` recovery path clears a wedged session without restarting the app.
- **Plugin/mod load diagnostics**: every Minecraft start logs the server flavor,
  plugin count in `plugins/`, mod count in `mods/`, and a clear warning when a
  Vanilla JAR is being asked to load Bukkit plugins (Geyser/Floodgate/ViaVersion).
  A JAR-vs-instance-type mismatch also produces a warning.
- **Installer compatibility gates**: Mods/Plugins install is disabled for
  incompatible server types (Vanilla cannot load either; Fabric/Forge use
  `mods/`, Bukkit-family uses `plugins/`), and version loaders are checked
  before install. The UI explains why and points to Paper.
- New `docs/TROUBLESHOOTING.md` covering stuck stops, plugin loading, and
  “visible in server list but cannot join”.

## 1.1.1 — 2026-11-14

Fixes from the focused v1.1.0 review:

- Folder import: partial cache copies are now cleaned up on failure/cancel with
  `NonCancellable` cleanup; the SAF tree is bounded (1.5 GB / 20k files / depth
  5); only the detected Java home is copied (not an accidentally selected
  Downloads folder); duplicate imports are guarded in ViewModel and UI; filename
  sanitisation now rejects `..`/separators via `SafePaths`.
- Folder/archive import checks cancellation between copy, chmod and finalize
  phases, so a cancelled import cannot finish and then report "cancelled".
- Folder import reads the first 8 KiB of `bin/java` before copying anything, so
  a selected Oracle/Adoptium glibc JDK is rejected immediately instead of after
  copying hundreds of megabytes.
- Console history is now truly instance-aware: `ServerController.readStartupTail`
  uses the correct per-instance transcript/server log, no longer the active
  session's tail; historical lines no longer render fake epoch timestamps.
- Package-index GETs now have a 45 s call timeout, so a mirror that passes the
  4 s HEAD probe but stalls on the body cannot block fallback for 120 s.
- Removed a stale "signed index" label, dead mirror helper and old 1.0.0
  artifacts/idsig files; documentation references updated.

## 1.1.0 — 2026-11-14

### Faster JRE downloads
- Mirror presets for the Termux package repository: official, TUNA (China), MWT,
  Grimler (Europe), USTC and BFSU.
- "Automatic (probe fastest)" mode: when no mirror is pinned, JavaHost probes
  candidates in parallel and tries the lowest-latency one first (4 s bound),
  while retaining the full fallback chain.
- Mirror picker in **Java runtimes → Download mirror**; custom HTTPS mirror URL
  still available in Settings.
- Measured mirror throughput on the development network (3 MB range requests):
  TUNA ~2.9 MB/s, MWT ~2.5 MB/s, Grimler ~1.4 MB/s, USTC ~0.7 MB/s,
  BFSU ~0.4 MB/s.

### Manual runtime installation
- **Import from a folder** via the SAF folder picker (`ACTION_OPEN_DOCUMENT_TREE`):
  select an extracted JRE home or a parent directory; JavaHost copies it into
  app-private storage, finds `bin/java`, validates it, and installs it.
- Validation rejects non-Android/JDK builds: checks the ELF interpreter
  (`/system/bin/linker64` for bionic) and `OS_ARCH=aarch64`. Oracle/Adoptium
  **Linux** JDKs (glibc) get a clear actionable error instead of failing later.
- Existing archive import (`.tar.xz`, `.tar.gz`, `.zip`) now applies the same
  bionic/arm64 validation.

### Fixes from the independent 1.0.0 review
- Instance deletion is asynchronous, stops the JVM first, waits for it, and
  deletes large worlds off the main thread.
- Expected stops no longer show as crashes; crash banners clear on new runs.
- "Dismiss report" now clears the persisted instance crash summary.
- Battery/notification state refreshes when the app resumes.
- Console shows the historical on-disk tail for stopped instances and never
  mixes another instance's live lines.
- New monochrome notification icon.
- `jreReady` treats any installed runtime as ready.
- Silent-media and auto-restart wording matches behaviour; JRE size/checksum
  wording corrected.
- `allowBackup=false`; signing material and `dist/` excluded from git.

## 1.0.0 — 2026-11-14

Initial MVP: Android/bionic OpenJDK downloader, JAR launcher and live console,
Minecraft detection/EULA/properties/Modrinth, generic Java power path,
foreground service + wakelocks/power guidance, crash post-mortems, memory
advisor, file browser, first-run wizard, debug/release APKs and 242-test suite.
