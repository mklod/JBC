# JBC B·IRON — independent BLE control

Goal: control a JBC B·IRON battery soldering iron directly over Bluetooth LE,
replacing the official app (`com.jbctools.jbcbiron`), which is buggy on the JBC
tablet and an old Android tablet and only reliable on a Pixel 7.

The full BLE control protocol has been reverse-engineered from the official app.
No firmware modification is involved — control is just short ASCII strings sent
over a standard GATT characteristic.

## Status

| Item | State |
|------|-------|
| Protocol decoded | ✅ complete — see `PROTOCOL.md` |
| Python PoC controller | ✅ written and **live-tested** — `jbc_biron.py` |
| Live test against iron | ✅ done 2026-07-21 from a Windows PC (scan, monitor, `<E>`/`<V>`/`<T>` round-trip). Fixed the status-field map: setpoint is field 5, not 2. |
| Native Android app | ✅ built + **live-tested 2026-07-22** on a Moto X4 (two irons at once) — Kotlin/Compose, portrait-first. See `android/`. |
| Home Assistant integration | 🔜 planned (ESPHome BLE-client or custom component) |

## Files

- `HANDOFF.md` — pick-up-cold brief for a fresh Claude Code session (read first).
- `PROTOCOL.md` — full GATT map, command table, status-frame layout, status codes.
- `REVERSE-ENGINEERING.md` — how it was decoded (reproducible), tools, findings.
- `jbc_biron.py` — cross-platform (bleak) CLI controller + protocol library.
- `dashboard.py` — **live web dashboard** replicating the app's core (set temp,
  on/off, status, battery, live temp graph). Self-contained; stdlib + bleak.
- `requirements.txt` — `bleak`.
- `android/` — **native Android app** (Kotlin + Jetpack Compose) porting the
  dashboard to the phone. Portrait-first; per-iron cards fill the screen with no
  vertical scroll. See `android/README.md`; prebuilt APK in `android/out/`.

## Live dashboard (the app replacement)

```bash
./venv/bin/python dashboard.py      # then open http://localhost:8770
```

**Multi-iron:** it auto-discovers *every* advertised JBC handle and shows each as
its own card, side by side (responsive grid). No manual connect step — it scans
on start and re-scans every 20 s, so a handle you dock/wake later just appears.
Each card has: a big **Apple-style on/off toggle** (green = ON, grey = OFF) that
mirrors the iron's real state, a **Setpoint** tile (tap it → popup with −10/+10
steppers → Set), a **Battery** tile (%, voltage, and a green *charging* / red
*discharging* badge), a color-coded **state pill** by the name
(working / asleep / hibernating / charging / off), and a live tip-temperature
graph. Freshly-woken handles that lose the first GATT-discovery attempt are
retried automatically within a few seconds.

The state pill surfaces the iron's own **inactivity/motion-sleep** behaviour: the
handle has an accelerometer and, after its sleep-delay of no motion, transitions
WORK → SLEEP → HIBERNATION on its own (firmware) and cools down — you'll see the
pill change and the temperature graph fall. Battery % is estimated from pack
voltage (fields 14/18 are constants, not the percentage — the app derives % from
voltage too); see `PROTOCOL.md`. The sleep-delay *setting* command (`<D>`) is
decoded but its unit is unconfirmed, so a settable timeout control is deferred.

Each iron allows only one BLE connection, so **close the phone app first**. A
handle that is OFF *and* out of its cradle stops advertising and won't appear
until it's docked or woken. Verified live 2026-07-21 on Windows: discovers,
connects, streams status at 4 Hz; set-temp, toggle, and multi-device layout all
work end-to-end. Power on/off is the iron's **master switch**: ON = charges in
the cradle + heats when used; OFF = no charge (LEDs dark) + won't heat when removed.

## Quick start

```bash
python3 -m venv venv
./venv/bin/pip install -r requirements.txt
./venv/bin/python jbc_biron.py scan       # find the iron (name starts JBC_)
./venv/bin/python jbc_biron.py monitor     # live temp/status, 250 ms poll
./venv/bin/python jbc_biron.py set-temp 340
./venv/bin/python jbc_biron.py on
./venv/bin/python jbc_biron.py off
./venv/bin/python jbc_biron.py unlock 1234 # parameter lock, if set
```

Notes:
- **Windows and Linux both work with no permission hoops** (verified live on
  Windows 10, bleak/WinRT). A Linux box / Raspberry Pi can sit permanently near
  the iron as a bridge; macOS is the awkward one (below).
- On **macOS** the venv Python is ad-hoc-signed and macOS default-denies
  Bluetooth to CLI tools with no dialog. `tccutil reset Bluetooth` refuses
  (exit 70) and it can't be added manually in System Settings. Workaround: run
  the scan from a *foreground* Terminal window (not via an automation/agent) so
  macOS attributes the request to Terminal and shows the
  "Terminal wants to use Bluetooth" prompt → Allow. If that never appears,
  use a Linux host instead.
- The iron must be **awake** and within a few metres of whatever runs the script
  (the script's host is the BLE central, not the tablet).

## Protocol in one paragraph

Service `2bbe5a4a-b9df-11ea-b3de-0242ac130004`. Write commands as UTF-8 to char
`2bbe5f90-…`; subscribe to notifications on char `2bbe5c8e-…` (write CCCD
`0x0100`). Commands are ASCII `<letter+arg>`: `<T340>` set 340 °C, `<M>`/`<L>`
power on/off, `<E>` poll status, `<P{pin}>` unlock. Replies are `;`-delimited
ASCII. No checksum. Work range 100–450 °C. Full detail in `PROTOCOL.md`.

## Hardware context

- **Iron:** JBC B·IRON Nano handle (app model id `B_NANO`). Other supported
  handles: `B_100`, `B_500`, `B_TWEEZER`.
- **Tablet the official app runs on:** Deasytech E19, Android 14, MediaTek
  MT6768, 4 GB RAM — a budget station-controller tablet, GMS-free.
- The official app v2.0.2 APK is only linked from JBC's B·IRON product page
  (`jbctools.com/software/BIRON/JBCBiron-v2.0.2.apk`), not the main downloads
  page; every third-party mirror stops at v1.7.

## The lock / PIN situation

"The nano has settings locked" — the B·IRON parameter lock is managed through
this BLE protocol (`<A{pin}>` lock, `<P{pin}>` unlock, `<Q{pin}>` verify).
Important: there is **no factory PIN baked into the app**; the `0105` default
often quoted online belongs to JBC's mains **NANE/DDE** stations, a different
product line — it does not apply to B·IRON. Status-frame field 12 reports
whether the lock is engaged, so the PoC can tell you if it's actually locked
before you guess. Avoid blind PIN brute-forcing over BLE — the station may issue
a recovery-code lockout after repeated failures (unconfirmed whether the BLE
path rate-limits).

## Roadmap

1. Confirm the frames live (run `monitor` near the iron).
2. Home Assistant: the tablet already has HA (minimal) installed. Bridge the
   iron into HA via either
   - an **ESP32 + ESPHome** BLE-client speaking these frames → native HA
     entities (target-temp number, on/off switch, status/temperature sensors), or
   - a small **custom HA integration** wrapping the same protocol.
3. ~~Standalone GUI as a drop-in replacement~~ — **done twice**: `dashboard.py`
   (web) and `android/` (native). Native app next steps: exercise the setpoint
   dialog write on-device, add a landscape layout, and a connection-lost banner.
