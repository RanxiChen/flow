package flow.memsys

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.bus._
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.interface._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.util.Random

import MemTestKit._

/** Wire-only peers let the tests exercise their Scala drivers independently
  * of cache RTL failures. All peer outputs are controlled by the test.
  */
class MemAgentTestHarness extends Module {
  val p = CoherenceParams(BreezeMemGeometry.singleCore)
  val ap = Axi4Params(p.paddrBits, 64, p.slotBits)
  val io = IO(new Bundle {
    val core = new L1DCoreIO
    val corePeer = Flipped(new L1DCoreIO)
    val coh = new L1DCoherenceIO(p)
    val cohPeer = Flipped(new L1DCoherenceIO(p))
    val mem = new Axi4MasterIO(ap)
    val memPeer = Flipped(new Axi4MasterIO(ap))
  })
  io.core <> io.corePeer
  io.coh <> io.cohPeer
  io.mem <> io.memPeer
}

class MemAgentsSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private def init(d: MemAgentTestHarness): Unit = {
    val c = d.io.corePeer
    c.req.ready.poke(true.B); c.resp.valid.poke(false.B); c.late.valid.poke(false.B)
    c.s2Hold.poke(false.B); c.drained.poke(true.B); c.mmioBusy.poke(false.B)
    val h = d.io.cohPeer
    h.req.valid.poke(false.B); h.req.bits.op.poke(ReqOp.GetS); h.req.bits.addr.poke(0.U)
    h.req.bits.id.poke(0.U); h.req.bits.mask.poke(0.U); h.req.bits.data.poke(0.U)
    h.rspUp.valid.poke(false.B); h.snp.ready.poke(true.B); h.rspDown.ready.poke(true.B)
    val m = d.io.memPeer
    m.ar.valid.poke(false.B); m.aw.valid.poke(false.B); m.w.valid.poke(false.B)
    m.r.ready.poke(false.B); m.b.ready.poke(false.B)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }

  for (op <- Seq(SnpOp.Inv, SnpOp.Down)) {
    s"behavioral L2 presents $op on the same edge as DataE and holds a stalled SNP" in
      simulate(new MemAgentTestHarness) { d =>
        init(d)
        val l = lineOf(MainRam + 0x100)
        val model = new BehavioralL2(d.io.coh, new GoldenMem(1), new Random(1))
        val bench = new Bench(d.clock, Seq(model))
        model.probeAfterGrant(l) = op
        d.io.cohPeer.req.valid.poke(true.B); d.io.cohPeer.req.bits.addr.poke(l.U)
        bench.step()
        d.io.cohPeer.req.valid.poke(false.B); d.io.cohPeer.snp.ready.poke(false.B)
        // Inspect both inputs before the same edge, even if SNP is stalled.
        model.drivePhase(bench.cycle)
        d.io.coh.rspDown.valid.peek().litToBoolean mustBe true
        d.io.coh.snp.valid.peek().litToBoolean mustBe true
        d.io.coh.snp.bits.op.peek().litValue mustBe op.litValue
        d.io.coh.snp.bits.owner.peek().litToBoolean mustBe true
        d.io.coh.snp.bits.addr.peek().litValue mustBe l
        bench.step()
        val grantCycle = model.grants.head._1
        bench.steps(7)
        d.io.coh.snp.valid.peek().litToBoolean mustBe true
        d.io.coh.snp.bits.op.peek().litValue mustBe op.litValue
        d.io.coh.snp.bits.addr.peek().litValue mustBe l
        model.probes mustBe empty
        d.io.cohPeer.snp.ready.poke(true.B); bench.step()
        model.probes.head._1 must be > grantCycle
        model.grants.size mustBe 1
      }
  }

  "an accepted grant and probe are logged on exactly the same edge" in simulate(new MemAgentTestHarness) { d =>
    init(d)
    val l = lineOf(MainRam + 0x200)
    val model = new BehavioralL2(d.io.coh, new GoldenMem(2), new Random(2))
    val bench = new Bench(d.clock, Seq(model))
    model.probeAfterGrant(l) = SnpOp.Inv
    d.io.cohPeer.req.valid.poke(true.B); d.io.cohPeer.req.bits.addr.poke(l.U); bench.step()
    d.io.cohPeer.req.valid.poke(false.B); bench.step()
    model.probes.head._1 mustBe model.grants.head._1
    model.probes.size mustBe 1
  }

  "quiesce waits for store miss replay and PS drain after the Mshr response" in simulate(new MemAgentTestHarness) { d =>
    init(d)
    val arch = new GoldenMem(3)
    val core = new CoreDriver(d.io.core, arch, None, new Random(3))
    val a = MainRam + 0x300
    core.enqueue(CoreOp.store(a, 0x1234))
    val releaseAt = 20L
    val peer = new CycleAgent {
      val name = "delayed-store-peer"
      protected def drive(): Unit = {
        d.io.corePeer.resp.valid.poke((now == 2).B)
        d.io.corePeer.resp.bits.kind.poke(L1DRespKind.Mshr)
        d.io.corePeer.resp.bits.data.poke(0.U); d.io.corePeer.resp.bits.excCause.poke(0.U)
        d.io.corePeer.resp.bits.tval.poke(0.U)
        d.io.corePeer.drained.poke((now >= releaseAt).B)
      }
      def sample(): Unit = ()
      // Keep the peer idle so only CoreDriver's DUT drain check holds the bench.
      def idle: Boolean = true
    }
    val bench = new Bench(d.clock, Seq(peer, core))
    bench.steps(3)
    core.history.head.done mustBe true
    arch.read(a, 8) mustBe BigInt(0x1234)
    core.idle mustBe false
    bench.quiesce()
    bench.cycle must be >= releaseAt
    core.drained mustBe true
  }

  "core idle includes MMIO busy even with drained and no outstanding requests" in simulate(new MemAgentTestHarness) { d =>
    init(d)
    val core = new CoreDriver(d.io.core, new GoldenMem(4), None, new Random(4))
    core.idle mustBe true
    d.io.corePeer.mmioBusy.poke(true.B)
    core.idle mustBe false
    d.io.corePeer.mmioBusy.poke(false.B)
    core.idle mustBe true
  }

  "AXI memory holds R and B valid and payload until acceptance despite randomized bubbles" in
    simulate(new MemAgentTestHarness) { d =>
      init(d)
      val mem = new AxiMemory(d.io.mem, new GoldenMem(5), new Random(5))
      mem.minLatency = 1; mem.maxLatency = 1
      val bench = new Bench(d.clock, Seq(mem))
      val m = d.io.memPeer
      def addr(a: Axi4Ar, id: Int, value: BigInt): Unit = {
        a.id.poke(id.U); a.addr.poke(value.U); a.len.poke(3.U); a.size.poke(3.U)
        a.burst.poke(1.U); a.prot.poke(0.U)
      }
      addr(m.ar.bits, 1, MainRam + 0x400)
      addr(m.aw.bits, 0, MainRam + 0x500)
      m.ar.valid.poke(true.B); m.aw.valid.poke(true.B); bench.step()
      m.ar.valid.poke(false.B); m.aw.valid.poke(false.B)
      // Present an R beat, then turn off new R assertions while stalled.
      bench.step()
      val r = d.io.mem.r.bits
      val heldR = (r.id.peek().litValue, r.data.peek().litValue, r.resp.peek().litValue, r.last.peek().litToBoolean)
      mem.rValidProb = 0
      m.w.valid.poke(true.B); m.w.bits.strb.poke(0xff.U)
      for (i <- 0 until 4) {
        m.w.bits.data.poke((i + 10).U); m.w.bits.last.poke((i == 3).B); bench.step()
      }
      m.w.valid.poke(false.B); bench.step()
      d.io.mem.b.valid.peek().litToBoolean mustBe true
      mem.bValidProb = 0
      for (_ <- 0 until 8) {
        bench.step()
        d.io.mem.r.valid.peek().litToBoolean mustBe true
        (r.id.peek().litValue, r.data.peek().litValue, r.resp.peek().litValue, r.last.peek().litToBoolean) mustBe heldR
        d.io.mem.b.valid.peek().litToBoolean mustBe true
        d.io.mem.b.bits.id.peek().litValue mustBe 0
        d.io.mem.b.bits.resp.peek().litValue mustBe 0
      }
      m.r.ready.poke(true.B); m.b.ready.poke(true.B); bench.step(); bench.step()
      d.io.mem.r.valid.peek().litToBoolean mustBe false
      d.io.mem.b.valid.peek().litToBoolean mustBe false
      mem.rValidProb = 1
      bench.quiesce()
      mem.idle mustBe true
      mem.writes.head._3 mustBe (0 until 4).map(i => BigInt(i + 10) << (64 * i)).reduce(_ | _)
    }

  for (field <- Seq("mask", "data")) {
    s"behavioral L2 rejects changes to stalled REQ $field" in simulate(new MemAgentTestHarness) { d =>
      init(d)
      val model = new BehavioralL2(d.io.coh, new GoldenMem(6), new Random(6))
      model.reqReadyProb = 0
      val bench = new Bench(d.clock, Seq(model))
      d.io.cohPeer.req.valid.poke(true.B); bench.step()
      if (field == "mask") d.io.cohPeer.req.bits.mask.poke(1.U)
      else d.io.cohPeer.req.bits.data.poke(1.U)
      intercept[AssertionError] { bench.step() }.getMessage must include("REQ changed before acceptance")
    }
  }

  "Scala L1D waits for a DownAck handshake before starting a store to the probed line" in
    simulate(new MemAgentTestHarness) { d =>
      init(d)
      val arch = new GoldenMem(7)
      val l = lineOf(MainRam + 0x600)
      val before = arch.line(l)
      val proxy = new L1DProxy(d.io.cohPeer, arch, new Random(7))
      proxy.lines(l) = ('M', before)
      val h = d.io.coh
      h.req.ready.poke(true.B); h.rspUp.ready.poke(false.B); h.rspDown.valid.poke(false.B)
      h.snp.valid.poke(true.B); h.snp.bits.op.poke(SnpOp.Down)
      h.snp.bits.owner.poke(true.B); h.snp.bits.addr.poke(l.U)
      val bench = new Bench(d.clock, Seq(proxy))
      bench.step()
      h.snp.valid.poke(false.B)
      proxy.store(l, 8, 0x1234)
      bench.steps(6)
      arch.line(l) mustBe before
      proxy.script.size mustBe 1
      d.io.cohPeer.req.valid.peek().litToBoolean mustBe false
      d.io.cohPeer.rspUp.valid.peek().litToBoolean mustBe true
      d.io.cohPeer.rspUp.bits.op.peek().litValue mustBe RspUpOp.DownAck.litValue
      d.io.cohPeer.rspUp.bits.hasData.peek().litToBoolean mustBe true
      d.io.cohPeer.rspUp.bits.data.peek().litValue mustBe before
      h.rspUp.ready.poke(true.B); bench.step(); bench.step()
      proxy.script mustBe empty
      proxy.grants mustBe empty
      d.io.cohPeer.req.bits.op.peek().litValue mustBe ReqOp.GetM.litValue
      h.rspDown.valid.poke(true.B); h.rspDown.bits.op.poke(RspDownOp.AckE)
      h.rspDown.bits.id.poke(0.U); h.rspDown.bits.error.poke(false.B); h.rspDown.bits.data.poke(0.U)
      bench.step()
      h.rspDown.valid.poke(false.B); bench.quiesce()
      arch.read((l << 5) + 8, 8) mustBe BigInt(0x1234)
      proxy.lines(l)._2 mustBe arch.line(l)
    }
}
