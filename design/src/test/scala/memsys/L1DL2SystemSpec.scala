package flow.memsys

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.interface._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.util.Random

import MemTestKit._

/** One core's real L1D on the real L2 with AXI memory, plus L1I and DMA
  * clients (l1d-rtl-spec 13.3, single core). `finish` reads every written
  * line through L2 with the DMA client, which pulls any dirty L1D copy,
  * and requires the exact golden value.
  */
class L1DL2SystemSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Env(val d: L1DL2Harness, val g: BreezeMemGeometry, seed: Int) {
    val rng = new Random(seed)
    val arch = new GoldenMem(seed)
    val dram = new GoldenMem(seed)
    val device = new AxiLiteDevice(d.io.mmio, rng, seed + 7)
    val core = new CoreDriver(d.io.core, arch, Some(device), rng)
    val ptw = new PtwDriver(d.io.ptw, arch)
    val tlb = new TlbKnobs(d.io.tlb, rng)
    val mem = new AxiMemory(d.io.mem, dram, rng)
    val l1i = new ReadClientAgent(d.io.l1i, "l1i", arch, rng, 2)
    val dma = new ReadClientAgent(d.io.dma, "dma", arch, rng, 1)
    val bench = new Bench(d.clock, Seq(tlb, core, ptw, mem, device, l1i, dma))
    /** Next tag in the same L1D set and the same L2 set. */
    val stride: BigInt = (BigInt(g.l1Sets) max BigInt(CoherenceParams(g).l2Sets)) * LineBytes
    def ram(off: BigInt): BigInt = MainRam + off
    def run(ops: CoreOp*): Unit = { core.enqueue(ops: _*); bench.quiesce() }
    def finish(): Unit = {
      bench.quiesce()
      val before = dma.results.size
      for (l <- arch.touched.toSeq.sorted) dma.pending += ClientReq.read(l)
      bench.quiesce()
      for ((_, q, data, _) <- dma.results.drop(before))
        withClue(s"line ${hex(q.line << 5)}: ") { data mustBe arch.line(q.line) }
    }
  }

  private def init(d: L1DL2Harness): Unit = {
    val c = d.io.core
    c.req.valid.poke(false.B); c.req.bits.op.poke(L1DOp.Load); c.req.bits.vaddr.poke(0.U)
    c.req.bits.size.poke(3.U); c.req.bits.signed.poke(false.B); c.req.bits.amoFunc.poke(BreezeAmoFunc.Swap)
    c.req.bits.aq.poke(false.B); c.req.bits.rl.poke(false.B); c.req.bits.wdata.poke(0.U)
    c.req.bits.rd.isFp.poke(false.B); c.req.bits.rd.idx.poke(1.U); c.req.bits.isFlw.poke(false.B)
    c.s1Kill.poke(false.B); c.s2Kill.poke(false.B); c.trapClearRsv.poke(false.B); c.late.ready.poke(true.B)
    c.csr.satp.poke(0.U); c.csr.privilege.poke(3.U); c.csr.mprv.poke(false.B); c.csr.mpp.poke(0.U)
    c.csr.sum.poke(false.B); c.csr.mxr.poke(false.B); c.csr.adue.poke(false.B)
    for (i <- c.csr.pmpcfg.indices) { c.csr.pmpcfg(i).poke(0.U); c.csr.pmpaddr(i).poke(0.U) }
    c.csr.pmpcfg(0).poke(0x1f.U); c.csr.pmpaddr(0).poke(0x1fffffff.U)
    d.io.ptw.req.valid.poke(false.B); d.io.ptw.req.bits.paddr.poke(0.U)
    val m = d.io.mmio
    m.ar.ready.poke(false.B); m.aw.ready.poke(false.B); m.w.ready.poke(false.B)
    m.r.valid.poke(false.B); m.r.bits.data.poke(0.U); m.r.bits.resp.poke(0.U)
    m.b.valid.poke(false.B); m.b.bits.poke(0.U)
    val x = d.io.mem
    x.ar.ready.poke(false.B); x.aw.ready.poke(false.B); x.w.ready.poke(false.B)
    x.r.valid.poke(false.B); x.r.bits.id.poke(0.U); x.r.bits.data.poke(0.U); x.r.bits.resp.poke(0.U)
    x.r.bits.last.poke(false.B); x.b.valid.poke(false.B); x.b.bits.id.poke(0.U); x.b.bits.resp.poke(0.U)
    for (r <- Seq(d.io.l1i, d.io.dma)) {
      r.req.valid.poke(false.B); r.req.bits.op.poke(ReqOp.Read); r.req.bits.addr.poke(0.U)
      r.req.bits.id.poke(0.U); r.req.bits.mask.poke(0.U); r.req.bits.data.poke(0.U)
      r.rspDown.ready.poke(true.B)
    }
    d.io.tlb.tlbReady.poke(true.B); d.io.tlb.tlbMiss.poke(false.B); d.io.tlb.tlbPageFault.poke(false.B)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }

  private def withSys(g: BreezeMemGeometry = BreezeMemGeometry.singleCore, seed: Int = 1)(body: Env => Unit): Unit =
    simulate(new L1DL2Harness(g)) { d => init(d); body(new Env(d, g, seed)) }

  "loads and stores through the real L2 read each line from memory once and match the golden memory" in withSys() { e =>
    import e._
    val ls = (0 until 5).map(k => ram(0x100 + k * LineBytes))
    run(ls.take(4).map(CoreOp.load(_)): _*)
    run(CoreOp.store(ls(0) + 8, 0x1234), CoreOp.store(ls(1), 0x55, 0), CoreOp.store(ls(4) + 16, BigInt("abcdef01", 16), 2))
    run(ls.map(CoreOp.load(_)) ++ Seq(CoreOp.load(ls(4) + 16, 2)): _*)
    mem.reads.map(_._2).sorted mustBe ls.map(lineOf).sorted
    finish()
  }

  "L1D and L2 evictions carry dirty data through L2 and AXI, and it reads back correctly" in withSys() { e =>
    import e._
    val ls = (0 until g.l2Ways + g.l1dWays + 2).map(k => ram(0x200 + k * stride))
    run(ls.zipWithIndex.map { case (a, i) => CoreOp.store(a + 8 * (i % 4), BigInt(i + 1) * 0x0101010101L) }: _*)
    mem.writes must not be empty
    run(ls.map(CoreOp.load(_)): _*)
    finish()
  }

  "L1I and DMA reads of a line the core owns return its dirty data, and the core keeps writing it" in withSys() { e =>
    import e._
    val a = ram(0x300)
    run(CoreOp.store(a, BigInt("feedface", 16), 2))
    l1i.pending += ClientReq.read(lineOf(a)); bench.quiesce()
    l1i.results.last._3 mustBe arch.line(lineOf(a))
    run(CoreOp.store(a + 4, 0x77, 0), CoreOp.load(a))
    dma.pending += ClientReq.read(lineOf(a)); bench.quiesce()
    dma.results.last._3 mustBe arch.line(lineOf(a))
    finish()
  }

  "a DMA write to a line the core holds invalidates it, and the next core load sees the DMA bytes from L2" in withSys() { e =>
    import e._
    val a = ram(0x400)
    run(CoreOp.store(a, BigInt("1111111111111111", 16)), CoreOp.load(a + 16))
    dma.pending += ClientReq.write(lineOf(a), BigInt(0xff) << 16, BigInt("2222222222222222", 16) << 128)
    bench.quiesce()
    val reads = mem.reads.size
    run(CoreOp.load(a + 16), CoreOp.load(a), CoreOp.load(a + 8))
    core.history.takeRight(3).head.kind mustBe "Mshr"
    mem.reads.size mustBe reads
    finish()
  }

  "PTW reads and device accesses work beside the real L2" in withSys() { e =>
    import e._
    val a = ram(0x500)
    ptw.read(a + 8); bench.quiesce()
    run(CoreOp.load(a), CoreOp.store(a + 8, 0x31))
    ptw.read(a + 8); bench.quiesce()
    ptw.results.size mustBe 2
    device.regs.write(Device, 8, BigInt("0102030405060708", 16))
    run(CoreOp.load(Device + 2, 1).copy(device = true), CoreOp.store(Device + 4, 0xaabb, 1).copy(device = true))
    device.log.map(_._1) mustBe Seq("R", "W")
    finish()
  }

  /** CPU traffic over lines that conflict in both L1D and L2, while L1I/DMA
    * traffic on other lines of the same L2 set forces L2 evictions (and so
    * Inv probes) of CPU lines. L1I also reads CPU lines unchecked, to force
    * Down probes; CPU loads and the final read-back check those lines.
    */
  private def randomTraffic(e: Env, ops: Int): Unit = {
    import e._
    val cpuTags = g.l1dWays + 2
    val cpuLines = for (s <- 0 until 2; t <- 0 until cpuTags) yield ram(0x1000 + s * LineBytes + t * stride)
    val ioLines = (0 until g.l2Ways).map(t => lineOf(ram(0x1000 + (cpuTags + t) * stride)))
    core.issueProb = 0.7; core.lateReadyProb = 0.6
    tlb.missProb = 0.02; tlb.busyProb = 0.03
    mem.arReadyProb = 0.7; mem.awReadyProb = 0.7; mem.wReadyProb = 0.8; mem.rValidProb = 0.8
    mem.minLatency = 1; mem.maxLatency = 12
    l1i.issueProb = 0.05; dma.issueProb = 0.05
    for (_ <- 0 until ops) {
      val size = rng.nextInt(4)
      val a = cpuLines(rng.nextInt(cpuLines.size)) + (rng.nextInt(LineBytes >> size) << size)
      core.enqueue(
        if (rng.nextInt(10) < 6) CoreOp.load(a, size, rng.nextBoolean(), rd = 1 + rng.nextInt(31))
        else CoreOp.store(a, BigInt(64, rng), size))
    }
    def ioLine = ioLines(rng.nextInt(ioLines.size))
    for (_ <- 0 until ops / 8) {
      l1i.pending += (if (rng.nextBoolean()) ClientReq.read(ioLine)
        else ClientReq.read(lineOf(cpuLines(rng.nextInt(cpuLines.size))), check = false))
      dma.pending += (if (rng.nextBoolean()) ClientReq.read(ioLine) else ClientReq.write(ioLine, BigInt(32, rng), BigInt(256, rng)))
    }
    bench.runUntil(core.idle)
    l1i.pending.clear(); dma.pending.clear()
    tlb.missProb = 0; tlb.busyProb = 0
    finish()
    mem.writes must not be empty
  }

  for (seed <- Seq(51, 52))
    s"random single-core traffic with L2 evictions, probes and backpressure matches the golden memory with seed $seed" in
      withSys(seed = seed) { e => randomTraffic(e, 2000) }

  "non-default geometry smoke: random system traffic on the stress geometry" in
    withSys(BreezeMemGeometry.stress.copy(nCores = 1), seed = 61) { e => randomTraffic(e, 1000) }

  "non-default geometry smoke: random system traffic with a two-way L1D" in
    withSys(BreezeMemGeometry.l1dTwoWay.copy(nCores = 1), seed = 62) { e => randomTraffic(e, 1000) }

  "non-default geometry smoke: random system traffic with a four-way L2" in
    withSys(BreezeMemGeometry.l2FourWay.copy(nCores = 1), seed = 63) { e => randomTraffic(e, 1000) }
}
