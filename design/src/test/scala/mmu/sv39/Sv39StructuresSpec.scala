package flow.mmu.sv39

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import Sv39TestSupport._

class PlruHarness(ways: Int) extends Module {
  val io = IO(new Bundle {
    val touch = Input(Bool()); val way = Input(UInt(math.max(1, log2Ceil(ways)).W))
    val victim = Output(UInt(math.max(1, log2Ceil(ways)).W))
  })
  val state = RegInit(0.U((ways - 1).W))
  when(io.touch) { state := TreePlru.touch(state, io.way, ways) }
  io.victim := TreePlru.victim(state, ways)
}

class Sv39StructuresSpec extends AnyFreeSpec with ChiselSim {
  "parameters reject non-powers of two and non-16-bit ASIDs" in {
    for (n <- Seq(0, -1, 3, 6)) {
      intercept[IllegalArgumentException] { Sv39TlbParams(n, 4, 4) }
      intercept[IllegalArgumentException] { Sv39TlbParams(8, n, 4) }
      intercept[IllegalArgumentException] { Sv39TlbParams(8, 4, n) }
      intercept[IllegalArgumentException] { Sv39MmuParams(wcUpperSets = n) }
      intercept[IllegalArgumentException] { Sv39MmuParams(wcUpperWays = n) }
      intercept[IllegalArgumentException] { Sv39MmuParams(wcMiddleSets = n) }
      intercept[IllegalArgumentException] { Sv39MmuParams(wcMiddleWays = n) }
    }
    intercept[IllegalArgumentException] { Sv39MmuParams(asidBits = 8) }
  }
  for (ways <- Seq(1, 2, 4, 8, 16)) {
    s"tree PLRU $ways ways agrees with a software tree after random touches" in {
      simulate(new PlruHarness(ways)) { d =>
        val tree = Array.fill(math.max(1, ways - 1))(false)
        val random = new scala.util.Random(ways)
        def victim(node: Int, first: Int, count: Int): Int =
          if (count == 1) first else if (tree(node)) victim(node * 2 + 2, first + count / 2, count / 2)
          else victim(node * 2 + 1, first, count / 2)
        def touch(node: Int, first: Int, count: Int, way: Int): Unit = if (count > 1) {
          val right = way >= first + count / 2
          tree(node) = !right
          if (right) touch(node * 2 + 2, first + count / 2, count / 2, way)
          else touch(node * 2 + 1, first, count / 2, way)
        }
        d.io.touch.poke(false.B); d.io.way.poke(0.U)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        for (_ <- 0 until 100) {
          d.io.victim.expect(victim(0, 0, ways).U)
          val way = random.nextInt(ways)
          d.io.touch.poke(true.B); d.io.way.poke(way.U); d.clock.step()
          touch(0, 0, ways, way)
        }
        d.io.victim.expect(victim(0, 0, ways).U)
      }
    }
  }
  for ((sets, ways) <- Seq((1, 1), (1, 2), (2, 4), (4, 8))) {
    s"walk-cache $sets sets, $ways ways: ASID, invalid-first replacement and flush" in {
      simulate(new Sv39WalkCache(sets, ways, 9)) { d =>
        d.io.flush.poke(false.B); d.io.port.lookupFire.poke(false.B)
        d.io.port.lookup.key.poke(0.U); d.io.port.lookup.asid.poke(0.U)
        d.io.port.fill.valid.poke(false.B); d.io.port.fill.bits.key.poke(0.U)
        d.io.port.fill.bits.asid.poke(0.U); d.io.port.fill.bits.ppn.poke(0.U)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        def lookup(key: Int, asid: Int, hit: Boolean, ppn: Int = 0): Unit = {
          d.io.port.lookup.key.poke(key.U); d.io.port.lookup.asid.poke(asid.U)
          d.io.port.lookup.hit.expect(hit.B)
          if (hit) d.io.port.lookup.ppn.expect(ppn.U)
        }
        def fill(key: Int, asid: Int, ppn: Int): Unit = {
          d.io.port.fill.bits.key.poke(key.U); d.io.port.fill.bits.asid.poke(asid.U)
          d.io.port.fill.bits.ppn.poke(ppn.U); d.io.port.fill.valid.poke(true.B)
          d.clock.step(); d.io.port.fill.valid.poke(false.B)
        }
        for (s <- 0 until sets; w <- 0 until ways) {
          fill(s + w * sets, 1, 0x100 + s + w * sets)
          for (i <- 0 to w) lookup(s + i * sets, 1, true, 0x100 + s + i * sets)
        }
        lookup(0, 0xffff, false); lookup(0, 1, true, 0x100)
        // Explicit touch then overflow: compare victim using a software tree.
        d.io.port.lookupFire.poke(true.B); d.clock.step(); d.io.port.lookupFire.poke(false.B)
        val tree = Array.fill(math.max(1, ways - 1))(false)
        def touch(way: Int): Unit = {
          var node = 0; var first = 0; var count = ways
          while (count > 1) {
            val right = way >= first + count / 2; tree(node) = !right
            if (right) { first += count / 2; node = 2 * node + 2 } else node = 2 * node + 1
            count /= 2
          }
        }
        for (w <- 0 until ways) touch(w)
        touch(0)
        var node = 0; var v = 0; var count = ways
        while (count > 1) {
          if (tree(node)) { v += count / 2; node = node * 2 + 2 } else node = node * 2 + 1
          count /= 2
        }
        fill(ways * sets, 1, 0x888)
        lookup(v * sets, 1, false)
        for (w <- 0 until ways if w != v) lookup(w * sets, 1, true, 0x100 + w * sets)
        lookup(ways * sets, 1, true, 0x888)
        d.io.flush.poke(true.B); d.clock.step(); d.io.flush.poke(false.B)
        for (key <- 0 to ways * sets) lookup(key, 1, false)
      }
    }
  }
  for (ways <- Seq(1, 2, 8)) {
    s"MMU supports one set and $ways ways including zero-width PLRU" in {
      val geometry = Sv39TlbParams(1, ways, 1)
      simulate(new Sv39Mmu(Sv39MmuParams(itlb = geometry, dtlb = geometry,
        wcUpperWays = 1, wcMiddleSets = 1, wcMiddleWays = 1))) { d =>
        val h = new Driver(d)
        h.map4k(0x1234, 0x400, Rwx)
        assert(h.access(0x1234).pa == 0x400234)
        assert(h.access(0x1234, 0).pa == 0x400234)
        h.fence(0x1234, true)
        assert(h.request(0x1234).kind == "miss"); h.waitReady(); h.request(0x1234)
        h.fence()
        h.memory(pteAddress(Root, 0x40001234, 2)) = pte(0x100000, Rwx)
        assert(h.access(0x40001234).pa == BigInt("100001234", 16))
      }
    }
  }
  "SOC3d local TLB ports preserve every set way and targeted SFENCE across bank switches" in {
    simulate(new Sv39Mmu()) { d =>
      val h = new Driver(d, latency = 2)
      val addresses = (for (way <- 0 until 4; set <- Seq(0, 7, 2, 5, 1, 6, 3, 4))
        yield BigInt((way * 8 + set) * 4096 + 0x124))
      for ((va, n) <- addresses.zipWithIndex) {
        h.map4k(va, 0x400 + n, Rwx)
        assert(h.access(va).pa == BigInt((0x400 + n) * 4096 + 0x124))
      }
      val readCount = h.reads.size
      for ((va, n) <- addresses.zipWithIndex.reverse) {
        val result = h.request(va)
        assert(result.kind == "hit" && result.pa == BigInt((0x400 + n) * 4096 + 0x124))
      }
      assert(h.reads.size == readCount, "populated TLB bank unexpectedly walked")
      val selected = addresses(11)
      h.fence(selected, rs1Nz = true)
      for (va <- addresses if va != selected) assert(h.request(va).kind == "hit")
      assert(h.request(selected).kind == "miss")
      h.waitReady(); assert(h.request(selected).kind == "hit")
    }
  }
}
