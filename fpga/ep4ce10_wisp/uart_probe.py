#!/usr/bin/env python3
"""Temporary LiteX/Migen board-level UART path probe for ALIENTEK EP4CE10."""

import os

from migen import Array, ClockDomain, Constant, Module, Signal, If

from target import Platform, SYS_CLK_FREQ


class UartProbe(Module):
    def __init__(self, platform):
        self.clock_domains.cd_sys = ClockDomain("sys", reset_less=True)
        self.comb += self.cd_sys.clk.eq(platform.request("clk50"))

        tx = platform.request("serial").tx
        led = platform.request("led", 0)
        message = Array(Constant(c, 8) for c in b"PROBE\r\n")
        selected_char = Signal(8)
        selected_bits = Array(selected_char[i] for i in range(8))
        divisor = round(SYS_CLK_FREQ / 115200)

        baud_counter = Signal(max=divisor, reset=divisor - 1)
        bit_index = Signal(max=11)
        char_index = Signal(max=len(message))
        heartbeat = Signal(25)

        self.sync += heartbeat.eq(heartbeat + 1)
        self.comb += [
            led.eq(heartbeat[-1]),
            selected_char.eq(message[char_index]),
        ]

        self.sync += If(baud_counter == 0,
            baud_counter.eq(divisor - 1),
            If(bit_index == 0,
                tx.eq(0),
                bit_index.eq(1),
            ).Elif(bit_index <= 8,
                tx.eq(selected_bits[bit_index - 1]),
                bit_index.eq(bit_index + 1),
            ).Elif(bit_index == 9,
                tx.eq(1),
                bit_index.eq(10),
            ).Else(
                bit_index.eq(0),
                If(char_index == len(message) - 1,
                    char_index.eq(0),
                ).Else(
                    char_index.eq(char_index + 1),
                ),
            ),
        ).Else(
            baud_counter.eq(baud_counter - 1),
        )


def main():
    platform = Platform()
    build_dir = os.path.join(os.path.dirname(__file__), "build-uart-probe")
    platform.build(
        UartProbe(platform),
        build_dir=build_dir,
        build_name="wisp_uart_probe",
        run=False,
    )
    qsf_path = os.path.join(build_dir, "wisp_uart_probe.qsf")
    rtl_path = os.path.join(build_dir, "wisp_uart_probe.v")
    with open(qsf_path, encoding="utf-8") as stream:
        qsf = stream.read()
    with open(qsf_path, "w", encoding="utf-8") as stream:
        stream.write(qsf.replace(rtl_path, "wisp_uart_probe.v"))


if __name__ == "__main__":
    main()
