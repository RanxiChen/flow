package flow.cache

import chisel3._
import chisel3.simulator.PeekPokeAPI
import chisel3.simulator.scalatest.ChiselSim
import flow.config.L2CacheGeometry
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import scala.collection.mutable

/** Mock per-hart L1D clients + a Wishbone memory slave around the L2/Home.
  *
  * Oracle discipline (T3):
  *   - every Wishbone beat is logged (byte address, isWrite), so writeback
  *     beats must appear exactly once, in ascending order (W-02/W-04
  *     regressions);
  *   - every probe is logged with (hart, lineAddr, opcode) so inclusive
  *     eviction and owner recall are directly observable;
  *   - the mock L1 tracks (state, data) per line, answers probes according to
  *     MESI rules, and records grant outcomes.
  */
final class L2HomeHarness(
    dut: BreezeL2Home,
    val mem: DTestMem,
    numHarts: Int = 1,
    wbLatency: Int = 2,
    probeLatency: Int = 1
) extends Assertions with PeekPokeAPI {

  // Mock L1 contents: (hart,lineAddr) -> (state 'S'/'E'/'M', line data).
  val l1 = mutable.Map.empty[(Int, BigInt), (Char, BigInt)]
  val wbLog = mutable.ArrayBuffer.empty[(BigInt, Boolean)]
  val probeLog = mutable.ArrayBuffer.empty[(Int, BigInt, String)]

  /** Byte addresses whose Wishbone beats must be answered with err. */
  var errorOnAddresses = Set.empty[BigInt]

  // ----- Wishbone slave state -----
  private var wbBusy = false
  private var wbCountdown = 0
  private var wbAddr = BigInt(0)
  private var wbWe = false
  private var wbData = BigInt(0)
  private var wbJustAcked = false

  // ----- coherence client state (per hart) -----
  private var reqActive = false
  private var reqHart = 0
  private var grantResult: Option[(BigInt, BigInt, Boolean, Boolean)] = None

  // probe response pipeline: (hart, txnId, lineAddr, opcode) pending
  private var probePending: Option[(Int, BigInt, BigInt, BigInt)] = None
  private var probeCountdown = 0
  private var probeAnswer: Option[(Boolean, BigInt)] = None

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
    dut.io.coherenceProbe(h).ready.poke(true.B)
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
  private def probePort(h: Int) = dut.io.coherenceProbe(h)
  private def probeRespPort(h: Int) = dut.io.coherenceProbeResp(h)

  /** Advance one cycle, servicing the Wishbone slave and any pending probe. */
  private def step(): Unit = {
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

    // --- probe channel: accept immediately (ready=1), answer after probeLatency ---
    for (h <- 0 until numHarts) {
      val p = probePort(h)
      p.ready.poke(true.B)
      if (p.valid.peek().litToBoolean) {
        assert(probePending.isEmpty, "mock L1 supports one outstanding probe")
        probeLog += ((h, p.lineAddr.peek().litValue, p.opcode.peek().litValue.toString()))
        probePending = Some((h, p.txnId.peek().litValue, p.lineAddr.peek().litValue,
          p.opcode.peek().litValue))
        probeCountdown = probeLatency
        probeAnswer = None
      }
    }

    // Advance the response pipeline once per cycle, not once per hart.  Latch
    // the mock L1's answer exactly once so valid/data remain stable if Home
    // is still moving from ProbeReq to ProbeWait or applies backpressure.
    if (probePending.isDefined && probeCountdown > 0) {
      probeCountdown -= 1
    } else if (probePending.isDefined && probeAnswer.isEmpty) {
      val (hart, _, lineAddr, opcode) = probePending.get
      probeAnswer = Some(answerProbe(hart, lineAddr, opcode))
    }
    dut.io.coherenceProbeResp.zipWithIndex.foreach { case (rsp, h) =>
      val fire = probePending.exists(_._1 == h) && probeAnswer.isDefined
      rsp.valid.poke(fire.B)
      if (fire) {
        val Some((_, txn, lineAddr, _)) = probePending
        rsp.txnId.poke(txn.U)
        rsp.lineAddr.poke(lineAddr.U)
        rsp.srcHart.poke(h.U)
        rsp.ack.poke(true.B)
        val (hasData, data) = probeAnswer.get
        rsp.hasData.poke(hasData.B)
        rsp.lineData.poke(data.U)
        if (rsp.ready.peek().litToBoolean) {
          probePending = None
          probeAnswer = None
        }
      }
    }

    dut.clock.step(1)
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

  /** Drive one coherence request and wait for the grant. */
  private def request(
      op: BreezeCoherenceOpcode.Type,
      lineAddr: BigInt,
      hart: Int = 0,
      hasData: Boolean = false,
      data: BigInt = 0
  ): (BigInt, BigInt, Boolean, Boolean) = {
    assert(!reqActive, "harness supports one outstanding client request")
    reqActive = true
    reqHart = hart
    val req = dut.io.coherenceReq(hart)
    req.valid.poke(true.B)
    req.opcode.poke(op.litValue)
    req.srcHart.poke(hart.U)
    req.txnId.poke(1.U)
    req.lineAddr.poke(lineAddr.U)
    req.hasData.poke(hasData.B)
    req.lineData.poke(data.U)

    var cycles = 0
    var fired = false
    while (!fired && cycles < 4000) {
      if (req.ready.peek().litToBoolean) fired = true
      step()
      cycles += 1
    }
    if (!fired) fail("L2/Home never accepted the coherence request")
    req.valid.poke(false.B)

    // Wait for the grant.
    val grant = grantPort(hart)
    grant.ready.poke(true.B)
    var result: Option[(BigInt, BigInt, Boolean, Boolean)] = None
    cycles = 0
    while (result.isEmpty && cycles < 4000) {
      if (grant.valid.peek().litToBoolean) {
        result = Some((
          grant.grantState.peek().litValue,
          grant.lineData.peek().litValue,
          grant.hasData.peek().litToBoolean,
          grant.error.peek().litToBoolean
        ))
      }
      step()
      cycles += 1
    }
    grant.ready.poke(false.B)
    reqActive = false
    if (result.isEmpty) fail("L2/Home never granted the coherence request")
    result.get
  }

  private def lineOf(addr: BigInt): BigInt = addr & ~BigInt(31)

  def getS(addr: BigInt, hart: Int = 0): (BigInt, BigInt, Boolean) = {
    val (state, data, hasData, error) = request(BreezeCoherenceOpcode.GetS, lineOf(addr), hart)
    if (!error) {
      assert(hasData, "GetS grant must carry data")
      l1((hart, lineOf(addr))) = (if (state == BreezeGrantState.M.litValue) 'M'
        else if (state == BreezeGrantState.E.litValue) 'E' else 'S', data)
    }
    (state, data, error)
  }

  def getM(addr: BigInt, hart: Int = 0): (BigInt, Boolean) = {
    val (state, data, hasData, error) = request(BreezeCoherenceOpcode.GetM, lineOf(addr), hart)
    if (!error) {
      // An upgrade grant may omit the data; the mock L1 keeps its copy.
      val key = (hart, lineOf(addr))
      val merged = if (hasData) data else l1.getOrElse(key, ('S', BigInt(0)))._2
      l1(key) = ('M', merged)
    }
    (data, error)
  }

  def putS(addr: BigInt, hart: Int = 0): Boolean = {
    val (_, _, _, error) = request(BreezeCoherenceOpcode.PutS, lineOf(addr), hart)
    if (!error) l1.remove((hart, lineOf(addr)))
    error
  }

  def putM(addr: BigInt, data: BigInt, hart: Int = 0): Boolean = {
    val (_, _, _, error) = request(BreezeCoherenceOpcode.PutM, lineOf(addr), hart,
      hasData = true, data = data)
    if (!error) l1.remove((hart, lineOf(addr)))
    error
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

  /** Present two GetS requests together and prove that each is accepted and
    * granted exactly once while the blocking Home serializes them. */
  def simultaneousGetS(addr: BigInt): Seq[(BigInt, BigInt, Boolean, Boolean)] = {
    assert(numHarts == 2, "simultaneousGetS is the dual-hart T4 helper")
    val line = lineOf(addr)
    val accepted = Array(false, false)
    val results = Array.fill[Option[(BigInt, BigInt, Boolean, Boolean)]](2)(None)
    for (h <- 0 until 2) {
      val req = dut.io.coherenceReq(h)
      req.valid.poke(true.B)
      req.opcode.poke(BreezeCoherenceOpcode.GetS.litValue)
      req.srcHart.poke(h.U)
      req.txnId.poke((h + 1).U)
      req.lineAddr.poke(line.U)
      req.hasData.poke(false.B)
      req.lineData.poke(0.U)
      dut.io.coherenceGrant(h).ready.poke(true.B)
    }

    var cycles = 0
    while (results.exists(_.isEmpty) && cycles < 8000) {
      val fires = (0 until 2).map(h =>
        !accepted(h) && dut.io.coherenceReq(h).ready.peek().litToBoolean)
      for (h <- 0 until 2) {
        val grant = dut.io.coherenceGrant(h)
        if (grant.valid.peek().litToBoolean) {
          assert(results(h).isEmpty, s"hart $h received a duplicate grant")
          results(h) = Some((grant.grantState.peek().litValue,
            grant.lineData.peek().litValue, grant.hasData.peek().litToBoolean,
            grant.error.peek().litToBoolean))
        }
      }
      step()
      for (h <- 0 until 2 if fires(h)) {
        accepted(h) = true
        dut.io.coherenceReq(h).valid.poke(false.B)
      }
      cycles += 1
    }
    assert(accepted.forall(identity), "both simultaneous requests must be accepted")
    if (results.exists(_.isEmpty)) fail("dual simultaneous GetS timed out")
    for (h <- 0 until 2) {
      dut.io.coherenceGrant(h).ready.poke(false.B)
      val result = results(h).get
      assert(result._3 && !result._4, s"hart $h GetS grant must carry data without error")
      l1((h, line)) = ('S', result._2)
    }
    results.map(_.get).toSeq
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

  def resetWbLog(): Unit = wbLog.clear()
  def resetProbeLog(): Unit = probeLog.clear()
}

class BreezeL2HomeSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val l2Cfg = L2CacheGeometry(capacityBytes = 16384)
  private val dualL2Cfg = L2CacheGeometry(capacityBytes = 32768)

  // main_ram region; set = addr[10:5], tag = addr[31:11].
  private def ramAddr(tagLsb: Int, set: Int, offset: Int = 0): BigInt =
    BigInt(0x80000000L) + (BigInt(tagLsb) << 11) + (BigInt(set) << 5) + offset

  // dual has 128 sets, so its physical tag starts at bit 12.
  private def dualRamAddr(tagLsb: Int, set: Int, offset: Int = 0): BigInt =
    BigInt(0x80000000L) + (BigInt(tagLsb) << 12) + (BigInt(set) << 5) + offset

  "L2/Home should refill a GetS miss from memory with all four beats" in {
    simulate(new BreezeL2Home(l2Cfg, numHarts = 1)) { dut =>
      val h = new L2HomeHarness(dut, new DTestMem)
      val (state, data, error) = h.getS(ramAddr(0, 0))
      error mustBe false
      state mustBe BreezeGrantState.S.litValue
      data mustBe h.mem.readLine(ramAddr(0, 0))
      // Exactly four read beats at ascending word addresses (W-02/W-04).
      h.wbLog.length mustBe 4
      h.wbLog.map(_._2).foreach(_ mustBe false)
      h.wbLog.map(_._1) mustBe (0 until 4).map(i => ramAddr(0, 0) + i * 8)

      // A second GetS hits in the L2: no memory traffic at all.
      h.resetWbLog()
      val (_, data2, error2) = h.getS(ramAddr(0, 0))
      error2 mustBe false
      data2 mustBe h.mem.readLine(ramAddr(0, 0))
      h.wbLog mustBe empty
    }
  }

  "L2/Home should keep a dirty PutM line in the L2 without memory traffic" in {
    simulate(new BreezeL2Home(l2Cfg, numHarts = 1)) { dut =>
      val h = new L2HomeHarness(dut, new DTestMem)
      val (_, errorM) = h.getM(ramAddr(1, 1))
      errorM mustBe false
      val newData = BigInt("00112233445566778899aabbccddeeff", 16)
      h.resetWbLog()
      h.putM(ramAddr(1, 1), newData) mustBe false
      h.wbLog mustBe empty

      // The line is now dir=NONE/dirtyToMemory: a GetS hit returns the dirty
      // data straight from the L2, still without memory traffic.
      val (_, data, error) = h.getS(ramAddr(1, 1))
      error mustBe false
      data mustBe newData
      h.wbLog mustBe empty
    }
  }

  "L2/Home should write back a dirty victim with ascending beats" in {
    simulate(new BreezeL2Home(l2Cfg, numHarts = 1)) { dut =>
      val h = new L2HomeHarness(dut, new DTestMem)
      val dirty = BigInt("ffeeddccbbaa99001122334455667700", 16)
      h.getM(ramAddr(0, 2))
      h.putM(ramAddr(0, 2), dirty) // dir=NONE, dirtyToMemory=1
      // Fill the remaining 7 ways of set 2, then overflow it with tag 8.
      for (tag <- 1 until 8) h.getS(ramAddr(tag, 2))
      h.resetWbLog()
      h.getS(ramAddr(8, 2))
      // The dirty victim is written back before the refill: 4 ascending write
      // beats, then 4 ascending read beats.
      h.wbLog.length mustBe 8
      val (writes, reads) = h.wbLog.splitAt(4)
      writes.map(_._2).foreach(_ mustBe true)
      writes.map(_._1) mustBe (0 until 4).map(i => ramAddr(0, 2) + i * 8)
      reads.map(_._2).foreach(_ mustBe false)
      reads.map(_._1) mustBe (0 until 4).map(i => ramAddr(8, 2) + i * 8)
      h.mem.readLine(ramAddr(0, 2)) mustBe dirty
    }
  }

  "I$ refill should allocate in the L2 but never join the D$ directory" in {
    simulate(new BreezeL2Home(l2Cfg, numHarts = 1)) { dut =>
      val h = new L2HomeHarness(dut, new DTestMem)
      val (data, error) = h.instr(ramAddr(3, 3))
      error mustBe false
      data mustBe h.mem.readLine(ramAddr(3, 3))

      // Hit: no memory traffic on the second refill.
      h.resetWbLog()
      h.instr(ramAddr(3, 3))._2 mustBe false
      h.wbLog mustBe empty

      // The I$ line is dir=NONE: a D$ GetS hits without probing anyone.
      h.resetProbeLog()
      val (_, data2, error2) = h.getS(ramAddr(3, 3))
      error2 mustBe false
      data2 mustBe h.mem.readLine(ramAddr(3, 3))
      h.probeLog mustBe empty
    }
  }

  "I$ refill of a UNIQUE line should recall the D$ owner's data" in {
    simulate(new BreezeL2Home(l2Cfg, numHarts = 1)) { dut =>
      val h = new L2HomeHarness(dut, new DTestMem)
      h.getM(ramAddr(4, 4))
      val modified = BigInt("0123456789abcdef02468ace13579bdf", 16)
      h.l1Store(ramAddr(4, 4), modified)

      h.resetProbeLog()
      val (data, error) = h.instr(ramAddr(4, 4))
      error mustBe false
      data mustBe modified
      // The owner was probed ToS and now holds S.
      h.probeLog.length mustBe 1
      h.probeLog.head._3 mustBe BreezeProbeOpcode.ProbeToS.litValue.toString()
      h.l1State(ramAddr(4, 4), 0) mustBe Some('S')

      // The line is SHARED now: a D$ GetS hits without probes or memory.
      h.resetWbLog()
      h.resetProbeLog()
      val (_, data2, error2) = h.getS(ramAddr(4, 4))
      error2 mustBe false
      data2 mustBe modified
      h.probeLog mustBe empty
      h.wbLog mustBe empty
    }
  }

  "L2/Home eviction of a SHARED victim should invalidate the L1 sharer" in {
    simulate(new BreezeL2Home(l2Cfg, numHarts = 1)) { dut =>
      val h = new L2HomeHarness(dut, new DTestMem)
      h.getS(ramAddr(0, 5)) // dir=SHARED{0}
      for (tag <- 1 until 8) h.getS(ramAddr(tag, 5))
      h.resetProbeLog()
      h.getS(ramAddr(8, 5)) // evicts tag 0, which is SHARED by hart 0
      h.probeLog.length mustBe 1
      h.probeLog.head._2 mustBe (ramAddr(0, 5) & ~BigInt(31))
      h.probeLog.head._3 mustBe BreezeProbeOpcode.ProbeInv.litValue.toString()
      h.l1Contains(ramAddr(0, 5), 0) mustBe false

      // Re-accessing the evicted line misses and refills from memory again.
      h.resetWbLog()
      h.getS(ramAddr(0, 5))
      h.wbLog.length mustBe 4
    }
  }

  "A refill error should not install and should not lose the evicted victim" in {
    simulate(new BreezeL2Home(l2Cfg, numHarts = 1)) { dut =>
      val h = new L2HomeHarness(dut, new DTestMem)
      val dirty = BigInt("0badf00ddeadbeef0011223344556677", 16)
      h.getM(ramAddr(0, 6))
      h.putM(ramAddr(0, 6), dirty) // dirty line in L2, dir=NONE
      for (tag <- 1 until 8) h.getS(ramAddr(tag, 6))

      // The 9th line overflows the set: victim writeback succeeds, refill fails.
      h.errorOnAddresses = (0 until 4).map(i => ramAddr(8, 6) + i * 8).toSet
      val (_, _, error) = h.getS(ramAddr(8, 6))
      error mustBe true
      h.errorOnAddresses = Set.empty

      // The victim's dirty data reached memory and the failed line is not
      // installed; a retry succeeds.
      h.mem.readLine(ramAddr(0, 6)) mustBe dirty
      val (_, data, error2) = h.getS(ramAddr(8, 6))
      error2 mustBe false
      data mustBe h.mem.readLine(ramAddr(8, 6))
    }
  }

  "A victim writeback error should preserve the dirty victim in the L2" in {
    simulate(new BreezeL2Home(l2Cfg, numHarts = 1)) { dut =>
      val h = new L2HomeHarness(dut, new DTestMem)
      val dirty = BigInt("5a5a5a5a5a5a5a5aa5a5a5a5a5a5a5a5", 16)
      h.getM(ramAddr(0, 7))
      h.putM(ramAddr(0, 7), dirty)
      for (tag <- 1 until 8) h.getS(ramAddr(tag, 7))

      // Overflow the set, but the victim writeback fails.
      h.errorOnAddresses = (0 until 4).map(i => ramAddr(0, 7) + i * 8).toSet
      val (_, _, error) = h.getS(ramAddr(8, 7))
      error mustBe true
      h.errorOnAddresses = Set.empty

      // The victim survived inside the L2 with its dirty data: a GetS hit
      // returns it without any memory traffic.
      h.resetWbLog()
      val (_, data, error2) = h.getS(ramAddr(0, 7))
      error2 mustBe false
      data mustBe dirty
      h.wbLog mustBe empty
    }
  }

  "T4 dual MSI should cover sharing, upgrades, dirty transfer and serialization" in {
    simulate(new BreezeL2Home(dualL2Cfg, numHarts = 2)) { dut =>
      val h = new L2HomeHarness(dut, new DTestMem, numHarts = 2)

      val sharingAddr = dualRamAddr(0, 9)
      val (_, data0, error0) = h.getS(sharingAddr, hart = 0)
      val (_, data1, error1) = h.getS(sharingAddr, hart = 1)
      error0 mustBe false
      error1 mustBe false
      data1 mustBe data0
      h.l1State(sharingAddr, 0) mustBe Some('S')
      h.l1State(sharingAddr, 1) mustBe Some('S')
      h.probeLog mustBe empty

      val upgradeAddr = dualRamAddr(1, 10)
      h.getS(upgradeAddr, hart = 0)
      h.getS(upgradeAddr, hart = 1)
      h.resetProbeLog()
      val (_, upgradeError) = h.getM(upgradeAddr, hart = 0)
      upgradeError mustBe false
      h.probeLog mustBe Seq((1, upgradeAddr & ~BigInt(31),
        BreezeProbeOpcode.ProbeInv.litValue.toString()))
      h.l1State(upgradeAddr, 0) mustBe Some('M')
      h.l1Contains(upgradeAddr, 1) mustBe false

      val dirtyReadAddr = dualRamAddr(2, 11)
      val modified = BigInt("123456789abcdef00112233445566778899aabbccddeeff", 16)
      h.getM(dirtyReadAddr, hart = 0)
      h.l1Store(dirtyReadAddr, modified, hart = 0)
      h.resetProbeLog()
      val (_, dirtyReadData, dirtyReadError) = h.getS(dirtyReadAddr, hart = 1)
      dirtyReadError mustBe false
      dirtyReadData mustBe modified
      h.probeLog mustBe Seq((0, dirtyReadAddr & ~BigInt(31),
        BreezeProbeOpcode.ProbeToS.litValue.toString()))
      h.l1State(dirtyReadAddr, 0) mustBe Some('S')
      h.l1State(dirtyReadAddr, 1) mustBe Some('S')
      h.l1Data(dirtyReadAddr, 0) mustBe Some(modified)
      h.l1Data(dirtyReadAddr, 1) mustBe Some(modified)

      val transferAddr = dualRamAddr(3, 12)
      val first = BigInt("fedcba98765432100011223344556677", 16)
      val second = BigInt("0badf00d0badf00d8899aabbccddeeff", 16)
      h.getM(transferAddr, hart = 0)
      h.l1Store(transferAddr, first, hart = 0)
      h.resetProbeLog()
      val (recalled, transferError) = h.getM(transferAddr, hart = 1)
      transferError mustBe false
      recalled mustBe first
      h.probeLog mustBe Seq((0, transferAddr & ~BigInt(31),
        BreezeProbeOpcode.ProbeRecallInv.litValue.toString()))
      h.l1Contains(transferAddr, 0) mustBe false
      h.l1State(transferAddr, 1) mustBe Some('M')
      h.l1Store(transferAddr, second, hart = 1)
      val (_, reread, readError) = h.getS(transferAddr, hart = 0)
      readError mustBe false
      reread mustBe second
      h.l1State(transferAddr, 0) mustBe Some('S')
      h.l1State(transferAddr, 1) mustBe Some('S')

      val differentWordAddr = dualRamAddr(4, 13)
      val wordMask = (BigInt(1) << 64) - 1
      val word0 = BigInt("1122334455667788", 16)
      val word1 = BigInt("8877665544332211", 16)
      h.getM(differentWordAddr, hart = 0)
      val initial = h.l1Data(differentWordAddr, 0).get
      h.l1Store(differentWordAddr, (initial & ~wordMask) | word0, hart = 0)
      h.getM(differentWordAddr, hart = 1)
      val afterWord1 = (h.l1Data(differentWordAddr, 1).get & ~(wordMask << 64)) |
        (word1 << 64)
      h.l1Store(differentWordAddr, afterWord1, hart = 1)
      val (_, finalData, differentWordError) = h.getS(differentWordAddr, hart = 0)
      differentWordError mustBe false
      (finalData & wordMask) mustBe word0
      ((finalData >> 64) & wordMask) mustBe word1

      val raceAddr = dualRamAddr(5, 14)
      h.resetWbLog()
      val results = h.simultaneousGetS(raceAddr)
      results.length mustBe 2
      results.map(_._2).distinct.length mustBe 1
      h.l1State(raceAddr, 0) mustBe Some('S')
      h.l1State(raceAddr, 1) mustBe Some('S')
      // Only the first serialized request misses to memory.
      h.wbLog.count(entry => !entry._2) mustBe 4
    }
  }
}
