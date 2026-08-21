"""Thin LiteX wrapper around the standalone FlowPlic SystemVerilog RTL."""

import os

from migen import ClockSignal, Instance, Module, ResetSignal, Signal
from litex.soc.interconnect import wishbone


class BreezePlicVerilog(Module):
    """Expose FlowPlic through the same Python-level contract as BreezePlic."""

    def __init__(self, platform, num_harts, num_sources=31):
        num_harts = int(num_harts)
        num_sources = int(num_sources)
        if num_harts < 1 or num_sources < 1 or num_sources > 31:
            raise ValueError("FlowPlic supports 1+ harts and 1..31 sources")

        self.bus = bus = wishbone.Interface(
            data_width=64, address_width=32, addressing="word")
        self.sources = Signal(num_sources)
        self.meip = Signal(num_harts)
        self.seip = Signal(num_harts)

        num_contexts = 2 * num_harts
        self.pending_bits = Signal(num_sources + 1)
        debug_claims = Signal(num_contexts * 32)
        self.claims = [Signal(32, name=f"flow_plic_claim{context}")
                       for context in range(num_contexts)]
        self.debug_priorities = Signal(num_sources * 3)
        self.debug_enables = Signal(num_contexts * 32)
        self.debug_thresholds = Signal(num_contexts * 3)
        for context, claim in enumerate(self.claims):
            self.comb += claim.eq(debug_claims[32*context:32*(context + 1)])

        rtl_path = os.path.join(
            os.path.dirname(os.path.abspath(__file__)), "rtl", "FlowPlic.sv")
        if not os.path.isfile(rtl_path):
            raise FileNotFoundError(f"missing FlowPlic RTL: {rtl_path}")
        platform.add_source(rtl_path)

        self.specials += Instance(
            "FlowPlic",
            p_NUM_HARTS=num_harts,
            p_NUM_SOURCES=num_sources,
            i_clk=ClockSignal("sys"),
            i_rst=ResetSignal("sys"),
            i_wb_adr=bus.adr,
            i_wb_dat_w=bus.dat_w,
            o_wb_dat_r=bus.dat_r,
            i_wb_sel=bus.sel,
            i_wb_cyc=bus.cyc,
            i_wb_stb=bus.stb,
            o_wb_ack=bus.ack,
            i_wb_we=bus.we,
            o_wb_err=bus.err,
            i_sources=self.sources,
            o_meip=self.meip,
            o_seip=self.seip,
            o_debug_pending=self.pending_bits,
            o_debug_claims=debug_claims,
            o_debug_priorities=self.debug_priorities,
            o_debug_enables=self.debug_enables,
            o_debug_thresholds=self.debug_thresholds,
        )
