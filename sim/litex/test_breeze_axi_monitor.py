"""Burst completion, independent AW/W and passive sampled monitor checks."""
import os
import sys
import unittest
from migen.sim import run_simulation
from litex.soc.interconnect import axi

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '../../litex_wrapper')))
from flow.axi_monitor import BreezeAxiMonitor


class BreezeAxiMonitorTest(unittest.TestCase):
    def test_axi_and_axilite_events_completion_errors_and_backpressure(self):
        for full in (True, False):
            with self.subTest(axi4=full):
                bus = (axi.AXIInterface(data_width=64, address_width=32, id_width=1)
                       if full else axi.AXILiteInterface(data_width=64, address_width=32))
                dut = BreezeAxiMonitor(bus, 'test')

                def cycle(**fields):
                    for channel in ('ar', 'aw', 'w', 'r', 'b'):
                        yield getattr(bus, channel).valid.eq(0)
                        yield getattr(bus, channel).ready.eq(1)
                    for name, value in fields.items():
                        channel, field = name.split('_', 1)
                        yield getattr(getattr(bus, channel), field).eq(value)
                    yield

                def pulse(**fields):
                    yield from cycle(**fields)
                    yield from cycle()
                    yield from cycle()

                def stimulus():
                    yield from pulse(ar_valid=1, ar_addr=0x10010000)
                    self.assertEqual((yield dut.ar_count), 1)
                    self.assertEqual((yield dut.reads_pending), 1)
                    self.assertEqual((yield dut.last_ar_addr), 0x10010000)
                    if full:
                        yield from pulse(r_valid=1, r_last=0)
                        self.assertEqual((yield dut.reads_pending), 1)
                    last = {'r_last': 1} if full else {}
                    yield from pulse(r_valid=1, **last)
                    self.assertEqual((yield dut.reads_pending), 0)
                    self.assertEqual((yield dut.r_count), 2 if full else 1)
                    yield from pulse(w_valid=1)
                    self.assertEqual((yield dut.write_active), 1)
                    self.assertEqual((yield dut.writes_pending), 0)
                    yield from pulse(aw_valid=1, aw_addr=0x12001000)
                    self.assertEqual((yield dut.writes_pending), 1)
                    self.assertEqual((yield dut.last_aw_addr), 0x12001000)
                    yield from pulse(b_valid=1)
                    self.assertEqual((yield dut.write_active), 0)
                    self.assertEqual((yield dut.writes_pending), 0)
                    # Same-edge AR/R and AW/B must leave occupancy unchanged.
                    # Two response errors in one cycle must count as two.
                    yield from pulse(ar_valid=1, r_valid=1, r_resp=2,
                                     aw_valid=1, b_valid=1, b_resp=3, **last)
                    self.assertEqual((yield dut.error_count), 2)
                    self.assertEqual((yield dut.error_seen), 1)
                    self.assertEqual((yield dut.accounting_fault), 0)
                    self.assertEqual((yield dut.reads_pending), 0)
                    self.assertEqual((yield dut.writes_pending), 0)
                    count = (yield dut.aw_count)
                    for _ in range(3):
                        yield from cycle(aw_valid=1, aw_ready=0)
                    yield from cycle()
                    yield from cycle()
                    self.assertEqual((yield dut.aw_count), count)
                    self.assertEqual((yield dut.stall_cycles), 3)
                    # The monitor must not manufacture a handshake or response.
                    yield from cycle(aw_valid=1, aw_ready=0)
                    yield
                    self.assertEqual((yield bus.aw.ready), 0)
                    self.assertEqual((yield bus.b.valid), 0)
                run_simulation(dut, stimulus())
