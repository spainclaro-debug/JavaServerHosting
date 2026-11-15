# Troubleshooting

## Upgrading from 1.2.0

The JavaHost 1.3.0 APK is signed with the same development key as 1.2.0 and
installs as an **in-place upgrade**: server instances, worlds, plugins, the
installed JRE and settings are kept. The Bedrock LAN bridge is on by default and
takes effect at the next server start/restart; enabling it edits the Geyser
`config.yml` `bedrock.port` (19133), `bedrock.transport` (`both`, when the key
exists) and `bedrock.webrtc-port` (19134, when the key exists). Only the port is
restored when you turn the bridge off — `transport: both` and `webrtc-port:
19134` stay so both Bedrock client generations keep working. Back up
`plugins/Geyser-*/config.yml` if you hand-tuned Geyser settings.

## Upgrading from 1.1.6

The JavaHost 1.2.0 APK is signed with the same development key as 1.1.6 and
installs as an **in-place upgrade**: server instances, `server.properties`, the
installed JRE and settings (including the new LAN discovery toggle) are kept. If
you installed a build signed with a different key, Android will refuse the
update; back up the instance working directories before uninstalling.

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
  `/proc/net/tcp`/`tcp6`. Android 10+ SELinux blocks apps from reading those files
  (`neverallow` for `proc_net_tcp_udp`), so a modern phone normally shows `n/a`;
  this is expected, not an error.

## “The server is running but does not appear in Minecraft”

Minecraft **dedicated servers never announce themselves** on the LAN — only a
world opened with “Open to LAN” broadcasts. 1.2.0 emulates that announcement
for the running Minecraft server: while a server is running and **Settings →
Minecraft LAN discovery announcement** is on (default), JavaHost sends
`[MOTD]…[/MOTD][AD]<port>[/AD]` to UDP multicast `224.0.2.60:4445` every ~1.5 s
(holding a `MulticastLock`), so a client on the same Wi-Fi/hotspot should list it
under “Scanning for LAN games”.

Discovery is **best-effort**, not a guarantee:

1. **Check the server screen's LAN card.** It lists the exact `address:port` for
   every usable LAN interface, each with a copy button. Add it with the client's
   **Add Server** dialog instead of guessing. This is the authoritative address.
2. **Confirm the announcement toggle is on** for Minecraft instances. If it is
   off, only manual entry works. The toggle can be changed while the server runs.
   For generic Java servers there is no announcement — always use the LAN card.
3. **Same Wi-Fi/hotspot.** The client must be on the same network as the phone.
   Client isolation, a guest network or a different subnet also stops the
   multicast from arriving — see the checklist below.
4. **Turn off VPNs** on the phone and on the client; a VPN can swallow multicast
   or route LAN traffic out of the network.
5. **Some clients/versions do not scan the LAN list** (modded clients especially,
   and Bedrock is a different protocol). If **Add Server** with the LAN card
   address works and the LAN list stays empty, the server is fine and only
   discovery is blocked — use the manual address, or share the address instead.
6. **Android 16/17 local-network permission.** Android 17 adds a local-network
   runtime consent and Android 16 an opt-in equivalent; if the OS prompts for or
   gates local-network access, allow it for JavaHost (see below).

If no address is shown on the LAN card at all, the phone is not on a LAN (for
example mobile-data-only). Connect it to Wi-Fi/hotspot first.

## “Other devices on the same network cannot connect”

Work through this order; each step is cheap and the app now surfaces most of
them. The server itself may be perfectly healthy while the network blocks it.

1. **Use the exact address from the server's LAN card.** The card shows the
   interface label (`Wi-Fi`, `Ethernet`, `Hotspot`) and `address:port`; use the
   copy button. If several addresses are listed, try each — only one may be the
   network the client is on.
2. **Clear the bind address (`server-ip`).** A loopback value such as
   `127.0.0.1` makes the server reachable only from the phone. 1.2.0 clears a
   loopback bind before start and the **Properties → Bind address (server-ip)**
   field warns about it; leave it blank (all interfaces) unless you know why not.
3. **Same Wi-Fi/hotspot and same subnet.** The phone and the client must be on
   the same network (SSID) and subnet (e.g. both `192.168.1.x`). A different
   subnet, a repeater with its own NAT or a `10.x` cellular address is not the
   same LAN.
4. **Turn VPNs off** (phone and client). A VPN can block inbound connections and
   route multicast away from the LAN.
5. **The phone must not be mobile-data-only.** If the LAN card shows no usable
   address or warns that only mobile data is connected, join Wi-Fi/hotspot first.
6. **Router AP/client isolation and guest networks.** “AP isolation”, “client
   isolation” or a guest Wi-Fi that separates clients will block LAN traffic
   regardless of the app. Disable isolation or use the phone's hotspot.
7. **Hotspot client isolation.** Some phones/ROMs isolate clients connected to
   their own hotspot. If the server works from the phone but not from devices on
   its hotspot, try a different hotspot/ROM setting or a router.
8. **Android 16/17 local-network permission.** Android 17 requires a
   local-network runtime permission (Android 16 has an opt-in equivalent).
   JavaHost declares `ACCESS_LOCAL_NETWORK`; check the system app info for a
   Local network permission and allow it if the OS offers one.
9. **Test from the app.** On the server screen tap **Test LAN reachability**.
   The self-test opens a TCP connection from the phone to its own LAN
   IP:port:
   - `REACHABLE` — the phone side is fine; the blocker is between the router and
     the client (isolation, client firewall, different VLAN/subnet).
   - `REFUSED` — nothing is listening on that interface: check the bind address,
     the port and whether the server is really up.
   - `TIMEOUT` — a firewall or network layer is dropping the connection.
10. **Client-side checks:** the client's firewall/antivirus/Wi-Fi privacy mode,
    a “private DNS/private Wi-Fi” setting, or a corporate/education network can
    all block TCP 25565. Bedrock clients use UDP 19132 via Geyser/Floodgate, not
    the Java port.

If the app's self-test is `REACHABLE` and the address is correct but another
device still cannot connect, the block is in the router/network, not in
JavaHost; see the router isolation steps above.

## “Bedrock clients cannot see or join the server (LAN/Friends)”

Bedrock Edition is a different protocol from Java. The app can only make a
**working Geyser setup discoverable** on the LAN; it does not translate Bedrock
itself. Requirements:

1. A **Paper/Spigot** server (not Vanilla/Fabric/Forge) with **Geyser-Spigot**
   and **Floodgate** installed in `plugins/` (Mods & plugins, or Files).
2. **Bedrock LAN bridge (Geyser)** switched on — server LAN card → Bedrock block,
   or **Settings → Bedrock LAN discovery bridge**. It is on by default.
3. **Restart the whole server** after switching it on. The bridge is applied at
   the next start: it moves Geyser from UDP 19132 to 19133, sets
   `bedrock.transport: both` (Geyser 2.12.1 defaults to NetherNet, which would
   otherwise leave no RakNet listener for LAN discovery) and moves NetherNet's
   WebRTC port to UDP 19134, then starts the relay on 19132.
4. Client and phone on the **same Wi-Fi/hotspot**, no VPN and no mobile-data-only
   phone. AP/client isolation, guest networks and router broadcast filtering
   still block **discovery** (see the end of this section).

**What the LAN card should show when it works:** a **Bedrock Edition** block with
the bridge address `<phone-ip>:19132` **and** Geyser's direct address
`<phone-ip>:19133` for every usable LAN address (copy buttons), plus a status
line like “Bedrock clients can join 192.168.1.50:19132 (Geyser <version> ·
<players> · "<motd>")” once Geyser has answered the app's ping. While Geyser is
still starting the card warns that the bridge is running but Geyser did not
answer on UDP 19133; if UDP 19132 could not be bound it shows a different warning
instead.

**Console lines to look for:**

```
Bedrock Edition: JavaHost's Bedrock LAN bridge listens on UDP 19132 ...
Bedrock bridge: moved Geyser from UDP 19132 to UDP 19133 and set transport to "both" ... (NetherNet WebRTC UDP moved to 19134) in plugins/Geyser-.../config.yml ...
Bedrock LAN bridge: listening on UDP 19132 and forwarding to Geyser on UDP 19133 ...
Bedrock LAN bridge ready: ... Manual Add Server for the LAN bridge: <ip>:19132. Newer Bedrock versions that switched to NetherNet may need the Geyser direct address instead: Add Server <ip>:19133. ...
```

Geyser's own startup line should read `Started Geyser on 0.0.0.0:19133`. If
Geyser still logs `Started Geyser on 0.0.0.0:19132`, or logs no RakNet “Started
Geyser” line at all (Geyser 2.12.1 with its NetherNet default), the bridge was
not applied: turn the setting on, stop the server fully and start it again, and
check the console for the app's `Bedrock bridge:` lines.

**If the server does not appear in LAN/Friends:** use Bedrock's manual
**Add Server** with `<phone-ip>:19132` (UDP, not the Java 25565 port). If manual
join works but the list stays empty, the server is fine and only discovery or
broadcast delivery is blocked — see the last items below.

**Newer Bedrock clients (26.60+, NetherNet).** Bedrock 26.60 (October 2026)
clients stopped joining over RakNet and use Geyser's NetherNet/WebRTC transport
instead (Geyser's NetherNet transition — see the Geyser blog/release notes). The
19132 bridge is the **RakNet LAN-discovery + relay** path: it restores LAN/Friends listing and join for RakNet-era clients and for a Geyser
running `transport: both`, but a NetherNet-only client may not join through
19132. For those clients use the LAN card's **Geyser direct** address:
**Add Server `<phone-ip>:19133`**. The bridge already sets `transport: both` and
moves NetherNet's WebRTC traffic to UDP 19134, so the direct path is enabled by
default once the bridge is on.

Two caveats: on Android/bionic Geyser's NetherNet native library may fail to
load — the console shows “Geyser's NetherNet/WebRTC transport cannot load its
native library on Android/bionic…” and that is a plugin-native limitation, not a
JavaHost bug, so NetherNet-only clients may not be able to join on this phone.
Also, some 2026 clients fail to list a server even when it returns a valid pong
(Mojang bug **MCPE-239883**), so treat manual Add Server as the reliable path.

**“Unable to connect to world” / kicked straight after joining:**

- **Floodgate** must be installed and configured (it lets Bedrock clients join
  without Java accounts). If the console shows Floodgate/key errors, reinstall
  the Floodgate JAR and its generated key/config, then restart.
- With `online-mode=true` and **no Floodgate**, Bedrock clients are rejected as
  unauthenticated; install Floodgate or (for a private server) set
  `online-mode=false` and use a white-list.
- **Version mismatch** — update the Bedrock client or Geyser. ViaVersion /
  ViaBackwards only help **Java** clients; they do not make a Bedrock client
  compatible with the server.
- **NetherNet-only client** — if a Bedrock 26.60+ client cannot join on 19132,
  try `<phone-ip>:19133` (Geyser direct). If that also fails and the console
  shows the NetherNet native-library warning above, the client cannot join over
  NetherNet on this Android build.
- Server full, whitelist, `max-players` or a world still loading — the console
  says which; retry after “Done”.

**If the card says Geyser is not reachable / the bridge is not running:**

- Check Geyser is actually in this instance's `plugins/` (Mods & plugins →
  Installed plugins), not another instance.
- Restart the server and confirm the console reaches Geyser's “Started Geyser”
  line without port-in-use errors.
- Another app (or a hung previous run) may hold UDP 19132 or 19133. Force stop
  the server/app, then start again; the card warns when 19132 is busy.
- Verify `plugins/Geyser-*/config.yml`: after start, `bedrock.port` should be
  `19133`, `bedrock.transport` should be `both` and `bedrock.webrtc-port` should
  be `19134` (unless the Geyser build honours the `GEYSER_BEDROCK_PORT`,
  `GEYSER_BEDROCK_TRANSPORT`, `GEYSER_BEDROCK_WEBRTC_PORT` and
  `GEYSER_BEDROCK_ADDRESS` environment overrides, which JavaHost always sets).
  If `transport` stayed `nethernet`, Geyser has no RakNet listener for the
  bridge to ping: set `transport: both` and `webrtc-port: 19134` in the config
  (or let the app patch it) and restart.
- The console warns after about a minute: “waiting on UDP 19132 but Geyser is not
  answering RakNet on UDP 19133 …” — that means Geyser has not bound the RakNet
  listener yet or the transport patch did not take effect.

**Discovery still blocked?** These are network-side, not app-side: router
AP/client isolation, “multicast/broadcast filtering” or guest Wi-Fi; a Windows
client firewall (allow Minecraft for UDP `4445` and `19132`, and `19133` when
using the NetherNet direct fallback, on private networks); console Bedrock
builds that hide the LAN list or the Add Server UI (behaviour varies by platform
and version — use a build that allows manual servers). The manual
`<phone-ip>:19132` (or `<phone-ip>:19133` for NetherNet-era clients) Add Server
path keeps working when only broadcasts are filtered.

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
   Paper/Spigot server, plus UDP port 19132 through the app's Bedrock LAN bridge
   (newer NetherNet-era clients may need Geyser's direct `<phone-ip>:19133`); Java
   clients need the Java port (25565 by default). See “Bedrock clients cannot see
   or join the server” above.
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
