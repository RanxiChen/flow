package flow.memsys

import chisel3._
import chisel3.simulator.PeekPokeAPI._
import flow.interface.{BreezeAmoFunc, L1DOp}
import flow.l1d.L1DCache
import scala.collection.mutable

/** One backend memory operation. */
final case class MemOp(op: Int, addr: BigInt, size: Int = 3, signed: Boolean = false, wdata: BigInt = 0, rd: Int = 0,
                       tag: Long = 0) {
  def word: BigInt = addr >> 3
  def off: Int = (addr & 7).toInt
  def byteMask: Int = (((1 << (1 << size)) - 1) << off) & 0xff
  def isLoad: Boolean = op == OpK.Load
  def isStore: Boolean = op == OpK.Store
}
object OpK { val Load = 0; val Store = 1; val LR = 2; val SC = 3; val AMO = 4; val Fence = 5 }

object LoadFmt {
  def apply(word: BigInt, o: MemOp): BigInt = {
    val bits = 8 << o.size
    val v = (word >> (8 * o.off)) & ((BigInt(1) << bits) - 1)
    if (o.signed && bits < 64 && v.testBit(bits - 1)) v - (BigInt(1) << bits) + (BigInt(1) << 64) else v
  }
}

/** Drives one L1DCache through the frozen L1DCoreIO with a backend lockstep model,
  * a behavioural Home on its coherence links, an identity dTLB and an MMIO slave.
  *
  * Backend lockstep model (B01): an accepted request is in MEM next cycle and in
  * WB the cycle after. A request in WB sees resp.valid or s2Hold every cycle;
  * anything else means S1/S2 and MEM/WB have slipped, and the bench fails.
  */
final class L1DBench(dut: L1DCache, val seed: Long, val words: Seq[BigInt] = Nil) {
  val p = dut.p
  val rng = new Rng(seed)
  def initWord(w: BigInt): BigInt = (w * BigInt("9e3779b97f4a7c15", 16) + 0x1234) & ((BigInt(1) << 64) - 1)
  val golden = new GoldenWords(initWord)
  def goldenLine(l: BigInt): BigInt =
    (0 until p.wordsPerLine).map(i => golden.current((l << 2) + i) << (64 * i)).reduce(_ | _)
  def initLine(l: BigInt): BigInt = (0 until p.wordsPerLine).map(i => initWord((l << 2) + i) << (64 * i)).reduce(_ | _)
  val wd = new Watchdog(limit = 2000, idleLimit = 300)
  var cycle = 0L

  // ---------------- event log for directed timing checks ----------------
  final case class Ev(cycle: Long, kind: String, op: Option[MemOp] = None, data: BigInt = 0)
  val events = mutable.ArrayBuffer.empty[Ev]
  def at(kind: String): Seq[Long] = events.filter(_.kind == kind).map(_.cycle).toSeq

  // ---------------- backend model ----------------
  val program = mutable.Queue.empty[MemOp]
  private var memSlot: Option[(MemOp, Long)] = None
  private var wbSlot: Option[(MemOp, Long)] = None
  private val pendingLate = mutable.Map.empty[Int, (MemOp, Int)] // rd -> (op, golden version at commit)
  var lateReadyP = 1.0
  var committed = 0

  // ---------------- behavioural Home ----------------
  val home = new HomeModel(this)

  // ---------------- dTLB (identity, hit, resp next cycle) and MMIO slave ----------------
  private var tlbResp: Option[BigInt] = None
  private var mmioR: Option[(BigInt, Long)] = None
  private var mmioAw, mmioW = false
  private var mmioB: Option[Long] = None
  def mmioData(a: BigInt): BigInt = (a * 3 + 0x55) & ((BigInt(1) << 64) - 1)
  val mmioWrites = mutable.ArrayBuffer.empty[(BigInt, BigInt, Int)]
  private var mmioAddrW = BigInt(0)

  def reset(): Unit = { dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B) }

  def dump: String =
    (Seq(s"seed=$seed cycle=$cycle mem=$memSlot wb=$wbSlot late=${pendingLate.keys}", home.dump) ++
      events.takeRight(30).map(e => s"  [${e.cycle}] ${e.kind} ${e.op.getOrElse("")}")).mkString("\n")

  private def pokeReq(o: MemOp): Unit = {
    val b = dut.io.core.req.bits
    b.op.poke(L1DOp.all(o.op)); b.vaddr.poke(o.addr.U); b.size.poke(o.size.U); b.signed.poke(o.signed.B)
    b.amoFunc.poke(BreezeAmoFunc.Swap); b.aq.poke(false.B); b.rl.poke(false.B)
    b.wdata.poke(o.wdata.U) // register value, unshifted; L1D aligns it (PS)
    b.rd.isFp.poke(false.B); b.rd.idx.poke(o.rd.U); b.isFlw.poke(false.B)
  }

  private var nextTag = 0L
  private def freeRd(): Int = (1 until 32).find(r => !pendingLate.contains(r)).get

  def step(): Unit = {
    val io = dut.io
    // ---------------- drive ----------------
    io.core.s1Kill.poke(false.B); io.core.s2Kill.poke(false.B); io.core.trapClearRsv.poke(false.B)
    io.core.csr.privilege.poke(3.U); io.core.csr.satp.poke(0.U); io.core.csr.mprv.poke(false.B)
    io.core.csr.mpp.poke(3.U); io.core.csr.sum.poke(false.B); io.core.csr.mxr.poke(false.B); io.core.csr.adue.poke(false.B)
    io.core.csr.pmpcfg.foreach(_.poke(0.U)); io.core.csr.pmpaddr.foreach(_.poke(0.U))
    program.headOption match {
      case Some(o) => io.core.req.valid.poke(true.B); pokeReq(o)
      case None => io.core.req.valid.poke(false.B)
    }
    val lateReady = rng.chance(lateReadyP)
    io.core.late.ready.poke(lateReady.B)

    io.tlb.req.ready.poke(true.B)
    tlbResp match {
      case Some(pa) =>
        io.tlb.resp.valid.poke(true.B); io.tlb.resp.bits.hit.poke(true.B); io.tlb.resp.bits.miss.poke(false.B)
        io.tlb.resp.bits.pageFault.poke(false.B); io.tlb.resp.bits.accessFault.poke(false.B); io.tlb.resp.bits.paddr.poke(pa.U)
      case None => io.tlb.resp.valid.poke(false.B)
    }
    io.ptw.req.valid.poke(false.B); io.ptw.req.bits.paddr.poke(0.U)

    val m = io.mmio
    m.ar.ready.poke(mmioR.isEmpty.B); m.aw.ready.poke((!mmioAw && mmioB.isEmpty).B); m.w.ready.poke((!mmioW && mmioB.isEmpty).B)
    mmioR.filter(_._2 <= cycle) match {
      case Some((a, _)) => m.r.valid.poke(true.B); m.r.bits.data.poke(mmioData(a).U); m.r.bits.resp.poke(0.U)
      case None => m.r.valid.poke(false.B)
    }
    mmioB.filter(_ <= cycle) match {
      case Some(_) => m.b.valid.poke(true.B); m.b.bits.poke(0.U)
      case None => m.b.valid.poke(false.B)
    }
    home.drive(dut.io.coh)

    // ---------------- sample ----------------
    var progress = false
    val reqFire = Bind.fired(io.core.req)
    val respV = io.core.resp.valid.peek().litToBoolean
    val respKind = io.core.resp.bits.kind.peek().litValue.toInt
    val respData = io.core.resp.bits.data.peek().litValue
    val hold = io.core.s2Hold.peek().litToBoolean
    val lateF = Bind.fired(io.core.late)
    val lateRd = io.core.late.bits.rd.idx.peek().litValue.toInt
    val lateData = io.core.late.bits.data.peek().litValue
    val lateErr = io.core.late.bits.error.peek().litToBoolean
    val tlbF = Bind.fired(io.tlb.req)
    val tlbVa = io.tlb.req.bits.vaddr.peek().litValue
    val arF = Bind.fired(m.ar); val arA = m.ar.bits.addr.peek().litValue
    val rF = Bind.fired(m.r)
    val awF = Bind.fired(m.aw); val awA = m.aw.bits.addr.peek().litValue
    val wF = Bind.fired(m.w); val wD = m.w.bits.data.peek().litValue; val wS = m.w.bits.strb.peek().litValue.toInt
    val bF = Bind.fired(m.b)
    val homeObs = home.sample(dut.io.coh)

    // ---------------- lockstep check and backend stage update ----------------
    val wbAdvance = wbSlot match {
      case None =>
        if (respV) throw new AssertionError(s"resp.valid with no request in WB at cycle $cycle\n$dump")
        true
      case Some((o, _)) =>
        if (respV) { onResp(o, respKind, respData); true }
        else if (hold) false
        else throw new AssertionError(s"lockstep: $o in WB with neither resp nor s2Hold at cycle $cycle\n$dump")
    }
    if (reqFire && memSlot.nonEmpty && !wbAdvance) throw new AssertionError(s"req fired while MEM is held at cycle $cycle\n$dump")
    if (wbAdvance) { wbSlot = memSlot; memSlot = None }
    if (reqFire) {
      val o = program.dequeue(); memSlot = Some((o, cycle)); events += Ev(cycle, "fire", Some(o)); progress = true
    }
    if (lateF) { onLate(lateRd, lateData, lateErr); progress = true }
    if (respV) progress = true

    dut.clock.step()
    cycle += 1

    tlbResp = if (tlbF) Some(tlbVa & ((BigInt(1) << p.paddrBits) - 1)) else None
    if (arF) { mmioR = Some((arA, cycle + 3)); progress = true }
    if (rF) { mmioR = None; progress = true }
    if (awF) { mmioAw = true; mmioAddrW = awA }
    if (wF) { mmioW = true; mmioWrites += ((mmioAddrW, wD, wS)) }
    if (mmioAw && mmioW && mmioB.isEmpty) { mmioB = Some(cycle + 2); mmioAw = false; mmioW = false }
    if (bF) { mmioB = None; progress = true }
    if (home.observe(homeObs)) progress = true
    if (progress) wd.progress(cycle)
    wd.tick(cycle, dump)
    home.act()
  }

  private def onResp(o: MemOp, kind: Int, data: BigInt): Unit = {
    events += Ev(cycle, Seq("done", "mshr", "exc")(kind), Some(o), data)
    committed += 1
    wd.finish(s"op${o.tag}")
    kind match {
      case 0 => // Done
        if (o.isLoad && !isMmio(o.addr)) {
          val want = LoadFmt(golden.current(o.word), o)
          if (data != want) throw new AssertionError(f"load $o returned 0x$data%x, want 0x$want%x at cycle $cycle\n$dump")
        }
        if (o.isStore && !isMmio(o.addr)) golden.store(o.word, o.wdata << (8 * o.off), o.byteMask)
      case 1 => // Mshr: committed; Load data comes as late
        // A store miss is globally performed when the line arrives (the Home applies it then).
        if (o.isLoad) { pendingLate(o.rd) = (o, golden.version(o.word)); wd.start(s"late${o.rd}", cycle) }
        else if (o.isStore) pendingStores.getOrElseUpdate(o.word >> 2, mutable.Queue.empty) += o
      case _ => throw new AssertionError(s"unexpected exception for $o at cycle $cycle\n$dump")
    }
  }

  private def onLate(rd: Int, data: BigInt, err: Boolean): Unit = {
    val (o, ver) = pendingLate.remove(rd).getOrElse(throw new AssertionError(s"late for idle rd $rd at cycle $cycle\n$dump"))
    events += Ev(cycle, "late", Some(o), data)
    wd.finish(s"late$rd")
    if (err) throw new AssertionError(s"unexpected late.error for $o")
    val allowed = (ver to golden.version(o.word)).map(v => LoadFmt(golden.valueAt(o.word, v), o))
    if (!allowed.contains(data)) throw new AssertionError(f"late load $o returned 0x$data%x, not any value since commit at cycle $cycle\n$dump")
  }
  /** Store misses committed but not yet performed, by line. */
  val pendingStores = mutable.Map.empty[BigInt, mutable.Queue[MemOp]]
  def performStores(line: BigInt): Unit = for (q <- pendingStores.remove(line); o <- q)
    golden.store(o.word, o.wdata << (8 * o.off), o.byteMask)

  def isMmio(a: BigInt): Boolean = a >= 0x12000000L && a < 0x13000000L

  // ---------------- program helpers ----------------
  def issue(o: MemOp): MemOp = {
    nextTag += 1
    val r = (if (o.isLoad && o.rd == 0) o.copy(rd = freeRd()) else o).copy(tag = nextTag)
    program.enqueue(r); wd.start(s"op${r.tag}", cycle); r
  }
  def load(a: BigInt, size: Int = 3, signed: Boolean = false): MemOp = issue(MemOp(OpK.Load, a, size, signed))
  def store(a: BigInt, v: BigInt, size: Int = 3): MemOp = issue(MemOp(OpK.Store, a, size, wdata = v & ((BigInt(1) << (8 << size)) - 1)))
  def fence(): MemOp = issue(MemOp(OpK.Fence, 0))

  def run(n: Int): Unit = for (_ <- 0 until n) step()
  def runUntil(what: String, max: Int = 500)(cond: => Boolean): Int = {
    var n = 0
    while (!cond) { if (n >= max) throw new AssertionError(s"timeout waiting for $what\n$dump"); step(); n += 1 }
    n
  }
  def idle: Boolean = program.isEmpty && memSlot.isEmpty && wbSlot.isEmpty && pendingLate.isEmpty
  /** Tag initialisation takes `sets` cycles (l1d-rtl-spec §2). */
  def waitInit(): Unit = run(p.sets + 4)

  /** Random backend traffic over `addrs` (8 B aligned). */
  def randomTraffic(n: Int, addrs: Seq[BigInt]): Unit = {
    var issued = 0
    while (issued < n || !idle) {
      if (issued < n && program.size < 2 && rng.chance(0.7)) {
        val a = rng.pick(addrs); val size = rng.int(4); val off = rng.int(8 >> size) << size
        val addr = a + off
        val k = rng.int(20)
        if (k < 9) load(addr, size, rng.chance(0.5))
        else if (k < 18) store(addr, rng.bits(64), size)
        else fence()
        issued += 1
      }
      step()
      if (cycle > 200000) throw new AssertionError(s"random traffic did not finish\n$dump")
    }
  }
}

/** Behavioural Home for one L1D: directory of what the L1D holds, a memory, and a
  * virtual second core that steals lines with Inv/Down and then writes or reads them.
  */
final class HomeModel(b: L1DBench) {
  private val p = b.p
  val mem = mutable.Map.empty[BigInt, BigInt]
  def line(l: BigInt): BigInt = mem.getOrElse(l, b.initLine(l))
  /** What the L1D holds per directory: S or E (E covers E/M; the L1D may be M silently). */
  val dir = mutable.Map.empty[BigInt, LState.Value]
  var minLat = 3; var maxLat = 15
  var stealP = 0.0
  private var reqReady = true
  private var getInFlight: Option[(Req, Long)] = None   // accepted, response at cycle
  private val rspQ = mutable.Queue.empty[(RspDown, Long)]
  private var putAck: Option[Long] = None
  private var snpOut: Option[Snp] = None
  private var snpSent = false
  private var lastGrant: Option[BigInt] = None
  val log = mutable.ArrayBuffer.empty[String]
  /** Cycles at which RSP↓ messages were delivered, by op. */
  val delivered = mutable.ArrayBuffer.empty[(Long, Int)]
  val received = mutable.ArrayBuffer.empty[(Long, String)]

  def dump: String = s"home dir=${dir.map { case (l, s) => f"0x$l%x:$s" }.mkString(",")} get=$getInFlight snp=$snpOut sent=$snpSent putAck=$putAck"

  private def next: Option[RspDown] = rspQ.headOption.filter(_._2 <= b.cycle).map(_._1)

  def drive(c: flow.coherence.L1DCoherenceIO): Unit = {
    c.req.ready.poke((reqReady && getInFlight.isEmpty).B)
    c.rspUp.ready.poke(true.B)
    next match { case Some(r) => c.rspDown.valid.poke(true.B); Bind.pokeRspDown(c.rspDown.bits, r); case None => c.rspDown.valid.poke(false.B) }
    snpOut.filter(_ => !snpSent) match { case Some(s) => c.snp.valid.poke(true.B); Bind.pokeSnp(c.snp.bits, s); case None => c.snp.valid.poke(false.B) }
  }

  final case class Obs(req: Option[Req], up: Option[RspUp], dn: Boolean, snp: Boolean)
  def sample(c: flow.coherence.L1DCoherenceIO): Obs =
    Obs(if (Bind.fired(c.req)) Some(Bind.peekReq(c.req.bits)) else None,
      if (Bind.fired(c.rspUp)) Some(Bind.peekRspUp(c.rspUp.bits)) else None,
      Bind.fired(c.rspDown), Bind.fired(c.snp))

  /** Returns true when any message moved. */
  def observe(o: Obs): Boolean = {
    val cyc = b.cycle - 1 // the cycle the handshake happened in
    for (r <- o.req) {
      received += ((cyc, s"req${r.op}"))
      getInFlight = Some((r, b.cycle + minLat + b.rng.int(maxLat - minLat + 1)))
    }
    if (o.dn) {
      val (r, _) = rspQ.dequeue(); delivered += ((cyc, r.op))
      if (r.op == Op.DataE || r.op == Op.AckE) lastGrant.foreach(b.performStores)
    }
    if (o.snp) snpSent = true
    for (u <- o.up) {
      received += ((cyc, s"up${u.op}"))
      val gl = b.goldenLine(u.addr)
      if (u.hasData && u.data != gl)
        throw new AssertionError(f"L1D sent stale line 0x${u.addr}%x: 0x${u.data}%x, golden 0x$gl%x\n${b.dump}")
      if (u.hasData) mem(u.addr) = u.data
      else if (line(u.addr) != gl && (u.op == Op.Put || dir.contains(u.addr)))
        throw new AssertionError(f"L1D dropped dirty data for line 0x${u.addr}%x (answer without data)\n${b.dump}")
      u.op match {
        case Op.Put =>
          if (!dir.contains(u.addr)) throw new AssertionError(f"Put for line 0x${u.addr}%x the L1D does not hold")
          dir.remove(u.addr); rspQ.enqueue((RspDown(Op.PutAck), b.cycle + 1 + b.rng.int(4)))
        case Op.InvAck | Op.DownAck =>
          val s = snpOut.getOrElse(throw new AssertionError("probe answer without a probe"))
          if (s.addr != u.addr) throw new AssertionError("probe answer address mismatch")
          if (u.op == Op.InvAck) {
            dir.remove(u.addr)
            // The virtual core now owns the line and writes one word of it.
            val w = (u.addr << 2) + b.rng.int(p.wordsPerLine)
            val v = b.rng.bits(64)
            b.golden.store(w, v, 0xff)
            mem(u.addr) = b.goldenLine(u.addr)
          } else if (dir.contains(u.addr)) dir(u.addr) = LState.S
          snpOut = None; snpSent = false
      }
    }
    o.req.isDefined || o.up.isDefined || o.dn || o.snp
  }

  def act(): Unit = {
    // Respond to the Get when its latency expires; decide the response kind now.
    for ((r, t) <- getInFlight if t <= b.cycle && !snpOut.exists(_.addr == r.addr)) {
      val held = dir.get(r.addr)
      val rsp =
        if (r.op == Op.GetM && held.contains(LState.S)) RspDown(Op.AckE)
        else RspDown(if (r.op == Op.GetS && b.rng.chance(0.3)) Op.DataS else Op.DataE, data = line(r.addr))
      dir(r.addr) = if (rsp.op == Op.DataS) LState.S else LState.E
      rspQ.enqueue((rsp, b.cycle)); getInFlight = None; lastGrant = Some(r.addr)
    }
    // Steal a held line from the L1D.
    if (snpOut.isEmpty && stealP > 0 && b.rng.chance(stealP)) {
      val cand = dir.keys.filterNot(l => getInFlight.exists(_._1.addr == l) || rspQ.nonEmpty).toSeq
      if (cand.nonEmpty) steal(b.rng.pick(cand), inv = b.rng.chance(0.6))
    }
  }

  def steal(l: BigInt, inv: Boolean): Unit = {
    require(snpOut.isEmpty)
    val owner = dir.get(l).contains(LState.E)
    snpOut = Some(if (inv || !owner) Snp(Op.Inv, owner, l) else Snp(Op.Down, owner = true, l)); snpSent = false
  }
  def probeIdle: Boolean = snpOut.isEmpty
}
