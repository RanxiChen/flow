"""Parameterized RISC-V CLINT (msip/mtimecmp/mtime) as a 64-bit Wishbone slave.

Classic CLINT register layout inside the platform's existing non-cacheable
0x0200_0000..0x0200_ffff device region (spec section 19):

    msip[h]     = region + 0x0000 + 4*h   (32-bit RW, only bit 0 implemented)
    mtimecmp[h] = region + 0x4000 + 8*h   (64-bit RW)
    mtime       = region + 0xbff8         (64-bit RW, shared free-running)

Replaces the single-hart BreezeMachineTimer. Every hart's MMIO master reaches
this slave through the shared LiteX interconnect, so hart A can raise hart B's
software interrupt by writing msip[B].

64-bit bus notes:
  - two 32-bit msip registers share one 64-bit bus word; writes are decoded
    with bus.sel per byte lane so writing msip[1] never clobbers msip[0]
    (and vice versa). Only the byte lane that contains a register's bit 0
    can change that register.
  - mtime/mtimecmp writes merge per selected byte lane; unselected lanes
    keep their value.

Unimplemented offsets inside the region (including msip/mtimecmp slots of
harts that do not exist) are fixed to read-as-zero / write-ignored rather
than a bus error: the region is already fenced by the PMA, RAZ/WI keeps
firmware probing benign, and - critically - a nonexistent hart's slot never
aliases onto an existing hart's register.
"""

from migen import Array, Cat, If, Module, Replicate, Signal

from litex.soc.interconnect import wishbone


class BreezeClint(Module):
    """Multi-hart CLINT with per-hart msip/mtip outputs."""

    def __init__(
        self,
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
        if msip_offset % 8:
            raise ValueError("msip base must be 64-bit aligned (msip[0] shares a bus word)")
        if mtimecmp_offset % 8 or mtime_offset % 8:
            raise ValueError("mtimecmp/mtime must be 64-bit aligned registers")

        msip_words = (num_harts + 1) // 2
        # Word index (64-bit granularity) of each implemented register.
        msip_word_base = msip_offset // 8
        mtimecmp_word_base = mtimecmp_offset // 8
        mtime_word = mtime_offset // 8
        region_words = region_size // 8
        for name, first, count in (
            ("msip", msip_word_base, msip_words),
            ("mtimecmp", mtimecmp_word_base, num_harts),
            ("mtime", mtime_word, 1),
        ):
            if first < 0 or first + count > region_words:
                raise ValueError(f"CLINT {name} registers do not fit in the region")
        msip_range = set(range(msip_word_base, msip_word_base + msip_words))
        mtimecmp_range = set(range(mtimecmp_word_base, mtimecmp_word_base + num_harts))
        if (msip_range & mtimecmp_range) or (mtime_word in msip_range) or (
                mtime_word in mtimecmp_range):
            raise ValueError("CLINT register windows overlap")

        self.bus = bus = wishbone.Interface(
            data_width=64,
            address_width=32,
            addressing="word",
        )
        self.msip = Signal(num_harts)
        self.mtip = Signal(num_harts)
        self.mtime = Signal(64, reset=0)
        # Reset to all-ones so no timer interrupt fires before firmware
        # programs a real compare value.
        self.mtimecmp = [
            Signal(64, reset=(1 << 64) - 1, name=f"mtimecmp{h}")
            for h in range(num_harts)
        ]
        msip_regs = [Signal(name=f"msip{h}") for h in range(num_harts)]

        # Timebase tick.
        timebase_divisor = sys_clk_freq // timebase_freq
        tick = Signal()
        if timebase_divisor == 1:
            self.comb += tick.eq(1)
        else:
            divider = Signal(max=timebase_divisor, reset=0)
            self.comb += tick.eq(divider == (timebase_divisor - 1))
            self.sync += If(tick, divider.eq(0)).Else(divider.eq(divider + 1))

        word_offset_width = max(1, (region_words - 1).bit_length())
        word_offset = bus.adr[:word_offset_width]

        responding = Signal(reset=0)
        read_data = Signal(64, reset=0)
        request = Signal()
        write_mask = Cat(*[Replicate(bus.sel[index], 8) for index in range(8)])

        self.comb += [
            request.eq(bus.cyc & bus.stb & ~responding),
            bus.dat_r.eq(read_data),
            # RAZ/WI for unmapped offsets: every access acks, none errors.
            bus.ack.eq(responding & bus.cyc & bus.stb),
            bus.err.eq(0),
        ]
        for h in range(num_harts):
            self.comb += [
                self.msip[h].eq(msip_regs[h]),
                self.mtip[h].eq(self.mtime >= self.mtimecmp[h]),
            ]

        # Combinational read mux over the implemented registers (RAZ default).
        read_value = Signal(64)
        read_cases = []
        for w in range(msip_words):
            low = msip_regs[2 * w]
            high = (msip_regs[2 * w + 1]
                    if (2 * w + 1) < num_harts else Signal(reset=0))
            value = Cat(low, Replicate(0, 31), high, Replicate(0, 31))
            read_cases.append((word_offset == (msip_word_base + w), value))
        for h in range(num_harts):
            read_cases.append(
                (word_offset == (mtimecmp_word_base + h), self.mtimecmp[h]))
        read_cases.append((word_offset == mtime_word, self.mtime))

        selector = Signal(reset=0)  # placeholder to build the If chain below
        del selector
        stmt = read_value.eq(0)
        chain = If(read_cases[0][0], read_value.eq(read_cases[0][1]))
        for condition, value in read_cases[1:]:
            chain = chain.Elif(condition, read_value.eq(value))
        chain = chain.Else(stmt)
        self.comb += chain

        # Register writes. Each register only changes when its own byte lanes
        # are selected; msip additionally keys off the single byte lane that
        # carries the register's bit 0 (byte 0 for the low lane, byte 4 for
        # the high lane), so a stray partial write cannot flip a neighbour.
        write_statements = []
        for h in range(num_harts):
            word = msip_word_base + (h // 2)
            lane_bit = 32 * (h % 2)
            lane_sel = bus.sel[4 * (h % 2)]
            write_statements.append(
                If(request & bus.we & (word_offset == word) & lane_sel,
                   msip_regs[h].eq(bus.dat_w[lane_bit])))
        for h in range(num_harts):
            merged = (self.mtimecmp[h] & ~write_mask) | (bus.dat_w & write_mask)
            write_statements.append(
                If(request & bus.we & (word_offset == (mtimecmp_word_base + h)),
                   self.mtimecmp[h].eq(merged)))

        merged_mtime = (self.mtime & ~write_mask) | (bus.dat_w & write_mask)
        self.sync += write_statements + [
            If(request & bus.we & (word_offset == mtime_word),
               self.mtime.eq(merged_mtime),
            ).Elif(tick,
               self.mtime.eq(self.mtime + 1),
            ),
            If(responding,
               responding.eq(0),
            ).Elif(request,
               responding.eq(1),
               read_data.eq(0),
               If(~bus.we, read_data.eq(read_value)),
            ),
        ]
