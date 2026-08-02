"""LiteX CPU integration wrapper for BreezeCore.

This module only adapts generated RTL to LiteX's CPU API. Generate the RTL
separately with ``cd design && sbt elaborate`` before constructing a SoC.
"""

import os
from migen import ClockSignal, Constant, Instance, ResetSignal, Signal

from litex.soc.interconnect import wishbone

from litex.soc.cores.cpu import CPU, CPU_GCC_TRIPLE_RISCV64

# Variants -----------------------------------------------------------------------------------------

CPU_VARIANTS = ["minimal"]

# GCC Flags ----------------------------------------------------------------------------------------

GCC_FLAGS = {
    #                               /------------ Base ISA
    #                               |    /------- Hardware Multiply + Divide
    #                               |    |/----- Atomics
    #                               |    ||/---- Compressed ISA
    #                               |    |||/--- Single-Precision Floating-Point
    #                               |    ||||/-- Double-Precision Floating-Point
    #                               i    macfd
    "minimal":          "-march=rv64i2p0       -mabi=lp64 "
}

class Flow(CPU):
    category             = "softcore"
    family               = "riscv"
    name                 = "flow"
    human_name           = "Flow"
    variants             = CPU_VARIANTS
    data_width           = 64
    endianness           = "little"
    gcc_triple           = CPU_GCC_TRIPLE_RISCV64
    linker_output_format = "elf64-littleriscv"
    nop                  = "nop"
    mem_map              = {
        "rom"      : 0x1000_0000,
        "sram"     : 0x1100_0000,
        "csr"      : 0x1200_0000,
        "main_ram" : 0x8000_0000,
    }
    io_regions           = {
        0x0200_0000: 0x0001_0000,  # Machine timer.
        0x1200_0000: 0x0100_0000,  # LiteX MMIO window.
    }
    # GCC Flags.
    @property
    def gcc_flags(self):
        flags = "-mno-save-restore "
        flags += GCC_FLAGS[self.variant]
        flags += " -D__flow__ "
        flags += "-mcmodel=medany"
        return flags

    def __init__(self, platform, variant="minimal"):
        self.platform     = platform
        self.variant      = variant
        self.human_name   = f"Flow-{variant.upper()}"
        self.reset        = Signal()
        self.ibus         = ibus = wishbone.Interface(
            data_width=64, address_width=32, addressing="word")
        self.dbus         = dbus = wishbone.Interface(
            data_width=64, address_width=32, addressing="word")
        self.periph_buses = [ibus, dbus] # Independent instruction/data masters.
        self.memory_buses = [] # No bus bypasses the shared LiteX interconnect.
        self.dcache_fatal_error = Signal()
        self.interrupt = Signal(8)
        self.mtip = Signal()

        self.cpu_params = dict(
            # Clk / Rst.
            i_clock = ClockSignal("sys"),
            i_reset = ResetSignal("sys") | self.reset,
            i_io_machineTimerInterrupt = self.mtip,
            i_io_externalInterrupts    = self.interrupt,

            # Instruction Wishbone master.
            o_io_iWishbone_adr   = ibus.adr,
            o_io_iWishbone_dat_w = ibus.dat_w,
            i_io_iWishbone_dat_r = ibus.dat_r,
            o_io_iWishbone_sel   = ibus.sel,
            o_io_iWishbone_cyc   = ibus.cyc,
            o_io_iWishbone_stb   = ibus.stb,
            i_io_iWishbone_ack   = ibus.ack,
            o_io_iWishbone_we    = ibus.we,
            o_io_iWishbone_cti   = ibus.cti,
            o_io_iWishbone_bte   = ibus.bte,
            i_io_iWishbone_err   = ibus.err,

            # Data Wishbone master.
            o_io_dWishbone_adr   = dbus.adr,
            o_io_dWishbone_dat_w = dbus.dat_w,
            i_io_dWishbone_dat_r = dbus.dat_r,
            o_io_dWishbone_sel   = dbus.sel,
            o_io_dWishbone_cyc   = dbus.cyc,
            o_io_dWishbone_stb   = dbus.stb,
            i_io_dWishbone_ack   = dbus.ack,
            o_io_dWishbone_we    = dbus.we,
            o_io_dWishbone_cti   = dbus.cti,
            o_io_dWishbone_bte   = dbus.bte,
            i_io_dWishbone_err   = dbus.err,
            o_io_dcacheFatalError = self.dcache_fatal_error,
            i_io_resetAddr = Constant(0, 64),
        )

        self.add_sources(platform)

    def set_reset_address(self, reset_address):
        self.reset_address = reset_address
        self.cpu_params.update(i_io_resetAddr=Constant(reset_address, 64))

    @staticmethod
    def add_sources(platform):
        current_dir = os.path.dirname(os.path.abspath(__file__))
        flow_root_dir = os.path.dirname(os.path.dirname(current_dir))
        rtl_file = os.path.join(
            flow_root_dir, "design", "build", "rtl", "BreezeCoreWishbone.sv")
        if not os.path.exists(rtl_file):
            raise FileNotFoundError(
                "BreezeCore RTL has not been elaborated. Expected:\n"
                f"  {rtl_file}\n"
                "Generate it with:\n"
                f"  cd {os.path.join(flow_root_dir, 'design')} && sbt elaborate"
            )

        platform.add_source(rtl_file)

    def do_finalize(self):
        assert hasattr(self, "reset_address")
        self.specials += Instance("BreezeCoreWishbone", **self.cpu_params)
