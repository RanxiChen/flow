"""Transport ownership is stable under command/response backpressure."""
import sys
from pathlib import Path
from migen import Record
from migen.sim import run_simulation

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "litex_wrapper"))
from flow.pcie import FaseArbiter


def test_arbiter():
    target = Record([("cmd_valid",1),("cmd_ready",1),("cmd_opcode",8),
        ("cmd_index",6),("cmd_data",64),("cmd_pc",64),("rsp_valid",1),
        ("rsp_ready",1),("rsp_error",1),("rsp_data",64)])
    dut = FaseArbiter(target)

    def bench():
        yield dut.jtag.cmd_valid.eq(1)
        yield dut.jtag.cmd_opcode.eq(11)
        yield dut.pcie.cmd_opcode.eq(22)
        for _ in range(4):
            yield
        # PCIe arrives while JTAG command is stalled at the core.
        yield dut.pcie.cmd_valid.eq(1)
        for _ in range(4):
            yield
            assert (yield target.cmd_valid) and (yield target.cmd_opcode) == 11
        yield target.cmd_ready.eq(1)
        yield
        yield
        yield dut.jtag.cmd_valid.eq(0)
        yield target.rsp_valid.eq(1)
        yield target.rsp_data.eq(123)
        for _ in range(4):
            yield
            assert (yield dut.jtag.rsp_valid)
            assert not (yield dut.pcie.rsp_valid)
            assert not (yield dut.pcie.cmd_ready)
        yield dut.jtag.rsp_ready.eq(1)
        yield
        yield
        yield target.rsp_valid.eq(0)
        for _ in range(3):
            yield
        yield dut.pcie.cmd_valid.eq(0)
        yield target.rsp_valid.eq(1)
        yield target.rsp_data.eq(456)
        yield
        yield
        assert (yield dut.pcie.rsp_valid) and (yield dut.pcie.rsp_data) == 456
        assert not (yield dut.jtag.rsp_valid)
        yield dut.pcie.rsp_ready.eq(1)
        yield
    run_simulation(dut, bench())


if __name__ == "__main__":
    test_arbiter()
    print("FLOW_PCIE_ARBITER_PASS")
