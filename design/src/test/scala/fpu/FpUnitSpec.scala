package flow.fpu

import chisel3._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FpProbe extends FpUnit {
  val rawInFire = IO(Output(Bool()))
  val rawOutFire = IO(Output(Bool()))
  val draining = IO(Output(Bool()))
  val rawInTag = IO(Output(UInt(tagWidth.W)))
  val queued = IO(Output(UInt(2.W)))
  rawInFire := impl.io.in_valid_i && impl.io.in_ready_o
  rawOutFire := impl.io.out_valid_o && impl.io.out_ready_i
  draining := killDrain
  rawInTag := impl.io.tag_i
  queued := input.io.count
}

/** Component evidence only: this does not instantiate BreezeBackend or ID/WB.
  * Commit pulses are supplied at the specified EX+2 boundary by the testbench.
  */
class FpUnitSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  private val one = BigInt("3ff0000000000000", 16)
  private val three = BigInt("4008000000000000", 16)
  private val inf = BigInt("7ff0000000000000", 16)
  private val negInf = BigInt("fff0000000000000", 16)
  private def init(d: FpProbe): Unit = {
    d.io.req.valid.poke(false.B); d.io.commit.poke(false.B); d.io.killUncommitted.poke(false.B)
    d.io.result.ready.poke(true.B)
    d.io.req.bits.operandA.poke(one.U); d.io.req.bits.operandB.poke(one.U); d.io.req.bits.operandC.poke(one.U)
    d.io.req.bits.rm.poke(0.U); d.io.req.bits.operation.poke(BreezeFpOp.FMADD.U)
    d.io.req.bits.opMod.poke(false.B); d.io.req.bits.srcFmt.poke(BreezeFpFmt.D.U)
    d.io.req.bits.dstFmt.poke(BreezeFpFmt.D.U); d.io.req.bits.intFmt.poke(BreezeIntFmt.L.U)
    d.io.req.bits.rd.idx.poke(1.U); d.io.req.bits.rd.isFp.poke(true.B)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }
  private case class Response(result: BigInt, status: BigInt)
  private def transact(d: FpProbe, operation: Int, a: BigInt, b: BigInt = 0, c: BigInt = 0,
      fmt: Int = BreezeFpFmt.D, rm: Int = 0, opMod: Boolean = false): Response = {
    d.io.result.ready.poke(false.B)
    d.io.req.bits.operation.poke(operation.U); d.io.req.bits.operandA.poke(a.U)
    d.io.req.bits.operandB.poke(b.U); d.io.req.bits.operandC.poke(c.U)
    d.io.req.bits.srcFmt.poke(fmt.U); d.io.req.bits.dstFmt.poke(fmt.U)
    d.io.req.bits.rm.poke(rm.U); d.io.req.bits.opMod.poke(opMod.B)
    d.io.req.valid.poke(true.B)
    d.io.req.ready.expect(true.B); d.clock.step(); d.io.req.valid.poke(false.B)
    d.clock.step(); d.io.commit.poke(true.B); d.clock.step(); d.io.commit.poke(false.B)
    var cycles=0
    while(!d.io.result.valid.peek().litToBoolean && cycles<1000) { d.clock.step(); cycles+=1 }
    d.io.result.valid.expect(true.B)
    val response=Response(d.io.result.bits.data.peek().litValue,d.io.result.bits.flags.peek().litValue)
    d.clock.step(3); d.io.result.valid.expect(true.B)
    d.io.result.bits.data.expect(response.result.U); d.io.result.bits.flags.expect(response.status.U)
    d.io.result.ready.poke(true.B); d.clock.step(); d.io.result.ready.poke(false.B)
    response
  }
  "execute exact FP32/FP64 arithmetic and report IEEE exception flags" in {
    simulate(new FpProbe) { dut =>
      init(dut)
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

      // The pre-adder stage must preserve the unrounded product: separately
      // rounding the multiply would turn this exact -2^-104 into zero.
      val fusedD = transact(dut, BreezeFpOp.FMADD,
        a = BigInt("3ff0000000000001", 16),
        b = BigInt("3feffffffffffffe", 16),
        c = BigInt("bff0000000000000", 16))
      fusedD.result mustBe BigInt("b970000000000000", 16)
      fusedD.status mustBe 0

      val divZero = transact(dut, BreezeFpOp.DIV,
        a = BigInt("3ff0000000000000", 16), b = 0)
      divZero.result mustBe BigInt("7ff0000000000000", 16)
      divZero.status mustBe 8 // DZ

      val invalid = transact(dut, BreezeFpOp.SQRT,
        a = BigInt("bff0000000000000", 16)) // sqrt(-1)
      if (invalid.result != BigInt("7ff8000000000000", 16)) {
        fail(s"FSQRT.D(-1) non-canonical result=0x${invalid.result.toString(16)} " +
          s"status=0x${invalid.status.toString(16)}")
      }
      invalid.status mustBe 16 // NV
    }
  }

  "T14_component_T15_component_P08_component: eight consecutive FMA inputs and direct output handshakes" in {
    simulate(new FpProbe) { d =>
      init(d)
      val received = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
      for (cycle <- 0 until 20) {
        d.io.req.valid.poke((cycle < 8).B)
        d.io.req.bits.rd.idx.poke((if (cycle < 8) cycle else 0).U) // includes writable f0
        d.io.commit.poke((cycle >= 2 && cycle < 10).B)
        if (cycle < 8) d.io.req.ready.expect(true.B)
        d.rawInFire.expect((cycle >= 1 && cycle <= 8).B)
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
  "SOC3d FP32 five-stage stream plus input boundary preserves exact latency, II1, tags and exception flags" in {
    simulate(new FpProbe) { d =>
      init(d)
      def box(x: BigInt): BigInt = (BigInt("ffffffff", 16) << 32) | x
      // Independently known IEEE results: fused cancellation, invalid,
      // inexact subnormal and an RNE tie. All share the ADDMUL slice.
      val cases = Seq(
        (BreezeFpOp.FMADD, BigInt("3f800001",16), BigInt("3f7ffffe",16), BigInt("bf800000",16), Response(box(BigInt("a8800000",16)),0)),
        (BreezeFpOp.FMADD, BigInt("7f800000",16), BigInt(0), BigInt("3f800000",16), Response(box(BigInt("7fc00000",16)),16)),
        (BreezeFpOp.MUL, BigInt(1), BigInt("3f000000",16), BigInt(0), Response(box(BigInt(0)),3)),
        (BreezeFpOp.ADD, BigInt(0), BigInt("3f800000",16), BigInt("33800000",16), Response(box(BigInt("3f800000",16)),1)))
      val received = scala.collection.mutable.ArrayBuffer.empty[(Int,Int)]
      for (cycle <- 0 until 28) {
        d.io.req.valid.poke((cycle < 16).B)
        d.io.commit.poke((cycle >= 2 && cycle < 18).B)
        if (cycle < 16) {
          val (op,a,b,c,_) = cases(cycle % cases.size)
          d.io.req.bits.operation.poke(op.U)
          d.io.req.bits.srcFmt.poke(BreezeFpFmt.S.U); d.io.req.bits.dstFmt.poke(BreezeFpFmt.S.U)
          d.io.req.bits.operandA.poke(box(a).U); d.io.req.bits.operandB.poke(box(b).U)
          d.io.req.bits.operandC.poke(box(c).U); d.io.req.bits.rd.idx.poke(cycle.U)
          d.io.req.ready.expect(true.B)
        }
        d.rawInFire.expect((cycle >= 1 && cycle <= 16).B)
        if (d.io.result.valid.peek().litToBoolean) {
          val rd = d.io.result.bits.rd.idx.peek().litValue.toInt
          val expected = cases(rd % cases.size)._5
          d.io.result.bits.data.expect(expected.result.U); d.io.result.bits.flags.expect(expected.status.U)
          d.io.result.bits.rd.isFp.expect(true.B); d.rawOutFire.expect(true.B)
          received += ((rd,cycle))
        }
        d.clock.step()
      }
      received.toSeq mustBe (0 until 16).map(n => (n,n+6))
      d.io.busy.expect(false.B); d.io.committedFpr.expect(0.U)
    }
  }
  "SOC3d FP32 kill at the normalization boundary drains a committed stalled result and reuses tags" in {
    simulate(new FpProbe) { d =>
      init(d)
      val boxOne = BigInt("ffffffff3f800000",16)
      d.io.req.bits.srcFmt.poke(BreezeFpFmt.S.U); d.io.req.bits.dstFmt.poke(BreezeFpFmt.S.U)
      d.io.req.bits.operandA.poke(boxOne.U); d.io.req.bits.operandB.poke(boxOne.U)
      d.io.req.bits.operandC.poke(boxOne.U)
      d.io.result.ready.poke(false.B)
      // Fill both sides of the new boundary before kill. Only f1 commits.
      for (cycle <- 0 until 9) {
        d.io.req.valid.poke((cycle < 3).B)
        if (cycle < 3) { d.io.req.bits.rd.idx.poke((cycle+1).U); d.io.req.ready.expect(true.B) }
        d.io.commit.poke((cycle == 2).B)
        d.io.killUncommitted.poke((cycle == 5).B)
        if (cycle >= 6) {
          d.draining.expect(true.B); d.io.req.ready.expect(false.B)
          if (cycle >= 6) { d.io.result.valid.expect(true.B); d.io.result.bits.rd.idx.expect(1.U) }
          if (cycle >= 6) {
            d.io.result.bits.data.expect(BigInt("ffffffff40000000",16).U)
            d.io.result.bits.flags.expect(0.U)
          }
        }
        d.clock.step()
      }
      d.io.req.valid.poke(false.B); d.io.killUncommitted.poke(false.B)
      d.io.result.ready.poke(true.B)
      var returned = 0
      for (_ <- 0 until 16) {
        if (d.io.result.valid.peek().litToBoolean) { d.io.result.bits.rd.idx.expect(1.U); returned += 1 }
        d.clock.step()
      }
      returned mustBe 1; d.draining.expect(false.B); d.io.busy.expect(false.B)
      for (_ <- 0 until 40) {
        d.io.req.bits.rd.idx.poke(4.U)
        transact(d,BreezeFpOp.FMADD,boxOne,boxOne,boxOne,BreezeFpFmt.S) mustBe
          Response(BigInt("ffffffff40000000",16),0)
      }
      d.io.busy.expect(false.B); d.io.committedFpr.expect(0.U)
    }
  }
  "SOC3d rounding boundary preserves all modes, subnormals, overflow and fused cancellation" in {
    simulate(new FpProbe) { d =>
      init(d)
      for ((fmt, bits, frac, bias) <- Seq((BreezeFpFmt.S, 32, 23, 127), (BreezeFpFmt.D, 64, 52, 1023))) {
        def box(x: BigInt): BigInt = if (bits == 32) (BigInt("ffffffff", 16) << 32) | x else x
        val oneBits = BigInt(bias) << frac
        val halfUlp = BigInt(bias - frac - 1) << frac
        val neg = BigInt(1) << (bits - 1)
        for (rm <- 0 until 5) {
          val positive = transact(d, BreezeFpOp.ADD, 0, box(oneBits), box(halfUlp), fmt, rm)
          positive mustBe Response(box(oneBits + (if (rm == 3 || rm == 4) 1 else 0)), 1)
          val negative = transact(d, BreezeFpOp.ADD, 0, box(neg | oneBits), box(neg | halfUlp), fmt, rm)
          negative mustBe Response(box(neg | (oneBits + (if (rm == 2 || rm == 4) 1 else 0))), 1)
          val zero = transact(d, BreezeFpOp.ADD, 0, box(oneBits), box(neg | oneBits), fmt, rm)
          zero mustBe Response(box(if (rm == 2) neg else BigInt(0)), 0)
          val tiny = transact(d, BreezeFpOp.MUL, box(BigInt(1)), box(BigInt(bias - 1) << frac), fmt = fmt, rm = rm)
          tiny mustBe Response(box(if (rm == 3 || rm == 4) BigInt(1) else BigInt(0)), 3)
          val max = ((BigInt(1) << (bits - 1)) - (BigInt(1) << frac)) - 1
          val over = transact(d, BreezeFpOp.MUL, box(max), box(BigInt(bias + 1) << frac), fmt = fmt, rm = rm)
          over mustBe Response(box(if (rm == 1 || rm == 2) max else max + 1), 5)
        }
        val normal = transact(d, BreezeFpOp.MUL, box(BigInt(1) << frac), box(oneBits), fmt = fmt)
        normal mustBe Response(box(BigInt(1) << frac), 0)
        val sub = transact(d, BreezeFpOp.MUL, box(BigInt(1) << frac), box(BigInt(bias - 1) << frac), fmt = fmt)
        sub mustBe Response(box(BigInt(1) << (frac - 1)), 0)
        // Tininess is checked after rounding to precision, before limiting
        // the exponent range. A tie at min-normal still has UF; a product
        // just below min-normal that rounds to normal at full precision does not.
        val tinyBoundary = transact(d, BreezeFpOp.MUL,
          box(BigInt(1) << frac), box(oneBits - 1), fmt = fmt)
        tinyBoundary mustBe Response(box(BigInt(1) << frac), 3)
        val normalBoundary = transact(d, BreezeFpOp.MUL,
          box((BigInt(1) << frac) + 1), box(oneBits - 2), fmt = fmt)
        normalBoundary mustBe Response(box(BigInt(1) << frac), 1)
        // (1+2^-p)*(1-2^-p)-1 = -2^(-2p), one final rounding.
        val fused = transact(d, BreezeFpOp.FMADD, box(oneBits + 1), box(oneBits - 2), box(neg | oneBits), fmt)
        fused mustBe Response(box(neg | (BigInt(bias - 2 * frac) << frac)), 0)
      }
    }
  }
  "SOC3d mixed FP32 FP64 stream preserves data flags and destinations under random output stalls" in {
    simulate(new FpProbe) { d =>
      init(d)
      val rng = new scala.util.Random(0x3df00dL)
      val expected = scala.collection.mutable.Map.empty[Int, Response]
      val seen = scala.collection.mutable.Set.empty[Int]
      var issued = 0
      var commits = List(false, false)
      var stalls = 0
      for (cycle <- 0 until 180) {
        val ready = cycle >= 24 && rng.nextBoolean()
        d.io.result.ready.poke(ready.B); d.io.commit.poke(commits.head.B)
        val sending = issued < 16
        d.io.req.valid.poke(sending.B)
        if (sending) {
          val single = issued % 2 == 0
          d.io.req.bits.srcFmt.poke((if (single) BreezeFpFmt.S else BreezeFpFmt.D).U)
          d.io.req.bits.dstFmt.poke((if (single) BreezeFpFmt.S else BreezeFpFmt.D).U)
          d.io.req.bits.operation.poke(BreezeFpOp.ADD.U); d.io.req.bits.rd.idx.poke(issued.U)
          d.io.req.bits.rm.poke((issued % 5).U)
          val special = issued % 4 < 2
          val b = if (special) { if (single) BigInt("ffffffff7f800000", 16) else inf }
            else { if (single) BigInt("ffffffff3f800000", 16) else one }
          val c = if (special) { if (single) BigInt("ffffffffff800000", 16) else negInf }
            else { if (single) BigInt("ffffffff33800000", 16) else BigInt("3ca0000000000000", 16) }
          d.io.req.bits.operandB.poke(b.U); d.io.req.bits.operandC.poke(c.U)
          val result = if (special) {
            if (single) BigInt("ffffffff7fc00000", 16) else BigInt("7ff8000000000000", 16)
          } else b + (if (issued % 5 == 3 || issued % 5 == 4) 1 else 0)
          expected(issued) = Response(result, if (special) 16 else 1)
        }
        val fire = sending && d.io.req.ready.peek().litToBoolean
        if (d.io.result.valid.peek().litToBoolean) {
          val rd = d.io.result.bits.rd.idx.peek().litValue.toInt
          val actual = Response(d.io.result.bits.data.peek().litValue, d.io.result.bits.flags.peek().litValue)
          actual mustBe expected(rd); d.io.result.bits.rd.isFp.expect(true.B)
          // Cross-unit arbitration may change its selected tag while stalled
          // (backend-v1 spec §5); account completions only on actual fire.
          if (ready) { seen.contains(rd) mustBe false; seen += rd }
          else stalls += 1
        }
        d.clock.step(); commits = commits.tail :+ fire
        if (fire) issued += 1
      }
      issued mustBe 16; seen.toSet mustBe (0 until 16).toSet
      stalls must be > 0
      d.io.busy.expect(false.B); d.io.committedFpr.expect(0.U)
    }
  }
  "P07_component_S04_S08: cross-unit out-of-order results and cumulative flags" in {
    simulate(new FpProbe) { d =>
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
    simulate(new FpProbe) { d =>
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
    simulate(new FpProbe) { d =>
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
  "SOC3c D2 same-edge FP fire/kill drains its tag before forty consecutive new requests" in {
    simulate(new FpProbe) { d =>
      init(d)
      d.io.req.bits.rd.idx.poke(31.U)
      d.io.req.valid.poke(true.B); d.io.killUncommitted.poke(true.B)
      d.io.req.ready.expect(true.B); d.rawInFire.expect(false.B)
      d.clock.step()
      d.io.killUncommitted.poke(false.B)
      d.draining.expect(true.B)
      var drainCycles = 0
      while (d.draining.peek().litToBoolean && drainCycles < 100) {
        d.io.req.ready.expect(false.B)
        d.io.result.valid.expect(false.B)
        d.clock.step(); drainCycles += 1
      }
      d.draining.expect(false.B)
      val returned = scala.collection.mutable.ArrayBuffer.empty[Int]
      for (cycle <- 0 until 60) {
        d.io.req.valid.poke((cycle < 40).B)
        d.io.req.bits.rd.idx.poke((cycle % 30 + 1).U)
        d.io.commit.poke((cycle >= 2 && cycle < 42).B)
        if (cycle < 40) d.io.req.ready.expect(true.B)
        d.rawInFire.expect((cycle >= 1 && cycle <= 40).B)
        if (d.io.result.valid.peek().litToBoolean) {
          d.io.result.bits.data.expect(BigInt("4000000000000000", 16).U)
          d.io.result.bits.flags.expect(0.U)
          returned += d.io.result.bits.rd.idx.peek().litValue.toInt
        }
        d.clock.step()
      }
      returned.toSeq mustBe (0 until 40).map(_ % 30 + 1)
      d.io.busy.expect(false.B); d.io.committedFpr.expect(0.U)
    }
  }
  "SOC3d-combined input buffer holds two requests across CVFPU stalls without loss or duplicate issue" in {
    simulate(new FpProbe) { d =>
      init(d)
      val waiting = scala.collection.mutable.Queue.empty[Int]
      val returned = scala.collection.mutable.ArrayBuffer.empty[Int]
      var issued = 0
      var delayedCommits = List(false, false)
      var fullStalls = 0
      for (cycle <- 0 until 100) {
        d.io.result.ready.poke((cycle >= 35).B)
        d.io.commit.poke(delayedCommits.head.B)
        d.io.req.valid.poke((issued < 20).B)
        d.io.req.bits.rd.idx.poke((issued + 1).U)
        val enqueue = issued < 20 && d.io.req.ready.peek().litToBoolean
        if (d.rawInFire.peek().litToBoolean) {
          waiting.nonEmpty mustBe true
          d.rawInTag.peek().litValue.toInt mustBe waiting.dequeue()
        }
        if (cycle < 35 && d.queued.peek().litValue == 2) {
          d.io.req.ready.expect(false.B); fullStalls += 1
        }
        if (d.io.result.valid.peek().litToBoolean && cycle >= 35) {
          d.io.result.bits.data.expect(BigInt("4000000000000000",16).U)
          d.io.result.bits.flags.expect(0.U)
          returned += d.io.result.bits.rd.idx.peek().litValue.toInt
        }
        if (enqueue) { waiting.enqueue(issued); issued += 1 }
        d.clock.step(); delayedCommits = delayedCommits.tail :+ enqueue
      }
      issued mustBe 20; fullStalls must be > 0
      waiting mustBe empty; returned.toSeq mustBe (1 to 20)
      d.io.busy.expect(false.B); d.queued.expect(0.U)
    }
  }
  "SOC3d-combined kill preserves committed queued DIV and cancels speculative requests before CVFPU issue" in {
    simulate(new FpProbe) { d =>
      init(d)
      d.io.req.bits.operation.poke(BreezeFpOp.DIV.U)
      d.io.req.bits.operandB.poke(three.U)
      var accepted = 0
      var committed = 0
      var delayedCommits = List(false, false)
      val results = scala.collection.mutable.ArrayBuffer.empty[Int]
      var heldCommitted = false
      for (cycle <- 0 until 250) {
        val killing = cycle == 8
        val send = cycle < 8 && accepted < 5
        d.io.req.valid.poke(send.B); d.io.req.bits.rd.idx.poke((accepted + 1).U)
        d.io.commit.poke(delayedCommits.head.B); d.io.killUncommitted.poke(killing.B)
        val enqueue = send && d.io.req.ready.peek().litToBoolean
        if (delayedCommits.head) committed += 1
        if (killing) { heldCommitted = d.queued.peek().litValue > 0 }
        if (d.io.result.valid.peek().litToBoolean) {
          d.io.result.bits.data.expect(BigInt("3fd5555555555555",16).U)
          d.io.result.bits.flags.expect(1.U)
          results += d.io.result.bits.rd.idx.peek().litValue.toInt
        }
        d.clock.step()
        // Last accepted request remains speculative, including when queued.
        delayedCommits = delayedCommits.tail :+ (enqueue && accepted < 2)
        if (enqueue) accepted += 1
      }
      heldCommitted mustBe true; committed mustBe 2
      results.toSeq mustBe Seq(1,2)
      d.io.busy.expect(false.B); d.draining.expect(false.B); d.queued.expect(0.U)
      d.io.req.bits.rd.idx.poke(6.U)
      transact(d, BreezeFpOp.FMADD,one,one,one) mustBe Response(BigInt("4000000000000000",16),0)
    }
  }

}
