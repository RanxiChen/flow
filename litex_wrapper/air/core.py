"""LiteX CPU wrapper for the independent Air-I/Air-IC area-first cores."""

import os

from migen import ClockSignal, Instance, ResetSignal, Signal
from litex.soc.cores.cpu import CPU, CPU_GCC_TRIPLE_RISCV64
from litex.soc.interconnect import wishbone


class Air(CPU):
    category = "softcore"
    family = "riscv"
    name = "air"
    human_name = "Air RV64I/IC"
    variants = ["i", "ic"]
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
        march = "rv64ic_zicsr_zifencei" if self.variant == "ic" else "rv64i_zicsr_zifencei"
        return (f"-mno-save-restore -march={march} -mabi=lp64 "
                "-mcmodel=medany -D__air__")

    def __init__(self, platform, variant="i"):
        if variant not in self.variants:
            raise ValueError(f"unsupported Air variant: {variant}")
        self.platform = platform
        self.variant = variant
        self.reset = Signal()
        self.bus = wishbone.Interface(
            data_width=32, address_width=30, addressing="word")
        self.area_probe = Signal()
        self.periph_buses = [self.bus]
        self.memory_buses = []

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
            o_io_areaProbe=self.area_probe,
        )

        profile = "air-ic" if variant == "ic" else "air-i"
        rtl_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__)))), "design", "build", "rtl", profile)
        manifest = os.path.join(rtl_dir, "filelist.f")
        if not os.path.isfile(manifest):
            raise FileNotFoundError(f"Generate Air RTL first: missing {manifest}")
        with open(manifest, encoding="utf-8") as stream:
            self.rtl_sources = [os.path.join(rtl_dir, line.strip())
                for line in stream if line.strip()]
        for rtl in self.rtl_sources:
            platform.add_source(rtl)

    def set_reset_address(self, reset_address):
        if reset_address != 0x1000_0000:
            raise ValueError(
                f"Air RTL reset vector is fixed at 0x10000000, got 0x{reset_address:x}")
        self.reset_address = reset_address

    def do_finalize(self):
        assert hasattr(self, "reset_address")
        self.specials += Instance("AirCore", **self.cpu_params)
