##
# This file wrappers the Flow core for LiteX integration.
##
import os
import subprocess
from migen import *
from litex.gen import *

from litex.soc.interconnect import wishbone

from litex.soc.cores.cpu import CPU,CPU_GCC_TRIPLE_RISCV64

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
    io_regions           = {
        0x0200_0000: 0x0001_0000,  # Machine timer.
        0x1200_0000: 0x0100_0000,  # LiteX MMIO window.
    }

    # GCC Flags.
    @property
    def gcc_flags(self):
        flags = "-mno-save-restore "
        flags +=  GCC_FLAGS[self.variant]
        flags += " -D__flow__ "
        flags += "-mcmodel=medany"
        return flags

    def __init__(self, platform, variant="minimal"):
        self.platform     = platform
        self.variant      = variant
        self.human_name   = f"Flow-{variant.upper()}"
        self.reset        = Signal()
        self.ibus         = ibus = wishbone.Interface(data_width=64, address_width=32)
        self.dbus         = dbus = wishbone.Interface(data_width=64, address_width=32)
        self.periph_buses = [ibus, dbus] # Independent instruction/data masters.
        self.memory_buses = []      # Memory buses (Connected directly to LiteDRAM).
        self.dcache_fatal_error = Signal()

        
        self.cpu_params = dict(
            # Clk / Rst.
            i_clock   = ClockSignal("sys"),
            i_reset = ResetSignal("sys") | self.reset,

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
            i_io_resetAddr = Constant(0, 64)
        )

        # Add Verilog sources.
        # --------------------
        self.add_sources(platform, variant)

    def set_reset_address(self, reset_address):
        self.reset_address = reset_address
        self.cpu_params.update(i_io_resetAddr=Constant(reset_address, 64))

    @staticmethod
    def add_sources(platform, variant):
        # Verilog sources.
        ## _ROOT_/generated will be replaced during Litex-Wrap generation.
        current_dir = os.path.dirname(os.path.abspath(__file__))
        flow_root_dir = os.path.dirname(os.path.dirname(current_dir))
        chisel_dir = os.path.join(flow_root_dir,"design")
        rtl_dir = os.path.join(flow_root_dir,"generated")
        print("Adding Flow core Verilog sources...")
        rtl_file = os.path.join(rtl_dir, "BreezeCoreWishbone.sv")
        if not os.path.exists(rtl_file):
            print(f"[FLOW] RTL file not found: {rtl_file}")
            print(f"[FLOW] Generate files in {chisel_dir}")
            try:
                subprocess.run(
                    ["sbt", "runMain flow.top.GenerateBreezeCoreWishbone"],
                    cwd=chisel_dir,
                    check=True,
                )
                print(f"[FLOW] Flow RTL files generated successfully.")
            except Exception as e:
                raise RuntimeError(f"Failed to generate Flow RTL files: {e}")

            if not os.path.exists(rtl_dir):
                os.makedirs(rtl_dir)
            generated_file = os.path.join(chisel_dir, "build", "BreezeCoreWishbone.sv")
            if not os.path.exists(generated_file):
                raise RuntimeError(f"Generated Flow RTL not found: {generated_file}")

            import shutil
            shutil.copy2(generated_file, rtl_file)
            print(f"[FLOW] Copied {generated_file} to {rtl_file}")

        platform.add_source(rtl_file)
        print(f"[FLOW] Added source: {rtl_file}")


    def do_finalize(self):
        assert hasattr(self, "reset_address")
        self.specials += Instance("BreezeCoreWishbone", **self.cpu_params)
