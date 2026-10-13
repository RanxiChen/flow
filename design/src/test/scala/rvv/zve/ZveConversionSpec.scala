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
      for((value,unsigned,rm,expected,flags)<-Seq(
          (32767.5f,false,0,32767,16),(32767.5f,false,1,32767,1),
          (-32769f,false,0,32768,16),(65535f,true,0,65535,0),
          (65536f,true,0,65535,16),(Float.NaN,true,0,65535,16))) {
        tag+=1; d.io.in.bits.metadata.poke(tag.U)
        d.io.in.bits.a(0).poke((BigInt(java.lang.Float.floatToRawIntBits(value)&0xffffffffL)).U)
        d.io.in.bits.operation.poke(BreezeFpOp.F2I.U); d.io.in.bits.modifier.poke(unsigned.B)
        d.io.in.bits.rounding.poke(rm.U); d.io.in.bits.integerFormat.poke(1.U)
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
        var n=0; while(!d.io.out.valid.peek().litToBoolean && n<30) { d.clock.step(); n+=1 }; assert(n<30)
        assert((d.io.out.bits.data(0).peek().litValue&65535)==expected)
        d.io.out.bits.flags.expect(flags.U); d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
    }
  }
  "conversion and non-computational results keep their tags and held payload under stalls" in {
    simulate(new ZveFpnewService(1)) { d =>
      val s=d.io.service
      s.request.valid.poke(false.B); s.response.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
      var sent=0; var received=Set.empty[Int]; var cycle=0
      var held: Option[(BigInt,BigInt,BigInt)]=None
      while(received.size<12 && cycle<500) {
        s.request.valid.poke((sent<12).B)
        val r=s.request.bits; r.tag.poke(sent.U); r.a.poke((if(sent%2==0)65534L else 0x40000000L).U)
        r.b.poke(0.U); r.c.poke(0.U); r.operation.poke((if(sent%2==0)BreezeFpOp.I2F else BreezeFpOp.CLASSIFY).U)
        r.modifier.poke(false.B); r.rounding.poke(0.U); r.integerFormat.poke(1.U)
        r.sourceFormat.poke(0.U); r.destinationFormat.poke(0.U)
        s.response.ready.poke((cycle>=40 && cycle%3==0).B)
        val valid=s.response.valid.peek().litToBoolean; val ready=s.response.ready.peek().litToBoolean
        val snapshot=(s.response.bits.tag.peek().litValue,s.response.bits.data.peek().litValue,s.response.bits.flags.peek().litValue)
        held.foreach(old=>assert(valid && snapshot==old,"FP service changed a held response"))
        if(valid && ready) {
          val tag=snapshot._1.toInt; assert(tag<sent && !received(tag)); received+=tag
          val expected=if(tag%2==0)BigInt("c0000000",16) else BigInt(64)
          assert((snapshot._2 & BigInt("ffffffff",16))==expected); assert(snapshot._3==0)
        }
        held=if(valid && !ready)Some(snapshot)else None
        if(s.request.valid.peek().litToBoolean && s.request.ready.peek().litToBoolean) sent+=1
        d.clock.step(); cycle+=1
      }
      assert(received.size==12); s.request.valid.poke(false.B); d.clock.step(3); d.io.drained.expect(true.B)
    }
  }
}
