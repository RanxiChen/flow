package flow.cache

import chisel3._
import chisel3.simulator.PeekPokeAPI
import chisel3.simulator.scalatest.ChiselSim
import flow.config.L2CacheGeometry
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** Exercise the real Home, SRAM arrays and memory port with independently
  * modeled L1 clients. Existing DCache tests cover probe/Put/upgrade races.
  */
class BreezeCoherentDmaSpec extends AnyFreeSpec with Matchers with ChiselSim with PeekPokeAPI {
  private val wordMask = (BigInt(1) << 64) - 1
  private val cfg = L2CacheGeometry(capacityBytes = 16384)
  private def dut(harts: Int = 4) = new BreezeL2Home(cfg, harts, withCoherentDma = true)

  private def transfer(d: BreezeL2Home, h: SmallL2Harness, address: BigInt,
                       write: Boolean = false, data: BigInt = 0, mask: Int = 255,
                       keepCycle: Boolean = false): (BigInt, Boolean) = {
    val bus = d.io.dmaWishbone.get
    bus.cyc.poke(true.B); bus.stb.poke(true.B); bus.we.poke(write.B)
    bus.adr.poke((address >> 3).U); bus.dat_w.poke(data.U); bus.sel.poke(mask.U)
    var cycles = 0
    while (!bus.ack.peek().litToBoolean && !bus.err.peek().litToBoolean && cycles < 5000) {
      h.step(); cycles += 1
    }
    assert(cycles < 5000, "DMA transaction timed out")
    val error = bus.err.peek().litToBoolean
    assert(!(error && bus.ack.peek().litToBoolean), "ACK and ERR must be exclusive")
    val result = bus.dat_r.peek().litValue
    h.step() // completion edge; master may change the next beat after this edge
    bus.cyc.poke(keepCycle.B); bus.stb.poke(false.B)
    (result, error)
  }

  "single hart DMA reads recall silent E to M stores without allocating a DMA owner" in {
    simulate(dut(1)) { d =>
      val mem = new DTestMem
      val h = new SmallL2Harness(d, mem, numHarts = 1)
      val a = BigInt("80000000", 16)
      h.getS(a)
      val latest = BigInt("deadbeef01234567", 16) << 128 | BigInt(99)
      h.l1Store(a, latest)
      transfer(d, h, a + 16) mustBe (((latest >> 128) & wordMask, false))
      h.l1State(a, 0) mustBe Some('S')
      mem.readBytes(a, 8) must not be BigInt(99)
      h.getM(a)._2 mustBe false
      h.l1Store(a, latest + 1)
      transfer(d, h, a) mustBe ((BigInt(100), false))
    }
  }

  "four sharers all acknowledge invalidation before a DMA partial write completes" in {
    simulate(dut()) { d =>
      val h = new SmallL2Harness(d, new DTestMem)
      val a = BigInt("90000000", 16)
      val original = h.getS(a)._2
      (1 until 4).foreach(h.getS(a, _))
      h.probeLatency(0) = 7; h.probeLatency(1) = 3; h.probeLatency(2) = 9
      h.resetProbeLog()
      transfer(d, h, a + 8, write = true, data = BigInt("1122334455667788", 16), mask = 0x81)._2 mustBe false
      h.probeLog.map(_.hart).sorted.toSeq mustBe Seq(0, 1, 2, 3)
      (0 until 4).foreach(i => h.l1Contains(a, i) mustBe false)
      val expected = (original & ~((BigInt(255) << 64) | (BigInt(255) << 120))) |
        (BigInt(0x88) << 64) | (BigInt(0x11) << 120)
      h.getS(a, 3)._2 mustBe expected
    }
  }

  "DMA write preserves untouched dirty bytes and CPU subsequently sees the merged line" in {
    simulate(dut()) { d =>
      val h = new SmallL2Harness(d, new DTestMem)
      val a = BigInt("c0000000", 16)
      h.getS(a, 2)
      val latest = (BigInt(1) << 250) | (BigInt(0xab) << 160) | 17
      h.l1Store(a, latest, 2)
      transfer(d, h, a, write = true, data = 123)._2 mustBe false
      h.l1Contains(a, 2) mustBe false
      h.getS(a, 0)._2 mustBe ((latest & ~wordMask) | 123)
      transfer(d, h, a + 24)._1 mustBe (latest >> 192)
    }
  }

  "DMA misses use the existing refill and dirty eviction including the top of 2 GiB DDR" in {
    simulate(dut()) { d =>
      val mem = new DTestMem
      val h = new SmallL2Harness(d, mem)
      val a = BigInt("fffffff8", 16)
      val prefix = mem.readBytes(a - 24, 24)
      transfer(d, h, a, write = true, data = 987)._2 mustBe false
      transfer(d, h, a) mustBe ((BigInt(987), false))
      h.getS(a, 1)._2 mustBe (prefix | (BigInt(987) << 192))
      // Same set, nine other tags: force inclusive eviction of the dirty line.
      (1 to 9).foreach(i => transfer(d, h, a - 2048 * i)._2 mustBe false)
      mem.readBytes(a, 8) mustBe BigInt(987)
    }
  }

  "DMA rejects devices and recovers from a refill error without granting stale data" in {
    simulate(dut()) { d =>
      val h = new SmallL2Harness(d, new DTestMem)
      transfer(d, h, BigInt("12001000", 16))._2 mustBe true
      h.wbLog mustBe empty
      val a = BigInt("80008000", 16)
      h.errorOnAddresses = Set(a + 16)
      transfer(d, h, a)._2 mustBe true
      h.errorOnAddresses = Set.empty
      transfer(d, h, a)._2 mustBe false
      transfer(d, h, BigInt("11000000", 16), write = true, data = 4321)._2 mustBe false
      h.getS(BigInt("11000000", 16), 3)._2.&(wordMask) mustBe BigInt(4321)
    }
  }

  "consecutive Wishbone beats with CYC held high complete exactly once" in {
    simulate(dut(1)) { d =>
      val h = new SmallL2Harness(d, new DTestMem, numHarts = 1)
      val a = BigInt("80002000", 16)
      (0 until 4).foreach(i => transfer(d, h, a + 8 * i,
        write = true, data = i + 100, keepCycle = true)._2 mustBe false)
      (0 until 4).foreach(i => transfer(d, h, a + 8 * i,
        keepCycle = i != 3) mustBe ((BigInt(i + 100), false)))
      h.wbLog.size mustBe 4 // one refill, no accidental extra transactions
    }
  }
}
