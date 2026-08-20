package flow.backend

import chisel3._
import flow.config.BackendConfig
import flow.fpu.BreezeFpChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeBackendFpMemorySpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  import BreezeBackendFpTestUtils._

  private def waitForRequest(dut: BreezeBackend): Unit = {
    var cycles = 0
    while (!dut.io.dmem.req.valid.peek().litToBoolean && cycles < 200) {
      dut.clock.step(1)
      cycles += 1
    }
    dut.io.dmem.req.valid.expect(true.B)
  }

  private def respond(dut: BreezeBackend, data: BigInt, writeAck: Boolean): Unit = {
    // First clock accepts the request and sets memWaitingRespReg.
    dut.clock.step(1)
    dut.io.dmem.rsp.valid.poke(true.B)
    dut.io.dmem.rsp.data.poke(data.U)
    dut.io.dmem.rsp.isWriteAck.poke(writeAck.B)
    dut.clock.step(1)
    dut.io.dmem.rsp.valid.poke(false.B)
    dut.io.dmem.rsp.isWriteAck.poke(false.B)
  }

  "load/store FP values with correct width, boxing and byte mask" in {
    simulate(new BreezeBackend(BackendConfig(), enabledebug = true)) { dut =>
      driveIdle(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pc = BigInt(0x800)
      def allocPc(): BigInt = { val result = pc; pc += 4; result }
      def run(inst: BigInt): BigInt = {
        issue(dut, allocPc(), inst)
        waitForWb(dut, inst)
      }
      enableFp(dut, () => allocPc())
      run(encodeAddi(1, 0, 0x100))

      val fld = encodeFpLoad(rd = 1, rs1 = 1, imm = 0, isDouble = true)
      issue(dut, allocPc(), fld)
      waitForRequest(dut)
      dut.io.dmem.req.isWrite.expect(false.B)
      dut.io.dmem.req.addr.expect(0x100.U)
      dut.io.dmem.req.sizeLog2.expect(3.U)
      respond(dut, BigInt("4008000000000000", 16), writeAck = false) // 3.0
      waitForWb(dut, fld)
      val moveD = encodeOpFp(0x1c, 1, 0, 0, rd = 2, rs1 = 1)
      run(moveD) mustBe BigInt("4008000000000000", 16)

      // 3.0f -> f2, then store at the upper word of the aligned 64-bit beat.
      run(encodeLui(3, 0x40400))
      run(encodeOpFp(0x1e, 0, 0, 0, rd = 2, rs1 = 3)) // FMV.W.X
      val fsw = encodeFpStore(rs2 = 2, rs1 = 1, imm = 4, isDouble = false)
      issue(dut, allocPc(), fsw)
      waitForRequest(dut)
      dut.io.dmem.req.isWrite.expect(true.B)
      dut.io.dmem.req.addr.expect(0x104.U)
      dut.io.dmem.req.sizeLog2.expect(2.U)
      dut.io.dmem.req.wmask.expect("hf0".U)
      dut.io.dmem.req.wdata.expect(BigInt("4040000040400000", 16).U)
      respond(dut, 0, writeAck = true)
      waitForWb(dut, fsw)
    }
  }

  "trap a misaligned FP64 load without issuing a memory request" in {
    simulate(new BreezeBackend(BackendConfig(), enabledebug = true)) { dut =>
      driveIdle(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pc = BigInt(0xa00)
      def allocPc(): BigInt = { val result = pc; pc += 4; result }
      enableFp(dut, () => allocPc())
      val base = encodeAddi(1, 0, 0x104)
      issue(dut, allocPc(), base)
      waitForWb(dut, base)
      val fld = encodeFpLoad(rd = 1, rs1 = 1, imm = 0, isDouble = true)
      issue(dut, allocPc(), fld)
      var trapped = false
      for (_ <- 0 until 50) {
        dut.io.dmem.req.valid.expect(false.B)
        if (dut.io.debug.get.memWbTrapValid.peek().litToBoolean) trapped = true
        dut.clock.step(1)
      }
      trapped mustBe true
      dut.io.debug.get.csrMcause.expect(4.U) // load address misaligned
    }
  }
}
