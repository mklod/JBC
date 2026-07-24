#!/usr/bin/env python3
"""
Minimal JBC B·IRON BLE controller — proof of concept.

Speaks the reverse-engineered ASCII-over-GATT protocol (see PROTOCOL.md).
Cross-platform via bleak (macOS/Linux/Windows).

    pip install bleak
    python jbc_biron.py scan
    python jbc_biron.py monitor
    python jbc_biron.py set-temp 340
    python jbc_biron.py on
    python jbc_biron.py off
"""
import asyncio
import sys

from bleak import BleakClient, BleakScanner

SERVICE = "2bbe5a4a-b9df-11ea-b3de-0242ac130004"
WRITE_CHAR = "2bbe5f90-b9df-11ea-b3de-0242ac130004"
NOTIFY_CHAR = "2bbe5c8e-b9df-11ea-b3de-0242ac130004"

NAME_PREFIX = "JBC_"

STATUS = {
    0: "WORK", 1: "NO CARTRIDGE", 2: "CHARGE", 3: "LOW BATTERY", 4: "WORK",
    5: "HIBERNATION", 6: "SHORT CIRCUIT", 7: "SLEEP", 8: "OFF", 9: "OFF",
    10: "COVER",
}


async def find_all_irons(timeout=6.0):
    """Return every advertised JBC handle as a list of (device, name)."""
    devices = await BleakScanner.discover(timeout=timeout, return_adv=True)
    out = []
    for dev, adv in devices.values():
        name = adv.local_name or dev.name or ""
        if name.startswith(NAME_PREFIX) or SERVICE in (adv.service_uuids or []):
            out.append((dev, name or dev.address))
    return out


async def find_iron():
    """Return the first advertised JBC handle, or None."""
    irons = await find_all_irons()
    return irons[0] if irons else (None, None)


# 2S Li-ion resting-voltage → state-of-charge curve (per-cell volts → %).
# Battery % is NOT a field in the <E> frame (fields 14/18 are constant 100);
# the official app derives it from pack voltage, so we do the same.
_SOC_CURVE = [
    (3.30, 0), (3.45, 5), (3.55, 12), (3.65, 22), (3.72, 35),
    (3.78, 48), (3.85, 62), (3.95, 76), (4.05, 89), (4.20, 100),
]


def battery_pct_from_voltage(pack_v):
    """Estimate battery % from 2S pack voltage. None if unknown."""
    if not pack_v:
        return None
    cell = pack_v / 2.0
    if cell <= _SOC_CURVE[0][0]:
        return 0
    if cell >= _SOC_CURVE[-1][0]:
        return 100
    for (v0, p0), (v1, p1) in zip(_SOC_CURVE, _SOC_CURVE[1:]):
        if v0 <= cell <= v1:
            return round(p0 + (p1 - p0) * (cell - v0) / (v1 - v0))
    return 100


def parse_status(text):
    f = text.split(";")
    if not f or f[0] != "E":
        return None

    def num(i):
        try:
            return int(f[i])
        except (IndexError, ValueError):
            return None

    # Live-verified mapping (see PROTOCOL.md): field 5 is the setpoint
    # (moved by <T…>), field 2 is tip temperature, field 1 is battery
    # voltage in centivolts (8.38 V docked/full).
    batt = num(1)
    return {
        "current_c": num(2),
        "setpoint_c": num(5),
        "max_c": num(17),          # confirmed via <I450> (HCI capture)
        "sleep_delay": num(7),     # confirmed via <D20>/<D48> (HCI capture)
        "battery_v": batt / 100 if batt is not None else None,
        # Derived from voltage: fields 14 & 18 stayed 100 even at 7.37 V, so
        # they are NOT the percentage — the app computes it from voltage.
        "battery_pct": battery_pct_from_voltage(batt / 100 if batt is not None else None),
        "countdown_s": (num(10) or 0) // 4,
        "status": STATUS.get(num(3), f"?{f[3] if len(f) > 3 else ''}"),
        "locked": len(f) > 12 and f[12] == "1",
    }


class Iron:
    def __init__(self, client):
        self.client = client
        self.last = None

    def _on_notify(self, _char, data: bytearray):
        text = data.decode("utf-8", "replace").strip()
        if text.startswith("E"):
            self.last = parse_status(text)
        else:
            print(f"  <- {text!r}")

    async def start(self):
        await self.client.start_notify(NOTIFY_CHAR, self._on_notify)

    async def send(self, frame: str):
        await self.client.write_gatt_char(WRITE_CHAR, frame.encode(), response=True)

    # high-level helpers -------------------------------------------------
    async def poll(self):
        await self.send("<E>")

    async def set_temp(self, c: int):
        assert 100 <= c <= 450, "work range is 100-450 C"
        await self.send(f"<T{c}>")

    async def set_max_temp(self, c: int):
        assert 100 <= c <= 450, "work range is 100-450 C"
        await self.send(f"<I{c}>")   # confirmed via HCI: moves status field 17

    async def set_min_temp(self, c: int):
        assert 100 <= c <= 450, "work range is 100-450 C"
        await self.send(f"<X{c}>")   # accepted, but not reflected in the <E> frame

    async def set_sleep_delay(self, n: int):
        await self.send(f"<D{n}>")   # confirmed via HCI: moves status fields 7/8

    async def power_on(self):
        await self.send("<M>")

    async def power_off(self):
        await self.send("<L>")

    async def unlock(self, pin: str):
        await self.send(f"<P{pin}>")

    async def get_version(self):
        await self.send("<V>")


async def with_iron(coro):
    dev, name = await find_iron()
    if not dev:
        print("No JBC B·IRON handle found. Wake the iron and retry.")
        return
    print(f"Connecting to {name} ({dev.address}) ...")
    async with BleakClient(dev) as client:
        iron = Iron(client)
        await iron.start()
        await coro(iron)


async def cmd_scan(_):
    dev, name = await find_iron()
    print(f"Found {name} ({dev.address})" if dev else "Nothing found.")


async def cmd_monitor(iron):
    print("Polling status every 250 ms. Ctrl-C to stop.")
    try:
        while True:
            await iron.poll()
            await asyncio.sleep(0.25)
            if iron.last:
                s = iron.last
                lock = " [LOCKED]" if s["locked"] else ""
                print(f"  {s['status']:<12} {s['current_c']}C -> {s['setpoint_c']}C"
                      f"  batt {s['battery_v']}V{lock}")
    except asyncio.CancelledError:
        pass


async def main():
    args = sys.argv[1:]
    cmd = args[0] if args else "monitor"

    if cmd == "scan":
        await cmd_scan(None)
    elif cmd == "monitor":
        await with_iron(cmd_monitor)
    elif cmd == "set-temp":
        await with_iron(lambda i: run_and_report(i, i.set_temp(int(args[1]))))
    elif cmd == "on":
        await with_iron(lambda i: run_and_report(i, i.power_on()))
    elif cmd == "off":
        await with_iron(lambda i: run_and_report(i, i.power_off()))
    elif cmd == "unlock":
        await with_iron(lambda i: run_and_report(i, i.unlock(args[1])))
    elif cmd == "version":
        await with_iron(lambda i: run_and_report(i, i.get_version()))
    else:
        print(__doc__)


async def run_and_report(iron, action_coro):
    await action_coro
    await asyncio.sleep(0.4)  # let the reply notify land
    await iron.poll()
    await asyncio.sleep(0.4)
    print(iron.last)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
