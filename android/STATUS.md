# Android app — status & review log

Newest first. Cross-session handoff (Windows CC ⇄ Mac mini CC). The Mac mini has
the build toolchain (`build.sh`); the Windows box has the irons + web dashboard.

---

## 2026-07-27 — Mac CC: scan-lifecycle fix — screen-off bug FIXED + verified

Fixed the scanner-dies-on-screen-off bug from the Windows entry below. Built,
installed, and verified on the Moto X4 over adb.

- **Lifecycle-aware scan (`MainActivity`).** Scan start/stop moved to
  `onStart`/`onStop`. Screen-on / foreground re-arms `ble.start()`; screen-off /
  background calls `ble.stop()` (stops scan + closes links — also spares the iron
  packs). Permission / BT-enable gate scanning via a `permsReady` flag; `onStart`
  is the single entry point that (re)starts scanning.
- **ScanFilter (`Ble.kt`).** `start()` is now idempotent and uses a **filtered**
  scan: service-UUID + one filter per known address (from prefs). A fresh install
  with no known irons falls back to an unfiltered scan so a new handle is still
  discovered by name. Filtered scans are also less aggressively suspended.
- **Reconnect known-by-address.** `onScanResult` reconnects a known iron by
  address even when a filtered result carries no name (the old name-gate could
  skip it); the name check now applies only to brand-new devices.

Verified live: launch → both connect; **screen OFF → both drop (`reason=22`
local close via `onStop`); screen ON → `onStart` re-scans → both reconnect in
~5 s** and the app is usable again — the "turn screen on, want it to work" flow.

---

## 2026-07-24 (afternoon) — Windows CC: BUG — scan dies on screen-off, no resume

**Symptom (user):** came back to desk, turned the phone screen on → **both cards
"offline", toggle won't respond.**

**Diagnosed live over adb (Moto X4 attached to the Windows box):**
- App process alive (not killed), screen Awake, Doze ACTIVE. App is **NOT** in the
  Doze battery-optimization whitelist.
- Both irons advertising **strongly right next to the phone** (Windows scan: Std
  −36 dBm, NANO −29 dBm) → **not** an iron-side / signal / deep-sleep problem.
- App produced **zero scan/connect activity** while both irons advertised → the
  app's BLE **scanner is dead** and never restarts.
- `am force-stop` + relaunch → both reconnect in ~seconds (verified by screenshot).
  So it's purely the scanner not resuming; connect/parse path is fine.

**Root cause:** `BleManager.start()` calls `startScan()` **once in onCreate** with
**no ScanFilter**. Android suspends *unfiltered* BLE scans when the screen turns
off (documented since 8.1) and does **not** auto-resume on screen-on. With the
screen off the app was also Doze-frozen. Net: scan stops, never restarts →
persistent cards stay "offline" → `AppleToggle(enabled = iron.connected)` is
**disabled while offline**, which is exactly the "won't toggle on" report.

**Immediate workaround (told the user):** reopen the app (swipe away + relaunch)
→ fresh scan → both reconnect in a few seconds. (I already force-restarted it via
adb this session, so it's live again now.)

**Proper fix — for the next Mac build (priority):**
1. **Lifecycle-aware scan.** Move scan start/stop into `onStart`/`onStop` (or a
   `DefaultLifecycleObserver`) so screen-on / app-foreground **re-triggers the
   scan**. This is the key fix — matches the usage ("turn screen on, want it to
   work"). Stopping on background also saves the irons' battery (no 24/7 links).
2. **Add a ScanFilter** (service UUID `2bbe5a4a-…` or name `JBC_`). Filtered scans
   are handled far better than unfiltered ones and are less aggressively suspended.
3. **Reconnect persistent-but-dead conns.** Ensure `onScanResult` reconnects a
   conn whose GATT is dead/disconnected — don't early-return just because the addr
   is still in the (never-removed) map.
4. Optional (only if background/screen-off control is wanted, at a battery cost):
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and/or a foreground service. NOT
   recommended by default — keeping two BLE links alive drains the iron packs.

---

## 2026-07-23 (evening) — Mac CC: persistent static cards + autoConnect

User: "both cards ALWAYS visible, static, never a single card, ever." The
vanishing card was **connection flapping** — GATT log showed reason=8 (timeout) +
reason=62 (fail-to-establish) churn on BOTH handles with the phone inches away
(so not signal strength). Two changes in `Ble.kt`:

- **Persistent cards.** Known irons are saved to SharedPreferences
  (`jbc_known_irons`, `addr\tname`). On launch the manager pre-creates a card for
  every known iron (offline) *before* scanning, and cards are **never removed** —
  a dropped iron keeps its last-known values with the grey "offline" pill instead
  of disappearing. Verified: prefs hold NANO + Std; force-stop→relaunch shows both
  immediately.
- **Scanner-driven connect + backoff.** First tried `autoConnect=true`, but its
  initial connect was too slow (cards sat "offline" ~20 s post-reboot). Final
  approach: keep `autoConnect=false` (fast) but connect a handle **only when the
  scanner sees it advertising**, guarded by a **3 s per-iron backoff**; on
  disconnect we close the gatt and let the next advertisement reconnect. Fast
  *and* storm-proof. Verified post-reboot: cold launch → both cards connect in
  ~3–7 s (one `reason=62` retry, then solid; zero disconnects after).
  Contention was also removed — the official app was uninstalled from the phone
  (below), so the single per-iron slot is ours.
- Poll **250→500 ms (4→2 Hz)** to halve radio load with two simultaneous links.

Contention removed: the **official app `com.jbctools.jbcbiron` was uninstalled
from the Moto X4** (2026-07-23) and the phone rebooted, clearing its 2 stale
GATT-client registrations (now 0). The single per-iron connection slot is ours.

---

## 2026-07-23 — Mac CC: optimistic toggle + sleep pill landed

Implemented the two priority gaps from the Windows review; built + installed +
screenshot-verified on the Moto X4 (both cards render, pills correct).

- **Optimistic toggle (done).** `IronCard` holds `pendingOn: Boolean?`; the knob
  shows `pendingOn ?: realOn` so it flips instantly on tap. A
  `LaunchedEffect(pendingOn, realOn)` clears it when real status catches up, or
  after a 2.5 s timeout → reverts to real (covers a failed/ignored command).
  Cards wrapped in `key(iron.id)` so pending state tracks the iron, not position.
- **Sleep-state pill (done).** `StatePill` + `pillFor(iron)` in `Screen.kt`, in the
  header next to the name: working/charging (green), asleep/hibernating (amber —
  new `C.amber`), off (grey), offline (grey when disconnected), low-batt/short
  (red). Verified live: Nano `off`, Std `offline`.
- **Bonus:** the `offline` pill + red dot doubles as the connection-lost indicator
  the reviewer noted (shows while disconnected, before the scan list drops it).

Field note (BLE, not code): the Nano vanishing from the phone was a real
disconnect — GATT log had `ea:be:3e:e6:32:90` DISCONNECTED **reason=8**
(GATT_CONN_TIMEOUT), then it stopped advertising (undocked+OFF / deep sleep). The
app re-scans every 10 s and re-grabs it on next advertisement — no code fix.

Still open: landscape; reactive Bluetooth-on state; firmware/version line;
sleep-delay & max-temp controls; true revert-on-command-failure (today it's
timeout-based, not plumbed back from the write result).

---

## 2026-07-22 — code review from Windows CC session

Reviewed the whole app (Ble.kt, Model.kt, Screen.kt, MainActivity.kt, manifest)
against the live-verified protocol and the current web `dashboard.py`. **Not run
here** — no Android device attached to the Windows box, and `build.sh` targets
the Mac (JBR/gradle paths). Review is static + protocol cross-check only.

### Verified correct (no action needed)
- BLE: service/write/notify/CCCD UUIDs, `JBC_` name scan, connect-to-all,
  4 Hz `<E>` poll, per-connection write **mutex** (right call for Android's
  single-GATT-op rule). Commands `<T>`/`<M>`/`<L>` correct; setpoint clamped.
- `Model.kt parseStatus` is an exact port of the corrected Python: current=f2,
  setpoint=f5, max=f17, sleep=f7, voltage=f1/100, battery% from the shared SOC
  curve, STATUS map + lock flag (f12) all match `../PROTOCOL.md`.
- Permissions per SDK (28→FINE_LOCATION; 31+→SCAN/CONNECT `neverForLocation`);
  connections released in `onDestroy`. Manifest maxSdkVersion gating correct.
- Charging/discharging badge present on the battery tile.

### TODO — parity gaps vs current web dashboard.py (in priority order)

1. **Optimistic toggle (UX bug — same one just fixed on web).**
   `Screen.kt` `AppleToggle(checked = on)` is driven only by real status, so a
   tap fires the command but the knob doesn't move until the iron reports back
   (~0.5–2 s) → feels unresponsive. Fix: flip the visual immediately on tap,
   then reconcile with real status, and revert on command failure.
   - Web reference (`dashboard.py`, toggle click handler): optimistic
     `classList.toggle('checked', !on)` + a `pending[id]` timestamp so the
     poll doesn't override for ~2.5 s + `revert()` on `!ok`.
   - Compose approach: hold a per-iron `pendingOn: Boolean?` + timestamp in
     the card; show `pendingOn ?: s.isOn`; clear it when real status matches or
     after a timeout; on command failure clear immediately (reverts to real).

2. **Sleep-state pill (missing feature).** Web shows a color-coded pill by the
   name: `working` (green) / `asleep` (amber, status SLEEP) /
   `hibernating` (amber, HIBERNATION) / `charging` (green) / `off` (grey) /
   `offline` when disconnected. This is the inactivity-sleep indicator the user
   asked for. Add it to the `IronCard` header row next to the name.

### Minor
- `MainActivity`: `bluetoothOn = ble.bluetoothEnabled` is read once, not
  reactive — toggling BT mid-session won't update the UI. Consider a
  BroadcastReceiver on `ACTION_STATE_CHANGED` or re-check on resume.
- Portrait-only (known TODO); landscape still pending.

### Protocol notes learned this session (already in ../PROTOCOL.md)
- Battery % is NOT a frame field — fields 14 & 18 stay `100` even at 7.37 V.
  Derived from pack voltage (field 1); app does the same. Curve is an estimate
  (reads high on charge, sags under load). Calibrate against the official app's
  displayed % at a known voltage if exactness is wanted.
- `<M>`/`<L>` = master power (charge-enable + heat-ready), not a heat toggle.
  Undocked+OFF stops BLE advertising (can't remote-on from there).
- Sleep-delay `<D{n}>` decoded (fields 7/8) but its UNIT is unconfirmed — a
  settable timeout control is intentionally deferred until the unit is known.
