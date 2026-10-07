package flow.rvv

import chisel3._
import chisel3.util._

/** One row per accepted beat, with register-granularity progress. The shared
  * read-port grant is separate from register hazards and bank arbitration. */
class RvvLegacyIntegerSequencer(p: RvvParams, multiply: Boolean) extends Module {
  val io = IO(new RvvIntegerPorts(p))
  val active = RegInit(false.B); val d = Reg(new RvvDescriptor(p))
  val row = RegInit(0.U(log2Ceil(p.rows+1).W))
  val pending = RegInit(false.B); val readAll = RegInit(false.B)
  val heldOperands = RegNext(io.readData)
  val operandData = heldOperands
  val returned = RegNext(RegNext(RegNext(io.readValid,false.B),false.B),false.B)
  val computed = RegInit(false.B)
  val operandRow = Reg(UInt(log2Ceil(p.rows+1).W))
  // Scalar moves need no BRAM operand and can write directly from the
  // registered descriptor. Vector reads retain the synchronous operand stage.
  val noRead = if(multiply) false.B else d.decoded.op === RvvOp.move.U && d.decoded.scalarOperand
  val resultRow = Mux(noRead,row,operandRow)
  val bytePosition = resultRow * p.rowBytes.U
  val registerOffset = resultRow >> log2Ceil(p.rowsPerReg)
  val subrow = if(p.rowsPerReg == 1) 0.U else resultRow(log2Ceil(p.rowsPerReg)-1,0)
  def address(base: UInt, at: UInt = operandRow): UInt = (base*p.rowsPerReg.U+at)(p.rowBits-1,0)
  def bit(base: UInt, at: UInt = operandRow): UInt = (1.U(32.W) << (base+(at >> log2Ceil(p.rowsPerReg))))(31,0)
  io.age := d.age; io.writeAge := d.age; io.busy := active
  io.readProgress.valid := false.B; io.readProgress.bits := 0.U.asTypeOf(new RvvProgress(p))
  io.readyEvent.valid := false.B; io.readyEvent.bits := 0.U.asTypeOf(new RvvRegisterEvent)
  io.blocking := 0.U
  io.hazard.dotBypass := false.B; io.hazard.accumulator := 0.U; io.hazard.observedSource := 0.U
  io.readRows(0) := address(d.decoded.vs2,row)
  io.readRows(1) := address(if(multiply) d.decoded.vd else d.decoded.vs1,row)
  io.readRows(2) := address(d.decoded.vs1,row)
  io.readDemand := (if(multiply) Mux(d.decoded.op === RvvOp.macc.U,3.U,2.U)
    else Mux(d.decoded.scalarOperand,1.U,2.U))
  if(!multiply) {
    when(d.decoded.op === RvvOp.move.U) { io.readRows(0) := address(d.decoded.vs1,row); io.readDemand := Mux(d.decoded.scalarOperand,0.U,1.U) }
  }
  val rowsTotal = (d.decoded.bytes + (p.rowBytes-1).U) >> log2Ceil(p.rowBytes)
  val last = resultRow + 1.U >= rowsTotal
  val regLast = subrow === (p.rowsPerReg-1).U || last
  // SEW=64 multiplication is the documented slow path: capture one DLEN row,
  // reuse one 64-bit multiplier across its elements, then perform one bank write.
  val slow = if(multiply) d.decoded.sew === 3.U else false.B
  val slowInputs = Reg(Vec(3,UInt(p.dlen.W)))
  val slowValues = Reg(Vec(p.dlen/64,UInt(64.W)))
  val slowIndex = RegInit(0.U(math.max(1,log2Ceil(p.dlen/64)).W))
  val slowActive = RegInit(false.B); val slowDone = RegInit(false.B)
  val zero = d.issue.vl === 0.U || d.issue.vstart >= d.issue.vl
  val captureSlow = returned && slow && !slowActive && !slowDone
  when(captureSlow) {
    slowInputs := operandData; slowIndex := 0.U; slowActive := true.B
  }
  when(slowActive && slow) {
    val shift = slowIndex << 6
    val a=(slowInputs(0) >> shift)(63,0)
    val b=(slowInputs(2) >> shift)(63,0)
    val acc=(slowInputs(1) >> shift)(63,0)
    slowValues(slowIndex) := (a*b+acc)(63,0)
    when(slowIndex === (p.dlen/64-1).U) { slowActive := false.B; slowDone := true.B }
      .otherwise { slowIndex := slowIndex+1.U }
  }
  when(slow && (slowActive || slowDone)) { io.readDemand := 0.U }
  def readsAt(at: UInt): UInt = if(multiply) bit(d.decoded.vs2,at) | bit(d.decoded.vd,at) |
    Mux(d.decoded.op === RvvOp.macc.U,bit(d.decoded.vs1,at),0.U)
    else Mux(d.decoded.op === RvvOp.move.U,Mux(d.decoded.scalarOperand,0.U,bit(d.decoded.vs1,at)),
      bit(d.decoded.vs2,at) | Mux(d.decoded.scalarOperand,0.U,bit(d.decoded.vs1,at)))
  val readMask = readsAt(operandRow)
  io.hazard.valid := active; io.hazard.slot := d.slot
  io.hazard.reads := Mux(readAll,0.U,readsAt(row)) | Mux(d.decoded.masked,1.U,0.U)
  when(slow && (slowActive || slowDone)) { io.hazard.reads := Mux(d.decoded.masked,1.U,0.U) }
  io.hazard.writes := bit(d.decoded.vd,Mux(noRead,row,Mux(pending,operandRow,row)))

  val resultData = if(multiply) {
  val candidates = (0 until 4).map { sew =>
    val width = 8 << sew
    val values = (0 until p.dlen/width).map { e =>
      val a = operandData(0)(e*width+width-1,e*width)
      val b = Mux(d.decoded.scalarOperand,d.issue.rs1(width-1,0),operandData(1)(e*width+width-1,e*width))
      if(multiply) {
        val acc = operandData(1)(e*width+width-1,e*width)
        val v1 = operandData(2)(e*width+width-1,e*width)
        val product = if(width==64) 0.U(64.W) else (a * v1 + acc)(width-1,0)
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
    VecInit(candidates)(d.decoded.sew)
  } else {
    val a = operandData(0)
    val broadcasts = (0 until 4).map(sew => Fill(p.dlen/(8 << sew),d.issue.rs1((8 << sew)-1,0)))
    val scalar = VecInit(broadcasts)(d.decoded.sew)
    val b = Mux(d.decoded.scalarOperand,scalar,operandData(1))
    val subtract = d.decoded.op === RvvOp.sub.U
    var carry: UInt = subtract.asUInt
    val sums = (0 until p.rowBytes).map { j =>
      val boundary = VecInit((0 until 4).map(sew => (j % (1 << sew) == 0).B))(d.decoded.sew)
      val cin = Mux(boundary,subtract.asUInt,carry)
      val bv = Mux(subtract,~b(8*j+7,8*j),b(8*j+7,8*j))
      val sum = (a(8*j+7,8*j) +& bv) + cin
      carry = sum(8)
      sum(7,0)
    }
    var shifted: UInt = a
    for(k <- 0 until 6) {
      val distance = 1 << k
      val bits = (0 until p.dlen).map { j =>
        val inside = VecInit((0 until 4).map(sew => (j % (8 << sew)+distance < (8 << sew)).B))(d.decoded.sew)
        val source = if(j+distance < p.dlen) shifted(j+distance) else false.B
        Mux(d.issue.rs1(k) && k.U < (3.U +& d.decoded.sew),source && inside,shifted(j))
      }
      shifted = VecInit(bits).asUInt
    }
    val moved = Mux(d.decoded.scalarOperand,scalar,a)
    MuxLookup(d.decoded.op,moved)(Seq(
      RvvOp.add.U -> Cat(sums.reverse), RvvOp.sub.U -> Cat(sums.reverse),
      RvvOp.and.U -> (a & b), RvvOp.shift.U -> shifted))
  }
  val registeredResult = Reg(UInt(p.dlen.W))
  when((returned && !slow) || (active && noRead && !computed)) { registeredResult := resultData; computed := true.B }
  io.write.bits.row := address(d.decoded.vd,resultRow)
  io.write.bits.data := registeredResult
  when(slow) { io.write.bits.data := slowValues.asUInt }
  io.write.bits.enables := VecInit((0 until p.rowBytes).map { b =>
    val element = (bytePosition + b.U) >> d.decoded.sew
    element < d.issue.vl && element >= d.issue.vstart &&
      (!d.decoded.masked || (io.mask >> element)(0))
  }).asUInt
  io.write.valid := (pending || (active && noRead)) && !zero && Mux(slow,slowDone,computed) && !io.blocked
  io.readValid := active && !noRead && !zero && !readAll && !slowActive && !slowDone &&
    !pending && io.grant && !io.blocked
  when(io.readValid) {
    pending := true.B; computed := false.B; operandRow := row; row := row+1.U
    when(row+1.U >= rowsTotal) { readAll := true.B }
  }.elsewhen(io.write.fire) { pending := false.B }
  when(io.write.fire) { computed := false.B }
  when(io.write.fire && noRead) { row := row+1.U }
  io.progress.valid := (io.write.fire || (active && zero))
  io.progress.bits.slot := d.slot
  io.progress.bits.readDone := Mux(regLast,readMask & Mux(d.decoded.masked,"hfffffffe".U,"hffffffff".U),0.U)
  io.progress.bits.writeDone := Mux(regLast,bit(d.decoded.vd,resultRow),0.U)
  io.progress.bits.finished := zero || last
  when(captureSlow) {
    io.progress.valid := true.B
    io.progress.bits.readDone := Mux(regLast,readMask & Mux(d.decoded.masked,"hfffffffe".U,"hffffffff".U),0.U)
    io.progress.bits.writeDone := 0.U; io.progress.bits.finished := false.B
  }
  when(io.write.fire || (active && zero)) {
    slowDone := false.B
    when(zero || last) { active := false.B }
  }
  io.in.ready := !active || (io.progress.valid && io.progress.bits.finished)
  when(io.in.fire) { d := io.in.bits; active := true.B; computed := false.B; row := 0.U; pending := false.B; readAll := false.B; slowActive := false.B; slowDone := false.B }
  io.readEvent.valid := io.readValid && (if(p.rowsPerReg == 1) true.B else row(log2Ceil(p.rowsPerReg)-1,0) === 0.U)
  io.readEvent.bits.age := d.age; io.readEvent.bits.register := d.decoded.vs2+(row >> log2Ceil(p.rowsPerReg))
  io.complete.valid := io.progress.valid && io.progress.bits.finished
  io.complete.bits := d.age
}

class RvvCrossLaneSequencer(p: RvvParams) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new RvvDescriptor(p)))
    val readValid = Output(Bool()); val row = Output(UInt(p.rowBits.W)); val data = Input(UInt(p.dlen.W))
    val hazard = Output(new RvvHazard(p)); val blocked = Input(Bool())
    val progress = Valid(new RvvProgress(p)); val result = Decoupled(new RvvScalar)
    val busy = Output(Bool())
  })
  val active = RegInit(false.B); val held = RegInit(false.B); val pending = RegInit(false.B)
  val d = Reg(new RvvDescriptor(p)); val result = Reg(new RvvScalar)
  val readReturned = RegNext(RegNext(io.readValid,false.B),false.B)
  io.in.ready := !active && !held
  when(io.in.fire) { d := io.in.bits; active := true.B }
  io.hazard.dotBypass := false.B; io.hazard.accumulator := 0.U; io.hazard.observedSource := 0.U
  io.row := d.decoded.vs2 * p.rowsPerReg.U
  io.hazard.valid := active; io.hazard.slot := d.slot
  io.hazard.reads := (1.U(32.W) << d.decoded.vs2)(31,0); io.hazard.writes := 0.U
  io.readValid := active && !pending && !io.blocked
  when(io.readValid) { pending := true.B }
  io.progress.valid := active && pending && readReturned && !io.blocked
  io.progress.bits.slot := d.slot; io.progress.bits.readDone := "hffffffff".U
  io.progress.bits.writeDone := 0.U; io.progress.bits.finished := true.B
  when(io.progress.valid) {
    result.rd := d.issue.rd; result.floating := false.B
    result.data := MuxLookup(d.decoded.sew,io.data(63,0))(Seq(
      0.U -> io.data(7,0).asSInt.pad(64).asUInt,
      1.U -> io.data(15,0).asSInt.pad(64).asUInt,
      2.U -> io.data(31,0).asSInt.pad(64).asUInt))
    active := false.B; pending := false.B; held := true.B
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

class RvvIntegerPorts(p: RvvParams) extends Bundle {
    val in = Flipped(Decoupled(new RvvDescriptor(p)))
    val readRows = Output(Vec(3,UInt(p.rowBits.W))); val readData = Input(Vec(3,UInt(p.dlen.W)))
    val readValid = Output(Bool())
    val readDemand = Output(UInt(2.W)); val grant = Input(Bool())
    val mask = Input(UInt(p.vlen.W))
    val hazard = Output(new RvvHazard(p)); val blocked = Input(Bool())
    val progress = Valid(new RvvProgress(p)); val write = Decoupled(new RvvWrite(p))
    val age = Output(UInt(p.ageBits.W)); val busy = Output(Bool())
    val readEvent = Valid(new RvvRegisterEvent)
    val complete = Valid(UInt(64.W))
  val readProgress = Valid(new RvvProgress(p))
  val writeAge = Output(UInt(p.ageBits.W))
  val otherRawBlocked = Input(Bool())
  val rawBlocked = Input(Bool()); val warBlocked = Input(Bool()); val wawBlocked = Input(Bool())
  val readyEvent = Valid(new RvvRegisterEvent)
  val blocking = Output(UInt(4.W))
}
