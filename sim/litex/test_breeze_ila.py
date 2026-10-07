"""Check complete passive AXI/retirement/hang ILA wiring and probe metadata."""
import json
import os
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock

from migen import ClockDomain, Instance, Module
from migen.sim import run_simulation
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '../../litex_wrapper')))
from flow.core import BreezeTinyDebug, RETIRE_LAYOUT
from flow.ila import BreezeDebugILA
from test_breeze_cpu_wrapper import _Platform, _write_production_rtl


class _InputOnlyILA:
    @staticmethod
    def lower(special):
        assert special.of == 'breeze_ila'
        return Module()


class BreezeILATest(unittest.TestCase):
    def test_invalid_cpu_is_rejected_before_ip_creation(self):
        for harts, tandem in ((4, True), (1, False)):
            with self.assertRaisesRegex(ValueError, 'single hart with live Tandem'):
                BreezeDebugILA(SimpleNamespace(num_harts=harts, tandem_enabled=tandem), None)

    def test_retained_chisel_debug_and_axi_probes_are_passive_and_map_matches(self):
        with tempfile.TemporaryDirectory() as root:
            _write_production_rtl(root, BreezeTinyDebug)
            with mock.patch.object(BreezeTinyDebug, 'flow_root_dir', return_value=root):
                cpu = BreezeTinyDebug(_Platform())
            platform = SimpleNamespace(toolchain=SimpleNamespace(pre_synthesis_commands=[], additional_commands=[]))
            dut = BreezeDebugILA(cpu, platform)
            dut.clock_domains.cd_sys = ClockDomain('sys')
            probes = {item['signal']: probe for item, probe in zip(dut.probe_map, dut.probes)}
            for name, _ in RETIRE_LAYOUT:
                self.assertIn('dbg_retire_' + name, probes)
            for name in ('memory_ar_id', 'memory_ar_len', 'memory_r_resp', 'memory_r_last',
                         'memory_w_strb', 'mmio_b_resp', 'l1d_mmio_cycles', 'l2_memReadsInFlight',
                         'router_read_dram', 'router_write_low', 'hang_reasons'):
                self.assertIn('dbg_' + name, probes)
            path = os.path.join(root, 'ila-probes.json')
            dut.write_probe_map(path)
            with open(path) as f:
                metadata = json.load(f)
            self.assertEqual(metadata['total_width'], sum(len(p) for p in dut.probes))
            self.assertEqual(metadata['depth'], 4096)
            self.assertEqual(metadata['clock_hz'], 100000000)

            def stimulus():
                yield cpu.debug.lastRetirePc.eq(0x10012340)
                yield cpu.debug.lastRetireInst.eq(0x8067)
                yield cpu.debug.seenRetire.eq(1)
                yield cpu.debug.noRetireCycles.eq(123456)
                yield cpu.debug.hang.eq(1)
                yield cpu.debug.hangReasons.eq(4)
                yield cpu.mmio_bus.aw.addr.eq(0x12001000)
                yield cpu.mmio_bus.aw.valid.eq(1)
                yield cpu.mmio_bus.aw.ready.eq(0)
                yield
                yield
                self.assertEqual((yield probes['dbg_last_retire_pc']), 0x10012340)
                self.assertEqual((yield probes['dbg_last_retire_inst']), 0x8067)
                self.assertEqual((yield probes['dbg_seen_retire']), 1)
                self.assertEqual((yield probes['dbg_no_retire_cycles']), 123456)
                self.assertEqual((yield probes['dbg_mmio_aw_addr']), 0x12001000)
                self.assertEqual((yield probes['dbg_mmio_aw_valid']), 1)
                self.assertEqual((yield probes['dbg_mmio_aw_ready']), 0)
                self.assertEqual((yield probes['dbg_hang']), 1)
                self.assertEqual((yield probes['dbg_hang_reasons']), 4)
                self.assertEqual((yield cpu.mmio_bus.aw.ready), 0)
            run_simulation(dut, stimulus(), special_overrides={Instance: _InputOnlyILA})
