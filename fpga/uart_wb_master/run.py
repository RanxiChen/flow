#!/usr/bin/env python3

import os
from pathlib import Path
from migen import *
import argparse

from litex.gen import *

from litex_boards.platforms import xilinx_kcu105

from litex.soc.cores.clock import *
from litex.soc.integration.soc_core import *
from litex.soc.integration.builder import *
from litex.soc.cores.led import LedChaser

from litedram.modules import EDY4016A
from litedram.phy import usddrphy

from liteeth.phy.ku_1000basex import KU_1000BASEX

from litepcie.phy.uspciephy import USPCIEPHY
from litepcie.software import generate_litepcie_software
from litex_boards.targets.xilinx_kcu105 import _CRG
from litex.soc.integration.soc_core import *
from litex.soc.integration.builder import *
from litex.build.generic_platform import *
from litex.build.xilinx import XilinxUSPlatform, VivadoProgrammer
from litex.soc.interconnect import wishbone
from litex.soc.interconnect.csr import CSRStatus

v_path = Path.cwd().parent.parent / "design" / "build"
chisel_root_path = Path.cwd().parent.parent / "design"
run_path = Path.cwd()

class WBWrapper(Module,AutoCSR):
    def __init__(self,platform):
        self.wb_bus = wishbone.Interface(data_width=32, addr_width=32)
        uart_rx = platform.request("ctrl_uart_rx")
        uart_tx = platform.request("ctrl_uart_tx")
        self.addr_port = Signal(32)
        self.data_port = Signal(32)
        self.state_port = Signal(2)
        self.byte_len = Signal(2)
        self.rx_fire = Signal()
        self.tx_fire = Signal()
        self.rx_data = Signal(8)
        self.uart_state = CSRStatus(32)
        self.index = CSRStatus(32)
        self.addr_reg = CSRStatus(32)
        self.data_reg = CSRStatus(32)
        self.specials += Instance("uart_wb_master",
            i_clock=ClockSignal("sys"),
            i_reset=ResetSignal("sys"),
            i_io_rxd = uart_rx,
            o_io_txd = uart_tx,
            o_io_wb_adr = self.wb_bus.adr,
            o_io_wb_dat_w = self.wb_bus.dat_w,
            i_io_wb_dat_r = self.wb_bus.dat_r,
            o_io_wb_sel = self.wb_bus.sel,
            o_io_wb_we = self.wb_bus.we,
            o_io_wb_stb = self.wb_bus.stb,
            i_io_wb_ack = self.wb_bus.ack,
            o_io_wb_cyc = self.wb_bus.cyc,
            o_io_wb_cti = self.wb_bus.cti,
            o_io_wb_bte = self.wb_bus.bte,
            i_io_wb_err = self.wb_bus.err,
            o_io_addr_buffer = self.addr_port,
            o_io_data_buffer = self.data_port,
            o_io_state = self.state_port,
            o_io_byte_len = self.byte_len,
            o_io_rx_fire = self.rx_fire,
            o_io_tx_fire = self.tx_fire,
            o_io_rx_data = self.rx_data
        )
        platform.add_sources(v_path,
                                "uart_wb_master.sv",
                                "Rx.sv",
                                "Tx.sv"                            
        )
        self.sync += [
            self.uart_state.status.eq(self.state_port),
            self.index.status.eq(self.byte_len),
            self.addr_reg.status.eq(self.addr_port),
            self.data_reg.status.eq(self.data_port)
        ]


                                  
        

class BaseSoC(SoCMini):
    def __init__(self):
        platform = xilinx_kcu105.Platform()
        platform.add_extension([
            ("ctrl_uart_tx",0,Pins("AM16"),IOStandard("LVCMOS12")),
            ("ctrl_uart_rx",0,Pins("AL14"),IOStandard("LVCMOS12")),
        ])
        platform.add_platform_command("set_property UNAVAILABLE_DURING_CALIBRATION TRUE [get_ports ctrl_uart_rx]")
        platform.add_platform_command("set_property UNAVAILABLE_DURING_CALIBRATION TRUE [get_ports ctrl_uart_tx]")
        self.crg = _CRG(platform,125e6)
        SoCMini.__init__(self, platform, 125e6,
            ident="LiteX SoC on KCU105 with UART WB Master"
        )

        ## add ddr
        if False:
            self.ddrphy = usddrphy.USDDRPHY(platform.request("ddram"),
                    memtype          = "DDR4",
                    sys_clk_freq     = 125e6,
                    iodelay_clk_freq = 200e6)
            self.add_sdram("sdram",
                    phy           = self.ddrphy,
                    module        = EDY4016A(125e6, "1:4"),
                    size          = 0x40000000,
                    l2_cache_size = 8192
                    )
        self.add_uartbone()
        ## add wb wrapper
        uart_wb_master = WBWrapper(platform)
        self.submodules.uart_wb_wrapper = uart_wb_master
        self.add_csr("uart_wb_wrapper")
        self.bus.add_master(name="uart_wb_master",master=self.uart_wb_wrapper.wb_bus)
        analyzer_signals = [
            uart_wb_master.wb_bus,
            uart_wb_master.addr_port,
            uart_wb_master.data_port,
            uart_wb_master.state_port,
            uart_wb_master.byte_len,
            uart_wb_master.rx_fire,
            uart_wb_master.tx_fire,
            uart_wb_master.rx_data
        ]
        from litescope import LiteScopeAnalyzer
        self.submodules.analyzer = LiteScopeAnalyzer(analyzer_signals, 512, 
                                                     clock_domain ="sys",
            csr_csv="analyzer.csv"
        )
        self.add_csr("analyzer")


def fire():
    print("Building UART WB Master example...")
    os.chdir(chisel_root_path)
    os.system("sbt 'runMain wishbone.WBMain 1 '")
    os.chdir(run_path)


def main():
    parser = argparse.ArgumentParser(description="LiteX SoC with UART WB Master on KCU105")

    parser.add_argument("--build",       action="store_true", help="Build bitstream")
    parser.add_argument("--fire",        action="store_true", help="Generate Verilog Files")
    
    args = parser.parse_args()

    if args.fire:
        fire()


    soc = BaseSoC()

    builder = Builder(soc)

    builder.build(run=args.build)


if __name__ == "__main__":
    main()
