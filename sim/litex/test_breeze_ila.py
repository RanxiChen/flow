#!/usr/bin/env python3
"""Check debug capture guards and retained retirement evidence."""

import os
import sys
import unittest
from types import SimpleNamespace

from migen import ClockDomain, Instance, Module, Record, Signal
from migen.sim import run_simulation
from litex.soc.interconnect import wishbone

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__),
                                             "../../litex_wrapper")))
from flow.core import DCACHE_TRACE_LAYOUT, RETIRE_LAYOUT
from flow.ila import BreezeDebugILA


class _InputOnlyILA:
    @staticmethod
    def lower(special):
        # The vendor sampler has no outputs. Simulate the observation registers.
        assert special.of == "breeze_ila"
        return Module()


class BreezeILATest(unittest.TestCase):
    def test_invalid_cpu_is_rejected_before_ip_creation(self):
        for harts, tandem in ((4, True), (1, False)):
            with self.assertRaisesRegex(ValueError, "single hart with live Tandem"):
                BreezeDebugILA(SimpleNamespace(num_harts=harts, tandem_enabled=tandem), None)

    def test_last_retire_survives_idle_bus_activity(self):
        retire = Record(RETIRE_LAYOUT)
        cpu = SimpleNamespace(
            num_harts=1, tandem_enabled=True, retires=[retire],
            dcache_traces=[Record(DCACHE_TRACE_LAYOUT)],
            hart_fatal=Signal(), hart_estop=Signal(), time=Signal(64),
            memory_bus=wishbone.Interface(data_width=64, address_width=32, addressing="word"),
            mmio_bus=wishbone.Interface(data_width=64, address_width=32, addressing="word"),
        )
        platform = SimpleNamespace(toolchain=SimpleNamespace(
            pre_synthesis_commands=[], additional_commands=[]))
        dut = BreezeDebugILA(cpu, platform)
        dut.clock_domains.cd_sys = ClockDomain("sys")
        probes = {item["signal"]: probe for item, probe in zip(dut.probe_map, dut.probes)}

        def stimulus():
            self.assertEqual((yield probes["dbg_seen_retire"]), 0)
            yield retire.pc.eq(0x10012340)
            yield retire.inst.eq(0x00008067)
            yield retire.valid.eq(1)
            yield
            yield
            self.assertEqual((yield probes["dbg_last_retire_pc"]), 0x10012340)
            yield retire.valid.eq(0)
            yield retire.pc.eq(0)
            yield cpu.mmio_bus.adr.eq(0x12001000 >> 3)
            yield cpu.mmio_bus.cyc.eq(1)
            yield
            yield
            self.assertEqual((yield probes["dbg_last_retire_pc"]), 0x10012340)
            self.assertEqual((yield probes["dbg_last_retire_inst"]), 0x00008067)
            self.assertEqual((yield probes["dbg_seen_retire"]), 1)
            self.assertGreater((yield probes["dbg_no_retire_cycles"]), 0)
            self.assertEqual((yield probes["dbg_mmio_address"]), 0x12001000)
            self.assertEqual((yield probes["dbg_mmio_cyc"]), 1)

        run_simulation(dut, stimulus(), special_overrides={Instance: _InputOnlyILA})


if __name__ == "__main__":
    unittest.main()
