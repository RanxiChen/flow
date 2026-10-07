"""Ordered two-exit AXI router. Address authority is the Chisel PMA JSON.

One read and one write globally in flight; channels remain independent.
No new AR until RLAST is accepted, no new AW until B is accepted. W is
backpressured until an AW chooses its destination. Responses are transparent.
"""
import json
import os

from migen import Constant, FSM, If, Module, NextState, NextValue, Signal
from litex.soc.interconnect import axi, wishbone


def platform_regions(root=None):
    root = root or os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
    with open(os.path.join(root, "config", "breeze_mcu_platform.json"), encoding="utf-8") as f:
        config = json.load(f)
    return {r["name"]: {**r, "origin": int(r["origin"], 0), "size": int(r["size"], 0)}
            for r in config["regions"]}


class BreezeAxiRouter(Module):
    def __init__(self, master, regions=None):
        regions = platform_regions() if regions is None else regions
        self.dram = axi.AXIInterface(data_width=master.data_width,
            address_width=master.address_width, id_width=master.id_width)
        self.low = axi.AXIInterface(data_width=master.data_width,
            address_width=master.address_width, id_width=master.id_width)
        self.read_dram = Signal()
        self.write_dram = Signal()
        self.read_low = Signal()
        self.write_low = Signal()
        self.sources = [("router_" + name, getattr(self, name)) for name in
                        ("read_dram", "write_dram", "read_low", "write_low")]
        low_names = ("boot_rom", "linux_boot_rom", "sram")

        def decode(channel, write=False):
            last_addr = Signal(master.address_width + 1)
            beats = Signal(9)
            span = Signal(17)
            self.comb += [beats.eq(channel.len + 1), span.eq(beats << channel.size),
                          last_addr.eq(channel.addr + span - 1)]
            def inside(region):
                permitted = region["writable" if write else "readable"]
                return (Constant(int(permitted)) & (channel.burst == 1) &
                    (channel.size <= 3) & (channel.addr >= region["origin"]) &
                    (last_addr < region["origin"] + region["size"]))
            dram = inside(regions["main_ram"])
            low = Constant(0)
            for name in low_names:
                low = low | inside(regions[name])
            return dram, low

        ar_dram, ar_low = decode(master.ar)
        aw_dram, aw_low = decode(master.aw, write=True)
        read_id = Signal(master.id_width)
        read_remaining = Signal(9)
        write_id = Signal(master.id_width)
        write_remaining = Signal(9)
        write_done = Signal()
        self.submodules.read_fsm = read = FSM(reset_state="IDLE")
        self.submodules.write_fsm = write = FSM(reset_state="IDLE")

        # AR/AW payloads always track the upstream address; VALID selects the exit.
        for target in (self.dram, self.low):
            for channel in ("ar", "aw"):
                src, dst = getattr(master, channel), getattr(target, channel)
                self.comb += src.connect(dst, omit={"valid", "ready"})
            self.comb += master.w.connect(target.w, omit={"valid", "ready"})
        read.act("IDLE",
            If(ar_dram,
                self.dram.ar.valid.eq(master.ar.valid), master.ar.ready.eq(self.dram.ar.ready),
                If(master.ar.valid & master.ar.ready, NextState("DRAM")),
            ).Elif(ar_low,
                self.low.ar.valid.eq(master.ar.valid), master.ar.ready.eq(self.low.ar.ready),
                If(master.ar.valid & master.ar.ready, NextState("LOW")),
            ).Else(
                master.ar.ready.eq(1),
                If(master.ar.valid, NextValue(read_id, master.ar.id),
                    NextValue(read_remaining, master.ar.len + 1), NextState("ERROR")),
            ),
        )
        for name, target, inflight in (("DRAM", self.dram, self.read_dram),
                                        ("LOW", self.low, self.read_low)):
            read.act(name, inflight.eq(1), target.r.connect(master.r),
                If(master.r.valid & master.r.ready & master.r.last, NextState("IDLE")))
        read.act("ERROR", master.r.valid.eq(1), master.r.id.eq(read_id),
            master.r.data.eq(0), master.r.resp.eq(3), master.r.last.eq(read_remaining == 1),
            If(master.r.ready, NextValue(read_remaining, read_remaining - 1),
                If(read_remaining == 1, NextState("IDLE"))))

        write.act("IDLE", NextValue(write_done, 0),
            If(aw_dram,
                self.dram.aw.valid.eq(master.aw.valid), master.aw.ready.eq(self.dram.aw.ready),
                If(master.aw.valid & master.aw.ready, NextState("DRAM")),
            ).Elif(aw_low,
                self.low.aw.valid.eq(master.aw.valid), master.aw.ready.eq(self.low.aw.ready),
                If(master.aw.valid & master.aw.ready, NextState("LOW")),
            ).Else(
                master.aw.ready.eq(1),
                If(master.aw.valid, NextValue(write_id, master.aw.id),
                    NextValue(write_remaining, master.aw.len + 1), NextState("ERROR_W")),
            ),
        )
        for name, target, inflight in (("DRAM", self.dram, self.write_dram),
                                        ("LOW", self.low, self.write_low)):
            write.act(name, inflight.eq(1),
                target.w.valid.eq(master.w.valid & ~write_done),
                master.w.ready.eq(target.w.ready & ~write_done),
                target.b.connect(master.b),
                If(master.w.valid & master.w.ready & master.w.last, NextValue(write_done, 1)),
                If(master.b.valid & master.b.ready, NextState("IDLE")))
        write.act("ERROR_W", master.w.ready.eq(1),
            If(master.w.valid, NextValue(write_remaining, write_remaining - 1),
                If(write_remaining == 1, NextState("ERROR_B"))))
        write.act("ERROR_B", master.b.valid.eq(1), master.b.id.eq(write_id), master.b.resp.eq(3),
            If(master.b.ready, NextState("IDLE")))


def attach_cluster_axi(soc):
    """Called after add_sdram; its direct AXI bus is the router's DRAM exit.

    Declare the router before add_sdram and expose only router.dram through
    cpu.memory_buses. LiteX then installs its own AXI width/native converter.
    """
    cpu = soc.cpu
    soc.submodules.memory_low_bridge = axi.AXI2Wishbone(cpu.axi_router.low, cpu.low_wishbone)
    # mmio's AXI-Lite bridge is automatically installed by SoCBusHandler.


def check_soc_regions(soc, root=None):
    regions = platform_regions(root)
    for soc_name, pma_name in (("rom", "linux_boot_rom" if soc.cpu.privilege_profile == "linux" else "boot_rom"),
                               ("sram", "sram"), ("main_ram", "main_ram")):
        actual, expected = soc.bus.regions[soc_name], regions[pma_name]
        if (actual.origin, actual.size) != (expected["origin"], expected["size"]):
            raise ValueError(f"{soc_name}/PMA mismatch: SoC={actual.origin:#x}+{actual.size:#x}, "
                             f"PMA={expected['origin']:#x}+{expected['size']:#x}")
