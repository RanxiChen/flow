package flow.cache

import chisel3._
import chisel3.simulator.PeekPokeAPI
import chisel3.simulator.scalatest.ChiselSim
import flow.config.DefaultDCacheConfig
import flow.interface.{BreezeAmoFunc, BreezeMemOp}
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import scala.collection.mutable

/** Byte-addressable memory model with deterministic power-on contents:
  * byte(addr) == addr & 0xff. */
final class DTestMem {
  private val bytes = mutable.Map.empty[BigInt, BigInt]
  private def byte(addr: BigInt): BigInt = bytes.getOrElse(addr, addr & 0xff)

  def readBytes(addr: BigInt, n: Int): BigInt =
    (0 until n).foldLeft(BigInt(0)) { (acc, i) => acc | (byte(addr + i) << (8 * i)) }

  def writeBytes(addr: BigInt, n: Int, data: BigInt, mask: BigInt): Unit = {
    for (i <- 0 until n) {
      if (((mask >> i) & 1) == 1) bytes(addr + i) = (data >> (8 * i)) & 0xff
    }
  }

  def readLine(addr: BigInt): BigInt = readBytes(addr, 32)
  def writeLine(addr: BigInt, data: BigInt): Unit =
    writeBytes(addr, 32, data, (BigInt(1) << 32) - 1)
}

/** One coherence request observed on the L1D request channel. */
final case class CoherentReq(opcode: BigInt, lineAddr: BigInt, txnId: BigInt,
    srcHart: BigInt, hasData: Boolean, data: BigInt)

/** One transaction observed on the scalar MMIO pulse interface. */
final case class MmioTxn(addr: BigInt, isWrite: Boolean, data: BigInt, mask: BigInt)

/** Probe response captured by injectProbe. */
final case class ProbeResult(srcHart: BigInt, txnId: BigInt, lineAddr: BigInt,
    hasData: Boolean, data: BigInt)

/** Home + memory + MMIO model around one coherent BreezeDCache.
  *
  * The model is a blocking single-transaction Home: one grant outstanding at
  * a time, GetS answered with `grantStateForGetS` (E by default, per the MESI
  * L2), GetM answered with M (data by default), PutS/PutM acknowledged and
  * PutM data landed in the test memory. Knobs stage the transient races:
  *
  *   - holdReqReady parks the L1D request (PutReq/UpgradeReq/RefillReq) so a
  *     probe can be injected before the handshake (cancel/convert rules);
  *   - holdGrant accepts the request but delays the grant so a probe can be
  *     injected while the L1D waits (upgrade race, SC reservation kill);
  *   - failNextGet errors the next GetS/GetM grant (never a Put ack);
  *     failNextPut errors the next Put ack (flush fatal path).
  *
  * Every request and MMIO pulse is logged so tests assert traffic identity
  * (hit = no traffic, eviction = PutS/PutM of the right line), not just data.
  */
final class DCacheHomeModel(dut: BreezeDCache, val mem: DTestMem, mmioLatency: Int = 2)
    extends Assertions with PeekPokeAPI {
  private val coh = dut.io.coherence

  val reqLog = mutable.ArrayBuffer.empty[CoherentReq]
  val mmioLog = mutable.ArrayBuffer.empty[MmioTxn]
  var grantsAccepted = 0
  var hpmAccess = 0
  var hpmMiss = 0
  var hpmUncached = 0

  var grantStateForGetS: BigInt = BreezeGrantState.E.litValue
  var getMHasData: Boolean = true
  var holdReqReady: Boolean = false
  var holdGrant: Boolean = false
  var failNextGet: Boolean = false
  var failNextPut: Boolean = false
  var mmioError: Boolean = false

  private case class PendingGrant(var delay: Int, txnId: BigInt, lineAddr: BigInt,
      state: BigInt, hasData: Boolean, data: BigInt, error: Boolean)
  private var pendingGrant: Option[PendingGrant] = None
  private var mmioPending: Option[MmioTxn] = None
  private var mmioCountdown = 0

  def fatalSeen: Boolean = dut.io.fatalError.peek().litToBoolean
  def lineOf(addr: BigInt): BigInt = addr & ~BigInt(31)

  // Deterministic idle values.
  dut.io.cpu.req.valid.poke(false.B)
  dut.io.arrayReq.foreach { p => p.valid.poke(false.B); p.bits.poke(0.U) }
  dut.io.cpu.req.addr.poke(0.U)
  dut.io.cpu.req.isWrite.poke(false.B)
  dut.io.cpu.req.sizeLog2.poke(3.U)
  dut.io.cpu.req.wdata.poke(0.U)
  dut.io.cpu.req.wmask.poke("hff".U)
  dut.io.cpu.req.memOp.poke(BreezeMemOp.Load.litValue)
  dut.io.cpu.req.amoFunc.poke(BreezeAmoFunc.Swap.litValue)
  dut.io.cpu.req.aq.poke(false.B)
  dut.io.cpu.req.rl.poke(false.B)
  dut.io.flushReq.poke(false.B)
  dut.io.resKill.poke(false.B)
  dut.io.mmioRsp.vld.poke(false.B)
  dut.io.mmioRsp.data.poke(0.U)
  dut.io.mmioRsp.error.poke(false.B)
  coh.req.ready.poke(false.B)
  coh.grant.valid.poke(false.B)
  coh.grant.dstHart.poke(0.U)
  coh.grant.txnId.poke(0.U)
  coh.grant.lineAddr.poke(0.U)
  coh.grant.grantState.poke(BreezeGrantState.S.litValue)
  coh.grant.hasData.poke(false.B)
  coh.grant.lineData.poke(0.U)
  coh.grant.error.poke(false.B)
  coh.probe.valid.poke(false.B)
  coh.probe.dstHart.poke(0.U)
  coh.probe.txnId.poke(0.U)
  coh.probe.lineAddr.poke(0.U)
  coh.probe.opcode.poke(BreezeProbeOpcode.ProbeInv.litValue)
  coh.probeResp.ready.poke(false.B)

  /** Advance one cycle, servicing the Home request/grant and the MMIO model. */
  def step(): Unit = {
    // --- grant channel ---
    coh.grant.valid.poke(false.B)
    val driveGrant = pendingGrant.exists(_.delay == 0) && !holdGrant
    if (driveGrant) {
      val g = pendingGrant.get
      coh.grant.valid.poke(true.B)
      coh.grant.txnId.poke(g.txnId.U)
      coh.grant.lineAddr.poke(g.lineAddr.U)
      coh.grant.grantState.poke(g.state)
      coh.grant.hasData.poke(g.hasData.B)
      coh.grant.lineData.poke(g.data.U)
      coh.grant.error.poke(g.error.B)
    } else {
      // The producer may change the payload immediately after a handshake.
      // Consumers must not keep using the live bus during refill installation.
      coh.grant.lineData.poke(0.U)
      coh.grant.hasData.poke(false.B)
      coh.grant.grantState.poke(BreezeGrantState.S.litValue)
      coh.grant.error.poke(false.B)
    }
    val grantFire = driveGrant && coh.grant.ready.peek().litToBoolean

    // --- request channel ---
    val readyNow = !holdReqReady && pendingGrant.isEmpty
    coh.req.ready.poke(readyNow.B)
    val reqFire = readyNow && coh.req.valid.peek().litToBoolean
    if (reqFire) {
      val opcode = coh.req.opcode.peek().litValue
      val lineAddr = coh.req.lineAddr.peek().litValue
      val txnId = coh.req.txnId.peek().litValue
      val hasData = coh.req.hasData.peek().litToBoolean
      val data = coh.req.lineData.peek().litValue
      reqLog += CoherentReq(opcode, lineAddr, txnId,
        coh.req.srcHart.peek().litValue, hasData, data)

      val isGetS = opcode == BreezeCoherenceOpcode.GetS.litValue
      val isGetM = opcode == BreezeCoherenceOpcode.GetM.litValue
      val isPutS = opcode == BreezeCoherenceOpcode.PutS.litValue
      val isPutM = opcode == BreezeCoherenceOpcode.PutM.litValue
      assert(isGetS || isGetM || isPutS || isPutM, s"unexpected request opcode $opcode")
      if (isPutM) {
        assert(hasData, "PutM must carry the dirty line")
        val err = failNextPut
        if (!err) mem.writeLine(lineAddr, data)
        failNextPut = false
        pendingGrant = Some(PendingGrant(1, txnId, lineAddr,
          BreezeGrantState.S.litValue, hasData = false, data = 0, error = err))
      } else if (isPutS) {
        val err = failNextPut
        failNextPut = false
        pendingGrant = Some(PendingGrant(1, txnId, lineAddr,
          BreezeGrantState.S.litValue, hasData = false, data = 0, error = err))
      } else {
        val err = failNextGet
        failNextGet = false
        val state = if (isGetM) BreezeGrantState.M.litValue else grantStateForGetS
        val withData = if (isGetM) getMHasData else true
        pendingGrant = Some(PendingGrant(1, txnId, lineAddr, state,
          hasData = withData && !err, data = mem.readLine(lineAddr), error = err))
      }
    }

    // --- MMIO scalar model ---
    if (mmioPending.isDefined && mmioCountdown == 0) {
      val t = mmioPending.get
      dut.io.mmioRsp.vld.poke(true.B)
      dut.io.mmioRsp.error.poke(mmioError.B)
      if (mmioError) {
        dut.io.mmioRsp.data.poke(0.U)
      } else if (t.isWrite) {
        mem.writeBytes(t.addr, 8, t.data, t.mask)
        dut.io.mmioRsp.data.poke(0.U)
      } else {
        dut.io.mmioRsp.data.poke(mem.readBytes(t.addr, 8).U)
      }
      mmioPending = None
    } else {
      dut.io.mmioRsp.vld.poke(false.B)
      dut.io.mmioRsp.error.poke(false.B)
      if (mmioPending.isDefined) mmioCountdown -= 1
    }
    if (dut.io.mmioReq.req.peek().litToBoolean) {
      assert(mmioPending.isEmpty, "DCache issued a second outstanding MMIO request")
      val t = MmioTxn(
        dut.io.mmioReq.addr.peek().litValue,
        dut.io.mmioReq.isWrite.peek().litToBoolean,
        dut.io.mmioReq.data.peek().litValue,
        dut.io.mmioReq.mask.peek().litValue)
      mmioPending = Some(t)
      mmioCountdown = mmioLatency
      mmioLog += t
    }

    if (dut.io.hpm.dcacheAccess.peek().litToBoolean) hpmAccess += 1
    if (dut.io.hpm.dcacheMiss.peek().litToBoolean) hpmMiss += 1
    if (dut.io.hpm.dcacheUncached.peek().litToBoolean) hpmUncached += 1

    dut.clock.step(1)
    if (grantFire) {
      grantsAccepted += 1
      pendingGrant = None
    }
    else pendingGrant.foreach(g => g.delay = math.max(0, g.delay - 1))
  }

  /** Present one CPU request pulse (exactly one cycle) and return. */
  def cpuStart(
      addr: BigInt,
      memOp: BreezeMemOp.Type,
      sizeLog2: Int = 3,
      wdata: BigInt = 0,
      wmask: BigInt = 0xff,
      amoFunc: BreezeAmoFunc.Type = BreezeAmoFunc.Swap
  ): Unit = {
    dut.io.cpu.req.valid.poke(true.B)
    dut.io.cpu.req.addr.poke(addr.U)
    dut.io.cpu.req.isWrite.poke((memOp == BreezeMemOp.Store).B)
    dut.io.cpu.req.sizeLog2.poke(sizeLog2.U)
    dut.io.cpu.req.wdata.poke(wdata.U)
    dut.io.cpu.req.wmask.poke(wmask.U)
    dut.io.cpu.req.memOp.poke(memOp.litValue)
    dut.io.cpu.req.amoFunc.poke(amoFunc.litValue)
    step()
    dut.io.cpu.req.valid.poke(false.B)
  }

  /** Wait for the single response and check no spurious extra response. */
  def cpuWait(): (BigInt, Boolean, Boolean) = {
    var result: Option[(BigInt, Boolean, Boolean)] = None
    var cycles = 0
    while (result.isEmpty && cycles < 4000) {
      if (dut.io.cpu.rsp.valid.peek().litToBoolean) {
        result = Some((
          dut.io.cpu.rsp.data.peek().litValue,
          dut.io.cpu.rsp.error.peek().litToBoolean,
          dut.io.cpu.rsp.isWriteAck.peek().litToBoolean))
      }
      step()
      cycles += 1
    }
    if (result.isEmpty) fail("DCache request timed out without a response")
    step()
    assert(!dut.io.cpu.rsp.valid.peek().litToBoolean, "spurious extra response cycle 1")
    step()
    assert(!dut.io.cpu.rsp.valid.peek().litToBoolean, "spurious extra response cycle 2")
    result.get
  }

  /** Blocking CPU request; returns (data, error, isWriteAck, reqs this op). */
  def cpu(
      addr: BigInt,
      memOp: BreezeMemOp.Type,
      sizeLog2: Int = 3,
      wdata: BigInt = 0,
      wmask: BigInt = 0xff,
      amoFunc: BreezeAmoFunc.Type = BreezeAmoFunc.Swap
  ): (BigInt, Boolean, Boolean, Seq[CoherentReq]) = {
    val logStart = reqLog.length
    cpuStart(addr, memOp, sizeLog2, wdata, wmask, amoFunc)
    val (data, error, ack) = cpuWait()
    if (memOp == BreezeMemOp.Load || memOp == BreezeMemOp.Store) {
      assert(ack == ((memOp == BreezeMemOp.Store) && !error),
        s"isWriteAck=$ack for $memOp error=$error")
    }
    (data, error, ack, reqLog.slice(logStart, reqLog.length).toSeq)
  }

  def load(addr: BigInt, sizeLog2: Int = 3): (BigInt, Boolean, Boolean, Seq[CoherentReq]) =
    cpu(addr, BreezeMemOp.Load, sizeLog2)

  def store(addr: BigInt, wdata: BigInt, sizeLog2: Int = 3, wmask: BigInt = 0xff)
      : (BigInt, Boolean, Boolean, Seq[CoherentReq]) =
    cpu(addr, BreezeMemOp.Store, sizeLog2, wdata, wmask)

  /** Inject one probe (works while a CPU operation is parked or in flight);
    * returns the captured response and lands recalled data in the memory. */
  def injectProbe(addr: BigInt, opcode: BreezeProbeOpcode.Type, txnId: BigInt = 3): ProbeResult = {
    val lineAddr = lineOf(addr)
    coh.probe.valid.poke(true.B)
    coh.probe.txnId.poke(txnId.U)
    coh.probe.lineAddr.poke(lineAddr.U)
    coh.probe.opcode.poke(opcode.litValue)
    var cycles = 0
    var accepted = false
    while (!accepted && cycles < 2000) {
      accepted = coh.probe.ready.peek().litToBoolean
      step()
      cycles += 1
    }
    if (!accepted) fail("DCache did not accept the probe")
    coh.probe.valid.poke(false.B)

    coh.probeResp.ready.poke(false.B)
    while (!coh.probeResp.valid.peek().litToBoolean && cycles < 2000) {
      step()
      cycles += 1
    }
    if (!coh.probeResp.valid.peek().litToBoolean) fail("probe response timed out")
    val r = ProbeResult(
      coh.probeResp.srcHart.peek().litValue,
      coh.probeResp.txnId.peek().litValue,
      coh.probeResp.lineAddr.peek().litValue,
      coh.probeResp.hasData.peek().litToBoolean,
      coh.probeResp.lineData.peek().litValue)
    // Two cycles of backpressure: the payload must stay stable.
    for (_ <- 0 until 2) {
      step()
      assert(coh.probeResp.valid.peek().litToBoolean, "probe response dropped under backpressure")
      assert(coh.probeResp.lineData.peek().litValue == r.data,
        "probe response data changed under backpressure")
      assert(coh.probeResp.hasData.peek().litToBoolean == r.hasData,
        "probe response hasData changed under backpressure")
    }
    coh.probeResp.ready.poke(true.B)
    step()
    coh.probeResp.ready.poke(false.B)
    if (r.hasData) mem.writeLine(lineAddr, r.data)
    r
  }

  /** Wait until the L1D presents a request with `opcode` while holdReqReady
    * parks the channel; returns its lineAddr without accepting it. */
  def peekParkedRequest(opcode: BreezeCoherenceOpcode.Type): BigInt = {
    assert(holdReqReady, "peekParkedRequest requires holdReqReady")
    var cycles = 0
    while (cycles < 2000) {
      if (coh.req.valid.peek().litToBoolean &&
          coh.req.opcode.peek().litValue == opcode.litValue) {
        return coh.req.lineAddr.peek().litValue
      }
      step()
      cycles += 1
    }
    fail(s"DCache never presented a parked $opcode request")
  }

  /** Pulse flushReq and run until flushDone or fatal. */
  def flush(): (Boolean, Seq[CoherentReq]) = {
    val logStart = reqLog.length
    dut.io.flushReq.poke(true.B)
    step()
    dut.io.flushReq.poke(false.B)
    var done = false
    var cycles = 0
    while (!done && cycles < 16000 && !fatalSeen) {
      if (dut.io.flushDone.peek().litToBoolean) done = true
      step()
      cycles += 1
    }
    (done, reqLog.slice(logStart, reqLog.length).toSeq)
  }

  def resKillPulse(): Unit = {
    dut.io.resKill.poke(true.B)
    step()
    dut.io.resKill.poke(false.B)
  }

  def resetHpm(): Unit = { hpmAccess = 0; hpmMiss = 0; hpmUncached = 0 }
}

object DCacheTestOps {
  val GetS: BigInt = BreezeCoherenceOpcode.GetS.litValue
  val GetM: BigInt = BreezeCoherenceOpcode.GetM.litValue
  val PutS: BigInt = BreezeCoherenceOpcode.PutS.litValue
  val PutM: BigInt = BreezeCoherenceOpcode.PutM.litValue
}

/** CPU-side behaviors of the set-associative coherent L1D: geometry, PLRU
  * replacement, store merging, MMIO/PMA, flush and HPM. Coherence-protocol
  * and RV64A behaviors live in BreezeDCacheCoherentSpec.
  */
class BreezeDCacheSetAssocSpec extends AnyFreeSpec with Matchers with ChiselSim {
  import DCacheTestOps._
  private val cfg = DefaultDCacheConfig()

  // Addresses in the writable, cacheable sram region (0x11000000..0x1103ffff).
  private def sramAddr(tagLsb: Int, set: Int, offset: Int = 0): BigInt =
    BigInt(0x11000000L) + (BigInt(tagLsb) << 11) + (BigInt(set) << 5) + offset
  private def lineBase(addr: BigInt): BigInt = addr & ~BigInt(31)
  private def storedVal(tag: Int): BigInt = BigInt(tag + 1) * BigInt("0102030405060708", 16)

  private def newDut() = new BreezeDCache(cfg, hartId = 0, hartIdWidth = 1)

  "DCache elaboration should reject non-frozen geometries" in {
    intercept[IllegalArgumentException] {
      new BreezeDCache(DefaultDCacheConfig(capacityBytes = 4096))
    }
    intercept[IllegalArgumentException] {
      new BreezeDCache(DefaultDCacheConfig(lineBytes = 64))
    }
  }

  "DCache should fill four ways with exactly one GetS each and no Put traffic" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      for (tag <- 0 until 4) {
        val (data, error, _, reqs) = h.load(sramAddr(tag, 0))
        error mustBe false
        data mustBe h.mem.readBytes(sramAddr(tag, 0), 8)
        reqs.map(_.opcode) mustBe Seq(GetS)
        reqs.head.lineAddr mustBe lineBase(sramAddr(tag, 0))
      }
      // All four subsequent accesses hit: zero coherence traffic.
      for (tag <- 0 until 4) {
        h.load(sramAddr(tag, 0))._4 mustBe empty
      }
    }
  }

  "DCache should evict the tree-PLRU victim, proven by PutM identity" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      // Fill all four ways of set 1 with dirty, uniquely identifiable lines.
      for (tag <- 0 until 4) {
        val (_, error, ack, reqs) = h.store(sramAddr(tag, 1), storedVal(tag))
        error mustBe false
        ack mustBe true
        reqs.map(_.opcode) mustBe Seq(GetM)
      }
      // Touch tag0 then tag1: tag2 becomes the PLRU victim.
      h.load(sramAddr(0, 1))._4 mustBe empty
      h.load(sramAddr(1, 1))._4 mustBe empty

      // Fifth distinct tag overflows the set: the dirty tag2 line is released
      // with PutM before the new line is requested.
      val (_, error5, _, reqs5) = h.load(sramAddr(4, 1))
      error5 mustBe false
      reqs5.map(_.opcode) mustBe Seq(PutM, GetS)
      reqs5.head.lineAddr mustBe lineBase(sramAddr(2, 1))
      (reqs5.head.data & BigInt("ffffffffffffffff", 16)) mustBe storedVal(2)
      reqs5(1).lineAddr mustBe lineBase(sramAddr(4, 1))

      // The evicted line reached memory via the PutM.
      h.mem.readBytes(lineBase(sramAddr(2, 1)), 8) mustBe storedVal(2)
      // Survivors still hit with zero traffic.
      for (tag <- Seq(0, 1, 3)) {
        h.load(sramAddr(tag, 1))._4 mustBe empty
      }
      // Re-accessing tag2 misses; the next PLRU victim is way 0 = dirty tag0.
      val (_, _, _, reqs2) = h.load(sramAddr(2, 1))
      reqs2.map(_.opcode) mustBe Seq(PutM, GetS)
      reqs2.head.lineAddr mustBe lineBase(sramAddr(0, 1))
      (reqs2.head.data & BigInt("ffffffffffffffff", 16)) mustBe storedVal(0)
    }
  }

  "DCache should not evict lines across different sets" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      for (tag <- 0 until 4) h.load(sramAddr(tag, 0))
      for (tag <- 0 until 4) h.load(sramAddr(tag, 1))
      h.reqLog.count(r => r.opcode == PutS || r.opcode == PutM) mustBe 0
      for (tag <- 0 until 4) {
        h.load(sramAddr(tag, 0))._4 mustBe empty
      }
    }
  }

  "DCache should merge partial-mask stores at all lane offsets" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val base = sramAddr(0, 2)

      h.store(base, BigInt("8899aabbccddeeff", 16))
      for (lane <- 0 until 4) {
        val (data, error, _, _) = h.load(base + lane * 8)
        error mustBe false
        if (lane == 0) data mustBe BigInt("8899aabbccddeeff", 16)
        else data mustBe h.mem.readBytes(base + lane * 8, 8)
      }

      // Halfword store into bytes [3:2] of lane 0 (0xbeef at offset 2).
      h.store(base + 2, BigInt("beef0000", 16), sizeLog2 = 1, wmask = 0x0c)
      h.load(base)._1 mustBe BigInt("8899aabbbeefeeff", 16)

      // Word store into the high half of lane 3 (bytes 28..31).
      h.store(base + 28, BigInt("a1b2c3d400000000", 16), sizeLog2 = 2, wmask = 0xf0)
      h.load(base + 24)._1 mustBe BigInt("a1b2c3d45b5a5958", 16)

      // Byte store into byte 9 (lane 1, byte 1).
      h.store(base + 9, BigInt("7700", 16), sizeLog2 = 0, wmask = 0x02)
      h.load(base + 8)._1 mustBe BigInt("4f4e4d4c4b4a7748", 16)
    }
  }

  "DCache should report a refill error after the victim was already released" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      // Dirty victim in set 4 way 0, then fill the remaining ways.
      h.store(sramAddr(0, 4), storedVal(0))
      for (tag <- 1 until 4) h.load(sramAddr(tag, 4))

      // The PutM succeeds (dirty data reaches memory) but the refill errors.
      h.failNextGet = true
      val (_, error, _, reqs) = h.load(sramAddr(5, 4))
      error mustBe true
      reqs.map(_.opcode) mustBe Seq(PutM, GetS)
      h.mem.readBytes(lineBase(sramAddr(0, 4)), 8) mustBe storedVal(0)

      // A retry refills from memory; the previously evicted data is intact.
      val (data, error2, _, reqs2) = h.load(sramAddr(5, 4))
      error2 mustBe false
      data mustBe h.mem.readBytes(sramAddr(5, 4), 8)
      reqs2.map(_.opcode) mustBe Seq(GetS)
      h.load(sramAddr(0, 4))._1 mustBe storedVal(0)
    }
  }

  "DCache should deny out-of-region accesses without any traffic" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val denied = BigInt("04000000", 16) // not inside any PMA region
      val (_, loadErr, ackL, reqsL) = h.load(denied)
      loadErr mustBe true
      ackL mustBe false
      reqsL mustBe empty
      val (_, storeErr, ackS, reqsS) = h.store(denied, 0)
      storeErr mustBe true
      ackS mustBe false
      reqsS mustBe empty
      h.mmioLog mustBe empty
    }
  }

  "DCache should bypass MMIO with beat-aligned address and byte mask" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val (data, error, _, reqs) = h.load(BigInt("02000004", 16))
      error mustBe false
      reqs mustBe empty
      h.mmioLog.length mustBe 1
      h.mmioLog.head.isWrite mustBe false
      h.mmioLog.head.addr mustBe BigInt("02000000", 16)
      h.mmioLog.head.mask mustBe BigInt(0xf0)
      data mustBe h.mem.readBytes(BigInt("02000000", 16), 8)

      val (_, _, ack, _) = h.store(BigInt("02000004", 16),
        BigInt("deadbeef00000000", 16), sizeLog2 = 2, wmask = 0xf0)
      ack mustBe true
      h.mmioLog.length mustBe 2
      h.mmioLog(1).isWrite mustBe true
      h.mmioLog(1).mask mustBe BigInt(0xf0)
      h.mem.readBytes(BigInt("02000000", 16), 8) mustBe BigInt("deadbeef03020100", 16)
    }
  }

  "DCache should propagate MMIO errors to the CPU response" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      h.mmioError = true
      val (_, error, ack, _) = h.load(BigInt("02000000", 16))
      error mustBe true
      ack mustBe false
      h.mmioLog.length mustBe 1
    }
  }

  "DCache flush should release every valid line (PutM dirty, PutS clean) and invalidate" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      // Dirty lines at the first and last flush indices, one clean E line.
      h.store(sramAddr(0, 0), BigInt("aaaaaaaaaaaaaaaa", 16))
      h.store(sramAddr(1, 63), BigInt("bbbbbbbbbbbbbbbb", 16))
      h.load(sramAddr(2, 30)) // clean E

      val (done, reqs) = h.flush()
      done mustBe true
      reqs.count(_.opcode == PutM) mustBe 2
      reqs.count(_.opcode == PutS) mustBe 1
      reqs.filter(_.opcode == PutM).map(_.lineAddr).toSet mustBe
        Set(lineBase(sramAddr(0, 0)), lineBase(sramAddr(1, 63)))
      reqs.filter(_.opcode == PutS).map(_.lineAddr) mustBe Seq(lineBase(sramAddr(2, 30)))
      h.mem.readBytes(lineBase(sramAddr(0, 0)), 8) mustBe BigInt("aaaaaaaaaaaaaaaa", 16)
      h.mem.readBytes(lineBase(sramAddr(1, 63)), 8) mustBe BigInt("bbbbbbbbbbbbbbbb", 16)

      // Everything was invalidated: re-accessing misses again.
      h.load(sramAddr(0, 0))._4.map(_.opcode) mustBe Seq(GetS)
      h.load(sramAddr(1, 63))._4.map(_.opcode) mustBe Seq(GetS)
      h.load(sramAddr(2, 30))._4.map(_.opcode) mustBe Seq(GetS)
    }
  }

  "DCache flush release error should raise the sticky fatal error" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      h.store(sramAddr(0, 6), BigInt("cccccccccccccccc", 16))
      h.failNextPut = true
      val (done, _) = h.flush()
      done mustBe false
      h.fatalSeen mustBe true
    }
  }

  "DCache HPM should count access, miss and uncached exactly once per request" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      h.load(sramAddr(0, 7))
      h.hpmAccess mustBe 1
      h.hpmMiss mustBe 1
      h.hpmUncached mustBe 0
      h.load(sramAddr(0, 7))
      h.hpmAccess mustBe 2
      h.hpmMiss mustBe 1
      h.load(BigInt("02000000", 16))
      h.hpmAccess mustBe 3
      h.hpmMiss mustBe 1
      h.hpmUncached mustBe 1
    }
  }
}
