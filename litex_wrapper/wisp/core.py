"""LiteX CPU wrapper for the serialized Wisp RV64I core."""

import os

from migen import ClockSignal, Instance, ResetSignal, Signal
from litex.soc.cores.cpu import CPU, CPU_GCC_TRIPLE_RISCV64
from litex.soc.interconnect import wishbone


class Wisp(CPU):
    category = "softcore"
    family = "riscv"
    name = "wisp"
    human_name = "Wisp RV64I"
    variants = ["minimal", "matrix-area"]
    data_width = 32
    endianness = "little"
    gcc_triple = CPU_GCC_TRIPLE_RISCV64
    linker_output_format = "elf64-littleriscv"
    nop = "nop"
    mem_map = {
        "rom": 0x1000_0000,
        "sram": 0x1100_0000,
        "csr": 0x1200_0000,
        "main_ram": 0x8000_0000,
    }
    io_regions = {
        0x1200_0000: 0x0100_0000,
        0x1300_0000: 0x0100_0000,
    }

    @property
    def gcc_flags(self):
        return ("-mno-save-restore -march=rv64i_zicsr_zifencei -mabi=lp64 "
                "-mcmodel=medany -D__wisp__")

    def __init__(self, platform, variant="minimal"):
        self.platform = platform
        self.variant = variant
        self.reset = Signal()
        self.bus = wishbone.Interface(
            data_width=32, address_width=30, addressing="word")
        self.interrupt = Signal(32)
        self.timer_interrupt = Signal()
        self.external_interrupt = Signal()
        self.area_probe = Signal()
        self.periph_buses = [self.bus]
        self.memory_buses = []
        self.comb += [
            self.timer_interrupt.eq(self.interrupt[1]),
            self.external_interrupt.eq((self.interrupt & 0xffff_fffd) != 0),
        ]

        self.cpu_params = dict(
            i_clock=ClockSignal("sys"),
            i_reset=ResetSignal("sys") | self.reset,
            o_io_wb_cyc=self.bus.cyc,
            o_io_wb_stb=self.bus.stb,
            o_io_wb_we=self.bus.we,
            o_io_wb_adr=self.bus.adr,
            o_io_wb_dat_w=self.bus.dat_w,
            o_io_wb_sel=self.bus.sel,
            o_io_wb_cti=self.bus.cti,
            o_io_wb_bte=self.bus.bte,
            i_io_wb_ack=self.bus.ack,
            i_io_wb_err=self.bus.err,
            i_io_wb_dat_r=self.bus.dat_r,
            i_io_timerIrq=self.timer_interrupt,
            i_io_externalIrq=self.external_interrupt,
            o_io_areaProbe=self.area_probe,
        )
        rtl_profile = "wisp-matrix-area" if variant == "matrix-area" else "wisp"
        rtl_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__)))), "design", "build", "rtl", rtl_profile)
        manifest = os.path.join(rtl_dir, "filelist.f")
        if not os.path.isfile(manifest):
            raise FileNotFoundError(f"Generate Wisp RTL first: missing {manifest}")
        with open(manifest, encoding="utf-8") as stream:
            self.rtl_sources = [os.path.join(rtl_dir, line.strip())
                for line in stream if line.strip()]
        for rtl in self.rtl_sources:
            platform.add_source(rtl)

    def set_reset_address(self, reset_address):
        if reset_address != 0x1000_0000:
            raise ValueError(f"Wisp v0 RTL reset vector is fixed at 0x10000000, got 0x{reset_address:x}")
        self.reset_address = reset_address

    def do_finalize(self):
        assert hasattr(self, "reset_address")
        self.specials += Instance("WispCore", **self.cpu_params)
