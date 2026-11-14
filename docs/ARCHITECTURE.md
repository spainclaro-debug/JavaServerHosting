# JavaHost architecture

JavaHost is a general-purpose **Java server host for Android**, not a Minecraft-only
launcher. Minecraft Java is the first-class path because its servers are the most
common JAR servers people want to run on a phone, but the runtime, JAR launcher,
console, file manager and persistence machinery are server-agnostic.

Target: `arm64-v8a`, Android 8.0 (API 26) and newer, no root.

## Why the app targets API 28

Since Android 10 (API 29), an app that targets API 29+ may not `exec()` an ELF
binary stored in its own writable data directory (the W^X restriction). A
downloaded JRE is too large to ship in the APK and cannot be added to
`nativeLibraryDir` after installation, so it has to run from
`files/javahost/jre/...`.

JavaHost therefore keeps `targetSdk = 28`, exactly like Termux and TinyMCServer.
This is a deliberate sideload-oriented choice:

- It is fine for a GitHub/F-Droid APK.
- It would be rejected by Google Play for a new listing; see
  [POLICY.md](POLICY.md) for the Play variant strategy.
- Android 8–16 still install apps targeting 28; newer platform restrictions
  (notification permission prompt, phantom-process guidance) are still handled.

`javahost.targetSdk` in `gradle.properties` exists so a future Play build can be
produced from the same source once a Play-compatible execution strategy is in
place.

## Process model

```
┌────────────────────────────── app process ──────────────────────────────┐
│                                                                          │
│  MainActivity ── Compose UI ── AppViewModel ── AppContainer               │
│                                    │                  │                   │
│                                    │        ┌─────────┴──────────┐        │
│                                    │        │ JreManager         │        │
│                                    │        │ ServerManager      │        │
│                                    │        │ InstanceStore      │        │
│                                    │        │ ModrinthRepository │        │
│                                    │        └─────────┬──────────┘        │
│                                    │                  │                   │
│  ForegroundServerService ──────────┴──────────────────┘                   │
│   (specialUse FGS, WakeLock, WifiLock, notification, silent-media flag)  │
│                                                                          │
└───────────────────────────────────┬──────────────────────────────────────┘
                                    │ ProcessBuilder(java -jar …)
                                    ▼
                     ┌─────────────────────────────────┐
                     │ child JVM: java -jar server.jar │
                     │ workdir = instance directory    │
                     │ stdout/stderr merged → console  │
                     └─────────────────────────────────┘
```

- Exactly **one child JVM** is hosted at a time. Multiple server instances can
  exist on disk; starting a second one while the first is active is rejected.
- `AppContainer` is a process-wide singleton created by `JavaHostApp`. The
  Activity, ViewModel and foreground service all observe the same
  `ServerManager`, which is what makes console/status survive Activity death.
- The foreground service owns the lifecycle: it acquires the locks, posts the
  ongoing notification, calls `ServerManager.start/stop/restart`, and updates
  the notification from the `runtime` `StateFlow`.
- `onTaskRemoved` deliberately does nothing and the service is
  `stopWithTask="false"`: swiping the app from Recents must not kill a running
  server. `START_STICKY` lets the service reattach to a child JVM that is still alive
  in the same app process after the service itself was recreated; after process
  death the next cold start surfaces the persisted crash instead of pretending
  the server is still running.

## Directory layout

Everything is app-private (`Context.filesDir`), so no `MANAGE_EXTERNAL_STORAGE`:

```
files/javahost/
  instances.json                 # list of ServerInstance records
  instances/<uuid>/              # server working directory
    <server>.jar
    eula.txt                     # Minecraft only
    server.properties            # Minecraft only
    plugins/  mods/              # Minecraft only
    logs/console.log             # app-owned console transcript (rotates)
    logs/console.previous.log
    logs/latest.log              # server-owned (Minecraft/log4j), never rotated by us
    crashes/crash-<ts>.txt       # post-mortem report
    crashes/last-crash.txt
    jna/libjnidispatch.so        # generated when a Minecraft server needs it
  jre/
    jre17/ jre21/ jre25/         # installed JRE homes
    jre21.version
  logs/
cache/javahost-downloads/        # .part downloads, SAF staging
```

The SAF picker (`ACTION_OPEN_DOCUMENT`) is used only to **import** a JAR/archive
or **export** logs and files. The server's working directory is never on shared
storage, which avoids scoped-storage and permission churn.

## JRE acquisition pipeline

The APK does not contain a JRE (keeping it ~9 MB release / ~13 MB debug instead of ~120 MB). On first
run the user installs Java 17/21/25:

1. Choose a Termux APT mirror. A user-pinned mirror wins; otherwise the app
   probes the official host and the built-in presets (TUNA, MWT, Grimler, USTC,
   BFSU) in parallel with a 4 s HEAD timeout and tries the lowest-latency
   mirror first. Fetch
   `dists/stable/main/binary-aarch64/Packages.gz`. Each stanza carries `SHA256`
   and `Size`; the app verifies package checksums but does **not** verify the
   index's GPG signature itself (the transport is HTTPS).
2. Parse stanzas with a pure Kotlin parser and resolve the runtime dependency
   closure for `openjdk-<major>`: `libandroid-shmem`, `libandroid-spawn`,
   `libiconv`, `zlib`, `libjpeg-turbo`, `littlecms`, and
   `ca-certificates-java`. Audio/X11-only packages (`alsa-plugins`,
   `openjdk-*-x`) are not installed.
3. Download each `.deb` over HTTPS, verify its SHA-256 and size, and keep it only
   in the cache.
4. Parse the Debian `ar` container, stream `data.tar.xz`, and extract:
   - `usr/lib/jvm/java-<major>-openjdk/` → `jre/jre<major>/`, skipping
     `jmods/`, `demo/`, `include/`, `man/` and `lib/ct.sym`;
   - dependency `.so` files → `jre/jre<major>/lib/`;
   - `ca-certificates-java`'s `cacerts` replaces the Debian package's absolute
     symlink at `lib/security/cacerts`, because the Termux path does not exist
     inside our sandbox.
5. `chmod 0755` `bin/*`, `lib/jspawnhelper`, `lib/jexec` and `*.so`.
6. Write a `jre<major>.version` marker only after the staging directory is
   complete and atomically renamed into place. A failed/cancelled install never
   looks installed.
7. Launch with `JAVA_HOME` and
   `LD_LIBRARY_PATH=<jre>/lib:<jre>/lib/server`; the Termux JRE is built for
   Android/bionic (interpreter `/system/bin/linker64`, API 24), so it runs on
   Android 8+.

A user-provided `.tar.xz` / `.tar.gz` / `.zip` or an already-extracted folder
(SAF tree picker) can be imported instead, for offline or self-hosted runtimes.
Folder imports are searched (bounded depth) for `bin/java`, checked for an
Android/bionic ELF interpreter and an arm64 `OS_ARCH`, then copied into
app-private staging and installed like a downloaded runtime. Oracle/Adoptium
Linux builds are glibc and are deliberately rejected with an actionable message.
Multiple architectures are out of scope: only `arm64-v8a` assets are supported,
and the UI says so.

## JAR detection

`JarInspector` is Android-free (`java.util.zip` only), so it is hermetically
unit-tested. It reads `META-INF/MANIFEST.MF` and samples entries to classify:

| Evidence | Classification |
|---|---|
| `io.papermc.paperclip`, `com.destroystokyo.paper`, CraftBukkit | Paper family |
| `net.fabricmc.loader` | Fabric |
| `net.minecraftforge`, `cpw.mods`, NeoForge | Forge family |
| `net/minecraft/server/Main.class`, bundler `META-INF/main-class` | Vanilla |
| `org.bukkit` | Bukkit family |
| `Main-Class` outside those packages | Generic Java |
| Forge/Fabric installer Main-Class | flagged `needsInstaller` |
| no manifest + no launcher | failure with an actionable message |

It also reads the highest class-file major version (→ minimum Java 17/21/25),
`version.json` / Paper's `Implementation-Version` for the Minecraft version, and
records the evidence lines shown in the UI. The user can always override the
classification manually; detection is a convenience, not a gate.

## Server lifecycle

`ServerManager` states: `STOPPED → STARTING → RUNNING → STOPPING → STOPPED`,
with `CRASHED` for an unexpected exit. On start it:

- validates JRE presence/executable, JAR containment, EULA (Minecraft only) and
  memory, resolving auto-memory from `ActivityManager.MemoryInfo` (conservative:
  ~45% of RAM capped at 2 GB by default);
- writes the launch command with `ProcessBuilder(List<String>)` (no shell):
  `-Xms/-Xmx`, headless, UTF-8, `user.home`, JNA path when needed, user JVM
  args, `-jar`, then `nogui` for Minecraft or the free-text args for generic
  servers;
- merges stderr into stdout and reads lines on a background coroutine;
- parses ANSI-stripped levels, readiness, player counts, joins/leaves, ports and
  crash signatures;
- mirrors every line to the app-owned `logs/console.log` (rotate at ~2 MB; the server's own `logs/latest.log` is left untouched) so an OOM kill still
  leaves a post-mortem;
- computes uptime, heap (`jcmd GC.heap_info`, falling back to `/proc/<pid>/status`
  RSS) and device free RAM every few seconds.

Graceful stop sends `stop` to Minecraft servers and waits; generic servers are
terminated after a short grace period because arbitrary JARs do not share a
stop protocol. Unexpected exits are classified (OOM, port already in use,
unsupported class version, missing library, EULA, signal 137 / system kill,
unknown), written to `crashes/`, persisted on the instance, and surfaced in the
UI and in a notification with a restart action.

## LAN access & discovery

Android phones are not obvious LAN hosts: Wi-Fi frequently blocks the multicast
Minecraft uses for LAN discovery, the first non-loopback IPv4 is often the
cellular or VPN interface, and a dedicated Minecraft server never announces
itself. 1.2.0 adds a small LAN layer under `core/net/` plus a LAN card on the
server screen.

### Address selection (`LanAddress.kt`)

`LanAddressSelector.select()` turns raw `NetworkInterface`s into the addresses
the UI shows. It is pure JVM code and fully unit-tested. An entry survives only
if it is up, not loopback, not point-to-point, a strict dotted-quad **private**
IPv4 address (10/8, 172.16/12, 192.168/16 only), and comes from an accepted
interface. Duplicate addresses are removed (higher-priority kind wins), then
entries are sorted by kind priority, interface name and address.

| Interface name patterns | Kind | Used for LAN? |
|---|---|---|
| `wlan*` | Wi-Fi | ✅ |
| `eth*`, `usb*`, `rndis*` | Ethernet / USB tether | ✅ |
| `ap*`, `softap*` | Hotspot | ✅ |
| `ccmni*`, `rmnet*`, `seth*`, `pdp*`, `clat*`, `rmnet_data*` | Cellular | ❌ rejected |
| `tun*`, `tap*`, `ppp*`, `wg*`, `ipsec*`, `v4-*`, `ip6tnl*`, `sit*`, `dummy*`, `ifb*`, `p2p*`, `swlan*`, `bond*` | VPN / virtual | ❌ rejected |

Empty input or everything filtered returns `emptyList()` instead of throwing.
`AndroidLanInfo` supplies the raw interfaces (`NetworkInterface`), the
connectivity state (`ConnectivityManager`: Wi-Fi / ethernet / mobile data / VPN),
the `MulticastLock`, and the preferred `NetworkInterface` for the announcement
socket. All Android calls are wrapped so missing permissions or services degrade
to an empty state rather than a crash.

### Reachability self-test (`LanProbe.kt`)

“Test LAN reachability” opens a TCP socket from the phone to its **own LAN
IP:port** (`SocketTcpProbe`, 2.5 s connect timeout with one retry on timeout).
This is exactly the path a
client takes through the network stack, and unlike `localhost` it fails when the
server is bound to loopback or the interface is wrong. Results are `REACHABLE`
(with measured latency), `REFUSED` (connection refused / no route), `TIMEOUT`, or
`ERROR`; the UI renders them per address, and `ServerManager.probeLan()` republishes
`ServerRuntimeStatus.lan`. The outcome is advisory: the probe goes from the
phone to itself, so it validates the bind and the phone's TCP stack but cannot
detect router-side AP/client isolation or a client firewall.

### Minecraft LAN announcement (`MinecraftLanAnnouncement.kt`, `MinecraftLanAnnouncer.kt`)

Minecraft's “Open to LAN” broadcasts on UDP multicast group `224.0.2.60`,
port `4445`, with the payload

```
[MOTD]<motd>[/MOTD][AD]<port>[/AD]
```

Dedicated servers do not send this. When the **Minecraft LAN discovery
announcement** toggle is on (default), JavaHost sends that datagram every
`INTERVAL_MS = 1500` ms for the running Minecraft server: TTL 1, socket bound to
an ephemeral port, `setNetworkInterface` pointing at the preferred LAN interface
when possible, and a `WifiManager` `MulticastLock` taken when the platform allows
it. The lock is a receive-side optimisation: sending multicast only needs
`INTERNET`, so a missing lock never disables discovery. MOTDs are sanitised (control
characters/CR/LF stripped, `[`/`]` escaped, trimmed, max 100 chars, fallback
`Minecraft server`). Per-tick failures are reported at most once per 30 s and do
not stop the loop; `stop()` cancels the job, closes the socket and releases the
lock. This is best-effort: clients must be on the same Wi-Fi/hotspot and some
clients/versions do not scan the LAN list, so the manual address from the LAN card
remains the authoritative fallback.

### `server-ip` normalization

`ServerPropertiesCodec` gains `server-ip` as a known key plus
`isLoopbackBind()` and `normalizeBindAddress()`. Before launch, a loopback bind
(`127.x.x.x`, `::1`, `localhost`) is normalised to an empty value so the JVM
listens on all interfaces instead of failing LAN clients silently. The typed
**Bind address (server-ip)** field in the properties editor warns on loopback
values; blank means all interfaces (recommended).

### Local-network permission and forward compatibility

The manifest declares `CHANGE_WIFI_MULTICAST_STATE` (multicast lock) and
`ACCESS_LOCAL_NETWORK` (Android 17's local-network runtime permission; Android 16
has an opt-in equivalent). The app targets API 28, so neither is enforced today;
`ACCESS_LOCAL_NETWORK` is declared now so a future build targeting a newer SDK
has the permission in place. The current runtime consent flow is not requested
from the app yet; the LAN card explains OS/network blockers instead.

### Security note

JavaHost now **intentionally makes the running server reachable on the local
network**: it prints reachable LAN addresses and, for Minecraft, advertises the
server on the LAN via multicast. This is the same reachability a server already
had when bound to all interfaces — the app removes the guesswork, it does not
create a new internet exposure: only devices on the same LAN can connect, and the
default Android app sandbox is unchanged. On networks you do not trust, keep the
server's own authentication (`online-mode`, white-list) and turn off the
Minecraft announcement if you do not want the server listed; because loopback
`server-ip` values are normalised away, stopping the server is what ends LAN
access. The app still does not request root, all-files access or any dangerous
runtime permission.

## Persistence stack

- `foreground service` (specialUse) + ongoing notification
- partial `WakeLock` + `WifiLock` held only while the JVM runs
- user-granted battery-optimization exemption (deep link + fallback)
- guided Phantom Process Killer disabling on Android 12+ (generic walkthrough,
  copyable ADB commands, no hardcoded OEM menu paths)
- continuous log mirror + crash reports

A near-silent looping `MediaPlayer` is implemented as an in-app toggle (default
off, applies live, compiled behind `BuildConfig.ENABLE_SILENT_MEDIA_HACK` so it
can be removed for Play builds). It is a marginal bonus, known to be unreliable
on Android 14+, and must not be relied on or advertised as a fix.

## UI

Compose Material 3, single Activity, hand-rolled navigation stack (no
navigation-compose dependency). Screens are stateless and receive an
`AppUiState` snapshot plus an `AppActions` interface implemented by
`AppViewModel`; high-frequency console lines travel in a separate
`StateFlow<List<ConsoleEntry>>` so log traffic does not recompose every screen.

Flow: first-run wizard (runtime, notifications/battery, phantom-killer guidance,
optional Minecraft EULA) → home (instance list, active-server card) → create
(SAF JAR import, detection, memory/JRE/args, EULA) → detail (controls, live
stats, crash post-mortem) → console / files / server.properties / Modrinth
plugins-mods. Generic instances hide Minecraft-only screens and instead expose
the working-directory browser, raw JVM args and server args.

## Security & privacy

- No accounts, no telemetry, no analytics SDK, no ads.
- Network is used only for the Termux JRE download, Modrinth API/downloads and
  optional update links; all endpoints are HTTPS and cleartext is disabled.
- Downloads are checksum-verified where the source supplies a checksum.
- File paths are resolved relative to the instance root and rejected if they
  escape it (including symlink checks); SAF handles user-chosen external files.
- The app never requests root, `MANAGE_EXTERNAL_STORAGE` or all-files access.

## References / provenance

- [TinyMCServer](https://codeberg.org/starclimber/TinyMCServer) (AGPL-3.0) was
  used as the **architectural reference** for bundling/downloading an Android
  JRE, foreground-service keep-alive, JNA-on-bionic handling and the
  targetSdk-28 W^X constraint. No TinyMCServer source code is copied.
- OpenJDK runtime packages come from the Termux repositories (OpenJDK GPLv2+CE;
  packages carry their own licenses), downloaded by the user at runtime.
- JNA's Android dispatcher library is Apache-2.0; the versioned arm64 binaries
  are packaged as small assets for Minecraft servers that need JNA/oshi.
- Modrinth API v2 is used for plugins/mods; see its terms.
