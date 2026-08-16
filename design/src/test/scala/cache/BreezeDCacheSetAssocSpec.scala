package flow.cache

import chisel3._
import chisel3.simulator.PeekPokeAPI
import chisel3.simulator.scalatest.ChiselSim
import flow.config.DefaultDCacheConfig
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

/** One transaction observed on the blocking nextLevelReq pulse interface. */
case class NlTxn(addr: BigInt, isWrite: Boolean, isLine: Boolean, data: BigInt, mask: BigInt)

/** Harness driving one BreezeDCache against a DTestMem.
  *
  * Oracle discipline (audit findings F-21/F-24):
  *   - every nextLevelReq pulse is recorded per CPU transaction, so tests can
  *     assert hit (=zero transactions) / miss (=exactly one refill) / eviction
  *     (=exactly one line writeback) instead of comparing data only;
  *   - a second pulse while one transaction is still outstanding is a protocol
  *     violation and fails the test immediately;
  *   - every CPU pulse must produce exactly one response; the two cycles after
  *     a response are checked for spurious extra responses.
  */
final class DCacheHarness(dut: BreezeDCache, val mem: DTestMem, latency: Int = 2) extends Assertions with PeekPokeAPI {
  val log = mutable.ArrayBuffer.empty[NlTxn]
  var hpmAccess = 0
  var hpmMiss = 0
  var hpmUncached = 0

  private var pending: Option[NlTxn] = None
  private var countdown = 0
  private var errPred: NlTxn => Boolean = _ => false

  def fatalSeen: Boolean = dut.io.fatalError.peek().litToBoolean

  def resetHpmCounters(): Unit = {
    hpmAccess = 0
    hpmMiss = 0
    hpmUncached = 0
  }

  private def countHpm(): Unit = {
    if (dut.io.hpm.dcacheAccess.peek().litToBoolean) hpmAccess += 1
    if (dut.io.hpm.dcacheMiss.peek().litToBoolean) hpmMiss += 1
    if (dut.io.hpm.dcacheUncached.peek().litToBoolean) hpmUncached += 1
  }

  /** Advance one cycle: serve the outstanding lower-level transaction, then
    * observe a possible new request pulse. */
  private def step(): Unit = {
    if (pending.isDefined && countdown == 0) {
      val t = pending.get
      val isErr = errPred(t)
      dut.io.nextLevelRsp.vld.poke(true.B)
      dut.io.nextLevelRsp.error.poke(isErr.B)
      if (isErr) {
        dut.io.nextLevelRsp.data.poke(0.U)
      } else if (t.isLine && !t.isWrite) {
        dut.io.nextLevelRsp.data.poke(mem.readLine(t.addr).U)
      } else if (t.isLine && t.isWrite) {
        mem.writeLine(t.addr, t.data)
        dut.io.nextLevelRsp.data.poke(0.U)
      } else if (t.isWrite) {
        mem.writeBytes(t.addr, 8, t.data, t.mask)
        dut.io.nextLevelRsp.data.poke(0.U)
      } else {
        dut.io.nextLevelRsp.data.poke(mem.readBytes(t.addr, 8).U)
      }
      pending = None
    } else {
      dut.io.nextLevelRsp.vld.poke(false.B)
      dut.io.nextLevelRsp.error.poke(false.B)
      if (pending.isDefined) countdown -= 1
    }

    if (dut.io.nextLevelReq.req.peek().litToBoolean) {
      val t = NlTxn(
        dut.io.nextLevelReq.addr.peek().litValue,
        dut.io.nextLevelReq.isWrite.peek().litToBoolean,
        dut.io.nextLevelReq.isLine.peek().litToBoolean,
        dut.io.nextLevelReq.data.peek().litValue,
        dut.io.nextLevelReq.mask.peek().litValue
      )
      if (pending.isDefined) {
        fail("DCache issued a second outstanding nextLevel request (protocol violation)")
      }
      pending = Some(t)
      countdown = latency
      log += t
    }

    countHpm()
    dut.clock.step(1)
    dut.io.nextLevelRsp.vld.poke(false.B)
    dut.io.nextLevelRsp.error.poke(false.B)
  }

  /** Issue one blocking CPU request and run until the single response.
    *
    * Returns (data, error, isWriteAck, transactions issued for this request).
    */
  def cpu(
      addr: BigInt,
      isWrite: Boolean,
      sizeLog2: Int = 3,
      wdata: BigInt = 0,
      wmask: BigInt = 0xff,
      errorOn: NlTxn => Boolean = _ => false
  ): (BigInt, Boolean, Boolean, Seq[NlTxn]) = {
    val logStart = log.length
    errPred = errorOn

    dut.io.cpu.req.valid.poke(true.B)
    dut.io.cpu.req.addr.poke(addr.U)
    dut.io.cpu.req.sizeLog2.poke(sizeLog2.U)
    dut.io.cpu.req.isWrite.poke(isWrite.B)
    dut.io.cpu.req.wdata.poke(wdata.U)
    dut.io.cpu.req.wmask.poke(wmask.U)
    dut.io.flushReq.poke(false.B)
    dut.io.nextLevelRsp.vld.poke(false.B)
    dut.io.nextLevelRsp.error.poke(false.B)
    dut.io.nextLevelRsp.data.poke(0.U)
    countHpm()
    dut.clock.step(1)
    dut.io.cpu.req.valid.poke(false.B)

    var result: Option[(BigInt, Boolean, Boolean)] = None
    var cycle = 0
    while (result.isEmpty && cycle < 8000) {
      if (dut.io.cpu.rsp.valid.peek().litToBoolean) {
        result = Some((
          dut.io.cpu.rsp.data.peek().litValue,
          dut.io.cpu.rsp.error.peek().litToBoolean,
          dut.io.cpu.rsp.isWriteAck.peek().litToBoolean
        ))
      }
      step()
      cycle += 1
    }
    errPred = _ => false
    if (result.isEmpty) fail("DCache request timed out without a response")

    val (data, error, isWriteAck) = result.get
    // isWriteAck is only meaningful for a successful store (F-23g).
    assert(isWriteAck == (isWrite && !error),
      s"isWriteAck=$isWriteAck for isWrite=$isWrite error=$error")

    // One CPU pulse produces exactly one response: no spurious valid after it.
    dut.clock.step(1)
    assert(!dut.io.cpu.rsp.valid.peek().litToBoolean, "spurious extra response cycle 1")
    dut.clock.step(1)
    assert(!dut.io.cpu.rsp.valid.peek().litToBoolean, "spurious extra response cycle 2")

    (data, error, isWriteAck, log.slice(logStart, log.length).toSeq)
  }

  /** Pulse flushReq and run until flushDone. Returns (completed, transactions). */
  def flush(errorOn: NlTxn => Boolean = _ => false): (Boolean, Seq[NlTxn]) = {
    val logStart = log.length
    errPred = errorOn
    dut.io.cpu.req.valid.poke(false.B)
    dut.io.nextLevelRsp.vld.poke(false.B)
    dut.io.nextLevelRsp.error.poke(false.B)
    dut.io.nextLevelRsp.data.poke(0.U)
    dut.io.flushReq.poke(true.B)
    dut.clock.step(1)
    dut.io.flushReq.poke(false.B)

    var done = false
    var cycle = 0
    while (!done && cycle < 8000 && !fatalSeen) {
      if (dut.io.flushDone.peek().litToBoolean) done = true
      step()
      cycle += 1
    }
    errPred = _ => false
    (done, log.slice(logStart, log.length).toSeq)
  }
}

class BreezeDCacheSetAssocSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val cfg = DefaultDCacheConfig()

  // Addresses in the writable, cacheable sram region (0x11000000..0x1103ffff).
  // tagLsb selects bits [20:11] (distinct 21-bit tags), set selects addr[10:5].
  private def sramAddr(tagLsb: Int, set: Int, offset: Int = 0): BigInt =
    BigInt(0x11000000L) + (BigInt(tagLsb) << 11) + (BigInt(set) << 5) + offset

  private def lineBase(addr: BigInt): BigInt = addr & ~BigInt(31)

  private def isRefill(t: NlTxn): Boolean = t.isLine && !t.isWrite
  private def isWriteback(t: NlTxn): Boolean = t.isLine && t.isWrite
  private def isScalar(t: NlTxn): Boolean = !t.isLine

  private def storedVal(tag: Int): BigInt = BigInt(tag + 1) * BigInt("0102030405060708", 16)

  "DCache elaboration should reject non-frozen geometries" in {
    intercept[IllegalArgumentException] {
      new BreezeDCache(DefaultDCacheConfig(capacityBytes = 4096))
    }
    intercept[IllegalArgumentException] {
      new BreezeDCache(DefaultDCacheConfig(lineBytes = 64))
    }
  }

  "DCache should fill four ways with exactly one refill each and no writeback" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val h = new DCacheHarness(dut, new DTestMem)
      for (tag <- 0 until 4) {
        val (data, error, _, txns) = h.cpu(sramAddr(tag, 0), isWrite = false)
        error mustBe false
        data mustBe h.mem.readBytes(sramAddr(tag, 0), 8)
        txns.length mustBe 1
        isRefill(txns.head) mustBe true
        txns.head.addr mustBe lineBase(sramAddr(tag, 0))
      }
      h.log.count(isWriteback) mustBe 0
    }
  }

  "DCache hits should produce no lower-level traffic" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val h = new DCacheHarness(dut, new DTestMem)
      for (tag <- 0 until 4) {
        h.cpu(sramAddr(tag, 0), isWrite = false)
      }
      for (tag <- 0 until 4) {
        val (data, error, _, txns) = h.cpu(sramAddr(tag, 0), isWrite = false)
        error mustBe false
        data mustBe h.mem.readBytes(sramAddr(tag, 0), 8)
        txns mustBe empty
      }
    }
  }

  "DCache should evict the tree-PLRU victim, proven by writeback identity" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val h = new DCacheHarness(dut, new DTestMem)
      // Fill all four ways of set 1 with dirty, uniquely identifiable lines.
      for (tag <- 0 until 4) {
        val (_, error, ack, txns) = h.cpu(sramAddr(tag, 1), isWrite = true, wdata = storedVal(tag))
        error mustBe false
        ack mustBe true
        txns.length mustBe 1
        isRefill(txns.head) mustBe true
      }
      // Touch tag0 then tag1: tag2 becomes the PLRU victim.
      h.cpu(sramAddr(0, 1), isWrite = false)._4 mustBe empty
      h.cpu(sramAddr(1, 1), isWrite = false)._4 mustBe empty

      // Fifth distinct tag overflows the set: tag2's dirty line must be written
      // back before the refill installs the new line.
      val (_, error5, _, txns5) = h.cpu(sramAddr(4, 1), isWrite = false)
      error5 mustBe false
      txns5.length mustBe 2
      isWriteback(txns5(0)) mustBe true
      txns5(0).addr mustBe lineBase(sramAddr(2, 1))
      (txns5(0).data & BigInt("ffffffffffffffff", 16)) mustBe storedVal(2)
      isRefill(txns5(1)) mustBe true
      txns5(1).addr mustBe lineBase(sramAddr(4, 1))

      // The evicted line is back in memory ...
      h.mem.readBytes(lineBase(sramAddr(2, 1)), 8) mustBe storedVal(2)
      // ... the survivors still hit with zero lower-level traffic ...
      for (tag <- Seq(0, 1, 3)) {
        h.cpu(sramAddr(tag, 1), isWrite = false)._4 mustBe empty
      }
      // ... and the evicted tag2 misses and refills on re-access.
      val (_, _, _, txns2) = h.cpu(sramAddr(2, 1), isWrite = false)
      txns2.length mustBe 1
      isRefill(txns2.head) mustBe true
    }
  }

  "DCache should not evict lines across different sets" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val h = new DCacheHarness(dut, new DTestMem)
      for (tag <- 0 until 4) {
        h.cpu(sramAddr(tag, 0), isWrite = false)
      }
      // Fill set 1 completely as well; set 0 residents must be untouched.
      for (tag <- 0 until 4) {
        h.cpu(sramAddr(tag, 1), isWrite = false)
      }
      h.log.count(isWriteback) mustBe 0
      for (tag <- 0 until 4) {
        h.cpu(sramAddr(tag, 0), isWrite = false)._4 mustBe empty
      }
    }
  }

  "DCache should merge partial-mask stores at all lane offsets" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val h = new DCacheHarness(dut, new DTestMem)
      val base = sramAddr(0, 2)

      // Full dword store, then dword read-back from every 64-bit lane.
      h.cpu(base, isWrite = true, wdata = BigInt("8899aabbccddeeff", 16))
      for (lane <- 0 until 4) {
        val (data, error, _, _) = h.cpu(base + lane * 8, isWrite = false)
        error mustBe false
        data mustBe h.mem.readBytes(base + lane * 8, 8)
      }

      // Halfword store into bytes [3:2] of lane 0 (0xbeef at offset 2).
      h.cpu(base + 2, isWrite = true, sizeLog2 = 1, wdata = BigInt("beef0000", 16), wmask = 0x0c)
      val (d1, _, _, _) = h.cpu(base, isWrite = false)
      d1 mustBe BigInt("8899aabbbefeeeff", 16)

      // Word store into the high half of lane 3 (bytes 28..31).
      h.cpu(base + 28, isWrite = true, sizeLog2 = 2, wdata = BigInt("a1b2c3d400000000", 16), wmask = 0xf0)
      val (d2, _, _, _) = h.cpu(base + 24, isWrite = false)
      d2 mustBe BigInt("a1b2c3d45b5a5958", 16)

      // Byte store into byte 9 (lane 1, byte 1).
      h.cpu(base + 9, isWrite = true, sizeLog2 = 0, wdata = BigInt("7700", 16), wmask = 0x02)
      val (d3, _, _, _) = h.cpu(base + 8, isWrite = false)
      d3 mustBe BigInt("4f4e4d4c4b4a7748", 16)
    }
  }

  "DCache should return an error and preserve the victim on a refill error" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val h = new DCacheHarness(dut, new DTestMem)
      // Dirty victim in set 4 way 0, then fill the remaining ways.
      h.cpu(sramAddr(0, 4), isWrite = true, wdata = storedVal(0))
      for (tag <- 1 until 4) {
        h.cpu(sramAddr(tag, 4), isWrite = false)
      }

      // The refill fails after the dirty victim has been written back.
      val (_, error, _, txns) = h.cpu(sramAddr(5, 4), isWrite = false, errorOn = isRefill)
      error mustBe true
      txns.length mustBe 2
      isWriteback(txns(0)) mustBe true
      isRefill(txns(1)) mustBe true
      h.mem.readBytes(lineBase(sramAddr(0, 4)), 8) mustBe storedVal(0)

      // The victim line is still valid and hits; its data is intact.
      val (data, error2, _, txns2) = h.cpu(sramAddr(0, 4), isWrite = false)
      error2 mustBe false
      data mustBe storedVal(0)
      txns2 mustBe empty
    }
  }

  "DCache should return an error and preserve the victim on a writeback error" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val h = new DCacheHarness(dut, new DTestMem)
      h.cpu(sramAddr(0, 5), isWrite = true, wdata = storedVal(1))
      for (tag <- 1 until 4) {
        h.cpu(sramAddr(tag, 5), isWrite = false)
      }

      // The writeback itself fails: no refill may be issued afterwards.
      val (_, error, _, txns) = h.cpu(sramAddr(5, 5), isWrite = false, errorOn = isWriteback)
      error mustBe true
      txns.length mustBe 1
      isWriteback(txns(0)) mustBe true

      // The dirty victim survives and still hits with its data intact.
      val (data, error2, _, txns2) = h.cpu(sramAddr(0, 5), isWrite = false)
      error2 mustBe false
      data mustBe storedVal(1)
      txns2 mustBe empty
    }
  }

  "DCache should deny out-of-region accesses without lower-level traffic" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val h = new DCacheHarness(dut, new DTestMem)
      val denied = BigInt("04000000", 16) // not inside any PMA region
      val (_, loadErr, ackL, txnsL) = h.cpu(denied, isWrite = false)
      loadErr mustBe true
      ackL mustBe false
      txnsL mustBe empty
      val (_, storeErr, ackS, txnsS) = h.cpu(denied, isWrite = true, wdata = 0, wmask = 0xff)
      storeErr mustBe true
      ackS mustBe false
      txnsS mustBe empty
    }
  }

  "DCache should bypass MMIO with beat-aligned address and byte mask" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val h = new DCacheHarness(dut, new DTestMem)
      // Dword load at a beat offset of 4: mask selects the high half.
      val (data, error, _, txns) = h.cpu(BigInt("02000004", 16), isWrite = false)
      error mustBe false
      txns.length mustBe 1
      isScalar(txns.head) mustBe true
      txns.head.isWrite mustBe false
      txns.head.addr mustBe BigInt("02000000", 16)
      txns.head.mask mustBe BigInt(0xf0)
      data mustBe h.mem.readBytes(BigInt("02000000", 16), 8)

      // Word store into the high half of the beat.
      val (_, _, ack, txnsW) = h.cpu(
        BigInt("02000004", 16), isWrite = true, sizeLog2 = 2,
        wdata = BigInt("deadbeef00000000", 16), wmask = 0xf0)
      ack mustBe true
      txnsW.length mustBe 1
      txnsW.head.isWrite mustBe true
      txnsW.head.addr mustBe BigInt("02000000", 16)
      txnsW.head.mask mustBe BigInt(0xf0)
      h.mem.readBytes(BigInt("02000000", 16), 8) mustBe BigInt("deadbeef03020100", 16)
    }
  }

  "DCache should propagate MMIO errors to the CPU response" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val h = new DCacheHarness(dut, new DTestMem)
      val (_, error, ack, txns) = h.cpu(BigInt("02000000", 16), isWrite = false, errorOn = isScalar)
      error mustBe true
      ack mustBe false
      txns.length mustBe 1
    }
  }

  "DCache flush should walk all 256 lines, write back dirty lines and invalidate them" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val h = new DCacheHarness(dut, new DTestMem)
      // Dirty lines at the very first (set 0) and very last (set 63) flush
      // indices: a flush that does not truly walk all 256 lines misses one.
      h.cpu(sramAddr(0, 0), isWrite = true, wdata = BigInt("aaaaaaaaaaaaaaaa", 16))
      h.cpu(sramAddr(1, 63), isWrite = true, wdata = BigInt("bbbbbbbbbbbbbbbb", 16))

      val (done, txns) = h.flush()
      done mustBe true
      txns.count(isWriteback) mustBe 2
      txns.map(_.addr) must contain(lineBase(sramAddr(0, 0)))
      txns.map(_.addr) must contain(lineBase(sramAddr(1, 63)))
      h.mem.readBytes(lineBase(sramAddr(0, 0)), 8) mustBe BigInt("aaaaaaaaaaaaaaaa", 16)
      h.mem.readBytes(lineBase(sramAddr(1, 63)), 8) mustBe BigInt("bbbbbbbbbbbbbbbb", 16)

      // Both lines were invalidated: re-accessing them must miss and refill.
      val (_, _, _, txnsA) = h.cpu(sramAddr(0, 0), isWrite = false)
      txnsA.length mustBe 1
      isRefill(txnsA.head) mustBe true
      val (_, _, _, txnsB) = h.cpu(sramAddr(1, 63), isWrite = false)
      txnsB.length mustBe 1
      isRefill(txnsB.head) mustBe true
    }
  }

  "DCache flush writeback error should raise the sticky fatal error" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val h = new DCacheHarness(dut, new DTestMem)
      h.cpu(sramAddr(0, 6), isWrite = true, wdata = BigInt("cccccccccccccccc", 16))
      val (done, _) = h.flush(errorOn = isWriteback)
      done mustBe false
      h.fatalSeen mustBe true
    }
  }

  "DCache HPM should count access, miss and uncached exactly once per request" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val h = new DCacheHarness(dut, new DTestMem)
      // One miss: exactly one access and one miss.
      h.cpu(sramAddr(0, 7), isWrite = false)
      h.hpmAccess mustBe 1
      h.hpmMiss mustBe 1
      h.hpmUncached mustBe 0
      // One hit: one more access, no more misses.
      h.cpu(sramAddr(0, 7), isWrite = false)
      h.hpmAccess mustBe 2
      h.hpmMiss mustBe 1
      h.hpmUncached mustBe 0
      // One MMIO access: one access and one uncached, no miss.
      h.cpu(BigInt("02000000", 16), isWrite = false)
      h.hpmAccess mustBe 3
      h.hpmMiss mustBe 1
      h.hpmUncached mustBe 1
    }
  }
}
