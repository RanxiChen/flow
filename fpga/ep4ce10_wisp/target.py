#!/usr/bin/env python3
"""LiteX-organized, firmware-booting Wisp MCU for ALIENTEK EP4CE10."""

import argparse
import os
import shutil
import sys

from migen import ClockDomain, If, Memory, Module, Signal
from migen.genlib.resetsync import AsyncResetSynchronizer
from litex.build.altera import AlteraPlatform
from litex.build.generic_platform import IOStandard, Pins, Subsignal
from litex.soc.cores.cpu import CPUS
from litex.soc.integration.builder import Builder
from litex.soc.integration.common import get_mem_data
from litex.soc.integration.soc import SoCRegion
from litex.soc.integration.soc_core import SoCCore
from litex.soc.interconnect import wishbone

THIS_DIR = os.path.dirname(os.path.abspath(__file__))
FLOW_ROOT = os.path.abspath(os.path.join(THIS_DIR, "..", ".."))
sys.path.insert(0, os.path.join(FLOW_ROOT, "litex_wrapper"))
from wisp import Wisp  # noqa: E402
from matrix_accelerator import MatrixAccelerator  # noqa: E402

SYS_CLK_FREQ = 50_000_000
ROM_BASE = 0x1000_0000
ROM_SIZE = 0x2000
SRAM_BASE = 0x1100_0000
SRAM_SIZE = 0x2000
MATRIX_SPM_BASE = 0x1300_0000
MATRIX_SPM_SIZE = 0x4000


class GatewareOnlyBuilder(Builder):
    def _generate_includes(self, with_bios=True):
        del with_bios
        return super()._generate_includes(with_bios=False)


_io = [
    ("clk50", 0, Pins("E1"), IOStandard("3.3-V LVTTL")),
    ("rst_n", 0, Pins("M1"), IOStandard("3.3-V LVTTL")),
    ("led", 0, Pins("D11"), IOStandard("3.3-V LVTTL")),
    ("serial", 0,
        # P2 direct TTL UART2 pins for an external USB-to-UART adapter.
        Subsignal("rx", Pins("A12")),
        Subsignal("tx", Pins("B12")),
        IOStandard("3.3-V LVTTL")),
]


class Platform(AlteraPlatform):
    default_clk_name = "clk50"
    default_clk_period = 1e9 / SYS_CLK_FREQ

    def __init__(self):
        super().__init__("EP4CE10F17C8", _io, toolchain="quartus")
        self.toolchain.additional_sdc_commands.append(
            "set_false_path -from [get_ports {rst_n}]")


class CRG(Module):
    def __init__(self, platform):
        self.clock_domains.cd_sys = ClockDomain("sys")
        clk = platform.request("clk50")
        rst_n = platform.request("rst_n")
        self.comb += self.cd_sys.clk.eq(clk)
        self.specials += AsyncResetSynchronizer(self.cd_sys, ~rst_n)
        platform.add_period_constraint(clk, 1e9 / SYS_CLK_FREQ)


class SyncWishboneRAM(Module):
    """One-cycle Wishbone RAM template that Quartus can map into M9Ks."""

    def __init__(self, size, init=None, read_only=False):
        self.bus = wishbone.Interface(data_width=32, address_width=30,
            addressing="word")
        depth = size // 4
        memory = Memory(32, depth, init=init or [])
        port = memory.get_port(write_capable=not read_only,
            we_granularity=8, has_re=True)
        self.specials += memory, port

        request = Signal()
        pending = Signal()
        self.comb += [
            request.eq(self.bus.cyc & self.bus.stb),
            port.adr.eq(self.bus.adr[:len(port.adr)]),
            port.re.eq(request & ~pending & ~self.bus.we),
            self.bus.dat_r.eq(port.dat_r),
            self.bus.ack.eq(pending),
            self.bus.err.eq(0),
        ]
        if not read_only:
            self.comb += [
                port.dat_w.eq(self.bus.dat_w),
                *[port.we[i].eq(request & ~pending & self.bus.we & self.bus.sel[i])
                  for i in range(4)],
            ]
        self.sync += If(pending,
            pending.eq(0)
        ).Elif(request,
            pending.eq(1)
        )


class WispSoC(SoCCore):
    mem_map = Wisp.mem_map
    csr_map = {"ctrl": 0, "uart": 1, "timer0": 2, "matrix": 3}

    def __init__(self, platform, rom_init, cpu_variant="minimal",
                 with_matrix=False):
        self.submodules.crg = CRG(platform)
        super().__init__(
            platform,
            clk_freq=SYS_CLK_FREQ,
            ident="",
            cpu_type="wisp",
            cpu_variant=cpu_variant,
            bus_standard="wishbone",
            bus_data_width=32,
            bus_address_width=32,
            bus_bursting=False,
            bus_interconnect="shared",
            cpu_reset_address=ROM_BASE,
            integrated_rom_size=0,
            integrated_sram_size=0,
            integrated_main_ram_size=0,
            with_ctrl=True,
            csr_paging=0x1000,
            with_uart=True,
            uart_name="serial",
            uart_baudrate=115200,
            with_timer=True,
        )
        self.submodules.rom = SyncWishboneRAM(ROM_SIZE, init=rom_init, read_only=True)
        self.bus.add_slave("rom", self.rom.bus,
            SoCRegion(origin=ROM_BASE, size=ROM_SIZE, mode="rx", cached=True))
        self.submodules.sram = SyncWishboneRAM(SRAM_SIZE)
        self.bus.add_slave("sram", self.sram.bus,
            SoCRegion(origin=SRAM_BASE, size=SRAM_SIZE, mode="rwx", cached=True))
        if with_matrix:
            self.submodules.matrix = MatrixAccelerator(platform)
            self.bus.add_slave("matrix_spm", self.matrix.bus,
                SoCRegion(origin=MATRIX_SPM_BASE, size=MATRIX_SPM_SIZE,
                    mode="rw", cached=False))
        # A visible heartbeat remains useful even before opening a serial terminal.
        self.comb += platform.request("led", 0).eq(self.cpu.area_probe)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--firmware", default=os.path.join(FLOW_ROOT,
        "software", "wisp-ep4ce10", "build", "wisp-ep4ce10.bin"))
    matrix_group = parser.add_mutually_exclusive_group()
    matrix_group.add_argument("--matrix-area", action="store_true",
        help="include the synthesis-only 4x4 INT8 systolic-array area probe")
    matrix_group.add_argument("--matrix", action="store_true",
        help="include the complete CSR/SPM asynchronous matrix engine")
    args = parser.parse_args()
    if os.path.getsize(args.firmware) > ROM_SIZE:
        raise ValueError("Wisp firmware exceeds the 8 KiB boot ROM")
    rom_init = get_mem_data(args.firmware, data_width=32,
        endianness="little", mem_size=ROM_SIZE + 1)
    CPUS["wisp"] = Wisp
    platform = Platform()
    cpu_variant = "matrix-area" if args.matrix_area else "minimal"
    soc = WispSoC(platform, rom_init=rom_init, cpu_variant=cpu_variant,
        with_matrix=args.matrix)
    output_name = ("ep4ce10-wisp-matrix" if args.matrix else
        ("ep4ce10-wisp-matrix-area" if args.matrix_area else "ep4ce10-wisp"))
    output_dir = os.path.join(FLOW_ROOT, "build", output_name)
    gateware_dir = os.path.join(output_dir, "gateware")
    builder = GatewareOnlyBuilder(soc, output_dir=output_dir,
        compile_software=False, compile_gateware=False)
    build_name = ("wisp_ep4ce10_matrix" if args.matrix else
        ("wisp_ep4ce10_matrix_area" if args.matrix_area else "wisp_ep4ce10"))
    builder.build(run=False, build_name=build_name)

    # Windows Quartus cannot open WSL /home paths: make every source local to
    # the generated project and rewrite the QSF accordingly.
    qsf_path = os.path.join(gateware_dir, build_name + ".qsf")
    with open(qsf_path, encoding="utf-8") as stream:
        qsf = stream.read()
    external_rtl_sources = list(soc.cpu.rtl_sources)
    if args.matrix:
        external_rtl_sources += soc.matrix.rtl_sources
    for rtl_src in external_rtl_sources:
        rtl_name = os.path.basename(rtl_src)
        shutil.copy2(rtl_src, os.path.join(gateware_dir, rtl_name))
        qsf = qsf.replace(rtl_src, rtl_name)
    qsf = qsf.replace(os.path.join(gateware_dir, build_name + ".v"), build_name + ".v")
    if args.matrix:
        qsf = ('set_global_assignment -name VERILOG_MACRO "MATRIX_QUARTUS=1"\n' +
            qsf)
    with open(qsf_path, "w", encoding="utf-8") as stream:
        stream.write(qsf)
    print(f"[WISP-EP4CE10] variant={cpu_variant} matrix={args.matrix} "
          f"firmware={os.path.getsize(args.firmware)} bytes "
          f"uart=0x12001000 project={gateware_dir}")


if __name__ == "__main__":
    main()
