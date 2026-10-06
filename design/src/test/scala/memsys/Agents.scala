package flow.memsys

import scala.collection.mutable

/** Behavioural L1D client for L2 tests. Follows exactly the client rules the
  * real L1D promises (l1d-rtl-spec §6, §10, coherence-l2-rtl-spec §1):
  *  - at most one Get and one Put in flight;
  *  - never Get a line while its Put waits for PutAck, never Put a line with a Get in flight;
  *  - GetS only from I, GetM from I or S (upgrade);
  *  - one SNP receive register; a probe is held while its line has a Put
  *    in flight, or a Get in flight and the probe's role exceeds the local
  *    state (local I: any probe; local S: owner probe);
  *  - probe answers take RSP↑ before a Put.
  * Writes happen only in E/M and update the golden memory at once.
  *
  * The bench calls, every cycle: drive* (what to poke), then observe(fires),
  * then act() for new random decisions.
  */
final class L1Agent(val id: Int, lines: Seq[BigInt], rng: Rng, golden: GoldenMemory, wd: Watchdog,
                    pGet: Double = 0.15, pWrite: Double = 0.2, pEvict: Double = 0.05) {
  final case class Copy(var st: LState.Value, var data: BigInt)
  val local = mutable.Map.empty[BigInt, Copy]
  private def stateOf(l: BigInt) = local.get(l).map(_.st).getOrElse(LState.I)

  // In-flight Get: (line, isGetM). `getSent` = REQ fired.
  var get: Option[(BigInt, Boolean)] = None
  var getSent = false
  // Pending Put: (line, hasData, data); `putSent` = RSP↑ fired, waiting PutAck.
  var put: Option[(BigInt, Boolean, BigInt)] = None
  var putSent = false
  // SNP receive register and the answer waiting for RSP↑.
  var snp: Option[Snp] = None
  var answer: Option[RspUp] = None
  /** Stop issuing new work (drain phase). */
  var quiesce = false
  var completed = 0

  // ---------------- drive ----------------
  def reqOut: Option[Req] = if (get.isDefined && !getSent) get.map { case (l, m) => Req(if (m) Op.GetM else Op.GetS, l) } else None
  def rspUpOut: Option[RspUp] = answer.orElse(
    if (put.isDefined && !putSent) put.map { case (l, d, v) => RspUp(Op.Put, d, l, if (d) v else 0) } else None)
  def snpReady: Boolean = snp.isEmpty

  // ---------------- observe ----------------
  def observe(cycle: Long, reqFired: Boolean, rspUpFired: Boolean, snpIn: Option[Snp], rspDownIn: Option[RspDown]): Unit = {
    if (reqFired) { getSent = true }
    if (rspUpFired) {
      if (answer.isDefined) { answer = None; snp = None; wd.finish(s"a$id.snp") }
      else { putSent = true }
    }
    for (r <- rspDownIn) {
      r.op match {
        case Op.DataS | Op.DataE =>
          val (l, _) = get.getOrElse(throw new AssertionError(s"agent$id: data without a Get at cycle $cycle"))
          if (r.error) throw new AssertionError(s"agent$id: unexpected refill error at cycle $cycle")
          if (r.data != golden.current(l))
            throw new AssertionError(f"agent$id: line 0x$l%x got 0x${r.data}%x, golden 0x${golden.current(l)}%x at cycle $cycle")
          local(l) = Copy(if (r.op == Op.DataS) LState.S else LState.E, r.data)
          get = None; getSent = false; completed += 1; wd.finish(s"a$id.get")
        case Op.AckE =>
          val (l, m) = get.getOrElse(throw new AssertionError(s"agent$id: AckE without a Get"))
          if (!m || stateOf(l) != LState.S) throw new AssertionError(s"agent$id: AckE for line in ${stateOf(l)} at cycle $cycle")
          local(l).st = LState.E
          get = None; getSent = false; completed += 1; wd.finish(s"a$id.get")
        case Op.PutAck =>
          if (!putSent) throw new AssertionError(s"agent$id: PutAck without a Put at cycle $cycle")
          put = None; putSent = false; wd.finish(s"a$id.put")
        case other => throw new AssertionError(s"agent$id: unexpected RSPdown op $other")
      }
    }
    for (s <- snpIn) {
      if (snp.isDefined) throw new AssertionError(s"agent$id: SNP accepted while register busy")
      snp = Some(s); wd.start(s"a$id.snp", cycle)
    }
    processProbe()
  }

  private def processProbe(): Unit = for (s <- snp if answer.isEmpty) {
    val l = s.addr
    val st = stateOf(l)
    val putHold = put.exists(_._1 == l)
    val getHold = get.exists(_._1 == l) && (st == LState.I || st == LState.S && s.owner)
    if (!putHold && !getHold) {
      val hasData = st == LState.M
      val data = local.get(l).map(_.data).getOrElse(BigInt(0))
      if (s.op == Op.Inv) {
        answer = Some(RspUp(Op.InvAck, hasData, l, if (hasData) data else 0))
        local.remove(l)
      } else {
        answer = Some(RspUp(Op.DownAck, hasData, l, if (hasData) data else 0))
        if (st == LState.E || st == LState.M) local(l).st = LState.S
      }
    }
  }

  // ---------------- random decisions ----------------
  def act(cycle: Long): Unit = {
    if (quiesce) return
    // Write a line held in E/M (E→M silent).
    if (rng.chance(pWrite)) {
      val owned = local.filter { case (l, c) => (c.st == LState.E || c.st == LState.M) && !snp.exists(_.addr == l) }.keys.toSeq
      if (owned.nonEmpty) {
        val l = rng.pick(owned); val v = rng.bits(256)
        local(l).st = LState.M; local(l).data = v; golden.write(l, v)
      }
    }
    // Evict.
    if (put.isEmpty && rng.chance(pEvict)) {
      val cand = local.keys.filter(l => !get.exists(_._1 == l) && !snp.exists(_.addr == l)).toSeq
      if (cand.nonEmpty) {
        val l = rng.pick(cand); val c = local.remove(l).get
        put = Some((l, c.st == LState.M, c.data)); putSent = false; wd.start(s"a$id.put", cycle)
      }
    }
    // New Get.
    if (get.isEmpty && rng.chance(pGet)) {
      val l = rng.pick(lines)
      if (!put.exists(_._1 == l)) stateOf(l) match {
        case LState.I => get = Some((l, rng.chance(0.5))); getSent = false; wd.start(s"a$id.get", cycle)
        case LState.S => get = Some((l, true)); getSent = false; wd.start(s"a$id.get", cycle)
        case _ =>
      }
    }
  }

  // ---------------- directed-test controls ----------------
  def issueGet(l: BigInt, getM: Boolean, cycle: Long): Unit = {
    require(get.isEmpty && !put.exists(_._1 == l)); get = Some((l, getM)); getSent = false; wd.start(s"a$id.get", cycle)
  }
  def write(l: BigInt, v: BigInt): Unit = {
    require(Set(LState.E, LState.M).contains(stateOf(l)), s"agent$id cannot write line in ${stateOf(l)}")
    local(l).st = LState.M; local(l).data = v; golden.write(l, v)
  }
  def evict(l: BigInt, cycle: Long): Unit = {
    require(put.isEmpty && !get.exists(_._1 == l)); val c = local.remove(l).get
    put = Some((l, c.st == LState.M, c.data)); putSent = false; wd.start(s"a$id.put", cycle)
  }
  def state(l: BigInt): LState.Value = stateOf(l)

  def copies: Seq[(Int, BigInt, LState.Value, BigInt)] = local.toSeq.map { case (l, c) => (id, l, c.st, c.data) }
  def idle: Boolean = get.isEmpty && put.isEmpty && snp.isEmpty
  def dump: String = s"agent$id get=$get sent=$getSent put=${put.map(p => (p._1, p._2))} putSent=$putSent snp=$snp answer=${answer.map(_.op)} local=${local.map { case (l, c) => f"0x$l%x:${c.st}" }.mkString(",")}"
}

/** Non-coherent reader (L1I: ids 0/1, DMA: id 0). Read data must be a version written since issue. */
final class ReadAgent(val name: String, maxInFlight: Int, lines: Seq[BigInt], rng: Rng, golden: GoldenMemory,
                      wd: Watchdog, pRead: Double = 0.05) {
  private val inFlight = mutable.Map.empty[Int, (BigInt, Int)] // id -> (line, version at issue)
  private var pending: Option[Req] = None
  var quiesce = false

  def reqOut: Option[Req] = pending
  def observe(cycle: Long, reqFired: Boolean, rspDownIn: Option[RspDown]): Unit = {
    if (reqFired) { val r = pending.get; inFlight(r.id) = (r.addr, golden.version(r.addr)); pending = None }
    for (r <- rspDownIn) {
      if (r.op != Op.ReadData) throw new AssertionError(s"$name: unexpected RSPdown op ${r.op}")
      val (l, v) = inFlight.remove(r.id).getOrElse(throw new AssertionError(s"$name: ReadData for idle id ${r.id}"))
      if (!r.error && !golden.seenSince(l, v, r.data))
        throw new AssertionError(f"$name: Read line 0x$l%x returned 0x${r.data}%x, never written since issue at cycle $cycle")
      lastData = Some(r.data); wd.finish(s"$name.read${r.id}")
    }
  }
  def act(cycle: Long): Unit = if (!quiesce && pending.isEmpty && rng.chance(pRead)) {
    val free = (0 until maxInFlight).filterNot(inFlight.contains)
    if (free.nonEmpty) {
      val id = free.head; pending = Some(Req(Op.Read, rng.pick(lines), id)); wd.start(s"$name.read$id", cycle)
    }
  }
  def idle: Boolean = pending.isEmpty && inFlight.isEmpty
  def issueRead(l: BigInt, id: Int, cycle: Long): Unit = {
    require(pending.isEmpty && !inFlight.contains(id)); pending = Some(Req(Op.Read, l, id)); wd.start(s"$name.read$id", cycle)
  }
  var lastData: Option[BigInt] = None
}

/** AXI4 slave backing memory: in-order R, random latency, one line per burst (coherence-l2-rtl-spec §7). */
final class AxiMemModel(beats: Int, beatBits: Int, offBits: Int, rng: Rng, init: BigInt => BigInt,
                        minLat: Int = 2, maxLat: Int = 12) {
  val mem = mutable.Map.empty[BigInt, BigInt]
  def line(l: BigInt): BigInt = mem.getOrElse(l, init(l))
  private val reads = mutable.Queue.empty[(Int, BigInt, Long)] // (id, line, readyCycle)
  private var rBeat = 0
  private var aw: Option[(Int, BigInt)] = None
  private val wData = mutable.ArrayBuffer.empty[BigInt]
  private var bPending: Option[(Int, Long)] = None
  var reads_, writes_ = 0

  def arReady: Boolean = reads.size < 4
  def awReady: Boolean = aw.isEmpty && bPending.isEmpty
  def wReady: Boolean = aw.isDefined && bPending.isEmpty
  /** R beat to present this cycle: (id, data, last). */
  def rOut(cycle: Long): Option[(Int, BigInt, Boolean)] = reads.headOption.filter(_._3 <= cycle).map { case (id, l, _) =>
    val d = (line(l) >> (rBeat * beatBits)) & ((BigInt(1) << beatBits) - 1)
    (id, d, rBeat == beats - 1)
  }
  def bOut(cycle: Long): Option[Int] = bPending.filter(_._2 <= cycle).map(_._1)

  def observe(cycle: Long, ar: Option[(Int, BigInt, Int)], rFired: Boolean, awIn: Option[(Int, BigInt, Int)],
              w: Option[(BigInt, Boolean)], bFired: Boolean): Unit = {
    for ((id, addr, len) <- ar) {
      if (len != beats - 1) throw new AssertionError(s"AXI AR len $len")
      reads.enqueue((id, addr >> offBits, cycle + minLat + rng.int(maxLat - minLat + 1))); reads_ += 1
    }
    if (rFired) { rBeat += 1; if (rBeat == beats) { reads.dequeue(); rBeat = 0 } }
    for ((id, addr, len) <- awIn) {
      if (len != beats - 1) throw new AssertionError(s"AXI AW len $len")
      aw = Some((id, addr >> offBits))
    }
    for ((d, last) <- w) {
      wData += d
      if (last) {
        if (wData.size != beats) throw new AssertionError(s"AXI W burst of ${wData.size} beats")
        val v = wData.zipWithIndex.map { case (b, i) => b << (i * beatBits) }.reduce(_ | _)
        mem(aw.get._2) = v; writes_ += 1
        bPending = Some((aw.get._1, cycle + 1 + rng.int(4))); aw = None; wData.clear()
      }
    }
    if (bFired) bPending = None
  }
}
