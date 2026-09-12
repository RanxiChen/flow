#!/usr/bin/env python3
"""Fixed LiteX target for bringing Breeze up on a Xilinx KCU105.

The first hardware milestone deliberately stops at the LiteX BIOS and a
single-hart bare-metal payload loaded into DDR4 over UART.  OpenSBI and Linux
use the same memory map later, but are not part of this target's build flow.
"""

import argparse
import json
import os
import subprocess
import sys

from litex.soc.cores.cpu import CPUS
from litex.soc.integration.builder import Builder
from litex.soc.integration.soc import SoCRegion
from litex.soc.integration.soc_core import SoCCore
from litex_boards.platforms import xilinx_kcu105
from litex_boards.targets.xilinx_kcu105 import _CRG
from litedram.modules import EDY4016A
from litedram.phy import usddrphy
from migen import Cat, Constant


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
LITEX_WRAPPER_ROOT = os.path.join(FLOW_ROOT, "litex_wrapper")
if LITEX_WRAPPER_ROOT not in sys.path:
    sys.path.insert(0, LITEX_WRAPPER_ROOT)

from flow import Breeze, BreezeTiny  # noqa: E402
from flow.core import BreezeTinyDebug, BreezeDma, BreezeTinyDma, BreezeTinyDebugDma  # noqa: E402
from flow.clint_verilog import BreezeClintVerilog  # noqa: E402
from flow.plic_verilog import BreezePlicVerilog  # noqa: E402
from flow.wiring import pack_plic_sources  # noqa: E402
from build_support import SnapshotBuilder  # noqa: E402


# This target is intentionally configured in source rather than exposing a
# large command-line configuration surface. Only the cluster size is selectable.
SYS_CLK_FREQ = 50_000_000
UART_BAUDRATE = 115_200

ROM_SIZE = 0x0001_0000
SRAM_SIZE = 0x0001_0000
DDR_SIZE = 0x8000_0000  # Full 2 GiB DDR4 at 0x80000000..0xffffffff.

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

BUILD_DIR = os.path.join(FLOW_ROOT, "build", "fpga", "kcu105-breeze-ddr")
TINY_BUILD_DIR = os.path.join(FLOW_ROOT, "build", "fpga", "kcu105-breeze-tiny-ddr")
DEBUG_BUILD_DIR = TINY_BUILD_DIR + "-debug"


CPUS["breeze"] = Breeze
# LiteX incorporates this key into CONFIG_CPU_TYPE_* C identifiers.
# Keep the public CLI spelling hyphenated and the internal registry key valid C.
CPUS["breeze_tiny"] = BreezeTiny
CPUS["breeze_tiny_debug"] = BreezeTinyDebug
CPUS["breeze_dma"] = BreezeDma
CPUS["breeze_tiny_dma"] = BreezeTinyDma
CPUS["breeze_tiny_debug_dma"] = BreezeTinyDebugDma


class BreezeKCU105SoC(SoCCore):
    """One- or four-hart Breeze SoC with BIOS, PLIC, CLINT and DDR4."""

    # Keep the software-visible LiteX CSR layout stable.  The DDR PHY and
    # controller CSRs are allocated after these fixed pages.
    csr_map = {
        "ctrl": 0,
        "uart": 1,
        "timer0": 2,
        "ddrphy": 3,
        "identifier_mem": 4,
        "sdram": 5,
        "sdcard": 6,
    }

    irq_map = {"uart": UART_PLIC_SOURCE, "sdcard": UART_PLIC_SOURCE + 1}

    def __init__(self, cpu_type="breeze", debug=False, sys_clk_freq=SYS_CLK_FREQ, with_sdcard=False):
        if cpu_type not in ("breeze", "breeze-tiny"):
            raise ValueError(f"Unsupported KCU105 CPU: {cpu_type}")
        if debug and cpu_type != "breeze-tiny":
            raise ValueError("--debug only supports --cpu-type breeze-tiny (single hart)")
        if sys_clk_freq not in (50_000_000, 100_000_000):
            raise ValueError("KCU105 system frequency must be 50 or 100 MHz")
        platform = xilinx_kcu105.Platform()
        self.crg = _CRG(platform, sys_clk_freq)
        cpu_key = "breeze_tiny_debug" if debug else cpu_type.replace("-", "_")
        if with_sdcard:
            cpu_key += "_dma"

        super().__init__(
            platform,
            clk_freq=sys_clk_freq,
            ident="Breeze RV64GC DDR4 SoC on KCU105",
            cpu_type=cpu_key,
            cpu_variant="standard",
            bus_standard="wishbone",
            bus_data_width=64,
            bus_address_width=32,
            bus_bursting=False,
            bus_interconnect="shared",
            integrated_rom_size=ROM_SIZE,
            integrated_sram_size=SRAM_SIZE,
            # The KCU105 DDR4 controller below owns the main_ram region.
            integrated_main_ram_size=0,
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

        # DDR4 is the only main_ram implementation.  Breeze already contains
        # its coherent shared L2, so a second LiteX L2 must not be inserted.
        self.ddrphy = usddrphy.USDDRPHY(
            platform.request("ddram"),
            memtype="DDR4",
            sys_clk_freq=sys_clk_freq,
            iodelay_clk_freq=200e6,
        )
        self.add_sdram(
            name="sdram",
            phy=self.ddrphy,
            module=EDY4016A(sys_clk_freq, "1:4"),
            size=DDR_SIZE,
            l2_cache_size=0,
        )

        # LiteDRAM can cap the requested size to the physical geometry. Check
        # its actual region against the PMA input used by Chisel, not just the
        # requested DDR_SIZE. CPU RTL must still be regenerated after JSON edits.
        with open(os.path.join(FLOW_ROOT, "config", "breeze_mcu_platform.json"),
                  encoding="utf-8") as config_file:
            platform_config = json.load(config_file)
        ram_pma = next(r for r in platform_config["regions"] if r["name"] == "main_ram")
        ram_region = self.bus.regions["main_ram"]
        pma_origin = int(ram_pma["origin"], 0)
        pma_size = int(ram_pma["size"], 0)
        if (ram_region.origin, ram_region.size) != (pma_origin, pma_size):
            raise ValueError(
                f"DDR/PMA mismatch: SoC={ram_region.origin:#x}+{ram_region.size:#x}, "
                f"PMA={pma_origin:#x}+{pma_size:#x}")
        if ram_region.size != DDR_SIZE:
            raise ValueError("DDR geometry does not provide the requested full capacity")
        if pma_origin + pma_size > (1 << platform_config["addressWidth"]):
            raise ValueError("DDR exceeds the CPU physical address width")
        ram_attributes = ("readable", "writable", "executable", "cacheable")
        if not all(ram_pma[key] for key in ram_attributes) or ram_pma["device"]:
            raise ValueError("DDR PMA must be cacheable R/W/X normal memory")
        sram_pma = next(r for r in platform_config["regions"] if r["name"] == "sram")
        actual_sram = self.bus.regions["sram"]
        if (actual_sram.origin, actual_sram.size) != (int(sram_pma["origin"], 0), int(sram_pma["size"], 0)):
            raise ValueError("SRAM/PMA mismatch: BIOS DMA buffers require an exact SRAM region")

        self.clint = BreezeClintVerilog(
            platform=platform,
            sys_clk_freq=sys_clk_freq,
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
        if with_sdcard:
            self.add_sdcard(mode="read+write")
            self.add_constant("CONFIG_BIOS_NO_BOOT", 1)
        interrupt_sources = Cat(self.uart.ev.irq, self.sdcard.ev.irq) if with_sdcard else self.uart.ev.irq
        self.comb += [
            self.plic.sources.eq(pack_plic_sources(
                interrupt_sources,
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
        if with_sdcard:
            self.add_constant("BREEZE_SDCARD_PLIC_SOURCE", UART_PLIC_SOURCE + 1)

        if debug:
            from flow.ila import BreezeDebugILA
            sd_probes = []
            if with_sdcard:
                bus, phy = self.cpu.dma_bus, self.sdcard.phy
                sd_probes = [("dma_address", Cat(Constant(0, 3), bus.adr))]
                sd_probes += [("dma_" + n, getattr(bus, n))
                              for n in ("cyc", "stb", "ack", "err", "we", "sel")]
                # Observe internal PHY signals, never physical bidirectional pads.
                sd_probes += [("sd_cmd_" + n, getattr(phy.sdpads.cmd, n))
                              for n in ("i", "o", "oe")]
                sd_probes += [("sd_data_" + n, getattr(phy.sdpads.data, n))
                              for n in ("i", "o", "oe")]
                sd_probes += [("sd_clock_pre_io", ~phy.clocker.clk),
                              ("sd_sample_ce", phy.sdpads.data_i_ce),
                              ("sd_cmd_event", self.sdcard.core.cmd_event.status),
                              ("sd_data_event", self.sdcard.core.data_event.status)]
            self.debug_ila = BreezeDebugILA(self.cpu, platform, clock_hz=sys_clk_freq, extra_sources=sd_probes)


def main():
    parser = argparse.ArgumentParser(
        description="Build the fixed Breeze/KCU105 LiteX SoC.")
    parser.add_argument("--cpu-type", choices=("breeze", "breeze-tiny"),
                        default="breeze", help="four-hart or single-hart Linux cluster")
    parser.add_argument("--sys-clk-freq", type=int, choices=(50_000_000, 100_000_000),
                        default=SYS_CLK_FREQ, help="system frequency in Hz (default: 50000000)")
    parser.add_argument("--output-dir", default=None,
                        help="override the CPU-specific build directory")
    parser.add_argument("--debug", action="store_true",
                        help="generate single-hart Tandem RTL and add a native Vivado ILA")
    parser.add_argument("--with-sdcard", action="store_true",
                        help="native SD, coherent DMA, bounded BIOS driver and SD/DMA ILA probes")
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
    if args.debug and args.cpu_type != "breeze-tiny":
        parser.error("--debug only supports --cpu-type breeze-tiny (single hart)")

    if (args.debug or args.with_sdcard) and not args.load:
        profile = "single" if args.cpu_type == "breeze-tiny" else "small"
        mode = "fpga-debug" if args.debug else "production"
        dma_arg = " coherent-dma" if args.with_sdcard else ""
        subprocess.run([
            "sbt", "runMain flow.top.GenerateBreezeMulticoreClusterWishbone "
            f"{profile} gshare linux {mode}{dma_arg}",
        ], cwd=os.path.join(FLOW_ROOT, "design"), check=True)

    soc = BreezeKCU105SoC(cpu_type=args.cpu_type, debug=args.debug,
                          sys_clk_freq=args.sys_clk_freq, with_sdcard=args.with_sdcard)
    output_dir = args.output_dir or (DEBUG_BUILD_DIR if args.debug else
        TINY_BUILD_DIR if args.cpu_type == "breeze-tiny" else BUILD_DIR)
    if args.output_dir is None and args.sys_clk_freq != SYS_CLK_FREQ:
        output_dir += f"-{args.sys_clk_freq // 1_000_000}mhz"
    if args.output_dir is None and args.with_sdcard:
        output_dir += "-sd-dma"
    builder = (SnapshotBuilder if args.with_sdcard else Builder)(
        soc,
        output_dir=output_dir,
        csr_csv=os.path.join(output_dir, "csr.csv"),
        csr_json=os.path.join(output_dir, "csr.json"),
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

    if args.debug:
        soc.debug_ila.write_probe_map(os.path.join(output_dir, "ila-probes.json"))

    if args.load:
        programmer = soc.platform.create_programmer()
        programmer.load_bitstream(builder.get_bitstream_filename(mode="sram"))


if __name__ == "__main__":
    main()
