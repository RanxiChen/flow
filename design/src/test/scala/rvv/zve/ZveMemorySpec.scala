package flow.rvv.zve

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable
import scala.util.Random

/** The service model translates two adjacent virtual pages to unrelated PAs.
  * It checks leases and byte strobes, and stalls all five AXI channels and
  * translation independently. Goldens use byte-addressed architectural data. */
class ZveMemorySpec extends AnyFreeSpec with ChiselSim {
  private def encoding(name: String,vd: Int,index: Int=8,nf: Int=1,vm: Boolean=true): BigInt = {
    val pat=ZveEncodings.forms.find(_._1==name).get._3
    val fields=(BigInt(vd)<<7)|(BigInt(index)<<20)|(BigInt(if(vm)1 else 0)<<25)|(BigInt(nf-1)<<29)
    pat.value | (fields & ~pat.mask & BigInt("ffffffff",16))
  }
  private def pack(xs: Seq[BigInt],width: Int): BigInt = xs.zipWithIndex.foldLeft(BigInt(0)) {
    case(v,(x,i)) => v|((x & ((BigInt(1)<<width)-1))<<(i*width))
  }
  "memory service ownership, address modes, page faults, FOF and restart survive random backpressure" in {
    simulate(new ZveMemoryHarness) { d =>
      val io=d.io; val debug=io.debugVrf.get
      io.issue.valid.poke(false.B); io.commit.poke(false.B); io.kill.poke(false.B); io.serialGo.poke(false.B)
      io.verdict.ready.poke(false.B); io.completion.ready.poke(false.B); io.scalarWritesVisible.poke(true.B)
      io.sharedFp.request.ready.poke(false.B); io.sharedFp.response.valid.poke(false.B)
      debug.read.valid.poke(false.B); debug.returned.ready.poke(false.B)
      debug.write.valid.poke(false.B); debug.written.ready.poke(false.B)
      io.translation.ready.poke(false.B); io.translated.valid.poke(false.B)
      io.order.ready.poke(false.B); io.release.ready.poke(false.B); io.invalidate.ready.poke(false.B)
      io.axi.ar.ready.poke(false.B); io.axi.aw.ready.poke(false.B); io.axi.w.ready.poke(false.B)
      io.axi.r.valid.poke(false.B); io.axi.b.valid.poke(false.B)
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
      val rng=new Random(91); var cycle=0; var debugTag=0
      val memory=mutable.Map.empty[BigInt,Int].withDefaultValue(0)
      def physical(va: BigInt): BigInt = if(va>=0x2000 && va<0x3000) va+0x7000 else va+0x4000
      def bytes(va: BigInt,value: BigInt,n: Int): Unit = for(i<-0 until n) memory(physical(va+i))=((value>>(8*i))&255).toInt
      def word(va: BigInt,n: Int=4): BigInt = (0 until n).foldLeft(BigInt(0))((v,i)=>v|(BigInt(memory(physical(va+i)))<<(8*i)))
      case class Translation(due: Int,tag: BigInt,va: BigInt,n: Int,store: Boolean,epoch: BigInt)
      case class Lease(tag: BigInt,pa: BigInt,n: Int,store: Boolean,var invalidated: Boolean=false,var busDone: Boolean=false)
      case class Read(due: Int,data: BigInt,error: Boolean)
      var pendingTranslation: Option[Translation]=None; var lease: Option[Lease]=None
      var pendingRead: Option[Read]=None; var pendingB: Option[Int]=None
      var aw: Option[(BigInt,Int)]=None; var w: Option[(BigInt,BigInt)]=None
      var pageFault: Option[BigInt]=None; var busFault: Option[BigInt]=None
      val translatedAddresses=mutable.ArrayBuffer.empty[BigInt]
      var busTransactions=0; var invalidations=0; var releases=0
      def tick(): Unit = {
        io.translation.ready.poke((pendingTranslation.isEmpty && rng.nextBoolean()).B)
        io.order.ready.poke((lease.isEmpty && rng.nextBoolean()).B)
        io.release.ready.poke(rng.nextBoolean().B); io.invalidate.ready.poke(rng.nextBoolean().B)
        io.axi.ar.ready.poke((pendingRead.isEmpty && rng.nextBoolean()).B)
        io.axi.aw.ready.poke((aw.isEmpty && pendingB.isEmpty && rng.nextBoolean()).B)
        io.axi.w.ready.poke((w.isEmpty && pendingB.isEmpty && rng.nextBoolean()).B)
        io.translated.valid.poke(pendingTranslation.exists(_.due<=cycle).B)
        pendingTranslation.filter(_.due<=cycle).foreach { t =>
          val fault=pageFault.exists(a=>a>=t.va && a<t.va+t.n)
          io.translated.bits.tag.poke(t.tag.U); io.translated.bits.epoch.poke(t.epoch.U)
          io.translated.bits.pa.poke(physical(t.va).U); io.translated.bits.bytes.poke(t.n.U)
          io.translated.bits.exception.poke(fault.B); io.translated.bits.cause.poke((if(t.store)15 else 13).U)
          io.translated.bits.tval.poke(pageFault.getOrElse(t.va).U); io.translated.bits.device.poke(false.B)
        }
        io.axi.r.valid.poke(pendingRead.exists(_.due<=cycle).B)
        pendingRead.filter(_.due<=cycle).foreach { r =>
          io.axi.r.bits.id.poke(0.U); io.axi.r.bits.last.poke(true.B)
          io.axi.r.bits.data.poke(r.data.U); io.axi.r.bits.resp.poke((if(r.error)2 else 0).U)
        }
        io.axi.b.valid.poke(pendingB.exists(_<=cycle).B); io.axi.b.bits.id.poke(0.U); io.axi.b.bits.resp.poke(0.U)
        def fire(valid: Bool,ready: Bool): Boolean=valid.peek().litToBoolean && ready.peek().litToBoolean
        if(fire(io.translated.valid,io.translated.ready)) pendingTranslation=None
        if(fire(io.axi.r.valid,io.axi.r.ready)) { pendingRead=None; lease.get.busDone=true }
        if(fire(io.axi.b.valid,io.axi.b.ready)) { pendingB=None; lease.get.busDone=true }
        if(fire(io.release.valid,io.release.ready)) {
          assert(lease.exists(l=>l.tag==io.release.bits.peek().litValue && l.busDone),"premature/unowned lease release")
          lease=None; releases+=1
        }
        if(fire(io.translation.valid,io.translation.ready)) {
          val t=io.translation.bits
          assert(pendingTranslation.isEmpty && lease.isEmpty)
          val request=Translation(cycle+1+rng.nextInt(5),t.tag.peek().litValue,t.va.peek().litValue,
            t.bytes.peek().litValue.toInt,t.store.peek().litToBoolean,t.epoch.peek().litValue)
          assert(Seq(1,2,4).contains(request.n)); translatedAddresses+=request.va; pendingTranslation=Some(request)
        }
        if(fire(io.order.valid,io.order.ready)) {
          val o=io.order.bits; assert(lease.isEmpty); o.age.expect(77.U)
          lease=Some(Lease(o.tag.peek().litValue,o.pa.peek().litValue,o.bytes.peek().litValue.toInt,o.store.peek().litToBoolean))
        }
        if(fire(io.invalidate.valid,io.invalidate.ready)) {
          val l=lease.get; assert(l.store); assert(io.invalidate.bits.peek().litValue==(l.pa & ~BigInt(31)))
          l.invalidated=true; invalidations+=1
        }
        if(fire(io.axi.ar.valid,io.axi.ar.ready)) {
          val a=io.axi.ar.bits; val pa=a.addr.peek().litValue; val n=1<<a.size.peek().litValue.toInt
          val l=lease.get; assert(!l.store && l.pa==pa && l.n==n); a.len.expect(0.U)
          val base=pa & ~BigInt(63)
          val data=(0 until 64).foldLeft(BigInt(0))((v,i)=>v|(BigInt(memory(base+i))<<(8*i)))
          pendingRead=Some(Read(cycle+1+rng.nextInt(5),data,busFault.contains(pa))); busTransactions+=1
        }
        if(fire(io.axi.aw.valid,io.axi.aw.ready)) {
          val a=io.axi.aw.bits; val l=lease.get; val pa=a.addr.peek().litValue; val n=1<<a.size.peek().litValue.toInt
          assert(l.store && l.invalidated && l.pa==pa && l.n==n); a.len.expect(0.U); aw=Some((pa,n)); busTransactions+=1
        }
        if(fire(io.axi.w.valid,io.axi.w.ready)) {
          assert(lease.exists(l=>l.store && l.invalidated)); io.axi.w.bits.last.expect(true.B)
          w=Some((io.axi.w.bits.data.peek().litValue,io.axi.w.bits.strb.peek().litValue))
        }
        if(aw.nonEmpty && w.nonEmpty) {
          val (pa,n)=aw.get; val (data,strobe)=w.get; val offset=(pa & 63).toInt
          assert(strobe==(((BigInt(1)<<n)-1)<<offset),"store touched bytes outside its lease")
          for(i<-0 until n) memory(pa+i)=((data>>(8*(offset+i)))&255).toInt
          aw=None; w=None; pendingB=Some(cycle+1+rng.nextInt(5))
        }
        d.clock.step(); cycle+=1
      }
      def waitFor(pred: => Boolean,limit: Int=20000): Unit = {
        var n=0; while(!pred && n<limit) { tick(); n+=1 }; assert(n<limit,s"timeout at cycle $cycle")
      }
      def write(reg: Int,value: BigInt): Unit = {
        debugTag+=1; debug.write.bits.tag.poke(debugTag.U); debug.write.bits.age.poke(0.U)
        debug.write.bits.row.poke(reg.U); debug.write.bits.data.poke(value.U); debug.write.bits.enables.poke(((BigInt(1)<<64)-1).U)
        debug.write.valid.poke(true.B); waitFor(debug.write.ready.peek().litToBoolean); tick(); debug.write.valid.poke(false.B)
        waitFor(debug.written.valid.peek().litToBoolean); debug.written.bits.tag.expect(debugTag.U)
        debug.written.ready.poke(true.B); tick(); debug.written.ready.poke(false.B)
      }
      def read(reg: Int): BigInt = {
        debugTag+=1; debug.read.bits.tag.poke(debugTag.U); debug.read.bits.age.poke(0.U)
        debug.read.bits.enables.poke(1.U); debug.read.bits.rows.foreach(_.poke(reg.U))
        debug.read.valid.poke(true.B); waitFor(debug.read.ready.peek().litToBoolean); tick(); debug.read.valid.poke(false.B)
        waitFor(debug.returned.valid.peek().litToBoolean); val v=debug.returned.bits.data(0).peek().litValue
        debug.returned.ready.poke(true.B); tick(); debug.returned.ready.poke(false.B); v
      }
      def instruction(name: String,vd: Int,base: BigInt,vl: Int,sew: Int=32,stride: BigInt=0,index: Int=8,
          nf: Int=1,vm: Boolean=true,start: Int=0,exc: Int=0,bad: BigInt=0,shortVl: Option[Int]=None): Unit = {
        val in=io.issue.bits; in.scalar.instruction.poke(encoding(name,vd,index,nf,vm).U)
        in.scalar.rs1.poke(base.U); in.scalar.rs2.poke((stride & ((BigInt(1)<<64)-1)).U)
        in.scalar.vtype.poke((if(sew==8)0 else if(sew==16)8 else 16).U)
        in.scalar.vl.poke(vl.U); in.scalar.vstart.poke(start.U); in.scalar.vxrm.poke(0.U)
        in.scalar.frm.poke(0.U); in.scalar.rd.poke(vd.U); in.fpScalar.poke(0.U); in.epoch.poke(9.U); in.memoryAge.poke(77.U)
        translatedAddresses.clear(); val before=busTransactions
        io.issue.valid.poke(true.B); waitFor(io.issue.ready.peek().litToBoolean); tick(); io.issue.valid.poke(false.B)
        waitFor(io.verdict.valid.peek().litToBoolean)
        val serial=io.verdict.bits.kind.peek().litValue==3; assert(serial || io.verdict.bits.kind.peek().litValue==0,s"illegal $name")
        io.verdict.ready.poke(true.B); tick(); io.verdict.ready.poke(false.B)
        // No translation or bus side effect before commit + Serial permission.
        for(_<-0 until 4) tick(); assert(translatedAddresses.isEmpty && busTransactions==before)
        io.commit.poke(true.B); io.serialGo.poke(serial.B); tick(); io.commit.poke(false.B); io.serialGo.poke(false.B)
        waitFor(io.completion.valid.peek().litToBoolean)
        io.completion.bits.exception.expect((exc!=0).B)
        if(exc!=0) { io.completion.bits.cause.expect(exc.U); io.completion.bits.tval.expect(bad.U); io.completion.bits.vstart.expect(2.U) }
        io.completion.bits.changeVl.expect(shortVl.nonEmpty.B); shortVl.foreach(v=>io.completion.bits.newVl.expect(v.U))
        assert(lease.isEmpty && pendingRead.isEmpty && pendingB.isEmpty && pendingTranslation.isEmpty)
        def status=(io.completion.bits.age.peek().litValue,io.completion.bits.exception.peek().litToBoolean,
          io.completion.bits.cause.peek().litValue,io.completion.bits.tval.peek().litValue,
          io.completion.bits.vstart.peek().litValue,io.completion.bits.changeVl.peek().litToBoolean,
          io.completion.bits.newVl.peek().litValue)
        val snapshot=status
        for(_<-0 until 3) { tick(); assert(status==snapshot) }
        io.completion.ready.poke(true.B); tick(); io.completion.ready.poke(false.B); waitFor(io.drained.peek().litToBoolean)
      }
      val sentinel=pack(Seq.fill(16)(BigInt("deadbeef",16)),32)
      for(i<-0 until 16) bytes(0x1000+4*i,100+i,4)
      write(16,sentinel); instruction("vle32.v",16,0x1000,16); assert(read(16)==pack((100 until 116).map(BigInt(_)),32))
      instruction("vse32.v",16,0x1100,16); for(i<-0 until 16) assert(word(0x1100+4*i)==100+i)
      for(i<-0 until 4) bytes(0x1203+4*i,0x11223340L+i,4)
      write(16,sentinel); instruction("vle32.v",16,0x1203,4)
      assert((read(16)&((BigInt(1)<<128)-1))==pack((0 until 4).map(i=>BigInt(0x11223340L+i)),32))
      assert(translatedAddresses.toSeq==(0 until 16).map(i=>BigInt(0x1203+i)))
      for(i<-0 until 4) bytes(0x1ff8+4*i,200+i,4)
      instruction("vle32.v",16,0x1ff8,4); assert((read(16)&((BigInt(1)<<128)-1))==pack((200 until 204).map(BigInt(_)),32))
      instruction("vlse32.v",16,0x103c,4,stride = -4); assert((read(16)&((BigInt(1)<<128)-1))==pack((112 to 115).reverse.map(BigInt(_)),32))
      instruction("vlse32.v",16,0x1000,4,stride=0); assert((read(16)&((BigInt(1)<<128)-1))==pack(Seq.fill(4)(BigInt(100)),32))
      for((name,width)<-Seq(("vluxei8.v",8),("vloxei16.v",16),("vluxei32.v",32))) {
        write(8,pack(Seq(12,0,8,4).map(BigInt(_)),width)); instruction(name,16,0x1000,4)
        assert((read(16)&((BigInt(1)<<128)-1))==pack(Seq(103,100,102,101).map(BigInt(_)),32))
      }
      for(i<-0 until 4) { bytes(0x1300+8*i,300+i,4); bytes(0x1304+8*i,400+i,4) }
      write(16,sentinel); write(17,sentinel); instruction("vle32.v",16,0x1300,4,nf=2,start=1)
      assert((read(16)&((BigInt(1)<<128)-1))==pack(Seq(BigInt("deadbeef",16),BigInt(301),BigInt(302),BigInt(303)),32))
      assert((read(17)&((BigInt(1)<<128)-1))==pack(Seq(BigInt("deadbeef",16),BigInt(401),BigInt(402),BigInt(403)),32))
      instruction("vse32.v",16,0x1400,4,nf=2,start=1); for(i<-1 until 4) { assert(word(0x1400+8*i)==300+i); assert(word(0x1404+8*i)==400+i) }
      // NF=8 must not wrap the three-bit encoded NF+1 back to zero.
      for(i<-0 until 8) bytes(0x1500+i,50+i,1)
      instruction("vle8.v",16,0x1500,1,sew=8,nf=8); for(i<-0 until 8) assert((read(16+i)&255)==50+i)
      bytes(0x1600,0xabcd,2); instruction("vlm.v",16,0x1600,13); assert((read(16)&65535)==0xabcd)
      instruction("vsm.v",16,0x1610,13); assert(word(0x1610,2)==0xabcd)
      for(i<-0 until 128) bytes(0x1700+i,i,1)
      instruction("vl2re8.v",16,0x1700,0); assert(read(16)==pack((0 until 64).map(BigInt(_)),8)); assert(read(17)==pack((64 until 128).map(BigInt(_)),8))
      write(16,sentinel); pageFault=Some(BigInt(0x1008))
      instruction("vle32ff.v",16,0x1000,4,shortVl=Some(2))
      assert((read(16)&((BigInt(1)<<128)-1))==pack(Seq(BigInt(100),BigInt(101),BigInt("deadbeef",16),BigInt("deadbeef",16)),32))
      instruction("vle32.v",16,0x1000,4,exc=13,bad=0x1008)
      pageFault=None; instruction("vle32.v",16,0x1000,4,start=2)
      assert(translatedAddresses.toSeq==Seq(BigInt(0x1008),BigInt(0x100c)))
      assert((read(16)&((BigInt(1)<<128)-1))==pack((100 until 104).map(BigInt(_)),32))
      busFault=Some(physical(0x1008)); instruction("vle32.v",16,0x1000,4,exc=5,bad=0x1008); busFault=None
      write(0,10); pageFault=Some(BigInt(0x1000)); instruction("vle32.v",16,0x1000,4,vm=false)
      assert(translatedAddresses.toSeq==Seq(BigInt(0x1004),BigInt(0x100c))); pageFault=None
      instruction("vle32.v",16,0x1000,0); assert(translatedAddresses.isEmpty)
      assert(invalidations>0 && releases==busTransactions)
    }
  }
}
