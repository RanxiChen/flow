#!/usr/bin/python3
import os
from pathlib import Path

from litex.gen import LiteXModule
from litex.soc.interconnect.csr import CSRStorage, CSRStatus
from migen import *
import argparse
from litex_boards.platforms import xilinx_kcu105
from litex.soc.integration.soc_core import SoCCore
from litex_boards.targets.xilinx_kcu105 import _CRG
from litedram.modules import EDY4016A
from litedram.phy import usddrphy
from litepcie.phy.uspciephy import USPCIEPHY
from litex.soc.cores.led import LedChaser
from litex.soc.integration.soc_core import *
from litex.soc.integration.builder import *
from litepcie.software import generate_litepcie_software
from litepcie.frontend.wishbone import LitePCIeWishboneBridge
from litex.soc.interconnect import wishbone

##
#多余添加示例代码，本身add_pcie之后就可以使用
#
class FaseCtrlWrapper(LiteXModule):
    def __init__(self):
        self.inst = CSRStorage
        self.cpu_status = CSRStatus

class UartWBWrapper(LiteXModule):
    def __init__(self,platform):
        self.wb_bus = wishbone.Interface(data_width=32,address_width=32)


class FaseSoC(SoCCore):
    def __init__(self, sys_clk_freq=125e6, **kwargs):
        platform = xilinx_kcu105.Platform()
        self.crg = _CRG(platform, sys_clk_freq)
        SoCCore.__init__(self, platform, sys_clk_freq, ident="FASE soc based on LiteX(PCIe)", cpu_type=None)
        ## add ddr
        if False: # not self.integrated_main_ram_size:
            self.ddrphy = usddrphy.USDDRPHY(platform.request("ddram"),
                                            memtype="DDR4",
                                            sys_clk_freq=sys_clk_freq,
                                            iodelay_clk_freq=200e6)
            self.add_sdram("sdram",
                           phy=self.ddrphy,
                           module=EDY4016A(sys_clk_freq, "1:4"),
                           size=0x40000000,
                           l2_cache_size=kwargs.get("l2_size", 8192)
                           )

        # add pcie
        self.pcie_phy = USPCIEPHY(platform, platform.request("pcie_x4"),
                                  data_width=128,
                                  bar0_size=0x20000)
        self.add_pcie(phy=self.pcie_phy, ndmas=1)

        #add LED, watch whether cpu runs
        self.leds = LedChaser(
            pads=platform.request_all("user_led"),
            sys_clk_freq=sys_clk_freq
        )

def main():
    from litex.build.parser import LiteXArgumentParser
    parser = LiteXArgumentParser(platform=xilinx_kcu105.Platform, description="FASE based on LiteX SoC(KCU105)")
    parser.add_target_argument("--sys-clk-freq", default=125e6, type=float, help="System clock frequency.")
    parser.add_target_argument("--driver", action="store_true", help="Generate PCIe driver.")
    args = parser.parse_args()

    soc = FaseSoC(
        sys_clk_freq=args.sys_clk_freq,
        **parser.soc_argdict
    )
    builder = Builder(soc,**parser.builder_argdict)
    if args.build:
        builder.build(**parser.toolchain_argdict)

    if args.driver:
        generate_litepcie_software(soc, os.path.join(builder.output_dir, "driver"))

    if args.load:
        prog = soc.platform.create_programmer()
        prog.load_bitstream(builder.get_bitstream_filename(mode="sram"))


if __name__ == "__main__":
    main()


        
