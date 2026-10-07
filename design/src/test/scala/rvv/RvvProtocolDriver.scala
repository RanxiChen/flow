package flow.rvv

import chisel3._
import chisel3.simulator.PeekPokeAPI
import com.fasterxml.jackson.databind.{JsonNode,ObjectMapper}
import java.nio.file.{Files,Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Random

case class R02Instruction(word: BigInt, rs1: BigInt, rs2: BigInt, vl: Int, vtype: BigInt,
  vstart: Int, rd: Int, label: String, scalarExpected: Option[BigInt]) {
  def memory: Boolean = (word.toInt & 127)==7 || (word.toInt & 127)==0x27
  def store: Boolean = (word.toInt & 127)==0x27
  def eew: Int = { val f=(word.toInt>>12)&7; if(f==0) 0 else f-4 }
  def bytes: Int = vl << eew
}
case class R02Fixture(base: BigInt, initial: Array[Int], expected: Array[Int], records: Vector[R02Instruction])
object R02Fixture {
  private def number(n: JsonNode): BigInt = BigInt(n.asText())
  def hex(s: String): Array[Int] = s.grouped(2).map(Integer.parseInt(_,16)).toArray
  def load(path: Path): R02Fixture = {
    val root=new ObjectMapper().readTree(Files.readString(path))
    val records=root.get("records").elements().asScala.map { n =>
      R02Instruction(number(n.get("instruction")),number(n.get("rs1")),number(n.get("rs2")),
        n.get("vl").asInt(),number(n.get("vtype")),n.get("vstart").asInt(),n.get("rd").asInt(),
        n.get("label").asText(),Option(n.get("scalarExpected")).map(number))
    }.toVector
    R02Fixture(number(root.get("base")),hex(root.get("initial").asText()),hex(root.get("expected").asText()),records)
  }
}
case class R02Measurement(cycles: Long, readBeats: Long, firstRead: Long, lastRead: Long,
  kernelFirstIssue: Long, kernelLastB: Long, kernelReadBeats: Long, kernelFirstRead: Long, kernelLastRead: Long,
  remainingBytes: BigInt, invalidations: Long, kernelRemaining: BigInt,
  linkDelays: Vector[Long], requestAdvances: Vector[Long])

/** Core, translation service and memory use only the mounting contract. Every
  * accepted item is accounted for, including reissued items after random kill.
  * Expected bytes and scalar results are supplied by the executed Spike ELF. */
class RvvProtocolDriver(dut: RvvCoprocessor, p: RvvParams, fixture: R02Fixture,
  seed: Long, randomize: Boolean = true, readLatency: Int = 3, writeLatency: Int = 3,
  translationLatency: Int = 2, injectKills: Boolean = true) extends PeekPokeAPI {
  private val rng = new Random(seed)
  private val memory = mutable.Map.empty[BigInt,Int]
  private def physical(va: BigInt): BigInt = BigInt("90000000",16)+((va-fixture.base)>>12)*12288+(va & 4095)
  for(b <- fixture.initial.indices) memory(physical(fixture.base+b))=fixture.initial(b)
  private case class Pending(index: Int,var judged: Boolean=false,var due: Long=Long.MaxValue)
  private case class Translation(id: Int,va: BigInt,due: Long)
  private case class Read(address: BigInt,beats: Int,var beat: Int,due: Long,label: String,index: Int)
  private case class Write(address: BigInt,beats: Int,var beat: Int,label: String,index: Int)
  private case class Response(due: Long,label: String,index: Int)
  private case class MemoryFrame(r: R02Instruction,index: Int,var allIssued: Boolean=false,var readRemaining: Int=0,var writeRemaining: Int=0) {
    def complete: Boolean=allIssued && readRemaining==0 && writeRemaining==0
  }
  private val frames=mutable.ArrayBuffer.empty[MemoryFrame]
  private var completesThisCycle=Set.empty[Int]
  private val pending=mutable.ArrayBuffer.empty[Pending]
  private val translations=mutable.ArrayBuffer.empty[Translation]
  private val reads=mutable.Queue.empty[Read]
  private val writes=mutable.Queue.empty[Write]
  private val responses=mutable.Queue.empty[Response]
  private val scalar=mutable.Queue.empty[(Int,BigInt)]
  private val committedMemory=mutable.Queue.empty[(R02Instruction,Int)]
  private var requestInstruction: Option[(R02Instruction,Int)]=None
  private var requestPosition=0
  private var cursor=0; private var committed=0
  private var cycle=0L; private var readBeats=0L; private var firstRead = -1L; private var lastRead = -1L
  private var kernelFirstIssue = -1L; private var kernelLastB = -1L
  private var kernelReadBeats=0L; private var kernelFirstRead = -1L; private var kernelLastRead = -1L
  private var invalidations=0L
  private var kernelRemaining=BigInt(-1)
  private val loadRegisterDone=mutable.Map.empty[(Long,Int),Long]
  private val macRegisterRead=mutable.Map.empty[(Long,Int),Long]
  private val macCompleted=mutable.Map.empty[Long,Long]
  private val kernelRequests=mutable.Map.empty[Int,Long]
  private var scalarGap=0
  private var nextIssueAge=0L
  private val acceptedAges=mutable.Map.empty[Int,Long]
  private val wireAgeToLogical=mutable.Map.empty[Long,Long]
  private def logicalAge(wire: BigInt): Long = wireAgeToLogical(wire.toLong)
  private val ageToRecord=mutable.Map.empty[Long,Int]
  private val liveLoads=mutable.Set.empty[Long]
  private var peakLiveLoads=0
  private var requestsDuringWar=0
  private var slowWarRequests=0
  private val invalidated=mutable.Set.empty[BigInt]
  private val touchedLines=mutable.Set.empty[BigInt]
  private def bool(x: Bool): Boolean=x.peek().litToBoolean
  private def uint(x: UInt): BigInt=x.peek().litValue
  private def jitter(n: Int): Int=if(randomize) rng.nextInt(n+1) else n
  private def ready(): Boolean = !randomize || rng.nextInt(4)!=0
  private def pokeIssue(r: R02Instruction): Unit = {
    val x=dut.io.issue.bits
    x.instruction.poke(r.word.U); x.rs1.poke(r.rs1.U); x.rs2.poke(r.rs2.U)
    x.vl.poke(r.vl.U); x.vtype.poke(r.vtype.U); x.vstart.poke(r.vstart.U)
    x.vxrm.poke(0.U); x.frm.poke(0.U); x.rd.poke(r.rd.U)
  }
  def reset(): Unit = {
    dut.io.issue.valid.poke(false.B); dut.io.verdict.ready.poke(true.B)
    dut.io.commit.poke(false.B); dut.io.killUncommitted.poke(false.B); dut.io.serialGo.poke(false.B)
    dut.io.translation.ready.poke(true.B); dut.io.translated.valid.poke(false.B)
    dut.io.scalarResult.ready.poke(true.B); dut.io.conflictQuery.valid.poke(false.B)
    dut.io.conflictQuery.pa.poke(0.U); dut.io.conflictQuery.bytes.poke(0.U); dut.io.conflictQuery.write.poke(false.B)
    dut.io.invalidate.ready.poke(true.B); dut.io.scalarWritesVisible.poke(true.B)
    dut.io.axi.ar.ready.poke(true.B); dut.io.axi.aw.ready.poke(true.B); dut.io.axi.w.ready.poke(true.B)
    dut.io.axi.r.valid.poke(false.B); dut.io.axi.b.valid.poke(false.B)
    dut.reset.poke(true.B); dut.clock.step(3); dut.reset.poke(false.B); dut.clock.step(2)
  }
  private def acceptRequest(address: BigInt,beats: Int,store: Boolean): (String,Int) = {
    if(requestInstruction.isEmpty) { assert(committedMemory.nonEmpty,s"AXI before commit at $cycle"); requestInstruction=Some(committedMemory.dequeue()); requestPosition=0 }
    val (r,index)=requestInstruction.get
    for(old <- frames if old.index<index && !old.complete && !completesThisCycle(old.index) && (old.r.store || store)) {
      // The mapping is injective; logical overlap is equivalent to physical
      // overlap, while AR/AW splitting is independently checked below.
      val overlaps=old.r.rs1<r.rs1+r.bytes && r.rs1<old.r.rs1+old.r.bytes
      assert(!overlaps,s"overlapping ${if(old.r.store) "store" else "load"} -> ${if(store) "store" else "load"} request before older R/B completion at cycle=$cycle old=${old.index} young=$index")
    }
    assert(r.store==store,s"memory issue order changed at $cycle")
    val va=r.rs1+requestPosition
    val expected=physical(va) & ~(BigInt(p.memBytes)-1)
    assert(address==expected,s"wrong PA/burst base at $cycle record=$index expected=$expected actual=$address")
    val prefix=(physical(va)&(p.memBytes-1)).toInt
    val remaining=math.min(r.bytes-requestPosition,4096-(va&4095).toInt)
    val expectedBeats=math.min(p.burstBeats,math.min((remaining+prefix+p.memBytes-1)/p.memBytes,(4096-(address&4095).toInt)/p.memBytes))
    assert(beats==expectedBeats,s"burst split changed record=$index expected=$expectedBeats actual=$beats")
    requestPosition += math.min(remaining,beats*p.memBytes-prefix)
    val frame=frames.find(_.index==index).get
    if(store) frame.writeRemaining+=1 else frame.readRemaining+=beats
    if(requestPosition==r.bytes) { frame.allIssued=true; requestInstruction=None }
    (r.label,index)
  }
  def run(maxCycles: Int=200000): R02Measurement = {
    reset()
    while(cycle<maxCycles && !(cursor==fixture.records.size && pending.isEmpty && bool(dut.io.drained))) {
      dut.io.commit.poke(false.B); dut.io.killUncommitted.poke(false.B)
      val kill=injectKills && randomize && pending.nonEmpty && rng.nextInt(35)==0
      dut.io.killUncommitted.poke(kill.B)
      val setupBarrier= !randomize && cursor<fixture.records.size && fixture.records(cursor).label=="zero-acc" && !bool(dut.io.drained)
      val issue=cursor<fixture.records.size && !kill && scalarGap==0 && !setupBarrier && (!randomize || rng.nextInt(3)!=0)
      dut.io.issue.valid.poke(issue.B)
      if(issue) pokeIssue(fixture.records(cursor))
      val verdictReady=ready(); dut.io.verdict.ready.poke(verdictReady.B)
      val translationReady=ready(); dut.io.translation.ready.poke(translationReady.B)
      val scalarReady=ready(); dut.io.scalarResult.ready.poke(scalarReady.B)
      dut.io.invalidate.ready.poke(ready().B); dut.io.scalarWritesVisible.poke(ready().B)
      dut.io.axi.ar.ready.poke(ready().B); dut.io.axi.aw.ready.poke(ready().B); dut.io.axi.w.ready.poke(ready().B)
      val tr=translations.find(_.due<=cycle).filter(_ => ready())
      dut.io.translated.valid.poke(tr.nonEmpty.B)
      tr.foreach { t =>
        dut.io.translated.bits.id.poke(t.id.U); dut.io.translated.bits.pa.poke(physical(t.va).U)
        dut.io.translated.bits.device.poke(false.B); dut.io.translated.bits.cacheable.poke(true.B)
        dut.io.translated.bits.exception.poke(false.B); dut.io.translated.bits.cause.poke(0.U)
      }
      val returning=reads.headOption.filter(r => r.due<=cycle && ready())
      dut.io.axi.r.valid.poke(returning.nonEmpty.B)
      returning.foreach { r =>
        val addr=r.address+r.beat*p.memBytes
        val data=(0 until p.memBytes).foldLeft(BigInt(0))((a,b)=>a | (BigInt(memory.getOrElse(addr+b,0))<<(8*b)))
        dut.io.axi.r.bits.data.poke(data.U); dut.io.axi.r.bits.id.poke(0.U)
        dut.io.axi.r.bits.last.poke((r.beat+1==r.beats).B); dut.io.axi.r.bits.resp.poke(0.U)
      }
      val responding=responses.headOption.filter(b => b.due<=cycle && ready())
      dut.io.axi.b.valid.poke(responding.nonEmpty.B)
      dut.io.axi.b.bits.id.poke(0.U); dut.io.axi.b.bits.resp.poke(0.U)
      completesThisCycle=Set.empty
      returning.foreach(r => frames.find(_.index==r.index).foreach(f => if(f.allIssued && f.readRemaining==1) completesThisCycle+=f.index))
      responding.foreach(b => frames.find(_.index==b.index).foreach(f => if(f.allIssued && f.writeRemaining==1) completesThisCycle+=f.index))
      val judgement=bool(dut.io.verdict.valid) && verdictReady && !kill
      val unjudged=pending.find(!_.judged)
      var justJudged: Option[Pending]=None
      if(judgement) {
        assert(unjudged.nonEmpty,s"unexpected or duplicate verdict at $cycle")
        dut.io.verdict.bits.kind.expect(0.U)
        val item=unjudged.get; item.judged=true; item.due=cycle+(if(randomize) rng.nextInt(4) else 0)
        justJudged=Some(item)
      }
      val doCommit=pending.headOption.exists(item => item.judged && item.due<=cycle && (!kill || !justJudged.contains(item)))
      dut.io.commit.poke(doCommit.B)
      val accepted=issue && bool(dut.io.issue.ready)
      if(doCommit) {
        val item=pending.head
        assert(item.index==committed,s"commit reordered at cycle $cycle")
        val r=fixture.records(item.index)
        if(r.memory && r.bytes>0 && r.vstart<r.vl) {
          committedMemory.enqueue((r,item.index)); frames += MemoryFrame(r,item.index)
        }
        if(r.memory && !r.store && r.bytes>0 && r.vstart<r.vl) {
          liveLoads += acceptedAges(item.index); peakLiveLoads=math.max(peakLiveLoads,liveLoads.size)
        }
        r.scalarExpected.foreach(value => scalar.enqueue((r.rd,value)))
      }
      dut.io.conflictQuery.valid.poke(true.B); dut.io.conflictQuery.bytes.poke(1.U)
      val query=frames.find(!_.complete)
      query match {
        case Some(f) =>
          dut.io.conflictQuery.pa.poke(physical(f.r.rs1).U)
          dut.io.conflictQuery.write.poke((!f.r.store).B)
          assert(bool(dut.io.conflict),s"missing committed memory interval seed=$seed cycle=$cycle index=${f.index} label=${f.r.label} bytes=${f.r.bytes} PA=${physical(f.r.rs1)} allIssued=${f.allIssued} readRemaining=${f.readRemaining} writeRemaining=${f.writeRemaining} commit=$doCommit kill=$kill")
        case None =>
          dut.io.conflictQuery.pa.poke("hdead00000000".U); dut.io.conflictQuery.write.poke(true.B)
          dut.io.conflict.expect(false.B)
      }
      if(bool(dut.io.translation.valid) && translationReady) {
        val id=uint(dut.io.translation.bits.id).toInt
        assert(!translations.exists(_.id==id),s"translation ID reused before response at $cycle")
        translations += Translation(id,uint(dut.io.translation.bits.va),cycle+math.max(1,jitter(translationLatency)))
      }
      if(bool(dut.io.axi.ar.valid) && bool(dut.io.axi.ar.ready)) {
        val address=uint(dut.io.axi.ar.bits.addr); val beats=uint(dut.io.axi.ar.bits.len).toInt+1
        assert((address&4095)+beats*p.memBytes<=4096,s"AR crosses a page at $cycle")
        val (label,index)=acceptRequest(address,beats,false)
        if(bool(dut.io.loadWriteWar)) {
          requestsDuringWar+=1
          if(label=="slow-reader-independent-load") slowWarRequests+=1
        }
        if(label.startsWith("gemv-load-") && !randomize) {
          val index=fixture.records.indexWhere(_.label==label)
          if(!kernelRequests.contains(index)) kernelRequests(index)=cycle
        }
        reads.enqueue(Read(address,beats,0,cycle+math.max(1,readLatency),label,index))
      }
      if(bool(dut.io.axi.aw.valid) && bool(dut.io.axi.aw.ready)) {
        val address=uint(dut.io.axi.aw.bits.addr); val beats=uint(dut.io.axi.aw.bits.len).toInt+1
        assert((address&4095)+beats*p.memBytes<=4096,s"AW crosses a page at $cycle")
        val (label,index)=acceptRequest(address,beats,true)
        writes.enqueue(Write(address,beats,0,label,index))
      }
      if(bool(dut.io.axi.w.valid) && bool(dut.io.axi.w.ready)) {
        assert(writes.nonEmpty,s"W without AW at $cycle")
        val w=writes.head; val data=uint(dut.io.axi.w.bits.data); val strobe=uint(dut.io.axi.w.bits.strb)
        assert(bool(dut.io.axi.w.bits.last)==(w.beat+1==w.beats))
        for(b <- 0 until p.memBytes if strobe.testBit(b)) {
          val address=w.address+w.beat*p.memBytes+b
          memory(address)=((data>>(8*b))&255).toInt
          touchedLines += address & ~(BigInt(p.cacheLineBytes)-1)
        }
        w.beat+=1
        if(w.beat==w.beats) { writes.dequeue(); responses.enqueue(Response(cycle+math.max(1,jitter(writeLatency)),w.label,w.index)) }
      }
      if(bool(dut.io.invalidate.valid) && bool(dut.io.invalidate.ready)) {
        invalidations+=1; invalidated += uint(dut.io.invalidate.bits)
      }
      if(bool(dut.io.scalarResult.valid) && scalarReady) {
        assert(scalar.nonEmpty,s"scalar result from killed/uncommitted item at $cycle")
        val (rd,value)=scalar.dequeue()
        assert(uint(dut.io.scalarResult.bits.data)==value,s"scalar mismatch at $cycle")
        dut.io.scalarResult.bits.rd.expect(rd.U); dut.io.scalarResult.bits.floating.expect(false.B)
      }
      if(returning.nonEmpty) {
        dut.io.axi.r.ready.expect(true.B)
        val r=returning.get
        frames.find(_.index==r.index).get.readRemaining-=1
        readBeats+=1; if(firstRead<0) firstRead=cycle; lastRead=cycle
        if(r.label.startsWith("gemv-load-")) {
          kernelReadBeats+=1; if(kernelFirstRead<0) kernelFirstRead=cycle; kernelLastRead=cycle
        }
        r.beat+=1; if(r.beat==r.beats) reads.dequeue()
      }
      if(responding.nonEmpty) {
        frames.find(_.index==responding.get.index).get.writeRemaining-=1
        if(responding.get.label=="kernel-final-store") {
          kernelLastB=cycle; kernelRemaining=uint(dut.io.counters.bufferedBytes)
        }
        responses.dequeue()
      }
      if(tr.nonEmpty) translations -= tr.get
      if(bool(dut.io.loadRegisterComplete.valid)) {
        val age=logicalAge(uint(dut.io.loadRegisterComplete.bits.age))
        val reg=uint(dut.io.loadRegisterComplete.bits.register).toInt
        loadRegisterDone((age,reg))=cycle
        val record=fixture.records(ageToRecord(age))
        val lastReg=((record.word>>7)&31).toInt+(record.bytes+p.regBytes-1)/p.regBytes-1
        if(reg==lastReg) liveLoads-=age
      }
      if(bool(dut.io.macRead.valid)) {
        val key=(logicalAge(uint(dut.io.macRead.bits.age)),uint(dut.io.macRead.bits.register).toInt)
        if(!macRegisterRead.contains(key)) macRegisterRead(key)=cycle
      }
      if(bool(dut.io.macComplete.valid)) macCompleted(logicalAge(uint(dut.io.macComplete.bits)))=cycle
      if(scalarGap>0) scalarGap-=1
      if(accepted) {
        if(fixture.records(cursor).label=="zero-acc") kernelFirstIssue=cycle
        wireAgeToLogical(nextIssueAge & ((1L << p.ageBits)-1))=nextIssueAge
        acceptedAges(cursor)=nextIssueAge; ageToRecord(nextIssueAge)=cursor; nextIssueAge+=1
        pending += Pending(cursor); cursor+=1
        // One scalar lw placeholder between a GEMV weight load and its dot.
        // Reference instrumentation/setup is excluded from the kernel model.
        if(!randomize && fixture.records(cursor-1).label.startsWith("gemv-load-")) scalarGap=1
      }
      if(doCommit) { pending.remove(0); committed+=1 }
      if(kill) { pending.clear(); cursor=committed }
      dut.clock.step(1); cycle+=1
    }
    assert(cycle<maxCycles,s"timeout seed=$seed cycle=$cycle committed=$committed cursor=$cursor pending=${pending.size}")
    assert(translations.isEmpty && reads.isEmpty && writes.isEmpty && responses.isEmpty && scalar.isEmpty && committedMemory.isEmpty && requestInstruction.isEmpty,
      s"drained without completing protocol obligations seed=$seed")
    for(b <- fixture.expected.indices) {
      val actual=memory.getOrElse(physical(fixture.base+b),0)
      assert(actual==fixture.expected(b),f"Spike memory/VRF mismatch seed=$seed VA=0x${fixture.base+b}%x expected=0x${fixture.expected(b)}%02x actual=0x$actual%02x")
    }
    assert(touchedLines.subsetOf(invalidated),s"missing store invalidations seed=$seed lines=${touchedLines--invalidated}")
    if(fixture.records.exists(_.label=="held-war-load")) {
      assert(peakLiveLoads>=2,s"multiple-load lifetime never overlapped seed=$seed")
      assert(requestsDuringWar>0,s"no younger AR was issued while an older load writeback waited for WAR seed=$seed")
      println(s"R02_WAR_BYPASS seed=$seed peakLiveLoads=$peakLiveLoads requestsDuringWar=$requestsDuringWar")
    }
    if(fixture.records.exists(_.label=="slow-reader-independent-load")) {
      assert(slowWarRequests>0,s"added slow-reader WAR window never overlapped an independent AR seed=$seed")
      println(s"R03_SLOW_WAR seed=$seed requests=$slowWarRequests")
    }
    dut.io.counters.readBytes.expect((readBeats*p.memBytes).U)
    val remaining=uint(dut.io.counters.bufferedBytes)
    assert(remaining==0,s"unconsumed data at drain seed=$seed bytes=$remaining")
    val loadIndices=fixture.records.indices.filter(i => fixture.records(i).label.startsWith("gemv-load-"))
    val links=if(randomize) Vector.empty else loadIndices.flatMap { index =>
      val reg=((fixture.records(index).word>>7)&31).toInt
      for(done <- loadRegisterDone.get((index.toLong,reg));read <- macRegisterRead.get((index.toLong+1,reg))) yield read-done
    }.toVector
    val advances=if(randomize) Vector.empty else loadIndices.drop(1).flatMap { index =>
      val previous=(0 until index).reverse.find(i => fixture.records(i).label.startsWith("gemv-dot-"))
      for(prev <- previous;done <- macCompleted.get(prev.toLong);requested <- kernelRequests.get(index)) yield done-requested
    }.toVector
    if(!randomize) sys.env.get("R02_RESULTS").foreach { destination =>
      val out=Path.of(destination); Files.createDirectories(out)
      val rows=loadIndices.map { index =>
        val reg=((fixture.records(index).word>>7)&31).toInt
        val previous=(0 until index).reverse.find(i => fixture.records(i).label.startsWith("gemv-dot-"))
        s"$index,${kernelRequests.getOrElse(index,-1L)},${loadRegisterDone.getOrElse((index.toLong,reg),-1L)},${macRegisterRead.getOrElse((index.toLong+1,reg),-1L)},${previous.flatMap(i => macCompleted.get(i.toLong)).getOrElse(-1L)}"
      }
      Files.writeString(out.resolve(s"links-lat$readLatency-buffer${p.returnBytes}.csv"),
        "loadRecord,firstAR,firstRegisterDone,dependentMacRead,previousMacDone\n"+rows.mkString("\n")+"\n")
    }
    R02Measurement(cycle,readBeats,firstRead,lastRead,kernelFirstIssue,kernelLastB,kernelReadBeats,kernelFirstRead,kernelLastRead,remaining,invalidations,kernelRemaining,links,advances)
  }
}
