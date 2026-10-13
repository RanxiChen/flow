package flow.rvv.zve

import chisel3._
import chisel3.util._

/** One reusable integer element datapath. Product is supplied by a registered
  * 33x33 signed multiplier; division is a separate iterative service. */
object ZveIntegerArithmetic {
  def apply(d: ZveCommand,a: UInt,b: UInt,old: UInt,product: UInt,carry: Bool): (UInt,Bool) = {
    val op=d.operation
    val results=(0 until 3).map { sew =>
      val w=8<<sew
      val ua=a(w-1,0); val ub=b(w-1,0); val sa=ua.asSInt.pad(64); val sb=ub.asSInt.pad(64)
      val sourceWide=d.source2Width>d.sew
      val wa=if(w<32) Mux(sourceWide,a(2*w-1,0).pad(64),ua.pad(64)) else ua.pad(64)
      val swa=if(w<32) Mux(sourceWide,a(2*w-1,0).asSInt.pad(64),sa) else sa
      val shifted=b(log2Ceil(w)-1,0)
      val wideShift=b(log2Ceil(w),0)
      val maxSigned=((BigInt(1)<<(w-1))-1).S(64.W); val minSigned=(-(BigInt(1)<<(w-1))).S(64.W)
      val maxUnsigned=((BigInt(1)<<w)-1).U(64.W)
      val result=WireDefault(ua.pad(64)); val saturated=WireDefault(false.B)
      def rounded(v: UInt,shift: UInt,signed: Boolean): UInt = {
        val base=if(signed) (v.asSInt>>shift).asUInt else v>>shift
        val discarded=v & ((1.U(65.W)<<shift)-1.U)
        val guard=(v>>(shift-1.U))(0)
        val sticky=(v & ((1.U(65.W)<<(shift-1.U))-1.U)).orR
        val inc=shift=/=0.U && MuxLookup(d.issue.scalar.vxrm,false.B)(Seq(
          0.U->guard,1.U->(guard && (sticky || base(0))),3.U->(!base(0) && discarded.orR)))
        base+inc.asUInt
      }
      val signedSum=sa+sb; val signedDifference=sa-sb
      val unsignedSum=ua+&ub; val unsignedDifference=ua.pad(64)-ub
      val addCarry=ua+&ub+carry.asUInt
      val borrow=ua.pad(64)<ub.pad(64)+carry.asUInt
      switch(op) {
        is(ZveOp.vadd.U) { result := ua+ub }
        is(ZveOp.vsub.U) { result := ua-ub }
        is(ZveOp.vrsub.U) { result := ub-ua }
        is(ZveOp.vand.U) { result := ua & ub }
        is(ZveOp.vor.U) { result := ua | ub }
        is(ZveOp.vxor.U) { result := ua ^ ub }
        is(ZveOp.vsll.U) { result := ua<<shifted }
        is(ZveOp.vsrl.U) { result := ua>>shifted }
        is(ZveOp.vsra.U) { result := (ua.asSInt>>shifted).asUInt }
        is(ZveOp.vminu.U) { result := Mux(ua<ub,ua,ub) }
        is(ZveOp.vmaxu.U) { result := Mux(ua>ub,ua,ub) }
        is(ZveOp.vmin.U) { result := Mux(sa<sb,ua,ub) }
        is(ZveOp.vmax.U) { result := Mux(sa>sb,ua,ub) }
        is(ZveOp.vmseq.U) { result := ua===ub }
        is(ZveOp.vmsne.U) { result := ua=/=ub }
        is(ZveOp.vmsltu.U) { result := ua<ub }
        is(ZveOp.vmslt.U) { result := sa<sb }
        is(ZveOp.vmsleu.U) { result := ua<=ub }
        is(ZveOp.vmsle.U) { result := sa<=sb }
        is(ZveOp.vmsgtu.U) { result := ua>ub }
        is(ZveOp.vmsgt.U) { result := sa>sb }
        is(ZveOp.vadc.U) { result := addCarry }
        is(ZveOp.vsbc.U) { result := ua-ub-carry.asUInt }
        is(ZveOp.vmadc.U) { result := addCarry(w) }
        is(ZveOp.vmsbc.U) { result := borrow }
        is(ZveOp.vmerge.U) { result := Mux(carry,ub,ua) }
        is(ZveOp.vmv.U) { result := ub }
        is(ZveOp.vmul.U,ZveOp.vwmul.U,ZveOp.vwmulu.U,ZveOp.vwmulsu.U) { result := product }
        is(ZveOp.vmulh.U,ZveOp.vmulhu.U,ZveOp.vmulhsu.U) { result := product>>w }
        is(ZveOp.vmacc.U,ZveOp.vwmacc.U,ZveOp.vwmaccu.U,ZveOp.vwmaccsu.U,ZveOp.vwmaccus.U) { result := old+product }
        is(ZveOp.vnmsac.U) { result := old-product }
        is(ZveOp.vmadd.U) { result := product+ua }
        is(ZveOp.vnmsub.U) { result := ua-product }
        is(ZveOp.vwaddu.U) { result := wa+ub }
        is(ZveOp.vwsubu.U) { result := wa-ub }
        is(ZveOp.vwadd.U) { result := (swa+sb).asUInt }
        is(ZveOp.vwsub.U) { result := (swa-sb).asUInt }
        is(ZveOp.vzext.U) { result := a }
        is(ZveOp.vsext.U) {
          result := MuxLookup(d.source2Width,a(31,0).asSInt.pad(64).asUInt)(Seq(
            0.U->a(7,0).asSInt.pad(64).asUInt,1.U->a(15,0).asSInt.pad(64).asUInt))
        }
        is(ZveOp.vsaddu.U) { saturated := unsignedSum>maxUnsigned; result := Mux(saturated,maxUnsigned,unsignedSum) }
        is(ZveOp.vsadd.U) { saturated := signedSum>maxSigned || signedSum<minSigned
          result := Mux(signedSum>maxSigned,maxSigned,Mux(signedSum<minSigned,minSigned,signedSum)).asUInt }
        is(ZveOp.vssubu.U) { saturated := ua<ub; result := Mux(saturated,0.U,ua-ub) }
        is(ZveOp.vssub.U) { saturated := signedDifference>maxSigned || signedDifference<minSigned
          result := Mux(signedDifference>maxSigned,maxSigned,Mux(signedDifference<minSigned,minSigned,signedDifference)).asUInt }
        is(ZveOp.vaaddu.U) { result := rounded(unsignedSum.pad(64),1.U,false) }
        is(ZveOp.vaadd.U) { result := rounded(signedSum.asUInt,1.U,true) }
        is(ZveOp.vasubu.U) { result := rounded(unsignedDifference,1.U,false) }
        is(ZveOp.vasub.U) { result := rounded(signedDifference.asUInt,1.U,true) }
        is(ZveOp.vssrl.U) { result := rounded(ua.pad(64),shifted,false) }
        is(ZveOp.vssra.U) { result := rounded(sa.asUInt,shifted,true) }
        is(ZveOp.vnsrl.U) { result := wa>>wideShift }
        is(ZveOp.vnsra.U) { result := (swa>>wideShift).asUInt }
        is(ZveOp.vnclipu.U) { val v=rounded(wa,wideShift,false)
          saturated := v>maxUnsigned; result := Mux(saturated,maxUnsigned,v) }
        is(ZveOp.vnclip.U) { val v=rounded(swa.asUInt,wideShift,true).asSInt
          saturated := v>maxSigned || v<minSigned; result := Mux(v>maxSigned,maxSigned,Mux(v<minSigned,minSigned,v)).asUInt }
        is(ZveOp.vsmul.U) { val v=rounded(product,(w-1).U,true).asSInt
          saturated := v>maxSigned || v<minSigned; result := Mux(v>maxSigned,maxSigned,Mux(v<minSigned,minSigned,v)).asUInt }
      }
      (result,saturated)
    }
    (VecInit(results.map(_._1))(d.sew),VecInit(results.map(_._2))(d.sew))
  }

  def multiplyOperands(d: ZveCommand,a: UInt,b: UInt,old: UInt): (SInt,SInt) = {
    val op=d.operation
    def is(names: Int*): Bool = names.map(n=>op===n.U).reduce(_ || _)
    val unsignedA=is(ZveOp.vmulhu,ZveOp.vwmulu,ZveOp.vwmaccu,ZveOp.vwmaccus)
    val unsignedB=is(ZveOp.vmulhu,ZveOp.vmulhsu,ZveOp.vwmulu,ZveOp.vwmulsu,ZveOp.vwmaccu,ZveOp.vwmaccsu)
    val useOld=is(ZveOp.vmadd,ZveOp.vnmsub)
    val aa=Mux(useOld,old,a)
    val pairs=(0 until 3).map { sew =>
      val w=8<<sew
      (Mux(unsignedA,aa(w-1,0).zext,aa(w-1,0).asSInt).pad(33),
       Mux(unsignedB,b(w-1,0).zext,b(w-1,0).asSInt).pad(33))
    }
    (VecInit(pairs.map(_._1))(d.sew),VecInit(pairs.map(_._2))(d.sew))
  }
}

class ZveDivideRequest extends Bundle {
  val a=UInt(32.W); val b=UInt(32.W); val signed=Bool(); val sew=UInt(2.W); val remainder=Bool()
}
class ZveDivider extends Module {
  val io=IO(new Bundle { val in=Flipped(Decoupled(new ZveDivideRequest)); val out=Decoupled(UInt(32.W)) })
  val busy=RegInit(false.B); val valid=RegInit(false.B)
  val quotient=Reg(UInt(32.W)); val rem=Reg(UInt(33.W)); val divisor=Reg(UInt(32.W))
  val count=Reg(UInt(6.W)); val negateQ=Reg(Bool()); val negateR=Reg(Bool()); val wantR=Reg(Bool())
  val result=Reg(UInt(32.W))
  io.in.ready := !busy && !valid; io.out.valid := valid; io.out.bits := result
  when(io.out.fire) { valid := false.B }
  val signsA=VecInit(Seq(io.in.bits.a(7),io.in.bits.a(15),io.in.bits.a(31)))
  val signsB=VecInit(Seq(io.in.bits.b(7),io.in.bits.b(15),io.in.bits.b(31)))
  val negA=io.in.bits.signed && signsA(io.in.bits.sew)
  val negB=io.in.bits.signed && signsB(io.in.bits.sew)
  val mask=MuxLookup(io.in.bits.sew,"hffffffff".U)(Seq(0.U->"hff".U,1.U->"hffff".U))
  when(io.in.fire) {
    val a=io.in.bits.a & mask; val b=io.in.bits.b & mask
    when(b===0.U) { result := Mux(io.in.bits.remainder,a,mask); valid := true.B }
      .otherwise {
        quotient := Mux(negA,(-a)&mask,a); divisor := Mux(negB,(-b)&mask,b)
        rem := 0.U; count := 0.U; busy := true.B
        negateQ := negA ^ negB; negateR := negA; wantR := io.in.bits.remainder
      }
  }
  when(busy) {
    val trial=Cat(rem(31,0),quotient(31))
    val ge=trial>=divisor
    val r=Mux(ge,trial-divisor,trial)
    val q=Cat(quotient(30,0),ge)
    quotient := q; rem := r; count := count+1.U
    when(count===31.U) {
      result := Mux(wantR,Mux(negateR,-r(31,0),r(31,0)),Mux(negateQ,-q,q))
      busy := false.B; valid := true.B
    }
  }
}
