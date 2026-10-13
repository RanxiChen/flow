package flow.rvv.zve

import chisel3._
import chisel3.util._

/** Full DLEN integer row pipeline. VRF returns, ALU results and bank writes
  * are decoupled by registered storage; the arithmetic carry never crosses an
  * element boundary and does not chain runtime SEW muxes across bytes. */
class ZveAluVectorEngine(p: ZveParams) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ZveCommand(p)))
    val vrf=new ZveVrfPort(p); val mask=Input(UInt(p.vrf.vlen.W))
    val complete=Decoupled(new ZveCompletion(p)); val drained=Output(Bool())
  })
  val active=RegInit(false.B); val d=Reg(new ZveCommand(p)); val mask=Reg(UInt(p.vrf.vlen.W))
  val sent=RegInit(0.U(16.W)); val consumed=RegInit(0.U(16.W)); val writes=RegInit(0.U(16.W)); val acks=RegInit(0.U(16.W))
  io.in.ready := !active
  when(io.in.fire) { active := true.B; d := io.in.bits; mask := io.mask
    sent := 0.U; consumed := 0.U; writes := 0.U; acks := 0.U }
  val x=d.issue.scalar.instruction
  val zero=d.issue.scalar.vstart>=d.issue.scalar.vl
  val bytes=d.issue.scalar.vl<<d.sew
  val rows=Mux(zero,0.U,(bytes+(p.vrf.rowBytes-1).U)>>log2Ceil(p.vrf.rowBytes))
  io.vrf.read.valid := active && sent<rows
  io.vrf.read.bits.tag := sent; io.vrf.read.bits.age := d.age
  io.vrf.read.bits.rows(0) := (x(24,20)*p.vrf.rowsPerReg.U+sent)(p.vrf.rowBits-1,0)
  io.vrf.read.bits.rows(1) := (x(19,15)*p.vrf.rowsPerReg.U+sent)(p.vrf.rowBits-1,0)
  io.vrf.read.bits.rows(2) := 0.U
  io.vrf.read.bits.enables := Cat(false.B,d.vectorSource1,d.vectorSource2)
  when(io.vrf.read.fire) { sent := sent+1.U }
  val q=Module(new Queue(new ZveWriteRequest(p),2,pipe=false,flow=false))
  val r=io.vrf.returned.bits
  val scalar=VecInit((0 until 4).map(sew => Fill(p.vrf.dlen/(8<<sew),d.issue.scalar.rs1((8<<sew)-1,0))))(d.sew)
  val a=Mux(d.operation===ZveOp.vmv.U,r.data(1),r.data(0))
  val b=Mux(d.vectorSource1,r.data(1),scalar)
  val subtract=d.operation===ZveOp.vsub.U
  val byteBase=(0 until p.vrf.rowBytes).map(j =>
    a(8*j+7,8*j)+&Mux(subtract,~b(8*j+7,8*j),b(8*j+7,8*j)))
  val sums=(0 until p.vrf.rowBytes).map { j =>
    val carries=(0 until 4).map { sew =>
      val first=j-j%(1<<sew)
      def propagate(from: Int): Bool=(from until j).map(k=>byteBase(k)(7,0).andR).foldLeft(true.B)(_ && _)
      val terms=(first until j).map(k=>byteBase(k)(8) && propagate(k+1))
      terms.foldLeft(subtract && propagate(first))(_ || _)
    }
    (byteBase(j)(7,0)+VecInit(carries)(d.sew).asUInt)(7,0)
  }
  // Shifts take one scalar amount (.vx/.vi); .vv shifts stay in the element engine.
  def shifts(f: (UInt,UInt,Int) => UInt): UInt = VecInit((0 until 3).map { sew =>
    val w=8<<sew
    Cat((0 until p.vrf.dlen/w).reverse.map(e => f(a((e+1)*w-1,e*w),d.issue.scalar.rs1(log2Ceil(w)-1,0),w)))
  })(d.sew)
  val moved=Mux(d.vectorSource1,a,scalar)
  val result=MuxLookup(d.operation,moved)(Seq(ZveOp.vadd.U->Cat(sums.reverse),
    ZveOp.vsub.U->Cat(sums.reverse),ZveOp.vand.U->(a & b),ZveOp.vor.U->(a | b),ZveOp.vxor.U->(a ^ b),
    ZveOp.vsrl.U->shifts((v,n,_) => v>>n),
    ZveOp.vsll.U->shifts((v,n,w) => (v<<n)(w-1,0)),
    ZveOp.vsra.U->shifts((v,n,_) => (v.asSInt>>n).asUInt)))
  q.io.enq.valid := io.vrf.returned.valid
  io.vrf.returned.ready := q.io.enq.ready
  q.io.enq.bits.tag := r.tag; q.io.enq.bits.age := d.age
  q.io.enq.bits.row := (x(11,7)*p.vrf.rowsPerReg.U+r.tag)(p.vrf.rowBits-1,0)
  q.io.enq.bits.data := result
  q.io.enq.bits.enables := VecInit((0 until p.vrf.rowBytes).map(b => {
    val e=(r.tag*p.vrf.rowBytes.U+b.U)>>d.sew
    e<d.issue.scalar.vl && e>=d.issue.scalar.vstart && (x(25) || (mask>>e)(0))
  })).asUInt
  io.vrf.write.valid := q.io.deq.valid && q.io.deq.bits.enables.orR
  io.vrf.write.bits := q.io.deq.bits
  q.io.deq.ready := !q.io.deq.bits.enables.orR || io.vrf.write.ready
  when(q.io.deq.fire) { consumed := consumed+1.U }
  when(io.vrf.write.fire) { writes := writes+1.U }
  io.vrf.written.ready := true.B
  when(io.vrf.written.fire) { acks := acks+1.U; assert(acks<writes,"ALU ack preceded its physical write") }
  io.complete.valid := active && consumed===rows && acks===writes && !q.io.deq.valid
  io.complete.bits := 0.U.asTypeOf(new ZveCompletion(p))
  io.complete.bits.slot := d.slot; io.complete.bits.age := d.age
  when(io.complete.fire) { active := false.B }
  io.drained := !active && !q.io.deq.valid
}
