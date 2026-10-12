package flow.rvv.zve

import chisel3._
import chisel3.util._
import flow.fpu.BreezeFpOp

/** Registered VRF request/return -> tagged arithmetic batches -> byte/bit
  * merge -> physical write acknowledgements. No instruction finishes while
  * one of its reads, FPU tokens or accepted writes still owns a result. */
class ZveFpVectorEngine(p: ZveParams, lanes: Int) extends Module {
  require(lanes<=p.vrf.rowBytes/4)
  private val metaBits=math.max(32,p.vrf.lengthBits+lanes)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ZveCommand(p)))
    val vrf=new ZveVrfPort(p); val mask=Input(UInt(p.vrf.vlen.W))
    val fp=Vec(lanes,new ZveFpService)
    val complete=Decoupled(new ZveCompletion(p)); val drained=Output(Bool())
  })
  val active=RegInit(false.B); val d=Reg(new ZveCommand(p))
  val next=RegInit(0.U((p.vrf.lengthBits+1).W))
  val mask=Reg(UInt(p.vrf.vlen.W))
  val lastConsumed=RegInit(false.B); val flags=RegInit(0.U(5.W))
  val writes=RegInit(0.U(log2Ceil(p.fpBatches+8).W))
  val batch=Module(new ZveFpBatchPipeline(lanes,p.fpBatches,metaBits))
  io.fp <> batch.io.fp
  io.in.ready := !active
  when(io.in.fire) {
    active := true.B; d := io.in.bits; next := 0.U; mask := io.mask
    lastConsumed := io.in.bits.issue.scalar.vl === 0.U || io.in.bits.issue.scalar.vstart >= io.in.bits.issue.scalar.vl
    flags := 0.U
    assert(batch.io.drained && writes === 0.U,"FP instruction accepted with old result ownership")
  }
  val x=d.issue.scalar.instruction; val op=d.operation
  def is(names: Int*): Bool = names.map(n => op===n.U).reduce(_ || _)
  val mulAdd=is(ZveOp.vfmacc,ZveOp.vfnmacc,ZveOp.vfmsac,ZveOp.vfnmsac,
    ZveOp.vfmadd,ZveOp.vfnmadd,ZveOp.vfmsub,ZveOp.vfnmsub)
  val destMultiply=is(ZveOp.vfmadd,ZveOp.vfnmadd,ZveOp.vfmsub,ZveOp.vfnmsub)
  val negProduct=is(ZveOp.vfnmacc,ZveOp.vfnmsac,ZveOp.vfnmadd,ZveOp.vfnmsub)
  val negAddend=is(ZveOp.vfnmacc,ZveOp.vfmsac,ZveOp.vfnmadd,ZveOp.vfmsub)
  val convert=is(ZveOp.vfcvt,ZveOp.vfwcvt,ZveOp.vfncvt)
  val toFloat=convert && x(16)
  val source1=x(19,15); val source2=x(24,20); val destination=x(11,7)
  def row(base: UInt,element: UInt,width: UInt): UInt =
    (base*p.vrf.rowsPerReg.U+((element<<width)>>log2Ceil(p.vrf.rowBytes)))(p.vrf.rowBits-1,0)
  io.vrf.read.valid := active && !lastConsumed && next<d.issue.scalar.vl
  io.vrf.read.bits.tag := next; io.vrf.read.bits.age := d.age
  io.vrf.read.bits.rows(0) := row(source2,next,d.source2Width)
  io.vrf.read.bits.rows(1) := row(source1,next,d.source1Width)
  io.vrf.read.bits.rows(2) := Mux(d.maskResult,
    (destination*p.vrf.rowsPerReg.U+((next>>3)>>log2Ceil(p.vrf.rowBytes)))(p.vrf.rowBits-1,0),
    row(destination,next,d.destinationWidth))
  io.vrf.read.bits.enables := Cat(mulAdd || d.maskResult,d.vectorSource1,true.B)
  when(io.vrf.read.fire) { next := next+lanes.U }
  val start=io.vrf.returned.bits.tag(p.vrf.lengthBits-1,0)
  def extract(data: UInt,element: UInt,width: UInt): UInt = {
    val byte=(element<<width)(log2Ceil(p.vrf.rowBytes)-1,0)
    val shifted=(data>>(byte<<3))(31,0)
    MuxLookup(width,shifted)(Seq(0.U->shifted(7,0).pad(32),1.U->shifted(15,0).pad(32)))
  }
  def boxed(v: UInt): UInt = Cat("hffffffff".U(32.W),v)
  val scalar= Mux(d.issue.fpScalar(63,32).andR,d.issue.fpScalar(31,0),"h7fc00000".U)
  val oldMask=(io.vrf.returned.bits.data(2)>>(start(log2Ceil(p.vrf.dlen)-1,0)))(lanes-1,0)
  batch.io.in.valid := io.vrf.returned.valid
  io.vrf.returned.ready := batch.io.in.ready
  batch.io.in.bits := 0.U.asTypeOf(batch.io.in.bits)
  batch.io.in.bits.metadata := Cat(oldMask,start).pad(metaBits)
  batch.io.in.bits.active := VecInit((0 until lanes).map(l =>
    start+&l.U<d.issue.scalar.vl && start+&l.U>=d.issue.scalar.vstart &&
      (x(25) || (mask>>(start+l.U))(0)))).asUInt
  batch.io.in.bits.operation := Mux(mulAdd,Mux(negProduct,BreezeFpOp.FNMSUB.U,BreezeFpOp.FMADD.U),BreezeFpOp.ADD.U)
  batch.io.in.bits.modifier := Mux(mulAdd,negAddend,is(ZveOp.vfsub,ZveOp.vfrsub))
  batch.io.in.bits.rounding := d.issue.scalar.frm
  batch.io.in.bits.sourceFormat := 0.U; batch.io.in.bits.destinationFormat := 0.U
  batch.io.in.bits.integerFormat := Mux(toFloat,d.source2Width,d.destinationWidth)
  when(convert) {
    batch.io.in.bits.operation := Mux(toFloat,BreezeFpOp.I2F.U,BreezeFpOp.F2I.U)
    batch.io.in.bits.modifier := !x(15)
    when(x(17)) { batch.io.in.bits.rounding := 1.U } // explicit RTZ forms
  }
  when(is(ZveOp.vfmul)) { batch.io.in.bits.operation := BreezeFpOp.MUL.U; batch.io.in.bits.modifier := false.B }
  when(is(ZveOp.vfdiv,ZveOp.vfrdiv)) { batch.io.in.bits.operation := BreezeFpOp.DIV.U; batch.io.in.bits.modifier := false.B }
  when(is(ZveOp.vfsqrt)) { batch.io.in.bits.operation := BreezeFpOp.SQRT.U; batch.io.in.bits.modifier := false.B }
  when(is(ZveOp.vfmin,ZveOp.vfmax)) {
    batch.io.in.bits.operation := BreezeFpOp.MINMAX.U; batch.io.in.bits.modifier := false.B
    batch.io.in.bits.rounding := Mux(op===ZveOp.vfmax.U,1.U,0.U)
  }
  when(is(ZveOp.vfsgnj,ZveOp.vfsgnjn,ZveOp.vfsgnjx)) {
    batch.io.in.bits.operation := BreezeFpOp.SGNJ.U; batch.io.in.bits.modifier := false.B
    batch.io.in.bits.rounding := Mux(op===ZveOp.vfsgnjn.U,1.U,Mux(op===ZveOp.vfsgnjx.U,2.U,0.U))
  }
  when(d.maskResult) {
    batch.io.in.bits.operation := BreezeFpOp.CMP.U; batch.io.in.bits.modifier := false.B
    batch.io.in.bits.rounding := Mux(is(ZveOp.vmfeq,ZveOp.vmfne),2.U,
      Mux(is(ZveOp.vmflt,ZveOp.vmfgt),1.U,0.U))
  }
  when(op===ZveOp.vfclass.U) { batch.io.in.bits.operation := BreezeFpOp.CLASSIFY.U; batch.io.in.bits.modifier := false.B }
  for(l<-0 until lanes) {
    val element=start+l.U
    val a=extract(io.vrf.returned.bits.data(0),element,d.source2Width)
    val b=Mux(d.vectorSource1,extract(io.vrf.returned.bits.data(1),element,d.source1Width),scalar)
    val old=extract(io.vrf.returned.bits.data(2),element,d.destinationWidth)
    batch.io.in.bits.a(l) := boxed(Mux(destMultiply,b,a))
    batch.io.in.bits.b(l) := boxed(Mux(destMultiply,old,b))
    batch.io.in.bits.c(l) := boxed(Mux(destMultiply,a,old))
    when(is(ZveOp.vfadd,ZveOp.vfsub,ZveOp.vfrsub)) {
      batch.io.in.bits.a(l) := 0.U
      batch.io.in.bits.b(l) := boxed(Mux(op===ZveOp.vfrsub.U,b,a))
      batch.io.in.bits.c(l) := boxed(Mux(op===ZveOp.vfrsub.U,a,b))
    }
    when(convert) { batch.io.in.bits.a(l) := Mux(toFloat,a.pad(64),boxed(a)) }
    when(is(ZveOp.vfrdiv,ZveOp.vmfgt,ZveOp.vmfge)) {
      batch.io.in.bits.a(l) := boxed(b); batch.io.in.bits.b(l) := boxed(a)
    }
  }
  val out=batch.io.out.bits
  val outStart=out.metadata(p.vrf.lengthBits-1,0)
  val preserved=out.metadata(p.vrf.lengthBits+lanes-1,p.vrf.lengthBits)
  val offset=(outStart<<d.destinationWidth)(log2Ceil(p.vrf.rowBytes)-1,0)
  val regularData=(0 until lanes).map(l => {
    val v=MuxLookup(d.destinationWidth,out.data(l)(31,0))(Seq(
      0.U->out.data(l)(7,0).pad(32),1.U->out.data(l)(15,0).pad(32)))
    (v.pad(p.vrf.dlen)<<((offset+(l.U<<d.destinationWidth))<<3))(p.vrf.dlen-1,0)
  }).reduce(_ | _)
  val packed=VecInit((0 until lanes).map(l => Mux(out.active(l),
    out.data(l)(0) ^ (op===ZveOp.vmfne.U),preserved(l)))).asUInt
  val packedData=(packed.pad(p.vrf.dlen)<<outStart(log2Ceil(p.vrf.dlen)-1,0))(p.vrf.dlen-1,0)
  val enables=VecInit((0 until p.vrf.rowBytes).map(b => {
    val regular=(0 until lanes).map(l => out.active(l) && b.U>=offset+(l.U<<d.destinationWidth) &&
      b.U<offset+((l+1).U<<d.destinationWidth)).reduce(_ || _)
    val maskByte=(0 until lanes).map(l => out.active(l) && ((outStart+l.U)>>3) % p.vrf.rowBytes.U === b.U).reduce(_ || _)
    Mux(d.maskResult,maskByte,regular)
  })).asUInt
  io.vrf.write.valid := batch.io.out.valid && enables.orR
  io.vrf.write.bits.tag := outStart; io.vrf.write.bits.age := d.age
  io.vrf.write.bits.row := Mux(d.maskResult,
    (destination*p.vrf.rowsPerReg.U+((outStart>>3)>>log2Ceil(p.vrf.rowBytes)))(p.vrf.rowBits-1,0),
    row(destination,outStart,d.destinationWidth))
  io.vrf.write.bits.data := Mux(d.maskResult,packedData,regularData)
  io.vrf.write.bits.enables := enables
  batch.io.out.ready := !enables.orR || io.vrf.write.ready
  when(batch.io.out.fire) {
    flags := flags | out.flags
    when(outStart+&lanes.U>=d.issue.scalar.vl) { lastConsumed := true.B }
  }
  io.vrf.written.ready := true.B
  when(io.vrf.write.fire =/= io.vrf.written.fire) {
    writes := writes+io.vrf.write.fire.asUInt-io.vrf.written.fire.asUInt
  }
  when(io.vrf.written.fire) { assert(writes =/= 0.U,"unowned FP physical write acknowledgement") }
  io.complete.valid := active && lastConsumed && writes===0.U && batch.io.drained
  io.complete.bits := 0.U.asTypeOf(new ZveCompletion(p))
  io.complete.bits.slot := d.slot; io.complete.bits.age := d.age; io.complete.bits.flags := flags
  when(io.complete.fire) { active := false.B }
  io.drained := !active && batch.io.drained && writes===0.U
}
