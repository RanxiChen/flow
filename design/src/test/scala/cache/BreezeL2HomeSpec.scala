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

  // Mock L1 contents: lineAddr -> (state 'S'/'E'/'M', 256-bit line data).
  val l1 = mutable.Map.empty[BigInt, (Char, BigInt)]
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
  private var probePending: Option[(Int, BigInt, BigInt, BreezeProbeOpcode.Type)] = None
  private var probeCountdown = 0

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

    // --- probe channel: latch and schedule the mock-L1 response ---
    for (h <- 0 until numHarts) {
      val p = probePort(h)
      if (p.valid.peek().litToBoolean && probePending.isEmpty) {
        probeLog += ((h, p.lineAddr.peek().litValue, p.opcode.peek().litValue.toString()))
        probePending = Some((h, p.txnId.peek().litValue, p.lineAddr.peek().litValue,
          BreezeProbeOpcode(p.opcode.peek().litValue.toInt)))
        probeCountdown = probeLatency
        // The DUT holds valid until ready; we never drive probe.ready from the
        // client side, so "accept" is implicit: the DUT sees ready only via its
        // own probeResp handshake. Drive nothing here; the response below
        // completes the pair.
        probeRespPort(h) // silence unused
      }
    }
    // NOTE: the probe channel's ready is driven by the DUT's probe service; the
    // mock accepts a probe as soon as valid is observed and answers it after
    // probeLatency cycles.
    dut.io.coherenceProbeResp.zipWithIndex.foreach { case (rsp, h) =>
      val fire = probePending.exists(_._1 == h) && probeCountdown == 0
      rsp.valid.poke(fire.B)
      if (fire) {
        val Some((_, txn, lineAddr, opcode)) = probePending
        rsp.txnId.poke(txn.U)
        rsp.lineAddr.poke(lineAddr.U)
        rsp.srcHart.poke(h.U)
        rsp.ack.poke(true.B)
        val (hasData, data) = answerProbe(lineAddr, opcode)
        rsp.hasData.poke(hasData.B)
        rsp.lineData.poke(data.U)
        if (rsp.ready.peek().litToBoolean) probePending = None
      } else if (probePending.isDefined && probeCountdown > 0) {
        probeCountdown -= 1
      }
    }

    dut.clock.step(1)
  }

  /** Mock-L1 probe answer following MESI rules; returns (hasData, data). */
  private def answerProbe(lineAddr: BigInt, opcode: BreezeProbeOpcode.Type): (Boolean, BigInt) = {
    val entry = l1.get(lineAddr)
    opcode match {
      case BreezeProbeOpcode.ProbeInv =>
        entry match {
          case Some(('M', data)) => l1.remove(lineAddr); (true, data)
          case Some((s, _)) => assert(s == 'S' || s == 'E', "ProbeInv to M-only rule broken"); l1.remove(lineAddr); (false, BigInt(0))
          case None => (false, BigInt(0))
        }
      case BreezeProbeOpcode.ProbeToS =>
        entry match {
          case Some(('M', data)) => l1(lineAddr) = ('S', data); (true, data)
          case Some(('E', data)) => l1(lineAddr) = ('S', data); (false, BigInt(0))
          case Some(('S', _)) => (false, BigInt(0))
          case None => (false, BigInt(0))
        }
      case BreezeProbeOpcode.ProbeRecallInv =>
        entry match {
          case Some((s, data)) => assert(s == 'M' || s == 'E', "RecallInv to non-owner"); l1.remove(lineAddr); (true, data)
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
    req.opcode.poke(op.litValue.U)
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
      l1(lineOf(addr)) = (if (state == BreezeGrantState.M.litValue) 'M'
        else if (state == BreezeGrantState.E.litValue) 'E' else 'S', data)
    }
    (state, data, error)
  }

  def getM(addr: BigInt, hart: Int = 0): (BigInt, Boolean) = {
    val (state, data, hasData, error) = request(BreezeCoherenceOpcode.GetM, lineOf(addr), hart)
    if (!error) {
      // An upgrade grant may omit the data; the mock L1 keeps its copy.
      val merged = if (hasData) data else l1.getOrElse(lineOf(addr), ('S', BigInt(0)))._2
      l1(lineOf(addr)) = ('M', merged)
    }
    (data, error)
  }

  def putS(addr: BigInt, hart: Int = 0): Boolean = {
    val (_, _, _, error) = request(BreezeCoherenceOpcode.PutS, lineOf(addr), hart)
    if (!error) l1.remove(lineOf(addr))
    error
  }

  def putM(addr: BigInt, data: BigInt, hart: Int = 0): Boolean = {
    val (_, _, _, error) = request(BreezeCoherenceOpcode.PutM, lineOf(addr), hart,
      hasData = true, data = data)
    if (!error) l1.remove(lineOf(addr))
    error
  }

  /** Mock a local store inside the L1 (line must be held in M). */
  def l1Store(addr: BigInt, newData: BigInt): Unit = {
    val line = lineOf(addr)
    assert(l1.get(line).exists(_._1 == 'M'), "l1Store requires an M line")
    l1(line) = ('M', newData)
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

  // main_ram region; set = addr[10:5], tag = addr[31:11].
  private def ramAddr(tagLsb: Int, set: Int, offset: Int = 0): BigInt =
    BigInt(0x80000000L) + (BigInt(tagLsb) << 11) + (BigInt(set) << 5) + offset

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
      h.probeLog.head._3 mustBe "ProbeToS"
      h.l1(ramAddr(4, 4) & ~BigInt(31))._1 mustBe 'S'

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
      h.probeLog.head._3 mustBe "ProbeInv"
      h.l1.contains(ramAddr(0, 5) & ~BigInt(31)) mustBe false

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
}
