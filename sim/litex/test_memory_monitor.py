#!/usr/bin/env python3
"""Unit tests for the passive Flow memory-path monitors."""

import contextlib
import io
import os
import sys
import unittest

from migen import Module
from migen.sim import run_simulation
from litex.soc.interconnect import wishbone


SIM_DIR = os.path.dirname(os.path.abspath(__file__))
if SIM_DIR not in sys.path:
    sys.path.insert(0, SIM_DIR)

from memory_monitor import FlowWishboneMonitor  # noqa: E402


class WishboneMonitorDut(Module):
    def __init__(self):
        self.bus = wishbone.Interface(
            data_width=64, address_width=32, addressing="word")
        self.submodules.monitor = FlowWishboneMonitor(
            self.bus, name="unit-mmio", max_events=4,
            address_start=0x13000000, address_end=0x13000100)


class FlowWishboneMonitorTest(unittest.TestCase):
    def test_records_request_and_response_without_driving_bus(self):
        dut = WishboneMonitorDut()

        def process():
            yield dut.bus.adr.eq(0x13000000 >> 3)
            yield dut.bus.sel.eq(0x20)
            yield dut.bus.we.eq(0)
            yield dut.bus.cyc.eq(1)
            yield dut.bus.stb.eq(1)
            yield
            self.assertEqual((yield dut.bus.cyc), 1)
            self.assertEqual((yield dut.bus.stb), 1)
            self.assertEqual((yield dut.bus.sel), 0x20)
            yield dut.bus.dat_r.eq(0x60 << 40)
            yield dut.bus.ack.eq(1)
            yield
            yield dut.bus.cyc.eq(0)
            yield dut.bus.stb.eq(0)
            yield dut.bus.ack.eq(0)
            yield

        captured = io.StringIO()
        with contextlib.redirect_stdout(captured):
            run_simulation(dut, process())
        output = captured.getvalue()
        self.assertIn("[WB-REQ] name=unit-mmio", output)
        self.assertIn("addr=0x13000000", output)
        self.assertIn("sel=0x20", output)
        self.assertIn("[WB-RSP] name=unit-mmio", output)
        self.assertIn("dat_r=0x600000000000", output)


if __name__ == "__main__":
    unittest.main()
