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
    "minimal":          "-march=rv64i2p0m       -mabi=lp64 "
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
    io_regions           = {0x8000_0000: 0x8000_0000} # Origin, Length.

    # GCC Flags.
    @property
    def gcc_flags(self):
        flags = ""
        flags +=  GCC_FLAGS[self.variant]
        flags += " -D__flow__ "
        return flags

    def __init__(self, platform, variant="minimal"):
        self.platform     = platform
        self.variant      = variant
        self.human_name   = f"Flow-{variant.upper()}"
        self.reset        = Signal()
        self.idbus        = idbus = wishbone.Interface(data_width=32, address_width=32)
        self.periph_buses = [idbus] # Peripheral buses (Connected to main SoC's bus).
        self.memory_buses = []      # Memory buses (Connected directly to LiteDRAM).

        
        self.cpu_params = dict(
            # Clk / Rst.
            i_clock   = ClockSignal("sys"),
            i_reset = ResetSignal("sys") | self.reset,

            # Wishbone Interface.
            o_io_bus_adr = idbus.adr,
            o_io_bus_dat_w = idbus.dat_w,
            i_io_bus_dat_r = idbus.dat_r,
            o_io_bus_sel = idbus.sel,
            o_io_bus_cyc = idbus.cyc,
            o_io_bus_stb = idbus.stb,
            i_io_bus_ack = idbus.ack,
            o_io_bus_we = idbus.we,
            o_io_bus_cti = idbus.cti,
            o_io_bus_bte = idbus.bte,
            i_io_bus_err = idbus.err,
            i_io_reset_addr = Constant(0, 64)
        )

        # Add Verilog sources.
        # --------------------
        self.add_sources(platform, variant)

    def set_reset_address(self, reset_address):
        self.reset_address = reset_address
        self.cpu_params.update(i_io_reset_addr=Constant(reset_address, 64))

    @staticmethod
    def add_sources(platform, variant):
        # Verilog sources.
        ## _ROOT_/generated will be replaced during Litex-Wrap generation.
        current_dir = os.path.dirname(os.path.abspath(__file__))
        flow_root_dir = os.path.dirname(os.path.dirname(current_dir))
        chisel_dir = os.path.join(flow_root_dir,"design")
        rtl_dir = os.path.join(flow_root_dir,"generated")
        print("Adding Flow core Verilog sources...")
        rtl_list = os.path.join(rtl_dir,"filelist.f")
        print(f"[FLOW] Reading RTL file list from :{rtl_list}")
        if not os.path.exists(rtl_list):
            #raise FileNotFoundError(f"Flow RTL file list not found: {rtl_list}")
            print(f"[FLOW] Warning: Flow RTL file list not found: {rtl_list}")
            print(f"[FLOW] Generate files in {chisel_dir}")
            try:
                subprocess.run(["sbt","runMain top.GenerateFlowTop"], cwd=chisel_dir, check=True)
                print(f"[FLOW] Flow RTL files generated successfully.")
            except Exception as e:
                raise RuntimeError(f"Failed to generate Flow RTL files: {e}")
            # cp generated files to rtl_dir
            if not os.path.exists(rtl_dir):
                os.makedirs(rtl_dir)
            print(f"[FLOW] Copying generated files to {rtl_dir}")
            for filename in os.listdir(os.path.join(chisel_dir,"build")):
                if filename.endswith(".v") or filename.endswith(".sv") or filename.endswith(".f"):
                    src_file = os.path.join(chisel_dir,"build",filename)
                    dst_file =  os.path.join(rtl_dir,filename)
                    import shutil
                    shutil.copy2(src_file,dst_file)
                    print(f"[FLOW] Copied {src_file} to {dst_file}")
            
        
        # Add all sources in filelist.f
        with open(rtl_list, "r") as f:
            for line in f:
                filename = line.strip()
                if not filename or filename.startswith("#") or filename.startswith("//"):
                    continue
                full_path = os.path.join(rtl_dir, filename)
                platform.add_source(full_path)
                print(f"[FLOW] Added source: {full_path}")


    def do_finalize(self):
        assert hasattr(self, "reset_address")
        self.specials += Instance("litex_flow_top", **self.cpu_params)
