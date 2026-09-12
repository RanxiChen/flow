#!/usr/bin/env python3
"""Check actual upstream CMD timing; not a card/protocol or CPU simulation."""
from migen import Module, Signal
from migen.sim import run_simulation
from litesdcard.phy import SDPHYClocker, SDPHYCMDW, _sdpads_layout

for divider in (20, 250):
    m = Module()
    m.submodules.clocker = clocker = SDPHYClocker()
    m.submodules.writer = writer = SDPHYCMDW(_sdpads_layout(4))
    m.comb += [clocker.clk_en.eq(writer.pads_out.clk), writer.pads_out.ready.eq(clocker.ce)]
    clk, data = Signal(), Signal()
    # Same FDCE one-cycle output behavior as XilinxSDROutputImplUS.
    m.sync += [clk.eq(~clocker.clk), data.eq(writer.pads_out.cmd.o)]
    edges, changes = [], []

    def drive():
        yield clocker.divider.storage.eq(divider)
        yield writer.sink.valid.eq(1)
        yield writer.sink.data.eq(0x55)
        prev_clk = prev_data = 0
        for cycle in range(128 + divider * 20):
            ck, dt = (yield clk), (yield data)
            if ck and not prev_clk and cycle > 128:
                edges.append(cycle)
            if dt != prev_data and cycle > 128:
                changes.append(cycle)
            prev_clk, prev_data = ck, dt
            yield
    run_simulation(m, drive())
    assert len(changes) >= 8
    assert all(t - max(e for e in edges if e <= t) == 1 for t in changes)
    assert all(b - a == divider for a, b in zip(edges, edges[1:]))
    print(f'divider={divider}: SD period={divider} sys cycles; CMD changes one sys cycle after rising edge')
