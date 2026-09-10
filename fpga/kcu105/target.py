#!/usr/bin/env python3
"""Fixed LiteX target for bringing Breeze up on a Xilinx KCU105.

The first hardware milestone deliberately stops at the LiteX BIOS and a
single-hart bare-metal payload loaded into on-chip RAM over UART. DDR4 is
temporarily omitted while the core and board integration are brought up.
"""

import argparse
import os
import sys

from migen import ClockDomain, Signal
from litex.gen import LiteXModule
from litex.soc.cores.clock import USMMCM
from litex.soc.cores.cpu import CPUS
from litex.soc.integration.builder import Builder
from litex.soc.integration.soc import SoCRegion
from litex.soc.integration.soc_core import SoCCore
from litex_boards.platforms import xilinx_kcu105


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
LITEX_WRAPPER_ROOT = os.path.join(FLOW_ROOT, "litex_wrapper")
if LITEX_WRAPPER_ROOT not in sys.path:
    sys.path.insert(0, LITEX_WRAPPER_ROOT)

from flow import Breeze  # noqa: E402
from flow.clint_verilog import BreezeClintVerilog  # noqa: E402
from flow.plic_verilog import BreezePlicVerilog  # noqa: E402
from flow.wiring import pack_plic_sources  # noqa: E402


# This target is intentionally configured in source rather than exposing a
# large command-line configuration surface.  A different hardware product
# should get a separate target script.
SYS_CLK_FREQ = 50_000_000
UART_BAUDRATE = 115_200

ROM_SIZE = 0x0001_0000
SRAM_SIZE = 0x0001_0000
MAIN_RAM_SIZE = 0x0004_0000  # 256 KiB BRAM at the existing main_ram origin.

CLINT_ORIGIN = 0x0200_0000
CLINT_SIZE = 0x0001_0000
CLINT_MSIP_OFFSET = 0x0000
CLINT_MTIMECMP_OFFSET = 0x4000
CLINT_MTIME_OFFSET = 0xBFF8
MTIME_FREQ = 1_000_000

PLIC_ORIGIN = 0x0C00_0000
PLIC_SIZE = 0x0400_0000
PLIC_NUM_SOURCES = 31
UART_PLIC_SOURCE = 10

BUILD_DIR = os.path.join(FLOW_ROOT, "build", "fpga", "kcu105-breeze-bram")


CPUS["breeze"] = Breeze


class _CRG(LiteXModule):
    """Single system clock; the BRAM target needs no DDR/IDELAY domains."""

    def __init__(self, platform, sys_clk_freq):
        self.rst = Signal()
        self.cd_sys = ClockDomain("sys")
        self.pll = pll = USMMCM(speedgrade=-2)
        self.comb += pll.reset.eq(platform.request("cpu_reset") | self.rst)
        pll.register_clkin(platform.request("clk125"), 125e6)
        pll.create_clkout(self.cd_sys, sys_clk_freq)
        platform.add_false_path_constraints(self.cd_sys.clk, pll.clkin)


class BreezeKCU105SoC(SoCCore):
    """Four-hart Breeze SoC with LiteX BIOS, UART and on-chip main RAM."""

    # Keep the software-visible control/UART/timer CSR layout stable.
    csr_map = {
        "ctrl": 0,
        "uart": 1,
        "timer0": 2,
    }

    def __init__(self):
        platform = xilinx_kcu105.Platform()
        self.crg = _CRG(platform, SYS_CLK_FREQ)

        super().__init__(
            platform,
            clk_freq=SYS_CLK_FREQ,
            ident="Breeze RV64GC BRAM SoC on KCU105",
            cpu_type="breeze",
            cpu_variant="standard",
            bus_standard="wishbone",
            bus_data_width=64,
            bus_address_width=32,
            bus_bursting=False,
            bus_interconnect="shared",
            integrated_rom_size=ROM_SIZE,
            integrated_sram_size=SRAM_SIZE,
            integrated_main_ram_size=MAIN_RAM_SIZE,
            csr_data_width=32,
            csr_address_width=14,
            csr_paging=0x1000,
            with_ctrl=True,
            with_uart=True,
            uart_name="serial",
            uart_baudrate=UART_BAUDRATE,
            # The BIOS polls timer0 for delays/timeouts.  Architectural time
            # and timer interrupts are supplied separately by the CLINT.
            with_timer=True,
        )

        self.clint = BreezeClintVerilog(
            platform=platform,
            sys_clk_freq=SYS_CLK_FREQ,
            timebase_freq=MTIME_FREQ,
            num_harts=self.cpu.num_harts,
            region_size=CLINT_SIZE,
            msip_offset=CLINT_MSIP_OFFSET,
            mtimecmp_offset=CLINT_MTIMECMP_OFFSET,
            mtime_offset=CLINT_MTIME_OFFSET,
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

        self.plic = BreezePlicVerilog(
            platform=platform,
            num_harts=self.cpu.num_harts,
            num_sources=PLIC_NUM_SOURCES,
        )
        self.bus.add_slave(
            name="plic",
            slave=self.plic.bus,
            region=SoCRegion(
                origin=PLIC_ORIGIN,
                size=PLIC_SIZE,
                cached=False,
            ),
        )
        self.comb += [
            self.plic.sources.eq(pack_plic_sources(
                self.uart.ev.irq,
                first_source=UART_PLIC_SOURCE,
                num_sources=PLIC_NUM_SOURCES,
            )),
            self.cpu.meip.eq(self.plic.meip),
            self.cpu.seip.eq(self.plic.seip),
        ]

        self.add_constant("BREEZE_NUM_HARTS", self.cpu.num_harts)
        self.add_constant("BREEZE_CLINT", CLINT_ORIGIN)
        self.add_constant("BREEZE_MSIP", CLINT_ORIGIN + CLINT_MSIP_OFFSET)
        self.add_constant("BREEZE_MTIMECMP", CLINT_ORIGIN + CLINT_MTIMECMP_OFFSET)
        self.add_constant("BREEZE_MTIME", CLINT_ORIGIN + CLINT_MTIME_OFFSET)
        self.add_constant("BREEZE_MTIME_FREQUENCY", MTIME_FREQ)
        self.add_constant("BREEZE_PLIC", PLIC_ORIGIN)
        self.add_constant("BREEZE_UART_PLIC_SOURCE", UART_PLIC_SOURCE)


def main():
    parser = argparse.ArgumentParser(
        description="Build the fixed Breeze/KCU105 LiteX SoC.")
    parser.add_argument(
        "--build",
        action="store_true",
        help="run Vivado synthesis/implementation after generating the project",
    )
    parser.add_argument(
        "--load",
        action="store_true",
        help="load the already-built bitstream into KCU105 SRAM",
    )
    args = parser.parse_args()

    soc = BreezeKCU105SoC()
    builder = Builder(
        soc,
        output_dir=BUILD_DIR,
        csr_csv=os.path.join(BUILD_DIR, "csr.csv"),
        csr_json=os.path.join(BUILD_DIR, "csr.json"),
    )

    # With no --build this still finalizes the SoC, compiles the ROM BIOS and
    # emits the Vivado project, but does not launch synthesis/implementation.
    # The fixed four-hart RV64GC cluster is close to the KU040 LUT limit, so
    # use Vivado's area-oriented flow instead of its default strategy.  These
    # directives do not change the architectural configuration.
    builder.build(
        run=args.build,
        vivado_synth_directive="AreaOptimized_high",
        vivado_opt_directive="ExploreArea",
        vivado_place_directive="AltSpreadLogic_high",
        vivado_post_place_phys_opt_directive="AggressiveExplore",
        vivado_route_directive="NoTimingRelaxation",
    )

    if args.load:
        programmer = soc.platform.create_programmer()
        programmer.load_bitstream(builder.get_bitstream_filename(mode="sram"))


if __name__ == "__main__":
    main()
