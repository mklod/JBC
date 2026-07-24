# JBC B·IRON BLE Protocol

Reverse-engineered from the official app `com.jbctools.jbcbiron` v2.0.2
(developer: Infinity Source). Source of truth: `utils/BLEManager.java`,
`domain/mapper/DeviceMapperKt.java`, `Constants.java`.

## GATT layout

| Role | UUID |
|------|------|
| Service | `2bbe5a4a-b9df-11ea-b3de-0242ac130004` |
| Write characteristic (phone → iron) | `2bbe5f90-b9df-11ea-b3de-0242ac130004` |
| Notify characteristic (iron → phone) | `2bbe5c8e-b9df-11ea-b3de-0242ac130004` |
| CCCD (enable notifications) | `00002902-0000-1000-8000-00805f9b34fb` → write `0x0100` |

Advertised device name is prefixed `JBC_` (the app strips that prefix for display).

## Framing

Dead simple. **No checksum, no length byte, no sequence number.**

- A command is an ASCII string `<` + letter + optional argument + `>`, written
  to the write characteristic as UTF-8 bytes (write *with* response).
- The iron replies on the notify characteristic. Replies are ASCII, fields
  separated by `;`. The first field echoes a letter identifying the reply type.
- The app matches a reply to its command by the expected leading letter
  (e.g. command `<T340>` expects a reply beginning `S`).

## Command table

Temperatures are integers in °C. Valid work range **100–450 °C**.

| Action | Frame sent | Reply prefix |
|--------|-----------|--------------|
| Poll status | `<E>` | `E;…` (see below) |
| Get location/handle id | `<U>` | `U;…` |
| Get firmware version | `<V>` | `V;…` |
| Get model | `<W>` | `W;…` |
| Get counters | `<C>` | `C;…` |
| Get temp-adjust (offset) | `<m>` | `m;<±int>;` |
| Set working temperature | `<T{°C}>` | `S` |
| Set sleep temperature | `<G{°C}>` | `G` |
| Set min temperature | `<X{°C}>` | `G` |
| Set max temperature | `<I{°C}>` | `G` |
| Set temp-adjust (offset) | `<n{°C}>` | `S` |
| Sleep mode on / off | `<F1>` / `<F0>` | `F` |
| Set sleep delay | `<D{seconds×4}>` | `D` |
| Power on | `<M>` | `S` |
| Power off | `<L>` | `S` |
| Rename handle | `<N{name}>` | `S` |
| Verify PIN (pre-lock) | `<Q{pin}>` | `Q` |
| Lock parameters | `<A{pin}>` | `S` or `A` |
| Unlock parameters | `<P{pin}>` | `S` (reply `A` = rejected/cancel) |

Notes:
- **Sleep delay** is sent as `seconds × 4` (device unit is ¼-second ticks).
- The app polls `<E>` every 250 ms to refresh the live screen.
- There is no factory PIN baked into the app; the lock is whatever the user set.
  `<P{pin}>` unlocks; a reply starting `A` means rejected. The station is
  reported to hand out an 8-digit recovery challenge after 3 wrong front-panel
  tries — unclear whether the BLE path rate-limits, so avoid blind brute force.

## Status reply `<E>` → `E;f1;f2;f3;…`

Split the reply on `;`. **Live-verified 2026-07-21** against a real B·IRON Nano
(docked/charging, heater off). Reference frame captured:

```
E;838;30;2;0;350;4;40;40;1200;1200;252;0;0;100;0;5;400;100;
```

The original static decode (field 1 = current temp, field 2 = setpoint) is
**wrong** — a live `<T340>` write moved **field 5** from 350 to 340 (with `S;`
ack), pinning the setpoint there.

A second capture the same day with the iron **undocked, in HIBERNATION (code
5), after a brief heat burst from being woken** pinned down more fields:

```
E;815;30;5;0;350;4;40;0;1200;1064;253;0;0;100;0;5;400;100;   (just undocked)
E;810;52;5;0;350;4;40;0;1200;828;253;0;0;100;0;5;400;100;    (60 s later)
```

| Index | Live values | Meaning | Confidence |
|-------|------------|---------|------------|
| 1 | `838` docked → `815…810` undocked | **Battery voltage, centivolts** (8.38 V = full 2S Li-ion on charger; sags to ~8.1 V off it). | strong |
| 2 | `30` → spiked to `145` on wake → decayed to `52` | **Current tip temperature (°C)** — rose ~30 °C/300 ms while heating, then classic exponential cool-down. | **confirmed live** |
| 3 | `2` docked, `5` woken-then-idle, `8` after `<L>` | Status code (see enum) — CHARGE / HIBERNATION / OFF all observed as expected. | **confirmed live** |
| 4 | `0` | Live counter (took 90 values 0…89+ across the run) — likely an uptime/poll tick, not config | live counter |
| 5 | `350` | **Working setpoint (°C)** — moved by `<T340>`. Reads `390` in undocked-idle state (code 9) vs `350` docked (code 2) — the displayed setpoint depends on mode. | **confirmed live** |
| 6 | `4` | ? | unknown |
| 7 | `40`→`20`→`48` | **Sleep delay setting** — moved by `<D20>` then `<D48>` (field mirrors the command arg exactly). | **confirmed live (HCI)** |
| 8 | `40`→`20`→`48` | **Sleep delay, mirror of field 7** (moved identically with `<D…>`). | **confirmed live (HCI)** |
| 9 | `1200` | Constant reset value (did NOT change with `<D…>`, so NOT the sleep delay). Some other timer's max. | strong |
| 10 | `1200` docked; counts down ~4/s undocked | **Live countdown timer, ¼-s ticks** — independent of the sleep-delay setting (fields 7/8). Purpose (keepalive? auto-off?) unconfirmed. | confirmed ticking |
| 11 | `252–262` | Handle/battery temperature, tenths of °C (~25–26 °C, tracks ambient/warmth) | hypothesis |
| 12 | `0` | Password/lock flag (`1` = locked) per app decode; read `0` and temp writes were accepted — consistent. | consistent, unconfirmed |
| 13 | `0` | Boolean flag (`1`/`0`) | unknown |
| 14 | `100` | **Constant `100`** — stayed 100 even at 7.37 V (low battery). NOT battery %, NOT min-temp. | ruled out |
| 15 | `0` | Status-light indicator (`1` = multicolor) per app decode | unconfirmed |
| 16 | `5` | ? | unknown |
| 17 | `400`→`450` | **Maximum temperature (°C)** — moved by `<I450>`. | **confirmed live (HCI)** |
| 18 | `100` | **Constant `100`** — also stayed 100 at 7.37 V. NOT battery %. | ruled out |

**Battery % is not in the frame.** At a full charge and at 7.37 V both fields 14
and 18 read `100`, and no other field tracks charge — so the percentage is
*derived from pack voltage* (field 1), which is what the official app does.
`battery_pct_from_voltage()` in `jbc_biron.py` applies a 2S Li-ion resting-voltage
curve (8.4 V ≈ 100 %, 7.4 V ≈ 30 %, 6.6 V ≈ 0 %). It's an estimate — voltage
reads high under charge and sags under load — so treat field 1 (voltage) as the
authoritative reading and the % as an indicator. Calibrate the curve against the
official app's displayed % at a known voltage if exactness is needed.

**Min temperature is not in the `<E>` frame.** Sending `<X150>` (min = 150 °C)
produced no field change anywhere in the status reply — min-temp is either
read-back only via its own query or simply not surfaced. Max-temp *is* mirrored
(field 17), so the two limits are handled asymmetrically.

Remaining unknowns (fields 6, 9, 13, 16 and the constants 14/18): need a WORK-state
capture while actively soldering, plus toggling temp-offset and the sleep
temperature in the app.

### Command encodings confirmed from live app capture (2026-07-21)

An Android HCI-snoop capture of the official app driving this iron confirmed the
on-wire command bytes (all ASCII, written to the write char):

| App action | Frame on wire | Effect in `<E>` frame |
|-----------|---------------|----------------------|
| Set sleep delay | `<D20>`, `<D48>` | fields 7 & 8 ← arg |
| Set min temp | `<X150>` | (none — not surfaced) |
| Set max temp | `<I450>` | field 17 ← arg |
| Get model / offset / version | `<W>` / `<m>` / `<V>` | separate `W;`/`m;`/`V;` replies |
| Power on | `<M>` | status code changes |

Capture method: enable "Bluetooth HCI snoop log" on the Android phone running the
app, drive the app, then `adb bugreport` (bundles `btsnoop_hci.log` on non-rooted
devices) and dissect with tshark filtering `btatt.handle` for the JBC write
(commands `3c…3e`) and notify (`E;…`) characteristics.

### Power commands — `<M>` / `<L>` are the MASTER power state

`<M>` and `<L>` are the app's on/off switch — a **master enable**, not a heat
toggle. Confirmed by the device owner's observed behaviour:

- **ON** (`<M>`, ack `S;`): iron enabled. In the cradle it **charges** (status
  `2` CHARGE, cradle LEDs blink); removed and used, it **heats** to setpoint.
- **OFF** (`<L>`, ack `S;`): iron disabled. In the cradle it does **not charge**
  (LEDs dark, status `8`/`9` OFF); removed, it will **not heat**.

So OFF gates *both* charging and heat-readiness. (Earlier notes that "`<M>`
doesn't heat in the cradle" were a misread — the tip didn't heat because the
iron was OFF, not because it was docked.)

BLE-connection nuance:
- **Docked**, you can toggle ON/OFF freely over BLE and the connection persists
  (cradle keeps the radio powered) — verified live via `dashboard.py`.
- **Undocked + OFF** drops into deep sleep and **stops BLE advertising**, so you
  cannot turn it back on remotely from that state — re-dock or physically wake.

`<V>` live reply: `V;8886928 ;` (firmware/serial, note trailing space).

Timing note: back-to-back writes ~0.6 s apart can drop the second command
(second `<T…>` was silently ignored once). Space writes ≥1 s apart or wait for
the ack before the next write.

### Status codes (field 3)

| Code | Meaning |
|------|---------|
| 0 | WORK |
| 1 | NO CARTRIDGE |
| 2 | CHARGE |
| 3 | LOW BATTERY |
| 4 | WORK |
| 5 | HIBERNATION |
| 6 | SHORT CIRCUIT |
| 7 | SLEEP |
| 8 | OFF |
| 9 | OFF |
| 10 | COVER |

## Known device models (`Constants$Devices`)

`B_100`, `B_500`, `B_NANO`, `B_TWEEZER` — the four B·IRON handle types.
