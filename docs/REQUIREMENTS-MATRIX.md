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

## Not testable in this environment

On-device FGS/Doze/swipe/notification rendering, SAF provider interactions and
actual `exec()` from the app sandbox on a retail ROM: no device/emulator/ADB was
available. `docs/TESTING.md` marks these as manual-only and provides the full
checklist; the host-level integration evidence is in that file.
