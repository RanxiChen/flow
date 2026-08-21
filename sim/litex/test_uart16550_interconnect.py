#!/usr/bin/env python3
"""LiteX shared-interconnect readback test for the Linux 16550 UART."""

import os
import sys
import unittest

from migen import Module
from migen.sim import run_simulation
from litex.soc.interconnect import wishbone


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
WRAPPER_ROOT = os.path.join(FLOW_ROOT, "litex_wrapper")
if WRAPPER_ROOT not in sys.path:
    sys.path.insert(0, WRAPPER_ROOT)

from flow.uart16550 import BreezeUart16550  # noqa: E402


UART_BYTE_ADDRESS = 0x1300_0000
UART_WORD_ADDRESS = UART_BYTE_ADDRESS >> 3
UART_LSR_LANE = 5


class UartInterconnectDut(Module):
    def __init__(self):
        self.master = wishbone.Interface(
            data_width=64, address_width=32, addressing="word")
        self.submodules.uart = uart = BreezeUart16550(fifo_depth=1)
        self.submodules.fabric = wishbone.InterconnectShared(
            [self.master],
            [(lambda address: address == UART_WORD_ADDRESS, uart.bus)],
            register=False,
        )


def wb_read(master, word_address, select):
    yield master.adr.eq(word_address)
    yield master.sel.eq(select)
    yield master.we.eq(0)
    yield master.cyc.eq(1)
    yield master.stb.eq(1)
    yield
    while not (yield master.ack):
        yield
    value = yield master.dat_r
    yield master.cyc.eq(0)
    yield master.stb.eq(0)
    yield
    return value


class BreezeUart16550InterconnectTest(unittest.TestCase):
    def test_lsr_empty_bits_survive_shared_interconnect_and_byte_lane(self):
        dut = UartInterconnectDut()
        observed = {}

        def process():
            # This is the exact 64-bit Wishbone request produced by an LBU
            # from physical address 0x13000005.
            observed["word"] = yield from wb_read(
                dut.master, UART_WORD_ADDRESS, 1 << UART_LSR_LANE)

        run_simulation(dut, process())
        expected_word = 0x60 << (8 * UART_LSR_LANE)
        self.assertEqual(observed["word"], expected_word)
        self.assertEqual(
            (observed["word"] >> (8 * UART_LSR_LANE)) & 0xff, 0x60)


if __name__ == "__main__":
    unittest.main()
