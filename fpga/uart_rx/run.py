#!/usr/bin/python3
import os
import sys
from pathlib import Path
import shutil
from migen import *
from litex.soc.integration.soc_core import *
from litex.gen import *
from litex.soc.cores.clock import *
from litex.soc.integration.soc_core import *
from litex.soc.integration.builder import *
from litex.soc.cores.led import LedChaser

from litedram.modules import EDY4016A
from litedram.phy import usddrphy

from liteeth.phy.ku_1000basex import KU_1000BASEX

from litepcie.phy.uspciephy import USPCIEPHY
from litepcie.software import generate_litepcie_software

# migen module
from litex.build.generic_platform import *
from litex.build.xilinx import XilinxUSPlatform, VivadoProgrammer
_io = [
    # Clk / Rst
    ("clk125", 0,
        Subsignal("p", Pins("G10"), IOStandard("LVDS")),
        Subsignal("n", Pins("F10"), IOStandard("LVDS"))
    ),
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
    ("user_led", 7, Pins("P23"), IOStandard("LVCMOS18")),
    ("serial", 0,
        Subsignal("cts", Pins("L23")),
        Subsignal("rts", Pins("K27")),
        Subsignal("tx",  Pins("K26")),
        Subsignal("rx",  Pins("G25")),
        IOStandard("LVCMOS18")
    )
]
class Platform(XilinxUSPlatform):
    default_clk_name   = "clk125"
    default_clk_period = 1e9/125e6

    def __init__(self, toolchain="vivado"):
        XilinxUSPlatform.__init__(self, "xcku040-ffva1156-2-e", _io,[], toolchain=toolchain)
    
    def create_programmer(self):
        return VivadoProgrammer()
    
class CRG(LiteXModule):
    def __init__(self, platform, sys_clk_freq):
        self.rst       = Signal()
        self.cd_sys    = ClockDomain()
        self.cd_sys4x  = ClockDomain()
        self.cd_pll4x  = ClockDomain()
        self.cd_idelay = ClockDomain()
        self.cd_eth    = ClockDomain()

        # # #

        self.pll = pll = USMMCM(speedgrade=-2)
        self.comb += pll.reset.eq(platform.request("cpu_reset") | self.rst)
        pll.register_clkin(platform.request("clk125"), 125e6)
        pll.create_clkout(self.cd_pll4x, sys_clk_freq*4, buf=None, with_reset=False)
        pll.create_clkout(self.cd_idelay, 200e6)
        pll.create_clkout(self.cd_eth,    200e6)
        platform.add_false_path_constraints(self.cd_sys.clk, pll.clkin) # Ignore sys_clk to pll.clkin path created by SoC's rst.

        self.specials += [
            Instance("BUFGCE_DIV",
                p_BUFGCE_DIVIDE=4,
                i_CE=1, i_I=self.cd_pll4x.clk, o_O=self.cd_sys.clk),
            Instance("BUFGCE",
                i_CE=1, i_I=self.cd_pll4x.clk, o_O=self.cd_sys4x.clk),
        ]

        self.idelayctrl = USIDELAYCTRL(cd_ref=self.cd_idelay, cd_sys=self.cd_sys)

class Top(SoCCore):
    def __init__(self, platform):
        SoCCore.__init__(self, platform, 125e6)
        #uart_tx_pin = platform.request("ctrl_uart_tx")
        uart_rx_pin = platform.request("ctrl_uart_rx")
        led_pins = platform.request_all("user_led")
        signal_index = Signal(4)
        signal_state = Signal()
        self.crg = CRG(platform, 125e6)
        self.add_uartbone()
        self.specials += Instance("UartRx",
                                i_clock = ClockSignal("sys"),
                                i_reset = ResetSignal("sys"),
                                o_io_led = led_pins,
                                i_io_rxd = uart_rx_pin,
                                o_io_index = signal_index,
                                o_io_state = signal_state

        )
        platform.add_sources(v_path,
                             "Rx.sv",
                             "UartRx.sv"
                             )
        analyzer_signals = [
            signal_index,
            signal_state,
            uart_rx_pin
        ]
        from litescope import LiteScopeAnalyzer
        self.submodules.analyzer = LiteScopeAnalyzer(
            analyzer_signals,
            depth        = 512,
            clock_domain ="sys",
            csr_csv      = "analyzer.csv"
        )
        self.add_csr("analyzer")
        
    
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
    print("Building UART Rx example...")
    os.chdir(chisel_root_path)
    os.system("sbt 'runMain fase.UartMain 2 '")
    os.chdir(run_path)


def main():
    print("UART Rx Example")
    print("current path:", v_path)
    import argparse
    parser = argparse.ArgumentParser(
        description="Uart Rx Example"
    )
    parser.add_argument(
        "--fire",
        action="store_true",
        help="Build the UART Rx example",
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
