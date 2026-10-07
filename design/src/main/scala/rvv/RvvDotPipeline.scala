package flow.rvv

import chisel3._
import chisel3.util._

/** Fixed-latency lane pipeline. Output credits are acquired at the read edge,
  * so downstream arbitration cannot stall the DSP registers. The row-tagged
  * accumulator cache is tagged and snooped; it is a bypass, not architectural
  * storage. Ordinary VRF writes invalidate a matching older cached value. */
class RvvDotPipeline(p: RvvParams) extends Module {
  val io = IO(new RvvIntegerPorts(p))
  val ageAdvance = IO(Input(Valid(UInt(p.ageBits.W))))
  val snoop = IO(Input(Vec(3,Valid(new Bundle {
    val row = UInt(p.rowBits.W); val age = UInt(p.ageBits.W)
  }))))
  val active = RegInit(false.B)
  val d = Reg(new RvvDescriptor(p))
  val row = RegInit(0.U(log2Ceil(p.rows+1).W))
  val rowsTotal = (d.decoded.bytes+(p.rowBytes-1).U) >> log2Ceil(p.rowBytes)
  val zero = d.issue.vl === 0.U || d.issue.vstart >= d.issue.vl
  val last = row+1.U >= rowsTotal
  def bit(r: UInt): UInt = (1.U(32.W) << r)(31,0)
  val source = d.decoded.vs2+(row >> log2Ceil(p.rowsPerReg))
  val dest = d.decoded.vd+(row >> log2Ceil(p.rowsPerReg))
  io.age := d.age
  io.readRows(0) := d.decoded.vs2*p.rowsPerReg.U+row
  io.readRows(1) := d.decoded.vd*p.rowsPerReg.U+row
  io.readRows(2) := 0.U; io.readDemand := Mux(active,2.U,0.U)
  io.hazard.valid := active && !zero; io.hazard.slot := d.slot
  io.hazard.reads := bit(source) | bit(dest) | Mux(d.decoded.masked,1.U,0.U)
  io.hazard.writes := bit(dest)
  // Accumulator forwarding applies only to older dots. Weight RAWs, v0,
  // and all cross-unit dependencies still use the registered scoreboard.
  io.hazard.aluBypass := false.B; io.hazard.maskRead := d.decoded.masked
  io.hazard.dotBypass := source =/= dest && (!d.decoded.masked || dest =/= 0.U)
  io.hazard.accumulator := bit(dest)
  io.hazard.observedSource := bit(source)

  class Token extends Bundle {
    val desc = new RvvDescriptor(p); val row = UInt(log2Ceil(p.rows+1).W)
    val bypassExpected = Bool()
    val last = Bool(); val enables = UInt(p.rowBytes.W)
  }
  class Result extends Bundle {
    val token = new Token; val data = UInt(p.dlen.W)
  }
  val out = Module(new Queue(new Result,16))
  val reserved = RegInit(0.U(5.W))
  val room = reserved < 16.U
  io.readValid := active && !zero && room && io.grant && !io.blocked
  io.in.ready := !active || (io.readValid && last) || (active && zero)
  when(io.readValid) { row := row+1.U; when(last) { active := false.B } }
  when(active && zero) { active := false.B }
  when(io.in.fire) { d := io.in.bits; row := 0.U; active := true.B }

  val launch = Wire(new Token)
  launch.desc := d; launch.row := row; launch.last := last
  launch.bypassExpected := io.accumulatorPending
  io.skipAccumulator := io.accumulatorPending
  io.readEnables := Mux(io.accumulatorPending,5.U,7.U)
  launch.enables := VecInit((0 until p.rowBytes).map { b =>
    val element = (row*p.rowBytes.U+b.U) >> d.decoded.sew
    element < d.issue.vl && element >= d.issue.vstart && (!d.decoded.masked || (io.mask >> element)(0))
  }).asUInt
  val valid = Seq.iterate(io.readValid,8)(v => RegNext(v,false.B))
  val tokens = Seq.iterate(launch,8)(t => RegNext(t))
  // BRAM array -> read output register -> bank mux -> A/B registers.
  val weights = RegNext(io.readData(0))
  val rawAccumulator = Seq.iterate(io.readData(1),5)(x => RegNext(x))
  val scalar = tokens(2).desc.issue.rs1
  // A/B are the explicit operand registers above; M and P below are inferred
  // into DSP48 where legal. All stages advance independently every edge.
  val products = (0 until p.dlen/32).map { e =>
    (0 until 4).map { j =>
      val a = weights(32*e+8*j+7,32*e+8*j).asSInt
      val b = RegNext(Mux(tokens(2).desc.decoded.op === RvvOp.dotsu.U,
        Cat(0.U(1.W),tokens(2).desc.issue.rs1(8*j+7,8*j)).asSInt,
        Cat(tokens(2).desc.issue.rs1(8*j+7),tokens(2).desc.issue.rs1(8*j+7,8*j)).asSInt))
      RegNext(a*b)
    }
  }
  val pProducts = products.map(xs => xs.map(x => RegNext(x)))
  val sums = pProducts.map(xs => RegNext((xs(0)+&xs(1))+&(xs(2)+&xs(3))))
  val t = tokens(6)
  val address = (t.desc.decoded.vd*p.rowsPerReg.U+t.row)(p.rowBits-1,0)
  val cacheDepth = p.rows
  val cacheValid = RegInit(VecInit(Seq.fill(cacheDepth)(false.B)))
  val cacheTags = Reg(Vec(cacheDepth,UInt(p.rowBits.W)))
  val cacheAges = Reg(Vec(cacheDepth,UInt(p.ageBits.W)))
  val cacheData = Mem(cacheDepth,UInt(p.dlen.W))
  val cacheIndex = address(log2Ceil(cacheDepth)-1,0)
  val forwarded = cacheValid(cacheIndex) && cacheTags(cacheIndex) === address &&
    RvvAge.older(cacheAges(cacheIndex),t.desc.age)
  // Also forward within a single instruction only for an identical row (rows
  // are issued once); equality never substitutes another instruction's value.
  when(valid(6) && t.bypassExpected) { assert(forwarded,"in-unit accumulator bypass unavailable") }
  val acc = Mux(forwarded,cacheData.read(cacheIndex),rawAccumulator(4))
  val values = (0 until p.dlen/32).map(e => (sums(e).pad(32).asUInt+acc(32*e+31,32*e))(31,0))
  val result = Cat(values.reverse)
  val merged = VecInit((0 until p.rowBytes).map(b => Mux(t.enables(b),result(8*b+7,8*b),acc(8*b+7,8*b)))).asUInt
  for(k <- 0 until cacheDepth) {
    when(ageAdvance.valid && cacheValid(k) && !RvvAge.older(cacheAges(k),ageAdvance.bits) && cacheAges(k) =/= ageAdvance.bits) { cacheValid(k) := false.B }
    for(s <- snoop) {
      when(s.valid && cacheValid(k) && cacheTags(k) === s.bits.row && !RvvAge.older(s.bits.age,cacheAges(k))) {
        cacheValid(k) := false.B
      }
    }
    when(valid(6) && cacheIndex === k.U) {
      cacheValid(k) := true.B; cacheTags(k) := address; cacheAges(k) := t.desc.age
    }
  }
  when(valid(6)) { cacheData.write(cacheIndex,merged) }
  val accumulated = RegNext(result)
  out.io.enq.valid := valid(7); out.io.enq.bits.token := tokens(7); out.io.enq.bits.data := accumulated
  when(valid(7)) { assert(out.io.enq.ready,"dot output credit lost") }
  io.write.valid := out.io.deq.valid
  io.write.bits.row := out.io.deq.bits.token.desc.decoded.vd*p.rowsPerReg.U+out.io.deq.bits.token.row
  io.write.bits.data := out.io.deq.bits.data; io.write.bits.enables := out.io.deq.bits.token.enables
  io.writeAge := out.io.deq.bits.token.desc.age
  out.io.deq.ready := io.write.ready
  when(io.readValid =/= io.write.fire) { reserved := reserved+io.readValid.asUInt-io.write.fire.asUInt }
  io.busy := active || reserved =/= 0.U
  val rLast = ((row+1.U) % p.rowsPerReg.U) === 0.U || last
  io.readProgress.valid := io.readValid && rLast
  io.readProgress.bits.slot := d.slot
  io.readProgress.bits.readDone := (bit(source)|bit(dest)) & Mux(d.decoded.masked,"hfffffffe".U,"hffffffff".U)
  io.readProgress.bits.writeDone := 0.U; io.readProgress.bits.finished := false.B
  val wt = out.io.deq.bits.token
  val wReg = wt.desc.decoded.vd+(wt.row >> log2Ceil(p.rowsPerReg))
  val wLast = ((wt.row+1.U) % p.rowsPerReg.U) === 0.U || wt.last
  io.progress.valid := io.write.fire || (active && zero && !out.io.deq.valid)
  io.progress.bits.slot := Mux(io.write.fire,wt.desc.slot,d.slot)
  io.progress.bits.readDone := 0.U
  io.progress.bits.writeDone := Mux(wLast,bit(wReg),0.U)
  io.progress.bits.finished := Mux(io.write.fire,wt.last,zero)
  // Keep zero-length instructions until they can share the one retirement port.
  when(active && zero && out.io.deq.valid) { active := true.B; io.in.ready := false.B }
  io.complete.valid := io.progress.valid && io.progress.bits.finished
  io.complete.bits := Mux(io.write.fire,wt.desc.age,d.age)
  io.readEvent.valid := io.readValid && (row % p.rowsPerReg.U) === 0.U
  io.readEvent.bits.age := d.age; io.readEvent.bits.register := source
  io.readyEvent.valid := active && !zero && room && io.grant && !io.otherRawBlocked && !io.warBlocked && !io.wawBlocked && (row % p.rowsPerReg.U) === 0.U
  io.readyEvent.bits := io.readEvent.bits
  io.candidateEvent.valid := active && !zero && (row % p.rowsPerReg.U) === 0.U
  io.candidateEvent.bits := io.readEvent.bits
  io.blocking := Cat(io.otherRawBlocked,!room,!io.grant,io.warBlocked,io.wawBlocked)
}

/** Dots pipeline across instruction boundaries; other operations keep the
  * existing single-instruction sequencer and cannot cross a dot drain. */
class RvvIntegerSequencer(p: RvvParams,multiply: Boolean) extends Module {
  val io = IO(new RvvIntegerPorts(p))
  val ageAdvance = IO(Input(Valid(UInt(p.ageBits.W))))
  val snoop = IO(Input(Vec(2,Valid(new Bundle {
    val row = UInt(p.rowBits.W); val age = UInt(p.ageBits.W)
  }))))
  if(!multiply) {
    val alu = Module(new RvvAluPipeline(p)); alu.snoop := snoop; alu.ageAdvance := ageAdvance; io <> alu.io
  }
  else {
    val legacy = Module(new RvvLegacyIntegerSequencer(p,true))
    val dot = Module(new RvvDotPipeline(p)); dot.ageAdvance := ageAdvance
    for(j <- 0 until 2) { dot.snoop(j) := snoop(j) }
    dot.snoop(2).valid := legacy.io.write.fire
    dot.snoop(2).bits.row := legacy.io.write.bits.row; dot.snoop(2).bits.age := legacy.io.writeAge
    val isDot = io.in.bits.decoded.op === RvvOp.dot.U || io.in.bits.decoded.op === RvvOp.dotsu.U
    legacy.io.in.valid := io.in.valid && !isDot && !dot.io.busy
    dot.io.in.valid := io.in.valid && isDot && !legacy.io.busy
    legacy.io.in.bits := io.in.bits; dot.io.in.bits := io.in.bits
    io.in.ready := Mux(isDot,dot.io.in.ready && !legacy.io.busy,legacy.io.in.ready && !dot.io.busy)
    for(x <- Seq(legacy.io,dot.io)) {
      x.readData := io.readData; x.grant := io.grant; x.mask := io.mask
      x.internalWrites := io.internalWrites; x.internalRows := io.internalRows
      x.accumulatorPending := io.accumulatorPending
      x.otherRawBlocked := io.otherRawBlocked
      x.blocked := io.blocked; x.rawBlocked := io.rawBlocked; x.warBlocked := io.warBlocked; x.wawBlocked := io.wawBlocked
      x.write.ready := io.write.ready
    }
    val useDot = dot.io.busy
    io.readEnables := Mux(useDot,dot.io.readEnables,legacy.io.readEnables)
    io.skipAccumulator := useDot && dot.io.skipAccumulator
    io.readRows := Mux(useDot,dot.io.readRows,legacy.io.readRows)
    io.readDemand := Mux(useDot,dot.io.readDemand,legacy.io.readDemand)
    io.readValid := Mux(useDot,dot.io.readValid,legacy.io.readValid)
    io.hazard := Mux(useDot,dot.io.hazard,legacy.io.hazard)
    io.progress := Mux(useDot,dot.io.progress,legacy.io.progress)
    io.readProgress := Mux(useDot,dot.io.readProgress,legacy.io.readProgress)
    io.write.valid := Mux(useDot,dot.io.write.valid,legacy.io.write.valid)
    io.write.bits := Mux(useDot,dot.io.write.bits,legacy.io.write.bits)
    io.age := Mux(useDot,dot.io.age,legacy.io.age)
    io.writeAge := Mux(useDot,dot.io.writeAge,legacy.io.writeAge)
    io.busy := dot.io.busy || legacy.io.busy
    io.readEvent := Mux(useDot,dot.io.readEvent,legacy.io.readEvent)
    io.complete := Mux(useDot,dot.io.complete,legacy.io.complete)
    io.candidateEvent := Mux(useDot,dot.io.candidateEvent,legacy.io.candidateEvent)
    io.readyEvent := Mux(useDot,dot.io.readyEvent,legacy.io.readyEvent)
    io.blocking := Mux(useDot,dot.io.blocking,legacy.io.blocking)
  }
}
