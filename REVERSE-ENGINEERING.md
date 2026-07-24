# How the JBC B·IRON protocol was reverse-engineered

Reproducible notes so this can be re-derived or checked against a newer app.

## Source

Official Android app `com.jbctools.jbcbiron` **v2.0.2** (versionCode 13),
developer *Infinity Source* (package root `es.infinitysource.jbc_soldering`).

The v2.0.2 APK is linked only from the JBC B·IRON product page:
`https://www.jbctools.com/software/BIRON/JBCBiron-v2.0.2.apk`
(the main `software.html` page lists firmware only; APK mirrors stop at v1.7).

## Tooling

```bash
# decompile to readable Java
brew install jadx
jadx -d jadx-out --no-res JBCBiron-v2.0.2.apk

# (alt, pure-python, no Java) class/string analysis
pip install androguard
```

Fast first pass without decompiling — pull UUIDs and BLE symbols straight from
the dex:

```bash
unzip -o JBCBiron-v2.0.2.apk -d extracted
strings extracted/*.dex | grep -oiE '[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}' | sort -u
strings extracted/*.dex | grep -iE 'gatt|characteristic|writeCharacteristic|CHARACTERISTIC_UUID'
```

## Key files in the decompiled tree

- `utils/BLEManager.java` — GATT setup, all command builders, `sendCommand()`.
- `domain/mapper/DeviceMapperKt.java` — parses the `;`-delimited replies into a
  `DeviceStatusModel` (gives the status-frame field order).
- `Constants.java` — status enum, temperature limits (`MIN 100`, `MAX 450`),
  device model ids (`B_100/B_500/B_NANO/B_TWEEZER`).

## What the code showed

1. **GATT UUIDs** are hard-coded in `BLEManager`'s static init:
   - Service `2bbe5a4a-b9df-11ea-b3de-0242ac130004`
   - Write char `2bbe5f90-…`, Notify char `2bbe5c8e-…`, CCCD `00002902-…`.
2. **`sendCommand()`** does `characteristic.setValue(command.getBytes(UTF_8))`
   then `writeCharacteristic()` — i.e. commands are literal ASCII strings, no
   binary framing/checksum.
3. Each high-level method builds a tiny string, e.g.
   `setCurrentTemperature(t)` → `"<T" + t + ">"`, expecting a reply starting
   `S`. `getStatus()` writes the raw bytes `{60,69,62}` = `<E>` every 250 ms.
   (Full table in `PROTOCOL.md`.)
4. **Replies** are matched to commands by expected leading letter; the status
   reply is split on `;` and mapped positionally (index 1 = current temp,
   2 = setpoint, 3 = status code, 12 = lock flag, …).

## Confidence

High for the command set and GATT layout (read directly from source, not
guessed). The status-frame indices 4–11 and 14/16/18 are secondary
counters/temps not individually confirmed — the important ones (current temp,
setpoint, status, lock) are verified. Live capture (or running the PoC's
`monitor`) will confirm the remaining fields.

## Cross-check idea (ground truth)

Enable Bluetooth HCI snoop on an Android phone where the app works (Pixel 7),
drive the iron with the official app, pull the btsnoop log via `adb bugreport`,
and confirm the `<…>` frames and `;`-replies match this spec byte-for-byte.
