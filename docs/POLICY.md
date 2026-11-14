# Play Store / distribution policy notes

JavaHost is designed to be **sideloaded** (GitHub Releases, F-Droid, IzzyOnDroid)
in its current form. This document records the deliberate policy-sensitive
choices and what a Play variant would need.

## 1. targetSdk 28 (W^X workaround)

Android 10+ forbids `exec()` of files in app-writable storage for apps targeting
API 29+. A downloaded JRE cannot be placed in `nativeLibraryDir` after install,
so JavaHost targets API 28 (same approach as Termux and TinyMCServer) to keep
`exec()` working. Google Play requires a recent target API for new listings and
updates, so this build is not Play-eligible.

**Play variant options** (not implemented here, in order of practicality):

1. Bundle a **pre-linked, signed runtime** in the APK and execute it from
   `nativeLibraryDir` (huge APK, per-ABI split, JRE license notices). Only
   realistic for one Java major and one ABI.
2. Ship a small Play-compatible "client" that connects to a separately
   installed host (not the product as described).
3. Use a platform-approved code-loading service (none exists for arbitrary
   server JARs today).

Do **not** simply raise `targetSdk` without replacing the execution strategy:
the app would install and then fail at "Start" with `EACCES`.

## 2. Background execution / foreground service

- Uses a `specialUse` foreground service with an ongoing notification and
  `startForeground()` within 5 s. Manifest includes the matching permission and
  subtype property.
- Holds a partial WakeLock and WifiLock only while a server JVM is actually
  running.
- Does not use `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` silently: the wizard
  explains why, opens the system request screen, and offers the settings
  fallback.
- Does not auto-start on boot; the user starts every server explicitly.
- `stopWithTask=false` / no-op `onTaskRemoved` is intentional and must be
  explained in any store listing ("keeps a user-started server alive when the
  app is swiped away").

## 3. Silent-media keep-alive

`SilentMediaKeepAlive` is a marginal trick that plays a near-silent looping
audio file to make the process less likely to be frozen. Since JavaHost 1.1.6
the build flag is enabled so users can toggle it in Settings without rebuilding:

- `BuildConfig.ENABLE_SILENT_MEDIA_HACK = true` (compiled in);
- still off by default via `AppSettings.silentMediaHack`;
- documented as unreliable on Android 14+ and **not** the primary mechanism;
- never advertised as a guarantee.

For a Play build, set the flag back to `false` (and optionally strip the class) to
avoid "deceptive background audio" / battery policy questions. The real defenses
are FGS + battery exemption + phantom-process guidance.

## 4. Arbitrary code execution / JAR hosting

The app runs user-supplied Java programs. This is the product, not an
afterthought. Play policy considerations:

- clearly disclose that imported JARs execute with the app's sandbox
  permissions and network access;
- no bundled Minecraft server software or plugins;
- no redistribution of CurseForge/Modrinth content; Modrinth downloads are
  fetched from Modrinth at the user's request;
- no root, no `MANAGE_EXTERNAL_STORAGE`, no accessibility/service abuse;
- server software licenses remain the user's responsibility (Mojang EULA for
  Minecraft servers).

## 5. JRE download and licenses

- The JRE is downloaded from Termux APT mirrors at the user's request; the APK
  does not redistribute OpenJDK.
- SHA-256 values come from the mirror's HTTPS `Packages` index and are verified
  before extraction. The app does not verify the index's GPG signature.
- The installed JRE's own license files are kept (`legal/`), and the About
  screen credits OpenJDK/Termux.
- If a Play build ever bundles a JRE, include the OpenJDK GPLv2+CE notice and
  corresponding source offer/URL, plus per-package licenses.

## 6. Data safety / privacy

No account, analytics, ads or telemetry SDK. Network calls:
- Termux mirrors (JRE packages; user initiated),
- Modrinth API (search/version/download; user initiated),
- user-tapped help links (dontkillmyapp.com, Minecraft EULA, GitHub).

No user data is uploaded. Server logs and world files stay in app-private
storage unless the user explicitly exports them via SAF. The Data Safety form
would declare no collection/sharing.

## 7. Recommended distribution

For now: GitHub Releases + F-Droid/IzzyOnDroid from source, with a README that
states arm64/Android 8+ and the sideload/W^X caveat. If Play distribution is a
goal, produce a separate flavor with a Play-compatible runtime strategy and the
media hack removed; do not submit this flavor.
