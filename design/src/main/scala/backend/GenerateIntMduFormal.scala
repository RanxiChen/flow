package flow.backend

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import flow.multiplier.CommittedMulUnit
import flow.divider.CommittedDivUnit

/** Formal-only observations; the production unit interfaces are unchanged. */
class FormalMulProbe extends CommittedMulUnit {
  val state = IO(Output(new Bundle {
    val live = UInt(3.W)
    val authorized = UInt(3.W)
    val p4Uncommitted = Bool()
    val readyByState = Bool()
    val stageValid = UInt(4.W)
    val stageCommitted = UInt(4.W)
    val stageRd = Vec(4, UInt(5.W))
    val stageProduct = Vec(4, UInt(130.W))
    val stageOp = Vec(4, UInt(3.W))
    val incomingProduct = UInt(130.W)
  }))
  state.live := PopCount(valid)
  state.authorized := PopCount((0 until 4).map(i => valid(i) && committed(i)))
  state.p4Uncommitted := valid(3) && !committed(3)
  state.readyByState := !valid(3) || (committed(3) && io.result.ready)
  state.stageValid := valid.asUInt
  state.stageCommitted := committed.asUInt
  state.stageRd := rd
  state.stageProduct := VecInit(product.map(_.asUInt))
  state.stageOp := op
  // Same combinational arithmetic node as the production P1 input. The
  // protocol ledger treats its value as a payload, not a math reference.
  state.incomingProduct := (io.req.bits.a * io.req.bits.b).asUInt
}

class FormalDivProbe extends CommittedDivUnit {
  val state = IO(Output(new Bundle {
    val live = UInt(3.W)
    val authorized = UInt(3.W)
    val earlyDone = Bool()
    val done = Bool()
    val rd = UInt(5.W)
    val data = UInt(64.W)
  }))
  state.live := occupied.asUInt
  state.authorized := (occupied && committed).asUInt
  state.earlyDone := occupied && done && !committed
  state.done := done
  state.rd := rd
  state.data := data
}

class FormalInitialReset extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val reset = Input(Bool()) })
  setInline("FormalInitialReset.sv", """module FormalInitialReset(input reset);
    always @* if ($initstate) assume(reset);
  endmodule
  """)
}

/** Independent accepted-request FIFO. Entries become committed in acceptance
  * order; a kill truncates only its uncommitted tail. No WAW or fairness assume.
  * Reset starts a new accounting epoch and may recur with work in flight.
  */
object FormalMduLedger {
  case class State(live: UInt, committed: UInt, destinations: Vec[UInt])
  def apply[T <: Data](io: IntMduIO[T], requestRd: UInt, capacity: Int,
      dutLive: UInt, dutCommitted: UInt, reset: Bool): State = {
    val init = Module(new FormalInitialReset)
    init.io.reset := reset
    val live = RegInit(0.U(3.W))
    val committed = RegInit(0.U(3.W))
    val destinations = Reg(Vec(capacity, UInt(5.W)))
    val accepted = RegInit(0.U(32.W))
    val written = RegInit(0.U(32.W))
    val killed = RegInit(0.U(32.W))
    val pop = io.result.fire
    val push = io.req.fire
    val afterPop = live - pop.asUInt
    val committedAfterPop = committed - pop.asUInt
    val authorized = committedAfterPop + io.commit.asUInt
    val kept = Mux(io.killUncommitted, authorized, afterPop)
    val canceled = afterPop - kept

    when(!reset) {
      // Caller obligations: WB has an older accepted uncommitted instruction;
      // WB kill suppresses a younger EX request; x0 is never sent to an FU.
      assume(!io.commit || live > committed)
      assume(!io.killUncommitted || !io.req.valid)
      assume(!io.req.valid || requestRd =/= 0.U)
      assert(live <= capacity.U && committed <= live, "F08 FIFO capacity")
      assert(dutLive === live && dutCommitted === committed, "F08 physical/accounting correspondence")
      assert((accepted - written - killed) === live, "F08 accepted = live + written + killed (mod 2^32)")
      assert(!io.result.valid || committed =/= 0.U, "F03/F07 only surviving committed entries can write")
      assert(!io.result.valid || io.result.bits.rd === destinations(0), "F03 FIFO destination identity")
      assert(!push || kept < capacity.U, "F08 accepted request cannot overflow")
      assert(!io.commit || dutLive > dutCommitted, "F11 commit has a physical uncommitted entry")
      cover(io.commit && io.killUncommitted, "F14_commit_before_kill")
      cover(io.result.valid && !io.result.ready && io.killUncommitted, "F05_committed_survives_kill_under_hold")
      if (capacity > 1) { cover(io.result.fire && io.commit, "F07_old_write_and_younger_commit") }
      cover(killed =/= 0.U && io.result.fire, "F03_write_after_kill")
    }
    when(pop) {
      for (i <- 0 until capacity - 1) { destinations(i) := destinations(i + 1) }
    }
    when(push) {
      if (capacity == 1) destinations(0) := requestRd
      else destinations(kept(log2Ceil(capacity) - 1, 0)) := requestRd
    }
    live := kept + push.asUInt
    committed := authorized
    accepted := accepted + push.asUInt
    written := written + pop.asUInt
    killed := killed + canceled
    State(live, committed, destinations)
  }
}

class MulProtocolFormal extends Module {
  val io = IO(new IntMduIO(new IntMulRequest))
  val dut = Module(new FormalMulProbe)
  dut.io.req <> io.req
  dut.io.commit := io.commit
  dut.io.killUncommitted := io.killUncommitted
  io.result <> dut.io.result
  val ledger = FormalMduLedger(io, io.req.bits.rd, 4, dut.state.live, dut.state.authorized, reset.asBool)
  val live = ledger.live
  val committed = ledger.committed
  val products = Reg(Vec(4, UInt(130.W)))
  val operations = Reg(Vec(4, UInt(3.W)))
  val afterPop = live - io.result.fire.asUInt
  val kept = Mux(io.killUncommitted,
    committed - io.result.fire.asUInt + io.commit.asUInt, afterPop)
  when(io.result.fire) {
    for (i <- 0 until 3) {
      products(i) := products(i + 1)
      operations(i) := operations(i + 1)
    }
  }
  when(io.req.fire) {
    products(kept(1, 0)) := dut.state.incomingProduct
    operations(kept(1, 0)) := io.req.bits.op
  }
  val stalledP4 = RegInit(0.U(4.W))
  val consecutive = RegInit(0.U(3.W))
  stalledP4 := Mux(dut.state.p4Uncommitted, Mux(stalledP4 === 15.U, stalledP4, stalledP4 + 1.U), 0.U)
  consecutive := Mux(io.req.fire, Mux(consecutive === 7.U, consecutive, consecutive + 1.U), 0.U)
  when(!reset.asBool) {
    // Strengthening asserts make ownership inductive even when an
    // uncommitted P4 stays blocked forever. They impose no input assumptions.
    for (i <- 0 until 4) {
      val age = if (i == 3) 0.U(2.W) else PopCount((i + 1 until 4).map(dut.state.stageValid(_)))
      when(dut.state.stageValid(i)) {
        assert(dut.state.stageRd(i) === ledger.destinations(age), "F03 every live MUL keeps its request rd")
        assert(dut.state.stageProduct(i) === products(age) && dut.state.stageOp(i) === operations(age),
          "F03 every live MUL keeps its request arithmetic payload")
        assert(dut.state.stageCommitted(i) === (age < committed), "F07 committed MUL entries are the FIFO prefix")
      }
    }
    val expected = MuxLookup(operations(0), products(0)(63, 0))(Seq(
      1.U -> products(0)(127, 64), 2.U -> products(0)(127, 64), 3.U -> products(0)(127, 64),
      4.U -> Cat(Fill(32, products(0)(31)), products(0)(31, 0))))
    assert(!io.result.valid || io.result.bits.data === expected, "F03 output payload belongs to surviving FIFO head")
    assert(io.req.ready === dut.state.readyByState, "F08 A01 enable")
    cover(live === 4.U && committed === 0.U, "F08_four_uncommitted_slots")
    cover(stalledP4 >= 8.U && io.commit, "F13_uncommitted_P4_hold_then_commit")
    cover(stalledP4 >= 8.U && io.killUncommitted, "F13_uncommitted_P4_hold_then_kill")
    cover(consecutive >= 4.U, "F13_continuous_MUL_acceptance")
  }
}

class DivProtocolFormal extends Module {
  val io = IO(new IntMduIO(new IntDivRequest))
  val dut = Module(new FormalDivProbe)
  dut.io.req <> io.req
  dut.io.commit := io.commit
  dut.io.killUncommitted := io.killUncommitted
  io.result <> dut.io.result
  val ledger = FormalMduLedger(io, io.req.bits.rd, 1, dut.state.live, dut.state.authorized, reset.asBool)
  val live = ledger.live
  val pastActive = RegNext(!reset.asBool, false.B)
  val previousDone = RegNext(live =/= 0.U && dut.state.done, false.B)
  val previousRelease = RegNext(io.result.fire ||
    (io.killUncommitted && live > ledger.committed && !io.commit), false.B)
  val previousData = RegNext(dut.state.data)
  val previousRd = RegNext(dut.state.rd)
  val waitedDone = RegInit(0.U(4.W))
  waitedDone := Mux(dut.state.earlyDone, Mux(waitedDone === 15.U, waitedDone, waitedDone + 1.U), 0.U)
  when(!reset.asBool) {
    assert(live === 0.U || dut.state.rd === ledger.destinations(0), "F03 occupied DIV keeps its accepted rd before/after commit")
    when(pastActive && previousDone && !previousRelease) {
      assert(live =/= 0.U && dut.state.done && dut.state.data === previousData && dut.state.rd === previousRd,
        "F05 internally completed DIV payload holds before/after commit")
    }
    assert(io.req.ready === (live === 0.U), "F08 single DIV occupied until release")
    cover(waitedDone >= 8.U && io.commit, "F13_early_done_waits_for_commit")
    cover(dut.state.earlyDone && io.killUncommitted, "F03_kill_and_done")
  }
}

object GenerateIntMduFormal extends App {
  val target = if (args.nonEmpty) os.Path(args(0), os.pwd) else os.pwd / "build" / "int-mdu-formal"
  val options = Array("-disable-all-randomization", "-strip-debug-info",
    "-default-layer-specialization=enable", "--verification-flavor=immediate",
    "--lowering-options=disallowLocalVariables,disallowPackedArrays,noAlwaysComb")
  for ((name, generator) <- Seq[(String, () => Module)](
      "mul" -> (() => new MulProtocolFormal), "div" -> (() => new DivProtocolFormal))) {
    ChiselStage.emitSystemVerilogFile(generator(), Array("--target-dir", (target / name).toString), firtoolOpts = options)
  }
}
