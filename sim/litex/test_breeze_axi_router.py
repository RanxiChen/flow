"""Directed protocol tests of the actual Migen SoC router."""
import os
import sys
import unittest

from migen.sim import run_simulation
from litex.soc.interconnect import axi

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '../../litex_wrapper')))
from flow.axi_router import BreezeAxiRouter, platform_regions


class BreezeAxiRouterTest(unittest.TestCase):
    def run_case(self, scenario):
        master = axi.AXIInterface(data_width=64, address_width=32, id_width=1)
        dut = BreezeAxiRouter(master)
        def driver():
            for out in (dut.dram, dut.low):
                yield out.ar.ready.eq(1)
                yield out.aw.ready.eq(1)
                yield out.w.ready.eq(1)
            yield master.r.ready.eq(1)
            yield master.b.ready.eq(1)
            yield
            yield from scenario(master, dut)
        run_simulation(dut, driver())

    def address(self, channel, addr, ident=0, length=3):
        yield channel.addr.eq(addr)
        yield channel.id.eq(ident)
        yield channel.len.eq(length)
        yield channel.size.eq(3)
        yield channel.burst.eq(1)
        yield channel.valid.eq(1)
        yield
        for _ in range(20):
            if (yield channel.ready):
                break
            yield
        else:
            self.fail('address handshake watchdog')
        yield
        yield channel.valid.eq(0)
        yield

    def test_alternating_read_exits_and_ids_preserve_ar_order_and_stalled_r(self):
        def scenario(m, d):
            for address, ident, target, other in (
                (0x80000000, 1, d.dram, d.low),
                (0x11000000, 0, d.low, d.dram),
                (0x80000020, 0, d.dram, d.low),
            ):
                yield from self.address(m.ar, address, ident)
                yield m.ar.addr.eq(0x11000020 if target is d.dram else 0x80000040)
                yield m.ar.valid.eq(1)
                for _ in range(3):
                    yield
                    self.assertEqual((yield other.ar.valid), 0)
                    self.assertEqual((yield m.ar.ready), 0)
                yield m.ar.valid.eq(0)
                for beat in range(4):
                    yield target.r.valid.eq(1)
                    yield target.r.id.eq(ident)
                    yield target.r.data.eq(100 + beat)
                    yield target.r.resp.eq(2 if beat == 1 else 0)
                    yield target.r.last.eq(beat == 3)
                    yield m.r.ready.eq(0)
                    yield
                    yield
                    self.assertEqual((yield m.r.valid), 1)
                    self.assertEqual((yield m.r.id), ident)
                    self.assertEqual((yield m.r.data), 100 + beat)
                    self.assertEqual((yield m.r.resp), 2 if beat == 1 else 0)
                    self.assertEqual((yield m.r.last), beat == 3)
                    yield m.r.ready.eq(1)
                    yield
                    yield
                    yield target.r.valid.eq(0)
                    yield
                yield
        self.run_case(scenario)

    def test_w_before_aw_is_backpressured_then_follows_aw_and_b_error(self):
        def scenario(m, d):
            for address, target, other in ((0x11000000, d.low, d.dram),
                                          (0x80000000, d.dram, d.low)):
                yield m.w.valid.eq(1)
                yield m.w.data.eq(0x123456789abcdef0)
                yield m.w.strb.eq(0x81)
                yield m.w.last.eq(0)
                yield
                yield
                self.assertEqual((yield m.w.ready), 0)
                self.assertEqual((yield target.w.valid), 0)
                yield from self.address(m.aw, address, 1)
                for beat in range(4):
                    yield m.w.last.eq(beat == 3)
                    yield
                    self.assertEqual((yield target.w.valid), 1)
                    self.assertEqual((yield other.w.valid), 0)
                    self.assertEqual((yield target.w.data), 0x123456789abcdef0)
                    self.assertEqual((yield target.w.strb), 0x81)
                    yield
                yield m.w.valid.eq(0)
                yield target.b.valid.eq(1)
                yield target.b.id.eq(1)
                yield target.b.resp.eq(2)
                yield m.b.ready.eq(0)
                yield m.aw.valid.eq(1)
                for _ in range(3):
                    yield
                    self.assertEqual((yield m.aw.ready), 0)
                    self.assertEqual((yield m.b.valid), 1)
                    self.assertEqual((yield m.b.id), 1)
                    self.assertEqual((yield m.b.resp), 2)
                yield m.aw.valid.eq(0)
                yield m.b.ready.eq(1)
                yield
                yield
                yield target.b.valid.eq(0)
                yield
        self.run_case(scenario)

    def test_unmapped_and_cross_region_bursts_complete_decerr_with_backpressure(self):
        def scenario(m, d):
            for address in (0x40000000, 0x1100fff8, 0xfffffff8):
                yield m.r.ready.eq(0)
                yield from self.address(m.ar, address, 1)
                self.assertEqual((yield d.dram.ar.valid), 0)
                self.assertEqual((yield d.low.ar.valid), 0)
                for beat in range(4):
                    self.assertEqual((yield m.r.valid), 1)
                    self.assertEqual((yield m.r.id), 1)
                    self.assertEqual((yield m.r.resp), 3)
                    self.assertEqual((yield m.r.last), beat == 3)
                    yield
                    yield m.r.ready.eq(1)
                    yield
                    yield m.r.ready.eq(0)
                    yield
                yield
                yield m.b.ready.eq(0)
                yield from self.address(m.aw, address, 0)
                for beat in range(4):
                    yield m.w.valid.eq(1)
                    yield m.w.last.eq(beat == 3)
                    yield
                    self.assertEqual((yield m.w.ready), 1)
                    self.assertEqual((yield d.dram.w.valid), 0)
                    self.assertEqual((yield d.low.w.valid), 0)
                    yield m.w.valid.eq(0)
                    yield
                yield m.w.valid.eq(0)
                yield m.b.ready.eq(0)
                yield
                yield
                self.assertEqual((yield m.b.valid), 1)
                self.assertEqual((yield m.b.resp), 3)
                self.assertEqual((yield m.b.id), 0)
                yield m.b.ready.eq(1)
                yield
                yield
        self.run_case(scenario)

    def test_regions_are_loaded_from_platform_json(self):
        regions = platform_regions()
        self.assertEqual(regions['main_ram']['origin'] + regions['main_ram']['size'], 1 << 32)
        self.assertFalse(regions['boot_rom']['writable'])
