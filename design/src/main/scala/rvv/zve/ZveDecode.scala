package flow.rvv.zve

import chisel3._
import chisel3.util._

/** Encoding recognition is separate from operand/configuration legality.
  * Each operand owns an EEW/EMUL description; a fractional group still owns
  * one architectural register, but tail preservation uses its actual bytes. */
object ZveDecode {
  def apply(in: ZveIssue, p: ZveParams): ZveCommand = {
    val d = WireDefault(0.U.asTypeOf(new ZveCommand(p)))
    d.issue := in
    val x=in.scalar.instruction; val vt=in.scalar.vtype
    val f3=x(14,12); val vs1=x(19,15); val vs2=x(24,20); val vd=x(11,7)
    val hits=ZveEncodings.forms.map { case(_,_,pat) => pat === x }
    d.operation := Mux1H(hits.zip(ZveEncodings.forms.map(_._2.U(9.W))))
    val op=d.operation
    def is(names: Int*): Bool = names.map(n => op === n.U).reduce(_ || _)
    val memory = x(6,0) === 7.U || x(6,0) === 0x27.U
    val fp = !memory && (f3 === 1.U || f3 === 5.U)
    val scalarOperand = f3 === 3.U || f3 === 4.U || f3 === 5.U || f3 === 6.U
    when(f3 === 3.U) { d.issue.scalar.rs1 := vs1.asSInt.pad(64).asUInt }
    d.sew := vt(4,3)
    val sew=vt(5,3)
    val lmul=Mux(vt(2),vt(2,0).asSInt,vt(1,0).zext).pad(6)
    val memoryWidth=Mux(f3 === 0.U,0.U,f3-4.U)(1,0)
    val wholeMemory=memory && x(27,26) === 0.U && x(24,20) === 8.U
    val wholeMove=is(ZveOp.vmv1r,ZveOp.vmv2r,ZveOp.vmv4r,ZveOp.vmv8r)
    d.whole := wholeMemory || wholeMove
    val maskMemory=memory && x(27,26) === 0.U && x(24,20) === 11.U
    d.maskSources := is(ZveOp.vmand,ZveOp.vmandn,ZveOp.vmor,ZveOp.vmorn,ZveOp.vmxor,
      ZveOp.vmnand,ZveOp.vmnor,ZveOp.vmxnor,ZveOp.vcpop,ZveOp.vfirst,ZveOp.vmsbf,
      ZveOp.vmsif,ZveOp.vmsof,ZveOp.viota)
    d.maskResult := is(ZveOp.vmseq,ZveOp.vmsne,ZveOp.vmslt,ZveOp.vmsltu,ZveOp.vmsle,
      ZveOp.vmsleu,ZveOp.vmsgt,ZveOp.vmsgtu,ZveOp.vmadc,ZveOp.vmsbc,ZveOp.vmfeq,
      ZveOp.vmfne,ZveOp.vmflt,ZveOp.vmfle,ZveOp.vmfgt,ZveOp.vmfge,ZveOp.vmand,
      ZveOp.vmandn,ZveOp.vmor,ZveOp.vmorn,ZveOp.vmxor,ZveOp.vmnand,ZveOp.vmnor,
      ZveOp.vmxnor,ZveOp.vmsbf,ZveOp.vmsif,ZveOp.vmsof)
    d.reduction := is(ZveOp.vredsum,ZveOp.vredand,ZveOp.vredor,ZveOp.vredxor,ZveOp.vredmin,
      ZveOp.vredminu,ZveOp.vredmax,ZveOp.vredmaxu,ZveOp.vwredsum,ZveOp.vwredsumu,
      ZveOp.vfredosum,ZveOp.vfredusum,ZveOp.vfredmin,ZveOp.vfredmax)
    val carry=is(ZveOp.vadc,ZveOp.vsbc,ZveOp.vmadc,ZveOp.vmsbc)
    d.noPredication := carry || is(ZveOp.vmerge,ZveOp.vfmerge,ZveOp.vcompress) || d.whole
    val scalarMove=is(ZveOp.vmv,ZveOp.vfmv) && f3 === 2.U || (op === ZveOp.vfmv.U && f3 === 1.U)
    d.scalarResult := scalarMove || is(ZveOp.vcpop,ZveOp.vfirst)
    d.scalarFloating := op === ZveOp.vfmv.U && f3 === 1.U
    val toVectorScalar=is(ZveOp.vmv,ZveOp.vfmv) && x(31,26) === 16.U && !d.scalarResult
    val broadcast=is(ZveOp.vmv,ZveOp.vfmv) && x(31,26) === 23.U
    d.vectorSource1 := !memory && !scalarOperand && !d.scalarResult &&
      !is(ZveOp.vfcvt,ZveOp.vfwcvt,ZveOp.vfncvt,ZveOp.vfsqrt,ZveOp.vfrec7,ZveOp.vfrsqrt7,
      ZveOp.vfclass,ZveOp.vsext,ZveOp.vzext,ZveOp.vid,ZveOp.vmsbf,ZveOp.vmsif,ZveOp.vmsof,
      ZveOp.viota,ZveOp.vcpop,ZveOp.vfirst) && !wholeMove
    d.vectorSource2 := !toVectorScalar && !(broadcast && scalarOperand) && !is(ZveOp.vid)
    when(broadcast && !scalarOperand) { d.vectorSource2 := false.B }
    when(memory) {
      d.vectorSource1 := false.B
      d.vectorSource2 := x(27,26)(0)
    }
    val widen=is(ZveOp.vwadd,ZveOp.vwaddu,ZveOp.vwsub,ZveOp.vwsubu,ZveOp.vwmul,
      ZveOp.vwmulu,ZveOp.vwmulsu,ZveOp.vwmacc,ZveOp.vwmaccu,ZveOp.vwmaccsu,ZveOp.vwmaccus,
      ZveOp.vwredsum,ZveOp.vwredsumu,ZveOp.vfwcvt)
    val narrow=is(ZveOp.vnsrl,ZveOp.vnsra,ZveOp.vnclip,ZveOp.vnclipu,ZveOp.vfncvt)
    val wideSource=widen && x(31,26)(2) && is(ZveOp.vwadd,ZveOp.vwaddu,ZveOp.vwsub,ZveOp.vwsubu)
    val extension=is(ZveOp.vsext,ZveOp.vzext)
    val extendBy=Mux(vs1(2,1) === 1.U,3.U,Mux(vs1(2,1) === 2.U,2.U,1.U))
    d.source1Width := Mux(op === ZveOp.vrgatherei16.U,1.U,d.sew)
    d.source2Width := Mux(extension,d.sew-extendBy,Mux(narrow || wideSource,d.sew+1.U,d.sew))
    d.destinationWidth := Mux(widen,d.sew+1.U,d.sew)
    when(d.reduction) { d.source1Width := d.destinationWidth }
    when(memory) {
      d.source2Width := memoryWidth
      d.destinationWidth := Mux(x(27,26)(0),d.sew,memoryWidth)
    }
    def bytes(exponent: SInt): UInt = Mux(exponent < 0.S,
      p.vrf.regBytes.U >> (-exponent).asUInt, p.vrf.regBytes.U << exponent.asUInt)(p.vrf.lengthBits-1,0)
    def regs(exponent: SInt): UInt = Mux(exponent <= 0.S,1.U,1.U(4.W) << exponent.asUInt)(3,0)
    val e1=lmul+d.source1Width.zext-sew.zext
    val e2=lmul+d.source2Width.zext-sew.zext
    val ed=lmul+d.destinationWidth.zext-sew.zext
    d.source1Registers := regs(e1); d.source2Registers := regs(e2); d.destinationRegisters := regs(ed)
    d.source1Bytes := bytes(e1); d.source2Bytes := bytes(e2); d.destinationBytes := bytes(ed)
    when(d.reduction) { d.source1Registers := 1.U; d.destinationRegisters := 1.U
      d.source1Bytes := p.vrf.regBytes.U; d.destinationBytes := p.vrf.regBytes.U }
    when(d.maskSources || d.scalarResult) { d.source2Registers := 1.U; d.source2Bytes := p.vrf.regBytes.U }
    when(d.maskSources) { d.source1Registers := 1.U; d.source1Bytes := p.vrf.regBytes.U }
    when(op===ZveOp.vcompress.U) { d.source1Registers := 1.U; d.source1Bytes := p.vrf.regBytes.U }
    when(toVectorScalar) { d.destinationRegisters := 1.U; d.destinationBytes := p.vrf.regBytes.U }
    when(d.maskResult || maskMemory) { d.destinationRegisters := 1.U; d.destinationBytes := p.vrf.regBytes.U }
    when(d.whole) {
      val count=Mux(wholeMove,vs1+1.U,x(31,29)+1.U)
      d.source2Registers := count; d.destinationRegisters := count
      d.source2Bytes := count*p.vrf.regBytes.U; d.destinationBytes := count*p.vrf.regBytes.U
    }
    def group(base: UInt,count: UInt): UInt = VecInit((0 until 32).map(r => r.U >= base && r.U < base+&count)).asUInt
    val src1=group(vs1,d.source1Registers); val src2=group(vs2,d.source2Registers)
    val dst=group(vd,d.destinationRegisters)
    val oldDestination = is(ZveOp.vmacc,ZveOp.vnmsac,ZveOp.vmadd,ZveOp.vnmsub,ZveOp.vwmacc,
      ZveOp.vwmaccu,ZveOp.vwmaccsu,ZveOp.vwmaccus,ZveOp.vfmacc,ZveOp.vfnmacc,ZveOp.vfmsac,
      ZveOp.vfnmsac,ZveOp.vfmadd,ZveOp.vfnmadd,ZveOp.vfmsub,ZveOp.vfnmsub) || !d.scalarResult
    d.reads := Mux(d.vectorSource1,src1,0.U) | Mux(d.vectorSource2,src2,0.U) |
      Mux(oldDestination,dst,0.U) | Mux(!x(25) || op === ZveOp.vcompress.U,1.U,0.U)
    d.writes := Mux(d.scalarResult,0.U,dst)
    when(memory) { d.reads := Mux(x(6,0) === 0x27.U,dst,0.U) |
      Mux(d.vectorSource2,src2,0.U) | Mux(!x(25),1.U,0.U)
      d.writes := Mux(x(6,0) === 0x27.U,0.U,dst) }
    val fast=is(ZveOp.vadd,ZveOp.vsub,ZveOp.vand,ZveOp.vsrl) || (op === ZveOp.vmv.U && broadcast)
    val cross=d.reduction || d.maskSources || d.scalarResult || toVectorScalar || d.whole ||
      is(ZveOp.vslideup,ZveOp.vslidedown,ZveOp.vslide1up,ZveOp.vslide1down,ZveOp.vfslide1up,
      ZveOp.vfslide1down,ZveOp.vrgather,ZveOp.vrgatherei16,ZveOp.vcompress,ZveOp.vid,ZveOp.viota)
    val fma=is(ZveOp.vfadd,ZveOp.vfsub,ZveOp.vfrsub,ZveOp.vfmacc,ZveOp.vfnmacc,ZveOp.vfmsac,
      ZveOp.vfnmsac,ZveOp.vfmadd,ZveOp.vfnmadd,ZveOp.vfmsub,ZveOp.vfnmsub)
    val conversion=is(ZveOp.vfcvt,ZveOp.vfwcvt,ZveOp.vfncvt,ZveOp.vfmin,ZveOp.vfmax,
      ZveOp.vfsgnj,ZveOp.vfsgnjn,ZveOp.vfsgnjx,ZveOp.vfclass,ZveOp.vmfeq,ZveOp.vmfne,
      ZveOp.vmflt,ZveOp.vmfle,ZveOp.vmfgt,ZveOp.vmfge)
    d.unit := Mux(memory,ZveUnit.memory.U,Mux(cross,ZveUnit.cross.U,
      Mux(fma,ZveUnit.fma.U,Mux(conversion,ZveUnit.conversion.U,Mux(fast,ZveUnit.alu.U,ZveUnit.integer.U)))))
    d.serial := memory
    def aligned(base: UInt,count: UInt): Bool = (base & (count-1.U)) === 0.U && base+&count <= 32.U
    val widths = (!d.vectorSource1 || d.source1Width <= 2.U) &&
      (!d.vectorSource2 || d.source2Width <= 2.U) && d.destinationWidth <= 2.U &&
      (!extension || sew >= extendBy)
    val groups = (!d.vectorSource1 || aligned(vs1,d.source1Registers)) &&
      (!d.vectorSource2 || aligned(vs2,d.source2Registers)) && (d.scalarResult || aligned(vd,d.destinationRegisters))
    val emul = (e1 >= -3.S && e1 <= 3.S || !d.vectorSource1 || d.reduction || d.maskSources) &&
      (e2 >= -3.S && e2 <= 3.S || !d.vectorSource2 || d.maskSources || d.scalarResult || d.whole) &&
      (ed >= -3.S && ed <= 3.S || d.reduction || d.maskResult || d.whole)
    val validType = !vt(63,8).orR && sew <= 2.U && vt(2,0) =/= 4.U
    val v0Legal = x(25) || vd =/= 0.U || d.maskResult || d.reduction || d.scalarResult || (memory && x(6,0) === 0x27.U)
    val floatingWidth = !fp || Mux(op === ZveOp.vfwcvt.U || op === ZveOp.vfncvt.U,sew === 1.U,sew === 2.U)
    val noRestart = !d.reduction && !is(ZveOp.vcpop,ZveOp.vfirst,ZveOp.viota,ZveOp.vmsbf,
      ZveOp.vmsif,ZveOp.vmsof,ZveOp.vcompress) || in.scalar.vstart === 0.U
    val destructiveOverlap = !is(ZveOp.vslideup,ZveOp.vslide1up,ZveOp.vfslide1up,
      ZveOp.vrgather,ZveOp.vrgatherei16,ZveOp.vcompress) ||
      !(dst & (src2 | Mux(is(ZveOp.vrgather,ZveOp.vrgatherei16,ZveOp.vcompress),src1,0.U))).orR
    val wideOverlap = !(dst & src2).orR || d.destinationWidth === d.source2Width ||
      (d.destinationWidth < d.source2Width && vd === vs2) ||
      (d.destinationWidth > d.source2Width && e2 >= 0.S && vd+&d.destinationRegisters === vs2+&d.source2Registers)
    val wideOverlap1 = !d.vectorSource1 || !(dst & src1).orR || d.destinationWidth === d.source1Width ||
      (d.destinationWidth < d.source1Width && vd === vs1) ||
      (d.destinationWidth > d.source1Width && e1 >= 0.S && vd+&d.destinationRegisters === vs1+&d.source1Registers)
    val maskOverlap = !d.maskResult || d.maskSources ||
      ((!(dst & src2).orR || vd===vs2) && (!d.vectorSource1 || !(dst & src1).orR || vd===vs1))
    val segmentOk = !memory || d.whole || (d.destinationRegisters*(x(31,29)+1.U) <= 8.U &&
      vd+& (d.destinationRegisters*(x(31,29)+1.U)) <= 32.U)
    val vlmax = bytes(lmul) >> d.sew
    val roundingOperation=is(ZveOp.vfadd,ZveOp.vfsub,ZveOp.vfrsub,ZveOp.vfmul,ZveOp.vfdiv,
      ZveOp.vfrdiv,ZveOp.vfsqrt,ZveOp.vfrec7,ZveOp.vfmacc,ZveOp.vfnmacc,ZveOp.vfmsac,
      ZveOp.vfnmsac,ZveOp.vfmadd,ZveOp.vfnmadd,ZveOp.vfmsub,ZveOp.vfnmsub,
      ZveOp.vfredosum,ZveOp.vfredusum) ||
      (is(ZveOp.vfcvt,ZveOp.vfwcvt,ZveOp.vfncvt) && !x(17))
    d.legal := hits.reduce(_ || _) && widths && groups && emul && floatingWidth && v0Legal &&
      noRestart && destructiveOverlap && (d.reduction || d.scalarResult || (wideOverlap && wideOverlap1)) &&
      maskOverlap && segmentOk && (validType || (memory && d.whole)) &&
      (!roundingOperation || in.scalar.frm<=4.U)
    when(!d.whole && in.scalar.vl > vlmax) { d.legal := false.B }
    // Segment fields are disjoint groups, so extend the owned range.
    when(memory && !d.whole && !maskMemory) {
      val all=group(vd,d.destinationRegisters*(x(31,29)+1.U))
      when(x(6,0) === 0x27.U) { d.reads := all | Mux(d.vectorSource2,src2,0.U) | Mux(!x(25),1.U,0.U) }
        .otherwise { d.writes := all }
    }
    d
  }
}
