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

final case class PipelinedRsp(id: BigInt, data: BigInt, error: Boolean, writeAck: Boolean)

/** Minimal blocking Home used to validate the new L1 pipeline independently
  * from the historical core and cluster wiring.
  */
final class PipelinedDCacheHomeModel(
    dut: BreezePipelinedDCache,
    val mem: DTestMem
) extends Assertions with PeekPokeAPI {
  private val coh = dut.io.coherence

  val reqLog = mutable.ArrayBuffer.empty[CoherentReq]
  var grantStateForGetS: BigInt = BreezeGrantState.E.litValue
  var getMHasData = true
  var holdGrant = false

  private case class PendingGrant(var delay: Int, txnId: BigInt, lineAddr: BigInt,
      state: BigInt, hasData: Boolean, data: BigInt)
  private var pendingGrant: Option[PendingGrant] = None

  dut.io.cpu.req.valid.poke(false.B)
  dut.io.cpu.req.bits.id.poke(0.U)
  dut.io.cpu.req.bits.addr.poke(0.U)
  dut.io.cpu.req.bits.sizeLog2.poke(3.U)
  dut.io.cpu.req.bits.wdata.poke(0.U)
  dut.io.cpu.req.bits.wmask.poke("hff".U)
  dut.io.cpu.req.bits.memOp.poke(BreezeMemOp.Load.litValue)
  dut.io.cpu.req.bits.amoFunc.poke(BreezeAmoFunc.Swap.litValue)
  dut.io.cpu.req.bits.aq.poke(false.B)
  dut.io.cpu.req.bits.rl.poke(false.B)
  dut.io.cpu.rsp.ready.poke(true.B)
  dut.io.reservationKill.poke(false.B)

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
  coh.probeResp.ready.poke(true.B)

  def lineOf(addr: BigInt): BigInt = addr & ~BigInt(31)

  def driveReq(id: Int, addr: BigInt, op: BreezeMemOp.Type,
      data: BigInt = 0, sizeLog2: Int = 3,
      mask: BigInt = 0xff, amo: BreezeAmoFunc.Type = BreezeAmoFunc.Swap): Unit = {
    dut.io.cpu.req.bits.id.poke(id.U)
    dut.io.cpu.req.bits.addr.poke(addr.U)
    dut.io.cpu.req.bits.sizeLog2.poke(sizeLog2.U)
    dut.io.cpu.req.bits.wdata.poke(data.U)
    dut.io.cpu.req.bits.wmask.poke(mask.U)
    dut.io.cpu.req.bits.memOp.poke(op.litValue)
    dut.io.cpu.req.bits.amoFunc.poke(amo.litValue)
    dut.io.cpu.req.bits.aq.poke(false.B)
    dut.io.cpu.req.bits.rl.poke(false.B)
  }

  def peekRsp(): Option[PipelinedRsp] =
    if (!dut.io.cpu.rsp.valid.peek().litToBoolean) None
    else Some(PipelinedRsp(
      dut.io.cpu.rsp.bits.id.peek().litValue,
      dut.io.cpu.rsp.bits.data.peek().litValue,
      dut.io.cpu.rsp.bits.error.peek().litToBoolean,
      dut.io.cpu.rsp.bits.isWriteAck.peek().litToBoolean))

  def step(): Unit = {
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
      coh.grant.error.poke(false.B)
    }
    val grantFire = driveGrant && coh.grant.ready.peek().litToBoolean

    val reqReady = pendingGrant.isEmpty
    coh.req.ready.poke(reqReady.B)
    val reqFire = reqReady && coh.req.valid.peek().litToBoolean
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
      if (isPutM) mem.writeLine(lineAddr, data)
      val state = if (isGetM) BreezeGrantState.M.litValue
        else if (isGetS) grantStateForGetS else BreezeGrantState.S.litValue
      val withData = if (isGetM) getMHasData else isGetS
      pendingGrant = Some(PendingGrant(1, txnId, lineAddr, state,
        withData, if (withData) mem.readLine(lineAddr) else 0))
    }

    dut.clock.step(1)
    if (grantFire) pendingGrant = None
    else pendingGrant.foreach(g => g.delay = math.max(0, g.delay - 1))
  }

  def start(id: Int, addr: BigInt, op: BreezeMemOp.Type,
      data: BigInt = 0, sizeLog2: Int = 3,
      mask: BigInt = 0xff, amo: BreezeAmoFunc.Type = BreezeAmoFunc.Swap): Unit = {
    driveReq(id, addr, op, data, sizeLog2, mask, amo)
    dut.io.cpu.req.valid.poke(true.B)
    var cycles = 0
    while (!dut.io.cpu.req.ready.peek().litToBoolean && cycles < 200) {
      step(); cycles += 1
    }
    assert(cycles < 200, "CPU request never became ready")
    step()
    dut.io.cpu.req.valid.poke(false.B)
  }

  def waitRsp(limit: Int = 1000): PipelinedRsp = {
    var result = peekRsp()
    var cycles = 0
    while (result.isEmpty && cycles < limit) {
      step(); cycles += 1; result = peekRsp()
    }
    assert(result.nonEmpty, "CPU response timed out")
    val value = result.get
    step()
    value
  }

  def killReservation(): Unit = {
    dut.io.reservationKill.poke(true.B)
    step()
    dut.io.reservationKill.poke(false.B)
  }

  def injectProbe(addr: BigInt, opcode: BreezeProbeOpcode.Type,
      txnId: Int = 3): (Boolean, BigInt) = {
    coh.probe.txnId.poke(txnId.U)
    coh.probe.lineAddr.poke(lineOf(addr).U)
    coh.probe.opcode.poke(opcode.litValue)
    coh.probe.valid.poke(true.B)
    var cycles = 0
    while (!coh.probe.ready.peek().litToBoolean && cycles < 200) {
      step(); cycles += 1
    }
    assert(cycles < 200, "probe was never accepted")
    step()
    coh.probe.valid.poke(false.B)
    while (!coh.probeResp.valid.peek().litToBoolean && cycles < 400) {
      step(); cycles += 1
    }
    assert(cycles < 400, "probe response timed out")
    val result = (coh.probeResp.hasData.peek().litToBoolean,
      coh.probeResp.lineData.peek().litValue)
    step()
    result
  }

  def transact(id: Int, addr: BigInt, op: BreezeMemOp.Type,
      data: BigInt = 0, sizeLog2: Int = 3,
      mask: BigInt = 0xff, amo: BreezeAmoFunc.Type = BreezeAmoFunc.Swap): PipelinedRsp = {
    start(id, addr, op, data, sizeLog2, mask, amo)
    waitRsp()
  }
}

class BreezePipelinedDCacheSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val cfg = DefaultDCacheConfig()
  private def newDut() = new BreezePipelinedDCache(cfg, cpuIdWidth = 4)
  private def sramAddr(tagLsb: Int, set: Int, offset: Int = 0): BigInt =
    BigInt(0x11000000L) + (BigInt(tagLsb) << 11) + (BigInt(set) << 5) + offset

  "cached Load hits accept and respond at one request per cycle" in {
    simulate(newDut()) { dut =>
      val h = new PipelinedDCacheHomeModel(dut, new DTestMem)
      val addresses = Seq(
        sramAddr(0, 1), sramAddr(1, 2), sramAddr(2, 3), sramAddr(3, 4))
      for ((addr, id) <- addresses.zipWithIndex) {
        val rsp = h.transact(id, addr, BreezeMemOp.Load)
        rsp.error mustBe false
      }
      h.reqLog.clear()

      val stream = Seq.tabulate(12)(i => (i, addresses(i % addresses.length)))
      val observed = mutable.ArrayBuffer.empty[PipelinedRsp]
      for ((id, addr) <- stream) {
        h.driveReq(id, addr, BreezeMemOp.Load)
        dut.io.cpu.req.valid.poke(true.B)
        dut.io.cpu.req.ready.expect(true.B)
        h.peekRsp().foreach(observed += _)
        h.step()
      }
      dut.io.cpu.req.valid.poke(false.B)
      var cycles = 0
      while (observed.length < stream.length && cycles < 30) {
        h.peekRsp().foreach(observed += _)
        h.step(); cycles += 1
      }

      observed.map(_.id).toSeq mustBe stream.map(_._1)
      observed.map(_.data).toSeq mustBe stream.map { case (_, addr) =>
        h.mem.readBytes(addr, 8)
      }
      observed.foreach(_.error mustBe false)
      h.reqLog mustBe empty
    }
  }

  "response backpressure closes request intake without losing the held hit" in {
    simulate(newDut()) { dut =>
      val h = new PipelinedDCacheHomeModel(dut, new DTestMem)
      val addresses = Seq(
        sramAddr(0, 20), sramAddr(1, 21), sramAddr(2, 22), sramAddr(3, 23))
      for ((addr, id) <- addresses.zipWithIndex) {
        h.transact(id, addr, BreezeMemOp.Load).error mustBe false
      }
      h.reqLog.clear()
      dut.io.cpu.rsp.ready.poke(false.B)

      // Four queue entries plus the lookup-stage request can be resident.
      for (id <- 0 until 5) {
        h.driveReq(id, addresses(id % addresses.length), BreezeMemOp.Load)
        dut.io.cpu.req.valid.poke(true.B)
        dut.io.cpu.req.ready.expect(true.B)
        h.step()
      }
      h.driveReq(6, addresses.head, BreezeMemOp.Load)
      dut.io.cpu.req.ready.expect(false.B)
      dut.io.cpu.req.valid.poke(false.B)

      dut.io.cpu.rsp.ready.poke(true.B)
      val observed = mutable.ArrayBuffer.empty[PipelinedRsp]
      var cycles = 0
      while (observed.length < 5 && cycles < 20) {
        h.peekRsp().foreach(observed += _)
        h.step(); cycles += 1
      }
      observed.map(_.id).toSeq mustBe Seq(0, 1, 2, 3, 4)
      observed.foreach(_.error mustBe false)
      h.reqLog mustBe empty
    }
  }

  "resident LRs use the commit pipeline at one request per cycle and the newest reservation wins" in {
    simulate(newDut()) { dut =>
      val h = new PipelinedDCacheHomeModel(dut, new DTestMem)
      val first = sramAddr(0, 24)
      val second = sramAddr(1, 25)
      h.transact(0, first, BreezeMemOp.Load).error mustBe false
      h.transact(1, second, BreezeMemOp.Load).error mustBe false
      h.reqLog.clear()

      h.driveReq(2, first, BreezeMemOp.Lr)
      dut.io.cpu.req.valid.poke(true.B)
      dut.io.cpu.req.ready.expect(true.B)
      h.step()
      h.driveReq(3, second, BreezeMemOp.Lr)
      dut.io.cpu.req.ready.expect(true.B)
      h.step()
      dut.io.cpu.req.valid.poke(false.B)

      val observed = mutable.ArrayBuffer.empty[PipelinedRsp]
      var cycles = 0
      while (observed.length < 2 && cycles < 20) {
        h.peekRsp().foreach(observed += _)
        h.step(); cycles += 1
      }
      observed.map(_.id).toSeq mustBe Seq(2, 3)
      observed.map(_.data).toSeq mustBe Seq(
        h.mem.readBytes(first, 8), h.mem.readBytes(second, 8))
      h.reqLog mustBe empty

      val value = BigInt("123456789abcdef0", 16)
      val sc = h.transact(4, second, BreezeMemOp.Sc, data = value)
      sc.data mustBe 0
      sc.error mustBe false
      sc.writeAck mustBe false
      h.reqLog mustBe empty
      h.transact(5, second, BreezeMemOp.Load).data mustBe value
    }
  }

  "LR/SC succeeds locally on an E line without GetM" in {
    simulate(newDut()) { dut =>
      val h = new PipelinedDCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(0, 26)
      val old = h.mem.readBytes(addr, 8)
      val value = BigInt("0102030405060708", 16)
      val lr = h.transact(1, addr, BreezeMemOp.Lr)
      lr.data mustBe old
      lr.error mustBe false
      h.reqLog.map(_.opcode).toSeq mustBe Seq(BreezeCoherenceOpcode.GetS.litValue)
      h.reqLog.clear()

      val sc = h.transact(2, addr, BreezeMemOp.Sc, data = value)
      sc.data mustBe 0
      sc.error mustBe false
      sc.writeAck mustBe false
      h.reqLog mustBe empty
      h.transact(3, addr, BreezeMemOp.Load).data mustBe value
    }
  }

  "LR/SC succeeds through GetM on an S line" in {
    simulate(newDut()) { dut =>
      val h = new PipelinedDCacheHomeModel(dut, new DTestMem)
      h.grantStateForGetS = BreezeGrantState.S.litValue
      val addr = sramAddr(1, 27)
      val value = BigInt("1112131415161718", 16)
      h.transact(1, addr, BreezeMemOp.Lr).error mustBe false
      h.reqLog.clear()

      val sc = h.transact(2, addr, BreezeMemOp.Sc, data = value)
      sc.data mustBe 0
      sc.error mustBe false
      h.reqLog.map(_.opcode).toSeq mustBe Seq(BreezeCoherenceOpcode.GetM.litValue)
      h.transact(3, addr, BreezeMemOp.Load).data mustBe value
    }
  }

  "SC without a matching reservation fails without coherence traffic or a write" in {
    simulate(newDut()) { dut =>
      val h = new PipelinedDCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(2, 28)
      val before = h.mem.readBytes(addr, 8)
      val sc = h.transact(1, addr, BreezeMemOp.Sc, data = BigInt(0x5555))
      sc.data mustBe 1
      sc.error mustBe false
      sc.writeAck mustBe false
      h.reqLog mustBe empty
      h.transact(2, addr, BreezeMemOp.Load).data mustBe before

      h.transact(3, addr, BreezeMemOp.Lr, sizeLog2 = 2).error mustBe false
      h.reqLog.clear()
      val wrongSize = h.transact(4, addr, BreezeMemOp.Sc,
        data = 1, sizeLog2 = 3)
      wrongSize.data mustBe 1
      wrongSize.error mustBe false
      h.reqLog mustBe empty
    }
  }

  "a probe on the reserved line clears the reservation" in {
    simulate(newDut()) { dut =>
      val h = new PipelinedDCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(3, 29)
      h.transact(1, addr, BreezeMemOp.Lr).error mustBe false
      h.injectProbe(addr, BreezeProbeOpcode.ProbeToS)._1 mustBe false
      h.reqLog.clear()

      val sc = h.transact(2, addr, BreezeMemOp.Sc, data = BigInt(0x7777))
      sc.data mustBe 1
      sc.error mustBe false
      h.reqLog mustBe empty
    }
  }

  "SC killed while GetM waits fails and installs the grant as clean E" in {
    simulate(newDut()) { dut =>
      val h = new PipelinedDCacheHomeModel(dut, new DTestMem)
      h.grantStateForGetS = BreezeGrantState.S.litValue
      val addr = sramAddr(4, 30)
      val before = h.mem.readBytes(addr, 8)
      h.transact(1, addr, BreezeMemOp.Lr).error mustBe false
      h.reqLog.clear()

      h.holdGrant = true
      h.start(2, addr, BreezeMemOp.Sc, data = BigInt(0x9999))
      var cycles = 0
      while (h.reqLog.isEmpty && cycles < 100) { h.step(); cycles += 1 }
      h.reqLog.map(_.opcode).toSeq mustBe Seq(BreezeCoherenceOpcode.GetM.litValue)
      h.injectProbe(addr, BreezeProbeOpcode.ProbeInv)._1 mustBe false

      h.holdGrant = false
      val sc = h.waitRsp()
      sc.data mustBe 1
      sc.error mustBe false
      h.transact(3, addr, BreezeMemOp.Load).data mustBe before
      h.reqLog.clear()
      h.transact(4, addr, BreezeMemOp.Store, data = BigInt(0xabcd)).error mustBe false
      h.reqLog mustBe empty
    }
  }

  "reservationKill cancels LR and makes the following SC fail" in {
    simulate(newDut()) { dut =>
      val h = new PipelinedDCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(5, 31)
      h.transact(1, addr, BreezeMemOp.Lr).error mustBe false
      h.killReservation()
      h.reqLog.clear()

      val sc = h.transact(2, addr, BreezeMemOp.Sc, data = 1)
      sc.data mustBe 1
      sc.error mustBe false
      h.reqLog mustBe empty
    }
  }

  "reservationKill between SC lookup and ScWrite prevents the data write" in {
    simulate(newDut()) { dut =>
      val h = new PipelinedDCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(6, 32)
      val before = h.mem.readBytes(addr, 8)
      h.transact(1, addr, BreezeMemOp.Lr).error mustBe false
      h.reqLog.clear()

      // start() leaves the accepted SC in s1.  The next edge performs lookup
      // and sees the old reservation, while reservationKill wins that edge.
      h.start(2, addr, BreezeMemOp.Sc, data = BigInt(0xfeedfaceL))
      dut.io.reservationKill.poke(true.B)
      h.step()
      dut.io.reservationKill.poke(false.B)

      val sc = h.waitRsp()
      sc.data mustBe 1
      sc.error mustBe false
      h.reqLog mustBe empty
      h.transact(3, addr, BreezeMemOp.Load).data mustBe before
    }
  }

  "an E-state AMO locks locally, completes before a queued recall, and sends no GetM" in {
    simulate(newDut()) { dut =>
      val h = new PipelinedDCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(0, 8)
      val old = h.mem.readBytes(addr, 8)
      h.transact(1, addr, BreezeMemOp.Load).error mustBe false // GetS -> E
      h.reqLog.clear()

      h.start(2, addr, BreezeMemOp.Amo, data = 7, amo = BreezeAmoFunc.Add)
      val coh = dut.io.coherence
      coh.probe.valid.poke(true.B)
      coh.probe.txnId.poke(3.U)
      coh.probe.lineAddr.poke(h.lineOf(addr).U)
      coh.probe.opcode.poke(BreezeProbeOpcode.ProbeRecallInv.litValue)
      coh.probe.ready.expect(true.B)
      h.step()
      coh.probe.valid.poke(false.B)

      var cpuRsp: Option[PipelinedRsp] = None
      var probeData: Option[BigInt] = None
      var cycles = 0
      while ((cpuRsp.isEmpty || probeData.isEmpty) && cycles < 100) {
        if (cpuRsp.isEmpty) cpuRsp = h.peekRsp()
        if (coh.probeResp.valid.peek().litToBoolean) {
          coh.probeResp.hasData.expect(true.B)
          probeData = Some(coh.probeResp.lineData.peek().litValue)
        }
        h.step(); cycles += 1
      }
      cpuRsp.get.id mustBe 2
      cpuRsp.get.data mustBe old
      cpuRsp.get.error mustBe false
      (probeData.get & BigInt("ffffffffffffffff", 16)) mustBe
        ((old + 7) & BigInt("ffffffffffffffff", 16))
      h.reqLog mustBe empty

      // Recall invalidated the line, so the next access goes back to Home.
      h.transact(3, addr, BreezeMemOp.Load).error mustBe false
      h.reqLog.map(_.opcode).toSeq mustBe Seq(BreezeCoherenceOpcode.GetS.litValue)
    }
  }

  "Store miss obtains M, while a Store hit on E upgrades silently" in {
    simulate(newDut()) { dut =>
      val h = new PipelinedDCacheHomeModel(dut, new DTestMem)
      val missAddr = sramAddr(0, 6)
      val missValue = BigInt("1122334455667788", 16)
      val missRsp = h.transact(1, missAddr, BreezeMemOp.Store, data = missValue)
      missRsp.error mustBe false
      missRsp.writeAck mustBe true
      h.reqLog.map(_.opcode).toSeq mustBe Seq(BreezeCoherenceOpcode.GetM.litValue)
      h.transact(2, missAddr, BreezeMemOp.Load).data mustBe missValue

      val eAddr = sramAddr(1, 7)
      h.transact(3, eAddr, BreezeMemOp.Load) // GetS -> E
      h.reqLog.clear()
      val eValue = BigInt("8877665544332211", 16)
      val eRsp = h.transact(4, eAddr, BreezeMemOp.Store, data = eValue)
      eRsp.error mustBe false
      eRsp.writeAck mustBe true
      h.reqLog mustBe empty
      h.transact(5, eAddr, BreezeMemOp.Load).data mustBe eValue
    }
  }

  "a dirty replacement emits PutM before refilling the requested line" in {
    simulate(newDut()) { dut =>
      val h = new PipelinedDCacheHomeModel(dut, new DTestMem)
      val set = 12
      for (tag <- 0 until 4) {
        val value = BigInt(tag + 1) * BigInt("1111111111111111", 16)
        h.transact(tag, sramAddr(tag, set), BreezeMemOp.Store, data = value).error mustBe false
      }
      h.reqLog.clear()
      val replacement = h.transact(8, sramAddr(4, set), BreezeMemOp.Load)
      replacement.error mustBe false
      h.reqLog.map(_.opcode).toSeq mustBe Seq(
        BreezeCoherenceOpcode.PutM.litValue,
        BreezeCoherenceOpcode.GetS.litValue)
      val put = h.reqLog.head
      put.hasData mustBe true
      h.mem.readLine(put.lineAddr) mustBe put.data
    }
  }

  "an S-state AMO services a probe while waiting for GetM, then uses grant data" in {
    simulate(newDut()) { dut =>
      val h = new PipelinedDCacheHomeModel(dut, new DTestMem)
      h.grantStateForGetS = BreezeGrantState.S.litValue
      h.getMHasData = true
      val addr = sramAddr(1, 9)
      val old = h.mem.readBytes(addr, 8)
      h.transact(1, addr, BreezeMemOp.Load).error mustBe false
      h.reqLog.clear()

      h.holdGrant = true
      h.start(4, addr, BreezeMemOp.Amo, data = 1, amo = BreezeAmoFunc.Add)
      var cycles = 0
      while (h.reqLog.isEmpty && cycles < 100) { h.step(); cycles += 1 }
      h.reqLog.map(_.opcode).toSeq mustBe Seq(BreezeCoherenceOpcode.GetM.litValue)

      val coh = dut.io.coherence
      coh.probe.valid.poke(true.B)
      coh.probe.txnId.poke(2.U)
      coh.probe.lineAddr.poke(h.lineOf(addr).U)
      coh.probe.opcode.poke(BreezeProbeOpcode.ProbeInv.litValue)
      while (!coh.probe.ready.peek().litToBoolean && cycles < 150) {
        h.step(); cycles += 1
      }
      coh.probe.ready.expect(true.B)
      h.step()
      coh.probe.valid.poke(false.B)
      while (!coh.probeResp.valid.peek().litToBoolean && cycles < 200) {
        h.step(); cycles += 1
      }
      coh.probeResp.valid.expect(true.B)
      coh.probeResp.hasData.expect(false.B)
      h.step()

      h.holdGrant = false
      val rsp = h.waitRsp()
      rsp.id mustBe 4
      rsp.data mustBe old
      rsp.error mustBe false

      val check = h.transact(5, addr, BreezeMemOp.Load)
      check.data mustBe ((old + 1) & BigInt("ffffffffffffffff", 16))
      check.error mustBe false
    }
  }
}
