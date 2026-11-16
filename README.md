# JavaHost

**A general-purpose Java server host for Android.** Import a `.jar`, choose a
Java runtime and a heap, and host Minecraft Java servers (Vanilla, Paper, Fabric,
Forge …) or any other executable Java server from your phone — no root, no
Termux, no PC required.

Built with Kotlin + Jetpack Compose. Target: **arm64-v8a, Android 8.0+ (API 26),
no root**. The APK is intended for GitHub/F-Droid-style sideloading; see
[Why targetSdk 28](docs/ARCHITECTURE.md#why-the-app-targets-api-28).

> **Status:** functional MVP, buildable and tested. **424 hermetic unit tests
> (0 failures, 3 environment-gated skips)**; debug and signed release APKs
> are built. The current release is **1.4.1** (versionCode 12, Home-screen
> cleanup + reliable stop/start/restart + light theme); see
> [CHANGELOG.md](CHANGELOG.md). Minecraft
> (Vanilla 1.20.4, Paper 1.20.4, Fabric 1.20.4), a generic Java server (WireMock)
> and a purpose-built sample HTTP server were booted on an Android/bionic
> OpenJDK 21 during development.
> On-device foreground-service/Doze behaviour is covered by design and a manual
> checklist but was **not** executed in this build environment (no
> device/emulator). See [docs/TESTING.md](docs/TESTING.md).
>
> Prebuilt, installable APKs are attached to
> [GitHub Releases](https://github.com/spainclaro-debug/JavaServerHosting/releases).
> The release APK is development-key signed; the debug APK is for development.
>
> An independent release review (`docs/REVIEW.md`) found **no ship-blockers**;
> its should-fix findings were addressed in `docs/REVIEW-RESPONSE.md`. A
> requirement-by-requirement status table is in
> [`docs/REQUIREMENTS-MATRIX.md`](docs/REQUIREMENTS-MATRIX.md).

## Download

- **APK:** [Latest GitHub Release](https://github.com/spainclaro-debug/JavaServerHosting/releases/latest)
- **Source:** [github.com/spainclaro-debug/JavaServerHosting](https://github.com/spainclaro-debug/JavaServerHosting)
- **Issues / feedback:** [GitHub Issues](https://github.com/spainclaro-debug/JavaServerHosting/issues)

## Features

### Runtime
- Downloads a **lightweight OpenJDK 17/21/25** (aarch64, bionic) on first use
  from Termux APT mirrors — not shipped in the APK. SHA-256 verified against the
  HTTPS package index, extracted to app-private storage, trimmed (no jmods,
  demos, includes) and cached per major version.
- **Fast-mirror selection:** a pinned mirror is preferred; otherwise JavaHost
  probes the official Termux host and several well-known mirrors (TUNA, MWT,
  Grimler, USTC, BFSU) in parallel and starts with the lowest-latency one. Pick
  a mirror in **Java runtimes → Download mirror**; a custom HTTPS mirror URL is
  also possible in Settings.
- Imports a user-supplied JRE **archive** (`.tar.xz`, `.tar.gz`, `.zip`) or an
  **already-extracted folder** via the SAF folder picker, for offline/self-hosted
  use. A folder may be the JRE home or a parent containing it.
- **Oracle/Adoptium Linux builds will not run on Android.** They are linked
  against glibc, while Android uses bionic; no `java` from a Linux JDK archive
  will execute in the app sandbox. JavaHost validates the ELF interpreter and
  rejects such folders/archives with a clear explanation. Use a Termux/Pojav-style
  Android aarch64 runtime (or let JavaHost download one).
- Launches arbitrary JARs through `ProcessBuilder` with `-Xms/-Xmx`, headless
  flags, UTF-8, per-instance working directory, free-text JVM args, raw server
  args and automatic Android-JNA dispatch for Paper-family servers.
- Safe memory selection from `ActivityManager.MemoryInfo`: the slider maximum is
  **device RAM − 1 GB** (6 GB phone → 5 GB, 8 GB → 7 GB), capped at 12 GB; auto mode
  still suggests a conservative 1.5 GB default and the final value is clamped in
  the launcher.

### JAR handling & detection
- SAF `ACTION_OPEN_DOCUMENT` import, copied into the instance working directory.
- `JarInspector` reads `META-INF/MANIFEST.MF`, class signatures and class-file
  versions to classify **Vanilla / Paper / Bukkit / Fabric / Forge / Generic**
  and suggest a Java major (17/21/25), with evidence and manual override.
- Installer JARs (Forge/Fabric installers) are detected and flagged as needing a
  one-time installer rather than being treated as runnable servers.

### Minecraft path (first-class)
- Plugin/mod manager using the open **Modrinth** API (search, versions,
  SHA-1-verified download into `plugins/` or `mods/`).
- `server.properties` editor (typed common keys + raw editor, comments/order
  preserved, port validation).
- EULA flow with the official `https://aka.ms/MinecraftEULA` link; writes
  `eula.txt` and blocks start until accepted.
- Player count parsed from server output and shown in the notification/UI.
- Server readiness detection and crash diagnostics.
- **Bedrock Edition clients** through Geyser-Spigot + Floodgate and the app's
  Bedrock LAN bridge (see LAN features below); Java and Bedrock can both be
  hosted and discovered at the same time.

### LAN hosting & discovery (1.2.0)
- **Correct LAN address resolution:** only Wi-Fi, ethernet and hotspot
  interfaces are used, and only private IPv4 addresses; cellular, VPN and
  virtual interfaces are ignored. Every usable address is listed with a label
  (`Wi-Fi`, `Ethernet`, `Hotspot`) and a copy button, so the phone's data
  connection can never be mistaken for its LAN address.
- **LAN card on the server screen** with the exact `address:port` to type into a
  client's **Add Server** dialog, and **Test LAN reachability** — the app opens a
  TCP connection to its own LAN IP:port and explains `REFUSED`/`TIMEOUT` instead
  of failing silently.
- **Minecraft auto-discovery:** the running Minecraft server is announced on
  UDP multicast `224.0.2.60:4445` (`[MOTD]…[/MOTD][AD]<port>[/AD]`) every
  ~1.5 s with a `MulticastLock`, so clients on the same Wi-Fi/hotspot list it
  under “scanning for LAN games”. The server's LAN card and **Settings** both
  have a **Minecraft LAN discovery announcement** toggle (on by default); off
  keeps the manual address.
- **`server-ip` handling:** a loopback bind (`127.0.0.1`, `::1`, `localhost`) in
  `server.properties` is cleared before start, and the properties editor has a
  typed **Bind address (server-ip)** field that warns when a value would make
  the server reachable only from the phone.
- Caveats that no app can fix: clients must be on the **same Wi-Fi/hotspot**, and
  router AP/client isolation or guest networks block LAN traffic. Dedicated-server
  discovery is best-effort (some clients/versions do not scan); Bedrock clients
  need Geyser/Floodgate and the Bedrock bridge below. See
  [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

### Bedrock Edition LAN (Geyser, 1.3.0)
- **RakNet-era Bedrock clients see the server in LAN/Friends** again, not only
  through manual Add Server. While a Minecraft server with **Geyser-Spigot +
  Floodgate** runs, JavaHost's Phantom-style bridge binds UDP `19132`, answers
  Bedrock's RakNet LAN pings with a correct Unconnected Pong, and relays gameplay
  datagrams to Geyser. The LAN card's **Bedrock Edition** block shows the bridge
  address `<phone-ip>:19132`, Geyser's direct address `<phone-ip>:19133`, the
  Geyser version, MOTD and player count, or a warning if Geyser is not reachable
  yet.
- **Geyser is moved to UDP 19133** for the bridge. Geyser 2.12.1 removed the
  RakNet-only transport and defaults to **NetherNet**, so the bridge also sets
  `bedrock.transport: both` and `bedrock.webrtc-port: 19134` (when those keys
  exist) and passes `GEYSER_BEDROCK_PORT=19133`,
  `GEYSER_BEDROCK_TRANSPORT=both`, `GEYSER_BEDROCK_WEBRTC_PORT=19134` and
  `GEYSER_BEDROCK_ADDRESS=0.0.0.0` to the server JVM. Only the original port is
  restored when you turn the bridge off; `transport: both` stays so both client
  generations keep working. **Restart the server** after enabling it.
- The relay is **reply-only** (Phantom-style): it answers the clients' own LAN
  pings and relays their traffic to Geyser. Unsolicited pong broadcasts are
  disabled in 1.3.0 because they are untested against real clients.
- **Newer clients (Bedrock 26.60+, NetherNet).** Those clients no longer join
  over RakNet and may not join through the 19132 bridge; use Geyser's direct
  address **Add Server `<phone-ip>:19133`** from the LAN card. On Android/bionic
  Geyser's NetherNet native library may not load at all (a plugin-native
  limitation the console reports). Some 2026 clients also fail to list a valid
  server pong (Mojang bug **MCPE-239883**) — the manual addresses remain the
  reliable fallback.
- **Bedrock LAN bridge (Geyser)** is on by default; change it on the LAN card or
  in **Settings**. If the LAN list stays empty, try **Add Server**
  `<phone-ip>:19132`, then `<phone-ip>:19133`. Console Bedrock builds and Windows
  firewall rules can affect discovery; see
  [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

### Generic Java path (power user)
- No Minecraft UI; working-directory file manager, raw JVM args and server args.
  Console/notification show the server port and established TCP connections
  instead of Minecraft player counts.
- Labelled **Advanced / Experimental**: arbitrary JARs may expect a GUI, native
  libs, a specific working directory or fixed ports. Missing libraries, port
  conflicts and OOM are surfaced as readable errors.
- Ships a real non-Minecraft test target (a tiny HTTP server) and was validated
  with [WireMock](https://wiremock.org/) standalone.

### Process persistence & forensics
- Foreground service (`specialUse`) with a persistent notification: status,
  uptime, players, JVM heap, device RAM, **Stop** and **Restart** actions.
- **Reliable Stop → Start / Restart:** the foreground service no longer disposes
  the process-wide server manager when it goes idle, so stopping a server never
  requires closing the app. Start pressed while “Stopping…” is showing is queued
  and runs immediately after the stop, and Restart keeps one service operation so
  it cannot kill the JVM it just started. Stale runtime state is recovered
  automatically with an internal force-stop.
- **Bounded stop:** a Minecraft server gets ~8 s to save and exit, then
  `SIGTERM`, then a 2 s grace before `SIGKILL`; the notification clears as soon
  as the server is down (normally under 2 s). The manual **Force stop** action
  remains for a genuinely stuck child.
- Partial `WakeLock` + `WifiLock` for the server lifetime.
- Battery-optimization exemption flow and Android 12+ Phantom Process Killer
  guidance with deep links, copyable ADB commands and OEM-agnostic wording.
- Continuous console mirror to `logs/console.log` with rotation; on unexpected
  exit a crash report with the last output and a classified reason
  (OOM / port in use / unsupported Java / missing lib / EULA / system kill) is
  written and offered for restart.
- The near-silent looping `MediaPlayer` is a normal in-app toggle (default off,
  applies live). It is best-effort only, not the mechanism.

### Console & files
- Live console: ANSI stripping, level colours and filters, search, auto-scroll,
  command input, Minecraft quick commands, export to a SAF destination.
- Built-in file browser/editor for the working directory: browse, open text
  files, import/export through SAF, rename, delete, create folders, path-traversal
  safe.
- The working directory is app-private; no `MANAGE_EXTERNAL_STORAGE` is needed.

### Look & feel (1.4.0)
- **New light theme:** a warm cream background (`#F0E4C8`) with a brown accent
  (`#5A4C39`); cards use a slightly lighter cream so they separate, and all text
  stays dark-brown/readable. **Dark mode is unchanged** (ink/teal).
- **Active-server accent:** the active server card, its `RUNNING` state pill and
  the active server's status card use the accent colour, while inactive cards
  look the same as before.

### First-run wizard
Device summary → Java runtime install → notification permission → battery
exemption → Phantom Process Killer guidance → optional Minecraft EULA → done.

## Requirements

- Android 8.0 (API 26)+, **arm64-v8a** device (arm64 only; no x86 build).
- ~9 MB release APK (~13 MB debug) + ~96–117 MB JRE download + ~170 MB JRE installed + server/world files.
  Plan for **≥ 500 MB free** for a Minecraft server.
- A server JAR you are allowed to run. JavaHost does not bundle Minecraft server
  software; Mojang's EULA applies to Minecraft servers.
- **Bedrock Edition hosting:** a Paper/Spigot server with **Geyser-Spigot +
  Floodgate** installed in `plugins/` (Geyser is available through the Modrinth
  plugin manager; Floodgate is distributed by GeyserMC and normally has to be
  imported via Files). Bedrock clients use UDP `19132` through the app's LAN
  bridge; Geyser's direct port `19133` is the fallback for NetherNet-era clients;
  Java clients use the Java port (25565 by default). The bridge sets Geyser's
  `transport: both` (RakNet + NetherNet) automatically.

## Build

```bash
# JDK 17+ and Android SDK 34 required.
./gradlew :app:testDebugUnitTest   # 424 hermetic tests
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease     # unsigned; sign with apksigner for distribution
```

Release builds are attached to
[GitHub Releases](https://github.com/spainclaro-debug/JavaServerHosting/releases).
The release APK is signed with a project development key, not a Play/enterprise
key; re-sign with your own key for any real distribution and keep the keystore
private.

On Termux, Gradle's prebuilt aapt2 is x86_64; add
`android.aapt2FromMavenOverride=/path/to/aapt2` to `local.properties` (already
done in this environment). `local.properties` is intentionally git-ignored.

Unit tests (all hermetic, no network/device):

```bash
./gradlew :app:testDebugUnitTest
```

## First run

1. Open the app, follow the wizard. Install **Java 21** (required for modern
   Minecraft; 17 also available for older servers). This downloads ~106 MB.
   If it is slow, open **Java runtimes → Download mirror** and pick a nearby
   mirror (or leave Automatic). If you already have an Android-compatible Java
   21 runtime extracted on the phone, use **Import from a folder** instead of
   downloading.
2. Grant the notification permission and allow the battery-optimization
   exemption. On Android 12+, read the Phantom Process Killer page — this is the
   main reason servers die in the background on some phones.
3. Tap **+ New server**, pick a `.jar`, review the detection result (override if
   needed), set heap/JVM args, accept the Minecraft EULA if applicable.
4. Start the server. Keep the ongoing notification; swipe-from-Recents is safe,
   but some OEMs still need the phantom-process guidance.

## Honest limitations

1. **Silent-media keep-alive is unreliable on Android 14+.** It is a setting
   (default off, applies live) and can be compiled out for Play-sensitive builds;
   the real defenses are the foreground service, battery exemption and
   phantom-process guidance.
2. **Shaders are client-side.** JavaHost does not offer a server “shader” button.
   Server-side resource packs are a server feature and are not a shader
   replacement; this is stated in the UI/README rather than shipping a dead
   control.
3. **Generic Java servers are a wide net.** No polish promise: GUI-expecting
   JARs, native libs and fixed ports are all possible. The generic path is a
   power-user escape hatch with raw args, raw files and explicit error surfacing.
4. **Modrinth only.** No CurseForge (API key + redistribution rules). Hangar is
   not integrated yet.
   **Oracle/Adoptium Linux builds do not work on Android** (glibc vs bionic).
   Use Termux's Android OpenJDK, a Pojav-style Android runtime, or an archive
   that is known to run on Android; JavaHost validates the ELF interpreter and
   rejects glibc builds before install.
5. **Phantom Process Killer varies by OEM.** No hardcoded menu paths; the guide
   is version-aware and offers ADB commands and a dontkillmyapp.com fallback.
6. **OOM kills are silent.** The last console lines are continuously written to
   disk; on restart the UI shows the crash reason and last output. Default heap
   is conservative.
7. **Play Store policy risk.** API 28 target + background execution + the media
   trick are Play-sensitive. Ship via GitHub/F-Droid unless a Play variant is
   prepared; see [docs/POLICY.md](docs/POLICY.md).
8. **JRE size.** Not in the APK. ~106 MB download from Termux mirrors on first
   use, verified by SHA-256, cached per architecture/major.
9. **Bedrock LAN discovery is best-effort and needs Geyser.** It requires
   Geyser-Spigot + Floodgate on a Paper/Spigot server, takes effect at the next
   server start, covers IPv4 only, and can still be blocked by AP/client
   isolation, router broadcast filtering or a Windows client firewall. The
   bridge is the **RakNet** LAN-discovery/relay path: Bedrock 26.60+ clients
   switched to NetherNet (Geyser's NetherNet transition) and may not join
   through 19132 — use Geyser's direct address **Add Server**
   `<phone-ip>:19133`, and note that some 2026 clients fail to list a valid
   server pong (Mojang bug MCPE-239883). On Android/bionic Geyser's NetherNet
   native library may not load. Console Bedrock builds vary in what they show;
   the LAN card's manual addresses are the reliable fallback.
10. **Stop is bounded.** A server JVM that ignores both `stop` and `SIGTERM` for
    ~10 s is `SIGKILL`ed; the console says so and the last few seconds of unsaved
    world changes can be lost. This is deliberate — it keeps Stop fast and lets
    Start work again without restarting the app.

Also: aarch64 only; no root; one child JVM at a time; first Paper boot downloads
its libraries and can take a while; JNA warnings may still appear on some server
versions even though the server runs.

## Troubleshooting

- Stop → Start did nothing until the app was restarted (fixed in 1.4.0)
- Stop still stuck / server will not die
- Plugins (Geyser/Floodgate/ViaVersion) installed but not loading
- Server does not appear in Minecraft / other devices cannot connect
- Bedrock clients cannot see or join the server (LAN/Friends)
- Server visible in Minecraft but join fails

See [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) for the server-flavor /
loader matrix, the LAN card and join checklists. JavaHost 1.4.1 removes the
duplicate Home-screen entry for the running server; 1.4.0 makes Stop → Start work
without closing the app (revivable manager, queued start, one-operation restart,
bounded stop) and adds the light theme; 1.3.0 added the Bedrock LAN bridge on top
of the 1.2.0 LAN work. v1.4.1 is an **in-place upgrade of 1.4.0** (same signing
key), so settings, instances and worlds are kept.

## Project layout

```
app/src/main/java/dev/javahost/
  core/api/          ServerController, JreController, Downloader contracts
  core/model/        domain + UI-support models
  core/storage/      Paths, InstanceStore, SettingsStore, FileBrowser, codecs
  core/runtime/      Termux index/deb extraction, JreManager, permissions
  core/jar/          JarInspector (Android-free)
  core/launch/       ProcessBuilder command building + arg tokenizer
  core/net/          LAN address selection, discovery announcers, Bedrock bridge
  core/server/       ServerManager, console/log/crash/heap/JNA helpers
  core/service/      ForegroundServerService, lifecycle decisions + notifications
  core/system/       powermanagement, memory advisor, silent media, guides
  core/modrinth/     Modrinth API v2 client
  ui/                Compose screens, ViewModel, hand-rolled navigation
docs/                architecture, requirements matrix, testing, policy, review
tools/               sample generic server + verification scripts
```

## Security & privacy

No accounts, telemetry, ads or analytics. Network is used for the Termux
JRE download, Modrinth, user-tapped links, and local-network hosting/discovery
(the LAN sockets only advertise/relay on the phone's own network). All
endpoints HTTPS; cleartext is disabled. No root, no all-files access. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Credits & licenses

- JavaHost source: **Apache-2.0** (see `LICENSE`).
- [TinyMCServer](https://codeberg.org/starclimber/TinyMCServer) (AGPL-3.0) is
  acknowledged as the architectural reference for Android JRE hosting, JNA
  handling and foreground-service keep-alive. **No TinyMCServer source code is
  copied**; JavaHost is an independent implementation that generalises the
  approach beyond Minecraft.
- OpenJDK runtime packages are downloaded from Termux repositories at runtime
  (OpenJDK GPLv2+CE; package licenses apply).
- JNA Android dispatcher binaries: Apache-2.0 (packaged assets).
- Modrinth API: subject to Modrinth's terms; no CurseForge integration.
- Minecraft is a trademark of Mojang/Microsoft. JavaHost is not affiliated with
  Mojang, Microsoft, PaperMC, Fabric or Modrinth.
