package flow.cache

import chisel3._
import chisel3.simulator.PeekPokeAPI
import chisel3.simulator.scalatest.ChiselSim
import flow.config.L2CacheGeometry
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import scala.collection.mutable

final case class SmallReqEvent(hart: Int, opcode: BigInt, txnId: BigInt, lineAddr: BigInt)
final case class SmallProbeEvent(hart: Int, txnId: BigInt, lineAddr: BigInt, opcode: BigInt)
final case class SmallRespEvent(hart: Int, txnId: BigInt, lineAddr: BigInt, hasData: Boolean, data: BigInt)
final case class SmallGrantEvent(hart: Int, txnId: BigInt, lineAddr: BigInt, state: BigInt,
    hasData: Boolean, data: BigInt, error: Boolean)
final case class PendProbe(txnId: BigInt, lineAddr: BigInt, opcode: BigInt, countdown: Int)

/** Four-hart mock L1D clients + a Wishbone memory slave around the L2/Home.
  *
  * Unlike the dual-hart `L2HomeHarness`, every hart owns an independent
  * probe-pending slot, latency, ready mask and response presentation order,
  * so a Home that probes several harts in the same cycle is modeled exactly:
  *
  *   - `probeReadyMask(h)` selects which ports accept a probe this cycle; a
  *     probe held by the Home with `valid && !ready` has its payload checked
  *     for stability every cycle until it is accepted;
  *   - `probeLatency(h)` and `respOrder` control when and in which order the
  *     mock L1s answer, so three Acks can be forced to return out of order;
  *   - every request/grant/probe/response/Wishbone beat is logged, grants are
  *     counted per hart, and the mock L1 tracks (state, data) per line;
  *   - a global cycle watchdog fails the test instead of hanging.
  */
final class SmallL2Harness(
    dut: BreezeL2Home,
    val mem: DTestMem,
    numHarts: Int = 4,
    wbLatency: Int = 2
) extends Assertions with PeekPokeAPI {

  // Mock L1 contents: (hart,lineAddr) -> (state 'S'/'E'/'M', line data).
  val l1 = mutable.Map.empty[(Int, BigInt), (Char, BigInt)]
  val wbLog = mutable.ArrayBuffer.empty[(BigInt, Boolean)]
  val wbDataLog = mutable.ArrayBuffer.empty[BigInt]
  val reqLog = mutable.ArrayBuffer.empty[SmallReqEvent]
  val probeLog = mutable.ArrayBuffer.empty[SmallProbeEvent]
  val respLog = mutable.ArrayBuffer.empty[SmallRespEvent]
  val grantLog = mutable.ArrayBuffer.empty[SmallGrantEvent]
  val grantCount = Array.fill(numHarts)(0)
  val instrRespCount = Array.fill(numHarts)(0)

  /** Byte addresses whose Wishbone beats must be answered with err. */
  var errorOnAddresses = Set.empty[BigInt]

  // ----- Wishbone slave state -----
  private var wbBusy = false
  private var wbCountdown = 0
  private var wbAddr = BigInt(0)
  private var wbWe = false
  private var wbData = BigInt(0)
  private var wbJustAcked = false

  // ----- per-hart probe machinery -----
  val probeReadyMask = Array.fill(numHarts)(true)
  val probeLatency = Array.fill(numHarts)(1)
  val respOrder = mutable.Queue.empty[Int]


  private val pendProbe = Array.fill[Option[PendProbe]](numHarts)(None)
  private val answer = Array.fill[Option[(Boolean, BigInt)]](numHarts)(None)
  private val preLatch = Array.fill[Option[(BigInt, BigInt, BigInt)]](numHarts)(None)

  // Global cycle watchdog: a hang fails the test instead of the CI run.
  var cycleCount = 0L
  private val cycleWatchdog = 200000L

  // Deterministic idle values for every client port, including harts a test
  // does not actively drive.
  for (h <- 0 until numHarts) {
    val req = dut.io.coherenceReq(h)
    req.valid.poke(false.B)
    req.opcode.poke(BreezeCoherenceOpcode.GetS.litValue)
    req.srcHart.poke(h.U)
    req.txnId.poke(0.U)
    req.lineAddr.poke(0.U)
    req.hasData.poke(false.B)
    req.lineData.poke(0.U)
    dut.io.coherenceGrant(h).ready.poke(false.B)
    val rsp = dut.io.coherenceProbeResp(h)
    rsp.valid.poke(false.B)
    rsp.srcHart.poke(h.U)
    rsp.txnId.poke(0.U)
    rsp.lineAddr.poke(0.U)
    rsp.ack.poke(false.B)
    rsp.hasData.poke(false.B)
    rsp.lineData.poke(0.U)
    dut.io.instrReq(h).req.poke(false.B)
    dut.io.instrReq(h).paddr.poke(0.U)
  }
  dut.io.memoryWishbone.ack.poke(false.B)
  dut.io.memoryWishbone.err.poke(false.B)
  dut.io.memoryWishbone.dat_r.poke(0.U)

  private def grantPort(h: Int) = dut.io.coherenceGrant(h)

  /** Advance one cycle: Wishbone slave, per-hart probe accept/respond, logs. */
  def step(): Unit = {
    // --- Wishbone slave ---
    val wb = dut.io.memoryWishbone
    wb.ack.poke(false.B)
    wb.err.poke(false.B)
    if (wbBusy && wbCountdown == 0) {
      val isErr = errorOnAddresses.contains(wbAddr)
      if (isErr) {
        wb.err.poke(true.B)
      } else {
        wb.ack.poke(true.B)
        if (wbWe) {
          mem.writeBytes(wbAddr, 8, wbData, 0xff)
        } else {
          wb.dat_r.poke(mem.readBytes(wbAddr, 8).U)
        }
      }
      wbLog += ((wbAddr, wbWe))
      if (wbWe) wbDataLog += wbData
      wbBusy = false
      wbJustAcked = true
    } else {
      if (wbBusy) wbCountdown -= 1
      wbJustAcked = false
    }
    // Latch a new beat (the master holds cyc/stb until ack; skip the beat we
    // just acknowledged).
    if (!wbBusy && !wbJustAcked && wb.cyc.peek().litToBoolean && wb.stb.peek().litToBoolean) {
      wbAddr = wb.adr.peek().litValue << 3
      wbWe = wb.we.peek().litToBoolean
      wbData = wb.dat_w.peek().litValue
      wbBusy = true
      wbCountdown = wbLatency
    }

    // --- probe accept: per-hart ready from the mask, payload stability ---
    for (h <- 0 until numHarts) {
      val p = dut.io.coherenceProbe(h)
      p.ready.poke(probeReadyMask(h).B)
      val vld = p.valid.peek().litToBoolean
      val txn = p.txnId.peek().litValue
      val la = p.lineAddr.peek().litValue
      val op = p.opcode.peek().litValue
      if (vld && !probeReadyMask(h)) {
        preLatch(h) match {
          case Some((t, a, o)) =>
            assert(txn == t && la == a && op == o,
              s"hart $h probe payload changed while valid && !ready")
          case None => preLatch(h) = Some((txn, la, op))
        }
      } else if (vld && probeReadyMask(h)) {
        assert(pendProbe(h).isEmpty, s"hart $h has an outstanding probe already")
        preLatch(h) = None
        probeLog += SmallProbeEvent(h, txn, la, op)
        pendProbe(h) = Some(PendProbe(txn, la, op, probeLatency(h)))
      } else {
        preLatch(h) = None
      }
    }

    // --- advance per-hart response latency, latch each answer once ---
    for (h <- 0 until numHarts) {
      pendProbe(h) match {
        case Some(p) if p.countdown > 0 =>
          pendProbe(h) = Some(p.copy(countdown = p.countdown - 1))
        case Some(p) if answer(h).isEmpty =>
          answer(h) = Some(answerProbe(h, p.lineAddr, p.opcode))
        case _ =>
      }
    }

    // --- present the response at the head of the order queue ---
    val candidate: Option[Int] =
      if (respOrder.nonEmpty) {
        // Strict queue discipline: only the head may present its response,
        // even if later harts already hold answers.
        val head = respOrder.head
        if (answer(head).isDefined) Some(head) else None
      } else {
        (0 until numHarts).find(h => answer(h).isDefined)
      }
    candidate.foreach { h =>
      val p = pendProbe(h).get
      val (hasData, data) = answer(h).get
      val rsp = dut.io.coherenceProbeResp(h)
      rsp.valid.poke(true.B)
      rsp.txnId.poke(p.txnId.U)
      rsp.lineAddr.poke(p.lineAddr.U)
      rsp.ack.poke(true.B)
      rsp.hasData.poke(hasData.B)
      rsp.lineData.poke(data.U)
      if (rsp.ready.peek().litToBoolean) {
        respLog += SmallRespEvent(h, p.txnId, p.lineAddr, hasData, data)
        if (respOrder.nonEmpty) {
          assert(respOrder.head == h, "response order queue head mismatch")
          respOrder.dequeue()
        }
        pendProbe(h) = None
        answer(h) = None
      }
    }

    // --- count I$ responses exactly once ---
    for (h <- 0 until numHarts) {
      if (dut.io.instrResp(h).vld.peek().litToBoolean) instrRespCount(h) += 1
    }

    dut.clock.step(1)
    cycleCount += 1
    assert(cycleCount < cycleWatchdog, "small harness cycle watchdog exceeded")
  }

  /** Mock-L1 probe answer following MESI rules; returns (hasData, data). */
  private def answerProbe(hart: Int, lineAddr: BigInt, opcode: BigInt): (Boolean, BigInt) = {
    val key = (hart, lineAddr)
    val entry = l1.get(key)
    if (opcode == BreezeProbeOpcode.ProbeInv.litValue) {
      entry match {
        case Some(('M', data)) => l1.remove(key); (true, data)
        case Some((s, _)) => assert(s == 'S' || s == 'E', "ProbeInv to M-only rule broken"); l1.remove(key); (false, BigInt(0))
        case None => (false, BigInt(0))
      }
    } else if (opcode == BreezeProbeOpcode.ProbeToS.litValue) {
      entry match {
        case Some(('M', data)) => l1(key) = ('S', data); (true, data)
        case Some(('E', data)) => l1(key) = ('S', data); (false, BigInt(0))
        case Some(('S', _)) => (false, BigInt(0))
        case Some((state, _)) => fail(s"ProbeToS to invalid state $state")
        case None => (false, BigInt(0))
      }
    } else {
      assert(opcode == BreezeProbeOpcode.ProbeRecallInv.litValue, "unknown probe opcode")
      entry match {
        case Some((s, data)) => assert(s == 'M' || s == 'E', "RecallInv to non-owner"); l1.remove(key); (true, data)
        case None => (false, BigInt(0))
      }
    }
  }

  private def lineOf(addr: BigInt): BigInt = addr & ~BigInt(31)

  /** Drive a request until accepted; the transaction then runs unattended. */
  def beginRequest(
      op: BreezeCoherenceOpcode.Type,
      lineAddr: BigInt,
      hart: Int = 0,
      txnId: BigInt = 1,
      hasData: Boolean = false,
      data: BigInt = 0
  ): Unit = {
    val req = dut.io.coherenceReq(hart)
    req.valid.poke(true.B)
    req.opcode.poke(op.litValue)
    req.srcHart.poke(hart.U)
    req.txnId.poke(txnId.U)
    req.lineAddr.poke(lineAddr.U)
    req.hasData.poke(hasData.B)
    req.lineData.poke(data.U)

    var cycles = 0
    while (!req.ready.peek().litToBoolean && cycles < 4000) {
      step()
      cycles += 1
    }
    if (!req.ready.peek().litToBoolean) fail("L2/Home never accepted the coherence request")
    reqLog += SmallReqEvent(hart, op.litValue, txnId, lineAddr)
    req.valid.poke(false.B)
  }

  /** Wait for the grant on one hart, logging and counting it exactly once. */
  def waitGrantEvent(hart: Int): SmallGrantEvent = {
    val grant = grantPort(hart)
    grant.ready.poke(true.B)
    var result: Option[SmallGrantEvent] = None
    var cycles = 0
    while (result.isEmpty && cycles < 4000) {
      if (grant.valid.peek().litToBoolean) {
        result = Some(SmallGrantEvent(
          hart,
          grant.txnId.peek().litValue,
          grant.lineAddr.peek().litValue,
          grant.grantState.peek().litValue,
          grant.hasData.peek().litToBoolean,
          grant.lineData.peek().litValue,
          grant.error.peek().litToBoolean
        ))
        grantCount(hart) += 1
        grantLog += result.get
      }
      step()
      cycles += 1
    }
    grant.ready.poke(false.B)
    if (result.isEmpty) fail(s"L2/Home never granted hart $hart")
    result.get
  }

  def grantValid(hart: Int): Boolean = grantPort(hart).valid.peek().litToBoolean

  /** Drive one coherence request and wait for the grant. */
  private def request(
      op: BreezeCoherenceOpcode.Type,
      lineAddr: BigInt,
      hart: Int = 0,
      txnId: BigInt = 1,
      hasData: Boolean = false,
      data: BigInt = 0
  ): SmallGrantEvent = {
    beginRequest(op, lineAddr, hart, txnId, hasData, data)
    waitGrantEvent(hart)
  }

  def grantPortReady(hart: Int, ready: Boolean): Unit =
    grantPort(hart).ready.poke(ready.B)


  def getS(addr: BigInt, hart: Int = 0, txnId: BigInt = 1): (BigInt, BigInt, Boolean) = {
    val g = request(BreezeCoherenceOpcode.GetS, lineOf(addr), hart, txnId)
    if (!g.error) {
      assert(g.hasData, "GetS grant must carry data")
      l1((hart, lineOf(addr))) = (if (g.state == BreezeGrantState.M.litValue) 'M'
        else if (g.state == BreezeGrantState.E.litValue) 'E' else 'S', g.data)
    }
    (g.state, g.data, g.error)
  }

  def getM(addr: BigInt, hart: Int = 0, txnId: BigInt = 1): (BigInt, Boolean) = {
    val g = request(BreezeCoherenceOpcode.GetM, lineOf(addr), hart, txnId)
    if (!g.error) {
      // An upgrade grant may omit the data; the mock L1 keeps its copy.
      val key = (hart, lineOf(addr))
      val merged = if (g.hasData) g.data else l1.getOrElse(key, ('S', BigInt(0)))._2
      l1(key) = ('M', merged)
    }
    (g.data, g.error)
  }

  def putS(addr: BigInt, hart: Int = 0): Boolean = {
    val g = request(BreezeCoherenceOpcode.PutS, lineOf(addr), hart)
    if (!g.error) l1.remove((hart, lineOf(addr)))
    g.error
  }

  def putM(addr: BigInt, data: BigInt, hart: Int = 0): Boolean = {
    val g = request(BreezeCoherenceOpcode.PutM, lineOf(addr), hart, hasData = true, data = data)
    if (!g.error) l1.remove((hart, lineOf(addr)))
    g.error
  }

  /** Mock a local store inside the L1 (line must be held in M). */
  def l1Store(addr: BigInt, newData: BigInt, hart: Int = 0): Unit = {
    val line = lineOf(addr)
    val key = (hart, line)
    assert(l1.get(key).exists(_._1 == 'M'), "l1Store requires an M line")
    l1(key) = ('M', newData)
  }

  def l1State(addr: BigInt, hart: Int): Option[Char] =
    l1.get((hart, lineOf(addr))).map(_._1)

  def l1Data(addr: BigInt, hart: Int): Option[BigInt] =
    l1.get((hart, lineOf(addr))).map(_._2)

  def l1Contains(addr: BigInt, hart: Int): Boolean =
    l1.contains((hart, lineOf(addr)))

  /** Assert a one-cycle I$ refill pulse on one hart (no response wait). */
  def pulseInstr(hart: Int, paddr: BigInt): Unit = {
    dut.io.instrReq(hart).paddr.poke(paddr.U)
    dut.io.instrReq(hart).req.poke(true.B)
  }

  def dropInstr(hart: Int): Unit = {
    dut.io.instrReq(hart).req.poke(false.B)
  }

  /** I$ refill pulse; waits for the response. Returns (data, error). */
  def instr(addr: BigInt, hart: Int = 0): (BigInt, Boolean) = {
    dut.io.instrReq(hart).paddr.poke(lineOf(addr).U)
    dut.io.instrReq(hart).req.poke(true.B)
    step()
    dut.io.instrReq(hart).req.poke(false.B)
    var cycles = 0
    var result: Option[(BigInt, Boolean)] = None
    while (result.isEmpty && cycles < 4000) {
      if (dut.io.instrResp(hart).vld.peek().litToBoolean) {
        result = Some((dut.io.instrResp(hart).data.peek().litValue,
          dut.io.instrResp(hart).error.peek().litToBoolean))
      }
      step()
      cycles += 1
    }
    if (result.isEmpty) fail("L2/Home never answered the I$ refill")
    result.get
  }

  /** Present GetS requests from all `harts` in the same cycle and prove that
    * each is accepted and granted exactly once while the Home serializes. */
  def simultaneousGetS(addr: BigInt, harts: Seq[Int]): Unit = {
    val line = lineOf(addr)
    val accepted = Array.fill(numHarts)(false)
    val results = Array.fill[Option[SmallGrantEvent]](numHarts)(None)
    for (h <- harts) {
      val req = dut.io.coherenceReq(h)
      req.valid.poke(true.B)
      req.opcode.poke(BreezeCoherenceOpcode.GetS.litValue)
      req.srcHart.poke(h.U)
      req.txnId.poke(h.U)
      req.lineAddr.poke(line.U)
      req.hasData.poke(false.B)
      req.lineData.poke(0.U)
      dut.io.coherenceGrant(h).ready.poke(true.B)
    }

    var cycles = 0
    while (results.exists(_.isEmpty) && cycles < 8000) {
      val fires = harts.map(h => !accepted(h) && dut.io.coherenceReq(h).ready.peek().litToBoolean)
      for (h <- harts) {
        val grant = dut.io.coherenceGrant(h)
        if (grant.valid.peek().litToBoolean) {
          assert(results(h).isEmpty, s"hart $h received a duplicate grant")
          val g = SmallGrantEvent(
            h,
            grant.txnId.peek().litValue,
            grant.lineAddr.peek().litValue,
            grant.grantState.peek().litValue,
            grant.hasData.peek().litToBoolean,
            grant.lineData.peek().litValue,
            grant.error.peek().litToBoolean
          )
          grantCount(h) += 1
          grantLog += g
          results(h) = Some(g)
        }
      }
      step()
      for (h <- harts if fires(h)) {
        accepted(h) = true
        dut.io.coherenceReq(h).valid.poke(false.B)
      }
      cycles += 1
    }
    assert(accepted.forall(identity), "all simultaneous requests must be accepted")
    if (results.exists(_.isEmpty)) fail("simultaneous GetS timed out")
    for (h <- harts) {
      dut.io.coherenceGrant(h).ready.poke(false.B)
      val g = results(h).get
      assert(g.hasData && !g.error, s"hart $h GetS grant must carry data without error")
      assert(g.state == BreezeGrantState.S.litValue, s"hart $h GetS grant must be S in MSI")
      l1((h, line)) = ('S', g.data)
    }
  }

  def resetWbLog(): Unit = { wbLog.clear(); wbDataLog.clear() }
  def resetProbeLog(): Unit = probeLog.clear()
  def resetRespLog(): Unit = respLog.clear()
  def resetReqLog(): Unit = reqLog.clear()
  def resetGrantLog(): Unit = grantLog.clear()
  def resetGrantCounts(): Unit = for (i <- grantCount.indices) grantCount(i) = 0
  def resetInstrCounts(): Unit = for (i <- instrRespCount.indices) instrRespCount(i) = 0
  def resetAllLogs(): Unit = {
    resetWbLog(); resetReqLog(); resetProbeLog(); resetRespLog(); resetGrantLog()
    resetGrantCounts(); resetInstrCounts()
  }
}

class BreezeL2HomeSmallSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val smallL2Cfg = L2CacheGeometry(capacityBytes = 65536)

  // small: 256 sets -> set = addr[12:5], tag = addr[31:13], stride = 8192 B.
  private def smallRamAddr(tagLsb: Int, set: Int, offset: Int = 0): BigInt =
    BigInt(0x80000000L) + (BigInt(tagLsb) << 13) + (BigInt(set) << 5) + offset

  private def beatOf(line: BigInt, i: Int): BigInt =
    (line >> (64 * i)) & ((BigInt(1) << 64) - 1)

  "S1 four simultaneous GetS requests must each be accepted and granted exactly once" in {
    simulate(new BreezeL2Home(smallL2Cfg, numHarts = 4)) { dut =>
      val h = new SmallL2Harness(dut, new DTestMem, numHarts = 4)
      val line = smallRamAddr(0, 0) & ~BigInt(31)
      h.resetAllLogs()
      h.simultaneousGetS(line, 0 until 4)
      for (hart <- 0 until 4) {
        h.grantCount(hart) mustBe 1
        h.l1State(line, hart) mustBe Some('S')
        h.l1Data(line, hart) mustBe Some(h.mem.readLine(line))
      }
      // All four data grants must agree with memory and with each other.
      h.grantLog.map(_.data).distinct.length mustBe 1
      h.grantLog.map(_.data).head mustBe h.mem.readLine(line)
      // Only the first serialized request missed to memory: 4 read beats.
      h.wbLog.length mustBe 4
      h.wbLog.map(_._2).foreach(_ mustBe false)
      h.wbLog.map(_._1) mustBe (0 until 4).map(i => line + i * 8)
      // Every grant echoes the request txnId of its own hart (1..4).
      h.grantLog.map(_.txnId).sorted mustBe Seq[BigInt](0, 1, 2, 3)
    }
  }

  "S2 a four-way SHARED line upgrade must wait for all three probe Acks before GrantM" in {
    simulate(new BreezeL2Home(smallL2Cfg, numHarts = 4)) { dut =>
      val h = new SmallL2Harness(dut, new DTestMem, numHarts = 4)
      val line = smallRamAddr(0, 1) & ~BigInt(31)
      for (hart <- 0 until 4) {
        h.getS(line, hart)
        h.l1State(line, hart) mustBe Some('S')
      }

      // Stage the upgrade: probes accepted one hart at a time on different
      // cycles; responses forced to return in 3, 1, 2 order.
      h.resetAllLogs()
      h.probeReadyMask.indices.foreach(i => h.probeReadyMask(i) = false)
      h.probeLatency(1) = 2
      h.probeLatency(2) = 4
      h.probeLatency(3) = 1
      h.respOrder ++= Seq(3, 1, 2)

      h.beginRequest(BreezeCoherenceOpcode.GetM, line, hart = 0, txnId = 3)
      h.grantPortReady(0, ready = true)
      for (target <- Seq(1, 2, 3)) {
        h.probeReadyMask(target) = true
        var accepted = false
        var cycles = 0
        while (!accepted && cycles < 2000) {
          assert(!h.grantValid(0), "hart0 received a grant before the last probe Ack")
          h.step()
          cycles += 1
          accepted = h.probeLog.exists(e => e.hart == target && e.txnId == BigInt(3))
        }
        assert(accepted, s"probe for hart $target was never accepted")
        h.probeReadyMask(target) = false
      }

      // Exactly three ProbeInv to harts 1..3, nothing to the requester.
      h.probeLog.map(_.hart).toSet mustBe Set(1, 2, 3)
      h.probeLog.map(_.opcode).foreach(_ mustBe BreezeProbeOpcode.ProbeInv.litValue)
      h.probeLog.map(_.txnId).foreach(_ mustBe BigInt(3))
      h.probeLog.map(_.lineAddr).foreach(_ mustBe line)

      // Before the last Ack handshakes, no grant may appear, cycle by cycle.
      var cycles = 0
      var allAcked = false
      while (!allAcked && cycles < 4000) {
        assert(!h.grantValid(0), "hart0 received a grant before the last probe Ack")
        h.step()
        cycles += 1
        allAcked = h.respLog.length == 3
      }
      if (!allAcked) fail("probe responses never completed")

      // Responses arrived in 3, 1, 2 order and each hart answered exactly once.
      h.respLog.map(_.hart) mustBe Seq(3, 1, 2)
      h.respLog.map(e => (e.txnId, e.lineAddr)).foreach(_ mustBe ((BigInt(3), line)))
      h.respLog.map(_.hasData).foreach(_ mustBe false)

      // Exactly one M grant to hart0, and nothing afterwards.
      val g = h.waitGrantEvent(0)
      g.error mustBe false
      g.state mustBe BreezeGrantState.M.litValue
      g.txnId mustBe BigInt(3)
      g.lineAddr mustBe line
      if (g.hasData) g.data mustBe h.mem.readLine(line)
      for (_ <- 0 until 8) {
        assert(!h.grantValid(0), "hart0 received a duplicate grant")
        h.step()
      }
      h.grantCount(0) mustBe 1
      h.grantCount(1) mustBe 0
      h.grantCount(2) mustBe 0
      h.grantCount(3) mustBe 0

      h.l1State(line, 0) mustBe Some('M')
      h.l1Contains(line, 1) mustBe false
      h.l1Contains(line, 2) mustBe false
      h.l1Contains(line, 3) mustBe false
    }
  }

  "S3 a dirty owner on hart 3 must transfer the latest full line to hart 1 and then share with hart 2" in {
    simulate(new BreezeL2Home(smallL2Cfg, numHarts = 4)) { dut =>
      val h = new SmallL2Harness(dut, new DTestMem, numHarts = 4)
      val line = smallRamAddr(0, 2) & ~BigInt(31)
      val modified = BigInt("0badf00d0badf00d0011223344556677", 16)

      h.getM(line, hart = 3)
      h.l1Store(line, modified, hart = 3)

      h.resetAllLogs()
      val (transferred, transferErr) = h.getM(line, hart = 1, txnId = 2)
      transferErr mustBe false
      transferred mustBe modified
      // Only hart 3 was recalled, and its response carried the latest line.
      h.probeLog mustBe Seq(SmallProbeEvent(3, 2, line, BreezeProbeOpcode.ProbeRecallInv.litValue))
      h.respLog mustBe Seq(SmallRespEvent(3, 2, line, hasData = true, data = modified))
      h.l1Contains(line, 3) mustBe false
      h.l1State(line, 1) mustBe Some('M')
      h.l1Data(line, 1) mustBe Some(modified)
      // A pure ownership transfer never touches memory.
      h.wbLog mustBe empty

      // hart 2 reads next: the Home must recall hart 1 and end with S/S.
      h.resetAllLogs()
      val (_, shared, shareErr) = h.getS(line, hart = 2, txnId = 3)
      shareErr mustBe false
      shared mustBe modified
      h.probeLog mustBe Seq(SmallProbeEvent(1, 3, line, BreezeProbeOpcode.ProbeToS.litValue))
      h.respLog mustBe Seq(SmallRespEvent(1, 3, line, hasData = true, data = modified))
      h.l1State(line, 1) mustBe Some('S')
      h.l1State(line, 2) mustBe Some('S')
      h.l1Data(line, 2) mustBe Some(modified)
      h.wbLog mustBe empty
    }
  }

  "S4 inclusive eviction of a four-sharer line must invalidate every L1 and refill on re-access" in {
    simulate(new BreezeL2Home(smallL2Cfg, numHarts = 4)) { dut =>
      val h = new SmallL2Harness(dut, new DTestMem, numHarts = 4)
      val target = smallRamAddr(0, 3) & ~BigInt(31)
      val newLine = smallRamAddr(8, 3) & ~BigInt(31)
      for (hart <- 0 until 4) h.getS(target, hart)
      for (tag <- 1 until 8) h.getS(smallRamAddr(tag, 3)) // fills the 7 other ways

      h.resetAllLogs()
      h.beginRequest(BreezeCoherenceOpcode.GetS, newLine, hart = 0, txnId = 2)
      h.grantPortReady(0, ready = true)

      var cycles = 0
      var allAcked = false
      while (!allAcked && cycles < 4000) {
        assert(h.wbLog.isEmpty, "memory traffic started before all victim Acks")
        assert(!h.grantValid(0), "grant issued before all victim Acks")
        h.step()
        cycles += 1
        allAcked = h.respLog.length == 4
      }
      if (!allAcked) fail("victim invalidations never completed")

      h.probeLog.length mustBe 4
      h.probeLog.map(_.hart).toSet mustBe Set(0, 1, 2, 3)
      h.probeLog.map(_.opcode).foreach(_ mustBe BreezeProbeOpcode.ProbeInv.litValue)
      h.probeLog.map(_.txnId).foreach(_ mustBe BigInt(2))
      h.probeLog.map(_.lineAddr).foreach(_ mustBe target)

      val g = h.waitGrantEvent(0)
      g.error mustBe false
      g.lineAddr mustBe newLine
      // Clean SHARED victim: no writeback, exactly 4 ascending read beats.
      h.wbLog.length mustBe 4
      h.wbLog.map(_._2).foreach(_ mustBe false)
      h.wbLog.map(_._1) mustBe (0 until 4).map(i => newLine + i * 8)

      for (hart <- 0 until 4) h.l1Contains(target, hart) mustBe false

      // Re-accessing the evicted line must refill from memory again.
      h.resetWbLog()
      h.getS(target, hart = 0)
      h.wbLog.length mustBe 4
      h.wbLog.map(_._2).foreach(_ mustBe false)
    }
  }

  "S5 eviction of a hart-3 dirty UNIQUE victim must write back the recalled line, preserving it on error" in {
    simulate(new BreezeL2Home(smallL2Cfg, numHarts = 4)) { dut =>
      val h = new SmallL2Harness(dut, new DTestMem, numHarts = 4)
      val victim = smallRamAddr(0, 4) & ~BigInt(31)
      val newLine = smallRamAddr(8, 4) & ~BigInt(31)
      val dirty = BigInt("5a5aa5a55a5aa5a5fedcba9876543210", 16)

      h.getM(victim, hart = 3)
      h.l1Store(victim, dirty, hart = 3)
      for (tag <- 1 until 8) h.getS(smallRamAddr(tag, 4))

      h.resetAllLogs()
      h.beginRequest(BreezeCoherenceOpcode.GetS, newLine, hart = 0, txnId = 1)
      h.grantPortReady(0, ready = true)

      var cycles = 0
      var allAcked = false
      while (!allAcked && cycles < 4000) {
        assert(h.wbLog.isEmpty, "writeback started before the recall Ack")
        assert(!h.grantValid(0), "grant issued before the recall Ack")
        h.step()
        cycles += 1
        allAcked = h.respLog.length == 1
      }
      if (!allAcked) fail("victim recall never completed")

      h.probeLog mustBe Seq(SmallProbeEvent(3, 1, victim, BreezeProbeOpcode.ProbeRecallInv.litValue))
      h.respLog mustBe Seq(SmallRespEvent(3, 1, victim, hasData = true, data = dirty))

      val g = h.waitGrantEvent(0)
      g.error mustBe false
      // Dirty victim: 4 ascending write beats carrying the recalled line,
      // then 4 ascending read beats for the refill.
      h.wbLog.length mustBe 8
      val (writes, reads) = h.wbLog.splitAt(4)
      writes.map(_._2).foreach(_ mustBe true)
      writes.map(_._1) mustBe (0 until 4).map(i => victim + i * 8)
      h.wbDataLog mustBe (0 until 4).map(i => beatOf(dirty, i))
      reads.map(_._2).foreach(_ mustBe false)
      reads.map(_._1) mustBe (0 until 4).map(i => newLine + i * 8)
      h.mem.readLine(victim) mustBe dirty
      h.l1Contains(victim, 3) mustBe false
    }
  }

  "S5-error a failed writeback must keep the dirty UNIQUE victim readable instead of losing it" in {
    simulate(new BreezeL2Home(smallL2Cfg, numHarts = 4)) { dut =>
      val h = new SmallL2Harness(dut, new DTestMem, numHarts = 4)
      val victim = smallRamAddr(0, 5) & ~BigInt(31)
      val newLine = smallRamAddr(8, 5) & ~BigInt(31)
      val dirty = BigInt("0f0f0f0f0f0f0f0ffedcba9876543210", 16)

      h.getM(victim, hart = 3)
      h.l1Store(victim, dirty, hart = 3)
      for (tag <- 1 until 8) h.getS(smallRamAddr(tag, 5))

      h.resetAllLogs()
      h.errorOnAddresses = (0 until 4).map(i => victim + i * 8).toSet
      h.beginRequest(BreezeCoherenceOpcode.GetS, newLine, hart = 0, txnId = 2)
      h.grantPortReady(0, ready = true)
      val g = h.waitGrantEvent(0)
      g.error mustBe true
      h.errorOnAddresses = Set.empty
      // The recall data was landed in the L2 before the failed writeback, so
      // the victim survives as a clean directory hit with the dirty data.
      h.resetAllLogs()
      val (_, data, err2) = h.getS(victim, hart = 2)
      err2 mustBe false
      data mustBe dirty
      h.probeLog mustBe empty
      h.wbLog mustBe empty
    }
  }

  "S6 four I$ refill pulses captured while busy must answer once each and never join the D$ sharers" in {
    simulate(new BreezeL2Home(smallL2Cfg, numHarts = 4)) { dut =>
      val h = new SmallL2Harness(dut, new DTestMem, numHarts = 4)
      val target = smallRamAddr(0, 6) & ~BigInt(31)
      val filler = smallRamAddr(1, 6) & ~BigInt(31)

      h.resetAllLogs()
      // Occupy the Home with a slow refill, then pulse all four I$ ports in
      // the same busy window (their latches must capture unconditionally).
      h.beginRequest(BreezeCoherenceOpcode.GetS, filler, hart = 0, txnId = 3)
      h.step() // state = LookupRead, definitely not Idle
      for (hart <- 0 until 4) h.pulseInstr(hart, target)
      h.step()
      for (hart <- 0 until 4) h.dropInstr(hart)

      val g = h.waitGrantEvent(0)
      g.error mustBe false

      var cycles = 0
      while (h.instrRespCount.sum < 4 && cycles < 8000) {
        h.step()
        cycles += 1
      }
      for (hart <- 0 until 4) h.instrRespCount(hart) mustBe 1

      // The I$ line is dir=NONE: a D$ GetM hits without probing anyone.
      h.resetProbeLog()
      h.resetWbLog()
      val (mData, mErr) = h.getM(target, hart = 2)
      mErr mustBe false
      mData mustBe h.mem.readLine(target)
      h.probeLog mustBe empty
      h.wbLog mustBe empty
    }
  }
}
