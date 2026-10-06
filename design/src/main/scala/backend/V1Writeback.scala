package flow.backend

import chisel3._
import chisel3.util._
import flow.interface.{L1DDestination, L1DLate}

class V1FpResult extends Bundle {
  val rd = new L1DDestination
  val data = UInt(64.W)
  val flags = UInt(5.W)
}
class V1RegisterWrite extends Bundle {
  val idx = UInt(5.W)
  val data = UInt(64.W)
}

/** One background grant, L1D > DIV > MUL > FPU. No result storage.
  * Ordinary WB writes win their bank. Fairness bubbles originate only in ID.
  */
class V1Writeback extends Module {
  val io = IO(new Bundle {
    val late = Flipped(Decoupled(new L1DLate))
    val div = Flipped(Decoupled(new IntMduResult))
    val mul = Flipped(Decoupled(new IntMduResult))
    val fp = Flipped(Decoupled(new V1FpResult))
    val ordinary = Flipped(Valid(new V1FpResult))
    val gprWrite = Valid(new V1RegisterWrite)
    val fprWrite = Valid(new V1RegisterWrite)
    val clear = Valid(new L1DDestination)
    val fpFlags = Valid(UInt(5.W))
    val grant = Output(UInt(4.W))
    val conflict = Output(UInt(2.W))
    val idStarve = Output(Bool())
    val starvation = Output(Vec(2, UInt(2.W)))
    val bubbleInFlight = Output(Vec(2, Bool()))
    val hartFatal = Output(Bool())
  })
  val valids = VecInit(Seq(io.late.valid, io.div.valid, io.mul.valid, io.fp.valid))
  val banks = VecInit(Seq(io.late.bits.rd.isFp, false.B, false.B, io.fp.bits.rd.isFp))
  val ordinaryWrite = io.ordinary.valid && (io.ordinary.bits.rd.isFp || io.ordinary.bits.rd.idx =/= 0.U)
  val needsPort = VecInit(Seq(!io.late.bits.error && (io.late.bits.rd.isFp || io.late.bits.rd.idx =/= 0.U),
    io.div.bits.rd =/= 0.U, io.mul.bits.rd =/= 0.U, io.fp.bits.rd.isFp || io.fp.bits.rd.idx =/= 0.U))
  val eligible = VecInit((0 until 4).map(i => valids(i) &&
    !(needsPort(i) && ordinaryWrite && banks(i) === io.ordinary.bits.rd.isFp)))
  val selected = PriorityEncoderOH(eligible.asUInt)
  io.grant := selected
  io.late.ready := selected(0)
  io.div.ready := selected(1)
  io.mul.ready := selected(2)
  io.fp.ready := selected(3)
  val starvation = RegInit(VecInit(Seq.fill(2)(0.U(2.W))))
  val inFlight = RegInit(VecInit(Seq.fill(2)(false.B)))
  val conflicts = Wire(Vec(2, Bool()))
  val protect = Wire(Vec(2, Bool()))
  for (bank <- 0 until 2) {
    val waiting = (0 until 4).map(i => valids(i) && banks(i) === (bank == 1).B).reduce(_ || _)
    val granted = (0 until 4).map(i => selected(i) && banks(i) === (bank == 1).B).reduce(_ || _)
    conflicts(bank) := waiting && !granted
    protect(bank) := starvation(bank) === 3.U && !inFlight(bank)
    when(conflicts(bank)) { when(starvation(bank) =/= 3.U) { starvation(bank) := starvation(bank) + 1.U } }
      .otherwise { starvation(bank) := 0.U }
    when(protect(bank)) { inFlight(bank) := true.B }
    when(granted) { inFlight(bank) := false.B }
  }
  // ID reads registers only: neither grant nor WB ordinaryWrite reaches this decision.
  io.idStarve := protect.asUInt.orR
  io.conflict := PopCount(conflicts)
  io.starvation := starvation
  io.bubbleInFlight := inFlight
  val rd = Wire(new L1DDestination)
  rd.isFp := (selected(0) && io.late.bits.rd.isFp) || (selected(3) && io.fp.bits.rd.isFp)
  rd.idx := Mux1H(selected, Seq(io.late.bits.rd.idx, io.div.bits.rd, io.mul.bits.rd, io.fp.bits.rd.idx))
  val data = Mux1H(selected, Seq(io.late.bits.data, io.div.bits.data, io.mul.bits.data, io.fp.bits.data))
  val error = selected(0) && io.late.bits.error
  val fatal = RegInit(false.B)
  when(error) { fatal := true.B }
  io.hartFatal := fatal || error
  val bgWrite = selected.orR && !error && (rd.isFp || rd.idx =/= 0.U)
  val normal = ordinaryWrite && !io.hartFatal
  def write(fp: Boolean): Unit = {
    val out = if (fp) io.fprWrite else io.gprWrite
    val background = bgWrite && rd.isFp === fp.B
    out.valid := background || (normal && io.ordinary.bits.rd.isFp === fp.B)
    out.bits.idx := Mux(background, rd.idx, io.ordinary.bits.rd.idx)
    out.bits.data := Mux(background, data, io.ordinary.bits.data)
  }
  write(false); write(true)
  io.clear.valid := selected.orR && (rd.isFp || rd.idx =/= 0.U)
  io.clear.bits := rd
  io.fpFlags.valid := selected(3)
  io.fpFlags.bits := io.fp.bits.flags
  when(!reset.asBool) {
    assert(PopCount(selected) <= 1.U, "[V1 S06] background grants not one-hot")
    assert(!io.gprWrite.valid || io.gprWrite.bits.idx =/= 0.U, "[V1 S01] physical x0 write")
    assert(io.clear.valid === (selected.orR && (rd.isFp || rd.idx =/= 0.U)),
      "[V1 S08] clear must equal actual completion, including fatal late")
  }
}
