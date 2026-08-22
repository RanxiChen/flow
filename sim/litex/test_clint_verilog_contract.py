#!/usr/bin/env python3
"""Static integration contract for the standalone FlowClint RTL."""

import json
import os
import unittest


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def read_text(relative_path):
    with open(os.path.join(FLOW_ROOT, relative_path), encoding="utf-8") as handle:
        return handle.read()


class FlowClintVerilogContractTest(unittest.TestCase):
    def test_linux_clock_and_timebase_contract_stays_synchronized(self):
        linux_sim = read_text("sim/litex/linux_sim.py")
        multicore_sim = read_text("sim/litex/multicore_sim.py")
        rtl = read_text("litex_wrapper/flow/rtl/FlowClint.sv")
        with open(os.path.join(FLOW_ROOT, "config", "breeze_mcu_platform.json"),
                  encoding="utf-8") as handle:
            platform = json.load(handle)

        self.assertEqual(platform["machineTimer"]["mtimeFrequencyHz"], 1_000_000)
        self.assertIn("LINUX_SYS_CLK_FREQUENCY_HZ = 50_000_000", linux_sim)
        self.assertIn(
            'sim_config.add_clocker("sys_clk", freq_hz=LINUX_SYS_CLK_FREQUENCY_HZ)',
            linux_sim)
        self.assertIn("sys_clk_freq=LINUX_SYS_CLK_FREQUENCY_HZ", linux_sim)
        self.assertIn("sdram_clk_freq = int(sys_clk_freq)", multicore_sim)
        self.assertIn("parameter integer SYS_CLK_FREQ    = 50000000", rtl)
        self.assertIn("parameter integer TIMEBASE_FREQ   = 1000000", rtl)

        for path in (
                "software/breeze-linux/flow-small.dts",
                "linux/buildroot-external/board/flow/dts/flow/flow-small.dts"):
            dts = read_text(path)
            self.assertEqual(dts.count("clock-frequency = <50000000>;"), 4, path)
            self.assertEqual(dts.count("timebase-frequency = <1000000>;"), 1, path)

    def test_linux_selects_verilog_clint_and_keeps_legacy_source(self):
        sim = read_text("sim/litex/multicore_sim.py")
        wrapper = read_text("litex_wrapper/flow/clint_verilog.py")
        self.assertIn("BreezeClintVerilog", sim)
        self.assertIn('privilege_profile == "linux"', sim)
        self.assertIn("BreezeClint)", sim)
        self.assertIn('Instance(\n            "FlowClint"', wrapper)
        self.assertTrue(os.path.isfile(os.path.join(
            FLOW_ROOT, "litex_wrapper", "flow", "clint.py")))

    def test_rtl_localizes_global_address_and_exports_timer_state(self):
        rtl = read_text("litex_wrapper/flow/rtl/FlowClint.sv")
        self.assertIn("wb_adr & (REGION_WORDS - 1)", rtl)
        self.assertIn("MSIP_OFFSET", rtl)
        self.assertIn("MTIMECMP_OFFSET", rtl)
        self.assertIn("MTIME_OFFSET", rtl)
        self.assertIn("debug_mtimecmp", rtl)
        self.assertNotIn("migen.fhdl", rtl)

    def test_directed_test_uses_global_addresses_and_required_marker(self):
        testbench = read_text("sim/rtl/flow_clint_tb.sv")
        runner = read_text("sim/rtl/run_flow_clint_test.sh")
        integration = read_text("sim/litex/run_linux_clint.py")
        self.assertIn("CLINT_BASE + 32'h0000_0004", testbench)
        self.assertIn("CLINT_BASE + 32'h0000_4000", testbench)
        self.assertIn("CLINT_BASE + 32'h0000_bff8", testbench)
        self.assertIn("[FLOW-CLINT-PASS]", testbench)
        self.assertIn("--top-module flow_clint_tb", runner)
        self.assertIn('"--privilege", "linux"', integration)
        self.assertIn('"LINK_SCRIPT=link-linux.ld"', integration)
        self.assertIn('"--mem-trace-address-start", "0x02000000"', integration)
        self.assertIn('PROFILE_HARTS = {"single": 1, "dual": 2, "small": 4}',
            integration)
        self.assertIn('"per-hart-timer"', integration)
        self.assertIn(
            'f"{args.profile} {args.core_preset} linux debug"', integration)
        self.assertIn("require_debug_rtl(args.profile, args.core_preset)",
            integration)
        self.assertIn('"rtlMode": "debug"', integration)


if __name__ == "__main__":
    unittest.main()
