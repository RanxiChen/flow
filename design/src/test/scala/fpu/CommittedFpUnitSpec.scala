package flow.fpu

import chisel3._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CommittedFpProbe extends CommittedFpUnit {
  val rawInFire = IO(Output(Bool()))
  val rawOutFire = IO(Output(Bool()))
  val draining = IO(Output(Bool()))
  rawInFire := impl.io.in_valid_i && impl.io.in_ready_o
  rawOutFire := impl.io.out_valid_o && impl.io.out_ready_i
  draining := killDrain
}

/** Component evidence only: this does not instantiate BreezeBackend or ID/WB.
  * Commit pulses are supplied at the specified EX+2 boundary by the testbench.
  */
class CommittedFpUnitSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  private val one = BigInt("3ff0000000000000", 16)
  private val three = BigInt("4008000000000000", 16)
  private val inf = BigInt("7ff0000000000000", 16)
  private val negInf = BigInt("fff0000000000000", 16)
  private def init(d: CommittedFpProbe): Unit = {
    d.io.req.valid.poke(false.B); d.io.commit.poke(false.B); d.io.killUncommitted.poke(false.B)
    d.io.result.ready.poke(true.B)
    d.io.req.bits.operandA.poke(one.U); d.io.req.bits.operandB.poke(one.U); d.io.req.bits.operandC.poke(one.U)
    d.io.req.bits.rm.poke(0.U); d.io.req.bits.operation.poke(BreezeFpOp.FMADD.U)
    d.io.req.bits.opMod.poke(false.B); d.io.req.bits.srcFmt.poke(BreezeFpFmt.D.U)
    d.io.req.bits.dstFmt.poke(BreezeFpFmt.D.U); d.io.req.bits.intFmt.poke(BreezeIntFmt.L.U)
    d.io.req.bits.rd.idx.poke(1.U); d.io.req.bits.rd.isFp.poke(true.B)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }
  "T14_component_T15_component_P08_component: eight consecutive FMA inputs and direct output handshakes" in {
    simulate(new CommittedFpProbe) { d =>
      init(d)
      val received = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
      for (cycle <- 0 until 20) {
        d.io.req.valid.poke((cycle < 8).B)
        d.io.req.bits.rd.idx.poke((if (cycle < 8) cycle else 0).U) // includes writable f0
        d.io.commit.poke((cycle >= 2 && cycle < 10).B)
        if (cycle < 8) { d.io.req.ready.expect(true.B); d.rawInFire.expect(true.B) }
        if (d.io.result.valid.peek().litToBoolean) {
          d.rawOutFire.expect(true.B)
          d.io.result.bits.data.expect(BigInt("4000000000000000", 16).U) // 1*1+1
          d.io.result.bits.flags.expect(0.U); d.io.result.bits.rd.isFp.expect(true.B)
          received += ((d.io.result.bits.rd.idx.peek().litValue.toInt, cycle))
        }
        d.clock.step()
      }
      received.map(_._1).toSeq mustBe (0 until 8)
      received.map(_._2).sliding(2).foreach(pair => pair(1) mustBe pair(0) + 1)
      d.io.committedFpr.expect(0.U); d.io.busy.expect(false.B)
    }
  }
  "P07_component_S04_S08: cross-unit out-of-order results and cumulative flags" in {
    simulate(new CommittedFpProbe) { d =>
      init(d)
      val received = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
      var flags = BigInt(0)
      for (cycle <- 0 until 150) {
        d.io.req.valid.poke((cycle < 2).B); d.io.commit.poke((cycle == 2 || cycle == 3).B)
        if (cycle == 0) {
          d.io.req.bits.operation.poke(BreezeFpOp.DIV.U); d.io.req.bits.operandA.poke(one.U)
          d.io.req.bits.operandB.poke(three.U); d.io.req.bits.rd.idx.poke(1.U)
        } else if (cycle == 1) {
          d.io.req.bits.operation.poke(BreezeFpOp.ADD.U)
          d.io.req.bits.operandB.poke(inf.U); d.io.req.bits.operandC.poke(negInf.U)
          d.io.req.bits.rd.idx.poke(2.U)
        }
        if (cycle < 2) d.io.req.ready.expect(true.B)
        if (d.io.result.valid.peek().litToBoolean) {
          val rd = d.io.result.bits.rd.idx.peek().litValue.toInt
          val expect = if (rd == 1) BigInt("3fd5555555555555", 16) else BigInt("7ff8000000000000", 16)
          d.io.result.bits.data.expect(expect.U)
          flags |= d.io.result.bits.flags.peek().litValue
          received += ((rd, cycle))
        }
        d.clock.step()
      }
      received.map(_._1).toSeq mustBe Seq(2, 1)
      flags mustBe BigInt(17) // FADD NV | FDIV NX, independent of result order
      d.io.committedFpr.expect(0.U); d.io.busy.expect(false.B)
    }
  }
  "S05_S15: commit-before-kill preserves old DIV, drops young FMA, drains tags before reuse" in {
    simulate(new CommittedFpProbe) { d =>
      init(d)
      val received = scala.collection.mutable.ArrayBuffer.empty[Int]
      for (cycle <- 0 until 150) {
        d.io.req.valid.poke((cycle < 2).B)
        d.io.commit.poke((cycle == 2).B); d.io.killUncommitted.poke((cycle == 2).B)
        if (cycle == 0) {
          d.io.req.bits.operation.poke(BreezeFpOp.DIV.U)
          d.io.req.bits.operandB.poke(three.U); d.io.req.bits.rd.idx.poke(1.U)
        } else if (cycle == 1) {
          d.io.req.bits.operation.poke(BreezeFpOp.FMADD.U)
          d.io.req.bits.operandB.poke(one.U); d.io.req.bits.rd.idx.poke(2.U)
        }
        if (cycle < 2) d.io.req.ready.expect(true.B)
        if (d.draining.peek().litToBoolean) d.io.req.ready.expect(false.B)
        if (d.io.result.valid.peek().litToBoolean) received += d.io.result.bits.rd.idx.peek().litValue.toInt
        d.clock.step()
      }
      received.toSeq mustBe Seq(1)
      d.io.busy.expect(false.B); d.io.committedFpr.expect(0.U)
      // Wrap the allocator past killed tags with real returned operations.
      for (n <- 0 until 40) {
        d.io.req.bits.operation.poke(BreezeFpOp.FMADD.U); d.io.req.bits.rd.idx.poke(2.U)
        d.io.req.bits.operandA.poke(one.U); d.io.req.bits.operandB.poke(one.U)
        d.io.req.bits.operandC.poke(one.U); d.io.req.bits.rd.isFp.poke((n % 2 == 0).B)
        d.io.req.valid.poke(true.B); d.io.req.ready.expect(true.B); d.clock.step()
        d.io.req.valid.poke(false.B); d.clock.step(); d.io.commit.poke(true.B)
        d.clock.step(); d.io.commit.poke(false.B)
        var waited = 0
        while (!d.io.result.valid.peek().litToBoolean && waited < 100) { d.clock.step(); waited += 1 }
        d.io.result.valid.expect(true.B); d.io.result.bits.rd.idx.expect(2.U)
        d.io.result.bits.rd.isFp.expect((n % 2 == 0).B)
        d.io.result.bits.data.expect(BigInt("4000000000000000", 16).U)
        d.clock.step(); d.io.busy.expect(false.B)
      }
    }
  }
  "S01_S08_S14: FP-to-x0 preserves conversion flags and explicit CSR drain state" in {
    simulate(new CommittedFpProbe) { d =>
      init(d)
      d.io.req.bits.operation.poke(BreezeFpOp.F2I.U)
      d.io.req.bits.operandA.poke(BigInt("3ff8000000000000", 16).U) // 1.5 -> 1, RTZ
      d.io.req.bits.rm.poke(1.U); d.io.req.bits.intFmt.poke(BreezeIntFmt.W.U)
      d.io.req.bits.rd.idx.poke(0.U); d.io.req.bits.rd.isFp.poke(false.B)
      var writes = 0
      for (cycle <- 0 until 30) {
        d.io.req.valid.poke((cycle == 0).B); d.io.commit.poke((cycle == 2).B)
        d.io.committedGpr.expect(0.U); d.io.committedFpr.expect(0.U)
        if (cycle > 2 && writes == 0) d.io.committedFlagsOnly.expect(true.B)
        if (d.io.result.valid.peek().litToBoolean) {
          d.io.result.bits.rd.idx.expect(0.U); d.io.result.bits.rd.isFp.expect(false.B)
          d.io.result.bits.data.expect(1.U); d.io.result.bits.flags.expect(1.U)
          writes += 1
        }
        d.clock.step()
      }
      writes mustBe 1; d.io.committedFlagsOnly.expect(false.B); d.io.busy.expect(false.B)
    }
  }
}
