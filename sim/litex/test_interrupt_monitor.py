#!/usr/bin/env python3
"""Generated-Verilog contract for the passive interrupt-chain monitor."""

import os
import sys
import unittest
from types import SimpleNamespace

from migen import Module, Signal
from migen.fhdl import verilog


SIM_DIR = os.path.dirname(os.path.abspath(__file__))
if SIM_DIR not in sys.path:
    sys.path.insert(0, SIM_DIR)

from interrupt_monitor import FlowInterruptChainMonitor  # noqa: E402


class InterruptMonitorDut(Module):
    def __init__(self):
        self.uart_enable = Signal(2)
        self.uart_status = Signal(2)
        self.uart_pending = Signal(2)
        self.uart_irq = Signal()
        self.plic_sources = Signal(31)
        self.plic_pending = Signal(32)
        self.plic_claim = Signal(32)
        self.plic_meip = Signal()
        self.cpu_meip = Signal()
        self.retire_valid = Signal()
        self.retire_pc = Signal(64)
        self.retire_inst = Signal(32)

        uart = SimpleNamespace(ev=SimpleNamespace(
            enable=SimpleNamespace(storage=self.uart_enable),
            status=SimpleNamespace(status=self.uart_status),
            pending=SimpleNamespace(status=self.uart_pending),
            irq=self.uart_irq,
        ))
        plic = SimpleNamespace(
            sources=self.plic_sources,
            pending_bits=self.plic_pending,
            claims=[self.plic_claim, Signal(32)],
            meip=self.plic_meip,
        )
        cpu = SimpleNamespace(meip=self.cpu_meip)
        retire = SimpleNamespace(
            valid=self.retire_valid,
            pc=self.retire_pc,
            inst=self.retire_inst,
        )
        self.submodules.monitor = FlowInterruptChainMonitor(
            uart=uart, plic=plic, cpu=cpu, retire=retire)


class InterruptMonitorVerilogTest(unittest.TestCase):
    def test_display_arguments_are_verilog_values_not_python_objects(self):
        dut = InterruptMonitorDut()
        ios = {
            dut.uart_enable, dut.uart_status, dut.uart_pending, dut.uart_irq,
            dut.plic_sources, dut.plic_pending, dut.plic_claim,
            dut.plic_meip, dut.cpu_meip, dut.retire_valid, dut.retire_pc,
            dut.retire_inst,
        }
        source = str(verilog.convert(dut, ios=ios))
        self.assertNotIn("<migen.", source)
        self.assertIn("irq_chain_plic_source", source)
        self.assertIn("irq_chain_cpu_meip", source)


if __name__ == "__main__":
    unittest.main()
