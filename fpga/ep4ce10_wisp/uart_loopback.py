#!/usr/bin/env python3
"""Temporary LiteX/Migen electrical loopback probe for ALIENTEK EP4CE10."""

import os

from migen import ClockDomain, Module, Signal

from target import Platform


class UartLoopback(Module):
    def __init__(self, platform):
        self.clock_domains.cd_sys = ClockDomain("sys", reset_less=True)
        self.comb += self.cd_sys.clk.eq(platform.request("clk50"))

        serial = platform.request("serial")
        led = platform.request("led", 0)
        heartbeat = Signal(25)

        self.comb += serial.tx.eq(serial.rx)
        self.sync += heartbeat.eq(heartbeat + 1)
        self.comb += led.eq(heartbeat[-1])


def main():
    platform = Platform()
    build_dir = os.path.join(os.path.dirname(__file__), "build-uart-loopback")
    build_name = "wisp_uart_loopback"
    platform.build(UartLoopback(platform), build_dir=build_dir,
        build_name=build_name, run=False)
    qsf_path = os.path.join(build_dir, build_name + ".qsf")
    rtl_path = os.path.join(build_dir, build_name + ".v")
    with open(qsf_path, encoding="utf-8") as stream:
        qsf = stream.read()
    with open(qsf_path, "w", encoding="utf-8") as stream:
        stream.write(qsf.replace(rtl_path, build_name + ".v"))


if __name__ == "__main__":
    main()
