# HANDOFF — for a fresh Claude Code session

Read this first, then `README.md`. This is the pick-up-cold brief for continuing
the JBC B·IRON project in a new CC session on the user's Mac (`murky`/darwin).

## One-line status

**Live-tested 2026-07-21 from the user's Windows 10 PC** (bleak/WinRT — no
permission gate there; the macOS TCC blocker below only applies to the Mac).
Confirmed working: scan (`JBC_NANO`, `EA:BE:3E:E6:32:90`), connect, notify,
`<E>` polling, `<V>` (→ `V;8886928 ;`), and a `<T340>`→`<T350>` setpoint
round-trip with `S;` acks. Key correction: **setpoint is status field 5, not
field 2** — `PROTOCOL.md` field table and `parse_status()` updated. Lock flag
(field 12) read `0` and temp writes were accepted → the iron was NOT
parameter-locked over BLE.

## Where things are

- **Docs (canonical):** `L:\PROJECTS\JBC` = `~/nas/murky/PROJECTS/JBC`
  (SMB share `murky`, automounts on access — see the
  `project-murkyserver-nas-automount` memory). Files: `README.md`, `PROTOCOL.md`,
  `REVERSE-ENGINEERING.md`, `jbc_biron.py`, `requirements.txt`, this file.
- **Runnable working copy:** `~/Developer/jbc-biron-ble/` — same `jbc_biron.py`
  plus a `venv/` with `bleak` already installed. Run from here, not the NAS
  (venvs on SMB are flaky; NAS paths also can't be used for gradle builds).
- **Native Android app:** `L:\PROJECTS\JBC\android` (source, Kotlin/Compose,
  package `dev.mklod.jbcbiron`). Gradle can't build on SMB, so `android/build.sh`
  mirrors to `~/Developer/jbc-android-build` and builds with the Android Studio
  JBR **JDK 21** (brew OpenJDK 26 is too new for AGP); pinned Gradle 8.11.1 /
  AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35 / minSdk 28. APK in `android/out/`.
- **Decompiled app source (if you need to re-check the protocol):** was produced
  under the session scratchpad with `jadx -d jadx-out JBCBiron-v2.0.2.apk`; the
  APK came from `https://www.jbctools.com/software/BIRON/JBCBiron-v2.0.2.apk`.
  Re-derive with the steps in `REVERSE-ENGINEERING.md` if the scratchpad is gone.

## The protocol (summary — full detail in PROTOCOL.md)

ASCII over GATT, no checksum. Service `2bbe5a4a-b9df-11ea-b3de-0242ac130004`,
write char `2bbe5f90-…`, notify char `2bbe5c8e-…`, CCCD `00002902` = `0x0100`.
Commands: `<T340>` set temp, `<M>`/`<L>` on/off, `<E>` poll status,
`<P{pin}>` unlock. Replies `;`-delimited; status = `E;current;setpoint;code;…`,
field 12 = lock flag. Work range 100–450 °C. Device advertises name `JBC_*`.

## THE blocker (don't re-discover this from scratch)

macOS default-denies Bluetooth to the ad-hoc-signed venv Python with **no
dialog**. Confirmed dead ends: `tccutil reset Bluetooth [com.apple.Terminal]`
both fail (exit 70); it can't be added manually in System Settings → Privacy &
Security → Bluetooth. **Only known fix:** the user runs the scan from a
*foreground* Terminal window (so macOS attributes it to Terminal and shows the
"Terminal wants to use Bluetooth" prompt → Allow). In this CC session that means
having the user run it via the `!` prefix:

```
! cd ~/Developer/jbc-biron-ble && ./venv/bin/python jbc_biron.py scan
```

If the prompt still never appears, fall back to running the PoC on a Linux box /
Raspberry Pi (no TCC) placed near the iron. Also: the iron must be **awake** and
within a few metres of whatever host runs the script (host = BLE central, NOT
the tablet).

## Deliverable (2026-07-21): `dashboard.py`

The user's actual goal is a minimal app replacement covering five things: set
temp, power on/off, read status, read battery, live current-temp graph.
`dashboard.py` does all five — a self-contained localhost web app (stdlib HTTP +
bleak, no CDN) at `http://localhost:8770`. Verified live end-to-end on Windows.
Two calibration items remain open:
- **Battery %:** currently reports status field 14 (read constant 100 while
  full/charging). Confirm it's really battery % — and not field 18 — by reading
  once when the pack is actually low. `battery_v` (field 1) is solid meanwhile.
- **Power = master switch (RESOLVED per owner):** `<M>`/`<L>` are the app's
  on/off master enable, not a heat toggle. ON → charges in cradle (LEDs blink) +
  heats when used; OFF → no charge (LEDs dark) + won't heat when removed. Docked
  ON/OFF works over BLE with the connection persisting; undocked+OFF drops BLE
  advertising (can't remote-on from there). See PROTOCOL.md.
- Minor: the iron advertised as `JBC_Std` this session vs `JBC_NANO` earlier —
  `find_iron()` takes the first `JBC_*`. If the user has more than one handle,
  add address filtering.

## Deliverable (2026-07-22): native Android app (`android/`)

Kotlin + Jetpack Compose port of `dashboard.py` for the phone. **Verified live
2026-07-22 on a Moto X4 (Android 9 / API 28)** with two irons connected at once
(JBC_NANO + JBC_Std): simultaneous BLE, 4 Hz `<E>` polling, setpoint/battery/
graph, green master toggle reflecting real state. No header ribbon — per-iron
cards are weighted to share the screen so all irons fit with no vertical scroll.

- Build/install: `cd android && ./build.sh install` (details in `android/README.md`).
- `android/…/Model.kt` is a direct port of `jbc_biron.py`'s `parse_status` —
  keep it in sync with `PROTOCOL.md`.
- Build gotcha: `JAVA_HOME` **must** be the Android Studio JBR 21, not brew's
  OpenJDK 26 (AGP rejects it). `build.sh` sets this.
- Open next: exercise the setpoint-dialog write on-device (only toggle + telemetry
  verified so far), add a landscape layout, and a connection-lost banner.

## Next actions, in order

1. ~~Get one live `monitor` run~~ **DONE 2026-07-21**, including an undocked
   HIBERNATION capture AND an Android HCI-snoop capture of the official app
   changing settings — see PROTOCOL.md. Confirmed field map: 1 = battery
   centivolts, 2 = tip °C, 4 = live counter, 5 = setpoint (reads 390 undocked-
   idle), **7 & 8 = sleep delay** (`<D…>`), 10 = ¼-s countdown, 11 ≈ handle temp
   ×10, **17 = max temp** (`<I…>`). **Min temp (`<X…>`) is NOT in the frame.**
   Power: `<L>` works from hibernation (→ 8 OFF, then stops advertising); `<M>`
   is acked but does NOT heat from hibernation (physical wake required). Still
   open: a WORK-state capture while soldering to pin fields 6/9/13/16 and the
   constants 14/18.

   **Capture method that worked (reusable):** phone with the app → enable
   Developer options → "Bluetooth HCI snoop log" (Enabled) → cycle Bluetooth →
   USB debugging on → `adb bugreport <out.zip>` bundles
   `FS/data/misc/bluetooth/logs/btsnoop_hci.log` (no root needed). Dissect with
   `tshark -r btsnoop_hci.log -Y 'btatt.handle==0x000d ...'` — write char was
   handle 0x000d (commands `3c…3e`), notify char 0x000f (`E;…`). Same pcap
   workflow as the razer-joro project (`L:\PROJECTS\razer-joro\captures`). Use
   the Pixel 7, not the JBC tablet (its app is buggy).
2. ~~Lock investigation~~ **Largely resolved live**: field 12 read `0` and
   `<T…>` writes were accepted with `S;` acks → the iron is not
   parameter-locked over BLE. If the app still shows settings as locked, that's
   an app-side/front-panel issue, not the BLE lock. (Background kept for
   reference: `0105` is the NANE/DDE default, wrong product line; B·IRON has no
   app-baked default. Don't blind-brute PINs over BLE.)
3. Home Assistant integration (HA "minimal" is already installed on the tablet,
   `io.homeassistant.companion.android.minimal`). Preferred path: ESP32 +
   ESPHome BLE-client speaking these frames → native HA entities (target-temp
   number, on/off switch, temperature + status sensors). Alt: custom HA
   integration over the host's Bluetooth.

## Environment gotchas

- This Mac is reached over SSH, no GUI console: `sudo` has no TTY and
  `osascript … with administrator privileges` fails — root steps must be run by
  the user in their own terminal.
- There is also an `android` MCP connected to the JBC tablet (Deasytech E19,
  Android 14) — useful for HCI-snoop capture / app inspection, not for running a
  BLE central.
- `L:` is the user's Windows drive-letter for share `murky`. Share `drop` also
  has a `PROJECTS` tree but is *different* storage — don't assume they're the
  same.
