package flow.mmu.sv39

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

/** Only the test adds visibility; the reference below never reads DUT state
  * to choose an expected hit, touched way or replacement victim. */
class DeferredPlruProbe extends Sv39Tlb(Sv39TlbParams(2,4,4), instruction=true, pipelinedFetchKill=true) {
  val logicalBase = IO(Output(Vec(2,UInt(3.W))))
  val logicalSuper = IO(Output(UInt(3.W)))
  logicalBase := visibleBasePlru
  logicalSuper := visibleSpPlru
}
class Sv39DeferredPlruSpec extends AnyFreeSpec with ChiselSim {
  "deferred iTLB touches preserve reference replacement across streams kills refills and sfence" in {
    simulate(new DeferredPlruProbe) { d =>
      d.io.csr.poke(0.U.asTypeOf(d.io.csr)); d.io.csr.sv39.poke(true.B); d.io.csr.priv.poke(1.U)
      d.io.sfence.poke(0.U.asTypeOf(d.io.sfence)); d.io.grant.poke(false.B)
      d.io.done.poke(0.U.asTypeOf(d.io.done)); d.io.port.kill.poke(false.B)
      d.io.port.req.valid.poke(false.B); d.io.port.req.bits.vaddr.poke(0.U)
      d.io.port.req.bits.cmd.poke(MmuCmd.Fetch)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val tags=Array.fill(2,4)(Option.empty[BigInt]); val large=Array.fill(4)(Option.empty[BigInt])
      val tree=Array.fill(3)(Array.fill(3)(false))
      def touch(t:Int,w:Int):Unit = { tree(t)(0)=w<2; tree(t)(if(w<2)1 else 2)=(w&1)==0 }
      def victim(t:Int):Int = { val right=tree(t)(0); (if(right)2 else 0)+(if(tree(t)(if(right)2 else 1))1 else 0) }
      def state(t:Int):BigInt = (0 until 3).foldLeft(BigInt(0))((v,i)=>if(tree(t)(i))v.setBit(i) else v)
      def views():Unit = {
        for(s <- 0 until 2)d.logicalBase(s).expect(state(s).U)
        d.logicalSuper.expect(state(2).U)
      }
      def find(va:BigInt):(Int,Int) = {
        val s=((va>>12)&1).toInt; val base=tags(s).indexOf(Some(va>>12))
        if(base>=0)(s,base) else (2,large.indexOf(Some(va>>30)))
      }
      def request(va:BigInt, killed:Boolean=false):Boolean = {
        d.io.port.req.ready.expect(true.B); d.io.port.req.valid.poke(true.B)
        d.io.port.req.bits.vaddr.poke(va.U); d.clock.step(); d.io.port.req.valid.poke(false.B)
        d.io.port.kill.poke(killed.B); val(t,w)=find(va)
        d.io.port.resp.valid.expect((!killed).B)
        if(!killed){ d.io.port.resp.bits.hit.expect((w>=0).B); d.io.port.resp.bits.miss.expect((w<0).B) }
        if(!killed && w>=0)touch(t,w)
        d.clock.step(); d.io.port.kill.poke(false.B); views(); !killed && w>=0
      }
      def fill(va:BigInt,level:Int):Unit = {
        assert(!request(va)); d.io.miss.valid.expect(true.B)
        d.io.grant.poke(true.B); d.clock.step(); d.io.grant.poke(false.B)
        d.io.done.valid.poke(true.B); d.io.done.bits.refillValid.poke(true.B)
        val e=d.io.done.bits.refill
        e.poke(0.U.asTypeOf(e)); e.vpn.poke((va>>12).U); e.level.poke(level.U)
        e.ppn.poke((if(level==0)BigInt(0x800)+(va>>12) else BigInt(0x80000)+(va>>30)*0x40000).U)
        e.r.poke(true.B); e.x.poke(true.B); e.a.poke(true.B); e.d.poke(true.B)
        val t=if(level==0)((va>>12)&1).toInt else 2
        val entries=if(t==2)large else tags(t)
        val hole=entries.indexWhere(_.isEmpty); val w=if(hole>=0)hole else victim(t)
        entries(w)=Some(if(t==2)va>>30 else va>>12); touch(t,w)
        d.clock.step(); d.io.done.valid.poke(false.B); d.io.done.bits.refillValid.poke(false.B); views()
      }
      val pages=(0 until 8).map(i=>BigInt(0x1000L+i*0x1000L))
      pages.foreach(fill(_,0))
      // II=1 hit stream: every cycle must expose the newly captured touch,
      // including repeated same-set accesses before physical writeback.
      val rng=new scala.util.Random(0x51a9)
      val stream=Seq(pages(0),pages(2),pages(0),pages(4))++Seq.fill(100)(pages(rng.nextInt(8)))
      d.io.port.req.valid.poke(true.B); d.io.port.req.bits.vaddr.poke(stream.head.U); d.clock.step()
      for(i <- stream.indices) {
        d.io.port.resp.valid.expect(true.B); d.io.port.resp.bits.hit.expect(true.B)
        val(t,w)=find(stream(i)); touch(t,w)
        d.io.port.req.valid.poke((i+1<stream.size).B)
        if(i+1<stream.size)d.io.port.req.bits.vaddr.poke(stream(i+1).U)
        d.clock.step(); views()
      }
      // A later kill must not revoke the preceding accepted touch.
      d.io.port.kill.poke(true.B); d.clock.step(); d.io.port.kill.poke(false.B); views()
      request(pages(1),killed=true); views()
      for(i <- 0 until 10)fill(BigInt(0x20000+i*0x1000),0)
      for(i <- 0 until 4)fill(BigInt(i+1)<<30,2)
      request(BigInt(1)<<30); request(BigInt(3)<<30); request(BigInt(2)<<30,killed=true)
      fill(BigInt(5)<<30,2); request(BigInt(5)<<30)
      d.io.idle.expect(true.B); d.io.sfence.valid.poke(true.B); d.clock.step()
      d.io.sfence.valid.poke(false.B)
      for(s <- 0 until 2; w <- 0 until 4)tags(s)(w)=None
      for(w <- 0 until 4)large(w)=None
      views(); assert(!request(BigInt(5)<<30))
      d.io.port.kill.poke(true.B); d.clock.step(); d.io.port.kill.poke(false.B)
      d.io.idle.expect(true.B); views()
    }
  }
}
