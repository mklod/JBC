# _status.md — JBC B·IRON

Canonical project status. Detailed handoff logs live in `HANDOFF.md` (protocol +
Python/web) and `android/STATUS.md` (native app, cross-session Win⇄Mac).

## Current milestone
Three working control surfaces for the JBC B·IRON over BLE, replacing the buggy
official app:
1. **`jbc_biron.py`** — protocol library + CLI (scan/monitor/set-temp/on/off).
2. **`dashboard.py`** — web dashboard (multi-iron, live graph, controls). **Primary
   daily driver on Windows.**
3. **`android/`** — native Kotlin/Compose app (Mac-built), the portable phone app.

Protocol is fully reverse-engineered and live-verified. All three surfaces share
the same command set + status-frame parsing.

## Last session — 2026-07-24--1400 (Mac CC)
- Android hardening: **persistent static cards** (SharedPreferences; a card is never
  removed — shows "offline"/last-known when dropped, and both show on cold launch).
  Fixes the "only one card" complaint.
- **Reconnect stability:** scanner-driven connect + 3 s backoff replaced the
  one-shot connect that stormed with `reason=8`/`62` (phone inches from the irons;
  `autoConnect=true` tried but connected too slowly). Poll 4→2 Hz. Fast, no flapping.
- Fixed the **battery badge** claiming "discharging" while disconnected/OFF.
- **Uninstalled the official app from the Moto X4** (+ reboot) to end BLE-slot contention.
- Initialized git and pushed the project to **github.com/mklod/JBC** (purchase
  records + build artifacts excluded via `.gitignore`).
- Clarified "irons heat in the cradle": a powered-ON B·IRON heats to setpoint even
  docked; the app never auto-heats (only polls + acts on taps).

## Last session — 2026-07-24--1354 (Windows CC)
- Reviewed the new native Android app end-to-end vs the live-verified protocol +
  web dashboard; logged findings to `android/STATUS.md` (2026-07-22 entry).
- Mac CC has since (per `android/STATUS.md`, 2026-07-23) landed the two parity
  fixes (optimistic toggle, sleep-state pill) + persistent static cards +
  scanner-driven reconnect; official app uninstalled from the Moto X4 to free the
  single per-iron BLE slot.
- Created the standard `_status.md` / `WORKPLAN.md` / `CHANGELOG.md` trio (this
  project previously had none — used `HANDOFF.md` + `android/STATUS.md` only).

## Next immediate task
- Battery-% calibration: current % is estimated from pack voltage (fields 14/18
  are constant 100, not the real %). Cross-check against the official app's
  displayed % at a known voltage to tune the SOC curve.
- Confirm the `<D{n}>` sleep-delay UNIT (blocks a settable inactivity-timeout
  control). Options logged: ask app values, or empirical undock-and-time test.
- Android: landscape layout; reactive Bluetooth-on state; true
  revert-on-command-failure (today timeout-based).

## Blockers
- Battery-% exactness needs an official-app cross-reference reading.
- `<D>` unit unknown → sleep-timeout control deferred.
- Each iron allows ONE BLE connection; undocked+OFF stops advertising (can't
  remote power-on from that state — hardware limit).

## Key decisions
- **Battery % is derived from voltage**, not read from the frame (proven: fields
  14 & 18 stay 100 at 7.37 V). App does the same.
- **`<M>`/`<L>` = master power** (charge-enable + heat-ready), not a heat toggle.
- **Toggles must be optimistic** (flip on tap, reconcile with real status) — a
  status-only toggle reads as unresponsive.
- Native Android app built on the **Mac** (`android/build.sh`, JBR 21); Windows
  runs the Python/web side. `android/STATUS.md` is the cross-machine channel.
