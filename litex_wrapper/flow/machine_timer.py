"""Architectural RISC-V machine timer exposed as a Wishbone slave."""

from migen import Cat, If, Module, Replicate, Signal

from litex.soc.interconnect import wishbone


class BreezeMachineTimer(Module):
    """64-bit mtime/mtimecmp timer with a level-sensitive mtip output."""

    def __init__(
        self,
        sys_clk_freq,
        timebase_freq,
        region_size,
        mtime_offset,
        mtimecmp_offset,
    ):
        sys_clk_freq = int(sys_clk_freq)
        timebase_freq = int(timebase_freq)
        region_size = int(region_size)
        mtime_offset = int(mtime_offset)
        mtimecmp_offset = int(mtimecmp_offset)

        if sys_clk_freq <= 0 or timebase_freq <= 0:
            raise ValueError("Machine timer clock frequencies must be positive")
        if sys_clk_freq % timebase_freq:
            raise ValueError(
                "Machine timer requires sys_clk_freq to be an integer multiple "
                "of timebase_freq"
            )
        if region_size < 8 or (region_size & (region_size - 1)):
            raise ValueError("Machine timer region size must be a power of two >= 8")
        for name, offset in (
            ("mtime", mtime_offset),
            ("mtimecmp", mtimecmp_offset),
        ):
            if offset < 0 or offset + 8 > region_size or offset % 8:
                raise ValueError(f"{name} must be an aligned 64-bit register inside the region")
        if mtime_offset == mtimecmp_offset:
            raise ValueError("mtime and mtimecmp offsets must differ")

        self.bus = bus = wishbone.Interface(
            data_width=64,
            address_width=32,
            addressing="word",
        )
        self.mtip = Signal()
        self.mtime = Signal(64, reset=0)
        self.mtimecmp = Signal(64, reset=(1 << 64) - 1)

        timebase_divisor = sys_clk_freq // timebase_freq
        tick = Signal()
        if timebase_divisor == 1:
            self.comb += tick.eq(1)
        else:
            divider = Signal(max=timebase_divisor, reset=0)
            self.comb += tick.eq(divider == (timebase_divisor - 1))
            self.sync += If(
                tick,
                divider.eq(0),
            ).Else(
                divider.eq(divider + 1),
            )

        region_words = region_size // 8
        word_offset_width = (region_words - 1).bit_length()
        word_offset = bus.adr[:word_offset_width]
        mtime_word_offset = mtime_offset // 8
        mtimecmp_word_offset = mtimecmp_offset // 8

        responding = Signal(reset=0)
        response_error = Signal(reset=0)
        read_data = Signal(64, reset=0)
        request = Signal()
        valid_address = Signal()
        write_mtime = Signal()
        write_mtimecmp = Signal()
        write_mask = Cat(*[Replicate(bus.sel[index], 8) for index in range(8)])
        merged_mtime = (self.mtime & ~write_mask) | (bus.dat_w & write_mask)
        merged_mtimecmp = (self.mtimecmp & ~write_mask) | (bus.dat_w & write_mask)

        self.comb += [
            request.eq(bus.cyc & bus.stb & ~responding),
            valid_address.eq(
                (word_offset == mtime_word_offset) |
                (word_offset == mtimecmp_word_offset)
            ),
            write_mtime.eq(
                request & bus.we & (word_offset == mtime_word_offset)
            ),
            write_mtimecmp.eq(
                request & bus.we & (word_offset == mtimecmp_word_offset)
            ),
            bus.dat_r.eq(read_data),
            bus.ack.eq(responding & bus.cyc & bus.stb & ~response_error),
            bus.err.eq(responding & bus.cyc & bus.stb & response_error),
            self.mtip.eq(self.mtime >= self.mtimecmp),
        ]

        self.sync += [
            If(
                responding,
                responding.eq(0),
            ).Elif(
                request,
                responding.eq(1),
                response_error.eq(~valid_address),
                read_data.eq(0),
                If(
                    ~bus.we & (word_offset == mtime_word_offset),
                    read_data.eq(self.mtime),
                ).Elif(
                    ~bus.we & (word_offset == mtimecmp_word_offset),
                    read_data.eq(self.mtimecmp),
                ),
            ),
            If(
                write_mtime,
                self.mtime.eq(merged_mtime),
            ).Elif(
                tick,
                self.mtime.eq(self.mtime + 1),
            ),
            If(
                write_mtimecmp,
                self.mtimecmp.eq(merged_mtimecmp),
            ),
        ]
