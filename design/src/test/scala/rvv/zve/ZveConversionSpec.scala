package flow.rvv.zve

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import svsim.{CommonCompilationSettings,CommonSettingsModifications}
import flow.fpu.{BreezeFpSources,BreezeFpOp}

class ZveConversionSpec extends AnyFreeSpec with ChiselSim {
  override implicit def commonSettingsModifications: CommonSettingsModifications = {
    val inherited=super.commonSettingsModifications
    (settings: CommonCompilationSettings) => {
      val base=inherited(settings)
      base.copy(includeDirs=Some((base.includeDirs.getOrElse(Seq.empty) :+ BreezeFpSources.includeDir.toString).distinct))
    }
  }
  "local conversion services preserve signed INT16 and INT32 boundaries" in {
    simulate(new ZveFpBatchHarness(1,kind=1)) { d =>
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
      var tag=0
      for(width<-Seq(16,32); value<-Seq(0,1,2,3,4,17,-1,-2,-3,-32768,32767)) {
        tag+=1
        d.io.in.bits.metadata.poke(tag.U); d.io.in.bits.active.poke(1.U)
        d.io.in.bits.a(0).poke((BigInt(value)&((BigInt(1)<<width)-1)).U)
        d.io.in.bits.b(0).poke(0.U); d.io.in.bits.c(0).poke(0.U)
        d.io.in.bits.operation.poke(BreezeFpOp.I2F.U); d.io.in.bits.modifier.poke(false.B)
        d.io.in.bits.rounding.poke(0.U); d.io.in.bits.sourceFormat.poke(0.U)
        d.io.in.bits.destinationFormat.poke(0.U); d.io.in.bits.integerFormat.poke((if(width==16)1 else 2).U)
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
        var n=0; while(!d.io.out.valid.peek().litToBoolean && n<30) { d.clock.step(); n+=1 }; assert(n<30)
        val expected=BigInt(java.lang.Float.floatToRawIntBits(value.toFloat)&0xffffffffL)
        assert((d.io.out.bits.data(0).peek().litValue & BigInt("ffffffff",16))==expected,s"width=$width value=$value")
        d.io.out.bits.flags.expect(0.U); d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
    }
  }
}
