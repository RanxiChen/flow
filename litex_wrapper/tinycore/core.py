import os
import shutil
import subprocess

from migen import *
from litex.gen import *

from litex.soc.interconnect import wishbone
from litex.soc.cores.cpu import CPU, CPU_GCC_TRIPLE_RISCV64

CPU_VARIANTS = ["tiny"]

GCC_FLAGS = {
    "tiny": "-march=rv64i2p0 -mabi=lp64 "
}


class TinyCore(CPU):
    category             = "softcore"
    family               = "riscv"
    name                 = "tinycore"
    human_name           = "TinyCore"
    variants             = CPU_VARIANTS
    data_width           = 64
    endianness           = "little"
    gcc_triple           = CPU_GCC_TRIPLE_RISCV64
    linker_output_format = "elf64-littleriscv"
    nop                  = "nop"
    io_regions           = {0x8000_0000: 0x8000_0000}

    @property
    def mem_map(self):
        return {
            "rom":      0x0000_0000,
            "sram":     0x1000_0000,
            "main_ram": 0x4000_0000,
            "csr":      0xf000_0000,
        }

    @property
    def gcc_flags(self):
        flags = "-mno-save-restore "
        flags += GCC_FLAGS[self.variant]
        flags += "-D__tinycore__ "
        flags += "-mcmodel=medany"
        return flags

    def __init__(self, platform, variant="tiny"):
        self.platform   = platform
        self.variant    = variant
        self.human_name = f"TinyCore-{variant.upper()}"
        self.reset      = Signal()

        adr_width = 32 - log2_int(self.data_width // 8)
        self.ibus = ibus = wishbone.Interface(
            data_width = self.data_width,
            adr_width  = adr_width,
            addressing = "word",
        )
        self.dbus = dbus = wishbone.Interface(
            data_width = self.data_width,
            adr_width  = adr_width,
            addressing = "word",
        )
        self.periph_buses = [ibus, dbus]
        self.memory_buses = []

        self.cpu_params = dict(
            i_clock         = ClockSignal("sys"),
            i_reset         = ResetSignal("sys") | self.reset,
            i_io_reset_addr = Constant(0, 64),

            o_io_ibus_adr   = ibus.adr,
            o_io_ibus_dat_w = ibus.dat_w,
            i_io_ibus_dat_r = ibus.dat_r,
            o_io_ibus_sel   = ibus.sel,
            o_io_ibus_cyc   = ibus.cyc,
            o_io_ibus_stb   = ibus.stb,
            i_io_ibus_ack   = ibus.ack,
            o_io_ibus_we    = ibus.we,
            o_io_ibus_cti   = ibus.cti,
            o_io_ibus_bte   = ibus.bte,
            i_io_ibus_err   = ibus.err,

            o_io_dbus_adr   = dbus.adr,
            o_io_dbus_dat_w = dbus.dat_w,
            i_io_dbus_dat_r = dbus.dat_r,
            o_io_dbus_sel   = dbus.sel,
            o_io_dbus_cyc   = dbus.cyc,
            o_io_dbus_stb   = dbus.stb,
            i_io_dbus_ack   = dbus.ack,
            o_io_dbus_we    = dbus.we,
            o_io_dbus_cti   = dbus.cti,
            o_io_dbus_bte   = dbus.bte,
            i_io_dbus_err   = dbus.err,
        )

        self.add_sources(platform, variant)

    def set_reset_address(self, reset_address):
        self.reset_address = reset_address
        self.cpu_params.update(i_io_reset_addr=Constant(reset_address, 64))

    @staticmethod
    def add_sources(platform, variant):
        current_dir = os.path.dirname(os.path.abspath(__file__))
        flow_root   = os.path.dirname(os.path.dirname(current_dir))
        chisel_dir  = os.path.join(flow_root, "design")
        build_dir   = os.path.join(chisel_dir, "build", "tinycore")
        rtl_dir     = os.path.join(flow_root, "generated", "tinycore")

        os.makedirs(rtl_dir, exist_ok=True)

        sbt_cmd = [
            "sbt",
            "runMain flow.top.GenerateTinyCoreLitexTop",
        ]
        subprocess.run(sbt_cmd, cwd=chisel_dir, check=True)

        copied = []
        for filename in sorted(os.listdir(build_dir)):
            if filename.endswith((".sv", ".v")):
                src = os.path.join(build_dir, filename)
                dst = os.path.join(rtl_dir, filename)
                shutil.copy2(src, dst)
                copied.append(dst)

        if not copied:
            raise RuntimeError("TinyCore RTL generation did not produce any .v/.sv files")

        for source in copied:
            platform.add_source(source)

    def do_finalize(self):
        assert hasattr(self, "reset_address")
        self.specials += Instance("TinyCoreLitexTop", **self.cpu_params)
