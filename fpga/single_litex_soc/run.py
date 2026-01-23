## 
# This is one example of integrating Flow core into LiteX SoC.
# @boards: xilinx_kcu105
##

import os
import sys
from curses import wrapper

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

## import flow cpu
current_dir = os.path.dirname(os.path.abspath(__file__))
flow_root_path = os.path.dirname(os.path.dirname(current_dir))
wrapper_path = os.path.join(flow_root_path, "litex_wrapper")
sys.path.append(wrapper_path)

from litex.soc.cores.cpu import CPUS
from flow.core import Flow
CPUS["flow"] = Flow

class FlowCore(SoCCore):
    def __init__(self,sys_clk_freq=int(125e6), **kwargs):
        platform = xilinx_kcu105.Platform()

        kwargs.pop("cpu_type", None)
        kwargs.pop("cpu_variant", None)


        # SoCCore ----------------------------------------------------------------------------------
        SoCCore.__init__(self, platform, sys_clk_freq,
            ident          = "LiteX SoC with Flow CPU",
            cpu_type       = "flow",
            cpu_variant    = "minimal",
            #integrated_rom_size=0x8000,
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
    from litex.build.parser import LiteXArgumentParser
    parser = LiteXArgumentParser(platform=xilinx_kcu105.Platform, description="LiteX SoC on KCU105.")
    parser.add_target_argument("--sys-clk-freq", default=125e6, type=float, help="System clock frequency.")
    args = parser.parse_args()
    soc = FlowCore(sys_clk_freq=args.sys_clk_freq, **parser.soc_argdict)
    builder = Builder(soc, **parser.builder_argdict)
    if args.build:
        builder.build(**parser.toolchain_argdict)

    if args.load:
        prog = soc.platform.create_programmer()
        prog.load_bitstream(builder.get_bitstream_filename(mode="sram"))


if __name__ == "__main__":
    main()

