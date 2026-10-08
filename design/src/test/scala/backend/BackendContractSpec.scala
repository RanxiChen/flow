package flow.backend

import chisel3._
import chisel3.simulator.PeekPokeAPI
import flow.config.{BackendConfig, PrivilegeProfile}
import flow.fpu.BreezeFpChiselSim
import flow.interface._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.collection.mutable

/** Cycle model is an environment, not a prediction copied from backend RTL.
  * S1/S2 are advanced on every non-s2Hold edge, irrespective of late.ready.
  * A replay that loses its write port moves to MSHR LATE; ordinary hits still
  * generate their unique Valid response. Seeds and response schedules are explicit.
  */
private[backend] object Instructions {
  val nop: BigInt = 0x13
  val mask: BigInt = (BigInt(1) << 64) - 1
  def add(rd: Int, a: Int = 0, b: Int = 0): BigInt = (BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(rd)<<7)|0x33
  def addi(rd: Int, a: Int, n: Int): BigInt = (BigInt(n & 4095)<<20)|(BigInt(a)<<15)|(BigInt(rd)<<7)|0x13
  def mdu(rd: Int, a: Int, b: Int, div: Boolean = false): BigInt =
    (BigInt(1)<<25)|(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(if(div) 5 else 0)<<12)|(BigInt(rd)<<7)|0x33
  def ld(rd: Int, addr: Int, fp: Boolean = false): BigInt = (BigInt(addr)<<20)|(BigInt(3)<<12)|(BigInt(rd)<<7)|(if(fp) 7 else 3)
  def sd(addr: Int, r: Int = 0): BigInt = (BigInt(addr>>5)<<25)|(BigInt(r)<<20)|(BigInt(3)<<12)|(BigInt(addr & 31)<<7)|0x23
  def csr(rd: Int, addr: Int, r: Int = 0, f: Int = 2): BigInt = (BigInt(addr)<<20)|(BigInt(r)<<15)|(BigInt(f)<<12)|(BigInt(rd)<<7)|0x73
  def fp(rd: Int, a: Int = 0, b: Int = 0, div: Boolean = false, toGpr: Boolean = false): BigInt =
    (BigInt(if(toGpr) 0x60 else if(div) 0x0d else 1)<<25)|(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(rd)<<7)|0x53
  def fma(rd: Int): BigInt = (BigInt(1)<<25)|(BigInt(rd)<<7)|0x43
}
private[backend] final case class Request(addr: BigInt, rd: Int, fp: Boolean, op: Int, data: BigInt)
private[backend] final case class Event(cycle: Int, kind: String, pc: BigInt = 0, rd: Int = -1, data: BigInt = 0)
private[backend] class Environment(val d: BreezeBackend, val seed: Int = 0xB01) extends PeekPokeAPI {
  import Instructions._
  var cycle = 0
  var pc = BigInt(0x400)
  val events = mutable.ArrayBuffer.empty[Event]
  val values = mutable.Map.empty[BigInt,BigInt].withDefaultValue(BigInt(0x1234))
  val misses = mutable.Set.empty[BigInt]
  val faults = mutable.Set.empty[BigInt]
  var returnDelay = 30
  var drained = true
  var mmuIdle = true
  var holdUntil = 0
  var randomHold = false
  var lateError = false
  var faultCause = 13
  var softwareInterrupt = false
  var timerInterrupt = false
  var predictionTaken = false
  var predictionTarget: Option[BigInt] = None
  var predictionIndex = 0
  var instructionAccessFault = false
  var instructionPageFault = false
  var faultSecondParcel = false
  private val rng = new scala.util.Random(seed)
  private var s1: Option[Request] = None
  private var s2: Option[Request] = None
  private var ps: Option[Request] = None
  private var pending: Option[(Request,Int)] = None // metadata/refill, single MSHR
  private var retryAt: Option[Int] = None
  var lateStored = false
  private var lateData: Option[BigInt] = None
  def bool(x: Bool): Boolean = x.peek().litToBoolean
  def uint[T <: Data](x: T): BigInt = x.peek().litValue
  def record(k: String, p: BigInt = 0, r: Int = -1, data: BigInt = 0): Unit = events += Event(cycle,k,p,r,data)
  def reset(): Unit = {
    d.io.resetAddr.poke(0x400.U)
    d.io.machineTimerInterrupt.poke(false.B); d.io.machineSoftwareInterrupt.poke(false.B)
    d.io.time.poke(0.U); d.io.externalInterrupts.poke(0.U); d.io.supervisorExternalInterrupt.poke(false.B)
    d.io.fetchBuffer.valid.poke(false.B)
    d.io.hpmEvents.controlRetired.poke(false.B); d.io.hpmEvents.controlTaken.poke(false.B)
    d.io.hpmEvents.predictionMiss.poke(false.B); d.io.hpmEvents.icacheAccess.poke(false.B)
    d.io.hpmEvents.icacheMiss.poke(false.B); d.io.hpmEvents.dcacheAccess.poke(false.B)
    d.io.hpmEvents.dcacheMiss.poke(false.B); d.io.hpmEvents.dcacheUncached.poke(false.B)
    d.io.hpmEvents.memStallCycle.poke(false.B); d.io.hpmEvents.loadUseStall.poke(false.B)
    d.io.hpmEvents.mulSourceStall.poke(false.B); d.io.hpmEvents.divSourceStall.poke(false.B)
    d.io.hpmEvents.wbPortConflict.poke(0.U)
    d.io.mmuIdle.poke(true.B)
    d.io.l1d.req.ready.poke(true.B); d.io.l1d.s2Hold.poke(false.B)
    d.io.l1d.resp.valid.poke(false.B); d.io.l1d.resp.bits.kind.poke(L1DRespKind.Done)
    d.io.l1d.resp.bits.data.poke(0.U); d.io.l1d.resp.bits.excCause.poke(0.U); d.io.l1d.resp.bits.tval.poke(0.U)
    d.io.l1d.late.valid.poke(false.B); d.io.l1d.late.bits.rd.idx.poke(0.U)
    d.io.l1d.late.bits.rd.isFp.poke(false.B); d.io.l1d.late.bits.data.poke(0.U); d.io.l1d.late.bits.error.poke(false.B)
    d.io.l1d.drained.poke(true.B); d.io.l1d.mmioBusy.poke(false.B)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }
  def step(instruction: Option[(BigInt,BigInt)] = None): Boolean = {
    instruction match {
      case Some((p,i)) =>
        d.io.fetchBuffer.valid.poke(true.B); d.io.fetchBuffer.bits.pc.poke(p.U)
        d.io.fetchBuffer.bits.inst.poke(i.U); d.io.fetchBuffer.bits.rawInst.poke(i.U)
        d.io.fetchBuffer.bits.instLen.poke(4.U)
        d.io.fetchBuffer.bits.instructionAccessFault.poke(instructionAccessFault.B); d.io.fetchBuffer.bits.instructionPageFault.poke(instructionPageFault.B)
        d.io.fetchBuffer.bits.instructionFaultSecondParcel.poke(faultSecondParcel.B); d.io.fetchBuffer.bits.illegalCompressed.poke(false.B)
        d.io.fetchBuffer.bits.pred.predType.poke(if((i & 0x7f)==0x63) FrontendPredType.BR else if((i & 0x7f)==0x67) FrontendPredType.JALR else FrontendPredType.NONE)
        d.io.fetchBuffer.bits.pred.predTaken.poke(predictionTaken.B)
        d.io.fetchBuffer.bits.pred.predPc.poke(predictionTarget.getOrElse(p+4).U); d.io.fetchBuffer.bits.pred.phtIdx.poke(predictionIndex.U)
      case None => d.io.fetchBuffer.valid.poke(false.B)
    }
    d.io.mmuIdle.poke(mmuIdle.B); d.io.machineSoftwareInterrupt.poke(softwareInterrupt.B); d.io.machineTimerInterrupt.poke(timerInterrupt.B)
    val lateAt = pending.map { case (_,r) => (r+7) }
    val lateValid = lateAt.exists(cycle >= _)
    d.io.l1d.late.valid.poke(lateValid.B)
    pending.foreach { case (q,_) =>
      d.io.l1d.late.bits.rd.idx.poke(q.rd.U); d.io.l1d.late.bits.rd.isFp.poke(q.fp.B)
      d.io.l1d.late.bits.data.poke(lateData.getOrElse(values(q.addr)).U); d.io.l1d.late.bits.error.poke(lateError.B)
    }
    val blockedLine = s2.exists(q => pending.exists { case (a,r) =>
      (q.addr>>5)==(a.addr>>5) && cycle <= (r+7)
    })
    if (blockedLine && retryAt.isEmpty) retryAt = pending.map { case (_,r) => (r+7)+3 }
    val retry = s2.nonEmpty && retryAt.exists(cycle < _)
    val full = s2.exists(q => misses(q.addr) && pending.nonEmpty && !blockedLine)
    val fence = s2.exists(_.op == 5) && pending.nonEmpty
    val hold = blockedLine || retry || full || fence || (s2.nonEmpty && cycle < holdUntil) || (randomHold && s2.nonEmpty && rng.nextInt(4)==0)
    d.io.l1d.s2Hold.poke(hold.B)
    d.io.l1d.drained.poke((drained && pending.isEmpty && ps.isEmpty).B)
    d.io.l1d.resp.valid.poke((s2.nonEmpty && !hold).B)
    var allocated: Option[(Request,Int)] = None
    s2.foreach { q =>
      val miss = misses(q.addr) && pending.isEmpty && !faults(q.addr)
      d.io.l1d.resp.bits.kind.poke(if(faults(q.addr)) L1DRespKind.Exc else if(miss) L1DRespKind.Mshr else L1DRespKind.Done)
      d.io.l1d.resp.bits.data.poke(values(q.addr).U)
      d.io.l1d.resp.bits.excCause.poke(faultCause.U); d.io.l1d.resp.bits.tval.poke(q.addr.U)
      if (!hold && miss) allocated = Some(q -> (cycle+(if(randomHold) 1+rng.nextInt(40) else returnDelay)))
    }
    d.io.l1d.req.ready.poke((!hold).B)
    val reqAddr = uint(d.io.l1d.req.bits.vaddr)
    val read = uint(d.io.l1d.req.bits.op)==0 || uint(d.io.l1d.req.bits.op)==2
    val conflict = read && Seq(s1,s2,ps).flatten.exists(q => (q.op==1 || q.op==3 || q.op==4) &&
      ((q.addr>>3)&511)==((reqAddr>>3)&511))
    val installing = pending.exists { case (_,r) => cycle >= r+1 && cycle <= r+5 }
    d.io.l1d.req.ready.poke((!hold && !conflict && !installing).B)
    val accepted = instruction.nonEmpty && bool(d.io.fetchBuffer.ready)
    if(accepted) record("id", instruction.get._1)
    val o = d.io.observe
    if(bool(o.exFire)) record("ex",uint(o.exPc))
    if(bool(o.commit)) record("commit",uint(o.commitPc))
    if(bool(d.io.hartFatal)) record("fatal")
    if(bool(o.gprWrite.valid)) record("gpr",r=uint(o.gprWrite.bits.idx).toInt,data=uint(o.gprWrite.bits.data))
    if(bool(o.fprWrite.valid)) record("fpr",r=uint(o.fprWrite.bits.idx).toInt,data=uint(o.fprWrite.bits.data))
    if(bool(o.fpIn)) record("fpIn",uint(o.exPc))
    if(bool(o.fpOut)) record("fpOut")
    if(bool(o.fpFlags.valid)) record("flags",data=uint(o.fpFlags.bits))
    if(bool(o.mulIn)) record("mulIn",uint(o.exPc))
    if(bool(o.divIn)) record("divIn",uint(o.exPc))
    if(bool(o.divIterating)) record("iter")
    if(bool(o.memHold)) record("hold")
    if(bool(d.io.frontendBtbUpdate.valid)) record("btb",data=uint(d.io.frontendBtbUpdate.target))
    if(bool(d.io.frontendPhtUpdate.valid)) record("pht",r=uint(d.io.frontendPhtUpdate.idx).toInt,data=uint(d.io.frontendPhtUpdate.taken))
    if(bool(d.io.frontendGhrUpdate.valid)) record("ghr",data=uint(d.io.frontendGhrUpdate.taken))
    if(bool(d.io.sfence.valid)) record("sfence")
    if(bool(d.io.frontendRedirect.valid)) record("redirect",data=uint(d.io.frontendRedirect.target))
    if(bool(d.io.frontendRedirect.cacheFlush)) record("icacheFlush")
    val conflicts = uint(d.io.backendEvents.wbPortConflict).toInt
    for(_ <- 0 until conflicts) record("conflict")
    if(bool(d.io.translationBlocked)) record("xlatBlock")
    val fired = bool(d.io.l1d.req.valid) && bool(d.io.l1d.req.ready)
    val newReq = if(fired) Some(Request(reqAddr,uint(d.io.l1d.req.bits.rd.idx).toInt,
      bool(d.io.l1d.req.bits.rd.isFp),uint(d.io.l1d.req.bits.op).toInt,uint(d.io.l1d.req.bits.wdata))) else None
    if(fired) record("req",uint(o.exPc))
    if(s2.nonEmpty && !hold) record("resp",r=s2.get.rd,data=uint(d.io.l1d.resp.bits.kind))
    val lateFire = lateValid && bool(d.io.l1d.late.ready)
    if(lateFire) record("late",r=pending.get._1.rd)
    if(lateValid && !lateFire) { lateStored = true; if(lateData.isEmpty) lateData=Some(values(pending.get._1.addr)) }
    if(pending.exists(_._2==cycle)) record("rspData")
    val kill = bool(d.io.l1d.s2Kill)
    d.clock.step()
    ps = if(s2.exists(q=>q.op==1) && !hold && !kill) s2 else None
    if(kill) { s1=None; s2=None; retryAt=None }
    else if(!hold) { s2=s1; s1=newReq; retryAt=None }
    if(lateFire) { misses -= pending.get._1.addr; pending=None; lateStored=false; lateData=None }
    if(allocated.nonEmpty && !kill) { pending=allocated; misses -= allocated.get._1.addr }
    cycle += 1
    accepted
  }
  def issue(i: BigInt): BigInt = {
    val p=pc; pc+=4
    var count=0
    while(!step(Some(p->i))) { count+=1; require(count<300,s"issue timeout at $p seed=$seed") }
    p
  }
  def run(program: Seq[BigInt], tail: Int = 80): Seq[BigInt] = {
    val pcs=program.map(issue)
    for(_ <- 0 until tail) { issue(nop) }
    pcs
  }
  def at(k: String, p: BigInt): Int = events.find(e=>e.kind==k && e.pc==p).getOrElse(sys.error(s"missing $k $p seed=$seed")).cycle
  def writes(r: Int, fp: Boolean = false): Seq[Event] = events.filter(e=>e.kind==(if(fp) "fpr" else "gpr") && e.rd==r).toSeq
  def written(r: Int, fp: Boolean = false): Int = { require(writes(r,fp).size==1,s"writes $r = ${writes(r,fp)}"); writes(r,fp).head.cycle }
  def all(k: String): Seq[Int] = events.filter(_.kind==k).map(_.cycle).toSeq
  def enableFp(): Unit = {
    issue(addi(30,0,1)); issue((BigInt(13)<<20)|(BigInt(30)<<15)|(BigInt(1)<<12)|(BigInt(30)<<7)|0x13)
    issue(csr(0,0x300,30)); for(_ <- 0 until 5) issue(nop)
  }
}

class BackendContractSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  import Instructions._
  private def check(bypass: Boolean = false)(f: Environment => Unit): Unit =
    simulate(new BreezeBackend(BackendConfig(privilegeProfile=PrivilegeProfile.Linux, loadUseBypass=bypass), enabledebug=true)) { d =>
      val m=new Environment(d); m.reset(); f(m)
    }
  private def consecutive(cs: Seq[Int]): Unit = { cs.sliding(2).foreach(p=> if(p.size==2) p(1) mustBe p(0)+1) }
  "T01_ALU_EX_bypass" in { check() { m =>
    val p=m.run(Seq(addi(1,0,7),add(2,1,1)),10)
    m.at("ex",p(1)) mustBe m.at("ex",p(0))+1
    m.writes(2).head.data mustBe 14
  }}
  "T02_load_use_default" in { check() { m =>
    val p=m.run(Seq(ld(1,0),add(3,1)),10); val e=m.at("ex",p(0))
    m.at("req",p(0)) mustBe e; m.written(1) mustBe e+3; m.at("ex",p(1)) mustBe e+4
    m.at("commit",p(0)) mustBe e+2; m.at("id",p(1)) mustBe e+3
    m.all("resp").head mustBe e+2; m.writes(3).head.data mustBe 0x1234
  }}
  // T02b was retired by SOC-3b; loadUseBypass has no functional effect.
  "T03_independent_ALU_after_load" in { check() { m =>
    val p=m.run(Seq(ld(1,0),add(3)),10); m.at("ex",p(1)) mustBe m.at("ex",p(0))+1
  }}
  "T04_MUL_dependency" in { check() { m =>
    val p=m.run(Seq(mdu(1,0,0),add(2,1)),12); val e=m.at("ex",p(0))
    m.at("mulIn",p(0)) mustBe e; m.written(1) mustBe e+4; m.at("id",p(1)) mustBe e+4; m.at("ex",p(1)) mustBe e+5
  }}
  "T05_DIV_fast_dependency" in { check() { m =>
    val p=m.run(Seq(mdu(1,0,0,true),add(2,1)),12); val e=m.at("ex",p(0))
    m.written(1) mustBe e+3; m.at("ex",p(1)) mustBe e+4; m.writes(1).head.data mustBe mask
  }}
  "T06_DIV_actual_iterations" in { check() { m =>
    m.run(Seq(ld(10,0),addi(11,0,1)),4); m.values(0)=mask
    // use a separate register so the operand is the max unsigned value
    m.run(Seq(ld(12,0)),4)
    val p=m.run(Seq(mdu(1,12,11,true)),45); val e=m.at("ex",p.head)
    m.all("iter").size mustBe 32; m.written(1) mustBe e+2+m.all("iter").size
    m.writes(1).head.data mustBe mask
  }}
  "T07_two_DIVs_EX_wait" in { check() { m =>
    val p=m.run(Seq(mdu(1,0,0,true),mdu(2,0,0,true),add(3)),15)
    m.at("divIn",p(1)) mustBe m.written(1)+1
    m.at("id",p(2)) mustBe m.at("divIn",p(1))
  }}
  "T08_store_same_word_conflict" in { check() { m =>
    val p=m.run(Seq(sd(0),ld(1,0)),12); m.at("req",p(1)) mustBe m.at("ex",p(0))+4
  }}
  "T09_store_different_word" in { check() { m =>
    val p=m.run(Seq(sd(0),ld(1,8)),12); m.at("req",p(1)) mustBe m.at("ex",p(0))+1
  }}
  "T10_miss_WB_commit_sets_busy" in { check() { m =>
    m.misses += BigInt(0); val p=m.issue(ld(1,0)); m.issue(nop); m.issue(nop); m.issue(nop)
    val e=m.at("ex",p); m.all("resp").head mustBe e+2; m.at("commit",p) mustBe e+2
    m.d.io.observe.gprBusy.expect(2.U)
  }}
  "T11_refill_R_plus_7_dependency" in { check() { m =>
    m.misses += BigInt(0); val p=m.run(Seq(ld(1,0),add(2,1)),55)
    val r=m.all("rspData").head
    m.all("late") mustBe Seq(r+7); m.written(1) mustBe r+8; m.at("id",p(1)) mustBe r+8
  }}
  "T12_WB_wins_late_without_hold" in { check() { m =>
    m.returnDelay=0; m.misses += BigInt(0); m.issue(ld(1,0)); m.issue(nop); m.issue(nop)
    for(_ <- 0 until 4) m.issue(nop)
    val n=m.cycle+3
    val p=m.run(Seq(add(2),nop),12)
    m.at("commit",p.head) mustBe n; m.written(2) mustBe n+1; m.written(1) mustBe n+2
    m.all("late") mustBe Seq(n); m.all("hold") mustBe empty
    m.all("conflict") mustBe Seq(n+1)
  }}
  "T13_four_sources_fixed_priority" in { check() { m =>
    // DIV takes 32 real arithmetic cycles. MUL E+4 and CLASSIFY's
    // committed return E+3 are aligned to it; L1D late is received at N-1.
    m.enableFp(); m.values(0)=mask; m.run(Seq(ld(10,0),addi(11,0,1)),5)
    m.returnDelay=25; m.misses += BigInt(32); m.issue(ld(1,32))
    val div=m.issue(mdu(2,10,11,true))
    val e=m.cycle // DIV's EX is this cycle
    for(_ <- 0 until 29) m.issue(nop)
    val mul=m.issue(mdu(3,0,0))
    val classify=(BigInt(0x71)<<25)|(BigInt(1)<<12)|(BigInt(4)<<7)|0x53
    val fpPc=m.issue(classify)
    val n=e+34
    m.run(Seq.fill(8)(nop),35)
    val ws=(1 to 4).map(m.written(_)); ws mustBe Seq(n,n+1,n+2,n+3)
    m.at("commit",div) must be < m.written(2); m.at("fpIn",fpPc) mustBe m.at("ex",fpPc)
    m.at("mulIn",mul) mustBe m.at("ex",mul)
  }}
  "T14_FPU_direct_EX_fire" in { check() { m =>
    m.enableFp(); val p=m.run(Seq(fp(1)),20); m.at("fpIn",p.head) mustBe m.at("ex",p.head)
  }}
  "T15_FPU_direct_output_write" in { check() { m =>
    m.enableFp(); val p=m.run(Seq(fp(1),fp(2,toGpr=true)),25)
    m.all("fpOut") mustBe Seq(m.written(1,true),m.written(2)).sorted
  }}
  "T16_FPU_dependency_write_through" in { check() { m =>
    m.enableFp(); val p=m.run(Seq(fp(1),fp(2,1)),25)
    m.at("id",p(1)) mustBe m.written(1,true)
    m.written(1,true)-m.at("ex",p.head) mustBe m.all("fpOut").head-m.at("fpIn",p.head)
  }}
  "T17_CSR_raw_busy_fflags" in { check() { m =>
    m.enableFp(); val p=m.run(Seq(fp(1,div=true),csr(5,1)),70)
    m.at("id",p(1)) mustBe m.written(1,true)+1
    m.writes(5).head.data mustBe 16 // 0/0 -> NV
  }}
  "T18_FENCEI_drained_WB_flush" in { check() { m =>
    val p=m.run(Seq(BigInt(0x100f)),8); val wb=m.at("ex",p.head)+2
    m.all("redirect") mustBe Seq(wb); m.all("icacheFlush") mustBe Seq(wb); m.at("commit",p.head) mustBe wb
  }}
  "T19_FENCEI_wait_serial_ID" in { check() { m =>
    m.drained=false; val p=m.issue(BigInt(0x100f)); for(_ <- 0 until 9) m.step(Some(m.pc->ld(1,8)))
    val end=m.cycle; m.drained=true; m.run(Seq(ld(1,8)),10)
    m.all("redirect").head mustBe end; m.at("commit",p) mustBe end
    m.events.filter(e=>e.kind=="id" && e.cycle>m.at("id",p) && e.cycle<=end) mustBe empty
    m.all("req").filter(_<=end) mustBe empty
  }}
  "T20_SFENCE_two_idle_phases" in { check() { m =>
    m.mmuIdle=false; val p=m.issue(BigInt(0x12000073)); for(_ <- 0 until 7) m.step(Some(m.pc->ld(1,8)))
    val s=m.cycle; m.mmuIdle=true; m.step(Some(m.pc->ld(1,8))); m.mmuIdle=false
    for(_ <- 0 until 3) m.step(Some(m.pc->ld(1,8)))
    val r=m.cycle; m.mmuIdle=true; m.step(Some(m.pc->ld(1,8)))
    m.all("sfence") mustBe Seq(s); m.all("redirect") mustBe Seq(r)
    m.all("req") mustBe empty; m.events.filter(e=>e.kind=="id" && e.cycle>m.at("id",p)) mustBe empty
    m.all("xlatBlock") must contain allElementsOf (m.at("ex",p)+2 to r)
  }}
  "T21_ID_single_bubble_bounds_starvation" in { check() { m =>
    m.returnDelay=0; m.misses += BigInt(0); m.issue(ld(1,0)); for(_ <- 0 until 9) m.issue(add(20))
    val c=m.cycle; m.run(Seq.fill(16)(add(20)),15)
    m.all("late") mustBe Seq(c); m.written(1) mustBe c+8
    val leaves=m.all("id").filter(x=>x>=c && x<=c+8)
    leaves mustBe (c to c+8).filter(_!=c+4)
    m.all("conflict").filter(_>=c) mustBe (c+1 to c+7)
  }}
  "T22_B01_miss_ADD_hit_response_alignment" in { check() { m =>
    m.returnDelay=0; m.misses += BigInt(0); m.issue(ld(1,0)); m.issue(nop); m.issue(nop)
    for(_ <- 0 until 4) m.issue(nop)
    val n=m.cycle+3; val p=m.run(Seq(add(2),ld(3,64),nop),15)
    m.at("commit",p(0)) mustBe n; m.written(2) mustBe n+1
    m.at("commit",p(1)) mustBe n+1; m.written(3) mustBe n+2
    m.events.filter(e=>e.kind=="resp" && e.rd==3).map(_.cycle).toSeq mustBe Seq(n+1)
    m.all("late") mustBe Seq(n); m.written(1) mustBe n+3
    m.all("hold") mustBe empty; m.all("conflict") mustBe Seq(n+1,n+2)
  }}
  "P01_sixteen_loads_II1" in { check() { m =>
    val p=m.run((1 to 16).map(r=>ld(r,64+r*8)),10)
    consecutive(p.map(m.at("req",_))); consecutive(p.map(m.at("commit",_)))
  }}
  "P02_eight_MULs_II1" in { check() { m =>
    val p=m.run((1 to 8).map(r=>mdu(r,0,0)),20)
    consecutive(p.map(m.at("mulIn",_))); consecutive((1 to 8).map(m.written(_)))
    p.zipWithIndex.foreach { case (pc,i)=>m.written(i+1) mustBe m.at("ex",pc)+4 }
  }}
  "P03_DIV_with_twenty_ADDs" in { check() { m =>
    m.values(0)=mask; m.run(Seq(ld(10,0),addi(11,0,1)),5)
    val p=m.run(Seq(mdu(1,10,11,true))++Seq.fill(20)(add(20)),45)
    val c=p.tail.map(m.at("commit",_)); consecutive(c); c.last must be < m.written(1)
    m.all("iter").size must be >= 20
  }}
  "P04_hit_under_miss" in { check() { m =>
    m.misses += BigInt(0); val p=m.run(Seq(ld(1,0),ld(2,64),sd(96))++Seq.fill(10)(add(20)),55)
    val e=m.at("ex",p.head); m.at("commit",p(1)) mustBe e+3; m.at("commit",p(2)) mustBe e+4
    p.tail.map(m.at("commit",_)).max must be < m.all("late").head
  }}
  "P05_same_line_recheck_R_plus_10" in { check() { m =>
    m.misses += BigInt(0); val p=m.run(Seq(ld(1,0),ld(2,64),sd(96))++Seq.fill(10)(add(20))++Seq(ld(3,8)),55)
    m.at("commit",p.last) mustBe m.all("rspData").head+10
    m.all("hold") must not be empty
  }}
  "P06_three_background_sources_and_twenty_ALUs" in { check() { m =>
    m.enableFp(); m.values(256)=BigInt("3ff0000000000000",16); m.values(264)=BigInt("4008000000000000",16)
    m.run(Seq(ld(10,256,true),ld(11,264,true)),5)
    m.values(64)=mask; m.run(Seq(ld(10,64),addi(11,0,1)),5)
    m.misses += BigInt(0)
    val p=m.run(Seq(ld(1,0),mdu(2,10,11,true),fp(1,10,11,div=true))++Seq.fill(20)(add(20)),85)
    // Contract P06: (1) overlap, (2) gaps one cycle before x1/x2 background GPR writes, (3) one write each.
    val c=p.drop(3).map(m.at("commit",_))
    val bg=Seq(m.written(1),m.written(2),m.written(1,true))
    c.head must be < bg.min
    val gaps=(c.head to c.last).filterNot(c.contains).toSet
    gaps mustBe Seq(m.written(1),m.written(2)).map(_-1).filter(w=>w>c.head && w<c.last).toSet
    c.sliding(2).foreach(q=> if(q.size==2) (q(1)-q(0)) must be <= 2)
    m.d.io.observe.gprBusy.expect(0.U); m.d.io.observe.fprBusy.expect(0.U)
  }}
  "P07_FPU_out_of_order_flags_OR" in { check() { m =>
    m.enableFp()
    m.values(256)=BigInt("3ff0000000000000",16); m.values(264)=BigInt("4008000000000000",16)
    m.values(272)=BigInt("7ff0000000000000",16); m.values(280)=BigInt("fff0000000000000",16)
    m.run(Seq(ld(10,256,true),ld(11,264,true),ld(12,272,true),ld(13,280,true)),5)
    val p=m.run(Seq(fp(1,10,11,div=true),fp(2,12,13)),75)
    m.at("fpIn",p(1)) must be < m.written(1,true); m.written(2,true) must be < m.written(1,true)
    m.events.filter(_.kind=="flags").map(_.data).foldLeft(BigInt(0))(_|_) mustBe 17
  }}
  "P08_eight_FMADD_inputs_II1" in { check() { m =>
    m.enableFp(); val p=m.run((1 to 8).map(fma),25); consecutive(p.map(m.at("fpIn",_)))
  }}
  "P09_interrupt_does_not_wait_for_committed_DIV" in { check() { m =>
    m.values(0)=mask; m.run(Seq(ld(10,0),addi(11,0,1),addi(12,0,8),csr(0,0x304,12),csr(0,0x300,12)),6)
    val p=m.issue(mdu(1,10,11,true)); for(_ <- 0 until 3) m.step()
    val boundary=m.cycle; m.softwareInterrupt=true; m.step(); m.softwareInterrupt=false
    for(_ <- 0 until 45) m.step()
    m.all("redirect").head mustBe boundary; boundary must be < m.written(1)
  }}
  "P10_exception_kills_young_MUL_retains_DIV" in { check() { m =>
    m.values(0)=mask; m.run(Seq(ld(10,0),addi(11,0,1)),5); m.faults += BigInt(128)
    val p=m.run(Seq(mdu(5,10,11,true),ld(1,128),mdu(6,0,0)),45)
    val trap=m.at("ex",p(1))+2
    m.all("redirect").head mustBe trap; m.writes(6) mustBe empty
    m.writes(5).size mustBe 1; m.d.io.observe.gprBusy.expect(0.U)
  }}
  "S01_S16_random_s2Hold_LATE_and_stall_direction_seed_B01" in { check() { m =>
    m.randomHold=true; m.misses += BigInt(0); m.values(0)=19
    val p=m.run(Seq(ld(1,0))++(2 to 12).map(r=>ld(r,64+r*8))++Seq(add(13,1,2)),100)
    m.writes(13).head.data mustBe 19+0x1234
    (1 to 13).foreach(r=>m.writes(r).size mustBe 1)
    m.d.io.observe.gprBusy.expect(0.U)
  }}
  "S02b_compatibility_parameter_miss_preserves_dependency" in { check(true) { m =>
    m.misses += BigInt(0); m.values(0)=23
    val p=m.run(Seq(ld(1,0),add(2,1,1),add(3,2)),65)
    m.writes(2).head.data mustBe 46; m.writes(3).head.data mustBe 46
    m.written(2) must be > m.written(1)
    m.all("hold") mustBe empty
    m.at("id",p(1)) mustBe m.written(1)
  }}
  "S01_S08_f0_and_cross_bank_RAW_WAW" in { check() { m =>
    m.enableFp(); m.values(256)=BigInt("3ff0000000000000",16)
    val p=m.run(Seq(ld(0,256,true), fp(1,0,0), (BigInt(0x61)<<25)|(BigInt(1)<<15)|(BigInt(2)<<7)|0x53,
      (BigInt(0x69)<<25)|(BigInt(2)<<15)|(BigInt(2)<<20)|(BigInt(2)<<7)|0x53),40)
    m.writes(0,true).size mustBe 1; m.writes(1,true).head.data mustBe BigInt("4000000000000000",16)
    m.writes(2).head.data mustBe 2; m.writes(2,true).head.data mustBe BigInt("4000000000000000",16)
    m.d.io.observe.gprBusy.expect(0.U); m.d.io.observe.fprBusy.expect(0.U)
  }}

  "S05_S08_fatal_late_stops_hart_without_trap_retains_committed_DIV" in { check() { m =>
    m.values(0)=mask; m.run(Seq(ld(10,0),addi(11,0,1)),5)
    m.returnDelay=0; m.misses += BigInt(64)
    m.issue(mdu(5,10,11,true)); m.issue(ld(1,64)); m.issue(nop); m.issue(nop)
    for(_ <- 0 until 4) m.issue(nop)
    m.lateError=true
    val n=m.cycle+3
    val ordinary=m.issue(addi(20,0,29))
    for(_ <- 0 until 3) m.step(Some(m.pc -> addi(21,0,31)))
    for(_ <- 0 until 50) m.step()
    m.all("late") mustBe Seq(n); m.all("fatal").head mustBe n+1
    m.at("commit",ordinary) mustBe n; m.written(20) mustBe n+1
    m.writes(20).head.data mustBe 29; m.writes(21) mustBe empty
    m.all("commit").filter(_>n) mustBe empty; m.all("id").filter(_>n) mustBe empty
    m.d.io.hartFatal.expect(true.B); m.d.io.fetchBuffer.ready.expect(false.B)
    m.all("redirect") mustBe empty; m.writes(1) mustBe empty; m.writes(5).size mustBe 1
    m.d.io.observe.gprBusy.expect(0.U)
  }}

  "SOC3b_held_EX_captures_MEM_WB_W2_and_youngest_value" in {
    // Reuse one elaboration; a second DIV waits in EX while its ordinary
    // producer traverses MEM/WB/W2 and disappears. Vary their separation.
    simulate(new BreezeBackend(BackendConfig(privilegeProfile=PrivilegeProfile.Linux), enabledebug=true)) { d =>
      for(gap <- 0 to 2) {
        val m=new Environment(d); m.reset()
        m.values(0)=mask; m.run(Seq(ld(10,0),addi(11,0,1)),5)
        val p=m.run(Seq(mdu(5,10,11,true),addi(12,0,10),addi(12,0,21)) ++
          Seq.fill(gap)(nop) ++ Seq(mdu(6,12,11,true)),85)
        withClue(s"producer gap=$gap: ") {
          m.at("divIn",p.last) must be > m.at("id",p.last)+3
          m.writes(12).map(_.data).toSeq mustBe Seq(BigInt(10),BigInt(21))
          m.writes(6).map(_.data).toSeq mustBe Seq(BigInt(21))
          m.writes(5).map(_.data).toSeq mustBe Seq(mask)
          m.d.io.observe.gprBusy.expect(0.U)
        }
      }
    }
  }

  "S09_BTB_training_waits_for_held_WB_once" in { check() { m =>
    m.holdUntil=8
    m.issue(ld(1,0)); m.issue(BigInt(0x463)) // beq x0,x0,+8
    for(_ <- 0 until 12) m.step()
    m.all("btb") mustBe Seq(8); m.all("pht") mustBe Seq(2); m.all("ghr") mustBe Seq(2)
    m.all("btb").intersect(m.all("hold")) mustBe empty
  }}
  "S13_WB_fault_discards_younger_pending_BTB_training" in { check() { m =>
    m.holdUntil=8; m.faults += BigInt(0)
    m.issue(ld(1,0)); m.issue(BigInt(0x463))
    for(_ <- 0 until 12) m.step()
    m.all("btb") mustBe empty
    m.all("redirect").last mustBe 8
    m.d.io.observe.gprBusy.expect(0.U)
  }}

}
