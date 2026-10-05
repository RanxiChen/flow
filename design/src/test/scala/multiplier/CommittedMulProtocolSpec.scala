package flow.multiplier

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.core.MUL_OP
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CommittedMulProtocolSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val mask = (BigInt(1) << 64) - 1
  private def init(d: CommittedMulUnit): Unit = {
    d.io.req.valid.poke(false.B); d.io.commit.poke(false.B)
    d.io.killUncommitted.poke(false.B); d.io.result.ready.poke(false.B)
    d.io.req.bits.a.poke(0.S); d.io.req.bits.b.poke(0.S)
    d.io.req.bits.op.poke(MUL_OP.MUL.U); d.io.req.bits.rd.poke(1.U)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
  }
  private def send(d: CommittedMulUnit, rd: Int, a: BigInt, b: BigInt, op: Int = MUL_OP.MUL): Unit = {
    d.io.req.ready.expect(true.B)
    d.io.req.bits.rd.poke(rd.U); d.io.req.bits.a.poke(a.S(65.W)); d.io.req.bits.b.poke(b.S(65.W))
    d.io.req.bits.op.poke(op.U); d.io.req.valid.poke(true.B); d.clock.step(); d.io.req.valid.poke(false.B)
  }
  "T05: kill in each of P1/P2/P3/P4 and reset in flight discard uncommitted requests" in {
    simulate(new CommittedMulUnit) { d =>
      for (stage <- 1 to 4) {
        init(d); send(d, 5, -7, 9); d.clock.step(stage-1)
        d.io.killUncommitted.poke(true.B); d.clock.step(); d.io.killUncommitted.poke(false.B)
        d.io.result.ready.poke(true.B); d.clock.step(6); d.io.result.valid.expect(false.B)
      }
      init(d); send(d, 5, 3, 4); d.io.commit.poke(true.B); d.clock.step()
      init(d); d.io.result.ready.poke(true.B); d.clock.step(6); d.io.result.valid.expect(false.B)
    }
  }
  "T05/T06/T09/T15: stopped uncommitted P4, oldest commit then kill, and committed hold" in {
    simulate(new CommittedMulUnit) { d =>
      init(d)
      for (r <- 1 to 4) send(d, r, r, 11)
      d.io.req.ready.expect(false.B); d.io.result.valid.expect(false.B)
      d.clock.step(9); d.io.req.ready.expect(false.B)
      d.io.commit.poke(true.B); d.io.killUncommitted.poke(true.B)
      d.clock.step(); d.io.commit.poke(false.B); d.io.killUncommitted.poke(false.B)
      d.io.result.valid.expect(true.B); d.io.result.bits.rd.expect(1.U); d.io.result.bits.data.expect(11.U)
      d.io.killUncommitted.poke(true.B); d.clock.step(8)
      d.io.result.valid.expect(true.B); d.io.result.bits.rd.expect(1.U); d.io.result.bits.data.expect(11.U)
      d.io.killUncommitted.poke(false.B); d.io.result.ready.poke(true.B); d.clock.step(6)
      d.io.result.valid.expect(false.B)
      send(d, 4, 7, 9); d.clock.step(3)
      d.io.result.valid.expect(false.B); d.io.req.ready.expect(false.B)
      d.io.killUncommitted.poke(true.B); d.clock.step(); d.io.killUncommitted.poke(false.B)
      d.io.req.ready.expect(true.B); d.clock.step(5); d.io.result.valid.expect(false.B)
    }
  }
  "B/U01: all five operations agree with independent BigInt mathematics (seed 0x701, 500 requests)" in {
    simulate(new CommittedMulUnit) { d =>
      init(d); val rng = new scala.util.Random(0x701)
      val operations = Vector(MUL_OP.MUL, MUL_OP.MULH, MUL_OP.MULHSU, MUL_OP.MULHU, MUL_OP.MULW)
      for (i <- 0 until 500) {
        val operation = operations(i % 5)
        val au = BigInt(64, rng); val bu = BigInt(64, rng)
        def signed(x: BigInt, width: Int): BigInt = if (x.testBit(width - 1)) x - (BigInt(1) << width) else x
        val a = if (operation == MUL_OP.MULHU) au else if (operation == MUL_OP.MULW) signed(au & ((BigInt(1)<<32)-1),32) else signed(au,64)
        val b = if (operation == MUL_OP.MULHU || operation == MUL_OP.MULHSU) bu else if (operation == MUL_OP.MULW) signed(bu & ((BigInt(1)<<32)-1),32) else signed(bu,64)
        val full = a * b
        val expected = (if (operation == MUL_OP.MULW) signed(full & ((BigInt(1)<<32)-1),32)
          else if (operation == MUL_OP.MUL) full else full >> 64) & mask
        send(d, 1 + i % 31, a, b, operation)
        val delay = rng.nextInt(7); d.clock.step(delay)
        d.io.result.valid.expect(false.B)
        d.io.commit.poke(true.B); d.clock.step(); d.io.commit.poke(false.B)
        var waited = 0
        while (!d.io.result.valid.peek().litToBoolean && waited < 4) { d.clock.step(); waited += 1 }
        d.io.result.valid.expect(true.B); d.io.result.bits.rd.expect((1+i%31).U)
        d.io.result.bits.data.peekValue().asBigInt mustBe expected
        val hold = rng.nextInt(10); d.clock.step(hold)
        d.io.result.bits.data.peekValue().asBigInt mustBe expected
        d.io.result.ready.poke(true.B); d.clock.step(); d.io.result.ready.poke(false.B)
        d.io.result.valid.expect(false.B)
      }
    }
  }
  "T15: four-cycle latency and one accepted request per cycle with in-order commits" in {
    simulate(new CommittedMulUnit) { d =>
      init(d); d.io.result.ready.poke(true.B)
      for (n <- 0 until 12) {
        d.io.req.valid.poke((n < 8).B); d.io.req.bits.rd.poke((1 + math.min(n,7)).U)
        d.io.req.bits.a.poke((n+1).S); d.io.req.bits.b.poke(13.S)
        d.io.commit.poke((n >= 2 && n < 10).B)
        d.io.req.ready.expect(true.B)
        if (n >= 4) {
          d.io.result.valid.expect(true.B); d.io.result.bits.rd.expect((n-3).U)
          d.io.result.bits.data.expect(((n-3)*13).U)
        } else d.io.result.valid.expect(false.B)
        d.clock.step()
      }
      d.io.commit.poke(false.B); d.io.result.valid.expect(false.B)
    }
  }
}

/** Align the unmodified three-cycle SignedMul65x65 reference with new P4. */
class MulProductProbe extends CommittedMulUnit {
  val fullProduct = IO(Output(UInt(130.W)))
  fullProduct := product(3).asUInt
}

class MulEquivalenceHarness extends Module {
  val io = IO(new Bundle {
    val valid = Input(Bool()); val a = Input(SInt(65.W)); val b = Input(SInt(65.W))
    val actual = Output(UInt(130.W)); val reference = Output(UInt(130.W)); val validOut = Output(Bool())
  })
  val current = Module(new MulProductProbe)
  val original = Module(new SignedMul65x65)
  current.io.req.valid := io.valid
  current.io.req.bits.a := io.a; current.io.req.bits.b := io.b
  val nextRd = RegInit(1.U(5.W))
  when(io.valid) { nextRd := Mux(nextRd === 31.U, 1.U, nextRd + 1.U) }
  current.io.req.bits.rd := nextRd; current.io.req.bits.op := MUL_OP.MUL.U
  current.io.commit := RegNext(RegNext(io.valid, false.B), false.B)
  current.io.killUncommitted := false.B; current.io.result.ready := true.B
  original.io.in_valid := io.valid; original.io.a := io.a; original.io.b := io.b
  io.actual := current.fullProduct
  io.reference := RegNext(original.io.product.asUInt)
  io.validOut := current.io.result.valid
  assert(current.io.result.valid === RegNext(original.io.out_valid, false.B))
}

class MulEquivalenceSpec extends AnyFreeSpec with Matchers with ChiselSim {
  "U01: new full product equals the unmodified reference and BigInt (seed 0x703, 1000 requests)" in {
    simulate(new MulEquivalenceHarness) { d =>
      d.io.valid.poke(false.B); d.io.a.poke(0.S); d.io.b.poke(0.S)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val rng = new scala.util.Random(0x703); val pending = scala.collection.mutable.Queue[BigInt]()
      val mask130 = (BigInt(1)<<130)-1
      for (cycle <- 0 until 1004) {
        if (d.io.validOut.peek().litToBoolean) {
          val expected = pending.dequeue()
          d.io.actual.peekValue().asBigInt mustBe expected
          d.io.reference.peekValue().asBigInt mustBe expected
        }
        val active = cycle < 1000
        d.io.valid.poke(active.B)
        if (active) {
          val a = BigInt(65,rng)-(BigInt(1)<<64); val b = BigInt(65,rng)-(BigInt(1)<<64)
          d.io.a.poke(a.S(65.W)); d.io.b.poke(b.S(65.W)); pending.enqueue((a*b)&mask130)
        }
        d.clock.step()
      }
      pending mustBe empty
    }
  }
}
