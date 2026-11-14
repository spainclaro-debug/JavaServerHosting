# Troubleshooting

## “Stopping the server never finishes / notification keeps saying Shutting down”

Fixed in **1.1.2/1.1.3**, but here is how the stop path works and what to do if it
happens again:

1. The app sends `stop` to the Minecraft console, then waits up to 20 s for a
   clean world save. If the JVM does not exit, it sends `SIGTERM`, then
   `SIGKILL` (including a direct pid-level kill) and finally force-resets the
   session so the notification cannot loop forever.
2. The “Shutting down…” notification is shown once; it is no longer re-posted
   every 2 seconds while stopping.
3. If the server still refuses to die, the app posts a **“Server stop forced”**
   alert and clears the foreground service. Check the console for a stuck
   save/shutdown hook and try again.
4. If the app process itself is killed by the ROM mid-stop, Android may leave
   the child JVM orphaned. Keep the battery-optimization exemption and follow
   the Phantom Process Killer guide.
5. **False crash after a normal stop** (“Server exited unexpectedly: A required
   class or native library is missing”, exit code 0) was a classification bug:
   shutdown warnings in the console tail were being treated as the cause of
   death. 1.1.3 treats exit code 0 as a normal/expected exit and sets the
   stop flag synchronously, so this report no longer appears after Stop.

## “I installed Geyser / Floodgate / ViaVersion but they are not loading”

The console now prints a diagnostic on every Minecraft start:

```
Server type: paper · 3 plugin JAR(s) in plugins/, 0 mod JAR(s) in mods/
Bukkit-family server: 3 plugin JAR(s) will be loaded from plugins/.
```

Check that line. The requirements are:

| Server JAR | Loads plugins? | Loads mods? |
|---|---|---|
| **Vanilla** | ❌ no | ❌ no |
| **Paper / Spigot / Bukkit** | ✅ `plugins/` | ❌ |
| **Fabric** | ❌ | ✅ `mods/` |
| **Forge / NeoForge** | ❌ | ✅ `mods/` |

- **Vanilla servers cannot load Geyser, Floodgate or ViaVersion.** JavaHost now
  blocks installing Bukkit plugins/mods on incompatible server flavors instead
  of downloading files the server will ignore. Import a Paper/Spigot server JAR
  from [papermc.io](https://papermc.io/downloads/paper) as a new server (keep your
  `world/` and move your `plugins/` folder).
- Install plugins with the correct loader and game version. The app prefilters
  Modrinth by game version and loader, and refuses a version whose loaders do not
  match the server (`Not for a Paper server`).
- **Restart the whole server** after installing plugins; `/reload` is not
  equivalent and many plugins will not load that way.
- Geyser + Floodgate are for **Bedrock** clients; ViaVersion is for **Java**
  clients on a different Minecraft version. Installing Geyser does not help a
  Java client and installing ViaVersion does not help Bedrock.
- If `plugins/` shows 0 JARs on the Mods screen, the install went to another
  instance. Open the correct server → Mods & plugins → Installed plugins.
- Paper may reject a plugin compiled for a newer MC version; the console shows
  the plugin name and the error. Use a version matching the server.

## Generic servers show “connections” instead of players

For non-Minecraft JARs the console, notification and server screen show:

- the server **port** parsed from its log (or the optional port you set), and
- the number of **established TCP connections** on that port, read from
  `/proc/net/tcp`/`tcp6`. If the ROM hides `/proc`, it shows `n/a`; this is not
  an error.

## “The server is running but does not appear in Minecraft”

Android Wi-Fi often blocks the multicast that Minecraft uses for LAN discovery,
so a healthy server may not show up automatically. 1.1.5 prints a line like:

```
LAN address: 192.168.1.50:25565 — if Minecraft does not list the server
automatically, use "Add Server" with this address.
```

Add it manually in Minecraft (same Wi-Fi/hotspot, TCP port 25565; Bedrock uses
UDP 19132 through Geyser). If no LAN address is printed, the phone is not on a
LAN; connect it to Wi-Fi/hotspot first.

## “A JAR import fails with duplicate entry (LICENSE) / not a valid archive”

Some Spring Boot fat JARs (for example **Suwayomi**) contain duplicate `LICENSE`
entries. 1.1.5 retries with a tolerant ZIP reader and imports them as generic
servers. If you are on an older build, update JavaHost.

## “Stop is stuck / I had to force-stop the app”

Use **Force stop** on the server screen (1.1.5) or the **Force stop** action on
the ongoing notification. This kills the JVM by PID and clears the session
without killing the whole app. See the stop section above for the normal
escalation path.

## “The server appears in my Minecraft server list but I cannot join”

Check in this order:

1. **Version mismatch** — the client can see the server MOTD but the join is
   rejected when the protocol differs. Install **ViaVersion** (and ViaBackwards
   if needed) on a **Paper/Spigot** server, or use a client version matching the
   server (`server.properties`/Paper version).
2. **Bedrock vs Java** — Bedrock clients need **Geyser + Floodgate** running on a
   Paper/Spigot server, plus UDP port 19132; Java clients need the Java port
   (25565 by default).
3. **Vanilla + plugins** — see above. A Vanilla JAR will never load ViaVersion,
   so the client/server version mismatch remains.
4. **online-mode / authentication** — a server with `online-mode=true` requires a
   valid Minecraft account; check the console for authentication errors.
5. **Port / network** — the phone firewall/hotspot must allow the port; make sure
   `server-port` is not already used by another app/server.
6. **Whitelist / ops / proxy** — check the console and `server.properties`
   (`white-list`, `online-mode`, `enforce-secure-profile`).
7. **World incompatibility** — a world from a newer MC version may fail to load;
   the console shows the error.

## “The console does not show plugin lines”

- If the server is Vanilla, it will never show plugin lines (see above).
- If the server is Paper, plugin loading messages appear **after** the “Done”
  line, e.g. `[Server thread/INFO]: [ViaVersion] Enabling ViaVersion vX`.
- Open **Console**, clear the level filter/search, and scroll to the start of
  the log. The app also writes the transcript to `logs/console.log` in the
  instance working directory.
- The startup diagnostic (`Server type: … N plugin JAR(s)`) is at the top of the
  app's console for every Minecraft start.

## Logs to share when asking for help

- In-app **Console → Export** (redact IPs if needed).
- The crash report under the instance `crashes/` folder, if the server exited
  unexpectedly.
- The “Server type / plugin count” line and the first ~100 lines after `Done`.
