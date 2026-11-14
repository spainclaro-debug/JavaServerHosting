# Jar detection — validation notes

Owner: `jar-modrinth` (task-4). This file records what was validated against
**real** jars (which are not committed) versus synthetic fixtures, so the
coverage claims are honest and reproducible.

## Detection results on real JARs

| JAR | Source | Detected | Kind / Flavor | MC version | classFileMajor → suggested JRE | needsInstaller |
| --- | --- | --- | --- | --- | --- | --- |
| `vanilla.jar` (MC 1.20.4) | `/data/data/com.termux/files/home/testtargets/vanilla.jar` | ✅ | MINECRAFT / VANILLA | `1.20.4` (version.json + versions.list) | 61 → **17** | false |
| `paper-1.20.4-499.jar` (Paper build 499) | https://fill.papermc.io/v3 (downloaded, 42.8 MB, not committed) | ✅ | MINECRAFT / PAPER | `1.20.4` (version.json) | 61 → **17** | false |
| `fabric-installer-1.0.1.jar` | https://maven.fabricmc.net (198 KB, not committed) | ✅ | MINECRAFT / FABRIC | `null` (installer carries only its own 1.0.1 version) | 52 → **17** | **true** |
| `forge-1.20.4-49.0.30-installer.jar` | https://maven.minecraftforge.net (5.5 MB, not committed) | ✅ | MINECRAFT / FORGE | `1.20.4` (version.json `inheritsFrom`) | 51 → **17** | **true** |
| `wiremock.jar` (WireMock 3.9.2) | `/data/data/com.termux/files/home/testtargets/wiremock.jar` | ✅ | GENERIC | `null` (library `Implementation-Version: 3.9.2` is ignored) | 55 → **17** | false |
| `h2.jar` (H2 2.2.224) | `/data/data/com.termux/files/home/testtargets/h2.jar` | ✅ | GENERIC | `null` (Main-Class `org.h2.tools.Console`) | 52 → **17** (base classes; MR `META-INF/versions/21` classes are optional) | false |
| `tools/sample-generic-server/generic-server.jar` | built by `tools/sample-generic-server/build.sh` | ✅ | GENERIC | `null` | 61 → **17** | false |

Reproduce the real-JAR checks with:

```sh
JAVAHOST_TESTTARGETS=/data/data/com.termux/files/home/testtargets \
  tools/verify-detection.sh
```

`JarInspectorTest.real vanilla wiremock and h2 jars classify correctly when provided`
skips itself (JUnit `assumeTrue`) when `JAVAHOST_TESTTARGETS` is unset, so CI
without the fixtures still goes green.

## Synthetic coverage (hermetic, built with `JarOutputStream` in tests)

* modern vanilla **bundler** (version.json + versions.list + META-INF/main-class)
* classic vanilla (`net.minecraft.server.Main`, `Implementation-Version: 1.12.2`)
* **paperclip** (`io.papermc.paperclip.Main`) incl. older `patch.properties` layout
* CraftBukkit-style PAPER (`org.bukkit.craftbukkit.Main`, `1.20.4-R0.1-SNAPSHOT`
  normalized to `1.20.4`)
* Fabric server launcher (class + manifest, and MC version from
  `fabric-server-mc.1.20.4-...` file name)
* Forge / NeoForge server launcher
* Forge, NeoForge and Fabric **installer** jars (`needsInstaller = true`)
* Bukkit family, Bukkit API library, Bukkit plugin, Fabric mod, Forge mod (actionable
  failures mentioning `plugins/` / `mods/`)
* generic executables (`wiremock.Run`, `org.h2.tools.Console`)
* non-ZIP, empty, missing file, directory inputs
* class-major → JRE mapping: 52/55/61→17, 65→21, 69/70→25
* multi-release JAR (base Java 8 + Java 21 versioned classes → Java 8 base wins)
* manifest continuation lines
* >5000-entry archive: enumeration is capped, known launcher paths are still
  found through targeted `ZipFile.getEntry` probes
* `tools/sample-generic-server/generic-server.jar` classification (skipped unless built)

## Known limitations / honest caveats

* **Paper API v2 is sunset.** The real Paper JAR above came from the successor
  `fill.papermc.io/v3` API. Detection itself does not talk to Paper; the synthetic
  paperclip fixtures are the authoritative automated coverage.
* **Fabric server launcher JAR** could not be fetched in this environment:
  `https://meta.fabricmc.net/v2/versions/loader/1.20.4/<loader>/server/jar` returned
  HTTP 404 (while `/server/json` and `/versions/loader/...` returned 200), so Fabric
  server detection is covered by synthetic fixtures plus the real Fabric *installer*.
* Minecraft-version detection is deliberately evidence-based: `version.json`,
  `META-INF/versions.list`, `patch.properties`, `Implementation-Version`
  (`(MC: x.y.z)` / clean prefix), then Fabric file name. It never guesses a version
  from arbitrary text, and reports `null` (with an evidence line) when unknown.
* `suggestedJreMajor` is a *minimum* from class-file bytes; JavaHost only installs
  JRE 17/21/25, so Java 8–17 code maps to 17, Java 18–21 to 21, and newer to 25.
  Unknown defaults to 21 (BuildConfig `DEFAULT_JRE_MAJOR`).
* Installer jars are reported as `Success` with `needsInstaller = true` and a warning;
  the UI must refuse to launch them as servers.
* NeoForge is reported as `ServerFlavor.FORGE` (closest model value) with a warning.

## Non-Minecraft end-to-end target

`tools/sample-generic-server/` contains original sample code (JDK-only
`com.sun.net.httpserver`, `--release 17`, no Gradle) and the committed
`generic-server.jar`. It was booted on this device with both the Termux JDK 21 and
the Android-side JRE copy at `/data/data/com.termux/files/home/jre-debs/sim2/jre/bin/java`:

```text
[sample-server] JavaHost sample generic server starting
[sample-server] Java: 21.0.13 (Termux)
[sample-server] Binding port 18082
[sample-server] Listening on port 18082
[sample-server] GET / -> 200
[sample-server] GET /health -> 200
[sample-server] GET /shutdown -> 200
[sample-server] Stopped
```

`curl http://127.0.0.1:18082/health` → `ok`, `GET /` → JSON status. The
`Listening on port <N>` line is the same format JavaHost's console parser uses.
