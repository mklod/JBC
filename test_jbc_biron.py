#!/usr/bin/env python3
"""Tests for the tip-reading filter, replaying a real live capture.

    python -m unittest test_jbc_biron -v

captures/2026-10-09-tipswap.tsv: both irons on the web dashboard; JBC_NANO
lifted, heated, cartridge pulled and re-seated (artifact frames 618, then
1551/1551/1185/822/465), heated back to its 350 °C setpoint, re-docked.
JBC_Std sat OFF at room temperature the whole time (26/27/28 flicker).
"""
# Last modified: 2026-10-09--0119
import csv
import os
import unittest

from jbc_biron import TipTrace, parse_status

CAPTURE = os.path.join(os.path.dirname(__file__), "captures", "2026-10-09-tipswap.tsv")


def replay(name):
    """[(t, raw tip, status, plotted value or None)] for one iron."""
    trace, out = TipTrace(), []
    with open(CAPTURE, encoding="utf-8") as fh:
        for t, _addr, nm, frame in csv.reader(fh, delimiter="\t"):
            if nm != name or not frame.startswith("E;"):
                continue
            st = parse_status(frame)
            out.append((float(t), st["current_c"], st["status"], trace.sample(float(t), st)))
    return out


def frame(tip, code=0, setpoint=350):
    return f"E;836;{tip};{code};19;{setpoint};4;48;47;1200;1199;254;0;0;100;0;5;450;100;"


class TipValidTest(unittest.TestCase):
    def test_ceiling_is_setpoint_plus_overshoot(self):
        self.assertTrue(parse_status(frame(360))["tip_valid"])
        self.assertFalse(parse_status(frame(361))["tip_valid"])
        self.assertFalse(parse_status(frame(465))["tip_valid"])     # live slew tail
        self.assertTrue(parse_status(frame(290, setpoint=280))["tip_valid"])
        self.assertFalse(parse_status(frame(300, setpoint=280))["tip_valid"])

    def test_no_cartridge_is_invalid(self):
        self.assertFalse(parse_status(frame(20, code=1))["tip_valid"])


class ReplayTest(unittest.TestCase):
    def test_swap_artifacts_never_plotted(self):
        rows = replay("JBC_NANO")
        plotted = [v for *_, v in rows if v is not None]
        self.assertTrue(plotted)
        self.assertLessEqual(max(plotted), 351)             # never above setpoint (+1 overshoot seen)
        for bad in (618, 1551, 1185, 822, 465):
            self.assertNotIn(bad, plotted)
        # Nothing plotted while the firmware reports NO CARTRIDGE.
        self.assertFalse([v for _, _, s, v in rows if s == "NO CARTRIDGE" and v is not None])

    def test_real_heatup_is_kept(self):
        plotted = {v for *_, v in replay("JBC_NANO") if v is not None}
        for real in (134, 153, 172, 191, 210, 229, 249, 269, 288, 307):   # post-swap heat-up
            self.assertIn(real, plotted)

    def test_idle_room_temp_plots_flat(self):
        rows = replay("JBC_Std")
        raw = {r for _, r, _, _ in rows}
        plotted = {v for *_, v in rows if v is not None}
        self.assertGreater(len(raw), 1)        # the raw reading really does flicker
        self.assertEqual(len(plotted), 1)      # ...but the graph is perfectly flat


if __name__ == "__main__":
    unittest.main()
