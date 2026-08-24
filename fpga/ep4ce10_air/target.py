#!/usr/bin/env python3
"""Independent LiteX/Quartus target for Air-I and Air-IC on EP4CE10."""

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
from air import Air  # noqa: E402

SYS_CLK_FREQ = 50_000_000
ROM_BASE = 0x1000_0000
ROM_SIZE = 0x2000
SRAM_BASE = 0x1100_0000
SRAM_SIZE = 0x2000


class GatewareOnlyBuilder(Builder):
    def _generate_includes(self, with_bios=True):
        del with_bios
        return super()._generate_includes(with_bios=False)


_io = [
    ("clk50", 0, Pins("E1"), IOStandard("3.3-V LVTTL")),
    ("rst_n", 0, Pins("M1"), IOStandard("3.3-V LVTTL")),
    ("led", 0, Pins("D11"), IOStandard("3.3-V LVTTL")),
    ("serial", 0,
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
    def __init__(self, size, init=None, read_only=False):
        self.bus = wishbone.Interface(data_width=32, address_width=30,
            addressing="word")
        memory = Memory(32, size // 4, init=init or [])
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
        self.sync += If(pending, pending.eq(0)).Elif(request, pending.eq(1))


class AirSoC(SoCCore):
    mem_map = Air.mem_map
    csr_map = {"ctrl": 0, "uart": 1, "timer0": 2}

    def __init__(self, platform, rom_init, cpu_variant):
        self.submodules.crg = CRG(platform)
        super().__init__(
            platform,
            clk_freq=SYS_CLK_FREQ,
            ident="",
            cpu_type="air",
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
        self.comb += platform.request("led", 0).eq(self.cpu.area_probe)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", choices=("i", "ic"), default="i")
    parser.add_argument("--firmware", default=None)
    args = parser.parse_args()
    if args.firmware is None:
        args.firmware = os.path.join(FLOW_ROOT, "software", "air-ep4ce10",
            "build", args.variant, "air-ep4ce10.bin")
    if os.path.getsize(args.firmware) > ROM_SIZE:
        raise ValueError("Air firmware exceeds the 8 KiB boot ROM")
    rom_init = get_mem_data(args.firmware, data_width=32,
        endianness="little", mem_size=ROM_SIZE + 1)

    CPUS["air"] = Air
    platform = Platform()
    soc = AirSoC(platform, rom_init=rom_init, cpu_variant=args.variant)
    output_name = f"ep4ce10-air-{args.variant}"
    output_dir = os.path.join(FLOW_ROOT, "build", output_name)
    gateware_dir = os.path.join(output_dir, "gateware")
    build_name = f"air_ep4ce10_{args.variant}"
    builder = GatewareOnlyBuilder(soc, output_dir=output_dir,
        compile_software=False, compile_gateware=False)
    builder.build(run=False, build_name=build_name)

    qsf_path = os.path.join(gateware_dir, build_name + ".qsf")
    with open(qsf_path, encoding="utf-8") as stream:
        qsf = stream.read()
    for rtl_src in soc.cpu.rtl_sources:
        rtl_name = os.path.basename(rtl_src)
        shutil.copy2(rtl_src, os.path.join(gateware_dir, rtl_name))
        qsf = qsf.replace(rtl_src, rtl_name)
    qsf = qsf.replace(os.path.join(gateware_dir, build_name + ".v"), build_name + ".v")
    with open(qsf_path, "w", encoding="utf-8") as stream:
        stream.write(qsf)
    print(f"[AIR-EP4CE10] variant={args.variant} "
          f"firmware={os.path.getsize(args.firmware)} bytes project={gateware_dir}")


if __name__ == "__main__":
    main()
