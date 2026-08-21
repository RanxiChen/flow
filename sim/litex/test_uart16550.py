#!/usr/bin/env python3
"""Register-level tests for the Flow 16550/LiteX FIFO front-end."""

import os
import sys
import unittest

from migen.sim import run_simulation


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
WRAPPER_ROOT = os.path.join(FLOW_ROOT, "litex_wrapper")
if WRAPPER_ROOT not in sys.path:
    sys.path.insert(0, WRAPPER_ROOT)

from flow.uart16550 import BreezeUart16550  # noqa: E402


def wb_write(dut, offset, value):
    yield dut.bus.adr.eq(0)
    yield dut.bus.sel.eq(1 << offset)
    yield dut.bus.dat_w.eq((value & 0xff) << (8 * offset))
    yield dut.bus.we.eq(1)
    yield dut.bus.cyc.eq(1)
    yield dut.bus.stb.eq(1)
    yield
    while not (yield dut.bus.ack):
        yield
    yield dut.bus.cyc.eq(0)
    yield dut.bus.stb.eq(0)
    yield dut.bus.we.eq(0)
    yield


def wb_read(dut, offset):
    yield dut.bus.adr.eq(0)
    yield dut.bus.sel.eq(1 << offset)
    yield dut.bus.we.eq(0)
    yield dut.bus.cyc.eq(1)
    yield dut.bus.stb.eq(1)
    yield
    while not (yield dut.bus.ack):
        yield
    value = ((yield dut.bus.dat_r) >> (8 * offset)) & 0xff
    yield dut.bus.cyc.eq(0)
    yield dut.bus.stb.eq(0)
    yield
    return value


class BreezeUart16550Test(unittest.TestCase):
    def test_opensbi_init_and_first_tx_do_not_depend_on_phy_ready(self):
        # Migen's in-process simulator cannot lower this checkout's multi-port
        # Memory-backed FIFO.  Depth one selects LiteX's register Buffer while
        # exercising the same UART/FIFO valid-ready contract.  The production
        # default remains a 16-entry SyncFIFO and is checked by SoC elaboration.
        dut = BreezeUart16550(fifo_depth=1)
        observed = {}

        def process():
            # A non-interactive backend may keep ready low until it sees
            # valid.  The reset LSR must still advertise an empty transmitter.
            yield dut.tx.ready.eq(0)
            observed["reset_lsr"] = yield from wb_read(dut, 5)

            # uart8250_init(): disable interrupts, program divisor through
            # DLAB, restore 8N1, enable/reset FIFOs, clear modem control.
            yield from wb_write(dut, 1, 0x00)
            yield from wb_write(dut, 3, 0x80)
            yield from wb_write(dut, 0, 0x01)
            yield from wb_write(dut, 1, 0x00)
            yield from wb_write(dut, 3, 0x03)
            yield from wb_write(dut, 2, 0x01)
            yield from wb_write(dut, 4, 0x00)
            yield from wb_write(dut, 7, 0x00)
            observed["post_init_lsr"] = yield from wb_read(dut, 5)

            # The first THR write must enter the local FIFO and acknowledge
            # even though the PHY has not raised ready yet.
            yield from wb_write(dut, 0, ord("K"))
            for _ in range(4):
                if (yield dut.tx.valid):
                    break
                yield
            observed["tx_valid"] = yield dut.tx.valid
            observed["tx_data"] = yield dut.tx.data
            observed["queued_lsr"] = yield from wb_read(dut, 5)

            yield dut.tx.ready.eq(1)
            yield
            yield dut.tx.ready.eq(0)
            yield
            observed["drained_lsr"] = yield from wb_read(dut, 5)

        run_simulation(dut, process())
        self.assertEqual(observed["reset_lsr"], 0x60)
        self.assertEqual(observed["post_init_lsr"], 0x60)
        self.assertEqual(observed["tx_valid"], 1)
        self.assertEqual(observed["tx_data"], ord("K"))
        self.assertEqual(observed["queued_lsr"], 0x00)
        self.assertEqual(observed["drained_lsr"], 0x60)


if __name__ == "__main__":
    unittest.main()
