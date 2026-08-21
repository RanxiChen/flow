#!/usr/bin/env python3
"""Register/FIFO/event tests for the native LiteX LiteUART core."""

import unittest

from migen.sim import run_simulation
from litex.soc.cores.uart import UART


class LiteUartTest(unittest.TestCase):
    def test_tx_rx_status_and_events_use_native_litex_contract(self):
        # Depth one selects LiteX's register Buffer, which the in-process
        # Migen simulator can execute without lowering a multi-port Memory.
        dut = UART(tx_fifo_depth=1, rx_fifo_depth=1)
        observed = {}

        def process():
            yield dut.source.ready.eq(0)
            observed["reset_txfull"] = yield dut._txfull.status
            observed["reset_rxempty"] = yield dut._rxempty.status

            # CSR write to RXTX: .re is the write strobe and .r is write data.
            yield dut._rxtx.r.eq(ord("L"))
            yield dut._rxtx.re.eq(1)
            yield
            yield dut._rxtx.re.eq(0)
            yield
            observed["tx_valid"] = yield dut.source.valid
            observed["tx_data"] = yield dut.source.data
            observed["queued_txfull"] = yield dut._txfull.status

            # TX-ready is LiteUART's raw event bit 0. The full ev.irq/CSR bank
            # is finalized by SoCCore and is covered by the CPU+PLIC smoke.
            yield dut.source.ready.eq(1)
            yield
            yield
            observed["tx_event"] = yield dut.ev.tx.trigger

            # Present one received byte. RX is LiteUART event bit 1.
            yield dut.sink.data.eq(ord("R"))
            yield dut.sink.valid.eq(1)
            yield
            yield dut.sink.valid.eq(0)
            yield
            observed["rxempty"] = yield dut._rxempty.status
            observed["rx_data"] = yield dut._rxtx.w
            observed["rx_event"] = yield dut.ev.rx.trigger

        run_simulation(dut, process())
        self.assertEqual(observed["reset_txfull"], 0)
        self.assertEqual(observed["reset_rxempty"], 1)
        self.assertEqual(observed["tx_valid"], 1)
        self.assertEqual(observed["tx_data"], ord("L"))
        self.assertEqual(observed["queued_txfull"], 1)
        self.assertEqual(observed["tx_event"], 1)
        self.assertEqual(observed["rxempty"], 0)
        self.assertEqual(observed["rx_data"], ord("R"))
        self.assertEqual(observed["rx_event"], 1)


if __name__ == "__main__":
    unittest.main()
