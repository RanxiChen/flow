#!/usr/bin/env python3
"""Static integration contract for the standalone FlowPlic RTL."""

import os
import unittest


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def read_text(relative_path):
    with open(os.path.join(FLOW_ROOT, relative_path), encoding="utf-8") as handle:
        return handle.read()


class FlowPlicVerilogContractTest(unittest.TestCase):
    def test_linux_profile_selects_verilog_plic_and_keeps_legacy_source(self):
        sim = read_text("sim/litex/multicore_sim.py")
        wrapper = read_text("litex_wrapper/flow/plic_verilog.py")
        self.assertIn('if privilege_profile == "linux":', sim)
        self.assertIn("BreezePlicVerilog", sim)
        self.assertIn("BreezePlic(", sim)
        self.assertIn('Instance(\n            "FlowPlic"', wrapper)
        self.assertTrue(os.path.isfile(os.path.join(
            FLOW_ROOT, "litex_wrapper", "flow", "plic.py")))

    def test_rtl_localizes_global_litex_address_and_exports_debug_state(self):
        rtl = read_text("litex_wrapper/flow/rtl/FlowPlic.sv")
        self.assertIn("wb_adr & 32'h007f_ffff", rtl)
        self.assertIn("output wire [NUM_SOURCES:0]", rtl)
        self.assertIn("debug_priorities", rtl)
        self.assertIn("debug_enables", rtl)
        self.assertIn("debug_thresholds", rtl)
        self.assertNotIn("migen.fhdl", rtl)

    def test_directed_test_uses_global_source10_addresses(self):
        testbench = read_text("sim/rtl/flow_plic_tb.sv")
        runner = read_text("sim/rtl/run_flow_plic_test.sh")
        self.assertIn("PLIC_BASE + 32'h0000_0028", testbench)
        self.assertIn("PLIC_BASE + 32'h0000_2000", testbench)
        self.assertIn("PLIC_BASE + 32'h0020_0004", testbench)
        self.assertIn("[FLOW-PLIC-PASS]", testbench)
        self.assertIn("--top-module flow_plic_tb", runner)


if __name__ == "__main__":
    unittest.main()
