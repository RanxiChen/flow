"""KCU105 clock tree with independent PCIe/software reset synchronization.

Clock topology follows LiteX-Boards' KCU105 _CRG (BSD-2-Clause,
Copyright 2018-2020 Florent Kermarrec). The reset supervisor is Flow-specific.
"""
from pathlib import Path
from migen import ClockDomain, ClockSignal, ResetSignal, Signal, Instance
from litex.gen import LiteXModule
from litex.soc.cores.clock import USMMCM, USIDELAYCTRL


class PcieCRG(LiteXModule):
    def __init__(self, platform, sys_clk_freq):
        self.rst = Signal()  # LiteX software SoC reset, never a PCIe fan-in.
        self.pcie_clk = Signal()
        self.pcie_reset_n = Signal()
        self.bridge_reset = Signal()
        self.cd_sys = ClockDomain("sys")
        self.cd_sys4x = ClockDomain("sys4x")
        self.cd_pll4x = ClockDomain("pll4x")
        self.cd_idelay = ClockDomain("idelay")
        self.cd_eth = ClockDomain("eth")
        self.pll = pll = USMMCM(speedgrade=-2)
        pll.register_clkin(platform.request("clk125"), 125e6)
        pll.create_clkout(self.cd_pll4x, sys_clk_freq*4, buf=None, with_reset=False)
        pll.create_clkout(self.cd_idelay, 200e6)
        pll.create_clkout(self.cd_eth, 200e6)
        self.specials += Instance("FlowPcieReset", name="pcie_reset_supervisor",
            i_ref_clk=pll.clkin, i_sys_clk=ClockSignal("sys"), i_pcie_clk=self.pcie_clk,
            i_pcie_reset_n=self.pcie_reset_n, i_button_reset=platform.request("cpu_reset"),
            i_soft_reset=self.rst, i_sys_reset=ResetSignal("sys"),
            o_pll_reset=pll.reset, o_bridge_reset=self.bridge_reset)
        # No blanket sys -> reference-clock false path: all requests use
        # named synchronizers and get endpoint-specific reset constraints.
        self.specials += [
            Instance("BUFGCE_DIV", p_BUFGCE_DIVIDE=4,
                i_CE=1, i_I=self.cd_pll4x.clk, o_O=self.cd_sys.clk),
            Instance("BUFGCE", i_CE=1, i_I=self.cd_pll4x.clk, o_O=self.cd_sys4x.clk),
        ]
        self.idelayctrl = USIDELAYCTRL(cd_ref=self.cd_idelay, cd_sys=self.cd_sys)
        platform.add_source(str(Path(__file__).resolve().parents[2] /
            "litex_wrapper/flow/rtl/FlowPcieReset.sv"))
