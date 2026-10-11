"""Passive, registered AXI accounting for JTAG/ILA startup diagnosis."""
from migen import Cat, If, Module, Signal


class BreezeAxiMonitor(Module):
    """Observe AXI4 or AXI-Lite without driving any bus signal.

    Events and payloads are sampled first. Counters/last values update one
    clock after the observed handshake; all ILA input pipelines add the same
    further latency. Counts wrap at 2**32. Outstanding counts saturate at 255
    and latch an accounting fault instead of wrapping. R completion means
    accepted RLAST for AXI4 and every accepted R for AXI-Lite. A write can
    start on W before AW; write_active remains set until accepted B.
    """
    def __init__(self, bus, prefix):
        channels = ('ar', 'aw', 'w', 'r', 'b')
        sampled = Signal(5)
        stalls = Signal(5)
        ar_addr = Signal(len(bus.ar.addr))
        aw_addr = Signal(len(bus.aw.addr))
        r_resp = Signal(2)
        b_resp = Signal(2)
        r_last = Signal()
        self.sync += [
            sampled.eq(Cat(*(getattr(bus, c).valid & getattr(bus, c).ready for c in channels))),
            stalls.eq(Cat(*(getattr(bus, c).valid & ~getattr(bus, c).ready for c in channels))),
            ar_addr.eq(bus.ar.addr), aw_addr.eq(bus.aw.addr),
            r_resp.eq(bus.r.resp), b_resp.eq(bus.b.resp),
            r_last.eq(bus.r.last if hasattr(bus.r, 'last') else 1),
        ]
        self.sources = []

        def probe(name, width):
            value = Signal(width, name=prefix + '_' + name)
            setattr(self, name, value)
            self.sources.append((prefix + '_' + name, value))
            return value

        for index, channel in enumerate(channels):
            count = probe(channel + '_count', 32)
            self.sync += If(sampled[index], count.eq(count + 1))
        accepted = probe('accepted', 5)
        stalled = probe('stalled', 5)
        self.comb += [accepted.eq(sampled), stalled.eq(stalls)]
        last_ar = probe('last_ar_addr', len(ar_addr))
        last_aw = probe('last_aw_addr', len(aw_addr))
        last_r = probe('last_r_resp', 2)
        last_b = probe('last_b_resp', 2)
        self.sync += [If(sampled[0], last_ar.eq(ar_addr)),
                      If(sampled[1], last_aw.eq(aw_addr)),
                      If(sampled[3], last_r.eq(r_resp)),
                      If(sampled[4], last_b.eq(b_resp))]
        errors = probe('error_count', 32)
        error_seen = probe('error_seen', 1)
        r_error = sampled[3] & r_resp[1]
        b_error = sampled[4] & b_resp[1]
        self.sync += If(r_error | b_error,
                       errors.eq(errors + r_error + b_error), error_seen.eq(1))
        fault = probe('accounting_fault', 1)
        for name, push, pop in (
                ('reads_pending', sampled[0], sampled[3] & r_last),
                ('writes_pending', sampled[1], sampled[4])):
            pending = probe(name, 8)
            self.sync += If(push & ~pop,
                            If(pending != 255, pending.eq(pending + 1)).Else(fault.eq(1)))
            self.sync += If(pop & ~push,
                            If(pending != 0, pending.eq(pending - 1)).Else(fault.eq(1)))
        active = probe('write_active', 1)
        self.sync += If(sampled[4], active.eq(0)).Elif(sampled[1] | sampled[2], active.eq(1))
        stall_count = probe('stall_cycles', 32)
        self.sync += If(stalls != 0, stall_count.eq(stall_count + 1))
