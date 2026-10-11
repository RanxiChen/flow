package flow.backend

import chisel3._
import chisel3.util._
import flow.interface.{L1DDestination, L1DLate}

class FpResult extends LongLatResult
class RegisterWrite extends Bundle {
  val idx = UInt(5.W)
  val data = UInt(64.W)
}
class OrdinaryResult extends Bundle {
  val rd = new L1DDestination
  val data = UInt(64.W)
}

/** WB captures W2; L1D late uses two registered slots without RF fall-through.
  * W2 wins its bank, then one background grant: lateReg > DIV > MUL > FPU.
  * Already committed writes survive all pipeline holds/kills and hartFatal.
  * Fairness bubbles originate only in ID.
  */
class Writeback(val withCoprocessor: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val late = Flipped(Decoupled(new L1DLate))
    val div = Flipped(Decoupled(new FpResult))
    val mul = Flipped(Decoupled(new FpResult))
    val fp = Flipped(Decoupled(new FpResult))
    val copro = if (withCoprocessor) Some(Flipped(Decoupled(new FpResult))) else None
    val ordinary = Flipped(Valid(new FpResult))
    val w2 = Output(Valid(new OrdinaryResult))
    val gprWrite = Valid(new RegisterWrite)
    val fprWrite = Valid(new RegisterWrite)
    val clear = Valid(new L1DDestination)
    val fpFlags = Valid(UInt(5.W))
    val grant = Output(UInt((if(withCoprocessor) 5 else 4).W))
    val conflict = Output(UInt(2.W))
    val idStarve = Output(Bool())
    val starvation = Output(Vec(2, UInt(2.W)))
    val bubbleInFlight = Output(Vec(2, Bool()))
    val hartFatal = Output(Bool())
    val lateWriteError = Output(Bool())
  })
  val w2 = RegInit(0.U.asTypeOf(Valid(new OrdinaryResult)))
  // Two registered slots keep late.ready independent of the current grant.
  // Non-flow/non-pipe preserves the first-return -> writeback one-edge delay.
  val lateQueue = Module(new Queue(new L1DLate, 2, pipe = false, flow = false))
  lateQueue.io.enq <> io.late
  val lateReg = Wire(Valid(new L1DLate))
  lateReg.valid := lateQueue.io.deq.valid
  lateReg.bits := lateQueue.io.deq.bits
  // Consume W2 every cycle, even when the following WB is held or killed.
  // The caller supplies only new, non-exceptional ordinary WB commits.
  w2.valid := io.ordinary.valid && (io.ordinary.bits.rd.isFp || io.ordinary.bits.rd.idx =/= 0.U)
  when(io.ordinary.valid) {
    w2.bits.rd := io.ordinary.bits.rd
    w2.bits.data := io.ordinary.bits.data
  }
  io.w2 := w2
  val lateSource = Wire(Decoupled(new FpResult))
  lateSource.valid := lateReg.valid
  lateSource.bits.rd := lateReg.bits.rd
  lateSource.bits.data := lateReg.bits.data
  lateSource.bits.flags := 0.U
  val sources = Seq(lateSource, io.div, io.mul, io.fp) ++ io.copro.toSeq
  val valids = VecInit(sources.map(_.valid))
  val banks = VecInit(sources.map(_.bits.rd.isFp))
  val ordinaryWrite = w2.valid
  val needsPort = VecInit(sources.zipWithIndex.map { case (s,i) =>
    (if(i == 0) !lateReg.bits.error else true.B) && (s.bits.rd.isFp || s.bits.rd.idx =/= 0.U)
  })
  val eligible = VecInit(sources.indices.map(i => valids(i) &&
    !(needsPort(i) && ordinaryWrite && banks(i) === w2.bits.rd.isFp)))
  val selected = PriorityEncoderOH(eligible.asUInt)
  io.grant := selected
  val lateRegGrant = selected(0)
  lateQueue.io.deq.ready := lateRegGrant
  sources.zipWithIndex.foreach { case (s,i) => s.ready := selected(i) }
  val starvation = RegInit(VecInit(Seq.fill(2)(0.U(2.W))))
  val inFlight = RegInit(VecInit(Seq.fill(2)(false.B)))
  val conflicts = Wire(Vec(2, Bool()))
  val protect = Wire(Vec(2, Bool()))
  for (bank <- 0 until 2) {
    val waiting = sources.indices.map(i => valids(i) && banks(i) === (bank == 1).B).reduce(_ || _)
    val granted = sources.indices.map(i => selected(i) && banks(i) === (bank == 1).B).reduce(_ || _)
    conflicts(bank) := waiting && !granted
    protect(bank) := starvation(bank) === 3.U && !inFlight(bank)
    when(conflicts(bank)) { when(starvation(bank) =/= 3.U) { starvation(bank) := starvation(bank) + 1.U } }
      .otherwise { starvation(bank) := 0.U }
    when(protect(bank)) { inFlight(bank) := true.B }
    when(granted) { inFlight(bank) := false.B }
  }
  // ID reads only the registered starvation/protection state.
  io.idStarve := protect.asUInt.orR
  io.conflict := PopCount(conflicts)
  io.starvation := starvation
  io.bubbleInFlight := inFlight
  val rd = Wire(new L1DDestination)
  rd := Mux1H(selected, sources.map(_.bits.rd))
  val data = Mux1H(selected, sources.map(_.bits.data))
  val error = lateReg.valid && lateReg.bits.error
  val fatal = RegInit(false.B)
  // Any accepted error must become fatal on N+1, even in the second slot
  // behind an older result blocked by W2. Completion/clear remains in order.
  when(io.late.fire && io.late.bits.error) { fatal := true.B }
  io.hartFatal := fatal || error
  io.lateWriteError := lateRegGrant && lateReg.bits.error
  val bgWrite = selected.orR && !io.lateWriteError && (rd.isFp || rd.idx =/= 0.U)
  def write(fp: Boolean): Unit = {
    val out = if (fp) io.fprWrite else io.gprWrite
    val background = bgWrite && rd.isFp === fp.B
    out.valid := background || (ordinaryWrite && w2.bits.rd.isFp === fp.B)
    out.bits.idx := Mux(background, rd.idx, w2.bits.rd.idx)
    out.bits.data := Mux(background, data, w2.bits.data)
  }
  write(false); write(true)
  io.clear.valid := selected.orR && (rd.isFp || rd.idx =/= 0.U)
  io.clear.bits := rd
  io.fpFlags.valid := selected(3) || (if(withCoprocessor) selected(4) else false.B)
  io.fpFlags.bits := Mux1H(selected, sources.map(_.bits.flags))
  val pastActive = RegNext(!reset.asBool, false.B)
  val wasLateBlocked = RegNext(io.late.valid && !io.late.ready, false.B)
  val heldLate = RegNext(io.late.bits.asUInt)
  val wasLateRegBlocked = RegNext(lateReg.valid && !lateRegGrant, false.B)
  val heldLateReg = RegNext(lateReg.bits.asUInt)
  when(pastActive && !reset.asBool && wasLateBlocked) {
    assert(io.late.valid && io.late.bits.asUInt === heldLate, "[S07] stalled late result changed")
  }
  when(pastActive && !reset.asBool && wasLateRegBlocked) {
    assert(lateReg.valid && lateReg.bits.asUInt === heldLateReg, "[S07] stalled lateReg changed")
  }
  when(!reset.asBool) {
    assert(PopCount(selected) <= 1.U, "[S06] background grants not one-hot")
    assert(!lateRegGrant || lateReg.valid, "[S08] lateReg completion without a buffered item")
    when(w2.valid) {
      val out = Mux(w2.bits.rd.isFp, io.fprWrite, io.gprWrite)
      assert(out.valid && out.bits.idx === w2.bits.rd.idx && out.bits.data === w2.bits.data,
        "[W2] committed ordinary write did not complete")
    }
    assert(!io.gprWrite.valid || io.gprWrite.bits.idx =/= 0.U, "[S01] physical x0 write")
    assert(io.clear.valid === (selected.orR && (rd.isFp || rd.idx =/= 0.U)),
      "[S08] clear must equal actual completion, including fatal late")
  }
}
