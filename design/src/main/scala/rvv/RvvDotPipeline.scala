package flow.rvv

import chisel3._
import chisel3.util._

/** Fixed-latency lane pipeline. Output credits are acquired at the read edge,
  * so downstream arbitration cannot stall the DSP registers. The eight-row
  * accumulator cache is tagged and snooped; it is a bypass, not architectural
  * storage. Ordinary VRF writes invalidate a matching older cached value. */
class RvvDotPipeline(p: RvvParams) extends Module {
  val io = IO(new RvvIntegerPorts(p))
  val snoop = IO(Input(Vec(2,Valid(new Bundle {
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
  io.hazard.dotBypass := source =/= dest && (!d.decoded.masked || dest =/= 0.U)
  io.hazard.accumulator := bit(dest)

  class Token extends Bundle {
    val desc = new RvvDescriptor(p); val row = UInt(log2Ceil(p.rows+1).W)
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
      val b = Mux(tokens(3).desc.decoded.op === RvvOp.dotsu.U,
        Cat(0.U(1.W),tokens(3).desc.issue.rs1(8*j+7,8*j)).asSInt,
        Cat(tokens(3).desc.issue.rs1(8*j+7),tokens(3).desc.issue.rs1(8*j+7,8*j)).asSInt)
      RegNext(a*b)
    }
  }
  val pairs = products.map(xs => Seq(RegNext(xs(0)+&xs(1)),RegNext(xs(2)+&xs(3))))
  val sums = pairs.map(xs => RegNext(xs(0)+&xs(1)))
  val t = tokens(6)
  val address = (t.desc.decoded.vd*p.rowsPerReg.U+t.row)(p.rowBits-1,0)
  val cacheDepth = 8*p.rowsPerReg
  val cacheValid = RegInit(VecInit(Seq.fill(cacheDepth)(false.B)))
  val cacheTags = Reg(Vec(cacheDepth,UInt(p.rowBits.W)))
  val cacheAges = Reg(Vec(cacheDepth,UInt(p.ageBits.W)))
  val cacheData = Reg(Vec(cacheDepth,UInt(p.dlen.W)))
  val cacheIndex = address(log2Ceil(cacheDepth)-1,0)
  val forwarded = cacheValid(cacheIndex) && cacheTags(cacheIndex) === address &&
    RvvAge.older(cacheAges(cacheIndex),t.desc.age)
  // Also forward within a single instruction only for an identical row (rows
  // are issued once); equality never substitutes another instruction's value.
  val acc = Mux(forwarded,cacheData(cacheIndex),rawAccumulator(4))
  val values = (0 until p.dlen/32).map(e => (sums(e).asUInt+acc(32*e+31,32*e))(31,0))
  val result = Cat(values.reverse)
  val merged = VecInit((0 until p.rowBytes).map(b => Mux(t.enables(b),result(8*b+7,8*b),acc(8*b+7,8*b)))).asUInt
  for(k <- 0 until cacheDepth) {
    for(s <- snoop) {
      when(s.valid && cacheValid(k) && cacheTags(k) === s.bits.row && RvvAge.older(cacheAges(k),s.bits.age)) {
        cacheValid(k) := false.B
      }
    }
    when(valid(6) && cacheIndex === k.U) {
      cacheValid(k) := true.B; cacheTags(k) := address; cacheAges(k) := t.desc.age; cacheData(k) := merged
    }
  }
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
  io.readyEvent.valid := active && !zero && room && io.grant && !io.warBlocked && !io.wawBlocked && (row % p.rowsPerReg.U) === 0.U
  io.readyEvent.bits := io.readEvent.bits
  io.blocking := Cat(!room,!io.grant,io.warBlocked,io.wawBlocked)
}

/** Dots pipeline across instruction boundaries; other operations keep the
  * existing single-instruction sequencer and cannot cross a dot drain. */
class RvvIntegerSequencer(p: RvvParams,multiply: Boolean) extends Module {
  val io = IO(new RvvIntegerPorts(p))
  val snoop = IO(Input(Vec(2,Valid(new Bundle {
    val row = UInt(p.rowBits.W); val age = UInt(p.ageBits.W)
  }))))
  val legacy = Module(new RvvLegacyIntegerSequencer(p,multiply))
  if(!multiply) { io <> legacy.io }
  else {
    val dot = Module(new RvvDotPipeline(p)); dot.snoop := snoop
    val isDot = io.in.bits.decoded.op === RvvOp.dot.U || io.in.bits.decoded.op === RvvOp.dotsu.U
    legacy.io.in.valid := io.in.valid && !isDot && !dot.io.busy
    dot.io.in.valid := io.in.valid && isDot && !legacy.io.busy
    legacy.io.in.bits := io.in.bits; dot.io.in.bits := io.in.bits
    io.in.ready := Mux(isDot,dot.io.in.ready && !legacy.io.busy,legacy.io.in.ready && !dot.io.busy)
    for(x <- Seq(legacy.io,dot.io)) {
      x.readData := io.readData; x.grant := io.grant; x.mask := io.mask
      x.blocked := io.blocked; x.rawBlocked := io.rawBlocked; x.warBlocked := io.warBlocked; x.wawBlocked := io.wawBlocked
      x.write.ready := io.write.ready
    }
    val useDot = dot.io.busy
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
    io.readyEvent := Mux(useDot,dot.io.readyEvent,legacy.io.readyEvent)
    io.blocking := Mux(useDot,dot.io.blocking,legacy.io.blocking)
  }
}
