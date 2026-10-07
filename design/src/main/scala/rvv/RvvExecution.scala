package flow.rvv

import chisel3._
import chisel3.util._

/** One row per accepted beat, with register-granularity progress. The shared
  * read-port grant is separate from register hazards and bank arbitration. */
class RvvIntegerSequencer(p: RvvParams, multiply: Boolean) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new RvvDescriptor(p)))
    val readRows = Output(Vec(3,UInt(p.rowBits.W))); val readData = Input(Vec(3,UInt(p.dlen.W)))
    val readDemand = Output(UInt(2.W)); val grant = Input(Bool())
    val mask = Input(UInt(p.vlen.W))
    val hazard = Output(new RvvHazard(p)); val blocked = Input(Bool())
    val progress = Valid(new RvvProgress(p)); val write = Decoupled(new RvvWrite(p))
    val age = Output(UInt(64.W)); val busy = Output(Bool())
    val readEvent = Valid(new RvvRegisterEvent)
    val complete = Valid(UInt(64.W))
  })
  val active = RegInit(false.B); val d = Reg(new RvvDescriptor(p))
  val row = RegInit(0.U(log2Ceil(p.rows+1).W))
  val bytePosition = row * p.rowBytes.U
  val registerOffset = row >> log2Ceil(p.rowsPerReg)
  val subrow = if(p.rowsPerReg == 1) 0.U else row(log2Ceil(p.rowsPerReg)-1,0)
  def address(base: UInt): UInt = ((base + registerOffset)*p.rowsPerReg.U+subrow)(p.rowBits-1,0)
  def bit(base: UInt): UInt = (1.U(32.W) << (base+registerOffset))(31,0)
  io.age := d.age; io.busy := active
  io.readRows(0) := address(d.decoded.vs2)
  io.readRows(1) := address(if(multiply) d.decoded.vd else d.decoded.vs1)
  io.readRows(2) := address(d.decoded.vs1)
  io.readDemand := (if(multiply) Mux(d.decoded.op === RvvOp.macc.U,3.U,2.U)
    else Mux(d.decoded.scalarOperand,1.U,2.U))
  if(!multiply) {
    when(d.decoded.op === RvvOp.move.U) { io.readRows(0) := address(d.decoded.vs1); io.readDemand := Mux(d.decoded.scalarOperand,0.U,1.U) }
  }
  val rowsTotal = (d.decoded.bytes + (p.rowBytes-1).U) >> log2Ceil(p.rowBytes)
  val last = row + 1.U >= rowsTotal
  val regLast = subrow === (p.rowsPerReg-1).U || last
  val readMask = if(multiply) bit(d.decoded.vs2) | bit(d.decoded.vd) |
    Mux(d.decoded.op === RvvOp.macc.U,bit(d.decoded.vs1),0.U)
    else Mux(d.decoded.op === RvvOp.move.U,Mux(d.decoded.scalarOperand,0.U,bit(d.decoded.vs1)),
      bit(d.decoded.vs2) | Mux(d.decoded.scalarOperand,0.U,bit(d.decoded.vs1)))
  io.hazard.valid := active; io.hazard.slot := d.slot
  io.hazard.reads := readMask | Mux(d.decoded.masked,1.U,0.U)
  io.hazard.writes := bit(d.decoded.vd)

  val candidates = (0 until 4).map { sew =>
    val width = 8 << sew
    val values = (0 until p.dlen/width).map { e =>
      val a = io.readData(0)(e*width+width-1,e*width)
      val b = Mux(d.decoded.scalarOperand,d.issue.rs1(width-1,0),io.readData(1)(e*width+width-1,e*width))
      if(multiply) {
        val acc = io.readData(1)(e*width+width-1,e*width)
        val v1 = io.readData(2)(e*width+width-1,e*width)
        val product = (a * v1 + acc)(width-1,0)
        if(width == 32) {
          val dotProducts = (0 until 4).map { j =>
            val av = a(8*j+7,8*j).asSInt
            val bv = Mux(d.decoded.op === RvvOp.dotsu.U,
              Cat(0.U(1.W),d.issue.rs1(8*j+7,8*j)).asSInt,
              Cat(d.issue.rs1(8*j+7),d.issue.rs1(8*j+7,8*j)).asSInt)
            av * bv
          }
          val dot = (dotProducts.reduce(_ +& _) +& Cat(0.U(1.W),acc).asSInt).asUInt
          Mux(d.decoded.op === RvvOp.macc.U,product,dot(31,0))
        } else product
      } else {
        val moved = Mux(d.decoded.scalarOperand,d.issue.rs1(width-1,0),a)
        MuxLookup(d.decoded.op,moved)(Seq(
          RvvOp.add.U -> (a+b)(width-1,0), RvvOp.sub.U -> (a-b)(width-1,0),
          RvvOp.and.U -> (a & b), RvvOp.shift.U -> (a >> d.issue.rs1(log2Ceil(width)-1,0))))
      }
    }
    Cat(values.reverse)
  }
  io.write.bits.row := address(d.decoded.vd)
  io.write.bits.data := VecInit(candidates)(d.decoded.sew)
  io.write.bits.enables := VecInit((0 until p.rowBytes).map { b =>
    val element = (bytePosition + b.U) >> d.decoded.sew
    element < d.issue.vl && element >= d.issue.vstart &&
      (!d.decoded.masked || (io.mask >> element)(0))
  }).asUInt
  val zero = d.issue.vl === 0.U || d.issue.vstart >= d.issue.vl
  io.write.valid := active && !zero && io.grant && !io.blocked
  io.progress.valid := (io.write.fire || (active && zero))
  io.progress.bits.slot := d.slot
  io.progress.bits.readDone := Mux(regLast,readMask & Mux(d.decoded.masked,"hfffffffe".U,"hffffffff".U),0.U)
  io.progress.bits.writeDone := Mux(regLast,bit(d.decoded.vd),0.U)
  io.progress.bits.finished := zero || last
  when(io.progress.valid) { when(zero || last) { active := false.B }.otherwise { row := row + 1.U } }
  io.in.ready := !active || (io.progress.valid && io.progress.bits.finished)
  when(io.in.fire) { d := io.in.bits; active := true.B; row := 0.U }
  io.readEvent.valid := io.write.fire && subrow === 0.U
  io.readEvent.bits.age := d.age; io.readEvent.bits.register := d.decoded.vs2+registerOffset
  io.complete.valid := io.progress.valid && io.progress.bits.finished
  io.complete.bits := d.age
}

class RvvCrossLaneSequencer(p: RvvParams) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new RvvDescriptor(p)))
    val row = Output(UInt(p.rowBits.W)); val data = Input(UInt(p.dlen.W))
    val hazard = Output(new RvvHazard(p)); val blocked = Input(Bool())
    val progress = Valid(new RvvProgress(p)); val result = Decoupled(new RvvScalar)
    val busy = Output(Bool())
  })
  val active = RegInit(false.B); val held = RegInit(false.B)
  val d = Reg(new RvvDescriptor(p)); val result = Reg(new RvvScalar)
  io.in.ready := !active && !held
  when(io.in.fire) { d := io.in.bits; active := true.B }
  io.row := d.decoded.vs2 * p.rowsPerReg.U
  io.hazard.valid := active; io.hazard.slot := d.slot
  io.hazard.reads := (1.U(32.W) << d.decoded.vs2)(31,0); io.hazard.writes := 0.U
  io.progress.valid := active && !io.blocked
  io.progress.bits.slot := d.slot; io.progress.bits.readDone := "hffffffff".U
  io.progress.bits.writeDone := 0.U; io.progress.bits.finished := true.B
  when(io.progress.valid) {
    result.rd := d.issue.rd; result.floating := false.B
    result.data := MuxLookup(d.decoded.sew,io.data(63,0))(Seq(
      0.U -> io.data(7,0).asSInt.pad(64).asUInt,
      1.U -> io.data(15,0).asSInt.pad(64).asUInt,
      2.U -> io.data(31,0).asSInt.pad(64).asUInt))
    active := false.B; held := true.B
  }
  io.result.valid := held; io.result.bits := result
  when(io.result.fire) { held := false.B }
  io.busy := active || held
}

class RvvFpSequencer(p: RvvParams) extends Module {
  val io = IO(new Bundle { val in = Flipped(Decoupled(new RvvDescriptor(p))); val busy = Output(Bool()) })
  io.in.ready := false.B; io.busy := io.in.valid
  assert(!io.in.valid,"R02 FP execution is not implemented")
}
