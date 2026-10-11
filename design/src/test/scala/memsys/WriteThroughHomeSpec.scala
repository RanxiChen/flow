package flow.memsys

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import flow.bus._
import flow.coherence._
import flow.config.BreezeClusterPresets
import flow.interface._
import flow.l1d._
import flow.rvv.RvvDatalessHome
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.collection.mutable
import scala.util.Random
import MemTestKit._

/** AXI reference supports narrow transfers on a wide bus and WSTRB. It is
  * separate from the frozen full-line-writeback memory agent.
  */
class MaskedAxiMemory(axi: Axi4MasterIO, val memory: GoldenMem, rng: Random) extends CycleAgent {
  val name = "masked-axi"
  val busBytes = axi.w.bits.strb.getWidth
  val errorReads = mutable.Set.empty[BigInt]
  val errorWrites = mutable.Set.empty[BigInt]
  var reads = 0; var writes = 0
  case class Address(id: BigInt, addr: BigInt, beats: Int, bytes: Int)
  case class Read(a: Address, values: Seq[BigInt], due: Long, bad: Boolean, var index: Int = 0)
  val rq = mutable.Queue.empty[Read]
  val awq = mutable.Queue.empty[Address]
  val wq = mutable.Queue.empty[(BigInt, BigInt, Boolean)]
  val bq = mutable.Queue.empty[(BigInt, Long, Boolean)]
  var rv = false; var bv = false
  private def address(a: Axi4Ar): Address = {
    check(a.burst.peek().litValue == 1, "non-INCR burst")
    val n = 1 << a.size.peek().litValue.toInt
    check(n <= busBytes, "transfer wider than bus")
    Address(a.id.peek().litValue, a.addr.peek().litValue, a.len.peek().litValue.toInt + 1, n)
  }
  protected def drive(): Unit = {
    axi.ar.ready.poke((rng.nextInt(4) != 0).B); axi.aw.ready.poke((rng.nextInt(4) != 0).B)
    axi.w.ready.poke((rng.nextInt(4) != 0).B)
    rv = rv || (rq.headOption.exists(_.due <= now) && rng.nextBoolean())
    bv = bv || (bq.headOption.exists(_._2 <= now) && rng.nextBoolean())
    axi.r.valid.poke(rv.B); axi.b.valid.poke(bv.B)
    axi.r.bits.id.poke(0.U); axi.r.bits.data.poke(0.U); axi.r.bits.resp.poke(0.U); axi.r.bits.last.poke(false.B)
    axi.b.bits.id.poke(0.U); axi.b.bits.resp.poke(0.U)
    rq.headOption.foreach { r =>
      axi.r.bits.id.poke(r.a.id.U); axi.r.bits.data.poke(r.values(r.index).U)
      axi.r.bits.resp.poke((if (r.bad) 2 else 0).U); axi.r.bits.last.poke((r.index == r.a.beats - 1).B)
    }
    bq.headOption.foreach { case (id, _, bad) => axi.b.bits.id.poke(id.U); axi.b.bits.resp.poke((if (bad) 2 else 0).U) }
  }
  def sample(): Unit = {
    if (axi.ar.valid.peek().litToBoolean && axi.ar.ready.peek().litToBoolean) {
      val a = address(axi.ar.bits)
      val values = (0 until a.beats).map(k => memory.read((a.addr + k * a.bytes) & ~(BigInt(busBytes) - 1), busBytes))
      rq.enqueue(Read(a, values, now + 3 + rng.nextInt(10), errorReads(lineOf(a.addr)))); reads += 1; progress()
    }
    if (rv && axi.r.ready.peek().litToBoolean) {
      rv = false; rq.head.index += 1; if (rq.head.index == rq.head.a.beats) rq.dequeue(); progress()
    }
    if (axi.aw.valid.peek().litToBoolean && axi.aw.ready.peek().litToBoolean) { awq.enqueue(address(axi.aw.bits)); progress() }
    if (axi.w.valid.peek().litToBoolean && axi.w.ready.peek().litToBoolean) {
      wq.enqueue((axi.w.bits.data.peek().litValue, axi.w.bits.strb.peek().litValue, axi.w.bits.last.peek().litToBoolean)); progress()
    }
    if (bv && axi.b.ready.peek().litToBoolean) { bv = false; bq.dequeue(); progress() }
    if (awq.nonEmpty && wq.size >= awq.head.beats) {
      val a = awq.dequeue(); val bad = errorWrites(lineOf(a.addr))
      for (k <- 0 until a.beats) {
        val (data, strb, last) = wq.dequeue()
        check(last == (k == a.beats - 1), "WLAST owner changed")
        val base = (a.addr + k * a.bytes) & ~(BigInt(busBytes) - 1)
        for (b <- 0 until busBytes if strb.testBit(b) && !bad) memory.write(base + b, 1, (data >> (8 * b)) & 255, now)
      }
      bq.enqueue((a.id, now + 3 + rng.nextInt(10), bad)); writes += 1; progress()
    }
  }
  def idle: Boolean = rq.isEmpty && awq.isEmpty && wq.isEmpty && bq.isEmpty
}

class WriteThroughHomeHarness(bits: Int) extends Module {
  val g = BreezeClusterPresets.single.mem
  val p = L1DParams(g)
  val io = IO(new Bundle {
    val core = new L1DCoreIO
    val mem = new Axi4MasterIO(Axi4Params(g.paddrBits, bits, 1))
    val mmio = new Axi4LiteMasterIO(g.paddrBits, 64)
    val inv = Flipped(Decoupled(UInt(64.W)))
  })
  val cache = Module(new L1DCache(g, writeThrough = true))
  val home = Module(new RvvDatalessHome(g, bits))
  val tlb = Module(new IdentityTlb)
  io.core <> cache.io.core; io.mmio <> cache.io.mmio
  cache.io.ptw.req.valid := false.B; cache.io.ptw.req.bits := 0.U.asTypeOf(cache.io.ptw.req.bits)
  cache.io.tlb <> tlb.io.port; tlb.io.ready := true.B; tlb.io.miss := false.B; tlb.io.pageFault := false.B
  home.io.l1d <> cache.io.coh; home.io.scalarWritePending := cache.writePending.get
  home.io.invalidate <> io.inv; io.mem <> home.io.mem
  for (port <- Seq(home.io.l1i, home.io.dma)) {
    port.req.valid := false.B; port.req.bits := 0.U.asTypeOf(port.req.bits); port.rspDown.ready := true.B
  }
}

class WriteThroughHomeSpec extends AnyFreeSpec with Matchers with ChiselSim {
  for (bits <- Seq(64, 512)) {
    s"write-through $bits-bit Home orders stores, AMO, SC, byte masks, failures and external invalidation" in {
      simulate(new WriteThroughHomeHarness(bits)) { d =>
        val rng = new Random(91); val arch = new GoldenMem(91); val dram = new GoldenMem(91)
        val device = new AxiLiteDevice(d.io.mmio, rng)
        val cpu = new CoreDriver(d.io.core, arch, Some(device), rng)
        val mem = new MaskedAxiMemory(d.io.mem, dram, rng)
        val bench = new Bench(d.clock, Seq(cpu, device, mem))
        val c = d.io.core
        c.csr.satp.poke(0.U); c.csr.privilege.poke(3.U); c.csr.mprv.poke(false.B); c.csr.mpp.poke(0.U)
        c.csr.sum.poke(false.B); c.csr.mxr.poke(false.B); c.csr.adue.poke(false.B); c.csr.permissionEvent.poke(false.B)
        for (i <- c.csr.pmpcfg.indices) { c.csr.pmpcfg(i).poke(0.U); c.csr.pmpaddr(i).poke(0.U) }
        c.csr.pmpRangesValid.poke(false.B)
        for (r <- c.csr.pmpRanges) {
          r.lower.poke(0.U); r.upper.poke(0.U); r.lowerBlockPrevious.poke(0.U)
          r.upperBlockPrevious.poke(0.U); r.nonempty.poke(false.B)
        }
        d.io.inv.valid.poke(false.B); d.io.inv.bits.poke(0.U)
        cpu.drivePhase(0); device.drivePhase(0); mem.drivePhase(0)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        def run(ops: CoreOp*): Unit = { cpu.pending ++= ops; bench.quiesce() }
        val a = MainRam + 0x420; val miss = MainRam + 0x840
        val before = mem.reads
        run(CoreOp.store(miss + 9, 0x5a, 0))
        mem.reads mustBe before // no allocation for a store miss
        dram.read(miss + 9, 1) mustBe arch.read(miss + 9, 1)
        run(CoreOp.load(a), CoreOp.store(a + 8, BigInt("1122334455667788", 16)), CoreOp.load(a + 8))
        run(CoreOp.amo(a, 7, BreezeAmoFunc.Add), CoreOp.load(a))
        run(CoreOp.lr(a), CoreOp.sc(a, 123, success = true), CoreOp.load(a))
        run(CoreOp.lr(a))
        d.io.inv.valid.poke(true.B); d.io.inv.bits.poke(a.U)
        bench.runUntil(d.io.inv.ready.peek().litToBoolean); bench.step(); d.io.inv.valid.poke(false.B); bench.steps(4)
        run(CoreOp.sc(a, 456, success = false), CoreOp.load(a))
        val original = arch.read(a + 8, 8)
        mem.errorWrites += lineOf(a)
        run(CoreOp.store(a + 8, 0xbad).copy(expectExc = Some(7)))
        dram.read(a + 8, 8) mustBe original; arch.read(a + 8, 8) mustBe original
        mem.errorWrites.clear()
        run(CoreOp.load(a + 8), CoreOp.fence)
        for (l <- arch.touched) dram.line(l) mustBe arch.line(l)
      }
    }
  }
}
