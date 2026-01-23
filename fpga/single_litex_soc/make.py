## 
# This is one example of integrating Flow core into LiteX SoC.
# @boards: xilinx_kcu105
##

import os
from migen import *
from litex.soc.cores.cpu.femtorv import FemtoRV

from litex.soc.integration.soc_core import *
from litex.soc.integration.builder import *
from litex.soc.cores.led import LedChaser

## kcu105 platform
from litex_boards.platforms import xilinx_kcu105
from litex_boards.targets.xilinx_kcu105 import _CRG
from litedram.modules import EDY4016A
from litedram.phy import usddrphy

class FlowCore(SoCCore):
    def __init__(self,sys_clk_freq=int(125e6), **kwargs):
        platform = xilinx_kcu105.Platform()

        # SoCCore ----------------------------------------------------------------------------------
        SoCCore.__init__(self, platform, sys_clk_freq,
            ident          = "LiteX SoC with Flow CPU",
            cpu_type       = "femtorv",
            #cpu_variant    = "standard",
            integrated_rom_size=0x8000,
                         **kwargs)
        
        self.crg = _CRG(platform, sys_clk_freq)
        # DDR4 SDRAM -------------------------------------------------------------------------------
        if not self.integrated_main_ram_size:
            self.ddrphy = usddrphy.USDDRPHY(platform.request("ddram"),
                memtype          = "DDR4",
                sys_clk_freq     = sys_clk_freq,
                iodelay_clk_freq = 200e6)
            self.add_sdram("sdram",
                phy           = self.ddrphy,
                module        = EDY4016A(sys_clk_freq, "1:4"),
                size          = 0x40000000,
                l2_cache_size = kwargs.get("l2_size", 8192)
            )
        # Leds -------------------------------------------------------------------------------------
        if True:
            self.leds = LedChaser(
                pads         = platform.request_all("user_led"),
                sys_clk_freq = sys_clk_freq)
            
def main():
    import argparse
    parser = argparse.ArgumentParser(description="LiteX SoC with Flow CPU")
    parser.add_argument("--build",        action="store_true", help="Build bitstream.")
    parser.add_argument("--load",         action="store_true", help="Load bitstream.")
    parser.add_argument("--sys-clk-freq", default=125e6,      help="System clock frequency.")
    args = parser.parse_args()
    builder_args(parser)
    args = parser.parse_args()
    soc = FlowCore(
        sys_clk_freq      = int(float(args.sys_clk_freq)),
        **soc_core_argdict(args)
    )

    builder = Builder(soc, csr_csv="csr.csv")

    builder.build(run=args.build)

    if args.load:
        prog = soc.platform.create_programmer()
        prog.load_bitstream(os.path.join(builder.gateware_dir, soc.build_name + ".bit"))
        exit()

if __name__ == "__main__":
    main()

