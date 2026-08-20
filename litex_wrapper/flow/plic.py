"""Small standards-shaped RISC-V PLIC for the Flow 1/2/4-hart platform.

Implements a configurable 1..31 interrupt sources (31 in the Linux platform)
and two contexts per hart (M then S), using the standard
priority/pending/enable/threshold/claim-complete address layout.
"""

from migen import Array, Cat, Constant, If, Module, Mux, Replicate, Signal
from litex.soc.interconnect import wishbone


class BreezePlic(Module):
    def __init__(self, num_harts, num_sources=8):
        num_harts = int(num_harts)
        num_sources = int(num_sources)
        if num_harts < 1 or num_sources < 1 or num_sources > 31:
            raise ValueError("PLIC supports 1+ harts and 1..31 sources")

        contexts = 2 * num_harts
        self.bus = bus = wishbone.Interface(
            data_width=64, address_width=32, addressing="word")
        self.sources = Signal(num_sources)
        self.meip = Signal(num_harts)
        self.seip = Signal(num_harts)

        priority = [Signal(3, name=f"priority{i}") for i in range(num_sources + 1)]
        pending = [Signal(name=f"pending{i}") for i in range(num_sources + 1)]
        claimed = [Signal(name=f"claimed{i}") for i in range(num_sources + 1)]
        enables = [Signal(num_sources + 1, name=f"enable{c}") for c in range(contexts)]
        threshold = [Signal(3, name=f"threshold{c}") for c in range(contexts)]
        claim = [Signal(32, name=f"claim{c}") for c in range(contexts)]

        for c in range(contexts):
            selected = Constant(0, 32)
            for level in range(1, 8):
                for source in range(num_sources, 0, -1):
                    eligible = (pending[source] & enables[c][source] &
                                (priority[source] == level) &
                                (priority[source] > threshold[c]))
                    selected = Mux(eligible, source, selected)
            self.comb += claim[c].eq(selected)
        for h in range(num_harts):
            self.comb += [self.meip[h].eq(claim[2*h] != 0),
                          self.seip[h].eq(claim[2*h + 1] != 0)]

        responding = Signal(reset=0)
        request = Signal()
        word = bus.adr
        self.comb += [
            request.eq(bus.cyc & bus.stb & ~responding),
            bus.ack.eq(responding & bus.cyc & bus.stb),
            bus.err.eq(0),
        ]

        # Two adjacent 32-bit PLIC registers share each 64-bit Wishbone word.
        read_low = Signal(32)
        read_high = Signal(32)
        self.comb += [read_low.eq(0), read_high.eq(0)]
        for source in range(1, num_sources + 1):
            byte_offset = 4 * source
            target_word = byte_offset // 8
            target = read_high if (byte_offset & 4) else read_low
            self.comb += If(word == target_word, target.eq(priority[source]))
        pending_bits = Cat(*pending)
        self.comb += If(word == (0x1000 // 8), read_low.eq(pending_bits))
        for c in range(contexts):
            enable_word = (0x2000 + 0x80 * c) // 8
            self.comb += If(word == enable_word, read_low.eq(enables[c]))
            context_word = (0x200000 + 0x1000 * c) // 8
            self.comb += If(word == context_word,
                            read_low.eq(threshold[c]), read_high.eq(claim[c]))
        read_data = Cat(read_low, read_high)
        latched_read = Signal(64)
        self.comb += bus.dat_r.eq(latched_read)
        self.sync += [
            If(request, responding.eq(1), latched_read.eq(read_data))
              .Elif(~bus.cyc | ~bus.stb, responding.eq(0))
        ]

        writes = []
        for source in range(1, num_sources + 1):
            byte_offset = 4 * source
            target_word = byte_offset // 8
            lane = 4 if (byte_offset & 4) else 0
            value = bus.dat_w[32:35] if lane else bus.dat_w[0:3]
            writes.append(If(request & bus.we & (word == target_word) & bus.sel[lane],
                             priority[source].eq(value)))
        for c in range(contexts):
            enable_word = (0x2000 + 0x80 * c) // 8
            writes.append(If(request & bus.we & (word == enable_word) & bus.sel[0],
                             enables[c].eq(bus.dat_w[:num_sources + 1])))
            context_word = (0x200000 + 0x1000 * c) // 8
            writes.append(If(request & bus.we & (word == context_word) & bus.sel[0],
                             threshold[c].eq(bus.dat_w[:3])))

        for source in range(1, num_sources + 1):
            claim_events = []
            completion_events = []
            for c in range(contexts):
                context_word = (0x200000 + 0x1000 * c) // 8
                claim_events.append(request & ~bus.we & (word == context_word) &
                                    bus.sel[4] & (claim[c] == source))
                completion_events.append(request & bus.we & (word == context_word) &
                                         bus.sel[4] & (bus.dat_w[32:64] == source))
            claim_any = claim_events[0]
            complete_any = completion_events[0]
            for event in claim_events[1:]:
                claim_any = claim_any | event
            for event in completion_events[1:]:
                complete_any = complete_any | event
            writes.append(
                If(claim_any, pending[source].eq(0), claimed[source].eq(1))
                .Elif(complete_any, claimed[source].eq(0))
                .Elif(self.sources[source - 1] & ~claimed[source], pending[source].eq(1))
            )
        self.sync += writes
