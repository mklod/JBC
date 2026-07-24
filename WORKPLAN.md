# WORKPLAN — JBC B·IRON independent BLE control

## Project summary
Control a JBC B·IRON battery soldering iron directly over Bluetooth LE, replacing
the buggy official `com.jbctools.jbcbiron` app. Protocol reverse-engineered from
the app (no firmware mod). Three surfaces: Python CLI/library, web dashboard,
native Android app.

## Tech stack
- **Protocol:** ASCII-over-GATT, no checksum. Service `2bbe5a4a-…`, write
  `2bbe5f90-…`, notify `2bbe5c8e-…`. Commands `<T{c}>` `<M>` `<L>` `<E>` etc.
- **Python:** `bleak` (WinRT/CoreBluetooth/BlueZ). `jbc_biron.py` + `dashboard.py`
  (stdlib HTTP, no extra deps).
- **Android:** Kotlin, Jetpack Compose, min SDK 28 / target 35. Built on Mac
  (Gradle 8.11.1, AGP 8.7.3, JBR 21) via `android/build.sh`.

## Key files
- `jbc_biron.py` — protocol library + CLI. `parse_status()` is the source of truth.
- `dashboard.py` — web app (localhost:8770).
- `android/` — native app; `Model.kt` mirrors `parse_status()`.
- `PROTOCOL.md` — GATT map, command table, status-frame field decode.
- `REVERSE-ENGINEERING.md` — how it was decoded (jadx + Android HCI snoop).
- `HANDOFF.md` / `_status.md` / `android/STATUS.md` — status & handoff logs.

## Stages
| # | Stage | Status |
|---|-------|--------|
| 1 | Decompile app, decode protocol (GATT, commands, framing) | ✅ COMPLETE |
| 2 | Python PoC controller (`jbc_biron.py`) | ✅ COMPLETE |
| 3 | Live-verify against real irons; correct field map (setpoint=f5) | ✅ COMPLETE |
| 4 | HCI-snoop capture → confirm setters (`<D>`/`<X>`/`<I>`) + field decode | ✅ COMPLETE |
| 5 | Web dashboard: multi-iron, live graph, toggle, ±10 setpoint modal | ✅ COMPLETE |
| 6 | Battery % from voltage; charging/discharging + sleep-state indicators | ✅ COMPLETE |
| 7 | Native Android app (Compose): scan-all, cards, controls | ✅ COMPLETE |
| 8 | Android parity: optimistic toggle, sleep pill, persistent cards | ✅ COMPLETE (Mac, 2026-07-23) |
| 8b | Android hardening: static-never-removed cards, scanner-driven reconnect + backoff (no flap), 2 Hz poll, offline/OFF badge fix | ✅ COMPLETE (Mac, 2026-07-24) |
| 9 | Battery-% calibration vs official app; confirm `<D>` unit | 🔜 TODO |
| 10 | Android landscape + reactive BT state + revert-on-write-fail | 🔜 TODO |
| 11 | Home Assistant integration (ESP32/ESPHome or custom component) | 🔜 PLANNED |

## Open questions
- Exact battery-% curve (needs official-app cross-reference).
- `<D{n}>` sleep-delay unit (seconds? minutes? ¼-s ticks?).
- Remaining status-frame fields 6/9/13/16 (need a WORK-state capture while soldering).
