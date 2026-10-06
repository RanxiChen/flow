package flow.backend

import chisel3._
import chisel3.util._
import flow.interface.L1DDestination

object LongSource {
  val L1D = 0
  val DIV = 1
  val MUL = 2
  val FPU = 3
}
class Producer extends Bundle {
  val rd = new L1DDestination
  val source = UInt(2.W)
}
class Operand extends Bundle {
  val used = Bool()
  val rd = new L1DDestination
}

/** WB sets, actual completion clears; kill has no scoreboard write enable.
  * The caller supplies only genuinely issued EX/MEM/WB producers.
  */
class Scoreboard extends Module {
  val io = IO(new Bundle {
    val set = Flipped(Valid(new Producer))
    val clear = Flipped(Valid(new L1DDestination))
    val pipe = Input(Vec(3, Valid(new Producer)))
    val operands = Input(Vec(4, new Operand)) // real sources + destination (WAW)
    val idValid = Input(Bool())
    val idLeave = Input(Bool())
    val csr = Input(Bool())
    val fpFlagsPending = Input(Bool()) // committed FP-to-x0 still owes flags
    val gprBusy = Output(UInt(32.W))
    val fprBusy = Output(UInt(32.W))
    val hazard = Output(Bool())
    val csrDrainOk = Output(Bool())
    val sourceStall = Output(Vec(4, Bool()))
  })
  val gprBusy = RegInit(0.U(32.W))
  val fprBusy = RegInit(0.U(32.W))
  val gprSource = Reg(Vec(32, UInt(2.W)))
  val fprSource = Reg(Vec(32, UInt(2.W)))
  def writable(rd: L1DDestination): Bool = rd.isFp || rd.idx =/= 0.U
  def mask(valid: Bool, rd: L1DDestination, fp: Boolean): UInt =
    Mux(valid && writable(rd) && rd.isFp === fp.B, UIntToOH(rd.idx, 32), 0.U(32.W))
  val gSet = mask(io.set.valid, io.set.bits.rd, false)
  val fSet = mask(io.set.valid, io.set.bits.rd, true)
  val gClear = mask(io.clear.valid, io.clear.bits, false)
  val fClear = mask(io.clear.valid, io.clear.bits, true)
  val gEffective = gprBusy & ~gClear
  val fEffective = fprBusy & ~fClear
  gprBusy := (gprBusy & ~gClear) | gSet
  fprBusy := (fprBusy & ~fClear) | fSet
  when(io.set.valid && writable(io.set.bits.rd)) {
    when(io.set.bits.rd.isFp) { fprSource(io.set.bits.rd.idx) := io.set.bits.source }
      .otherwise { gprSource(io.set.bits.rd.idx) := io.set.bits.source }
  }
  val matches = (0 until 4).map { src =>
    io.operands.map { operand =>
      val rd = operand.rd
      val sb = Mux(rd.isFp,
        fEffective(rd.idx) && fprSource(rd.idx) === src.U,
        gEffective(rd.idx) && gprSource(rd.idx) === src.U)
      val pipe = io.pipe.map(p => p.valid && writable(p.bits.rd) &&
        p.bits.source === src.U && p.bits.rd.asUInt === rd.asUInt).reduce(_ || _)
      operand.used && writable(rd) && (sb || pipe)
    }.reduce(_ || _)
  }
  io.csrDrainOk := !(gprBusy.orR || fprBusy.orR || io.fpFlagsPending || io.pipe.map(_.valid).reduce(_ || _))
  val csrSources = (0 until 4).map { src =>
    (0 until 32).map { idx =>
      (gprBusy(idx) && gprSource(idx) === src.U) ||
        (fprBusy(idx) && fprSource(idx) === src.U)
    }.reduce(_ || _) || io.pipe.map(p => p.valid && p.bits.source === src.U).reduce(_ || _)
  }
  io.hazard := matches.reduce(_ || _) || (io.csr && !io.csrDrainOk)
  for (src <- 0 until 4) {
    io.sourceStall(src) := io.idValid && !io.idLeave &&
      (matches(src) || (io.csr && (csrSources(src) || ((src == LongSource.FPU).B && io.fpFlagsPending))))
  }
  io.gprBusy := gprBusy
  io.fprBusy := fprBusy
  when(!reset.asBool) {
    assert(!gprBusy(0), "[S01] x0 busy")
    assert(!(gSet & gClear).orR && !(fSet & fClear).orR, "[S10] same rd set and clear")
    assert(!(gSet & gprBusy).orR && !(fSet & fprBusy).orR, "[S01] duplicate outstanding rd")
    assert((gClear & ~gprBusy) === 0.U && (fClear & ~fprBusy) === 0.U,
      "[S08] completion without committed pending destination")
    assert(!io.idLeave || !io.hazard, "[S03/S14] ID left with dependency or CSR drain hazard")
  }
}
