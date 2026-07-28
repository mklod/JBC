# CHANGELOG — JBC B·IRON

## TODO
> [!tip] Queued for next build
> - **Android BUG (high): scanner dies on screen-off, never resumes → both cards go "offline" and toggle is disabled after the phone sleeps.** Fix: lifecycle-aware scan (restart on onStart/onResume) + add a ScanFilter + reconnect dead persistent conns. Full diagnosis in `android/STATUS.md` (2026-07-24 afternoon). Workaround: reopen the app.
> - Battery-% calibration: cross-check derived % against the official app at a known voltage; tune the SOC curve in `jbc_biron.py` / `Model.kt`.
> - Confirm `<D{n}>` sleep-delay unit, then add a settable inactivity-timeout control.
> - Android: landscape layout; reactive Bluetooth-on state; revert toggle on actual write failure (not just timeout).
> - Optional: WORK-state HCI capture while soldering to decode remaining fields 6/9/13/16.

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
