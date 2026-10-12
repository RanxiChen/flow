package flow.rvv.zve

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import svsim.{CommonCompilationSettings,CommonSettingsModifications}
import flow.fpu.BreezeFpSources

class ZveCoprocessorSpec extends AnyFreeSpec with ChiselSim {
  override implicit def commonSettingsModifications: CommonSettingsModifications = {
    val inherited=super.commonSettingsModifications
    (settings: CommonCompilationSettings) => {
      val base=inherited(settings)
      base.copy(includeDirs=Some((base.includeDirs.getOrElse(Seq.empty) :+ BreezeFpSources.includeDir.toString).distinct))
    }
  }
  private def encoding(name: String,vd: Int,vs2: Int,vs1: Int,vm: Boolean=true,nf: Int=1): BigInt = {
    val pat=ZveEncodings.forms.find(_._1==name).get._3
    val fields=(BigInt(vd)<<7)|(BigInt(vs1)<<15)|(BigInt(vs2)<<20)|(BigInt(if(vm)1 else 0)<<25)|(BigInt(nf-1)<<29)
    pat.value | (fields & ~pat.mask & BigInt("ffffffff",16))
  }
  private def packed(values: Seq[BigInt],width: Int): BigInt = {
    val mask=(BigInt(1)<<width)-1
    values.zipWithIndex.foldLeft(BigInt(0)) { case(acc,(v,i)) => acc|((v & mask)<<(i*width)) }
  }
  private def fbits(f: Float): BigInt=BigInt(java.lang.Float.floatToRawIntBits(f)&0xffffffffL)

  "independent coprocessor executes integer, conversion, half-width FMA, mask and slow paths through physical VRF" in {
    simulate(new ZveStandaloneHarness) { d =>
      d.io.issue.valid.poke(false.B); d.io.commit.poke(false.B); d.io.kill.poke(false.B); d.io.serialGo.poke(false.B)
      d.io.verdict.ready.poke(false.B); d.io.completion.ready.poke(false.B)
      d.io.translation.ready.poke(false.B); d.io.translated.valid.poke(false.B)
      d.io.order.ready.poke(false.B); d.io.release.ready.poke(false.B); d.io.invalidate.ready.poke(false.B)
      d.io.scalarWritesVisible.poke(true.B)
      d.io.axi.ar.ready.poke(false.B); d.io.axi.aw.ready.poke(false.B); d.io.axi.w.ready.poke(false.B)
      d.io.axi.r.valid.poke(false.B); d.io.axi.b.valid.poke(false.B)
      val debug=d.io.debugVrf
      debug.read.valid.poke(false.B); debug.returned.ready.poke(false.B)
      debug.write.valid.poke(false.B); debug.written.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
      var debugTag=0
      def waitFor(pred: => Boolean,limit: Int=12000): Unit = {
        var n=0
        while(!pred && n<limit) { d.clock.step(); n+=1 }
        assert(n<limit,s"coprocessor timed out after $limit cycles")
      }
      def write(reg: Int,value: BigInt): Unit = {
        debugTag+=1; debug.write.bits.tag.poke(debugTag.U); debug.write.bits.age.poke(0.U)
        debug.write.bits.row.poke(reg.U); debug.write.bits.data.poke(value.U)
        debug.write.bits.enables.poke(((BigInt(1)<<64)-1).U)
        debug.write.valid.poke(true.B); waitFor(debug.write.ready.peek().litToBoolean)
        d.clock.step(); debug.write.valid.poke(false.B)
        waitFor(debug.written.valid.peek().litToBoolean); debug.written.bits.tag.expect(debugTag.U)
        debug.written.ready.poke(true.B); d.clock.step(); debug.written.ready.poke(false.B)
      }
      def read(reg: Int): BigInt = {
        debugTag+=1; debug.read.bits.tag.poke(debugTag.U); debug.read.bits.age.poke(0.U)
        debug.read.bits.enables.poke(1.U); debug.read.bits.rows.foreach(_.poke(reg.U))
        debug.read.valid.poke(true.B); waitFor(debug.read.ready.peek().litToBoolean)
        d.clock.step(); debug.read.valid.poke(false.B)
        waitFor(debug.returned.valid.peek().litToBoolean); debug.returned.bits.tag.expect(debugTag.U)
        val result=debug.returned.bits.data(0).peek().litValue
        debug.returned.ready.poke(true.B); d.clock.step(); debug.returned.ready.poke(false.B)
        result
      }
      def instruction(name: String,vd: Int=16,vs2: Int=8,vs1: Int=12,vl: Int=16,
          sew: Int=32,vm: Boolean=true,start: Int=0,scalar: BigInt=0,rounding: Int=0,
          expectedFlags: Int=0,expectedSat: Boolean=false,scalarExpected: Option[BigInt]=None): Unit = {
        val in=d.io.issue.bits
        in.scalar.instruction.poke(encoding(name,vd,vs2,vs1,vm).U)
        in.scalar.rs1.poke((scalar & ((BigInt(1)<<64)-1)).U); in.scalar.rs2.poke(0.U)
        in.scalar.vtype.poke((if(sew==8)0 else if(sew==16)8 else 16).U)
        in.scalar.vl.poke(vl.U); in.scalar.vstart.poke(start.U); in.scalar.vxrm.poke(rounding.U)
        in.scalar.frm.poke(rounding.U); in.scalar.rd.poke(vd.U)
        in.fpScalar.poke(((BigInt("ffffffff",16)<<32)|(scalar & BigInt("ffffffff",16))).U)
        in.epoch.poke(0.U); in.memoryAge.poke(0.U)
        d.io.issue.valid.poke(true.B); waitFor(d.io.issue.ready.peek().litToBoolean)
        d.clock.step(); d.io.issue.valid.poke(false.B)
        waitFor(d.io.verdict.valid.peek().litToBoolean); d.io.verdict.bits.kind.expect(0.U)
        d.io.verdict.ready.poke(true.B); d.clock.step(); d.io.verdict.ready.poke(false.B)
        d.io.commit.poke(true.B); d.clock.step(); d.io.commit.poke(false.B)
        waitFor(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.exception.expect(false.B); d.io.completion.bits.flags.expect(expectedFlags.U)
        d.io.completion.bits.saturated.expect(expectedSat.B)
        scalarExpected.foreach(v => { d.io.completion.bits.scalarValid.expect(true.B); d.io.completion.bits.scalarData.expect(v.U) })
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
        waitFor(d.io.drained.peek().litToBoolean)
      }
      val a=(1 to 16).map(BigInt(_)); val b=Seq.fill(16)(BigInt(2)); val zero=BigInt(0)
      write(8,packed(a,32)); write(12,packed(b,32)); write(16,zero)
      instruction("vadd.vv"); assert(read(16)==packed(a.map(_+2),32))
      instruction("vmacc.vv"); assert(read(16)==packed(a.map(v=>v+2+v*2),32))
      instruction("vdiv.vx",scalar=3); assert(read(16)==packed(a.map(_/3),32))
      instruction("vmseq.vx",vl=16,scalar=4); assert((read(16)&65535)==8)
      instruction("vredsum.vs",vd=20); assert((read(20)&BigInt("ffffffff",16))==138)
      val floats=a.map(v=>fbits(v.toFloat))
      write(8,packed(floats,32)); write(12,packed(Seq.fill(16)(fbits(2.0f)),32))
      write(16,packed(Seq.fill(16)(fbits(3.0f)),32))
      instruction("vfmacc.vv"); assert(read(16)==packed(a.map(v=>fbits(v.toFloat*2.0f+3.0f)),32))
      instruction("vfcvt.x.f.v"); assert(read(16)==packed(a,32))
      instruction("vfcvt.f.x.v",vs2=16,vd=20); assert(read(20)==packed(floats,32))
      instruction("vfmul.vf",vd=20,scalar=fbits(2.0f)); assert(read(20)==packed(a.map(v=>fbits(v.toFloat*2.0f)),32))
      instruction("vfredosum.vs",vd=20); assert((read(20)&BigInt("ffffffff",16))==fbits(138.0f))
      // Inactive infinity*zero must neither overwrite old values nor set NV.
      val mask=BigInt("5555",16); write(0,mask)
      write(8,packed(a.zipWithIndex.map { case(v,i)=>if(i%2==1)BigInt("7f800000",16)else fbits(v.toFloat) },32))
      write(12,packed((0 until 16).map(i=>fbits(if(i%2==1)0.0f else 2.0f)),32))
      write(16,packed(Seq.fill(16)(fbits(3.0f)),32))
      instruction("vfmacc.vv",vm=false,start=3)
      assert(read(16)==packed(a.zipWithIndex.map { case(v,i)=>fbits(if(i>=3 && i%2==0)v.toFloat*2.0f+3.0f else 3.0f) },32))
      instruction("vmv.x.s",vd=5,vs2=16,vl=0,start=7,scalarExpected=Some(fbits(3.0f)))
      write(8,packed(Seq.fill(64)(BigInt(127)),8)); write(12,packed(Seq.fill(64)(BigInt(1)),8))
      instruction("vsadd.vv",vl=64,sew=8,expectedSat=true)
      assert(read(16)==packed(Seq.fill(64)(BigInt(127)),8))
      instruction("vcpop.m",vd=5,vs2=0,vs1=0,vl=16,scalarExpected=Some(BigInt(8)))
    }
  }
}
