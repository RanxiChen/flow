package flow.fpu

import chisel3._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeFpUnitSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  private case class Response(result: BigInt, status: BigInt)

  private def idle(dut: BreezeFpUnit): Unit = {
    dut.io.flush.poke(false.B)
    dut.io.inValid.poke(false.B)
    dut.io.operandA.poke(0.U)
    dut.io.operandB.poke(0.U)
    dut.io.operandC.poke(0.U)
    dut.io.rm.poke(0.U)
    dut.io.operation.poke(0.U)
    dut.io.opMod.poke(false.B)
    dut.io.srcFmt.poke(BreezeFpFmt.D.U)
    dut.io.dstFmt.poke(BreezeFpFmt.D.U)
    dut.io.intFmt.poke(BreezeIntFmt.L.U)
  }

  private def transact(dut: BreezeFpUnit, operation: Int,
      a: BigInt, b: BigInt = 0, c: BigInt = 0,
      fmt: Int = BreezeFpFmt.D, rm: Int = 0,
      opMod: Boolean = false): Response = {
    dut.io.operation.poke(operation.U)
    dut.io.operandA.poke(a.U)
    dut.io.operandB.poke(b.U)
    dut.io.operandC.poke(c.U)
    dut.io.srcFmt.poke(fmt.U)
    dut.io.dstFmt.poke(fmt.U)
    dut.io.rm.poke(rm.U)
    dut.io.opMod.poke(opMod.B)
    dut.io.inValid.poke(true.B)
    var cycles = 0
    while (!dut.io.inReady.peek().litToBoolean && cycles < 100) {
      dut.clock.step(1)
      cycles += 1
    }
    dut.io.inReady.expect(true.B)
    dut.clock.step(1)
    dut.io.inValid.poke(false.B)
    cycles = 0
    while (!dut.io.outValid.peek().litToBoolean && cycles < 1000) {
      dut.clock.step(1)
      cycles += 1
    }
    dut.io.outValid.expect(true.B)
    Response(dut.io.result.peekValue().asBigInt,
      dut.io.status.peekValue().asBigInt)
  }

  "execute exact FP32/FP64 arithmetic and report IEEE exception flags" in {
    simulate(new BreezeFpUnit) { dut =>
      idle(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      // FPnew ADD consumes operands B/C; BreezeBackend uses this same mapping.
      val addS = transact(dut, BreezeFpOp.ADD,
        a = 0,
        b = BigInt("ffffffff3fc00000", 16),  // 1.5f
        c = BigInt("ffffffff40100000", 16),  // 2.25f
        fmt = BreezeFpFmt.S)
      addS.result mustBe BigInt("ffffffff40700000", 16) // 3.75f
      addS.status mustBe 0

      val mulD = transact(dut, BreezeFpOp.MUL,
        a = BigInt("3ff8000000000000", 16),  // 1.5
        b = BigInt("c000000000000000", 16)) // -2.0
      mulD.result mustBe BigInt("c008000000000000", 16) // -3.0
      mulD.status mustBe 0

      val divZero = transact(dut, BreezeFpOp.DIV,
        a = BigInt("3ff0000000000000", 16), b = 0)
      divZero.result mustBe BigInt("7ff0000000000000", 16)
      divZero.status mustBe 8 // DZ

      val invalid = transact(dut, BreezeFpOp.SQRT,
        a = BigInt("bff0000000000000", 16)) // sqrt(-1)
      (invalid.result & BigInt("7ff8000000000000", 16)) mustBe
        BigInt("7ff8000000000000", 16)
      invalid.status mustBe 16 // NV
    }
  }

  "flush an accepted long-latency operation without a stale response" in {
    simulate(new BreezeFpUnit) { dut =>
      idle(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.operation.poke(BreezeFpOp.DIV.U)
      dut.io.operandA.poke(BigInt("3ff0000000000000", 16).U)
      dut.io.operandB.poke(BigInt("4008000000000000", 16).U)
      dut.io.srcFmt.poke(BreezeFpFmt.D.U)
      dut.io.dstFmt.poke(BreezeFpFmt.D.U)
      dut.io.inValid.poke(true.B)
      dut.io.inReady.expect(true.B)
      dut.clock.step(1)
      dut.io.inValid.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.clock.step(1)
      dut.io.flush.poke(false.B)
      for (_ <- 0 until 80) {
        dut.io.outValid.expect(false.B)
        dut.clock.step(1)
      }
      dut.io.busy.expect(false.B)
    }
  }
}
