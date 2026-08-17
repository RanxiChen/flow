package flow.cache

import chisel3._
import chisel3.simulator.PeekPokeAPI
import chisel3.simulator.scalatest.ChiselSim
import flow.config.DefaultDCacheConfig
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import scala.collection.mutable

final case class CoherentReq(opcode: BigInt, lineAddr: BigInt, txnId: BigInt)
final case class PendingGrant(
    delay: Int,
    txnId: BigInt,
    lineAddr: BigInt,
    state: BigInt,
    hasData: Boolean,
    data: BigInt)

/** Minimal Home model for the coherent L1D interface.
  *
  * It deliberately models only one blocking transaction, matching the P3
  * Home contract, while checking the request opcode and returning full-line
  * data for misses.  Probes are driven independently so the tests cover the
  * L1D's pending-probe latch and response backpressure behavior as well as
  * normal refill/upgrade traffic.
  */
final class CoherentDCacheHarness(dut: BreezeDCache)
    extends Assertions with PeekPokeAPI {
  private val mem = new DTestMem
  private var pendingGrant: Option[PendingGrant] = None
  private val resident = mutable.Set.empty[BigInt]
  val reqLog = mutable.ArrayBuffer.empty[CoherentReq]

  private val coh = dut.io.coherence.get

  dut.io.cpu.req.valid.poke(false.B)
  dut.io.cpu.req.addr.poke(0.U)
  dut.io.cpu.req.sizeLog2.poke(3.U)
  dut.io.cpu.req.isWrite.poke(false.B)
  dut.io.cpu.req.wdata.poke(0.U)
  dut.io.cpu.req.wmask.poke("hff".U)
  dut.io.flushReq.poke(false.B)
  dut.io.nextLevelRsp.vld.poke(false.B)
  dut.io.nextLevelRsp.data.poke(0.U)
  dut.io.nextLevelRsp.error.poke(false.B)

  coh.req.ready.poke(true.B)
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

  private def lineOf(addr: BigInt): BigInt = addr & ~BigInt(31)

  private def step(): Unit = {
    coh.grant.valid.poke(false.B)

    pendingGrant match {
      case Some(grant) if grant.delay == 0 =>
        coh.grant.valid.poke(true.B)
        coh.grant.dstHart.poke(0.U)
        coh.grant.txnId.poke(grant.txnId.U)
        coh.grant.lineAddr.poke(grant.lineAddr.U)
        coh.grant.grantState.poke(grant.state.U)
        coh.grant.hasData.poke(grant.hasData.B)
        coh.grant.lineData.poke(grant.data.U)
        coh.grant.error.poke(false.B)
      case _ =>
    }

    val grantFire = pendingGrant.exists(_.delay == 0) &&
      coh.grant.ready.peek().litToBoolean
    val reqFire = coh.req.valid.peek().litToBoolean &&
      coh.req.ready.peek().litToBoolean

    if (reqFire) {
      assert(pendingGrant.isEmpty, "coherent DCache issued a second request")
      val opcode = coh.req.opcode.peek().litValue
      val lineAddr = coh.req.lineAddr.peek().litValue
      val txnId = coh.req.txnId.peek().litValue
      reqLog += CoherentReq(opcode, lineAddr, txnId)

      val getS = opcode == BreezeCoherenceOpcode.GetS.litValue
      val getM = opcode == BreezeCoherenceOpcode.GetM.litValue
      assert(getS || getM, s"unexpected request opcode $opcode")
      val miss = !resident.contains(lineAddr)
      pendingGrant = Some(PendingGrant(
        delay = 1,
        txnId = txnId,
        lineAddr = lineAddr,
        state = (if (getM) BreezeGrantState.M else BreezeGrantState.S).litValue,
        hasData = getS || miss,
        data = mem.readLine(lineAddr)))
    }

    dut.clock.step(1)

    if (grantFire) {
      resident += pendingGrant.get.lineAddr
      pendingGrant = None
    } else {
      pendingGrant = pendingGrant.map(g => g.copy(delay = math.max(0, g.delay - 1)))
    }
  }

  def cpu(
      addr: BigInt,
      isWrite: Boolean,
      wdata: BigInt = 0,
      wmask: BigInt = 0xff
  ): (BigInt, Boolean, Boolean) = {
    dut.io.cpu.req.valid.poke(true.B)
    dut.io.cpu.req.addr.poke(addr.U)
    dut.io.cpu.req.sizeLog2.poke(3.U)
    dut.io.cpu.req.isWrite.poke(isWrite.B)
    dut.io.cpu.req.wdata.poke(wdata.U)
    dut.io.cpu.req.wmask.poke(wmask.U)
    step()
    dut.io.cpu.req.valid.poke(false.B)

    var result: Option[(BigInt, Boolean, Boolean)] = None
    var cycles = 0
    while (result.isEmpty && cycles < 2000) {
      if (dut.io.cpu.rsp.valid.peek().litToBoolean) {
        result = Some((
          dut.io.cpu.rsp.data.peek().litValue,
          dut.io.cpu.rsp.error.peek().litToBoolean,
          dut.io.cpu.rsp.isWriteAck.peek().litToBoolean))
      }
      step()
      cycles += 1
    }
    if (result.isEmpty) fail("coherent DCache CPU request timed out")
    result.get
  }

  /** Send a probe, hold response ready low for two cycles, and return the
    * stable (hasData, lineData) response. */
  def probe(addr: BigInt, opcode: BreezeProbeOpcode.Type): (Boolean, BigInt) = {
    val lineAddr = lineOf(addr)
    coh.probe.valid.poke(true.B)
    coh.probe.dstHart.poke(0.U)
    coh.probe.txnId.poke(3.U)
    coh.probe.lineAddr.poke(lineAddr.U)
    coh.probe.opcode.poke(opcode.litValue)
    coh.probeResp.ready.poke(false.B)

    var accepted = false
    var cycles = 0
    while (!accepted && cycles < 2000) {
      accepted = coh.probe.ready.peek().litToBoolean
      step()
      cycles += 1
    }
    if (!accepted) fail("coherent DCache did not accept probe")
    coh.probe.valid.poke(false.B)

    while (!coh.probeResp.valid.peek().litToBoolean && cycles < 2000) {
      step()
      cycles += 1
    }
    if (!coh.probeResp.valid.peek().litToBoolean) fail("probe response timed out")

    val hasData = coh.probeResp.hasData.peek().litToBoolean
    val data = coh.probeResp.lineData.peek().litValue
    for (_ <- 0 until 2) {
      step()
      assert(coh.probeResp.valid.peek().litToBoolean,
        "probe response valid dropped under backpressure")
      assert(coh.probeResp.hasData.peek().litToBoolean == hasData,
        "probe response hasData changed under backpressure")
      assert(coh.probeResp.lineData.peek().litValue == data,
        "probe response data changed under backpressure")
    }

    coh.probeResp.ready.poke(true.B)
    step()
    coh.probeResp.ready.poke(false.B)
    resident -= lineAddr
    (hasData, data)
  }
}

class BreezeDCacheCoherentSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val cfg = DefaultDCacheConfig()
  private def sramAddr(tagLsb: Int, set: Int, offset: Int = 0): BigInt =
    BigInt(0x11000000L) + (BigInt(tagLsb) << 11) + (BigInt(set) << 5) + offset

  "coherent load should install S and ProbeInv should invalidate it" in {
    simulate(new BreezeDCache(cfg, coherent = true, hartId = 0, hartIdWidth = 1)) { dut =>
      val h = new CoherentDCacheHarness(dut)
      val addr = sramAddr(0, 3)
      val first = h.cpu(addr, isWrite = false)
      first._2 mustBe false
      h.reqLog.map(_.opcode) mustBe Seq(BreezeCoherenceOpcode.GetS.litValue)

      val (hasData, _) = h.probe(addr, BreezeProbeOpcode.ProbeInv)
      hasData mustBe false
      h.cpu(addr, isWrite = false)._2 mustBe false
      h.reqLog.map(_.opcode) mustBe Seq(
        BreezeCoherenceOpcode.GetS.litValue,
        BreezeCoherenceOpcode.GetS.litValue)
    }
  }

  "coherent dirty owner should return modified data on ProbeRecallInv" in {
    simulate(new BreezeDCache(cfg, coherent = true, hartId = 0, hartIdWidth = 1)) { dut =>
      val h = new CoherentDCacheHarness(dut)
      val addr = sramAddr(1, 4)
      val value = BigInt("0123456789abcdef", 16)
      val store = h.cpu(addr, isWrite = true, wdata = value)
      store._2 mustBe false
      store._3 mustBe true
      h.reqLog.map(_.opcode) mustBe Seq(BreezeCoherenceOpcode.GetM.litValue)

      val (hasData, lineData) = h.probe(addr, BreezeProbeOpcode.ProbeRecallInv)
      hasData mustBe true
      (lineData & ((BigInt(1) << 64) - 1)) mustBe value
    }
  }

  "coherent S-line store should request GetM before modifying the line" in {
    simulate(new BreezeDCache(cfg, coherent = true, hartId = 0, hartIdWidth = 1)) { dut =>
      val h = new CoherentDCacheHarness(dut)
      val addr = sramAddr(2, 5)
      val value = BigInt("fedcba9876543210", 16)
      h.cpu(addr, isWrite = false)._2 mustBe false
      val store = h.cpu(addr, isWrite = true, wdata = value)
      store._2 mustBe false
      store._3 mustBe true
      h.reqLog.map(_.opcode) mustBe Seq(
        BreezeCoherenceOpcode.GetS.litValue,
        BreezeCoherenceOpcode.GetM.litValue)

      val (hasData, lineData) = h.probe(addr, BreezeProbeOpcode.ProbeRecallInv)
      hasData mustBe true
      (lineData & ((BigInt(1) << 64) - 1)) mustBe value
    }
  }
}
