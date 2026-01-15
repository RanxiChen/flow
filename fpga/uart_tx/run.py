#!/usr/bin/python3
import os
import sys
from pathlib import Path
import shutil
from migen import *

# migen module
from litex.build.generic_platform import *
from litex.build.xilinx import XilinxUSPlatform, VivadoProgrammer
_io = [
    # Clk / Rst
    ("clk125_p", 0, Pins("G10"), IOStandard("LVDS")),
    ("clk125_n", 0, Pins("F10"), IOStandard("LVDS")),
    ("cpu_reset", 0, Pins("AN8"), IOStandard("LVCMOS18")),
    ("ctrl_uart_tx",0,Pins("AM16"),IOStandard("LVCMOS12")),
    ("ctrl_uart_rx",0,Pins("AL14"),IOStandard("LVCMOS12"))
]
class Platform(XilinxUSPlatform):
    default_clk_name   = "clk125"
    default_clk_period = 1e9/125e6

    def __init__(self, toolchain="vivado"):
        XilinxUSPlatform.__init__(self, "xcku040-ffva1156-2-e", _io,[], toolchain=toolchain)
    
    def create_programmer(self):
        return VivadoProgrammer()
    
class CRG(Module):
    def __init__(self, platform):
        self.clock_domains.cd_sys = ClockDomain()
        # clk125
        clk125_p = platform.request("clk125_p")
        clk125_n = platform.request("clk125_n")
        clk125_bufg = Signal()
        self.specials += Instance("IBUFDS",
            i_I=clk125_p,
            i_IB=clk125_n,
            o_O=clk125_bufg
        )
        # sys_clk
        self.specials += Instance("BUFG",
            i_I=clk125_bufg,
            o_O=self.cd_sys.clk
        )
        # reset
        reset_btn = platform.request("cpu_reset")
        self.comb += self.cd_sys.rst.eq(reset_btn)

class Top(Module):
    def __init__(self, platform):
        uart_tx_pin = platform.request("ctrl_uart_tx")
        self.submodules.crg = CRG(platform)
        self.specials += Instance("SecTx",
                                i_clock = ClockSignal("sys"),
                                i_reset = ResetSignal("sys"),
                                o_io_txd = uart_tx_pin

        )
        platform.add_sources(v_path,
                             "Tx.sv",
                             "SecTx.sv"
                             )
    
def build_fpga():
    platform = Platform()
    top = Top(platform)
    platform.build(top,run=True)

        



# File Process
v_path = Path.cwd().parent.parent / "design" / "build"
chisel_root_path = Path.cwd().parent.parent / "design"
run_path = Path.cwd()
def check_build_clean():
    if v_path.exists():
        if v_path.is_dir():
            if any(v_path.iterdir()):
                return False
            else:
                return True
        else:
            return False
    else:
        return True
def clean():
    if check_build_clean():
        print("No previous build files to clean.")
        return
    else:
        print("Cleaning previous runs...")
        shutil.rmtree(v_path, ignore_errors=True)

def build():
    print("Building UART TX example...")
    os.chdir(chisel_root_path)
    os.system("sbt 'runMain fase.UartMain 1 '")
    os.chdir(run_path)


def main():
    print("UART TX Example")
    print("current path:", v_path)
    import argparse
    parser = argparse.ArgumentParser(
        description="Uart TX Example"
    )
    parser.add_argument(
        "--fire",
        action="store_true",
        help="Build the UART TX example",
    )
    parser.add_argument(
        "--build",
        action="store_true",
        help="Generate the bitstream to FPGA",
    )
    
    parser.add_argument(
        "--clean",
        action="store_true",
        help="Clean previous build files",
    )
    args = parser.parse_args()
    if args.clean:
        clean()
    if args.fire:
        build()
    if args.build:
        build_fpga()
    

if __name__ == "__main__":
    main()
