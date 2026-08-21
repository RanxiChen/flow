"""Thin LiteX wrapper around the standalone FlowClint SystemVerilog RTL."""

import os

from migen import ClockSignal, Instance, Module, ResetSignal, Signal
from litex.soc.interconnect import wishbone


class BreezeClintVerilog(Module):
    """Expose FlowClint through the same contract as the legacy BreezeClint."""

    def __init__(
        self,
        platform,
        sys_clk_freq,
        timebase_freq,
        num_harts,
        region_size,
        msip_offset=0x0000,
        mtimecmp_offset=0x4000,
        mtime_offset=0xBFF8,
    ):
        sys_clk_freq = int(sys_clk_freq)
        timebase_freq = int(timebase_freq)
        num_harts = int(num_harts)
        region_size = int(region_size)
        msip_offset = int(msip_offset)
        mtimecmp_offset = int(mtimecmp_offset)
        mtime_offset = int(mtime_offset)

        if sys_clk_freq <= 0 or timebase_freq <= 0:
            raise ValueError("CLINT clock frequencies must be positive")
        if sys_clk_freq % timebase_freq:
            raise ValueError(
                "CLINT requires sys_clk_freq to be an integer multiple of timebase_freq")
        if num_harts < 1:
            raise ValueError("CLINT requires at least one hart")
        if region_size < 8 or (region_size & (region_size - 1)):
            raise ValueError("CLINT region size must be a power of two >= 8")
        if msip_offset % 8 or mtimecmp_offset % 8 or mtime_offset % 8:
            raise ValueError("CLINT register bases must be 64-bit aligned")
        region_end = region_size
        if msip_offset + 4*num_harts > region_end:
            raise ValueError("CLINT msip registers do not fit in the region")
        if mtimecmp_offset + 8*num_harts > region_end:
            raise ValueError("CLINT mtimecmp registers do not fit in the region")
        if mtime_offset + 8 > region_end:
            raise ValueError("CLINT mtime register does not fit in the region")

        self.bus = bus = wishbone.Interface(
            data_width=64, address_width=32, addressing="word")
        self.msip = Signal(num_harts)
        self.mtip = Signal(num_harts)
        self.mtime = Signal(64)
        self.debug_msip = Signal(num_harts)
        self.debug_mtimecmp = Signal(num_harts * 64)
        self.mtimecmp = [
            self.debug_mtimecmp[64*hart:64*(hart + 1)]
            for hart in range(num_harts)
        ]

        rtl_path = os.path.join(
            os.path.dirname(os.path.abspath(__file__)), "rtl", "FlowClint.sv")
        if not os.path.isfile(rtl_path):
            raise FileNotFoundError(f"missing FlowClint RTL: {rtl_path}")
        platform.add_source(rtl_path)

        self.specials += Instance(
            "FlowClint",
            p_NUM_HARTS=num_harts,
            p_SYS_CLK_FREQ=sys_clk_freq,
            p_TIMEBASE_FREQ=timebase_freq,
            p_REGION_BYTES=region_size,
            p_MSIP_OFFSET=msip_offset,
            p_MTIMECMP_OFFSET=mtimecmp_offset,
            p_MTIME_OFFSET=mtime_offset,
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
            o_msip=self.msip,
            o_mtip=self.mtip,
            o_mtime=self.mtime,
            o_debug_msip=self.debug_msip,
            o_debug_mtimecmp=self.debug_mtimecmp,
        )
