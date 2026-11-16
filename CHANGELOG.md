# Changelog

## 1.4.1 — 2026-11-16

- **The running server is no longer duplicated on Home.** While a server is
  active it appears only in the top “active server” panel; the “Servers” list
  below now hides it and shows it again as soon as it stops. When the active
  server is the only instance, the list area shows a short hint (“The running
  server is shown in the panel above…”) instead of the “No servers yet” empty
  state.
- UI-only cleanup: no changes to hosting, stop/start, LAN or Bedrock behaviour.
- 424 hermetic unit tests (no new tests; UI-only change).
- **Release note:** the 1.4.1 APK is signed with the same development key as
  1.4.0, so it installs as an **in-place upgrade**; instances, worlds, plugins and
  settings are preserved.

## 1.4.0 — 2026-11-16

Fixes the “after Stop, Start does nothing until the app is closed” bug and adds
a light theme.

### Root cause — a stopped server could not be started again
- `ForegroundServerService.onDestroy()` unconditionally called
  `ServerManager.shutdown()`. The foreground service is destroyed after every
  normal Stop, and `shutdown()` **permanently disposed the process-wide
  singleton**: it set `disposed = true`, cancelled the coroutine scope and
  latched `shutdownOnce`. The next Start returned
  `StartResult.Rejected("JavaHost is shutting down.")` — the “shutting down”
  notification — until the whole app process was killed and a fresh
  `AppContainer`/`ServerManager` was created. That is why the user had to close
  the app to start a server again.
- Secondary bugs: a Start pressed while the runtime was `STOPPING` adopted the
  dying server instead of queuing; `restartExclusive()` ended its service
  operation immediately, so the runtime observer could see the transient
  `STOPPED` published during a restart and tear the service down, killing the
  JVM the restart had just launched; Stop was slow (20 s save + 3 s kill) and its
  notification lingered; there was no queue for Start-during-Stop.

### Fix — revivable manager and explicit service lifecycle
- **`ServerManager` is revivable after service teardown.** `shutdown()` keeps its
  contract (kill the child, release coroutines), but a later `start()`/
  `restart()` calls `ensureOperational()`, which recreates the coroutine scope
  and console publisher, clears the disposed flag and resets the shutdown latch.
  New `hasActiveSession()` tells a real child session apart from a stale
  published runtime snapshot.
- **Idle service destruction no longer disposes the controller.**
  `ForegroundServerService.onDestroy()` calls `shutdown()` only when a session or
  active runtime actually exists; after a normal Stop it just releases the locks
  and notification and leaves the process-wide manager usable.
- **Start during Stop queues.** A pure `ServiceLifecycleDecisions.decideStart()`
  helper classifies each Start command (adopt running, queue after STOPPING,
  clear stale state then start, or start fresh). A queued Start runs immediately
  after the stop in the same service operation, so the notification switches
  from “Stopping…” to “Starting…” with no teardown in between.
- **Restart stays one service operation.** A restart-in-flight guard makes the
  runtime observer ignore the transient terminal state during a restart; the
  service waits (bounded) for the new session to become active and stays alive,
  so the Restart button cannot kill the JVM it just started.
- **Fast, bounded stop.** Minecraft gets the `stop` command and up to **8 s** to
  save and exit, then `SIGTERM` with a **2 s** grace, then `SIGKILL` escalation
  (worst case ~11 s, normally under 2 s). The notification is removed as soon as
  a terminal state is published, and Start works again immediately.
- **Stale-state recovery.** If the published runtime claims active but
  `hasActiveSession()` is false, the service calls `forceStop()` to clear the
  zombie session and starts fresh; the manual **Force stop** action is retained
  for a genuinely stuck child.

### New light theme
- **Light palette:** cream background `#F0E4C8`, brown accent `#5A4C39`, with a
  readable on-colour set (cream `onPrimary`, dark-brown `onBackground`/
  `onSurface`, a slightly lighter `surface` so cards separate).
- **Dark theme unchanged** (ink/teal); the accent resolves to the dark palette's
  teal there, so no light-mode brown is hardcoded.
- **Active server accents:** active server cards and the `RUNNING` state pill use
  the accent colour, and `SectionCard` gained optional container/border colours
  for that treatment; inactive cards look the same as before.

- 424 hermetic unit tests (new `ServiceLifecycleDecisionsTest` truth table for
  start/restart decisions; exact count in the release build).
- **Release note:** the 1.4.0 APK is signed with the same development key as
  1.3.0, so it installs as an **in-place upgrade**; instances, worlds, plugins and
  settings are preserved.

### Notes
- A JVM that ignores both `stop` and `SIGTERM` is SIGKILLed after ~10 s and can
  lose the last few seconds of unsaved world data; the console says when that
  happens. This is the trade-off that makes the notification clear quickly and
  Start reliable.
- Force Stop is only for a genuinely wedged child; normal Stop now always leaves
  the app able to start another server.

## 1.3.0 — 2026-11-15

Adds **Bedrock Edition LAN hosting**: with Geyser + Floodgate installed,
RakNet-era Bedrock clients on the same Wi-Fi/hotspot can see the server in the
LAN/Friends list again, not only via manual “Add Server”. Newer NetherNet-only
clients use Geyser's direct address — see the known limitations below.

### Root cause
Bedrock does not use Java's multicast announcement. A Bedrock client broadcasts a
**RakNet Unconnected Ping** on UDP `19132` and lists a server only when something
answers with an **Unconnected Pong** from the address the client should connect
to. Geyser does not reliably provide that on Android/plugin setups (the Geyser
wiki itself suggests adding the server manually), and Geyser owns UDP `19132`, so
the app could not answer the pings itself.

### Fix — Phantom-style Bedrock LAN bridge
- **The app owns UDP 19132.** While a Minecraft server with Geyser runs and
  **Bedrock LAN bridge (Geyser)** is on (default), JavaHost binds UDP `19132`
  and answers RakNet pings itself. Pongs are rewritten to advertise port `19132`
  and the bridge's GUID; every other datagram is relayed to Geyser through one
  upstream socket per client (max 32 clients, idle sessions closed after 120 s).
- **Geyser moves to UDP 19133** so the bridge can own 19132. Geyser 2.12.1
  removed the RakNet-only transport and **defaults to NetherNet**, which does not
  start the RakNet listener the bridge needs, so the bridge patches
  `plugins/Geyser-*/config.yml` `bedrock.port` to 19133 **and** (when those keys
  exist) `bedrock.transport` to `both` and `bedrock.webrtc-port` to 19134,
  preserving every other byte. The JVM also gets
  `GEYSER_BEDROCK_PORT=19133`, `GEYSER_BEDROCK_TRANSPORT=both`,
  `GEYSER_BEDROCK_WEBRTC_PORT=19134` and `GEYSER_BEDROCK_ADDRESS=0.0.0.0`
  (Geyser ≥2.12 `GEYSER_<PATH>` env overrides, for builds without env support it
  patches the config instead). When the bridge is turned off only the **port** is
  restored; `transport: both` and `webrtc-port: 19134` stay, so both client
  generations keep working.
- **Reply-only LAN answering:** the bridge answers the clients' own Unconnected
  Pings from UDP 19132 and relays their datagrams to Geyser (Phantom-style). The
  relay also has an unsolicited pong beacon, but it is disabled in 1.3.0 because
  broadcast pongs are untested against real clients and can confuse the LAN list.
- **Live status:** a RakNet ping every few seconds tells the app whether Geyser
  is actually reachable and publishes the MOTD, Bedrock version, protocol and
  players (`3/10`). The LAN card's new **Bedrock Edition** block shows the bridge
  address `<ip>:19132` **and** Geyser's direct address `<ip>:19133` for every
  usable LAN address, the Geyser status and any warning.
- **Setting:** **Bedrock LAN bridge (Geyser)** on the server's LAN card and in
  **Settings** (on by default). It takes effect at the next server start/restart,
  because Geyser's listen port is fixed when the JVM launches.
- No new Android permissions: the bridge uses the existing `INTERNET` socket
  access (`ACCESS_LOCAL_NETWORK` was already declared in 1.2.0).
- 419 hermetic unit tests (0 failures, 3 environment-gated skips; new RakNet codec,
  loopback UDP relay and Geyser
  config suites; subnet-broadcast cases added to the LAN selector tests).
- **Release note:** the 1.3.0 APK is signed with the same development key as
  1.2.0, so it installs as an **in-place upgrade**; instances, worlds, plugins and
  settings are preserved.

### Known limitations
- **Geyser + Floodgate are required.** The bridge does not translate Bedrock
  itself; it makes a working Geyser setup discoverable. A Vanilla/Fabric/Forge
  server cannot accept Bedrock clients.
- **Applies at the next server start/restart.** Turning the switch on while a
  server runs updates the card and setting, but the Geyser port/transport change
  and the UDP 19132 relay start with the next launch.
- **IPv6 LAN discovery is not covered.** The bridge advertises the IPv4 address
  on `19132`; IPv6-only networks are out of scope.
- **Newer Bedrock clients use NetherNet, not RakNet.** Bedrock 26.60 (October
  2026) clients no longer join over RakNet and use Geyser's NetherNet/WebRTC
  transport instead. The bridge restores LAN/Friends discovery and joins for
  RakNet-era clients and for a Geyser running `transport: both`, but a
  **NetherNet-only client may not join through 19132**. Use Geyser's direct
  address **Add Server `<phone-ip>:19133`**, also shown on the LAN card. On
  Android/bionic Geyser's NetherNet native library may fail to load entirely — a
  plugin-native limitation that the console surfaces.
- **Some client-side LAN-list bugs are not fixable here.** Some 2026 Bedrock
  clients fail to list a server even when it returns a valid pong (Mojang bug
  **MCPE-239883**), and the NetherNet transition (see the Geyser blog/release
  notes) is still rolling out; the LAN card's manual addresses are the reliable
  fallback, not a guarantee of universal LAN-list visibility.
- **AP/client isolation and router broadcast filtering can still block
  discovery.** The relay may still work when only broadcasts are filtered — use
  **Add Server** `<phone-ip>:19132` (or `<phone-ip>:19133` for NetherNet-era
  clients) as the fallback.
- **Console Bedrock clients vary by platform and version.** PlayStation, Xbox and
  Switch builds may hide the LAN/Friends list or the Add Server UI, and newer
  console versions may be NetherNet-only; use a build that allows manual servers
  if discovery does not appear.
- **Windows client firewall.** Allow Minecraft through the Windows firewall for
  UDP `4445` (Java LAN discovery), UDP `19132` (Bedrock bridge) and, when using
  the NetherNet fallback, Geyser's direct address `19133`; a blocked inbound
  rule keeps the LAN list empty even while the phone is announcing.
- **Java LAN is unchanged.** Both editions can be discovered at the same time:
  Java via multicast `224.0.2.60:4445`, Bedrock via the `19132` bridge.

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
