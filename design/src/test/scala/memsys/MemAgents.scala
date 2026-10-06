package flow.memsys

import chisel3._
import flow.bus._
import flow.coherence._
import flow.interface._
import flow.mmu.sv39.PtwMemIO
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

import MemTestKit._

// =============================================================================
// CPU side of the L1D
// =============================================================================

/** One backend request. `data` is the store value in its low bytes. */
case class CoreOp(
    op: L1DOp.Type,
    addr: BigInt,
    size: Int = 3,
    signed: Boolean = false,
    data: BigInt = 0,
    rd: Int = 1,
    isFlw: Boolean = false,
    expectExc: Option[Int] = None,
    device: Boolean = false,
    refillError: Boolean = false,
    s1Kill: Boolean = false,
    s2KillAtResp: Boolean = false) {
  override def toString: String =
    s"${op.litValue.toInt match { case 0 => "Load"; case 1 => "Store"; case 5 => "Fence"; case x => s"op$x" }}" +
      s"(${hex(addr)}, size $size${if (op.litValue == 1) ", data " + hex(data) else ""})"
}

object CoreOp {
  def load(a: BigInt, size: Int = 3, signed: Boolean = false, rd: Int = 1): CoreOp =
    CoreOp(L1DOp.Load, a, size, signed, rd = rd)
  def store(a: BigInt, data: BigInt, size: Int = 3): CoreOp = CoreOp(L1DOp.Store, a, size, data = data)
  def fence: CoreOp = CoreOp(L1DOp.Fence, 0)
}

class CoreTxn(val id: Int, val op: CoreOp, val fired: Long) {
  var respCycle = -1L
  var kind = ""
  var value: BigInt = 0
  var expected: BigInt = 0
  var lateCycle = -1L
  var killed = false
  var done = false
  override def toString: String = s"#$id $op fired $fired resp $respCycle $kind late $lateCycle" +
    (if (killed) " killed" else "")
}

/** Drives L1DCoreIO like the backend: a presented request stays until
  * accepted, responses arrive in program order, loads that miss finish on
  * `late`. Loads are checked against `golden` at their response; stores
  * update `golden` at their response (the commit point).
  */
class CoreDriver(core: L1DCoreIO, golden: GoldenMem, device: Option[AxiLiteDevice], rng: Random)
    extends CycleAgent {
  val name = "core"
  val pending = mutable.Queue.empty[CoreOp]
  val inPipe = mutable.Queue.empty[CoreTxn]
  val awaitingLate = mutable.Queue.empty[CoreTxn]
  val history = ArrayBuffer.empty[CoreTxn]
  var issueProb = 1.0
  var lateReadyProb = 1.0
  var holdCycles = 0L
  var mmioBusyCycles = 0L
  private var nextId = 0
  private var presenting: Option[CoreOp] = None
  private var lateReady = true
  private var respValid = false
  private var s1Victim: Option[CoreTxn] = None
  private var s2KillNow = false

  def enqueue(ops: CoreOp*): Unit = pending ++= ops
  def last: CoreTxn = history.last
  def drained: Boolean = core.drained.peek().litToBoolean

  protected def drive(): Unit = {
    lateReady = rng.nextDouble() < lateReadyProb
    core.late.ready.poke(lateReady.B)
    if (presenting.isEmpty && pending.nonEmpty && rng.nextDouble() < issueProb) presenting = Some(pending.dequeue())
    core.req.valid.poke(presenting.nonEmpty.B)
    presenting.foreach { o =>
      val b = core.req.bits
      b.op.poke(o.op); b.vaddr.poke(o.addr.U(64.W)); b.size.poke(o.size.U)
      b.signed.poke(o.signed.B); b.wdata.poke((o.data & mask(64)).U(64.W))
      b.rd.isFp.poke(false.B); b.rd.idx.poke(o.rd.U); b.isFlw.poke(o.isFlw.B)
      b.aq.poke(false.B); b.rl.poke(false.B); b.amoFunc.poke(BreezeAmoFunc.Swap)
    }
    core.s1Kill.poke(false.B)
    core.s2Kill.poke(false.B)
  }

  override def decide(): Unit = {
    // A request accepted in the previous cycle is in S1 now.
    s1Victim = inPipe.lastOption.filter(t => t.op.s1Kill && t.fired == now - 1)
    respValid = core.resp.valid.peek().litToBoolean
    s2KillNow = respValid && inPipe.headOption.exists(_.op.s2KillAtResp)
    core.s1Kill.poke(s1Victim.nonEmpty.B)
    core.s2Kill.poke(s2KillNow.B)
  }

  def sample(): Unit = {
    val fired = presenting.nonEmpty && core.req.ready.peek().litToBoolean
    if (s1Victim.nonEmpty || s2KillNow) check(!fired, "request accepted in a kill cycle")
    if (core.s2Hold.peek().litToBoolean) holdCycles += 1
    if (core.mmioBusy.peek().litToBoolean) mmioBusyCycles += 1
    if (respValid) {
      check(inPipe.nonEmpty, "resp without an outstanding request")
      val t = inPipe.dequeue()
      t.respCycle = now
      val r = core.resp.bits
      val k = r.kind.peek().litValue
      val kind = if (k == L1DRespKind.Done.litValue) "Done" else if (k == L1DRespKind.Mshr.litValue) "Mshr" else "Exc"
      if (s2KillNow) {
        t.kind = kind; t.killed = true
        inPipe.foreach(_.killed = true); inPipe.clear()
      } else respond(t, kind, r.data.peek().litValue, r.excCause.peek().litValue, r.tval.peek().litValue)
      progress()
    }
    s1Victim.foreach { v =>
      if (inPipe.exists(_ eq v)) { v.killed = true; inPipe.dequeueAll(_ eq v) }
      progress()
    }
    if (fired) {
      val t = new CoreTxn(nextId, presenting.get, now)
      nextId += 1; inPipe.enqueue(t); history += t; presenting = None; progress()
    }
    val lateValid = core.late.valid.peek().litToBoolean
    if (lateValid) check(awaitingLate.nonEmpty, "late data without an outstanding load miss")
    if (lateValid && lateReady) {
      val t = awaitingLate.dequeue()
      t.lateCycle = now
      val err = core.late.bits.error.peek().litToBoolean
      check(core.late.bits.rd.idx.peek().litValue == t.op.rd, s"late rd for $t")
      check(!core.late.bits.rd.isFp.peek().litToBoolean, s"late isFp for $t")
      if (t.op.refillError) check(err, s"$t: refill error not reported on late")
      else {
        check(!err, s"$t: unexpected late error")
        t.value = core.late.bits.data.peek().litValue
        check(t.value == t.expected, s"$t: late ${hex(t.value)}, expected ${hex(t.expected)}")
      }
      t.done = true
      progress()
    }
  }

  private def respond(t: CoreTxn, kind: String, data: BigInt, cause: BigInt, tval: BigInt): Unit = {
    t.kind = kind
    t.op.expectExc match {
      case Some(c) =>
        check(kind == "Exc", s"$t: expected exception $c, got $kind")
        check(cause == c, s"$t: cause $cause, expected $c")
        check(tval == t.op.addr, s"$t: tval ${hex(tval)}")
        t.done = true
      case None =>
        check(kind != "Exc", s"$t: unexpected exception, cause $cause")
        if (enumIs(t.op.op, L1DOp.Load)) {
          val exp = expectedLoad(t.op)
          if (kind == "Done") {
            t.value = data
            check(data == exp, s"$t: data ${hex(data)}, expected ${hex(exp)}")
            t.done = true
          } else {
            check(!t.op.device, s"$t: device load reported Mshr")
            t.expected = exp
            awaitingLate.enqueue(t)
          }
        } else if (enumIs(t.op.op, L1DOp.Store)) {
          if (t.op.device) check(kind == "Done", s"$t: device store must complete as Done")
          else if (!t.op.refillError) golden.write(t.op.addr, 1 << t.op.size, t.op.data & mask(8 << t.op.size), now)
          t.done = true
        } else {
          check(kind == "Done", s"$t must complete as Done")
          t.done = true
        }
    }
  }

  def expectedLoad(o: CoreOp): BigInt = {
    val raw =
      if (o.device) device.get.word(o.addr) >> (8 * (o.addr & 7).toInt)
      else golden.read(o.addr, if (o.isFlw) 4 else 1 << o.size)
    formatLoad(raw, o.size, o.signed, o.isFlw)
  }

  // A store miss responds before install/replay/PS have completed (§6.2).
  // The interface's drained indication, rather than a fixed delay, closes it.
  def idle: Boolean = pending.isEmpty && presenting.isEmpty && inPipe.isEmpty && awaitingLate.isEmpty &&
    drained && !core.mmioBusy.peek().litToBoolean
  override def describe: String =
    s"core: pending ${pending.size}, presenting $presenting, inPipe ${inPipe.mkString("; ")}, " +
      s"awaiting late ${awaitingLate.mkString("; ")}, drained $drained"
}

class TlbKnobs(io: TlbKnobIO, rng: Random) extends CycleAgent {
  val name = "tlb"
  var missProb = 0.0
  var busyProb = 0.0
  var missUntil = -1L
  var busyUntil = -1L
  var pageFault = false
  protected def drive(): Unit = {
    io.tlbMiss.poke((now < missUntil || rng.nextDouble() < missProb).B)
    io.tlbReady.poke((!(now < busyUntil || rng.nextDouble() < busyProb)).B)
    io.tlbPageFault.poke(pageFault.B)
  }
  def sample(): Unit = ()
  def idle = true
  def requests: BigInt = io.tlbRequests.peek().litValue
}

/** PTW reads through the L1D entry (one outstanding, like the PTW). */
class PtwDriver(ptw: PtwMemIO, golden: GoldenMem) extends CycleAgent {
  val name = "ptw"
  val pending = mutable.Queue.empty[(BigInt, Boolean)]
  val results = ArrayBuffer.empty[(BigInt, BigInt, Boolean)]
  private var presenting: Option[(BigInt, Boolean)] = None
  private var waiting: Option[(BigInt, Boolean)] = None
  def read(paddr: BigInt, expectFault: Boolean = false): Unit = pending.enqueue((paddr, expectFault))
  protected def drive(): Unit = {
    if (presenting.isEmpty && waiting.isEmpty && pending.nonEmpty) presenting = Some(pending.dequeue())
    ptw.req.valid.poke(presenting.nonEmpty.B)
    ptw.req.bits.paddr.poke(presenting.map(_._1).getOrElse(BigInt(0)).U(56.W))
  }
  def sample(): Unit = {
    if (ptw.resp.valid.peek().litToBoolean) {
      check(waiting.nonEmpty, "PTW response without a request")
      val (a, fault) = waiting.get
      val f = ptw.resp.bits.accessFault.peek().litToBoolean
      val d = ptw.resp.bits.data.peek().litValue
      check(f == fault, s"PTW ${hex(a)}: accessFault $f, expected $fault")
      if (!fault) check(d == golden.read(a & ~BigInt(7), 8), s"PTW ${hex(a)}: ${hex(d)}, golden ${hex(golden.read(a & ~BigInt(7), 8))}")
      results += ((a, d, f)); waiting = None; progress()
    }
    if (presenting.nonEmpty && ptw.req.ready.peek().litToBoolean) { waiting = presenting; presenting = None; progress() }
  }
  def idle: Boolean = pending.isEmpty && presenting.isEmpty && waiting.isEmpty
  override def describe: String = s"ptw: pending ${pending.size}, presenting $presenting, waiting $waiting"
}

// =============================================================================
// Behavioral L2 seen from one L1D (four links)
// =============================================================================

/** Single-core L2 model obeying coherence-l2-rtl-spec section 1. It tracks
  * what the L1D holds, answers Get/Put, injects probes and checks every
  * L1D-side protocol rule. `backing` receives dirty data.
  */
class BehavioralL2(coh: L1DCoherenceIO, val backing: GoldenMem, rng: Random) extends CycleAgent {
  val name = "l2-model"
  var reqReadyProb = 1.0
  var rspUpReadyProb = 1.0
  var minLatency = 1
  var maxLatency = 1
  var randomProbeProb = 0.0
  /** GetS of these lines is granted DataS. */
  val sharedLines = mutable.Set.empty[BigInt]
  /** The next grant of these lines carries error=1. */
  val errorLines = mutable.Set.empty[BigInt]
  /** Send this probe in the cycle the line's grant is delivered. */
  val probeAfterGrant = mutable.Map.empty[BigInt, SnpOp.Type]
  /** GetM from S: invalidate the sharer copy first, then grant DataE. */
  val upgradeRace = mutable.Set.empty[BigInt]
  var beforeRacedGrant: (BigInt, Long) => Unit = (_, _) => ()

  /** L1D copies as L2 sees them: 'S' or 'E' (E includes silent M). */
  val held = mutable.Map.empty[BigInt, Char]
  val gets = ArrayBuffer.empty[(Long, String, BigInt)]
  val grants = ArrayBuffer.empty[(Long, String, BigInt)]
  val probes = ArrayBuffer.empty[(Long, String, BigInt)]
  val puts = ArrayBuffer.empty[(Long, BigInt, Boolean)]
  val acks = ArrayBuffer.empty[(Long, String, BigInt, Boolean)]

  private case class Rsp(due: Long, op: RspDownOp.Type, data: BigInt, error: Boolean, line: BigInt)
  private case class Snp(op: SnpOp.Type, owner: Boolean, line: BigInt)
  private val rspQ = mutable.Queue.empty[Rsp]
  private val snpQ = mutable.Queue.empty[Snp]
  private var snpOut: Option[Snp] = None
  private var getOut: Option[BigInt] = None
  private var putOut: Option[BigInt] = None
  private var deferred: Option[BigInt] = None
  private var reqReady = false
  private var upReady = false
  private var rspSent = false
  private var snpSent = false
  private var stalledReq: Option[(BigInt, BigInt, BigInt, BigInt, BigInt)] = None
  private var stalledUp: Option[(BigInt, Boolean, BigInt, BigInt)] = None

  private val rspNames = Seq(RspDownOp.DataS -> "DataS", RspDownOp.DataE -> "DataE", RspDownOp.AckE -> "AckE",
    RspDownOp.PutAck -> "PutAck", RspDownOp.ReadData -> "ReadData", RspDownOp.WriteAck -> "WriteAck")
  private def rspName(op: RspDownOp.Type): String = rspNames.find(_._1.litValue == op.litValue).get._2

  def probe(op: SnpOp.Type, line: BigInt): Unit = {
    val owner = held.get(line).contains('E')
    if (enumIs(op, SnpOp.Down)) require(owner, "Down is only sent to an owner")
    snpQ.enqueue(Snp(op, owner, line))
  }
  def getsOf(line: BigInt): Seq[String] = gets.filter(_._3 == line).map(_._2).toSeq
  def grantsOf(line: BigInt): Seq[String] = grants.filter(_._3 == line).map(_._2).toSeq
  private def latency = minLatency + rng.nextInt(maxLatency - minLatency + 1)
  private def schedule(op: RspDownOp.Type, data: BigInt, error: Boolean, line: BigInt): Unit =
    rspQ.enqueue(Rsp(now + latency, op, data, error, line))

  protected def drive(): Unit = {
    reqReady = rng.nextDouble() < reqReadyProb
    upReady = rng.nextDouble() < rspUpReadyProb
    coh.req.ready.poke(reqReady.B)
    coh.rspUp.ready.poke(upReady.B)
    val r = rspQ.headOption.filter(_.due <= now)
    rspSent = r.nonEmpty
    coh.rspDown.valid.poke(rspSent.B)
    r.foreach { x =>
      coh.rspDown.bits.op.poke(x.op); coh.rspDown.bits.id.poke(0.U)
      coh.rspDown.bits.error.poke(x.error.B); coh.rspDown.bits.data.poke(x.data.U(256.W))
    }
    // Drive SNP before this edge, alongside the grant, not after sampling it.
    r.filterNot(x => enumIs(x.op, RspDownOp.PutAck)).foreach { x =>
      probeAfterGrant.remove(x.line).foreach { op =>
        check(snpOut.isEmpty && snpQ.isEmpty, "same-cycle grant/probe requested while SNP is busy")
        probe(op, x.line)
      }
    }
    if (snpOut.isEmpty && snpQ.isEmpty && randomProbeProb > 0 && rng.nextDouble() < randomProbeProb) {
      val candidates = held.keys.filter(l => !getOut.contains(l) && !deferred.contains(l)).toSeq.sorted
      if (candidates.nonEmpty) {
        val l = candidates(rng.nextInt(candidates.size))
        probe(if (held(l) == 'E' && rng.nextBoolean()) SnpOp.Down else SnpOp.Inv, l)
      }
    }
    snpSent = snpOut.isEmpty && snpQ.nonEmpty
    coh.snp.valid.poke(snpSent.B)
    snpQ.headOption.foreach { s =>
      coh.snp.bits.op.poke(s.op); coh.snp.bits.owner.poke(s.owner.B); coh.snp.bits.addr.poke(s.line.U)
    }
  }

  def sample(): Unit = {
    if (rspSent) {
      check(coh.rspDown.ready.peek().litToBoolean, "L1D must always accept RSPdown")
      val r = rspQ.dequeue()
      if (enumIs(r.op, RspDownOp.PutAck)) putOut = None
      else {
        getOut = None
        grants += ((now, rspName(r.op), r.line))
      }
      progress()
    }
    if (snpSent && coh.snp.ready.peek().litToBoolean) {
      val s = snpQ.dequeue()
      snpOut = Some(s)
      probes += ((now, if (enumIs(s.op, SnpOp.Inv)) "Inv" else "Down", s.line))
      progress()
    }

    if (coh.req.valid.peek().litToBoolean) {
      val q = coh.req.bits
      val sig = (q.op.peek().litValue, q.addr.peek().litValue, q.id.peek().litValue,
        q.mask.peek().litValue, q.data.peek().litValue)
      stalledReq.foreach(s => check(s == sig, "REQ changed before acceptance"))
      check(coh.req.bits.id.peek().litValue == 0, "L1D REQ id must be 0")
      if (reqReady) { stalledReq = None; handleReq(sig._1, sig._2); progress() }
      else stalledReq = Some(sig)
    } else check(stalledReq.isEmpty, "REQ withdrawn before acceptance")

    if (coh.rspUp.valid.peek().litToBoolean) {
      val u = coh.rspUp.bits
      val sig = (u.op.peek().litValue, u.hasData.peek().litToBoolean, u.addr.peek().litValue, u.data.peek().litValue)
      stalledUp.foreach(s => check(s == sig, "RSPup changed before acceptance"))
      if (upReady) { stalledUp = None; handleUp(sig._1, sig._2, sig._3, sig._4); progress() }
      else stalledUp = Some(sig)
    } else check(stalledUp.isEmpty, "RSPup withdrawn before acceptance")
  }

  private def handleReq(op: BigInt, line: BigInt): Unit = {
    val isGetM = op == ReqOp.GetM.litValue
    check(isGetM || op == ReqOp.GetS.litValue, s"L1D sent REQ op $op")
    gets += ((now, if (isGetM) "GetM" else "GetS", line))
    check(getOut.isEmpty, s"Get ${hex(line)} while ${getOut.map(hex)} is outstanding")
    check(!putOut.contains(line), s"Get ${hex(line)} while its Put is unacknowledged")
    getOut = Some(line)
    held.get(line) match {
      case Some('E') => check(false, s"Get ${hex(line)} from its owner")
      case Some(_) =>
        check(isGetM, s"GetS ${hex(line)} from a sharer")
        if (upgradeRace.remove(line)) { snpQ.enqueue(Snp(SnpOp.Inv, owner = false, line)); deferred = Some(line) }
        else { held(line) = 'E'; schedule(RspDownOp.AckE, 0, error = false, line) }
      case None =>
        grant(line, if (!isGetM && sharedLines(line)) RspDownOp.DataS else RspDownOp.DataE)
    }
  }

  private def grant(line: BigInt, op: RspDownOp.Type): Unit = {
    if (errorLines.remove(line)) schedule(op, BigInt("dead", 16), error = true, line)
    else {
      held(line) = if (enumIs(op, RspDownOp.DataS)) 'S' else 'E'
      schedule(op, backing.line(line), error = false, line)
    }
  }

  private def handleUp(op: BigInt, hasData: Boolean, line: BigInt, data: BigInt): Unit = {
    if (op == RspUpOp.Put.litValue) {
      puts += ((now, line, hasData))
      check(putOut.isEmpty, "second Put while one is unacknowledged")
      check(held.contains(line), s"Put ${hex(line)} for a line the L1D does not hold")
      check(!hasData || held(line) == 'E', s"dirty Put ${hex(line)} from a sharer")
      if (hasData) backing.writeLine(line, data, now)
      held -= line
      putOut = Some(line)
      schedule(RspDownOp.PutAck, 0, error = false, line)
    } else {
      val inv = op == RspUpOp.InvAck.litValue
      check(inv || op == RspUpOp.DownAck.litValue, s"RSPup op $op")
      acks += ((now, if (inv) "InvAck" else "DownAck", line, hasData))
      check(snpOut.nonEmpty, s"probe answer ${hex(line)} without a probe")
      val s = snpOut.get
      check(s.line == line, s"probe answer for ${hex(line)}, probe was ${hex(s.line)}")
      check(inv == enumIs(s.op, SnpOp.Inv), "probe answer opcode mismatch")
      check(!hasData || s.owner, s"probe data ${hex(line)} from a sharer")
      if (hasData) backing.writeLine(line, data, now)
      if (held.contains(line)) { if (inv) held -= line else held(line) = 'S' }
      snpOut = None
      if (deferred.contains(line)) {
        deferred = None
        beforeRacedGrant(line, now)
        grant(line, RspDownOp.DataE)
      }
    }
  }

  /** Invalidate every L1D copy so `backing` holds all dirty data. */
  def flushAll(bench: Bench): Unit = {
    randomProbeProb = 0
    bench.quiesce()
    for (l <- held.keys.toSeq.sorted) probe(SnpOp.Inv, l)
    bench.quiesce()
    check(held.isEmpty, s"lines still held after flush: ${held.keys.map(hex).mkString(", ")}")
  }
  def compareWith(arch: GoldenMem): Unit =
    for (l <- (arch.touched ++ backing.touched).toSeq.sorted)
      check(backing.line(l) == arch.line(l),
        s"line ${hex(l << 5)}: memory ${hex(backing.line(l))}, golden ${hex(arch.line(l))}")

  def idle: Boolean = rspQ.isEmpty && snpQ.isEmpty && snpOut.isEmpty && getOut.isEmpty && putOut.isEmpty &&
    deferred.isEmpty && stalledReq.isEmpty && stalledUp.isEmpty
  override def describe: String =
    s"l2-model: get ${getOut.map(hex)}, put ${putOut.map(hex)}, probe $snpOut, queued probes ${snpQ.size}, " +
      s"responses ${rspQ.size}, deferred ${deferred.map(hex)}, held ${held.size}"
}

// =============================================================================
// AXI4-Lite device and AXI4 memory
// =============================================================================

class AxiLiteDevice(axi: Axi4LiteMasterIO, rng: Random, seed: Int) extends CycleAgent {
  val name = "mmio"
  val regs = new GoldenMem(seed)
  /** 8 B words that answer SLVERR (writes to them are dropped). */
  val errorWords = mutable.Set.empty[BigInt]
  var latency = 2
  /** (R/W, address, data, strobe) in acceptance order. */
  val log = ArrayBuffer.empty[(String, BigInt, BigInt, Int)]
  private var rPending: Option[(Long, BigInt, Int)] = None
  private var bPending: Option[(Long, Int)] = None
  private var aw: Option[BigInt] = None
  private var w: Option[(BigInt, Int)] = None
  private var arReady, awReady, wReady, rValid, bValid = false
  def word(a: BigInt): BigInt = regs.read(a & ~BigInt(7), 8)

  protected def drive(): Unit = {
    arReady = rPending.isEmpty
    awReady = aw.isEmpty && bPending.isEmpty
    wReady = w.isEmpty && bPending.isEmpty
    axi.ar.ready.poke(arReady.B); axi.aw.ready.poke(awReady.B); axi.w.ready.poke(wReady.B)
    rValid = rPending.exists(_._1 <= now)
    bValid = bPending.exists(_._1 <= now)
    axi.r.valid.poke(rValid.B)
    axi.r.bits.data.poke(rPending.map(_._2).getOrElse(BigInt(0)).U(64.W))
    axi.r.bits.resp.poke(rPending.map(_._3).getOrElse(0).U(2.W))
    axi.b.valid.poke(bValid.B)
    axi.b.bits.poke(bPending.map(_._2).getOrElse(0).U(2.W))
  }
  def sample(): Unit = {
    if (arReady && axi.ar.valid.peek().litToBoolean) {
      val a = axi.ar.bits.addr.peek().litValue
      check(axi.ar.bits.prot.peek().litValue == 0, "ARPROT must be 0")
      log += (("R", a, word(a), 0xff))
      rPending = Some((now + latency, word(a), if (errorWords(a & ~BigInt(7))) 2 else 0))
      progress()
    }
    if (awReady && axi.aw.valid.peek().litToBoolean) {
      check(axi.aw.bits.prot.peek().litValue == 0, "AWPROT must be 0")
      aw = Some(axi.aw.bits.addr.peek().litValue); progress()
    }
    if (wReady && axi.w.valid.peek().litToBoolean) {
      w = Some((axi.w.bits.data.peek().litValue, axi.w.bits.strb.peek().litValue.toInt)); progress()
    }
    if (rValid && axi.r.ready.peek().litToBoolean) { rPending = None; progress() }
    if (bValid && axi.b.ready.peek().litToBoolean) { bPending = None; progress() }
    (aw, w) match {
      case (Some(a), Some((d, s))) =>
        val base = a & ~BigInt(7)
        val err = errorWords(base)
        if (!err) for (i <- 0 until 8 if ((s >> i) & 1) == 1) regs.write(base + i, 1, d >> (8 * i), now)
        log += (("W", a, d, s))
        bPending = Some((now + latency, if (err) 2 else 0))
        aw = None; w = None
      case _ =>
    }
  }
  def idle: Boolean = rPending.isEmpty && bPending.isEmpty && aw.isEmpty && w.isEmpty
}

/** AXI4 memory slave for the L2 memory engine: in-order line reads,
  * independent AW/W, optional SLVERR lines and random backpressure.
  */
class AxiMemory(axi: Axi4MasterIO, val mem: GoldenMem, rng: Random) extends CycleAgent {
  val name = "axi-mem"
  private val Beats = 4
  var minLatency = 2
  var maxLatency = 6
  var arReadyProb = 1.0
  var awReadyProb = 1.0
  var wReadyProb = 1.0
  var rValidProb = 1.0
  var bValidProb = 1.0
  val errorLines = mutable.Set.empty[BigInt]
  val reads = ArrayBuffer.empty[(Long, BigInt)]
  val writes = ArrayBuffer.empty[(Long, BigInt, BigInt)]
  var maxReadsInFlight = 0
  private class Rd(val due: Long, val id: BigInt, val line: BigInt, val data: BigInt, val error: Boolean) {
    var beat = 0
  }
  private val rq = mutable.Queue.empty[Rd]
  private val awq = mutable.Queue.empty[(BigInt, BigInt)]
  private val wbeats = ArrayBuffer.empty[BigInt]
  private val bq = mutable.Queue.empty[(Long, BigInt)]
  private var arReady, awReady, wReady, rValid, bValid = false
  private def latency = minLatency + rng.nextInt(maxLatency - minLatency + 1)
  def readsOf(line: BigInt): Int = reads.count(_._2 == line)
  def writesOf(line: BigInt): Seq[BigInt] = writes.filter(_._2 == line).map(_._3).toSeq

  protected def drive(): Unit = {
    arReady = rng.nextDouble() < arReadyProb
    awReady = rng.nextDouble() < awReadyProb
    wReady = rng.nextDouble() < wReadyProb
    axi.ar.ready.poke(arReady.B); axi.aw.ready.poke(awReady.B); axi.w.ready.poke(wReady.B)
    val r = rq.headOption.filter(_.due <= now)
    // Random bubbles are allowed before asserting VALID; once asserted,
    // VALID and the head beat remain held until READY accepts them.
    rValid = rValid || (r.nonEmpty && rng.nextDouble() < rValidProb)
    axi.r.valid.poke(rValid.B)
    r.foreach { x =>
      axi.r.bits.id.poke(x.id.U); axi.r.bits.data.poke(((x.data >> (64 * x.beat)) & mask(64)).U(64.W))
      axi.r.bits.resp.poke((if (x.error && x.beat == 1) 2 else 0).U(2.W)); axi.r.bits.last.poke((x.beat == Beats - 1).B)
    }
    val b = bq.headOption.filter(_._1 <= now)
    bValid = bValid || (b.nonEmpty && rng.nextDouble() < bValidProb)
    axi.b.valid.poke(bValid.B)
    b.foreach { x => axi.b.bits.id.poke(x._2.U); axi.b.bits.resp.poke(0.U) }
  }

  private def checkBurst(a: Axi4Ar, what: String): BigInt = {
    val addr = a.addr.peek().litValue
    check(a.len.peek().litValue == Beats - 1 && a.size.peek().litValue == 3 && a.burst.peek().litValue == 1,
      s"$what burst shape")
    check((addr & (LineBytes - 1)) == 0, s"$what address ${hex(addr)} not line aligned")
    addr
  }

  def sample(): Unit = {
    if (arReady && axi.ar.valid.peek().litToBoolean) {
      val l = checkBurst(axi.ar.bits, "AR") >> 5
      reads += ((now, l))
      rq.enqueue(new Rd(now + latency, axi.ar.bits.id.peek().litValue, l, mem.line(l), errorLines(l)))
      maxReadsInFlight = maxReadsInFlight max rq.size
      progress()
    }
    if (rValid && axi.r.ready.peek().litToBoolean) {
      rValid = false
      val x = rq.head
      x.beat += 1
      if (x.beat == Beats) rq.dequeue()
      progress()
    }
    if (awReady && axi.aw.valid.peek().litToBoolean) {
      awq.enqueue((axi.aw.bits.id.peek().litValue, checkBurst(axi.aw.bits, "AW"))); progress()
    }
    if (wReady && axi.w.valid.peek().litToBoolean) {
      check(axi.w.bits.strb.peek().litValue == 0xff, "partial WSTRB on a line writeback")
      wbeats += axi.w.bits.data.peek().litValue
      check(axi.w.bits.last.peek().litToBoolean == (wbeats.size == Beats), "WLAST position")
      progress()
    }
    if (bValid && axi.b.ready.peek().litToBoolean) { bValid = false; bq.dequeue(); progress() }
    if (awq.nonEmpty && wbeats.size == Beats) {
      val (id, addr) = awq.dequeue()
      val data = wbeats.zipWithIndex.map { case (d, i) => d << (64 * i) }.reduce(_ | _)
      mem.writeLine(addr >> 5, data, now)
      writes += ((now, addr >> 5, data))
      wbeats.clear()
      bq.enqueue((now + latency, id))
    }
  }
  def idle: Boolean = rq.isEmpty && awq.isEmpty && wbeats.isEmpty && bq.isEmpty
  override def describe: String = s"axi-mem: reads ${rq.size}, aw ${awq.size}, w beats ${wbeats.size}, b ${bq.size}"
}

// =============================================================================
// L2 clients: L1I/DMA read clients and a Scala L1D
// =============================================================================

case class ClientReq(op: ReqOp.Type, line: BigInt, mask: BigInt = 0, data: BigInt = 0,
    expectError: Boolean = false, check: Boolean = true)

object ClientReq {
  def read(line: BigInt, check: Boolean = true): ClientReq = ClientReq(ReqOp.Read, line, check = check)
  def write(line: BigInt, mask: BigInt, data: BigInt): ClientReq = ClientReq(ReqOp.MaskWrite, line, mask, data)
}

/** L1I (up to two Reads, ids 0/1) or DMA (one request) client. Reads are
  * checked against some value the line held while the read was outstanding;
  * MaskWrite updates `arch` at its WriteAck.
  */
class ReadClientAgent(port: ReadClientIO, val name: String, arch: GoldenMem, rng: Random, maxInFlight: Int)
    extends CycleAgent {
  val pending = mutable.Queue.empty[ClientReq]
  var issueProb = 1.0
  val results = ArrayBuffer.empty[(Long, ClientReq, BigInt, Boolean)]
  private case class Out(req: ClientReq, id: Int, start: Long)
  private var presenting: Option[Out] = None
  private val inFlight = ArrayBuffer.empty[Out]

  protected def drive(): Unit = {
    if (presenting.isEmpty && pending.nonEmpty && inFlight.size < maxInFlight && rng.nextDouble() < issueProb) {
      val free = (0 until maxInFlight).find(i => !inFlight.exists(_.id == i)).get
      presenting = Some(Out(pending.dequeue(), free, now))
    }
    port.req.valid.poke(presenting.nonEmpty.B)
    presenting.foreach { o =>
      port.req.bits.op.poke(o.req.op); port.req.bits.addr.poke(o.req.line.U); port.req.bits.id.poke(o.id.U)
      port.req.bits.mask.poke(o.req.mask.U(32.W)); port.req.bits.data.poke(o.req.data.U(256.W))
    }
    port.rspDown.ready.poke(true.B)
  }
  def sample(): Unit = {
    if (port.rspDown.valid.peek().litToBoolean) {
      val r = port.rspDown.bits
      val id = r.id.peek().litValue.toInt
      val op = r.op.peek().litValue
      val err = r.error.peek().litToBoolean
      val data = r.data.peek().litValue
      val o = inFlight.find(_.id == id)
      check(o.nonEmpty, s"response id $id without a request")
      val q = o.get.req
      inFlight -= o.get
      if (enumIs(q.op, ReqOp.Read)) {
        check(op == RspDownOp.ReadData.litValue, s"Read ${hex(q.line << 5)} answered with op $op")
        check(err == q.expectError, s"Read ${hex(q.line << 5)}: error $err, expected ${q.expectError}")
        if (!err && q.check)
          check(arch.heldDuring(q.line, o.get.start, now, data),
            s"Read ${hex(q.line << 5)} returned ${hex(data)}; golden ${hex(arch.line(q.line))}")
      } else {
        check(op == RspDownOp.WriteAck.litValue, s"MaskWrite ${hex(q.line << 5)} answered with op $op")
        check(!err, "WriteAck carries no error")
        arch.writeMasked(q.line, q.mask, q.data, now)
      }
      results += ((now, q, data, err))
      progress()
    }
    if (presenting.nonEmpty && port.req.ready.peek().litToBoolean) {
      inFlight += presenting.get; presenting = None; progress()
    }
  }
  def idle: Boolean = pending.isEmpty && presenting.isEmpty && inFlight.isEmpty
  override def describe: String = s"$name: pending ${pending.size}, presenting $presenting, in flight $inFlight"
}

/** A Scala L1D on the four links, obeying the L1D rules the L2 relies on:
  * one Get and one Put in flight, probes held while the line's grant or
  * PutAck is outstanding, sharer copies invalidated at once during an
  * upgrade. Local copies always equal `arch`; stores update `arch`.
  */
class L1DProxy(port: L1DCoherenceIO, arch: GoldenMem, rng: Random) extends CycleAgent {
  val name = "l1d-proxy"
  sealed trait Act
  case class Acquire(line: BigInt, write: Boolean) extends Act
  case class Store(line: BigInt, offset: Int, value: BigInt, size: Int) extends Act
  case class Evict(line: BigInt) extends Act

  val script = mutable.Queue.empty[Act]
  var randomLines: Seq[BigInt] = Nil
  var randomProb = 0.0
  var answerDelayMax = 0
  val expectError = mutable.Set.empty[BigInt]
  val lines = mutable.Map.empty[BigInt, (Char, BigInt)]
  val grants = ArrayBuffer.empty[(Long, String, BigInt)]
  val probes = ArrayBuffer.empty[(Long, String, BigInt, Boolean)]

  private case class GetReq(op: ReqOp.Type, line: BigInt, start: Long, after: Option[Store])
  private case class Probe(op: SnpOp.Type, owner: Boolean, line: BigInt, readyAt: Long)
  private var get: Option[GetReq] = None
  private var getAccepted = false
  private var put: Option[(BigInt, Boolean, BigInt)] = None
  private var putAccepted = false
  private var probe: Option[Probe] = None
  private var answer: Option[(RspUpOp.Type, Boolean, BigInt, BigInt)] = None
  private var upChoice: Option[Boolean] = None // true: probe answer, false: Put
  private var upPresented: Option[Boolean] = None
  private var snpReady = false

  def acquire(line: BigInt, write: Boolean = false): Unit = script.enqueue(Acquire(line, write))
  def store(line: BigInt, offset: Int, value: BigInt, size: Int = 3): Unit = script.enqueue(Store(line, offset, value, size))
  def evict(line: BigInt): Unit = script.enqueue(Evict(line))
  def state(line: BigInt): Char = lines.get(line).map(_._1).getOrElse('I')

  private def busyLine(l: BigInt): Boolean = put.exists(_._1 == l) || get.exists(_.line == l) ||
    probe.exists(_.line == l) || answer.exists(_._3 == l)

  private def doStore(s: Store): Unit = {
    val (st, data) = lines(s.line)
    check(st == 'E' || st == 'M', s"store to ${hex(s.line << 5)} in state $st")
    check(data == arch.line(s.line), s"local copy of ${hex(s.line << 5)} diverged from golden")
    val bits = 8 << s.size
    val sh = 8 * s.offset
    val v = s.value & mask(bits)
    lines(s.line) = ('M', (data & ~(mask(bits) << sh)) | (v << sh))
    arch.write((s.line << 5) + s.offset, 1 << s.size, v, now)
  }

  /** Try to start `a`; false means it must wait. */
  private def start(a: Act): Boolean = a match {
    case Acquire(l, write) =>
      if (busyLine(l)) false
      else {
        val st = state(l)
        if (st == 'E' || st == 'M' || (st == 'S' && !write)) true
        else if (get.nonEmpty) false
        else {
          get = Some(GetReq(if (write) ReqOp.GetM else ReqOp.GetS, l, now, None)); getAccepted = false; true
        }
      }
    case s @ Store(l, _, _, _) =>
      if (busyLine(l)) false
      else {
        val st = state(l)
        if (st == 'E' || st == 'M') { doStore(s); true }
        else if (get.nonEmpty) false
        else { get = Some(GetReq(ReqOp.GetM, l, now, Some(s))); getAccepted = false; true }
      }
    case Evict(l) =>
      if (state(l) == 'I') true
      else if (busyLine(l) || put.nonEmpty) false
      else {
        val (st, data) = lines(l)
        check(data == arch.line(l), s"local copy of ${hex(l << 5)} diverged from golden")
        put = Some((l, st == 'M', data)); putAccepted = false
        lines -= l
        true
      }
  }

  private def randomAct(): Act = {
    val l = randomLines(rng.nextInt(randomLines.size))
    rng.nextInt(10) match {
      case x if x < 4 => Acquire(l, write = false)
      case x if x < 8 =>
        val size = rng.nextInt(4)
        Store(l, rng.nextInt(LineBytes >> size) << size, BigInt(64, rng), size)
      case _ => Evict(l)
    }
  }

  protected def drive(): Unit = {
    // Probe: held while this line's grant or PutAck is outstanding, except a
    // sharer Inv during an upgrade, which invalidates S at once.
    probe.foreach { pr =>
      val st = state(pr.line)
      val upgradeInv = enumIs(pr.op, SnpOp.Inv) && !pr.owner && st == 'S' && getAccepted &&
        get.exists(g => g.line == pr.line && enumIs(g.op, ReqOp.GetM))
      val held = !upgradeInv && (get.exists(_.line == pr.line) || put.exists(_._1 == pr.line))
      if (answer.isEmpty && now >= pr.readyAt && !held) {
        if (st == 'E' || st == 'M') check(pr.owner, s"probe ${hex(pr.line << 5)} to an owner with owner=0")
        if (st == 'S') check(!pr.owner, s"probe ${hex(pr.line << 5)} to a sharer with owner=1")
        val data = lines.get(pr.line).map(_._2).getOrElse(BigInt(0))
        if (st != 'I') check(data == arch.line(pr.line), s"local copy of ${hex(pr.line << 5)} diverged from golden")
        val hasData = st == 'M'
        if (enumIs(pr.op, SnpOp.Inv)) { lines -= pr.line; answer = Some((RspUpOp.InvAck, hasData, pr.line, data)) }
        else {
          if (st != 'I') lines(pr.line) = ('S', data)
          answer = Some((RspUpOp.DownAck, hasData, pr.line, data))
        }
        probe = None
      }
    }
    if (script.nonEmpty) { if (start(script.head)) script.dequeue() }
    else if (randomLines.nonEmpty && rng.nextDouble() < randomProb) start(randomAct())

    port.req.valid.poke((get.nonEmpty && !getAccepted).B)
    get.foreach { g =>
      port.req.bits.op.poke(g.op); port.req.bits.addr.poke(g.line.U); port.req.bits.id.poke(0.U)
      port.req.bits.mask.poke(0.U); port.req.bits.data.poke(0.U)
    }
    val choice = upChoice.orElse(if (answer.nonEmpty) Some(true) else if (put.nonEmpty && !putAccepted) Some(false) else None)
    upPresented = choice
    port.rspUp.valid.poke(choice.nonEmpty.B)
    choice.foreach { isAnswer =>
      val u = port.rspUp.bits
      if (isAnswer) {
        val (op, hasData, l, data) = answer.get
        u.op.poke(op); u.hasData.poke(hasData.B); u.addr.poke(l.U); u.data.poke(data.U(256.W))
      } else {
        val (l, hasData, data) = put.get
        u.op.poke(RspUpOp.Put); u.hasData.poke(hasData.B); u.addr.poke(l.U); u.data.poke(data.U(256.W))
      }
    }
    snpReady = probe.isEmpty && answer.isEmpty
    port.snp.ready.poke(snpReady.B)
    port.rspDown.ready.poke(true.B)
  }

  def sample(): Unit = {
    if (get.nonEmpty && !getAccepted && port.req.ready.peek().litToBoolean) { getAccepted = true; progress() }
    upPresented.foreach { isAnswer =>
      if (port.rspUp.ready.peek().litToBoolean) {
        if (isAnswer) answer = None else putAccepted = true
        upChoice = None
        progress()
      } else upChoice = Some(isAnswer)
    }
    if (snpReady && port.snp.valid.peek().litToBoolean) {
      val s = port.snp.bits
      val op = if (s.op.peek().litValue == SnpOp.Inv.litValue) SnpOp.Inv else SnpOp.Down
      val owner = s.owner.peek().litToBoolean
      val l = s.addr.peek().litValue
      probes += ((now, if (enumIs(op, SnpOp.Inv)) "Inv" else "Down", l, owner))
      probe = Some(Probe(op, owner, l, now + 1 + (if (answerDelayMax > 0) rng.nextInt(answerDelayMax + 1) else 0)))
      progress()
    }
    if (port.rspDown.valid.peek().litToBoolean) {
      val r = port.rspDown.bits
      val op = r.op.peek().litValue
      val err = r.error.peek().litToBoolean
      val data = r.data.peek().litValue
      if (op == RspDownOp.PutAck.litValue) {
        check(put.nonEmpty && putAccepted, "PutAck without an accepted Put")
        put = None
      } else {
        check(get.nonEmpty && getAccepted, s"grant op $op without an accepted Get")
        val g = get.get
        val isGetM = enumIs(g.op, ReqOp.GetM)
        val kind = if (op == RspDownOp.DataS.litValue) "DataS" else if (op == RspDownOp.DataE.litValue) "DataE"
          else if (op == RspDownOp.AckE.litValue) "AckE" else s"op$op"
        grants += ((now, kind, g.line))
        check(kind != "DataS" || !isGetM, s"GetM ${hex(g.line << 5)} granted DataS")
        check(kind == "DataS" || kind == "DataE" || kind == "AckE", s"L1D received $kind")
        if (err) {
          check(expectError.remove(g.line), s"unexpected grant error for ${hex(g.line << 5)}")
          check(kind != "AckE", "AckE carries no error")
        } else {
          check(!expectError.contains(g.line), s"grant for ${hex(g.line << 5)} should carry an error")
          if (kind == "AckE") {
            check(isGetM && state(g.line) == 'S', s"AckE for ${hex(g.line << 5)} in state ${state(g.line)}")
            lines(g.line) = ('E', lines(g.line)._2)
          } else {
            check(state(g.line) == 'I', s"$kind for ${hex(g.line << 5)} while holding ${state(g.line)}")
            check(arch.heldDuring(g.line, g.start, now, data),
              s"$kind ${hex(g.line << 5)}: ${hex(data)}, golden ${hex(arch.line(g.line))}")
            lines(g.line) = (if (kind == "DataS") 'S' else 'E', data)
          }
          g.after.foreach(doStore)
        }
        get = None
      }
      progress()
    }
  }

  def idle: Boolean = script.isEmpty && randomProb == 0 && get.isEmpty && put.isEmpty && probe.isEmpty && answer.isEmpty
  override def describe: String =
    s"l1d-proxy: script ${script.size}, get $get accepted $getAccepted, put ${put.map(p => hex(p._1))}, probe $probe, answer ${answer.map(a => hex(a._3))}"
}
