package flow.memsys

import chisel3._
import chisel3.simulator.PeekPokeAPI._
import flow.coherence.CoherenceParams
import flow.l2.L2Home

/** Drives an L2Home with behavioural L1D agents, L1I/DMA readers and an AXI memory.
  * Every cycle: poke all inputs from model state, peek handshakes, step, update
  * models, run SWMR/data/watchdog checks.
  */
final class L2Bench(dut: L2Home, val seed: Long, lines: Seq[BigInt], useReaders: Boolean = true,
                    pGet: Double = 0.15, pWrite: Double = 0.2, pEvict: Double = 0.05) {
  val p: CoherenceParams = dut.p
  val rng = new Rng(seed)
  def init(l: BigInt): BigInt = (l * BigInt("9e3779b97f4a7c15", 16)) & ((BigInt(1) << p.lineBits) - 1)
  val golden = new GoldenMemory(p.lineBytes, init)
  val wd = new Watchdog(limit = 3000, idleLimit = 400)
  val swmr = new SwmrMonitor(golden)
  val agents: Seq[L1Agent] = (0 until p.nCores).map(c => new L1Agent(c, lines, new Rng(seed * 31 + c), golden, wd, pGet, pWrite, pEvict))
  val l1i: Seq[ReadAgent] = (0 until p.nCores).map(c => new ReadAgent(s"l1i$c", 2, lines, new Rng(seed * 57 + c), golden, wd, if (useReaders) 0.05 else 0))
  val dma = new ReadAgent("dma", 1, lines, new Rng(seed * 97), golden, wd, if (useReaders) 0.02 else 0)
  val mem = new AxiMemModel(p.memBeats, p.memDataBits, p.offBits, new Rng(seed * 13), init)
  var cycle = 0L
  val log = scala.collection.mutable.ArrayBuffer.empty[String]
  def note(s: String): Unit = { log += s"[$cycle] $s"; if (log.size > 200) log.remove(0) }

  def reset(): Unit = {
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
  }

  def dump: String =
    (agents.map(_.dump) ++ Seq(s"seed=$seed cycle=$cycle memReads=${mem.reads_} memWrites=${mem.writes_}") ++ log.takeRight(40)).mkString("\n")

  def step(): Unit = {
    val io = dut.io
    // ---------------- drive ----------------
    for (a <- agents) {
      val l = io.l1d(a.id)
      a.reqOut match { case Some(r) => l.req.valid.poke(true.B); Bind.pokeReq(l.req.bits, r); case None => l.req.valid.poke(false.B) }
      a.rspUpOut match { case Some(r) => l.rspUp.valid.poke(true.B); Bind.pokeRspUp(l.rspUp.bits, r); case None => l.rspUp.valid.poke(false.B) }
      l.snp.ready.poke(a.snpReady.B)
      l.rspDown.ready.poke(true.B)
    }
    for ((r, i) <- l1i.zipWithIndex) {
      val port = io.l1i(i)
      r.reqOut match { case Some(q) => port.req.valid.poke(true.B); Bind.pokeReq(port.req.bits, q); case None => port.req.valid.poke(false.B) }
      port.rspDown.ready.poke(true.B)
    }
    dma.reqOut match { case Some(q) => io.dma.req.valid.poke(true.B); Bind.pokeReq(io.dma.req.bits, q); case None => io.dma.req.valid.poke(false.B) }
    io.dma.rspDown.ready.poke(true.B)

    val m = io.mem
    m.ar.ready.poke(mem.arReady.B)
    m.aw.ready.poke(mem.awReady.B)
    m.w.ready.poke(mem.wReady.B)
    mem.rOut(cycle) match {
      case Some((id, d, last)) =>
        m.r.valid.poke(true.B); m.r.bits.id.poke(id.U); m.r.bits.data.poke(d.U); m.r.bits.resp.poke(0.U); m.r.bits.last.poke(last.B)
      case None => m.r.valid.poke(false.B)
    }
    mem.bOut(cycle) match {
      case Some(id) => m.b.valid.poke(true.B); m.b.bits.id.poke(id.U); m.b.bits.resp.poke(0.U)
      case None => m.b.valid.poke(false.B)
    }

    // ---------------- sample handshakes ----------------
    var progress = false
    val agentObs = agents.map { a =>
      val l = io.l1d(a.id)
      val reqF = Bind.fired(l.req); val upF = Bind.fired(l.rspUp)
      val snpIn = if (Bind.fired(l.snp)) Some(Bind.peekSnp(l.snp.bits)) else None
      val dnIn = if (Bind.valid(l.rspDown)) Some(Bind.peekRspDown(l.rspDown.bits)) else None
      if (reqF || upF || snpIn.isDefined || dnIn.isDefined) progress = true
      snpIn.foreach(s => note(f"snp->a${a.id} op=${s.op} owner=${s.owner} 0x${s.addr}%x"))
      dnIn.foreach(r => note(s"rspDown->a${a.id} op=${r.op}"))
      (reqF, upF, snpIn, dnIn)
    }
    val readObs = (l1i.zipWithIndex.map { case (r, i) => (Bind.fired(io.l1i(i).req), if (Bind.valid(io.l1i(i).rspDown)) Some(Bind.peekRspDown(io.l1i(i).rspDown.bits)) else None) }) :+
      ((Bind.fired(io.dma.req), if (Bind.valid(io.dma.rspDown)) Some(Bind.peekRspDown(io.dma.rspDown.bits)) else None))
    val ar = if (Bind.fired(m.ar)) Some((m.ar.bits.id.peek().litValue.toInt, m.ar.bits.addr.peek().litValue, m.ar.bits.len.peek().litValue.toInt)) else None
    val rF = Bind.fired(m.r)
    val aw = if (Bind.fired(m.aw)) Some((m.aw.bits.id.peek().litValue.toInt, m.aw.bits.addr.peek().litValue, m.aw.bits.len.peek().litValue.toInt)) else None
    val w = if (Bind.fired(m.w)) Some((m.w.bits.data.peek().litValue, m.w.bits.last.peek().litToBoolean)) else None
    val bF = Bind.fired(m.b)
    if (ar.isDefined || rF || aw.isDefined || w.isDefined || bF || readObs.exists(o => o._1 || o._2.isDefined)) progress = true

    dut.clock.step()
    cycle += 1

    // ---------------- update models ----------------
    for ((a, (reqF, upF, snpIn, dnIn)) <- agents.zip(agentObs)) a.observe(cycle, reqF, upF, snpIn, dnIn)
    for ((r, (f, d)) <- (l1i :+ dma).zip(readObs)) r.observe(cycle, f, d)
    mem.observe(cycle, ar, rF, aw, w, bF)
    if (progress) wd.progress(cycle)
    swmr.check(cycle, agents.flatMap(_.copies))
    wd.tick(cycle, dump)
    agents.foreach(_.act(cycle)); l1i.foreach(_.act(cycle)); dma.act(cycle)
  }

  def run(n: Int): Unit = for (_ <- 0 until n) step()

  /** Step until `cond` holds; fail with a dump after `max` cycles. Returns cycles taken. */
  def runUntil(what: String, max: Int = 500)(cond: => Boolean): Int = {
    var n = 0
    while (!cond) { if (n >= max) throw new AssertionError(s"timeout waiting for $what\n$dump"); step(); n += 1 }
    n
  }

  /** Stop new work and run until everything in flight completes. */
  def drain(maxCycles: Int = 5000): Unit = {
    agents.foreach(_.quiesce = true); l1i.foreach(_.quiesce = true); dma.quiesce = true
    var n = 0
    while (!(agents.forall(_.idle) && l1i.forall(_.idle) && dma.idle)) {
      if (n > maxCycles) throw new AssertionError(s"drain did not finish\n$dump")
      step(); n += 1
    }
  }

  def waitInit(): Unit = run(p.l2Sets + 4)
}
