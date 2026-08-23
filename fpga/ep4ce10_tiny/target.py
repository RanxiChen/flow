#!/usr/bin/env python3
"""Fixed minimal Flow MCU SoC for the ALIENTEK EP4CE10 board."""

import argparse

import os
import sys

from migen import ClockDomain, Module
from migen.genlib.resetsync import AsyncResetSynchronizer

from litex.build.altera import AlteraPlatform
from litex.build.generic_platform import IOStandard, Pins, Subsignal
from litex.soc.cores.cpu import CPUS
from litex.soc.integration.builder import Builder
from litex.soc.integration.common import get_mem_data
from litex.soc.integration.soc import SoCRegion
from litex.soc.integration.soc_core import SoCCore


THIS_DIR = os.path.dirname(os.path.abspath(__file__))
FLOW_ROOT = os.path.abspath(os.path.join(THIS_DIR, "..", ".."))
WRAPPER_ROOT = os.path.join(FLOW_ROOT, "litex_wrapper")
if WRAPPER_ROOT not in sys.path:
    sys.path.insert(0, WRAPPER_ROOT)

from flow.clint import BreezeClint  # noqa: E402
from flow.cluster import FlowCluster  # noqa: E402


SYS_CLK_FREQ = 50_000_000
MTIME_FREQ = 1_000_000
CLINT_ORIGIN = 0x0200_0000
CLINT_SIZE = 0x0001_0000
ROM_SIZE = 0x0000_2000


class GatewareOnlyBuilder(Builder):
    """Generate CSR metadata without requiring the unused LiteX BIOS SDK."""

    def _generate_includes(self, with_bios=True):
        del with_bios
        return super()._generate_includes(with_bios=False)


_io = [
    ("clk50", 0, Pins("E1"), IOStandard("3.3-V LVTTL")),
    ("rst_n", 0, Pins("M1"), IOStandard("3.3-V LVTTL")),
    ("serial", 0,
        Subsignal("rx", Pins("N5")),
        Subsignal("tx", Pins("M7")),
        IOStandard("3.3-V LVTTL")),
]


class Platform(AlteraPlatform):
    default_clk_name = "clk50"
    default_clk_period = 1e9 / SYS_CLK_FREQ

    def __init__(self):
        super().__init__("EP4CE10F17C8", _io, toolchain="quartus")
        self.toolchain.additional_sdc_commands.append(
            "set_false_path -from [get_ports {rst_n}]"
        )


class _CRG(Module):
    def __init__(self, platform):
        self.clock_domains.cd_sys = ClockDomain("sys")
        clk50 = platform.request("clk50")
        rst_n = platform.request("rst_n")
        self.comb += self.cd_sys.clk.eq(clk50)
        self.specials += AsyncResetSynchronizer(
            self.cd_sys, ~rst_n
        )
        platform.add_period_constraint(clk50, 1e9 / SYS_CLK_FREQ)


class FlowTinyEp4ce10SoC(SoCCore):
    """Minimal real SoC around the reduced Flow cache hierarchy."""

    mem_map = {
        "rom": 0x1000_0000,
        "sram": 0x1100_0000,
        "csr": 0x1200_0000,
    }
    # Keep the firmware-visible LiteUART registers fixed at 0x12001000.
    csr_map = {"uart": 1}

    def __init__(self, platform, rom_init):
        FlowCluster.set_cluster_config("tiny-fpga", "tiny-fpga", "mcu")
        self.submodules.crg = _CRG(platform)

        super().__init__(
            platform,
            clk_freq=SYS_CLK_FREQ,
            ident="",
            cpu_type="flow_cluster",
            cpu_variant="minimal",
            bus_standard="wishbone",
            bus_data_width=64,
            bus_address_width=32,
            bus_bursting=False,
            bus_interconnect="shared",
            integrated_rom_size=ROM_SIZE,
            integrated_rom_init=rom_init,
            integrated_sram_size=0x0000_4000,
            integrated_main_ram_size=0,
            csr_data_width=32,
            csr_address_width=14,
            csr_paging=0x1000,
            with_ctrl=False,
            with_uart=True,
            uart_name="serial",
            uart_baudrate=115200,
            with_timer=False,
        )

        self.submodules.clint = BreezeClint(
            sys_clk_freq=SYS_CLK_FREQ,
            timebase_freq=MTIME_FREQ,
            num_harts=1,
            region_size=CLINT_SIZE,
        )
        self.bus.add_slave(
            name="clint",
            slave=self.clint.bus,
            region=SoCRegion(
                origin=CLINT_ORIGIN,
                size=CLINT_SIZE,
                cached=False,
            ),
        )
        self.comb += [
            self.cpu.msip.eq(self.clint.msip),
            self.cpu.mtip.eq(self.clint.mtip),
            self.cpu.time.eq(self.clint.mtime),
        ]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--firmware",
        default=os.path.join(
            FLOW_ROOT,
            "software",
            "breeze-tiny-ep4ce10",
            "build",
            "breeze-tiny-ep4ce10.bin",
        ),
    )
    args = parser.parse_args()

    firmware_size = os.path.getsize(args.firmware)
    if firmware_size > ROM_SIZE:
        raise ValueError(
            f"firmware is {firmware_size} bytes, larger than {ROM_SIZE}-byte ROM"
        )
    rom_init = get_mem_data(
        args.firmware,
        data_width=64,
        endianness="little",
        mem_size=ROM_SIZE + 1,
    )

    CPUS["flow_cluster"] = FlowCluster
    platform = Platform()
    soc = FlowTinyEp4ce10SoC(platform, rom_init=rom_init)

    output_dir = os.path.join(FLOW_ROOT, "build", "ep4ce10-tiny")
    gateware_dir = os.path.join(output_dir, "gateware")
    os.makedirs(gateware_dir, exist_ok=True)

    builder = GatewareOnlyBuilder(
        soc,
        output_dir=output_dir,
        compile_software=False,
        compile_gateware=False,
    )
    build_name = "flow_tiny_ep4ce10"
    builder.build(run=False, build_name=build_name)

    # Quartus runs on Windows, so every generated source reference must be
    # relative to the project directory rather than a WSL absolute path.
    qsf_path = os.path.join(gateware_dir, f"{build_name}.qsf")
    with open(qsf_path, "r", encoding="utf-8") as qsf_file:
        qsf = qsf_file.read()
    qsf = qsf.replace(
        os.path.join(gateware_dir, f"{build_name}.v"),
        f"{build_name}.v",
    )
    with open(qsf_path, "w", encoding="utf-8") as qsf_file:
        qsf_file.write(qsf)
    print(
        f"[FLOW-TINY-EP4CE10] firmware={firmware_size} bytes "
        f"rom={ROM_SIZE} bytes uart=0x12001000 project={gateware_dir}"
    )


if __name__ == "__main__":
    main()
