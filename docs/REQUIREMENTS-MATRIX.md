# Requirements → implementation matrix

Status values: **Implemented** (code + build), **Verified** (plus tests/host
evidence), **Manual-only** (requires a physical device; see `TESTING.md`).

## Core

| Requirement | Status | Where |
|---|---|---|
| Download a lightweight OpenJDK 17/21 (25 also) and extract to app-private storage | Verified (real packages assembled and executed; app orchestration compile-tested); fastest-mirror probe + presets | `core/runtime/JreManager.kt`, `TermuxPackages.kt`, `DebExtractor.kt`, `SafeTarExtractor.kt`, `TermuxMirrors.kt` |
| Manual runtime installation | Folder picker + archive import; Android/bionic validation (rejects Oracle/Adoptium glibc builds) | `JreManager.importDirectory`, `JreRuntimeValidation.kt`, `JreScreen.kt` |
| Launch JARs with `ProcessBuilder`, pipe stdout/stderr to live console | Verified (vanilla/Paper/WireMock booted on the extracted JRE) | `core/server/ServerManager.kt`, `core/launch/LaunchPlan.kt`, `ui/screens/ConsoleScreen.kt` |
| Key classes `ServerManager`, `JarInspector`, `ForegroundServerService`, `JreManager` | Implemented | same names under `core/server`, `core/jar`, `core/service`, `core/runtime` |
| arm64-v8a, Android 8+, no root | Implemented; APK minSdk 26, only arm64 JRE/JNA assets, no root | `app/build.gradle.kts`, manifest |

## JAR handling & detection

| Requirement | Status | Where |
|---|---|---|
| SAF `ACTION_OPEN_DOCUMENT` picker; copy into working dir; persist URI permission | Implemented | `ui/screens/CreateInstanceScreen.kt`, `ui/AppViewModel.kt` (`persistReadPermission`) |
| Detect by `META-INF/MANIFEST.MF` + class signatures (Bukkit/Paper/Fabric/Forge/net.minecraft) | Verified against real vanilla/Paper/WireMock/H2 jars | `core/jar/JarInspector.kt` |
| Classify Minecraft vs Generic; manual override | Implemented | `JarInspection`, Create screen override chips |
| Installer JARs flagged, not launched blindly | Implemented | `JarInspector` `needsInstaller` |

## Conditional features

| Requirement | Status | Where |
|---|---|---|
| Minecraft plugin/mod manager via Modrinth | Implemented (live API search/versions/download verified) | `core/modrinth/*`, `ui/screens/ModsScreen.kt` |
| `server.properties` editor | Implemented + unit tested | `core/storage/ServerPropertiesCodec.kt`, `PropertiesScreen.kt` |
| EULA flow | Implemented (wizard opt-in + per-instance gate + `eula.txt`) | Wizard, Detail/Properties screens, `ServerManager.start` |
| Player count in notification | Implemented (parser + notification builder) | `ConsoleParser`, `ServerNotifications` |
| Generic: no Minecraft UI, file manager + free-text JVM args, "Advanced / Experimental" | Implemented | Create/Detail/Home labels; Files/Console/args; MC routes guarded |
| Shaders: omit/relabel, no dead button | Omitted; no shader control anywhere (grep clean) | — |

## LAN access & discovery (1.2.0)

| Requirement | Status | Where |
|---|---|---|
| LAN address resolution: Wi-Fi/ethernet/hotspot only, private IPv4 only, cellular/VPN/virtual rejected, multi-address deduplicated + sorted list | Verified (pure-JVM selector unit-tested; Android enumeration compile-tested) | `core/net/LanAddress.kt`, `core/net/AndroidLanInfo.kt`, `ServerManager`, LAN card |
| LAN reachability self-test: TCP connect from the phone to its own LAN IP:port with `REACHABLE`/`REFUSED`/`TIMEOUT`/`ERROR` and actionable messages | Verified (probe unit-tested with hermetic fakes; UI wiring compile-tested) | `core/net/LanProbe.kt`, `ServerController.probeLan`, `ServerDetailScreen.kt` |
| Minecraft LAN discovery announcement: `[MOTD]…[/MOTD][AD]<port>[/AD]` to UDP multicast `224.0.2.60:4445` every ~1.5 s, `MulticastLock`, sanitised MOTD, settings toggle | Payload/sanitiser unit-tested; multicast path **Manual-only** (needs real Wi-Fi) | `core/net/MinecraftLanAnnouncement.kt`, `MinecraftLanAnnouncer.kt`, `AppSettings.minecraftLanDiscovery` |
| `server-ip` handling: loopback (`127.x`, `::1`, `localhost`) normalised to all-interfaces before start; typed Bind address field with validation and loopback warning | Implemented + unit tested | `ServerPropertiesCodec.kt`, `PropertiesScreen.kt`, `ServerManager.start` |
| LAN UI: server-screen LAN card (labels, `address:port`, copy button, probe rows, connectivity warning/summary) and Minecraft announcement toggle | Implemented (Compose; compile-tested) | `ServerDetailScreen.kt`, `SettingsScreen.kt`, `AppViewModel.kt` |
| New permissions `CHANGE_WIFI_MULTICAST_STATE` and `ACCESS_LOCAL_NETWORK` | Implemented in the manifest; `ACCESS_LOCAL_NETWORK` is forward compatibility only and is not requested at runtime | `AndroidManifest.xml` |

Note: the app still has `targetSdk = 28`, so neither permission is enforced on
current Android releases. `CHANGE_WIFI_MULTICAST_STATE` is a normal permission
used for the LAN announcement; `ACCESS_LOCAL_NETWORK` is declared ahead of
Android 17's local-network consent (Android 16 opt-in equivalent) so a future
higher-target build is ready for it.

## Bedrock Edition LAN bridge (1.3.0)

Bedrock clients discover LAN servers over RakNet, so a Geyser setup is made
discoverable by an app-side bridge: the app owns UDP `19132` while Geyser is moved
to `19133` and set to `transport: both` (RakNet + NetherNet, with NetherNet WebRTC
on `19134`). Bedrock translation itself remains Geyser + Floodgate's job.

| Requirement | Status | Where |
|---|---|---|
| RakNet LAN relay: bind UDP `19132` (`SO_BROADCAST` only, **no** `SO_REUSEADDR` so it fails loudly if Geyser still owns the port), relay client datagrams including pings to Geyser at `127.0.0.1:19133` through one socket per client, send server datagrams back via the listening socket with rewritten pongs; reply-only (no unsolicited beacon); max 32 clients, idle sessions 120 s | Verified (pure-JVM loopback UDP tests: ping answered, both-way relay, clients isolated, no-reuse/busy port); real LAN **Manual-only** | `core/net/BedrockRakNet.kt`, `core/net/BedrockLanRelay.kt`, `ServerManager` |
| Pong rewrite + `MCPE;…;` server-id descriptor: forces advertised IPv4/IPv6 port `19132` and the bridge GUID, forwards client ping bytes unchanged, keeps unknown trailing fields, supports missing ports | Verified (hermetic codec tests) | `core/net/BedrockRakNet.kt` |
| Beacon: unsolicited pong re-broadcast to `255.255.255.255` + each interface's subnet broadcast | Implemented in the relay but **disabled in 1.3.0** (reply-only, Phantom-style) — broadcasts are untested against real clients and can confuse the LAN list | `BedrockLanRelay`, `LanAddressSelector.subnetBroadcast` |
| Geyser port + transport: env `GEYSER_BEDROCK_PORT=19133`, `GEYSER_BEDROCK_TRANSPORT=both`, `GEYSER_BEDROCK_WEBRTC_PORT=19134`, `GEYSER_BEDROCK_ADDRESS=0.0.0.0`, plus atomic `prepareForBridge` config patch (`bedrock.port`, and `bedrock.transport: both` / `bedrock.webrtc-port: 19134` only when those keys exist; legacy `bedrock-port` understood) with a sidecar that restores **only the port** on disable | Verified (config patch/restore tests; env applied when the child JVM launches) | `core/net/BedrockGeyserConfig.kt`, `ServerManager`, sidecar file |
| Geyser polling (MOTD/version/protocol/players/reachability) and LAN-card **Bedrock Edition** block showing the bridge `<ip>:19132` + Geyser direct `<ip>:19133` endpoints, with the **Bedrock LAN bridge (Geyser)** setting (on by default, applies at next start/restart) | Implemented (Compose; compile-tested); live status needs a real Geyser server | `ServerManager`, `ServerDetailScreen.kt`, `SettingsScreen.kt`, `AppSettings.bedrockLanDiscovery` |
| NetherNet-era clients (Bedrock 26.60+, Oct 2026, no longer join over RakNet): Geyser direct `<ip>:19133` shown for manual Add Server; console surfaces Geyser's NetherNet native-library failure on Android/bionic as a plugin-native limitation; MCPE-239883 client listing bug documented | Implemented in UI/status (compile-tested); real NetherNet client **Manual-only** | `ServerManager` (`directEndpoints`), `ServerDetailScreen.kt`, docs |
| No new permissions: bridge uses the existing `INTERNET` UDP sockets and the local-network access already declared | Implemented (manifest unchanged in 1.3.0) | `AndroidManifest.xml` |

Note: the bridge does not make a Vanilla/Fabric/Forge server accept Bedrock
clients — Geyser-Spigot + Floodgate on Paper/Spigot are still required, and the
port/transport change only takes effect at a server start/restart. Geyser 2.12.1
defaults to NetherNet, so `transport: both` is what keeps the RakNet listener (and
the whole LAN bridge) working; only the port is restored when the bridge is
disabled.

## Process persistence

| Requirement | Status | Where |
|---|---|---|
| Foreground service + persistent notification (status, uptime, players, Stop/Restart) | Implemented; **Manual-only** device verification | `core/service/ForegroundServerService.kt`, `ServerNotifications.kt` |
| Partial WakeLock + WifiLock for lifetime | Implemented; lifecycle reviewed | `core/system/WakeLocks.kt`, service |
| Near-silent MediaPlayer as best-effort only, feature-flagged | Implemented, **off by default** (`ENABLE_SILENT_MEDIA_HACK=false` + settings flag) | `core/system/SilentMediaKeepAlive.kt`, `res/raw/silence.wav` |
| Battery-optimization exemption guidance with deep links | Implemented | `core/system/PowerGuides.kt`, wizard/Settings/PowerGuide |
| Android 12+ Phantom Process Killer guidance, no hardcoded OEM paths | Implemented (version-aware, ADB commands, dontkillmyapp fallback) | `PowerGuides.kt`, `PowerGuideScreen.kt` |
| Detect unexpected exit, log reason, offer restart | Implemented (classification + reports + notification/UI restart; optional auto-restart with 3-attempt cap) | `ExitClassifier.kt`, `ServerManager`, `ForegroundServerService` |

## Memory

| Requirement | Status | Where |
|---|---|---|
| RAM slider `-Xms/-Xmx`, capped by `ActivityManager.MemoryInfo` | Implemented | `MemoryAdvisor.kt`, `LaunchPlan.kt`, Create/Detail sliders |
| Auto mode picks safe value; warn above safe threshold | Implemented (safe ceiling + auto recommendation) | `MemoryMath`, `MemoryAdvisor`, screens |
| Live JVM heap + device RAM in UI and notification | Implemented (jcmd heap with `/proc` fallback; device free RAM) | `HeapSampler.kt`, service notification, Detail/Console |

## Console & files

| Requirement | Status | Where |
|---|---|---|
| Live console with level filter, command input, export to file | Implemented + unit-tested parser | `ConsoleScreen.kt`, `ConsoleParser.kt` |
| Built-in file browser for working dir | Implemented; traversal-safe + unit tests | `FilesScreen.kt`, `FileBrowser.kt` |

## First-run wizard

| Requirement | Status |
|---|---|
| JRE download | Implemented (step 2) |
| EULA (Minecraft only) | Implemented (conditional step when the user opts into Minecraft) |
| Storage permission | **Not needed by design**: SAF is used for imports/exports, server data is app-private. Wizard/README explain this. |
| Battery exemption | Implemented (step 3) |
| Phantom-killer guidance | Implemented (step 4, Android 12+) |

## Testing targets

| Target | Status |
|---|---|
| Vanilla | ✅ booted 1.20.4 on the extracted Termux JRE (`Done (36.419s)`) |
| Paper | ✅ booted 1.20.4 (`Done (15.696s)`); JNA ABI selector added for later boots |
| Fabric | ✅ generated a real 1.20.4 Fabric server launcher with the official installer (loader 0.15.11) and booted it on the extracted Android JRE (`Done (38.487s)`) |
| Non-Minecraft JAR | ✅ WireMock standalone booted + HTTP 200; original sample HTTP server booted + `/health` 200 |

## Honest flags (requested)

1. **Silent media is unreliable on 14+** — off by default, feature-flagged, documented as marginal; FGS + battery exemption + phantom guidance are the real defenses.
2. **Shaders are client-side** — no shader button exists; no claim of server-side shader support.
3. **Generic Java is a wide net** — labelled Advanced/Experimental; raw args, raw files, port/OOM/missing-lib errors surfaced; no polish promise.
4. **Modrinth only** — no CurseForge API/key; Hangar not integrated.
5. **Phantom killer varies by OEM** — no hardcoded menu paths; generic steps + ADB + help link.
6. **OOM kills are silent** — continuous transcript (`logs/console.log`), crash reports, conservative default heap, restart prompt.
7. **Play policy risk** — APK targetSdk 28; `docs/POLICY.md` documents the sideload strategy and a Play variant would need a different execution strategy and the media hack removed.
8. **JRE size** — not in APK; downloaded from Termux mirrors with SHA-256 verification (~96–117 MB per major, ~170 MB installed).
9. **LAN discovery is best-effort** — the Minecraft announcement is an emulation of “Open to LAN”, not a protocol dedicated servers implement. Clients must be on the same Wi-Fi/hotspot; AP/client isolation and guest networks block LAN traffic; some clients/versions do not scan the LAN list, and Bedrock clients still need Geyser/Floodgate. The LAN card's manual address is authoritative.
10. **Bedrock LAN is best-effort too** — the 19132 bridge makes a **Geyser-Spigot + Floodgate** setup discoverable over RakNet (IPv4 only, applied at the next server start; Geyser is set to `transport: both` because 2.12.1 defaults to NetherNet). AP/client isolation, router broadcast filtering and Windows client firewalls can still block discovery. **Bedrock 26.60+ clients use NetherNet and may not join through 19132** — use Geyser's direct **Add Server** `<phone-ip>:19133`; Geyser's NetherNet native library may not load on Android/bionic (plugin-native limitation), some 2026 clients fail to list a valid pong (MCPE-239883), and console Bedrock builds vary.

## Not testable in this environment

On-device FGS/Doze/swipe/notification rendering, SAF provider interactions,
actual `exec()` from the app sandbox on a retail ROM, the real-network LAN paths
(multicast announcement, AP/client isolation, Android 16/17 local-network
consent), the Bedrock bridge's real LAN discovery/relay and the NetherNet direct
path (need a Geyser server plus Bedrock clients of both generations, and Geyser's
NetherNet native library on the phone): no device/emulator/ADB was available.
`docs/TESTING.md` marks these as manual-only and provides the full checklist;
the host-level integration evidence is in that file.
