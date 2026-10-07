package flow.rvv

import chisel3._
import chisel3.util._

class RvvAluPipeline(p: RvvParams) extends Module {
  val io = IO(new RvvIntegerPorts(p))
  val ageAdvance = IO(Input(Valid(UInt(p.ageBits.W))))
  val snoop = IO(Input(Vec(2,Valid(new Bundle {
    val row = UInt(p.rowBits.W); val age = UInt(p.ageBits.W)
  }))))
  class Token extends Bundle {
    val desc = new RvvDescriptor(p); val row = UInt(log2Ceil(p.rows+1).W)
    val last = Bool(); val enables = UInt(p.rowBytes.W); val expected = UInt(2.W)
  }
  class Result extends Bundle { val token = new Token; val data = UInt(p.dlen.W) }
  val active = RegInit(false.B); val d = Reg(new RvvDescriptor(p))
  val row = RegInit(0.U(log2Ceil(p.rows+1).W))
  val rowsTotal = (d.decoded.bytes+(p.rowBytes-1).U) >> log2Ceil(p.rowBytes)
  val last = row+1.U >= rowsTotal
  val zero = d.issue.vl === 0.U || d.issue.vstart >= d.issue.vl
  val move = d.decoded.op === RvvOp.move.U
  val noRead = move && d.decoded.scalarOperand
  def bit(r: UInt): UInt = (1.U(32.W) << r)(31,0)
  val offset = row >> log2Ceil(p.rowsPerReg)
  val srcA = Mux(move,d.decoded.vs1,d.decoded.vs2)+offset
  val srcB = d.decoded.vs1+offset
  val dst = d.decoded.vd+offset
  val enables = VecInit((0 until p.rowBytes).map { b =>
    val e = (row*p.rowBytes.U+b.U) >> d.decoded.sew
    e < d.issue.vl && e >= d.issue.vstart && (!d.decoded.masked || (io.mask >> e)(0))
  }).asUInt
  val needsOld = !enables.andR
  val aUsed = !noRead; val bUsed = !move && !d.decoded.scalarOperand
  io.readRows(0) := Mux(move,d.decoded.vs1,d.decoded.vs2)*p.rowsPerReg.U+row
  io.readRows(1) := d.decoded.vs1*p.rowsPerReg.U+row; io.readRows(2) := d.decoded.vd*p.rowsPerReg.U+row
  val skipA = aUsed && io.internalRows(io.readRows(0)) && (!d.decoded.masked || srcA =/= 0.U)
  val skipB = bUsed && io.internalRows(io.readRows(1)) && (!d.decoded.masked || srcB =/= 0.U)
  val skipOld = needsOld && io.internalRows(io.readRows(2)) && (!d.decoded.masked || dst =/= 0.U)
  io.readEnables := Cat(needsOld && !skipOld,!skipB && bUsed,!skipA && aUsed)
  io.skipAccumulator := false.B
  io.readDemand := Mux(active,Mux(needsOld,3.U,Mux(noRead,0.U,Mux(bUsed,2.U,1.U))),0.U)
  io.hazard.valid := active && !zero; io.hazard.slot := d.slot
  io.hazard.reads := Mux(aUsed,bit(srcA),0.U) | Mux(bUsed,bit(srcB),0.U) | Mux(d.decoded.masked,1.U,0.U) | Mux(needsOld,bit(dst),0.U)
  io.hazard.writes := bit(dst)
  io.hazard.dotBypass := false.B; io.hazard.accumulator := 0.U
  io.hazard.aluBypass := true.B; io.hazard.maskRead := d.decoded.masked; io.hazard.observedSource := 0.U
  val out = Module(new Queue(new Result,8))
  val reserved = RegInit(0.U(4.W))
  val launchValid = active && !zero && reserved < 8.U && io.grant && !io.blocked
  io.readValid := launchValid
  io.in.ready := !active || (launchValid && last) || (active && zero && !out.io.deq.valid)
  when(launchValid) { row := row+1.U; when(last) { active := false.B } }
  when(active && zero && !out.io.deq.valid) { active := false.B }
  when(io.in.fire) { d := io.in.bits; row := 0.U; active := true.B }
  val launch = Wire(new Token)
  launch.desc := d; launch.row := row; launch.last := last; launch.expected := Cat(skipB,skipA)
  launch.enables := enables
  val valid = Seq.iterate(launchValid,5)(v => RegNext(v,false.B))
  val tokens = Seq.iterate(launch,5)(t => RegNext(t))
  val operands = RegNext(io.readData)
  val t = tokens(3)
  val cacheValid = RegInit(VecInit(Seq.fill(p.rows)(false.B)))
  val cacheAges = Reg(Vec(p.rows,UInt(p.ageBits.W)))
  val cacheData = Mem(p.rows,UInt(p.dlen.W))
  val addrA = (Mux(t.desc.decoded.op === RvvOp.move.U,t.desc.decoded.vs1,t.desc.decoded.vs2)*p.rowsPerReg.U+t.row)(p.rowBits-1,0)
  val addrB = (t.desc.decoded.vs1*p.rowsPerReg.U+t.row)(p.rowBits-1,0)
  val addrD = (t.desc.decoded.vd*p.rowsPerReg.U+t.row)(p.rowBits-1,0)
  def operand(at: UInt,port: Int): UInt = {
    val hit = cacheValid(at) && RvvAge.older(cacheAges(at),t.desc.age)
    when(valid(3) && t.expected(port)) {
      when(!hit) { printf(p"ALU_BYPASS age=${t.desc.age} row=$at port=${port.U} cachedValid=${cacheValid(at)} cachedAge=${cacheAges(at)} op=${t.desc.decoded.op} vl=${t.desc.issue.vl} start=${t.desc.issue.vstart}\n") }
      assert(hit,"in-unit ALU bypass unavailable")
    }
    Mux(hit,cacheData.read(at),operands(port))
  }
  val aInput = operand(addrA,0); val bInput = operand(addrB,1)
  val scalarInput = t.desc.issue.rs1; val sewInput = t.desc.decoded.sew
  val scalarOperand = t.desc.decoded.scalarOperand; val opInput = t.desc.decoded.op
  val result = RvvAluArithmetic(p,aInput,bInput,scalarInput,sewInput,scalarOperand,opInput)
  val computed = RegNext(result)
  // Byte-preserved cached values are needed only for source forwarding. For
  // disabled destination bytes use its previous cache/VRF value only when it is
  // already present; uncached partial destinations invalidate the bypass.
  val oldKnown = cacheValid(addrD) && RvvAge.older(cacheAges(addrD),t.desc.age)
  val old = Mux(oldKnown,cacheData.read(addrD),operands(2))
  val merged = VecInit((0 until p.rowBytes).map(b => Mux(t.enables(b),result(8*b+7,8*b),old(8*b+7,8*b)))).asUInt
  when(valid(3)) { cacheData.write(addrD,merged) }
  for(k <- 0 until p.rows) {
    when(ageAdvance.valid && cacheValid(k) && !RvvAge.older(cacheAges(k),ageAdvance.bits) && cacheAges(k) =/= ageAdvance.bits) { cacheValid(k) := false.B }
    for(s <- snoop) {
      when(s.valid && s.bits.row === k.U && cacheValid(k) && !RvvAge.older(s.bits.age,cacheAges(k))) { cacheValid(k) := false.B }
    }
    when(valid(3) && addrD === k.U) { cacheValid(k) := true.B; cacheAges(k) := t.desc.age }
  }
  out.io.enq.valid := valid(4); out.io.enq.bits.token := tokens(4); out.io.enq.bits.data := computed
  when(valid(4)) { assert(out.io.enq.ready,"ALU output credit lost") }
  val wt = out.io.deq.bits.token
  io.write.valid := out.io.deq.valid; out.io.deq.ready := io.write.ready
  io.write.bits.row := wt.desc.decoded.vd*p.rowsPerReg.U+wt.row
  io.write.bits.data := out.io.deq.bits.data; io.write.bits.enables := wt.enables
  io.writeAge := wt.desc.age; io.age := d.age
  when(launchValid =/= io.write.fire) { reserved := reserved+launchValid.asUInt-io.write.fire.asUInt }
  io.busy := active || reserved =/= 0.U
  val rLast = ((row+1.U) % p.rowsPerReg.U) === 0.U || last
  io.readProgress.valid := launchValid && rLast
  io.readProgress.bits.slot := d.slot
  io.readProgress.bits.readDone := (Mux(aUsed,bit(srcA),0.U) | Mux(bUsed,bit(srcB),0.U)) & Mux(d.decoded.masked,"hfffffffe".U,"hffffffff".U)
  io.readProgress.bits.writeDone := 0.U; io.readProgress.bits.finished := false.B
  val wLast = ((wt.row+1.U) % p.rowsPerReg.U) === 0.U || wt.last
  io.progress.valid := io.write.fire || (active && zero && !out.io.deq.valid)
  io.progress.bits.slot := Mux(io.write.fire,wt.desc.slot,d.slot)
  io.progress.bits.readDone := 0.U
  io.progress.bits.writeDone := Mux(wLast,bit(wt.desc.decoded.vd+(wt.row >> log2Ceil(p.rowsPerReg))),0.U)
  io.progress.bits.finished := Mux(io.write.fire,wt.last,zero)
  io.readEvent.valid := false.B; io.readEvent.bits := 0.U.asTypeOf(new RvvRegisterEvent)
  io.complete.valid := io.progress.valid && io.progress.bits.finished; io.complete.bits := Mux(io.write.fire,wt.desc.age,d.age)
  io.candidateEvent.valid := false.B; io.candidateEvent.bits := 0.U.asTypeOf(new RvvRegisterEvent)
  io.readyEvent.valid := false.B; io.readyEvent.bits := 0.U.asTypeOf(new RvvRegisterEvent); io.blocking := 0.U
}

object RvvAluArithmetic {
  def apply(p: RvvParams,aInput: UInt,bInput: UInt,scalarInput: UInt,sewInput: UInt,scalarOperand: Bool,opInput: UInt): UInt = {
    val a = aInput
    val broadcasts = (0 until 4).map(sew => Fill(p.dlen/(8 << sew),scalarInput((8 << sew)-1,0)))
    val scalar = VecInit(broadcasts)(sewInput)
    val b = Mux(scalarOperand,scalar,bInput)
    val subtract = opInput === RvvOp.sub.U
    // Byte generate/propagate avoids chaining two nine-bit additions per
    // byte. No element exceeds eight bytes; unconditional block boundaries
    // keep the physical carry network local even with runtime SEW.
    val byteBase = (0 until p.rowBytes).map { j =>
      val bv = Mux(subtract,~b(8*j+7,8*j),b(8*j+7,8*j))
      a(8*j+7,8*j) +& bv
    }
    val sums = (0 until p.rowBytes).map { j =>
      var cin: Bool = subtract
      val block = j-j%8
      for(k <- block until j) {
        val nextBoundary = VecInit((0 until 4).map(sew => ((k+1) % (1 << sew) == 0).B))(sewInput)
        cin = Mux(nextBoundary,subtract,byteBase(k)(8) || (byteBase(k)(7,0).andR && cin))
      }
      (byteBase(j)(7,0) + cin.asUInt)(7,0)
    }
    var shifted: UInt = a
    for(k <- 0 until 6) {
      val distance = 1 << k
      val bits = (0 until p.dlen).map { j =>
        val inside = VecInit((0 until 4).map(sew => (j % (8 << sew)+distance < (8 << sew)).B))(sewInput)
        val source = if(j+distance < p.dlen) shifted(j+distance) else false.B
        Mux(scalarInput(k) && k.U < (3.U +& sewInput),source && inside,shifted(j))
      }
      shifted = VecInit(bits).asUInt
    }
    val moved = Mux(scalarOperand,scalar,a)
    MuxLookup(opInput,moved)(Seq(
      RvvOp.add.U -> Cat(sums.reverse), RvvOp.sub.U -> Cat(sums.reverse),
      RvvOp.and.U -> (a & b), RvvOp.shift.U -> shifted))
  }
}
