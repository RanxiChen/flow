#!/usr/bin/env python3
"""Generated-Verilog checks for expressions that must widen explicitly."""

import ast
import glob
import os
import sys
import unittest

from migen import Module, Signal
from migen.fhdl import verilog


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
WRAPPER_ROOT = os.path.join(FLOW_ROOT, "litex_wrapper")
if WRAPPER_ROOT not in sys.path:
    sys.path.insert(0, WRAPPER_ROOT)

from flow.wiring import pack_plic_sources, wishbone_byte_address  # noqa: E402


class WidthContractDut(Module):
    def __init__(self):
        self.irq = Signal()
        self.sources = Signal(31)
        self.word_address = Signal(32)
        self.byte_address = Signal(64)
        self.comb += [
            self.sources.eq(pack_plic_sources(self.irq)),
            self.byte_address.eq(
                wishbone_byte_address(self.word_address, data_width=64)),
        ]


class MigenWidthContractTest(unittest.TestCase):
    def test_project_migen_code_has_no_signal_left_shift(self):
        roots = (
            os.path.join(FLOW_ROOT, "sim", "litex", "*.py"),
            os.path.join(FLOW_ROOT, "litex_wrapper", "flow", "*.py"),
        )
        violations = []
        for pattern in roots:
            for path in glob.glob(pattern):
                with open(path, encoding="utf-8") as handle:
                    tree = ast.parse(handle.read(), filename=path)
                for node in ast.walk(tree):
                    if (isinstance(node, ast.BinOp) and
                            isinstance(node.op, ast.LShift) and
                            not isinstance(node.left, ast.Constant)):
                        violations.append(
                            f"{os.path.relpath(path, FLOW_ROOT)}:{node.lineno}")
        self.assertEqual(violations, [],
            "use explicit Cat/Constant packing instead of widening a Signal")

    def test_helpers_have_the_required_expression_width(self):
        irq = Signal()
        word_address = Signal(32)
        self.assertEqual(len(pack_plic_sources(irq)), 31)
        self.assertEqual(len(wishbone_byte_address(word_address, 64)), 35)

    def test_generated_verilog_uses_concatenation_not_narrow_shift(self):
        dut = WidthContractDut()
        source = str(verilog.convert(dut, ios={
            dut.irq, dut.sources, dut.word_address, dut.byte_address,
        }))
        self.assertNotIn("<<<", source)
        self.assertIn("assign sources = {21'd0, irq, 9'd0};", source)
        self.assertIn("assign byte_address = {word_address, 3'd0};", source)


if __name__ == "__main__":
    unittest.main()
