"""CSR-controlled six-digit seven-segment display for ALIENTEK EP4CE10."""

from migen import Array, Case, Cat, Constant, If, Module, Signal
from litex.soc.interconnect.csr import AutoCSR, CSRStorage


class SevenSegmentDisplay(Module, AutoCSR):
    """Scan six common-anode digits without periodic CPU intervention.

    ``digits`` packs six hexadecimal nibbles, least-significant digit first.
    ``enable`` and ``dots`` use the same bit ordering.  Both digit-select and
    segment outputs are active low on the ALIENTEK Pioneer board.
    """

    def __init__(self, pads, sys_clk_freq, digit_period_us=1000):
        self.digits = CSRStorage(24, name="digits",
            description="Six packed hexadecimal digits; digit 0 is bits 3:0.")
        self.enable = CSRStorage(6, name="enable",
            description="Per-digit display enable; bit 0 selects digit 0.")
        self.dots = CSRStorage(6, name="dots",
            description="Per-digit decimal-point enable; bit 0 selects digit 0.")

        cycles_per_digit = max(1,
            int(sys_clk_freq * digit_period_us // 1_000_000))
        scan_counter = Signal(max=cycles_per_digit)
        scan_index = Signal(max=6)
        current_digit = Signal(4)
        segment_pattern = Signal(8)
        dot_enabled = Signal()
        digit_enabled = Signal()

        digit_values = Array(
            self.digits.storage[index * 4:(index + 1) * 4]
            for index in range(6))
        dot_values = Array(self.dots.storage[index] for index in range(6))
        enable_values = Array(self.enable.storage[index] for index in range(6))
        select_patterns = Array(Constant((~(1 << index)) & 0x3f, 6)
            for index in range(6))

        self.sync += If(scan_counter == cycles_per_digit - 1,
            scan_counter.eq(0),
            If(scan_index == 5,
                scan_index.eq(0)
            ).Else(
                scan_index.eq(scan_index + 1)
            )
        ).Else(
            scan_counter.eq(scan_counter + 1)
        )

        self.comb += [
            current_digit.eq(digit_values[scan_index]),
            dot_enabled.eq(dot_values[scan_index]),
            digit_enabled.eq(enable_values[scan_index]),
            pads.sel.eq(0x3f),
            pads.seg.eq(Cat(segment_pattern[:7], ~dot_enabled)),
            If(digit_enabled,
                pads.sel.eq(select_patterns[scan_index])
            ),
        ]

        # Bit order is {dp, g, f, e, d, c, b, a}; zero lights a..f.
        self.comb += Case(current_digit, {
            0x0: segment_pattern.eq(0xc0),
            0x1: segment_pattern.eq(0xf9),
            0x2: segment_pattern.eq(0xa4),
            0x3: segment_pattern.eq(0xb0),
            0x4: segment_pattern.eq(0x99),
            0x5: segment_pattern.eq(0x92),
            0x6: segment_pattern.eq(0x82),
            0x7: segment_pattern.eq(0xf8),
            0x8: segment_pattern.eq(0x80),
            0x9: segment_pattern.eq(0x90),
            0xa: segment_pattern.eq(0x88),
            0xb: segment_pattern.eq(0x83),
            0xc: segment_pattern.eq(0xc6),
            0xd: segment_pattern.eq(0xa1),
            0xe: segment_pattern.eq(0x86),
            0xf: segment_pattern.eq(0x8e),
        })
