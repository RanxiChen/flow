package flow.memsys

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.l2.L2Home
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.util.Random

import MemTestKit._

/** Single-core L2Home with a Scala L1D, L1I and DMA client and an AXI memory
  * model (coherence-l2-rtl-spec). Reads are checked against a value the
  * line held while they were outstanding; grants to the L1D likewise.
  */
class L2HomeSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Env(val d: L2Home, val g: BreezeMemGeometry, seed: Int) {
    val rng = new Random(seed)
    val arch = new GoldenMem(seed)
    val dram = new GoldenMem(seed)
    val mem = new AxiMemory(d.io.mem, dram, rng)
    val l1d = new L1DProxy(d.io.l1d(0), arch, rng)
    val l1i = new ReadClientAgent(d.io.l1i(0), "l1i", arch, rng, 2)
    val dma = new ReadClientAgent(d.io.dma, "dma", arch, rng, 1)
    val bench = new Bench(d.clock, Seq(l1d, l1i, dma, mem))
    /** Next tag in the same L2 set. */
    val l2Stride: BigInt = BigInt(CoherenceParams(g).l2Sets) * LineBytes
    def line(off: BigInt): BigInt = lineOf(MainRam + off)
    def settle(): Unit = bench.quiesce()
    def finish(): Unit = {
      settle()
      val before = dma.results.size
      for (l <- arch.touched.toSeq.sorted) dma.pending += ClientReq.read(l)
      settle()
      for ((_, q, data, _) <- dma.results.drop(before))
        withClue(s"final line ${hex(q.line << 5)}: ") { data mustBe arch.line(q.line) }
    }
  }

  private def init(d: L2Home): Unit = {
    for (c <- d.io.l1d) {
      c.req.valid.poke(false.B); c.req.bits.op.poke(ReqOp.GetS); c.req.bits.addr.poke(0.U)
      c.req.bits.id.poke(0.U); c.req.bits.mask.poke(0.U); c.req.bits.data.poke(0.U)
      c.rspUp.valid.poke(false.B); c.rspUp.bits.op.poke(RspUpOp.Put); c.rspUp.bits.hasData.poke(false.B)
      c.rspUp.bits.addr.poke(0.U); c.rspUp.bits.data.poke(0.U)
      c.snp.ready.poke(false.B); c.rspDown.ready.poke(true.B)
    }
    for (c <- d.io.l1i :+ d.io.dma) {
      c.req.valid.poke(false.B); c.req.bits.op.poke(ReqOp.Read); c.req.bits.addr.poke(0.U)
      c.req.bits.id.poke(0.U); c.req.bits.mask.poke(0.U); c.req.bits.data.poke(0.U)
      c.rspDown.ready.poke(true.B)
    }
    val m = d.io.mem
    m.ar.ready.poke(false.B); m.aw.ready.poke(false.B); m.w.ready.poke(false.B)
    m.r.valid.poke(false.B); m.r.bits.id.poke(0.U); m.r.bits.data.poke(0.U); m.r.bits.resp.poke(0.U)
    m.r.bits.last.poke(false.B); m.b.valid.poke(false.B); m.b.bits.id.poke(0.U); m.b.bits.resp.poke(0.U)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }

  private def withL2(g: BreezeMemGeometry = BreezeMemGeometry.singleCore, seed: Int = 1)(body: Env => Unit): Unit =
    simulate(new L2Home(g)) { d =>
      init(d)
      val env = new Env(d, g, seed)
      body(env)
      env.finish()
    }

  "cold GetS reads one AXI line and grants E; a clean Put keeps the line in L2" in withL2() { e =>
    import e._
    val a = line(0x100)
    l1d.acquire(a); settle()
    mem.reads.map(_._2) mustBe Seq(a)
    l1d.grants.map(_._2) mustBe Seq("DataE")
    l1d.evict(a); settle()
    l1d.acquire(a); settle()
    mem.reads.size mustBe 1
    l1d.grants.map(_._2) mustBe Seq("DataE", "DataE")
  }

  "GetM, a dirty Put and a later L1I Read return the written data from L2" in withL2() { e =>
    import e._
    val a = line(0x200)
    l1d.store(a, 8, BigInt("1122334455667788", 16)); settle()
    l1d.grants.map(_._2) mustBe Seq("DataE")
    l1d.evict(a); settle()
    l1i.pending += ClientReq.read(a); settle()
    l1i.results.size mustBe 1
    mem.reads.size mustBe 1
    mem.writes mustBe empty
  }

  "an L1I Read of an owned line sends Down and returns the dirty data; the L1D then upgrades with AckE" in withL2() { e =>
    import e._
    val b = line(0x300)
    l1d.store(b, 0, 0xabcdef); settle()
    l1i.pending += ClientReq.read(b); settle()
    l1d.probes.map(x => (x._2, x._3, x._4)) mustBe Seq(("Down", b, true))
    l1d.state(b) mustBe 'S'
    l1d.store(b, 16, 0x77, 0); settle()
    l1d.grants.map(_._2) mustBe Seq("DataE", "AckE")
    dma.pending += ClientReq.read(b); settle()
    l1d.probes.size mustBe 2
  }

  "a DMA MaskWrite of an owned line invalidates the owner and merges its bytes over the dirty data" in withL2() { e =>
    import e._
    val c = line(0x400)
    l1d.store(c, 0, BigInt("1111111111111111", 16)); l1d.store(c, 8, BigInt("2222222222222222", 16)); settle()
    dma.pending += ClientReq.write(c, BigInt(0xff00), BigInt("3333333333333333", 16) << 64); settle()
    l1d.probes.map(x => (x._2, x._4)) mustBe Seq(("Inv", true))
    l1d.state(c) mustBe 'I'
    dma.pending += ClientReq.read(c); settle()
    val data = dma.results.last._3
    (data & mask(64)) mustBe BigInt("1111111111111111", 16)
    ((data >> 64) & mask(64)) mustBe BigInt("3333333333333333", 16)
  }

  "a DMA MaskWrite of an uncached line misses, merges with memory and is visible to the L1D" in withL2() { e =>
    import e._
    val l = line(0x500)
    dma.pending += ClientReq.write(l, BigInt("f000000f", 16), BigInt(256, rng)); settle()
    mem.reads.map(_._2) mustBe Seq(l)
    l1d.acquire(l); settle()
    mem.reads.size mustBe 1
    l1d.grants.map(_._2) mustBe Seq("DataE")
  }

  "L2 eviction invalidates the L1D copy, writes dirty data back over AXI and refetches it" in withL2() { e =>
    import e._
    val set = (0 until 2 * g.l2Ways + 1).map(k => line(0x600 + k * l2Stride))
    l1d.store(set(0), 8, BigInt("e0e0e0e0", 16), 2); settle()
    dma.pending += ClientReq.write(set(1), BigInt(0xff), BigInt(0x5d)); settle()
    for (l <- set.drop(2)) dma.pending += ClientReq.read(l)
    settle()
    l1d.probes.map(x => (x._2, x._3, x._4)) must contain (("Inv", set(0), true))
    l1d.state(set(0)) mustBe 'I'
    mem.writesOf(set(0)) mustBe Seq(arch.line(set(0)))
    mem.writesOf(set(1)) mustBe Seq(arch.line(set(1)))
    for (l <- set.take(2)) dma.pending += ClientReq.read(l)
    settle()
    mem.readsOf(set(0)) mustBe 2
    mem.readsOf(set(1)) mustBe 2
  }

  "AXI read errors return error responses without installing the line" in withL2() { e =>
    import e._
    val f = line(0x700); val h = line(0x740)
    mem.errorLines ++= Seq(f, h)
    l1d.expectError += f
    l1d.acquire(f); settle()
    l1i.pending += ClientReq(ReqOp.Read, h, expectError = true); settle()
    l1d.state(f) mustBe 'I'
    mem.errorLines.clear()
    l1d.acquire(f); l1i.pending += ClientReq.read(h); settle()
    mem.readsOf(f) mustBe 2
    mem.readsOf(h) mustBe 2
    l1d.state(f) mustBe 'E'
  }

  "four concurrent misses share the two slots and every client gets its line" in withL2() { e =>
    import e._
    mem.minLatency = 20; mem.maxLatency = 20
    val ls = (0 until 4).map(k => line(0x800 + k * LineBytes))
    l1d.acquire(ls(0))
    l1i.pending ++= Seq(ClientReq.read(ls(1)), ClientReq.read(ls(2)))
    dma.pending += ClientReq.read(ls(3))
    settle()
    mem.reads.map(_._2).toSet mustBe ls.toSet
    mem.maxReadsInFlight mustBe g.l2Slots
    l1i.results.size mustBe 2
    dma.results.size mustBe 1
  }

  /** Random L1D acquire/store/evict, L1I reads and DMA reads/writes over
    * three L2 sets with more lines than ways, random AXI backpressure and
    * probe answer delays. Afterwards every line reads back its golden value.
    */
  private def randomTraffic(e: Env, cycles: Int): Unit = {
    import e._
    val pool = (for (s <- 0 until 3; t <- 0 until g.l2Ways + 2) yield line(0x2000 + s * LineBytes + t * l2Stride)).distinct
    l1d.randomLines = pool; l1d.randomProb = 0.3; l1d.answerDelayMax = 4
    mem.arReadyProb = 0.7; mem.awReadyProb = 0.7; mem.wReadyProb = 0.7; mem.rValidProb = 0.8
    mem.minLatency = 1; mem.maxLatency = 10
    l1i.issueProb = 0.2; dma.issueProb = 0.2
    def pick = pool(rng.nextInt(pool.size))
    for (_ <- 0 until cycles / 4) {
      l1i.pending += ClientReq.read(pick)
      dma.pending += (if (rng.nextBoolean()) ClientReq.read(pick) else ClientReq.write(pick, BigInt(32, rng), BigInt(256, rng)))
    }
    bench.steps(cycles)
    l1d.randomProb = 0
    l1i.pending.clear(); dma.pending.clear()
    settle()
    l1d.probes must not be empty
    mem.writes must not be empty
    val before = dma.results.size
    for (l <- pool) dma.pending += ClientReq.read(l)
    settle()
    for ((_, q, data, _) <- dma.results.drop(before))
      withClue(s"line ${hex(q.line << 5)}: ") { data mustBe arch.line(q.line) }
  }

  for (seed <- Seq(31, 32))
    s"random L1D, L1I and DMA traffic stays coherent with the golden memory with seed $seed" in
      withL2(seed = seed) { e => randomTraffic(e, 6000) }

  "non-default geometry smoke: random traffic on the single-set two-way stress L2" in
    withL2(BreezeMemGeometry.stress.copy(nCores = 1), seed = 41) { e => randomTraffic(e, 4000) }

  "non-default geometry smoke: random traffic on a four-way L2" in
    withL2(BreezeMemGeometry.l2FourWay.copy(nCores = 1), seed = 42) { e => randomTraffic(e, 4000) }
}
