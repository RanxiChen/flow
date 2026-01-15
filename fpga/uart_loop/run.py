#!/usr/bin/python3
import os
import sys
from pathlib import Path
import shutil
from migen import *
import serial

# migen module
from litex.build.generic_platform import *
from litex.build.xilinx import XilinxUSPlatform, VivadoProgrammer
_io = [
    # Clk / Rst
    ("clk125_p", 0, Pins("G10"), IOStandard("LVDS")),
    ("clk125_n", 0, Pins("F10"), IOStandard("LVDS")),
    ("cpu_reset", 0, Pins("AN8"), IOStandard("LVCMOS18")),
    ("ctrl_uart_tx",0,Pins("AM16"),IOStandard("LVCMOS12")),
    ("ctrl_uart_rx",0,Pins("AL14"),IOStandard("LVCMOS12")),
    ("user_led", 0, Pins("AP8"), IOStandard("LVCMOS18")),
    ("user_led", 1, Pins("H23"), IOStandard("LVCMOS18")),
    ("user_led", 2, Pins("P20"), IOStandard("LVCMOS18")),
    ("user_led", 3, Pins("P21"), IOStandard("LVCMOS18")),
    ("user_led", 4, Pins("N22"), IOStandard("LVCMOS18")),
    ("user_led", 5, Pins("M22"), IOStandard("LVCMOS18")),
    ("user_led", 6, Pins("R23"), IOStandard("LVCMOS18")),
    ("user_led", 7, Pins("P23"), IOStandard("LVCMOS18"))
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
        uart_rx_pin = platform.request("ctrl_uart_rx")
        signal_index = Signal(4)
        signal_state = Signal()
        signal_counter = Signal(16)
        signal_loop_state = Signal(2)
        signal_data = Signal(8)
        self.submodules.crg = CRG(platform)
        self.specials += Instance("UartLoop",
                                i_clock = ClockSignal("sys"),
                                i_reset = ResetSignal("sys"),
                                o_io_txd = uart_tx_pin,
                                i_io_rxd = uart_rx_pin,
                                o_io_rx_index = signal_index,
                                o_io_rx_state = signal_state,
                                o_io_counter = signal_counter,
                                o_io_loop_state = signal_loop_state,
                                o_io_data = signal_data

        )
        platform.add_sources(v_path,
                             "Tx.sv",
                             "Rx.sv",
                             "UartLoop.sv"
                             )
    
def build_fpga():
    platform = Platform()
    top = Top(platform)
    platform.build(top,run=False)

        



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
    print("Building UART Loop example...")
    os.chdir(chisel_root_path)
    os.system("sbt 'runMain fase.UartMain 3 '")
    os.chdir(run_path)

def run():
    ser = serial.Serial(
    port="/dev/ttyUSB2",   # Linux /dev/ttyUSB0 或 /dev/ttyACM0
    baudrate=115200,
    timeout=None           # None = 一直阻塞，直到有数据
    )
    print("Listening...")

    while True:
        data = ser.read(1)   # 读最多 64 字节
        if data:
            print("RX:", data)


def main():
    print("UART Loop Example")
    print("current path:", v_path)
    import argparse
    parser = argparse.ArgumentParser(
        description="Uart Loop Example"
    )
    parser.add_argument(
        "--fire",
        action="store_true",
        help="Build the UART Loop example",
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
    parser.add_argument(
        "--run",
        action="store_true",
        help="Run UART Loop test",
    )
    args = parser.parse_args()
    if args.clean:
        clean()
    if args.fire:
        build()
    if args.build:
        build_fpga()
    if args.run:
        run()
    

if __name__ == "__main__":
    main()
