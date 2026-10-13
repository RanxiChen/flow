package flow.rvv.zve

import chisel3._
import chisel3.util._

class ZveMacOperands(p: ZveParams) extends Bundle {
  val tag=UInt(16.W); val a=Vec(p.vrf.rowBytes/2,SInt(17.W)); val b=Vec(p.vrf.rowBytes/2,SInt(17.W))
  val old=UInt(p.vrf.dlen.W); val enables=UInt(p.vrf.rowBytes.W)
}
class ZveMacProducts(p: ZveParams) extends Bundle {
  val tag=UInt(16.W); val product=Vec(p.vrf.rowBytes/2,UInt(32.W))
  val old=UInt(p.vrf.dlen.W); val enables=UInt(p.vrf.rowBytes.W)
}

/** Full-row widening integer multiply / multiply-accumulate: vwmul{,u,su},
  * vwmacc{,u,su,us}, .vv and .vx, SEW 8 -> 16 and 16 -> 32 (Zve32 has no
  * wider form). This is the llama Q4_0/Q8_0 inner product; the element
  * engine would take several cycles per element.
  *
  * Destination row t takes half t(0) of source row t>>1, so one VRF triplet
  * (vs2 row, vs1 row, old vd row) yields one destination row. Rows are read
  * in increasing order and each read precedes the write of its own row,
  * which keeps the legal widening overlap (sources in the upper half of vd).
  *
  * Pipeline: VRF return -> S1 operand registers -> S2 product registers
  * (one 17x17 DSP per lane) -> add old vd -> write queue. Stages never stall:
  * a return is accepted only when the queue has room for everything in
  * flight. */
class ZveMacVectorEngine(p: ZveParams) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ZveCommand(p)))
    val vrf=new ZveVrfPort(p); val mask=Input(UInt(p.vrf.vlen.W))
    val complete=Decoupled(new ZveCompletion(p)); val drained=Output(Bool())
  })
  private val R=p.vrf.rowBytes; private val lanes=R/2; private val half=p.vrf.dlen/2
  val active=RegInit(false.B); val d=Reg(new ZveCommand(p)); val mask=Reg(UInt(p.vrf.vlen.W))
  val sent=RegInit(0.U(16.W)); val consumed=RegInit(0.U(16.W)); val writes=RegInit(0.U(16.W)); val acks=RegInit(0.U(16.W))
  io.in.ready := !active
  when(io.in.fire) { active := true.B; d := io.in.bits; mask := io.mask
    sent := 0.U; consumed := 0.U; writes := 0.U; acks := 0.U
    assert(io.in.bits.sew<=1.U && io.in.bits.destinationWidth===io.in.bits.sew+1.U,"MAC engine is widening SEW 8/16 only") }
  val x=d.issue.scalar.instruction; val op=d.operation
  def is(names: Int*): Bool = names.map(n=>op===n.U).reduce(_ || _)
  val accumulate=is(ZveOp.vwmacc,ZveOp.vwmaccu,ZveOp.vwmaccsu,ZveOp.vwmaccus)
  val vs2Unsigned=is(ZveOp.vwmulu,ZveOp.vwmaccu,ZveOp.vwmaccsu)
  val vs1Unsigned=is(ZveOp.vwmulu,ZveOp.vwmulsu,ZveOp.vwmaccu,ZveOp.vwmaccus)
  val wide=d.sew===1.U
  val zero=d.issue.scalar.vstart>=d.issue.scalar.vl
  val bytes=d.issue.scalar.vl<<(d.sew+&1.U)
  val rows=Mux(zero,0.U,(bytes+(R-1).U)>>log2Ceil(R))
  io.vrf.read.valid := active && sent<rows
  io.vrf.read.bits.tag := sent; io.vrf.read.bits.age := d.age
  io.vrf.read.bits.rows(0) := (x(24,20)*p.vrf.rowsPerReg.U+(sent>>1))(p.vrf.rowBits-1,0)
  io.vrf.read.bits.rows(1) := (x(19,15)*p.vrf.rowsPerReg.U+(sent>>1))(p.vrf.rowBits-1,0)
  io.vrf.read.bits.rows(2) := (x(11,7)*p.vrf.rowsPerReg.U+sent)(p.vrf.rowBits-1,0)
  io.vrf.read.bits.enables := Cat(accumulate,d.vectorSource1,true.B)
  when(io.vrf.read.fire) { sent := sent+1.U }

  val q=Module(new Queue(new ZveWriteRequest(p),4,pipe=false,flow=false))
  val s1Valid=RegInit(false.B); val s1=Reg(new ZveMacOperands(p))
  val s2Valid=RegInit(false.B); val s2=Reg(new ZveMacProducts(p))
  val r=io.vrf.returned.bits
  io.vrf.returned.ready := q.io.count+&s1Valid.asUInt+&s2Valid.asUInt<q.entries.U
  def lanesOf(row: UInt,unsigned: Bool): Vec[SInt] = VecInit((0 until lanes).map { l =>
    val e8=row(8*l+7,8*l); val e16=if(l<lanes/2) row(16*l+15,16*l) else 0.U(16.W)
    Mux(wide,Mux(unsigned,e16.zext,e16.asSInt.pad(17)),Mux(unsigned,e8.zext,e8.asSInt.pad(17)).pad(17))
  })
  val sourceHalf=r.tag(0)
  val a=(r.data(0)>>(sourceHalf<<log2Ceil(half)))(half-1,0)
  val scalar=Mux(wide,Fill(half/16,d.issue.scalar.rs1(15,0)),Fill(half/8,d.issue.scalar.rs1(7,0)))
  val b=Mux(d.vectorSource1,(r.data(1)>>(sourceHalf<<log2Ceil(half)))(half-1,0),scalar)
  s1Valid := io.vrf.returned.fire
  when(io.vrf.returned.fire) {
    s1.tag := r.tag; s1.a := lanesOf(a,vs2Unsigned); s1.b := lanesOf(b,vs1Unsigned)
    s1.old := Mux(accumulate,r.data(2),0.U)
    s1.enables := VecInit((0 until R).map(j => {
      val e=(r.tag*R.U+j.U)>>(d.sew+&1.U)
      e<d.issue.scalar.vl && e>=d.issue.scalar.vstart && (x(25) || (mask>>e)(0))
    })).asUInt
  }
  s2Valid := s1Valid
  when(s1Valid) {
    s2.tag := s1.tag; s2.old := s1.old; s2.enables := s1.enables
    for(l<-0 until lanes) { s2.product(l) := (s1.a(l)*s1.b(l))(31,0) }
  }
  val sum8=Cat((0 until lanes).reverse.map(l => s2.old(16*l+15,16*l)+s2.product(l)(15,0)))
  val sum16=Cat((0 until lanes/2).reverse.map(l => s2.old(32*l+31,32*l)+s2.product(l)))
  q.io.enq.valid := s2Valid
  q.io.enq.bits.tag := s2.tag; q.io.enq.bits.age := d.age
  q.io.enq.bits.row := (x(11,7)*p.vrf.rowsPerReg.U+s2.tag)(p.vrf.rowBits-1,0)
  q.io.enq.bits.data := Mux(wide,sum16,sum8)
  q.io.enq.bits.enables := s2.enables
  when(s2Valid) { assert(q.io.enq.ready,"MAC write queue exceeded its reservation") }
  io.vrf.write.valid := q.io.deq.valid && q.io.deq.bits.enables.orR
  io.vrf.write.bits := q.io.deq.bits
  q.io.deq.ready := !q.io.deq.bits.enables.orR || io.vrf.write.ready
  when(q.io.deq.fire) { consumed := consumed+1.U }
  when(io.vrf.write.fire) { writes := writes+1.U }
  io.vrf.written.ready := true.B
  when(io.vrf.written.fire) { acks := acks+1.U; assert(acks<writes,"MAC ack preceded its physical write") }
  io.complete.valid := active && consumed===rows && acks===writes && !q.io.deq.valid && !s1Valid && !s2Valid
  io.complete.bits := 0.U.asTypeOf(new ZveCompletion(p))
  io.complete.bits.slot := d.slot; io.complete.bits.age := d.age
  when(io.complete.fire) { active := false.B }
  io.drained := !active && !q.io.deq.valid && !s1Valid && !s2Valid
}
