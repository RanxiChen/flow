#!/usr/bin/env python3
"""Unit tests for the passive Flow memory-path monitors."""

import contextlib
import io
import os
import sys
import unittest
from types import SimpleNamespace

from migen import Module, Record, Signal
from migen.fhdl import verilog
from migen.sim import run_simulation
from litex.soc.interconnect import wishbone


SIM_DIR = os.path.dirname(os.path.abspath(__file__))
FLOW_ROOT = os.path.abspath(os.path.join(SIM_DIR, "..", ".."))
WRAPPER_ROOT = os.path.join(FLOW_ROOT, "litex_wrapper")
if WRAPPER_ROOT not in sys.path:
    sys.path.insert(0, WRAPPER_ROOT)
if SIM_DIR not in sys.path:
    sys.path.insert(0, SIM_DIR)

from flow.cluster import DCACHE_TRACE_LAYOUT, RETIRE_LAYOUT  # noqa: E402
from memory_monitor import (  # noqa: E402
    FlowCompactEventMonitor, FlowCycleSnapshotMonitor,
    FlowFaultRetireMonitor, FlowWishboneMonitor,
)


class WishboneMonitorDut(Module):
    def __init__(self):
        self.bus = wishbone.Interface(
            data_width=64, address_width=32, addressing="word")
        self.submodules.monitor = FlowWishboneMonitor(
            self.bus, name="unit-mmio", max_events=4,
            address_start=0x12001000, address_end=0x12002000)


class CompactEventMonitorDut(Module):
    def __init__(self):
        self.retire = Record(RETIRE_LAYOUT)
        self.dcache = Record(DCACHE_TRACE_LAYOUT)
        self.msip = Signal()
        self.mtip = Signal()
        self.meip = Signal()
        self.seip = Signal()
        self.hart_fatal = Signal()
        self.hart_estop = Signal()
        cpu = SimpleNamespace(
            retires=[self.retire],
            dcache_traces=[self.dcache],
            msip=self.msip,
            mtip=self.mtip,
            meip=self.meip,
            seip=self.seip,
            hart_fatal=self.hart_fatal,
            hart_estop=self.hart_estop,
        )
        self.submodules.monitor = FlowCompactEventMonitor(cpu)


class FaultRetireMonitorDut(Module):
    def __init__(self, depth=4, trigger_pc=None, stop_on_trigger=False):
        self.retire = Record(RETIRE_LAYOUT)
        cpu = SimpleNamespace(retires=[self.retire])
        self.submodules.monitor = FlowFaultRetireMonitor(
            cpu, depth=depth, trigger_pc=trigger_pc,
            stop_on_trigger=stop_on_trigger)


class CycleSnapshotMonitorDut(Module):
    def __init__(self, interval):
        self.retire = Record(RETIRE_LAYOUT)
        self.dcache = Record(DCACHE_TRACE_LAYOUT)
        cpu = SimpleNamespace(
            retires=[self.retire],
            dcache_traces=[self.dcache],
            msip=Signal(),
            mtip=Signal(),
            meip=Signal(),
            seip=Signal(),
            hart_fatal=Signal(),
            hart_estop=Signal(),
        )
        self.submodules.monitor = FlowCycleSnapshotMonitor(
            cpu, interval=interval)


class FlowWishboneMonitorTest(unittest.TestCase):
    def test_cycle_snapshot_respects_interval(self):
        dut = CycleSnapshotMonitorDut(interval=3)

        def process():
            for _ in range(8):
                yield

        captured = io.StringIO()
        with contextlib.redirect_stdout(captured):
            run_simulation(dut, process())
        snapshots = [
            line for line in captured.getvalue().splitlines()
            if line.startswith("[FLOW-CYCLE]")
        ]
        self.assertEqual(len(snapshots), 3)
        self.assertIn("cycle=0", snapshots[0])
        self.assertIn("cycle=3", snapshots[1])
        self.assertIn("cycle=6", snapshots[2])

    def test_records_request_and_response_without_driving_bus(self):
        dut = WishboneMonitorDut()

        def process():
            yield dut.bus.adr.eq(0x12001000 >> 3)
            yield dut.bus.sel.eq(0x10)
            yield dut.bus.we.eq(0)
            yield dut.bus.cyc.eq(1)
            yield dut.bus.stb.eq(1)
            yield
            self.assertEqual((yield dut.bus.cyc), 1)
            self.assertEqual((yield dut.bus.stb), 1)
            self.assertEqual((yield dut.bus.sel), 0x10)
            yield dut.bus.dat_r.eq(1 << 32)
            yield dut.bus.ack.eq(1)
            yield
            yield dut.bus.cyc.eq(0)
            yield dut.bus.stb.eq(0)
            yield dut.bus.ack.eq(0)
            yield

        captured = io.StringIO()
        with contextlib.redirect_stdout(captured):
            run_simulation(dut, process())
        output = captured.getvalue()
        self.assertIn("[WB-REQ] name=unit-mmio", output)
        self.assertIn("addr=0x12001000", output)
        self.assertIn("sel=0x10", output)
        self.assertIn("[WB-RSP] name=unit-mmio", output)
        self.assertIn("dat_r=0x100000000", output)

    def test_compact_stream_has_no_event_cap_and_records_irq_and_retire(self):
        dut = CompactEventMonitorDut()

        def process():
            yield
            yield dut.msip.eq(1)
            yield dut.retire.valid.eq(1)
            yield dut.retire.pc.eq(0x80000000)
            yield dut.retire.inst.eq(0x10500073)
            yield dut.retire.next_pc.eq(0x80000004)
            yield
            yield dut.retire.valid.eq(0)
            yield

        captured = io.StringIO()
        with contextlib.redirect_stdout(captured):
            run_simulation(dut, process())
        output = captured.getvalue()
        self.assertIn("[FLOW-EVENT] kind=I", output)
        self.assertIn("msip=0x1", output)
        self.assertIn("[FLOW-EVENT] kind=R hart=0", output)
        self.assertIn("pc=0x80000000", output)
        self.assertIn("inst=0x10500073", output)

    def test_fault_retire_ring_dumps_history_on_exact_bad_pc(self):
        bad_pc = 0xffffffff92bffbfe
        dut = FaultRetireMonitorDut(depth=4, trigger_pc=bad_pc)

        def process():
            yield dut.retire.valid.eq(1)
            yield dut.retire.pc.eq(0x0000003f928ff980)
            yield dut.retire.inst.eq(0x00008f6d)
            yield dut.retire.next_pc.eq(0x0000003f928ff982)
            yield
            yield dut.retire.pc.eq(0x0000003f928ff982)
            yield dut.retire.inst.eq(0x01071763)
            yield dut.retire.next_pc.eq(bad_pc)
            yield
            yield dut.retire.valid.eq(0)
            yield

        captured = io.StringIO()
        with contextlib.redirect_stdout(captured):
            run_simulation(dut, process())
        output = captured.getvalue()
        self.assertIn("[FLOW-FAULT-RETIRE] kind=T hart=0", output)
        self.assertIn("pc=0x3f928ff982", output)
        self.assertIn("next=0xffffffff92bffbfe", output)
        self.assertIn("[FLOW-FAULT-RETIRE] kind=H hart=0", output)
        self.assertIn("pc=0x3f928ff980", output)
        self.assertIn("[FLOW-FAULT-RETIRE] kind=STOP", output)

    def test_fault_retire_monitor_emits_verilator_stop(self):
        dut = FaultRetireMonitorDut(
            depth=2, trigger_pc=0xffffffff92bffbfe,
            stop_on_trigger=True)
        generated = str(verilog.convert(dut, name="fault_retire_monitor"))
        self.assertIn("$finish", generated)


if __name__ == "__main__":
    unittest.main()
