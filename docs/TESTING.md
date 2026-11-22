# Testing

This project has three layers of testing: hermetic JVM unit tests, host-level
integration validation (real JARs on an Android/bionic JRE), and a manual
device checklist for Android lifecycle behaviour.

## Automated unit tests (hermetic, no network/device)

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Final run in this environment for the 1.6.0 suite: **470 hermetic tests,
0 failures, 0 errors, 3 skipped** (448 before this release; the skipped tests are
environment-gated real-archive/real-JRE smoke tests that only run when their
fixture variables are provided).

Coverage by area:

| Area | Tests |
|---|---|
| JAR classification | synthetic Vanilla/Paper/Fabric/Forge/Bukkit/generic/installer/no-main JARs; class-major/JRE suggestion; failure messages |
| Launch plan | quoted arg tokenizer, `-Xms/-Xmx`, JNA property, env (`JAVA_HOME`, `LD_LIBRARY_PATH`, `HOME`, `TMPDIR`), Minecraft `nogui` vs generic args |
| Console | ANSI stripping, level parsing, player counts, join/leave, ready/port detection, crash signatures |
| Crash classification | OOM, port in use, unsupported class version, missing library, EULA, signal 137, unknown |
| JRE pipeline | Termux `Packages` parsing, dependency resolution, `ar`/`.deb` extraction with synthetic archives, traversal rejection, cacerts overlay, archive import (tar.xz/gz/zip), folder-layout/java-home discovery, bionic-vs-glibc ELF validation, arm64 `OS_ARCH` checks, permissions, release file |
| Storage | `ServerPropertiesCodecTest` — `server.properties` parse/format (comments/order/unknown keys), port validation, `server-ip` get/set/loopback-normalisation cases; `FileBrowserSafetyTest` — path/symlink containment |
| LAN (new in 1.2.0) | `core/net/LanAddressTest` — private-IPv4/loopback checks, cellular/VPN interface rejection, kind classification/labels, dedupe and `WIFI < ETHERNET < HOTSPOT < OTHER` ordering, subnet broadcast maths (`/8`, `/24`, `/32`, invalid), empty/all-filtered input; `core/net/MinecraftLanAnnouncementTest` — message/payload format, MOTD sanitisation, port validation; `core/net/LanProbeTest` — `REACHABLE`/`REFUSED`/`TIMEOUT`/`ERROR` mapping, latency, host/port validation, no exception on bad input; plus the `server-ip` cases in `ServerPropertiesCodecTest` |
| Bedrock (new in 1.3.0) | `core/net/BedrockRakNetTest` — ping detection/build, pong parse/build, MAGIC and length checks, `MCPE;…` server-id parse/build round-trips with missing ports and unknown trailing fields, pong rewrite (port/GUID, echoed ping time preserved) invalid-input safety; `core/net/BedrockLanRelayTest` — loopback UDP harness: client ping bytes forwarded unchanged and the reply pong rewritten with the advertised port + relay GUID, non-ping datagrams relayed both ways through a mock upstream socket, two clients isolated, busy port (no `SO_REUSEADDR`) makes `start()` return false, a non-positive beacon interval sends no unsolicited pong bytes, idle/stop cleanup; `core/net/BedrockGeyserConfigTest` — config discovery, modern `bedrock:`/`port:` and legacy `bedrock-port:` patching, `prepareForBridge` transport/WebRTC cases (`transport` absent → no-op, `nethernet` → `both`, `webrtc-port 0` → `19134`, idempotent), comments/CRLF/indentation preserved, port-only restore happy/no-op, atomic write |
| Service lifecycle (new in 1.4.0) | `core/service/ServiceLifecycleDecisionsTest` (9 tests) — truth table for `decideStart` (`ADOPT_RUNNING`, `QUEUE_AFTER_STOP`, `CLEAR_STALE_THEN_START`, `START_FRESH`) and `decideRestart` (`RESTART_EXISTING`, `CLEAR_STALE_THEN_START`, `START_FRESH`) over active/stopping/idle runtime states with and without a real session; verifies the stale-runtime and start-during-stop cases that caused the 1.3.0 bug. `ServerManager`'s fast-stop constants/escalation are review-verified; real service timing is manual-only |
| Multi-server & port guard (new in 1.5.0) | no hermetic tests for the port rules: the resolved-port rules (Minecraft `server.properties` → instance port → 25565; generic instance port), `portOwner(port, except)` and the start rejections (already active, five-session limit, port conflict naming the owner) live in the Android-dependent `ServerManager` and are **review-verified** plus covered by the manual checklist. The per-instance lifecycle decision helper (`ServiceLifecycleDecisionsTest`, 9 tests) is the unit-tested part; real concurrent-JVM behaviour and per-instance notification/auto-restart are manual-only |
| Themes (1.4.0 → 1.5.0) | `core/model/ThemePalettesTest` (20 tests) — exact ARGB hex values for every custom palette (Monochromatic Blue, Energy, Triadic, Analog Green, High Contrast Black) in light and dark variants, `null` for `SYSTEM`/`MATERIAL_YOU`, all `ThemeChoice` values covered, plus the on-colour/contrast helper cases (including the v1.5.0 contrast fix). `ThemeCatalog`/Material You visual rendering is compile-checked and manual-only |
| JAR update (new in 1.6.0) | `core/storage/JarUpdaterTest` (hermetic, `TemporaryFolder`) — replaces the target JAR content and creates `<jar>.bak` with the old bytes; other files untouched; a second update replaces the single `.bak` (no growing files); missing source; target path traversal (`../x.jar`, `/abs.jar`, `.`, `..`) rejected; inspection failure leaves the target unchanged and cleans temp files; installer JAR rejected; same-file rejected; a failure rolls back with the target intact; temp files cleaned, including stale `.jarupdate-*.tmp` files from a killed update. The backup-copy + `ATOMIC_MOVE` crash-safety (the target always exists through a crash) is review-verified; the UI running-guard and SAF flow are compile-checked and manual-only |
| System | safe/auto heap math, battery/notification state helpers |
| Misc | log rotation/tail, heap-sampler parsing, generic TCP connection counter, EULA text |

Android-framework-dependent classes (foreground service, notifications,
wakelocks, intents, `ActivityManager`) are covered by compile checks and the
manual checklist below — not by Robolectric/instrumentation in this build. The
foreground service's start/stop/restart decisions are additionally covered by
the pure-JVM `ServiceLifecycleDecisionsTest` (9 tests) and the theme palette
values by `ThemePalettesTest` (20 tests). The port-conflict rules and
`portOwner` live in the Android-dependent `ServerManager` and are
review-verified, not unit-tested.

## Host-level integration validation

Executed during development in a Termux aarch64 userland on the target phone,
using the **exact extraction rules** JavaHost uses. This validates the JRE
pipeline, command line shape and console behaviour; it is not a test of the
Android app sandbox, foreground service or Doze.

| Check | Result |
|---|---|
| Assemble JRE from Termux `openjdk-21` + runtime dependency `.deb`s | ✅ `openjdk 21.0.13` runs (`java -version`) with `LD_LIBRARY_PATH=<jre>/lib:<jre>/lib/server`; installed size ≈ 162 MiB (169.8 MB) (jmods/demo/includes/`ct.sym` stripped) |
| Vanilla Minecraft 1.20.4 server (47 MB `server.jar`) | ✅ reached `Done (36.419s)! For help, type "help"`; `-Xms256m -Xmx1024m -Djava.awt.headless=true -jar server.jar nogui` |
| Paper 1.20.4 (42 MB paperclip jar, downloads its libraries on first run) | ✅ reached `Done (15.696s)!`; JNA/oshi warnings on Android (server still boots; JavaHost ships a JNA selector for later boots) |
| Fabric 1.20.4 (loader 0.15.11, launcher generated by the official Fabric installer) | ✅ reached `Done (38.487s)!`; `fabric-server-launch.jar` Main-Class = `net.fabricmc.loader.impl.launch.server.FabricServerLauncher`; its bundled JNA 5.13.0 confirms the version-aware JNA asset selection |
| WireMock 3.9.2 standalone (non-Minecraft generic server) | ✅ started on port 18080; `GET /__admin/mappings` → HTTP 200 |
| `tools/sample-generic-server` (original test target) | ✅ built with `javac --release 17`; booted on the isolated Android JRE; `GET /health` → HTTP 200 |
| JAR detection against real jars | Vanilla 1.20.4 → MINECRAFT/VANILLA + MC 1.20.4; Paper 1.20.4 → MINECRAFT/PAPER; WireMock → GENERIC; H2 → GENERIC (see unit tests for exact assertions) |
| Modrinth API live smoke | ✅ search `worldedit` plugins for 1.20.4/paper returned hits; project versions returned a primary `.jar` (16 KB, SHA-1 present); file HEAD → HTTP 200. UA/facets/encoding validated manually; not part of the hermetic suite |
| Termux mirror smoke (live) | ✅ `Packages.gz` 732 KB → parsed 3,029 packages; real `openjdk-21` deb chain downloaded with SHA-256 verification and extracted to a runnable JRE |
| Mirror speed probe (host/dev network) | ✅ TUNA ~2.9 MB/s, MWT ~2.5 MB/s, Grimler ~1.4 MB/s, USTC ~0.7 MB/s, BFSU ~0.4 MB/s on a 3 MB range request; auto-probe ordering is unit-tested without network |
| Full Gradle unit suite | ✅ 470 tests, 0 failures, 0 errors, 3 skipped (exact count in the v1.6.0 release) |

### LAN smoke check (JVM)

The hermetic LAN tests use fakes; this host-level check exercises the real socket
paths once per release (no Android device needed — a plain JVM on the same host
is enough). It is **not** part of `testDebugUnitTest` and does not download
anything.

1. **Start a real server.** Launch the sample target the same way the app does,
   e.g. `java -jar tools/sample-generic-server/generic-server.jar --port 18081`,
   and wait for it to listen.
2. **Probe loopback and the LAN IP.** With `SocketTcpProbe` (or the app's
   **Test LAN reachability** action when running on-device):
   - `probe("127.0.0.1", 18081)` → `REACHABLE` with a measured latency;
   - `probe(<host LAN IPv4>, 18081)` → `REACHABLE` when the server binds all
     interfaces (this is the address the LAN card shows);
   - `probe(<host LAN IPv4>, <closed port>)` → `REFUSED`;
   - `probe(<blackholed address>, <port>)` → `TIMEOUT` where the network drops
     packets (`No route to host`/`REFUSED` is also a valid platform outcome and
     is mapped by the probe accordingly).
   A loopback-only bind must fail the LAN-IP probe while loopback succeeds —
   that is the regression the self-test exists to catch.
3. **Verify the announcement datagram.** Bind a `MulticastSocket`/`DatagramSocket`
   to UDP `4445` and join `224.0.2.60` (or capture the loop with a listener),
   start the announcer with a test MOTD and the server port, and assert one
   received datagram equals
   `MinecraftLanAnnouncement.payload("<motd>", <port>)` — i.e. the UTF-8 bytes of
   `[MOTD]<motd>[/MOTD][AD]<port>[/AD]` — and that sends arrive about every
   1.5 s. Use a sanitised MOTD from `sanitizeMotd` when checking the exact text.
4. **On-device follow-up:** run the LAN card + Minecraft client check in the
   manual checklist below; real multicast delivery depends on the access point.

### Bedrock bridge smoke check (JVM)

The hermetic Bedrock tests use loopback sockets with a mock upstream; this
host-level check plays a real Bedrock client's part against the real relay
classes (no Android device and no Geyser install needed):

1. **Mock upstream.** Bind a `DatagramSocket` on loopback port `19133` that
   answers a RakNet ping with a `BedrockRakNet.buildPong(...)` whose serverId
   carries a test MOTD, version and advertised port `19132`.
2. **Start the relay on an ephemeral port** (`listenPort = 0`) with
   `upstreamPort = 19133` and `beaconIntervalMs = 0` (reply-only, exactly how
   the app runs it in 1.3.0).
3. **Ping it.** Send `BedrockRakNet.buildPing(time, guid)` to `boundPort`, parse
   the pong and assert: the echoed `pingTime` equals the sent time (clients may
   ignore mismatched pongs), the descriptor advertises the relay's bound port
   and relay GUID while keeping the MOTD/version/players from the upstream, and
   the upstream saw the client's ping bytes unchanged.
4. **Relay gameplay bytes** through the bridge in both directions and assert they
   arrive unchanged at the client and the mock upstream; check `stats()` counters
   (clients, packets each way, pings answered).
5. **Reply-only check.** With `beaconIntervalMs = 0`, assert no unsolicited pong
   arrives on a second loopback listener for a few seconds.
6. **Busy port.** Start a second relay bound to the same concrete port and assert
   `start()` returns false with an error rather than throwing (the listening
   socket deliberately has no `SO_REUSEADDR`).
7. **On-device follow-up:** with Paper + Geyser + Floodgate, the LAN card must
   show both `<ip>:19132` (bridge) and `<ip>:19133` (Geyser direct);
   check a RakNet-era client on LAN/Friends and manual `<ip>:19132`, and note
   whether a NetherNet-era client needs manual `<ip>:19133` (manual checklist
   6e).

Results for a release are recorded with that release's artifacts (see the
v1.6.0 release).

Known host-level quirks observed:

- Paper/Jansi prints a harmless `libc.so.6 not found` warning for its terminal
  native; Minecraft's console does not need it.
- Paper/Spigot JNA native versions must match the server's bundled JNA
  (`5.13` needs the 5.13 ABI; newer JNA needs the 5.19 ABI). JavaHost picks a
  compatible asset when it can detect the JNA jar, otherwise it omits the
  property and the server still boots with degraded hardware info.
- First Paper boot downloads libraries, so a slow first start is expected.

## Manual device checklist (required before a release)

Not executed in this build environment (no device or emulator attached).

1. **Fresh install / wizard** — JRE 21 downloads, checksum passes, install
   completes; kill the app mid-download and confirm a retry works and no
   half-installed runtime is reported.
1b. **Mirror selection** — set each preset in **Java runtimes → Download
   mirror**, start an install, confirm the chosen host is used; use Automatic
   and confirm the fastest reachable mirror is preferred and a dead mirror falls
   back within ~4 s.
1c. **Folder import** — download/extract an Android-compatible arm64 JRE in a
   browser, use **Import from a folder**, pick the JRE home or its parent, and
   confirm it installs and `java -version` works. Then deliberately pick an
   Oracle/Adoptium Linux JDK folder and confirm the actionable glibc rejection
   (not a crash).
2. **Vanilla** — import a Vanilla JAR, accept EULA, start, see console, player
   count via `list`, stop/restart from notification.
3. **Paper** — same, plus plugin install from Modrinth and `server.properties`
   edit.
4. **Fabric** — install the Fabric server launcher JAR (or run the Fabric
   installer once and import the resulting launcher), start.
5. **Generic** — import `tools/sample-generic-server/generic-server.jar`, set
   server args `--port 18081`, start, verify the console and port; also import a
   non-Minecraft server such as WireMock.
6. **Generic stats** — start the sample generic server, connect a TCP client, and confirm the console/notification show the port and a connection count (not players).
6b. **LAN card** — on a phone connected to Wi-Fi, start a server and confirm the
   server screen lists the correct `Wi-Fi` `address:port` (the same address the
   router shows), plus a second address when the phone is also on ethernet/
   hotspot; confirm the copy button. Disconnect Wi-Fi and use mobile data only:
   no cellular `10.x` address may be listed, and the card must warn.
6c. **LAN self-test** — tap **Test LAN reachability**: the phone's own LAN address
   must be `REACHABLE`. Set `server-ip` to `127.0.0.1` in the properties editor,
   restart, and confirm the app normalises it away before start (server still
   reachable from the LAN) and that the field warns about loopback.
6d. **Minecraft LAN discovery** — with the announcement toggle on (default),
   open a Minecraft client on another device on the same Wi-Fi: the server must
   appear under “Scanning for LAN games” within a few seconds. Turn the toggle
   off while the server runs and confirm it disappears/does not reappear; use
   **Add Server** with the LAN card address to confirm manual play. Repeat on a
   guest network or with AP isolation enabled and confirm the docs' warning that
   no app-side fix exists.
6e. **Bedrock LAN bridge** — on a Paper server with **Geyser-Spigot +
   Floodgate** (2.12.1) installed, enable **Bedrock LAN bridge (Geyser)** and
   restart the server. Confirm the console shows the app's `Bedrock bridge:`
   lines (Geyser moved to UDP 19133, `transport: both`, WebRTC 19134),
   `Started Geyser on 0.0.0.0:19133` and the bridge listening/ready lines, and
   that the LAN card shows the bridge `<ip>:19132` **and** Geyser direct
   `<ip>:19133` with “Geyser reachable”, MOTD, version and players. On the same
   Wi-Fi: a **RakNet-era** Bedrock client must appear in LAN/Friends and join via
   `<ip>:19132`; a **NetherNet-era client (26.60+)** may need manual **Add
   Server** `<ip>:19133` — if Geyser's NetherNet native library fails to load on
   this ROM, confirm the console explains it as a plugin-native limitation.
   Then turn the bridge off, restart and confirm `bedrock.port` is restored to
   its original value while `transport: both` and `webrtc-port: 19134` stay, and
   that the bridge and discovery stop.
6f. **Stop → Start / Restart reliability** — start a Minecraft server, press
   **Stop** and confirm the notification disappears in under ~2 s. Press
   **Start** while the “Stopping…” notification is still visible: the server must
   start immediately after the stop, in the same service session, without
   restarting the app. Press **Restart** several times in a row: the newly
   started JVM must survive, and only one foreground service/notification may
   exist. Then simulate a stale runtime (e.g. let the ROM kill the child and
   reopen the app) and confirm Start recovers via the internal force-stop.
   Confirm **Force stop** still kills a genuinely wedged child.
6g. **Light theme** — switch the system to light mode: the app must use the cream
   background (`#F0E4C8`) and brown accent (`#5A4C39`); the active server card,
   its `RUNNING` pill and the active server's status card must use the accent,
   and every screen (home, create, detail, console, files, settings, dialogs,
   properties editor) must stay readable with dark text on cream. Switch to dark
   mode and confirm the previous ink/teal theme is unchanged. Keep screenshots
   for the release notes.
6h. **Home screen de-duplication** — start a server: it must appear only in the
   top active-server panel, not duplicated in the “Servers” list below; stop it
   and confirm it reappears in the list immediately. When the running server is
   the only instance, the list area must show the “shown in the panel above”
   hint instead of the “No servers yet” empty state.
6i. **Multi-server hosting** — start two or three servers with different ports:
   each must have its own console/status and keep running independently. Home
   must show one active-server panel per running server, with running servers
   removed from the “Servers” list and an “N running / 5” count; tapping an
   active-server card focuses that server and opens its details, and opening a
   console focuses the right instance. The
   notification must show the focused server plus “N servers running”. Stop one
   server from the notification/screen: only that one stops; restart one; then
   **Stop all** and confirm the notification clears with no JVM left. Check
   Device RAM, heat and battery with three servers running, then stop them.
6j. **Port-conflict guard & 5-server limit** — create/edit a server with a port
   already configured on another instance: the create screen and the port fields
   must warn (naming the owner) without blocking the save. Start it anyway: the
   start must be rejected with the owner named. Change `server-port` (Minecraft)
   or the generic port to a free value and start successfully. Start five
   servers, then try a sixth: it must be rejected with the limit message. With
   two Minecraft+Geyser servers running, confirm only the first gets the Bedrock
   bridge and the later one is warned.
6k. **Theme picker** — open Settings → Appearance and cycle all nine choices
   (System, Light, Dark, Material You, Monochromatic Blue, Energy, Triadic,
   Analog Green, High Contrast Black): each must apply immediately, have a usable
   light and dark variant, and keep the active-server card/`RUNNING` pill in the
   theme accent. On Android 12+ confirm Material You uses dynamic colours; on
   older versions confirm it falls back to System. Return to System.
6l. **Update JAR file** — on a stopped server with a world/plugins, tap
   **Update JAR file** on the **Server JAR** card and pick a different valid
   server `.jar` via the system picker: the JAR must be replaced in place, a
   `<jar>.bak` with the previous bytes created, and the world, `plugins/`,
   `server.properties` and logs left intact; the app shows the updated
   type/Minecraft version and a Java warning if one is recommended. Start the
   server and confirm the new JAR boots and the world is intact; then try the
   update while the server is running (button disabled / refused). Reject cases:
   pick an invalid file or a Forge/Fabric installer JAR — the old JAR must stay
   and no `.bak`/temp garbage is left. Restore the rollback by renaming the
   `.bak` over the JAR in Files and confirm the old version boots again.
7. **Screen off** — start a server, turn the screen off for 15+ minutes, confirm
   the process stays alive and the console keeps flowing.
7. **Doze** — leave the device idle/unplugged for 30+ minutes with the
   battery-optimization exemption granted; confirm survival and notification.
8. **Swipe from Recents** — swipe the app away while a server runs; the
   foreground service, notification and server must survive.
9. **Phantom Process Killer** — on Android 12+ without the ADB override, test a
   second child process/OEM behaviour and confirm the guidance is accurate.
10. **OOM post-mortem** — set a small heap, force an OOM (e.g. many plugins),
    confirm `crashes/last-crash.txt` and the restart prompt.
11. **Restart actions** — Stop/Restart from the notification while the Activity
    is destroyed; verify no two JVMs and no stale notification.
12. **SAF export** — export the console and a world file; verify the files open
    outside the app.
13. **Battery/notification states** — deny notifications, revoke battery
    exemption; the app must degrade gracefully without crashing.

## Environment caveats

- The development host is an aarch64 Android device running Termux; there is no
  Android emulator or ADB bridge available in this working environment, so the
  APK was compiled and JVM-level behaviour was tested, but instrumentation/FGS
  scenarios were not.
- Unit tests do not download anything. The JRE installer's full network path is
  covered by parser/extractor tests plus the host-level real-deb assembly above;
  its `Context`-dependent wrapper is compile-tested only.
- LAN multicast delivery, AP/client isolation and the Android 16/17 local-network
  consent can only be verified with a real phone plus an access point and a
  second client; the manual checklist items 6b–6d cover them.
- Bedrock LAN discovery/relay needs a real Geyser + Floodgate server and real
  Bedrock clients of **both** generations (RakNet-era and NetherNet-era, 26.60+);
  the loopback relay tests and the JVM smoke check cover the protocol/relay
  logic, but real broadcast/client listing, the NetherNet direct path and whether
  Geyser's NetherNet native library loads on the phone are manual-only (checklist
  item 6e).
- The real service Stop → Start / Restart transitions, stop timings, multi-server
  concurrency (up to 5 JVMs, focus switching, per-instance crash/auto-restart),
  theme contrast, Material You dynamic colour, the Home screen de-duplication and
  the SAF-based JAR-update flow can only be verified on a device;
  `ServiceLifecycleDecisionsTest` and `ThemePalettesTest` cover the pure logic,
  `JarUpdaterTest` covers the file replace/rollback logic, and the rest is
  checklist items 6f–6l.
- The Bedrock bridge's single-owner rule with several concurrent sessions needs a
  real device/access point and a Geyser setup (manual checklist item 6j).
