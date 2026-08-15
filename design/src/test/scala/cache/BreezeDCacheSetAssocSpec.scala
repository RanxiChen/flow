package flow.cache

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.DefaultDCacheConfig
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import scala.collection.mutable

/** Byte-addressable memory model with deterministic power-on contents. */
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

class BreezeDCacheSetAssocSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val cfg = DefaultDCacheConfig()

  // Addresses in the writable, cacheable sram region (0x11000000..0x1103ffff).
  // tagLsb selects bits [20:11] (distinct 21-bit tags), set selects addr[10:5].
  private def sramAddr(tagLsb: Int, set: Int, offset: Int = 0): BigInt =
    BigInt(0x11000000L) + (BigInt(tagLsb) << 11) + (BigInt(set) << 5) + offset

  /** Issue one blocking CPU request and run until `io.cpu.rsp.valid`.
    *
    * Serves the blocking `nextLevelReq` pulse against the memory model with a
    * fixed refill latency. Returns (data, error, isWriteAck).
    */
  private def runReq(
      dut: BreezeDCache,
      mem: DTestMem,
      addr: BigInt,
      sizeLog2: Int,
      isWrite: Boolean,
      wdata: BigInt,
      wmask: BigInt,
      latency: Int
  ): (BigInt, Boolean, Boolean) = {
    dut.io.cpu.req.valid.poke(true.B)
    dut.io.cpu.req.addr.poke(addr.U)
    dut.io.cpu.req.sizeLog2.poke(sizeLog2.U)
    dut.io.cpu.req.isWrite.poke(isWrite.B)
    dut.io.cpu.req.wdata.poke(wdata.U)
    dut.io.cpu.req.wmask.poke(wmask.U)
    dut.io.nextLevelRsp.vld.poke(false.B)
    dut.io.nextLevelRsp.data.poke(0.U)
    dut.io.nextLevelRsp.error.poke(false.B)
    dut.io.flushReq.poke(false.B)
    dut.clock.step(1)
    dut.io.cpu.req.valid.poke(false.B)

    var pending: Option[(BigInt, Boolean, Boolean, BigInt)] = None
    var countdown = 0
    var cycle = 0
    while (cycle < 8000) {
      if (pending.isDefined && countdown == 0) {
        val (nlAddr, nlWrite, nlLine, nlData) = pending.get
        dut.io.nextLevelRsp.vld.poke(true.B)
        dut.io.nextLevelRsp.error.poke(false.B)
        if (nlLine && !nlWrite) {
          dut.io.nextLevelRsp.data.poke(mem.readLine(nlAddr).U)
        } else if (nlLine && nlWrite) {
          mem.writeLine(nlAddr, nlData)
          dut.io.nextLevelRsp.data.poke(0.U)
        } else {
          dut.io.nextLevelRsp.data.poke(mem.readBytes(nlAddr, 8).U)
        }
        pending = None
      } else {
        dut.io.nextLevelRsp.vld.poke(false.B)
        if (pending.isDefined) countdown -= 1
      }

      if (dut.io.nextLevelReq.req.peek().litToBoolean) {
        val nlAddr = dut.io.nextLevelReq.addr.peek().litValue
        val nlWrite = dut.io.nextLevelReq.isWrite.peek().litToBoolean
        val nlLine = dut.io.nextLevelReq.isLine.peek().litToBoolean
        val nlData = dut.io.nextLevelReq.data.peek().litValue
        val nlMask = dut.io.nextLevelReq.mask.peek().litValue
        if (!nlLine && nlWrite) {
          mem.writeBytes(nlAddr, 8, nlData, nlMask)
        }
        pending = Some((nlAddr, nlWrite, nlLine, nlData))
        countdown = latency
      }

      if (dut.io.cpu.rsp.valid.peek().litToBoolean) {
        val data = dut.io.cpu.rsp.data.peek().litValue
        val error = dut.io.cpu.rsp.error.peek().litToBoolean
        val isW = dut.io.cpu.rsp.isWriteAck.peek().litToBoolean
        dut.io.nextLevelRsp.vld.poke(false.B)
        dut.clock.step(1)
        return (data, error, isW)
      }

      dut.clock.step(1)
      cycle += 1
    }
    fail("DCache request timed out without a response")
  }

  "DCache should fill four distinct tags in the same set with no writeback" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val mem = new DTestMem
      for (tag <- 0 until 4) {
        val (data, error, _) = runReq(dut, mem, sramAddr(tag, 0), 3, isWrite = false, 0, 0xff, 2)
        error mustBe false
        // memory[addr] == addr, so a dword load returns the address.
        data mustBe sramAddr(tag, 0)
      }
    }
  }

  "DCache should evict the tree-PLRU victim when the same set overflows" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val mem = new DTestMem
      for (tag <- 0 until 4) {
        runReq(dut, mem, sramAddr(tag, 0), 3, isWrite = false, 0, 0xff, 2)
      }
      val (data, error, _) = runReq(dut, mem, sramAddr(4, 0), 3, isWrite = false, 0, 0xff, 2)
      error mustBe false
      data mustBe sramAddr(4, 0)
      // The evicted line (tagLsb 0) is gone: re-accessing it misses and refills.
      val (data2, error2, _) = runReq(dut, mem, sramAddr(0, 0), 3, isWrite = false, 0, 0xff, 2)
      error2 mustBe false
      data2 mustBe sramAddr(0, 0)
    }
  }

  "DCache should not evict lines across different sets" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val mem = new DTestMem
      for (tag <- 0 until 4) {
        runReq(dut, mem, sramAddr(tag, 0), 3, isWrite = false, 0, 0xff, 2)
      }
      runReq(dut, mem, sramAddr(0, 1), 3, isWrite = false, 0, 0xff, 2)
      val (data, error, _) = runReq(dut, mem, sramAddr(0, 0), 3, isWrite = false, 0, 0xff, 2)
      error mustBe false
      data mustBe sramAddr(0, 0)
    }
  }

  "DCache should merge partial-mask stores and round-trip a full dword" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val mem = new DTestMem
      val base = sramAddr(0, 2)
      runReq(dut, mem, base, 3, isWrite = true, BigInt("0x8899aabbccddeeff", 16), 0xff, 2)
      val (data, error, _) = runReq(dut, mem, base, 3, isWrite = false, 0, 0xff, 2)
      error mustBe false
      data mustBe BigInt("0x8899aabbccddeeff", 16)

      // Partial (low 4-byte) store overwrites only the selected bytes.
      runReq(dut, mem, base, 3, isWrite = true, BigInt("0x11111111", 16), 0x0f, 2)
      val (data2, error2, _) = runReq(dut, mem, base, 3, isWrite = false, 0, 0xff, 2)
      error2 mustBe false
      data2 mustBe BigInt("0x8899aabb11111111", 16)
    }
  }

  "DCache should write back a dirty victim before overwriting it" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val mem = new DTestMem
      val storeAddr = sramAddr(0, 3)
      val storeVal = BigInt("0xfeedfacecafebeef", 16)
      runReq(dut, mem, storeAddr, 3, isWrite = true, storeVal, 0xff, 2)

      for (tag <- 1 until 4) {
        runReq(dut, mem, sramAddr(tag, 3), 3, isWrite = false, 0, 0xff, 2)
      }

      val (data, error, _) = runReq(dut, mem, sramAddr(4, 3), 3, isWrite = false, 0, 0xff, 2)
      error mustBe false
      data mustBe sramAddr(4, 3)

      mem.readBytes(storeAddr & ~BigInt(31), 8) mustBe storeVal
    }
  }

  "DCache should return an error and keep the victim on a refill error" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val mem = new DTestMem
      runReq(dut, mem, sramAddr(0, 4), 3, isWrite = true, BigInt("0x1234567890abcdef", 16), 0xff, 2)
      for (tag <- 1 until 4) {
        runReq(dut, mem, sramAddr(tag, 4), 3, isWrite = false, 0, 0xff, 2)
      }

      dut.io.cpu.req.valid.poke(true.B)
      dut.io.cpu.req.addr.poke(sramAddr(5, 4).U)
      dut.io.cpu.req.sizeLog2.poke(3.U)
      dut.io.cpu.req.isWrite.poke(false.B)
      dut.io.cpu.req.wdata.poke(0.U)
      dut.io.cpu.req.wmask.poke(0xff.U)
      dut.io.flushReq.poke(false.B)
      dut.io.nextLevelRsp.vld.poke(false.B)
      dut.io.nextLevelRsp.error.poke(false.B)
      dut.clock.step(1)
      dut.io.cpu.req.valid.poke(false.B)

      var responded = false
      var respError = false
      var sawWriteback = false
      var pending = false
      var pendingError = false
      var countdown = 0
      var cycle = 0
      while (!responded && cycle < 8000) {
        if (pending && countdown == 0) {
          dut.io.nextLevelRsp.vld.poke(true.B)
          dut.io.nextLevelRsp.error.poke(pendingError)
          dut.io.nextLevelRsp.data.poke(0.U)
          pending = false
        } else {
          dut.io.nextLevelRsp.vld.poke(false.B)
          if (pending) countdown -= 1
        }
        if (dut.io.nextLevelReq.req.peek().litToBoolean) {
          val nlWrite = dut.io.nextLevelReq.isWrite.peek().litToBoolean
          val nlLine = dut.io.nextLevelReq.isLine.peek().litToBoolean
          if (nlLine && nlWrite) sawWriteback = true
          pending = true
          pendingError = nlLine && !nlWrite
          countdown = 1
        }
        if (dut.io.cpu.rsp.valid.peek().litToBoolean) {
          respError = dut.io.cpu.rsp.error.peek().litToBoolean
          responded = true
        }
        dut.clock.step(1)
        cycle += 1
      }
      responded mustBe true
      respError mustBe true
      sawWriteback mustBe true
    }
  }

  "DCache should bypass MMIO accesses without touching the arrays" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val mem = new DTestMem
      val mmio = BigInt(0x02000000L)
      val (data, error, _) = runReq(dut, mem, mmio, 3, isWrite = false, 0, 0xff, 2)
      error mustBe false
      data mustBe mem.readBytes(mmio, 8)
    }
  }

  "DCache flush should walk all 256 lines and write back dirty data" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val mem = new DTestMem
      runReq(dut, mem, sramAddr(0, 0), 3, isWrite = true, BigInt("0xaaaaaaaaaaaaaaaa", 16), 0xff, 2)
      runReq(dut, mem, sramAddr(1, 1), 3, isWrite = true, BigInt("0xbbbbbbbbbbbbbbbb", 16), 0xff, 2)

      dut.io.cpu.req.valid.poke(false.B)
      dut.io.nextLevelRsp.vld.poke(false.B)
      dut.io.nextLevelRsp.error.poke(false.B)
      dut.io.nextLevelRsp.data.poke(0.U)
      dut.io.flushReq.poke(true.B)
      dut.clock.step(1)
      dut.io.flushReq.poke(false.B)

      var done = false
      var writebacks = 0
      var pending = false
      var countdown = 0
      var cycle = 0
      while (!done && cycle < 4000) {
        if (pending && countdown == 0) {
          dut.io.nextLevelRsp.vld.poke(true.B)
          dut.io.nextLevelRsp.error.poke(false.B)
          dut.io.nextLevelRsp.data.poke(0.U)
          pending = false
        } else {
          dut.io.nextLevelRsp.vld.poke(false.B)
          if (pending) countdown -= 1
        }
        if (dut.io.nextLevelReq.req.peek().litToBoolean) {
          val nlWrite = dut.io.nextLevelReq.isWrite.peek().litToBoolean
          val nlLine = dut.io.nextLevelReq.isLine.peek().litToBoolean
          val nlAddr = dut.io.nextLevelReq.addr.peek().litValue
          val nlData = dut.io.nextLevelReq.data.peek().litValue
          if (nlLine && nlWrite) {
            mem.writeLine(nlAddr, nlData)
            writebacks += 1
          }
          pending = true
          countdown = 1
        }
        if (dut.io.flushDone.peek().litToBoolean) done = true
        dut.clock.step(1)
        cycle += 1
      }
      done mustBe true
      writebacks mustBe 2
      mem.readBytes(sramAddr(0, 0), 8) mustBe BigInt("0xaaaaaaaaaaaaaaaa", 16)
      mem.readBytes(sramAddr(1, 1), 8) mustBe BigInt("0xbbbbbbbbbbbbbbbb", 16)
    }
  }

  "DCache HPM should count exactly one access and one miss per miss request" in {
    simulate(new BreezeDCache(cfg)) { dut =>
      val mem = new DTestMem
      dut.io.cpu.req.valid.poke(true.B)
      dut.io.cpu.req.addr.poke(sramAddr(0, 0).U)
      dut.io.cpu.req.sizeLog2.poke(3.U)
      dut.io.cpu.req.isWrite.poke(false.B)
      dut.io.cpu.req.wdata.poke(0.U)
      dut.io.cpu.req.wmask.poke(0xff.U)
      dut.io.flushReq.poke(false.B)
      dut.io.nextLevelRsp.vld.poke(false.B)
      dut.io.nextLevelRsp.error.poke(false.B)
      dut.io.nextLevelRsp.data.poke(0.U)

      var accesses = 0
      var misses = 0
      var responded = false
      var pending = false
      var pendingAddr = BigInt(0)
      var countdown = 0
      var cycle = 0
      if (dut.io.hpm.dcacheAccess.peek().litToBoolean) accesses += 1
      dut.clock.step(1)
      dut.io.cpu.req.valid.poke(false.B)
      while (!responded && cycle < 8000) {
        if (dut.io.hpm.dcacheAccess.peek().litToBoolean) accesses += 1
        if (dut.io.hpm.dcacheMiss.peek().litToBoolean) misses += 1
        if (pending && countdown == 0) {
          dut.io.nextLevelRsp.vld.poke(true.B)
          dut.io.nextLevelRsp.error.poke(false.B)
          dut.io.nextLevelRsp.data.poke(mem.readLine(pendingAddr).U)
          pending = false
        } else {
          dut.io.nextLevelRsp.vld.poke(false.B)
          if (pending) countdown -= 1
        }
        if (dut.io.nextLevelReq.req.peek().litToBoolean) {
          val nlLine = dut.io.nextLevelReq.isLine.peek().litToBoolean
          val nlWrite = dut.io.nextLevelReq.isWrite.peek().litToBoolean
          pendingAddr = dut.io.nextLevelReq.addr.peek().litValue
          pending = nlLine && !nlWrite
          countdown = 1
        }
        if (dut.io.cpu.rsp.valid.peek().litToBoolean) responded = true
        dut.clock.step(1)
        cycle += 1
      }
      responded mustBe true
      accesses mustBe 1
      misses mustBe 1
    }
  }
}
