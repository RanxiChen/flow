"""LiteX CSR/Wishbone wrapper for the Wisp matrix engine."""

import os

from migen import ClockSignal, Instance, Module, ResetSignal, Signal
from litex.soc.interconnect import wishbone
from litex.soc.interconnect.csr import AutoCSR, CSRField, CSRStatus, CSRStorage


class MatrixAccelerator(Module, AutoCSR):
    """Single-command asynchronous 4x4 INT8 matrix accelerator."""

    def __init__(self, platform):
        self.bus = wishbone.Interface(
            data_width=32, address_width=30, addressing="word")

        self.m = CSRStorage(3, name="m",
            description="Active output rows for this tile (1..4).")
        self.n = CSRStorage(3, name="n",
            description="Active output columns for this tile (1..4).")
        self.k = CSRStorage(9, name="k",
            description="Dot-product length (1..256).")
        self.a_base = CSRStorage(8, name="a_base",
            description="A-SPM starting row.")
        self.b_base = CSRStorage(8, name="b_base",
            description="B-SPM starting row.")
        self.c_base = CSRStorage(8, name="c_base",
            description="C-SPM starting output row.")
        self.control = CSRStorage(fields=[
            CSRField("start", pulse=True,
                description="Submit the configured tile command."),
        ], name="control")
        self.status = CSRStatus(fields=[
            CSRField("busy", description="The engine owns the SPMs."),
            CSRField("done", description="All results are visible in C-SPM."),
            CSRField("error", description="Invalid command or start while busy."),
        ], name="status")

        busy = Signal()
        done = Signal()
        error = Signal()
        self.comb += [
            self.status.fields.busy.eq(busy),
            self.status.fields.done.eq(done),
            self.status.fields.error.eq(error),
        ]

        rtl_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__)))), "design", "build", "rtl",
            "matrix-engine-m9k")
        manifest = os.path.join(rtl_dir, "filelist.f")
        if not os.path.isfile(manifest):
            raise FileNotFoundError(
                f"Generate matrix RTL first: missing {manifest}")
        with open(manifest, encoding="utf-8") as stream:
            self.rtl_sources = [os.path.join(rtl_dir, line.strip())
                for line in stream if line.strip()]
        for rtl in self.rtl_sources:
            platform.add_source(rtl)

        self.specials += Instance("MatrixEngine",
            i_clock=ClockSignal("sys"),
            i_reset=ResetSignal("sys"),
            i_io_start=self.control.fields.start,
            i_io_m=self.m.storage,
            i_io_n=self.n.storage,
            i_io_k=self.k.storage,
            i_io_aBase=self.a_base.storage,
            i_io_bBase=self.b_base.storage,
            i_io_cBase=self.c_base.storage,
            o_io_busy=busy,
            o_io_done=done,
            o_io_error=error,
            i_io_spm_cyc=self.bus.cyc,
            i_io_spm_stb=self.bus.stb,
            i_io_spm_we=self.bus.we,
            i_io_spm_adr=self.bus.adr,
            i_io_spm_dat_w=self.bus.dat_w,
            i_io_spm_sel=self.bus.sel,
            i_io_spm_cti=self.bus.cti,
            i_io_spm_bte=self.bus.bte,
            o_io_spm_ack=self.bus.ack,
            o_io_spm_err=self.bus.err,
            o_io_spm_dat_r=self.bus.dat_r,
        )
