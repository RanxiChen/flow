#!/usr/bin/env python3
"""Contract tests for the fixed Breeze LiteX CPU product."""

import os
import sys
import tempfile
import unittest
from unittest import mock


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
WRAPPER_ROOT = os.path.join(FLOW_ROOT, "litex_wrapper")
if WRAPPER_ROOT not in sys.path:
    sys.path.insert(0, WRAPPER_ROOT)

from flow import Breeze  # noqa: E402
from flow.cluster import Flow  # noqa: E402


class _Platform:
    def __init__(self):
        self.sources = []
        self.include_paths = []

    def add_source(self, path):
        self.sources.append(path)

    def add_verilog_include_path(self, path):
        self.include_paths.append(path)


def _write_production_rtl(root):
    rtl_dir = os.path.join(
        root, "design", "build", "rtl", "cluster", "small", "gshare", "linux")
    os.makedirs(rtl_dir)
    source = os.path.join(rtl_dir, "BreezeMulticoreClusterWishbone.sv")
    with open(source, "w", encoding="utf-8") as source_file:
        source_file.write("module BreezeMulticoreClusterWishbone; endmodule\n")
    with open(os.path.join(rtl_dir, "filelist.f"), "w", encoding="utf-8") as manifest:
        manifest.write("BreezeMulticoreClusterWishbone.sv\n")
    marker = """profile=small
numHarts=4
l1iBytes=8192
l1dBytes=8192
l2Bytes=65536
lineBytes=32
l1Ways=4
l2Ways=8
corePreset=gshare
privilegeProfile=linux
rtlMode=production
tandem=false
compressed=true
addressTranslation=bare,sv39
"""
    marker_path = os.path.join(rtl_dir, "cluster-profile.txt")
    with open(marker_path, "w", encoding="utf-8") as marker_file:
        marker_file.write(marker)
    return rtl_dir, marker_path


class BreezeCpuWrapperTest(unittest.TestCase):
    def test_public_product_is_fixed_four_hart_linux(self):
        self.assertEqual(Breeze.name, "breeze")
        self.assertEqual(Breeze.variants, ("standard",))
        self.assertEqual(Breeze.num_harts, 4)
        self.assertEqual(Breeze.cluster_profile, "small")
        self.assertEqual(Breeze.core_preset, "gshare")
        self.assertEqual(Breeze.privilege_profile, "linux")
        self.assertEqual(Breeze.rtl_mode, "production")
        self.assertFalse(Breeze.tandem_enabled)
        self.assertEqual(Breeze.mem_map["rom"], 0x1001_0000)

    def test_instantiation_requires_and_loads_matching_production_rtl(self):
        with tempfile.TemporaryDirectory() as root:
            rtl_dir, _ = _write_production_rtl(root)
            platform = _Platform()
            with mock.patch.object(Breeze, "flow_root_dir", return_value=root):
                cpu = Breeze(platform)

            self.assertEqual(len(cpu.retires), 4)
            self.assertEqual(cpu.periph_buses, [cpu.memory_bus, cpu.mmio_bus])
            self.assertEqual(cpu.memory_buses, [])
            self.assertEqual(
                platform.sources,
                [os.path.join(rtl_dir, "BreezeMulticoreClusterWishbone.sv")])
            self.assertIn("-march=rv64imafdc_zicsr_zifencei", cpu.gcc_flags)
            self.assertIn("-mabi=lp64d", cpu.gcc_flags)
            self.assertIn("-D__riscv_plic__", cpu.gcc_flags)

            cpu.set_reset_address(0x1001_0000)
            with self.assertRaisesRegex(ValueError, "reset address is fixed"):
                cpu.set_reset_address(0x1000_0000)

    def test_debug_marker_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            _, marker_path = _write_production_rtl(root)
            with open(marker_path, "r", encoding="utf-8") as marker_file:
                marker = marker_file.read()
            with open(marker_path, "w", encoding="utf-8") as marker_file:
                marker_file.write(marker.replace(
                    "rtlMode=production", "rtlMode=debug"))

            with mock.patch.object(Breeze, "flow_root_dir", return_value=root):
                with self.assertRaisesRegex(RuntimeError, "rtlMode"):
                    Breeze(_Platform())

    def test_legacy_mcu_wrapper_keeps_direct_interrupt_wiring(self):
        with tempfile.TemporaryDirectory() as root:
            rtl_dir = os.path.join(
                root, "design", "build", "rtl", "cluster", "single", "gshare")
            os.makedirs(rtl_dir)
            source = os.path.join(rtl_dir, "BreezeMulticoreClusterWishbone.sv")
            with open(source, "w", encoding="utf-8") as source_file:
                source_file.write(
                    "module BreezeMulticoreClusterWishbone; endmodule\n")
            with open(os.path.join(rtl_dir, "filelist.f"), "w",
                      encoding="utf-8") as manifest:
                manifest.write("BreezeMulticoreClusterWishbone.sv\n")
            marker = """profile=single
numHarts=1
l1iBytes=8192
l1dBytes=8192
l2Bytes=16384
lineBytes=32
l1Ways=4
l2Ways=8
corePreset=gshare
privilegeProfile=mcu
rtlMode=debug
tandem=true
"""
            with open(os.path.join(rtl_dir, "cluster-profile.txt"), "w",
                      encoding="utf-8") as marker_file:
                marker_file.write(marker)

            Flow.set_core_preset("gshare")
            with mock.patch.object(Flow, "flow_root_dir", return_value=root):
                cpu = Flow(_Platform())

            self.assertIs(cpu.cpu_params["i_io_externalInterrupts_0"], cpu.interrupt)


if __name__ == "__main__":
    unittest.main()
