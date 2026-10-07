package flow.rvv

import chisel3._
import chisel3.util._

case class RvvParams(vlen: Int = 512, dlen: Int = 512, lanes: Int = 8,
  viqDepth: Int = 32, unitDepths: Seq[Int] = Seq(4, 2, 2, 2, 2),
  writeBanks: Int = 4, execReadPorts: Int = 4, returnBytes: Int = 16384,
  memoryBits: Int = 512, axiIds: Int = 1, burstBeats: Int = 4,
  scoreboardDepth: Int = 16, memoryInflight: Int = 16, translationIds: Int = 8, cacheLineBytes: Int = 32, paBits: Int = 34) {
  require(vlen >= 128 && vlen <= 4096 && isPow2(vlen))
  require(dlen >= 64 && dlen <= vlen && isPow2(dlen) && vlen % dlen == 0)
  require(lanes > 0 && dlen % lanes == 0 && dlen / lanes >= 8)
  require(unitDepths.length == 5 && unitDepths.forall(_ > 0))
  require(isPow2(writeBanks) && execReadPorts >= 4)
  require(memoryBits >= 64 && isPow2(memoryBits) && memoryBits <= 1024)
  require(returnBytes % (memoryBits / 8) == 0 && returnBytes >= memoryBits / 8)
  require(axiIds == 1, "R02 uses one ID, with multiple ordered outstanding bursts")
  require(burstBeats > 0 && burstBeats <= 256 && translationIds >= 2)
  require(paBits >= 32 && paBits <= 64)
  val pendingDepth = math.min(8,viqDepth)
  val ageBits = log2Ceil(2*(viqDepth+scoreboardDepth+memoryInflight+translationIds+unitDepths.sum+5))
  val ageLimit = (BigInt(1) << (ageBits-1))-1
  val rowBytes = dlen / 8
  val regBytes = vlen / 8
  val rowsPerReg = vlen / dlen
  val rows = 32 * rowsPerReg
  val rowBits = log2Ceil(rows)
  val memBytes = memoryBits / 8
  val returnBeats = returnBytes / memBytes
  require(scoreboardDepth >= 2)
  val slotBits = log2Ceil(scoreboardDepth)
  val translationBits = log2Ceil(translationIds)
  val memorySlotBits = log2Ceil(memoryInflight)
  val maxBytes = vlen // EMUL <= 8, expressed in bytes
  val lengthBits = log2Ceil(maxBytes + 1)
}

class RvvIssue(p: RvvParams) extends Bundle {
  val instruction = UInt(32.W)
  val rs1 = UInt(64.W)
  val rs2 = UInt(64.W)
  val vl = UInt(p.lengthBits.W)
  val vtype = UInt(64.W)
  val vstart = UInt(p.lengthBits.W)
  val vxrm = UInt(2.W)
  val frm = UInt(3.W)
  val rd = UInt(5.W)
}
object RvvOp {
  val load = 0; val store = 1; val add = 2; val sub = 3; val and = 4
  val shift = 5; val move = 6; val macc = 7; val dot = 8; val dotsu = 9; val scalar = 10
}
class RvvDecoded(p: RvvParams) extends Bundle {
  val op = UInt(4.W)
  val unit = UInt(3.W)
  val memory = Bool()
  val store = Bool()
  val supported = Bool()
  val fast = Bool()
  val masked = Bool()
  val scalarOperand = Bool()
  val vd = UInt(5.W)
  val vs1 = UInt(5.W)
  val vs2 = UInt(5.W)
  val sew = UInt(2.W)
  val eew = UInt(2.W)
  val bytes = UInt(p.lengthBits.W)
  val readMask = UInt(32.W)
  val writeMask = UInt(32.W)
}
object RvvDecode {
  def apply(i: RvvIssue, p: RvvParams): RvvDecoded = {
    val d = WireDefault(0.U.asTypeOf(new RvvDecoded(p)))
    val x = i.instruction
    val f6 = x(31,26); val f3 = x(14,12)
    d.vd := x(11,7); d.vs1 := x(19,15); d.vs2 := x(24,20)
    d.masked := !x(25); d.scalarOperand := f3 === 4.U || f3 === 6.U
    d.sew := i.vtype(4,3)
    d.eew := Mux(f3 === 0.U, 0.U, f3 - 4.U)
    d.memory := x(6,0) === "h07".U || x(6,0) === "h27".U
    d.store := x(6,0) === "h27".U
    val widthOk = f3 === 0.U || f3 >= 5.U
    d.fast := d.memory && !d.masked && x(31,29) === 0.U && !x(28) &&
      x(27,26) === 0.U && x(24,20) === 0.U && i.vstart === 0.U && widthOk
    d.bytes := i.vl << Mux(d.memory, d.eew, d.sew)
    d.supported := d.memory
    d.unit := 0.U; d.op := Mux(d.store, RvvOp.store.U, RvvOp.load.U)
    when(x(6,0) === "h57".U) {
      d.unit := 1.U
      when(f6 === 0.U && (f3 === 0.U || f3 === 4.U)) { d.op := RvvOp.add.U; d.supported := true.B }
      when(f6 === 2.U && f3 === 0.U) { d.op := RvvOp.sub.U; d.supported := true.B }
      when(f6 === 9.U && f3 === 4.U) { d.op := RvvOp.and.U; d.supported := true.B }
      when(f6 === 40.U && f3 === 4.U) { d.op := RvvOp.shift.U; d.supported := true.B }
      when(f6 === 23.U && x(25) && x(24,20) === 0.U && (f3 === 0.U || f3 === 4.U)) {
        d.op := RvvOp.move.U; d.supported := true.B
      }
      when(f6 === 45.U && f3 === 2.U) { d.unit := 2.U; d.op := RvvOp.macc.U; d.supported := true.B }
      when((f6 === 44.U || f6 === 42.U) && f3 === 6.U && i.vtype(5,3) === 2.U) {
        d.unit := 2.U; d.op := Mux(f6 === 44.U, RvvOp.dot.U, RvvOp.dotsu.U); d.supported := true.B
      }
      when(f6 === 16.U && f3 === 2.U && x(19,15) === 0.U && x(25)) {
        d.unit := 4.U; d.op := RvvOp.scalar.U; d.supported := true.B
      }
    }
    val groupRegs = Mux(i.vtype(2), 1.U, 1.U(4.W) << i.vtype(1,0))
    val memRegs = ((d.bytes + (p.regBytes-1).U) >> log2Ceil(p.regBytes))
    def mask(base: UInt, n: UInt): UInt = {
      VecInit((0 until 32).map(r => r.U >= base && r.U < base +& n)).asUInt
    }
    val src2 = mask(d.vs2, groupRegs); val src1 = mask(d.vs1, groupRegs)
    val dst = mask(d.vd, Mux(d.memory, memRegs, groupRegs))
    d.writeMask := Mux(d.store || d.op === RvvOp.scalar.U, 0.U, dst)
    d.readMask := Mux(d.memory, Mux(d.store, dst, 0.U),
      Mux(d.op === RvvOp.move.U, Mux(d.scalarOperand, 0.U, src1),
        src2 | Mux(d.scalarOperand || d.op === RvvOp.scalar.U, 0.U, src1) |
          Mux(d.unit === 2.U, dst, 0.U))) | Mux(d.masked && !d.memory, 1.U, 0.U)
    d
  }
}

object RvvAge {
  def older(a: UInt,b: UInt): Bool = (a-b).asSInt < 0.S
}
/** Only fields used after decode. The mounted issue interface remains RV64. */
class RvvExecuteIssue(p: RvvParams) extends Bundle {
  val rs1 = UInt(64.W); val vl = UInt(p.lengthBits.W)
  val vtype = UInt(8.W); val vstart = UInt(p.lengthBits.W)
  val vxrm = UInt(2.W); val frm = UInt(3.W); val rd = UInt(5.W)
}
class RvvDescriptor(p: RvvParams) extends Bundle {
  val issue = new RvvExecuteIssue(p)
  val decoded = new RvvDecoded(p)
  val age = UInt(p.ageBits.W)
  val slot = UInt(p.slotBits.W)
  val pa = Vec(2, UInt(p.paBits.W))
  val length = Vec(2, UInt(p.lengthBits.W))
}
class RvvVerdict(p: RvvParams) extends Bundle {
  val kind = UInt(3.W) // 0 Ok, 1 OkVl, 2 Exc, 3 Serial, 4 Done, 5 DoneVl
  val newVl = UInt(p.lengthBits.W)
  val cause = UInt(64.W)
  val tval = UInt(64.W)
  val vstart = UInt(p.lengthBits.W)
}
class RvvTranslationRequest(p: RvvParams) extends Bundle {
  val va = UInt(64.W); val write = Bool(); val id = UInt(p.translationBits.W)
}
class RvvTranslationResponse(p: RvvParams) extends Bundle {
  val pa = UInt(64.W); val device = Bool(); val cacheable = Bool()
  val exception = Bool(); val cause = UInt(64.W); val id = UInt(p.translationBits.W)
}
class RvvScalar extends Bundle { val rd = UInt(5.W); val data = UInt(64.W); val floating = Bool() }
class RvvConflict extends Bundle {
  val valid = Bool(); val pa = UInt(64.W); val bytes = UInt(64.W); val write = Bool()
}
class RvvAxiAddress(p: RvvParams) extends Bundle {
  val addr = UInt(64.W); val id = UInt(math.max(1,log2Ceil(p.axiIds)).W)
  val len = UInt(8.W); val size = UInt(3.W); val burst = UInt(2.W)
}
class RvvAxiRead(p: RvvParams) extends Bundle {
  val data = UInt(p.memoryBits.W); val id = UInt(math.max(1,log2Ceil(p.axiIds)).W)
  val last = Bool(); val resp = UInt(2.W)
}
class RvvAxiWrite(p: RvvParams) extends Bundle {
  val data = UInt(p.memoryBits.W); val strb = UInt(p.memBytes.W); val last = Bool()
}
class RvvAxiResponse(p: RvvParams) extends Bundle {
  val id = UInt(math.max(1,log2Ceil(p.axiIds)).W); val resp = UInt(2.W)
}
class RvvAxi(p: RvvParams) extends Bundle {
  val ar = Decoupled(new RvvAxiAddress(p)); val r = Flipped(Decoupled(new RvvAxiRead(p)))
  val aw = Decoupled(new RvvAxiAddress(p)); val w = Decoupled(new RvvAxiWrite(p))
  val b = Flipped(Decoupled(new RvvAxiResponse(p)))
}
class RvvWrite(p: RvvParams) extends Bundle {
  val row = UInt(p.rowBits.W); val data = UInt(p.dlen.W); val enables = UInt(p.rowBytes.W)
}
class RvvHazard(p: RvvParams) extends Bundle {
  val valid = Bool(); val slot = UInt(p.slotBits.W)
  val reads = UInt(32.W); val writes = UInt(32.W)
  val aluBypass = Bool(); val maskRead = Bool()
  val dotBypass = Bool(); val accumulator = UInt(32.W)
  val observedSource = UInt(32.W)
}
class RvvProgress(p: RvvParams) extends Bundle {
  val slot = UInt(p.slotBits.W); val readDone = UInt(32.W); val writeDone = UInt(32.W); val finished = Bool()
}
class RvvCounters extends Bundle {
  val readBytes = UInt(64.W); val writeBytes = UInt(64.W); val bufferedBytes = UInt(64.W)
  val war = UInt(64.W); val overlap = UInt(64.W); val credit = UInt(64.W); val axi = UInt(64.W)
  val invalidations = UInt(64.W); val serial = UInt(64.W)
  val busy = Vec(5,UInt(64.W)); val rawStall = Vec(5,UInt(64.W))
  val warStall = Vec(5,UInt(64.W)); val wawStall = Vec(5,UInt(64.W))
}
class RvvRegisterEvent extends Bundle { val age = UInt(64.W); val register = UInt(5.W) }
