#!/usr/bin/env python3
"""Directed unit test for one complete Breeze PLIC interrupt transaction."""

import os
import sys
import unittest

from migen.sim import run_simulation


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
WRAPPER_ROOT = os.path.join(FLOW_ROOT, "litex_wrapper")
if WRAPPER_ROOT not in sys.path:
    sys.path.insert(0, WRAPPER_ROOT)

from flow.plic import BreezePlic  # noqa: E402


class BreezePlicInterruptPathTest(unittest.TestCase):
    def test_source_10_reaches_machine_context_and_completes(self):
        dut = BreezePlic(num_harts=1, num_sources=31)
        observed = {}

        def write32(address, value):
            high_lane = bool(address & 4)
            yield dut.bus.adr.eq(address // 8)
            yield dut.bus.dat_w.eq(value * (2 ** 32) if high_lane else value)
            yield dut.bus.sel.eq(0xf0 if high_lane else 0x0f)
            yield dut.bus.we.eq(1)
            yield dut.bus.cyc.eq(1)
            yield dut.bus.stb.eq(1)
            while not (yield dut.bus.ack):
                yield
            yield dut.bus.cyc.eq(0)
            yield dut.bus.stb.eq(0)
            yield dut.bus.we.eq(0)
            yield

        def read32(address):
            high_lane = bool(address & 4)
            yield dut.bus.adr.eq(address // 8)
            yield dut.bus.sel.eq(0xf0 if high_lane else 0x0f)
            yield dut.bus.we.eq(0)
            yield dut.bus.cyc.eq(1)
            yield dut.bus.stb.eq(1)
            while not (yield dut.bus.ack):
                yield
            data = yield dut.bus.dat_r
            yield dut.bus.cyc.eq(0)
            yield dut.bus.stb.eq(0)
            yield
            return (data >> 32) & 0xffffffff if high_lane else data & 0xffffffff

        def stimulus():
            yield from write32(0x000028, 1)
            yield from write32(0x002000, 1 << 10)
            yield from write32(0x200000, 0)
            yield dut.sources.eq(1 << 9)
            yield
            yield

            observed["pending"] = yield from read32(0x001000)
            observed["meip_before_claim"] = yield dut.meip[0]
            observed["claim"] = yield from read32(0x200004)
            observed["meip_after_claim"] = yield dut.meip[0]

            yield dut.sources.eq(0)
            yield from write32(0x200004, 10)

        run_simulation(dut, stimulus())
        self.assertEqual(observed["pending"] & (1 << 10), 1 << 10)
        self.assertEqual(observed["meip_before_claim"], 1)
        self.assertEqual(observed["claim"], 10)
        self.assertEqual(observed["meip_after_claim"], 0)


if __name__ == "__main__":
    unittest.main()
