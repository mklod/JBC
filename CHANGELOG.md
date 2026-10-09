# CHANGELOG — JBC B·IRON

## TODO
> [!tip] Queued for next build
> - Battery-% calibration: cross-check derived % against the official app at a known voltage; tune the SOC curve in `jbc_biron.py` / `Model.kt`.
> - Confirm `<D{n}>` sleep-delay unit, then add a settable inactivity-timeout control.
> - Android: landscape layout; reactive Bluetooth-on state; revert toggle on actual write failure (not just timeout).
> - Optional: WORK-state HCI capture while soldering to decode remaining fields 6/9/13/16.
> - Optional: show heater power % (status field 4, decoded 2026-10-09) on the cards.

## Build 2026-10-09--0132 — Windows CC: tip graph rework from live captures
### Changes
- **Live tip-swap capture** (`captures/2026-10-09-tipswap.tsv`) showed the 500 °C ceiling was the wrong model: the firmware **slews** the tip reading toward/away from the open-thermocouple rail at ~1000 °C/s, so the re-seat tail (1551 → 1185 → 822 → **465**) leaked onto the graph.
- **New `TipTrace` filter** (Python `jbc_biron.py` + Kotlin `Model.kt`, 1:1), used by `dashboard.py` and `Ble.kt`:
  - ceiling = **setpoint + 10 °C** (user: the tip never legitimately exceeds setpoint; +10 covers the 1–2 °C regulator overshoot);
  - drop any frame moving faster than **500 °C/s** vs the previous frame (real heat-up peaks ~160 °C/s);
  - **±2 °C hysteresis** so the ±1 °C idle flicker plots perfectly flat (user request). First tried a 3 °C deadband, which staircased slow cooling in 3 °C steps on the phone; hysteresis follows drifts in 1 °C steps.
- **Tests replay the real capture:** `test_jbc_biron.py` (6) + `ModelTest` (6). They include a no-staircase property that the old deadband fails.
- **Protocol:** status **field 4 = heater power %** (was "live counter"); tip-swap slew documented; **switching ON while docked is a real ~3 s heat burst** (WORK at up to 97 % power before cradle detection, tip overshoots to ~308 °C). That explains the user's "graph heats briefly when switched ON in the cradle": it isn't an app bug. See `captures/2026-10-09-offon.png`.
> [!warning] Testing Checklist
> - [x] Web, live swap #2 (`captures/2026-10-09-tipswap-2.tsv`): graph max 350, every artifact frame dropped (649; 1551 ×2, 1194, 835, 476, 146), heat-up resumes at 183
> - [x] Web, live idle: raw 26/27/28 and 29/30/31 → graph one flat value each
> - [x] Phone (new build): both irons connect; Std idle line flat; cooling curve smooth
> - [ ] Phone: live cartridge swap on the new build — graph stays ≤ setpoint, no spike
>   - Notes:
> - [ ] Mac CC: pull and build once to confirm the Kotlin changes build there too (Win10-built + installed already)
>   - Notes:

## Build 2026-10-09--0113 — Windows CC: Win10 Android build + Android tip-swap filter
### Changes
- **Android app now builds on Win10.** New `android/build.ps1` (twin of `build.sh`): mirrors to `%USERPROFILE%\builds\jbc-android`, Temurin 17 + pinned Gradle 8.11.1 (from the wrapper cache), copies the APK to `android/out/`, `install` auto-picks the API-28+ phone on adb (ignores the Luckfox board that's also on adb).
- **Shared debug signing key.** Both build scripts sign with `android/debug.keystore` (the Mac's debug key, gitignored because the repo is public), so a Windows build can `install -r` over the Mac-built app without an uninstall that would wipe the saved irons. Verified: Win-built and Mac-built APKs both carry signer `9a0f7d51…e35355`.
- **Android tip-swap filter** (mirror of the 0054 Python fix): `Model.kt` gains `TIP_MAX_PLAUSIBLE_C` + `IronStatus.tipValid`; `Ble.kt` only appends trustworthy samples to the graph history.
- **Unit tests gate every build:** `ModelTest` (5 tests) pins `parseStatus` parity with `jbc_biron.py`, including the 1100 °C / NO CARTRIDGE rejection. Green on Win10 and on the Mac (`build.sh` run over SSH).
- `dashboard.py --raw-log PATH` records every raw BLE frame (TSV) for live captures such as the tip-swap test.
> [!warning] Testing Checklist
> - [x] `build.ps1` builds on Win10 (cold 78 s, incremental ~19 s); tests 5/5
> - [x] `build.sh` still builds on the Mac with the shared key + test gate
> - [x] APK signer matches the installed Mac build (`9a0f7d51…`)
> - [x] `build.ps1 install` onto the Moto X4 upgrades in place (saved irons survive)
>   - Notes: firstInstallTime unchanged (2026-07-22), both saved irons intact after install.
> - [x] Live tip swap: capture the real frame/status code
>   - Notes: pull = one 618 frame (status still WORK) then NO CARTRIDGE @ 20; re-seat = 1551/1551/1185/822/465 slew tail. The 500 ceiling let 465 through → superseded by build 0132.
> - [x] Same on the phone app after install → superseded by build 0132

## Build 2026-10-09--0054 — Windows CC: filter tip-swap temperature spike
### Changes
- **Live graph no longer spikes to ~1100 °C during a cartridge/tip swap.** Pulling the cartridge opens the tip thermocouple, which reads a rail value; the firmware passes it through in status field 2.
- `jbc_biron.py`: added `TIP_MAX_PLAUSIBLE_C = 500`; `parse_status()` now returns a `tip_valid` flag (`current ≤ 500 AND status != NO CARTRIDGE`).
- `dashboard.py`: the web graph only records a sample when `tip_valid` — the line holds flat across the swap instead of spiking.
- Logged the matching Android fix for the next Mac build (`android/STATUS.md`).
> [!warning] Testing Checklist
> - [x] Unit-verified: 1100 rejected (NO-CARTRIDGE and WORK status), 450/340/28 kept
> - [ ] Live end-to-end: pull a cartridge on a real iron, confirm the web graph stays flat (no 1100 spike)
>   - Notes:
> - [ ] Android build picks up the same filter and verified on device
>   - Notes:

## Build 2026-07-27--1753 — Mac CC: fix scanner dies on screen-off (won't reconnect)
### Changes
- **Fixed the "come back, both cards offline, toggle won't respond" bug** (Windows-reported). Root cause: `startScan()` ran once in `onCreate` with no filter; Android suspends unfiltered BLE scans on screen-off and never auto-resumes.
- **Lifecycle-aware scanning:** scan start/stop moved to `onStart`/`onStop` — screen-on re-arms the scan (and reconnects), screen-off stops it and drops the links (saves the iron packs).
- **Filtered scan:** service-UUID + per-known-address `ScanFilter`s (fresh install → unfiltered by name). Known irons now reconnect **by address** even if a filtered result has no name.
- Verified on the Moto X4: launch → both connect; **screen off → drop; screen on → both reconnect in ~5 s.**
> [!warning] Testing Checklist
> - [x] Screen-off drops links, screen-on re-scans and reconnects both (~5 s)
> - [x] Both cards still connect on cold launch (filtered scan)
> - [ ] Confirm a brand-new (unknown) handle is still discovered on fresh install
>   - Notes: unfiltered fallback when no known irons — untested with a 3rd handle

## Build 2026-07-24--1400 — Mac CC: persistent cards, stable reconnect, badge fix, git
### Changes
- **Persistent static cards (Android).** Known irons are saved to SharedPreferences; a card once seen is **never removed** — a dropped iron shows "offline" with last-known values instead of vanishing, and both cards appear on a cold launch before either connects. Fixes the "sometimes only one card" complaint.
- **Connection stability (Android).** Replaced the one-shot connect (which caused a `reason=8`/`reason=62` reconnect storm with the phone inches from the irons) with **scanner-driven connect + a 3 s per-iron backoff** (`autoConnect=true` was tried but its initial connect was too slow). Poll **4 Hz → 2 Hz** to halve radio load with two simultaneous links. Result: fast connect (~3–7 s), no flapping.
- **Battery-badge fix (Android).** A card no longer shows "discharging" when disconnected or OFF (was stale/misleading) — charging/discharging renders only for a live, connected iron.
- **Contention removed.** Uninstalled the official `com.jbctools.jbcbiron` app from the Moto X4 (and rebooted to clear its stale GATT-client registrations) so our app owns the single per-iron BLE slot.
- **Repo.** Initialized git and pushed to `github.com/mklod/JBC`. Personal purchase records (order PDFs/screenshots, `archive/`) and build artifacts are gitignored.
- Field note: "irons heating in the cradle" is expected — a powered-ON B·IRON heats to setpoint **even docked** (confirmed behavior). The app never auto-heats; it only polls `<E>` and acts on explicit taps.
> [!warning] Testing Checklist
> - [x] Both cards persist — cold launch shows both "offline", then they connect
> - [x] No reconnect flapping over a sustained watch (reason 8/62 storm gone)
> - [x] Disconnected/OFF card no longer says "discharging"
> - [ ] Optimistic toggle + sleep pill re-confirmed on device after these changes
>   - Notes:

## Build 2026-07-24--1354 — Windows CC: docs + Android review
### Changes
- Reviewed the native Android app (Ble/Model/Screen/MainActivity + manifest) against the live-verified protocol and web dashboard. Protocol, parser, permissions all confirmed correct; flagged two parity gaps (optimistic toggle, sleep-state pill). Logged to `android/STATUS.md`.
- Added the standard `_status.md`, `WORKPLAN.md`, `CHANGELOG.md` (project previously had none).
> [!warning] Testing Checklist
> - [ ] Android parity fixes verified on device (Mac session reports done 2026-07-23 — confirm on next build)
>   - Notes:

## Build 2026-07-22 — Windows CC: web dashboard feature-complete
### Changes
- Battery % now **derived from pack voltage** after proving fields 14 & 18 stay 100 at 7.37 V (not the % field). Added 2S Li-ion SOC curve.
- Battery card: green **charging** / red **discharging** badge.
- Color-coded **sleep-state pill** (working/asleep/hibernating/charging/off) — surfaces the firmware inactivity-sleep behavior.
- Fixed **unresponsive toggle**: optimistic flip on tap + revert on failure.
- Multi-iron: discover + connect to all `JBC_*` handles, side-by-side cards, fast reconnect retry for the GATT-discovery race.
- Setpoint tile → tap → modal with −10/+10 steppers.
> [!warning] Testing Checklist
> - [x] Two irons connect simultaneously, live graph + battery %/status per card
> - [x] Set-temp round-trip (350→370→350) verified live
> - [x] Charging/discharging badge + sleep pill render correctly
> - [x] Toggle flips instantly on tap

## Build 2026-07-21 — protocol live-verified + web dashboard v1
### Changes
- First live contact from Windows (bleak/WinRT): scan, connect, `<E>` poll, `<V>`, `<T>` round-trip.
- **Corrected field map**: setpoint = field 5 (not 2); field 2 = tip °C; field 1 = pack voltage.
- Android HCI-snoop capture of the official app confirmed setters: `<D>` sleep-delay (fields 7/8), `<I>` max-temp (field 17), `<X>` min-temp (not in frame).
- `<M>`/`<L>` characterized as master power (charge-enable + heat-ready).
- Built `dashboard.py` (self-contained web app, localhost:8770).
> [!warning] Testing Checklist
> - [x] Scan finds JBC handles; monitor streams status
> - [x] Setpoint/power commands acked and reflected in status
