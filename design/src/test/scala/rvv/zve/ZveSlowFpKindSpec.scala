package flow.rvv.zve

import chisel3._
import chisel3.util.Decoupled
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import svsim.{CommonCompilationSettings,CommonSettingsModifications}
import flow.fpu.{BreezeFpOp,BreezeFpSources}
import scala.collection.mutable
import scala.util.Random

class ZveSlowFpKindHarness extends Module {
  val io=IO(new Bundle {
    val request=Vec(2,Flipped(Decoupled(new ZveFpRequest)))
    val response=Vec(2,Decoupled(new ZveFpResponse))
    val drained=Output(Vec(2,Bool()))
  })
  for((kind,index)<-Seq(2,3).zipWithIndex) {
    val service=Module(new ZveFpnewService(kind))
    service.io.service.request.valid := io.request(index).valid
    service.io.service.request.bits := io.request(index).bits
    io.request(index).ready := service.io.service.request.ready
    io.response(index).valid := service.io.service.response.valid
    io.response(index).bits := service.io.service.response.bits
    service.io.service.response.ready := io.response(index).ready
    io.drained(index) := service.io.drained
  }
}

class ZveSlowFpKindSpec extends AnyFreeSpec with ChiselSim {
  override implicit def commonSettingsModifications: CommonSettingsModifications = {
    val inherited=super.commonSettingsModifications
    (settings: CommonCompilationSettings) => {
      val base=inherited(settings)
      base.copy(includeDirs=Some((base.includeDirs.getOrElse(Seq.empty) :+
        BreezeFpSources.includeDir.toString).distinct))
    }
  }

  private case class Request(op: Int, rm: Int, a: Long, b: Long, c: Long,
    kind3Unboxed: Boolean = false)
  private val fp32Mask=BigInt("ffffffff",16)
  private def boxed(bits: Long): BigInt=(BigInt("ffffffff",16)<<32) | BigInt(bits & 0xffffffffL)

  "FP32 KIND=3 matches RV64D KIND=2 through tags, flags and backpressure" in {
    val operations=Seq(BreezeFpOp.ADD,BreezeFpOp.MUL,BreezeFpOp.DIV,
      BreezeFpOp.SQRT,BreezeFpOp.MINMAX,BreezeFpOp.CMP)
    val specials=Seq(0x00000000L,0x80000000L,0x7f800000L,0xff800000L,
      0x7fc00000L,0x7f800001L,0x00000001L,0x007fffffL,0x7f7fffffL,
      0x00800000L,0xbf800000L,0x3f800000L)
    val directedBase=for(op<-operations; rm<- (if(op==BreezeFpOp.MINMAX) 0 to 1
      else if(op==BreezeFpOp.CMP) 0 to 2 else 0 to 4);
      i<-specials.indices) yield Request(op,rm,specials(i),
        specials((i+3)%specials.size),specials((i+7)%specials.size))
    // KIND=2 always receives valid NaN-boxed FP32. KIND=3 receives both
    // encodings for identical low bits; its result must match the boxed case.
    val directed=directedBase.flatMap(r=>Seq(r,r.copy(kind3Unboxed=true)))
    val rng=new Random(0x707c32fL)
    val random=Vector.fill(10000) {
      val op=operations(rng.nextInt(operations.size))
      val rm=if(op==BreezeFpOp.MINMAX)rng.nextInt(2)
        else if(op==BreezeFpOp.CMP)rng.nextInt(3) else rng.nextInt(5)
      Request(op,rm,rng.nextInt()&0xffffffffL,rng.nextInt()&0xffffffffL,
        rng.nextInt()&0xffffffffL,rng.nextBoolean())
    }
    // Isolated first requests give comparable acceptance-to-return latency.
    val requests=(Seq(Request(BreezeFpOp.DIV,0,0x3f800000L,0x40000000L,0L),
      Request(BreezeFpOp.SQRT,0,0x40800000L,0L,0L))++directed++random).toVector
    assert(requests.size<65536)
    simulate(new ZveSlowFpKindHarness) { d =>
      d.io.request.foreach(_.valid.poke(false.B))
      d.io.response.foreach(_.ready.poke(false.B))
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
      val sent=Array(0,0)
      val seen=Array.fill(2)(mutable.Map.empty[Int,(BigInt,BigInt)])
      val received=Array.fill(2)(mutable.Set.empty[Int])
      val held=Array.fill[Option[(BigInt,BigInt,BigInt)]](2)(None)
      val acceptedAt=Array.fill(2,2)(-1)
      val latency=Array.fill(2,2)(-1)
      var matched=0
      var cycle=0
      val pressure=new Random(0x5032f07cL)
      while(matched<requests.size && cycle<300000) {
        val sendLimit=if(matched<2)matched+1 else requests.size
        for(i<-0 until 2) {
          val valid=sent(i)<sendLimit
          val p=d.io.request(i)
          p.valid.poke(valid.B)
          if(valid) {
            val t=sent(i); val r=requests(t)
            def operand(bits: Long): BigInt = if(i==1 && r.kind3Unboxed)
              BigInt(bits & 0xffffffffL) else boxed(bits)
            p.bits.tag.poke(t.U); p.bits.a.poke(operand(r.a).U)
            p.bits.b.poke(operand(r.b).U); p.bits.c.poke(operand(r.c).U)
            p.bits.operation.poke(r.op.U); p.bits.modifier.poke(false.B)
            p.bits.rounding.poke(r.rm.U); p.bits.sourceFormat.poke(0.U)
            p.bits.destinationFormat.poke(0.U); p.bits.integerFormat.poke(2.U)
          }
          d.io.response(i).ready.poke((matched<2 || pressure.nextInt(4)!=0).B)
        }
        for(i<-0 until 2) {
          val p=d.io.response(i)
          val valid=p.valid.peek().litToBoolean
          val snapshot=(p.bits.tag.peek().litValue,p.bits.data.peek().litValue,
            p.bits.flags.peek().litValue)
          held(i).foreach(old=>assert(valid && snapshot==old,
            s"kind=${i+2} changed a held response at cycle=$cycle"))
          if(valid && p.ready.peek().litToBoolean) {
            val tag=snapshot._1.toInt
            assert(tag<sent(i) && !received(i)(tag),s"kind=${i+2} invalid tag=$tag")
            received(i)+=tag
            seen(i)(tag)=(snapshot._2,snapshot._3)
            if(tag<2)latency(i)(tag)=cycle-acceptedAt(i)(tag)
          }
          held(i)=if(valid && !p.ready.peek().litToBoolean)Some(snapshot) else None
        }
        for(i<-0 until 2 if sent(i)<sendLimit) {
          if(d.io.request(i).ready.peek().litToBoolean) {
            if(sent(i)<2)acceptedAt(i)(sent(i))=cycle
            sent(i)+=1
          }
        }
        while(seen(0).contains(matched) && seen(1).contains(matched)) {
          val x=seen(0).remove(matched).get; val y=seen(1).remove(matched).get
          val mask=if(requests(matched).op==BreezeFpOp.CMP)BigInt(1) else fp32Mask
          assert((x._1 & mask)==(y._1 & mask),
            s"FP result mismatch tag=$matched op=${requests(matched).op} rm=${requests(matched).rm} kind2=$x kind3=$y")
          assert(x._2==y._2,
            s"FP status mismatch tag=$matched op=${requests(matched).op} rm=${requests(matched).rm} kind2=$x kind3=$y")
          matched+=1
        }
        d.clock.step(); cycle+=1
      }
      assert(matched==requests.size,s"only matched $matched/${requests.size} in $cycle cycles")
      d.io.request.foreach(_.valid.poke(false.B))
      d.io.response.foreach(_.ready.poke(true.B))
      d.clock.step(3)
      d.io.drained.foreach(_.expect(true.B))
      println(s"ZveSlowFpKindSpec seed=0x707c32f random=10000 directed=${directed.size} total=${requests.size} cycles=$cycle")
      println(s"isolated DIV latency KIND=2 ${latency(0)(0)} cycles, KIND=3 ${latency(1)(0)} cycles")
      println(s"isolated SQRT latency KIND=2 ${latency(0)(1)} cycles, KIND=3 ${latency(1)(1)} cycles")
    }
  }
}
