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
  }))
  state.live := PopCount(valid)
  state.authorized := PopCount((0 until 4).map(i => valid(i) && committed(i)))
  state.p4Uncommitted := valid(3) && !committed(3)
  state.readyByState := !valid(3) || (committed(3) && io.result.ready)
}

class FormalDivProbe extends CommittedDivUnit {
  val state = IO(Output(new Bundle {
    val live = UInt(3.W)
    val authorized = UInt(3.W)
    val earlyDone = Bool()
  }))
  state.live := occupied.asUInt
  state.authorized := (occupied && committed).asUInt
  state.earlyDone := occupied && done && !committed
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
  def apply[T <: Data](io: IntMduIO[T], requestRd: UInt, capacity: Int,
      dutLive: UInt, dutCommitted: UInt, reset: Bool): (UInt, UInt) = {
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
    when(push) { destinations(kept) := requestRd }
    live := kept + push.asUInt
    committed := authorized
    accepted := accepted + push.asUInt
    written := written + pop.asUInt
    killed := killed + canceled
    (live, committed)
  }
}

class MulProtocolFormal extends Module {
  val io = IO(new IntMduIO(new IntMulRequest))
  val dut = Module(new FormalMulProbe)
  dut.io.req <> io.req
  dut.io.commit := io.commit
  dut.io.killUncommitted := io.killUncommitted
  io.result <> dut.io.result
  val (live, committed) = FormalMduLedger(io, io.req.bits.rd, 4, dut.state.live, dut.state.authorized, reset.asBool)
  val stalledP4 = RegInit(0.U(4.W))
  val consecutive = RegInit(0.U(3.W))
  stalledP4 := Mux(dut.state.p4Uncommitted, Mux(stalledP4 === 15.U, stalledP4, stalledP4 + 1.U), 0.U)
  consecutive := Mux(io.req.fire, Mux(consecutive === 7.U, consecutive, consecutive + 1.U), 0.U)
  when(!reset.asBool) {
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
  val (live, _) = FormalMduLedger(io, io.req.bits.rd, 1, dut.state.live, dut.state.authorized, reset.asBool)
  val waitedDone = RegInit(0.U(4.W))
  waitedDone := Mux(dut.state.earlyDone, Mux(waitedDone === 15.U, waitedDone, waitedDone + 1.U), 0.U)
  when(!reset.asBool) {
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
