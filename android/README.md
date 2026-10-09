# JBC B·IRON — native Android app

Native Kotlin/Jetpack-Compose port of the web `dashboard.py`. Scans for every
advertised `JBC_` handle, connects to each over BLE, polls `<E>` at 4 Hz, and
shows a per-iron card (green master toggle, SETPOINT + BATTERY tiles, live
tip-temperature graph). Portrait-first. Matches the web dashboard's dark theme.

## Status

Builds, installs, and runs on a **Moto X4 (Android 9 / API 28)**. **Verified live
2026-07-22** against two real irons at once (JBC_NANO + JBC_Std): simultaneous
BLE connections, 4 Hz telemetry, setpoint/battery/graph, toggle reflects real
state. No header ribbon — cards share screen height (weighted), so all irons fit
with no vertical scroll. Flow: scan → connect → live status → set-temp dialog
(±10°) → power on/off.

## Build & install

Gradle can't build on the SMB/NAS path, so both scripts mirror the source to a
local dir and build there. Every build runs the `ModelTest` unit tests first
(`parseStatus` parity with `jbc_biron.py`).

**Windows (Win10):**
```powershell
pwsh -NoProfile -File build.ps1 install   # build + install to the connected phone
pwsh -NoProfile -File build.ps1           # build only; APK lands in .\out\
```
Mirror `%USERPROFILE%\builds\jbc-android`; JDK = Temurin 17 (or `JAVA_HOME`);
Gradle 8.11.1 from `%USERPROFILE%\builds\tools` or the Gradle wrapper cache
(downloaded once if missing); SDK `%LOCALAPPDATA%\Android\Sdk`. `install`
auto-picks the single API-28+ phone on adb (skips non-Android gadgets like the
Luckfox board) — pass `-Serial <id>` if more than one is attached.

**Mac mini:**
```bash
./build.sh install     # build + install to the connected phone
./build.sh             # build only; APK lands in ./out/
```

**Shared signing key.** `debug.keystore` (gitignored — public repo) is the Mac's
`~/.android/debug.keystore`, used by both machines' debug builds so either can
`adb install -r` over the other's APK without an uninstall (which would wipe the
saved irons). Signer SHA-256 `9a0f7d51…e35355`. If it's missing, re-copy it:
`scp macmini:.android/debug.keystore android/`.

Prebuilt debug APK: `out/jbc-biron-debug.apk`.

## Toolchain (pinned, known-good)

| Piece | Version |
|-------|---------|
| Gradle | 8.11.1 (Mac `~/builds/tools/gradle-8.11.1`; Win wrapper cache) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 (+ compose compiler plugin) |
| Compose BOM | 2024.10.01 |
| JDK | Mac: 21 — Android Studio JBR (`/Applications/Android Studio.app/Contents/jbr`) · Win: Temurin 17 |
| compileSdk / targetSdk | 35 · minSdk 28 |

Brew's OpenJDK 26 is too new for AGP — the Mac build **must** use the JBR 21 that
`build.sh` sets via `JAVA_HOME`. AGP 8.7.3 accepts JDK 17–21.

## Layout

```
app/src/main/java/dev/mklod/jbcbiron/
  Model.kt          status-frame parsing (port of jbc_biron.py parse_status)
  Ble.kt            BleManager: scan-all, per-iron connection, write queue, poll
  MainActivity.kt   permissions (API 28: FINE_LOCATION; 31+: SCAN/CONNECT) + Compose host
  ui/Theme.kt       palette from the web dashboard
  ui/Screen.kt      cards, Apple toggle, tiles, Canvas graph, setpoint dialog
```

## Notes / gotchas

- Each iron accepts **one** BLE connection — close the official phone app first.
- An undocked + OFF handle stops advertising and won't appear until woken/docked.
- BLE writes are serialized through a per-connection mutex (Android allows one
  GATT op at a time); commands and the `<E>` poll share that queue.
- Protocol source of truth is the parent folder's `PROTOCOL.md` (field 5 =
  setpoint, field 2 = tip °C, field 1 = pack voltage; battery % derived from
  voltage). Keep `Model.kt` in sync with it.

## TODO

See **`STATUS.md`** for the latest cross-session review log and prioritized work
(2026-07-22 Windows CC review). Top items:

- **Optimistic toggle** — knob must flip on tap, not after the status round-trip
  (same fix just landed in the web `dashboard.py`). Feels unresponsive today.
- **Sleep-state pill** — color-coded working/asleep/hibernating/charging/off by
  the name (the inactivity indicator; web has it, Android doesn't yet).
- Landscape layout (portrait shipped first per request).
- Optional: firmware/version line, sleep-delay & max-temp controls, per-iron
  connection-lost banner, reactive Bluetooth-on state.
