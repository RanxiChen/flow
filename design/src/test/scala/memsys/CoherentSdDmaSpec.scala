package flow.memsys

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.bus._
import flow.coherence._
import flow.config.BreezeClusterPresets
import flow.l2.L2Home
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.util.Random
import MemTestKit._

/** Actual SD bus adapter and Home; the existing independent byte oracle,
  * dirty-owner proxy and AXI memory are used without changing their rules.
  */
class CoherentSdDmaHarness extends Module {
  val g = BreezeClusterPresets.single.mem
  val p = CoherenceParams(g)
  val io = IO(new Bundle {
    val wb = Flipped(new LiteXWishboneMasterIO(LiteXWishboneParameters(g.paddrBits, 64)))
    val l1d = Flipped(new L1DCoherenceIO(p))
    val mem = new Axi4MasterIO(Axi4Params(g.paddrBits, p.memDataBits, p.slotBits))
  })
  val bridge = Module(new CoherentDmaWishbone(g))
  val home = Module(new L2Home(g, reportDmaErrors = true))
  bridge.io.wishbone <> io.wb
  home.io.dma <> bridge.io.home
  home.io.l1d(0) <> io.l1d
  home.io.l1i(0).req.valid := false.B
  home.io.l1i(0).req.bits := 0.U.asTypeOf(home.io.l1i(0).req.bits)
  home.io.l1i(0).rspDown.ready := true.B
  io.mem <> home.io.mem
}

class CoherentSdDmaSpec extends AnyFreeSpec with Matchers with ChiselSim {
  "SD DMA sees dirty CPU bytes, invalidates masked-write owners, and reports AXI failures before recovery" in {
    simulate(new CoherentSdDmaHarness) { d =>
      val arch = new GoldenMem(91)
      val dram = new GoldenMem(91)
      val rng = new Random(91)
      val cpu = new L1DProxy(d.io.l1d, arch, rng)
      val mem = new AxiMemory(d.io.mem, dram, rng)
      cpu.answerDelayMax = 9
      mem.arReadyProb = 0.6; mem.rValidProb = 0.6
      mem.awReadyProb = 0.6; mem.wReadyProb = 0.6; mem.bValidProb = 0.6
      val bench = new Bench(d.clock, Seq(cpu, mem))
      val b = d.io.wb
      b.cyc.poke(false.B); b.stb.poke(false.B); b.we.poke(false.B)
      b.adr.poke(0.U); b.dat_w.poke(0.U); b.sel.poke(255.U); b.cti.poke(0.U); b.bte.poke(0.U)
      cpu.drivePhase(0); mem.drivePhase(0)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def transfer(a: BigInt, write: Boolean = false, data: BigInt = 0,
          bytes: Int = 255, error: Boolean = false): BigInt = {
        b.adr.poke((a >> 3).U); b.we.poke(write.B); b.dat_w.poke(data.U); b.sel.poke(bytes.U)
        b.cyc.poke(true.B); b.stb.poke(true.B)
        bench.runUntil(b.ack.peek().litToBoolean || b.err.peek().litToBoolean)
        b.err.expect(error.B); b.ack.expect((!error).B)
        val value = b.dat_r.peek().litValue
        if (!error) {
          if (write) arch.writeMasked(lineOf(a), BigInt(bytes) << (a.toInt & 31),
            data << (8 * (a.toInt & 31)), bench.cycle)
          else value mustBe arch.read(a, 8)
        }
        bench.step()
        b.cyc.poke(false.B); b.stb.poke(false.B); bench.steps(2); bench.quiesce()
        value
      }
      val a = MainRam + 0x400
      cpu.store(lineOf(a), 0, BigInt("1111222233334444", 16))
      cpu.store(lineOf(a), 8, BigInt("5555666677778888", 16)); bench.quiesce()
      transfer(a)
      cpu.probes.last._2 mustBe "Down"
      cpu.store(lineOf(a), 16, BigInt("9999aaaabbbbcccc", 16)); bench.quiesce()
      transfer(a + 8, true, BigInt("123456789abcdef0", 16), 0xa5)
      cpu.state(lineOf(a)) mustBe 'I'
      cpu.probes.last._2 mustBe "Inv"
      for (off <- Seq(0, 8, 16, 24)) transfer(a + off)
      cpu.acquire(lineOf(a)); bench.quiesce()
      cpu.lines(lineOf(a))._2 mustBe arch.line(lineOf(a))
      for (write <- Seq(false, true)) {
        val bad = MainRam + (if (write) 0x800 else 0x840)
        mem.errorLines += lineOf(bad)
        val expected = arch.line(lineOf(bad))
        transfer(bad, write, BigInt("ffffffffffffffff", 16), error = true)
        arch.line(lineOf(bad)) mustBe expected
        mem.errorLines -= lineOf(bad)
        transfer(bad)
        mem.readsOf(lineOf(bad)) mustBe 2
      }
      val before = mem.reads.size
      transfer(Device, error = true); transfer(Hole, true, error = true)
      mem.reads.size mustBe before
    }
  }
}
