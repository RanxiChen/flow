"""AXI product marker, RTL port and memory-routing contract tests."""
import hashlib
import os
import shutil
import sys
import tempfile
import unittest
from unittest import mock

FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
sys.path.insert(0, os.path.join(FLOW_ROOT, 'litex_wrapper'))
from flow import Breeze, BreezeTiny
from flow.core import BreezeTinyDebug, RETIRE_LAYOUT
from litex.soc.interconnect import axi


class _Platform:
    def __init__(self):
        self.sources, self.include_paths = [], []
    def add_source(self, path):
        self.sources.append(path)
    def add_verilog_include_path(self, path):
        self.include_paths.append(path)


def _write_production_rtl(root, cpu_cls=Breeze):
    os.makedirs(os.path.join(root, 'config'), exist_ok=True)
    shutil.copyfile(os.path.join(FLOW_ROOT, 'config/breeze_mcu_platform.json'),
                    os.path.join(root, 'config/breeze_mcu_platform.json'))
    with mock.patch.object(cpu_cls, 'flow_root_dir', return_value=root):
        rtl_dir = cpu_cls.rtl_dir()
    os.makedirs(rtl_dir)
    with open(os.path.join(rtl_dir, 'BreezeClusterAxi.sv'), 'w') as f:
        f.write('module BreezeClusterAxi; endmodule\n')
    with open(os.path.join(rtl_dir, 'filelist.f'), 'w') as f:
        f.write('BreezeClusterAxi.sv\n')
    with open(os.path.join(root, 'config/breeze_mcu_platform.json'), 'rb') as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    values = dict(bus='axi', profile=cpu_cls.cluster_profile, preset=cpu_cls.core_preset,
        privilege=cpu_cls.privilege_profile, tandem=str(cpu_cls.tandem_enabled).lower(),
        debug=str(cpu_cls.debug_enabled).lower(), platformSha256=digest,
        nCores=str(cpu_cls.num_harts), idBits='1', l2Slots='2', l2BytesPerCore='65536',
        hangThresholdCycles='10000000')
    marker_path = os.path.join(rtl_dir, 'cluster-profile.txt')
    with open(marker_path, 'w') as f:
        f.write(''.join(f'{key}={value}\n' for key, value in values.items()))
    return rtl_dir, marker_path


class BreezeCpuWrapperTest(unittest.TestCase):
    def test_production_products_use_axi_and_only_dram_exit_is_direct(self):
        for cls in (Breeze, BreezeTiny):
            with tempfile.TemporaryDirectory() as root:
                rtl_dir, _ = _write_production_rtl(root, cls)
                platform = _Platform()
                with mock.patch.object(cls, 'flow_root_dir', return_value=root):
                    cpu = cls(platform)
                self.assertIsInstance(cpu.memory_bus, axi.AXIInterface)
                self.assertIsInstance(cpu.mmio_bus, axi.AXILiteInterface)
                self.assertEqual(cpu.memory_buses, [cpu.axi_router.dram])
                self.assertEqual(cpu.periph_buses, [cpu.axi_router.low, cpu.mmio_bus])
                self.assertEqual(cpu.l2_bytes, cls.num_harts * 65536)
                self.assertEqual(cpu.id_width, 1)
                self.assertEqual(platform.sources, [os.path.join(rtl_dir, 'BreezeClusterAxi.sv')])
                self.assertFalse(any('Wishbone' in p or 'debug' in p for p in cpu.cpu_params))
                self.assertFalse(hasattr(cpu, 'dma_bus'))
                cpu.set_reset_address(0x10010000)
                with self.assertRaisesRegex(ValueError, 'reset address is fixed'):
                    cpu.set_reset_address(0x10000000)
                self.assertIn('-march=rv64imafdc_zicsr_zifencei', cpu.gcc_flags)
                self.assertIn('-mabi=lp64d', cpu.gcc_flags)
                self.assertIn('-D__riscv_plic__', cpu.gcc_flags)

    def test_debug_is_isolated_and_exports_every_retire_field_and_hang(self):
        with tempfile.TemporaryDirectory() as root:
            production, _ = _write_production_rtl(root, BreezeTiny)
            debug, _ = _write_production_rtl(root, BreezeTinyDebug)
            with mock.patch.object(BreezeTinyDebug, 'flow_root_dir', return_value=root):
                cpu = BreezeTinyDebug(_Platform())
            self.assertNotEqual(production, debug)
            self.assertEqual(cpu.hang_threshold, 10000000)
            self.assertIn('o_io_debug_hangReasons', cpu.cpu_params)
            self.assertIn('o_io_debug_retire_lateWriteData', cpu.cpu_params)
            self.assertEqual(len([p for p in cpu.cpu_params if p.startswith('o_io_debug_retire_')]),
                             len(RETIRE_LAYOUT))
            self.assertIn('o_io_debug_l1dEvents_mmio_cycles', cpu.cpu_params)
            self.assertIn('o_io_debug_l2Events_memReadsInFlight', cpu.cpu_params)

    def test_each_required_marker_mismatch_is_rejected(self):
        for key in ('bus', 'profile', 'preset', 'privilege', 'tandem', 'debug', 'platformSha256', 'nCores'):
            with tempfile.TemporaryDirectory() as root:
                _, path = _write_production_rtl(root, BreezeTiny)
                with open(path) as f:
                    marker = f.read()
                lines = [key + '=WRONG' if line.startswith(key + '=') else line for line in marker.splitlines()]
                with open(path, 'w') as f:
                    f.write('\n'.join(lines) + '\n')
                with mock.patch.object(BreezeTiny, 'flow_root_dir', return_value=root):
                    with self.assertRaisesRegex(RuntimeError, key):
                        BreezeTiny(_Platform())

    def test_axilite_b_and_axi_r_port_directions_and_ids(self):
        with tempfile.TemporaryDirectory() as root:
            _write_production_rtl(root, BreezeTiny)
            with mock.patch.object(BreezeTiny, 'flow_root_dir', return_value=root):
                cpu = BreezeTiny(_Platform())
            self.assertIs(cpu.cpu_params['i_io_mem_r_bits_id'], cpu.memory_bus.r.id)
            self.assertIs(cpu.cpu_params['o_io_mem_r_ready'], cpu.memory_bus.r.ready)
            self.assertIs(cpu.cpu_params['o_io_mem_aw_bits_id'], cpu.memory_bus.aw.id)
            self.assertIs(cpu.cpu_params['i_io_mmio_b_bits'], cpu.mmio_bus.b.resp)
            self.assertIs(cpu.cpu_params['o_io_mmio_b_ready'], cpu.mmio_bus.b.ready)
            self.assertNotIn('i_io_msip_1', cpu.cpu_params)

    def test_fixed_public_profiles_and_memory_map(self):
        self.assertEqual((Breeze.cluster_profile, Breeze.num_harts), ('small', 4))
        self.assertEqual((BreezeTiny.cluster_profile, BreezeTiny.num_harts), ('single', 1))
        self.assertEqual(BreezeTiny.mem_map, Breeze.mem_map)
        self.assertEqual(Breeze.mem_map['rom'], 0x10010000)
        self.assertEqual(Breeze.mem_map['sram'], 0x11000000)
