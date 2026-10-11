package flow.cluster

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.{BreezeClusterPresets, PrivilegeProfile}
import flow.coherence.ReqOp
import flow.fpu.BreezeFpSources
import flow.memsys.{CycleAgent, GoldenMem, MaskedAxiMemory}
import flow.top.BreezeRvvSystem
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import svsim.{CommonCompilationSettings, CommonSettingsModifications}
import scala.collection.mutable
import scala.util.Random

/** Separate wide-bus runner: original scalar ELF signatures and reference
  * memory are retained, and the frozen scalar runner is unchanged.
  */
class RvvSystemSpec extends AnyFreeSpec with Matchers with ChiselSim {
  implicit private val fpIncludes: CommonSettingsModifications =
    (s: CommonCompilationSettings) => s.copy(includeDirs = Some(
      (s.includeDirs.getOrElse(Seq.empty) :+ BreezeFpSources.includeDir.toString).distinct))

  "RVV system retains the minimum scalar ISA signatures and executes the vector memory smoke" in {
    val cfg = BreezeClusterPresets.singleRvv.copy(privilegeProfile = PrivilegeProfile.Linux)
    val names = Seq("rv64ui-p-add", "rv64ui-p-lw", "rv64ui-p-sw", "rv64ui-p-beq",
      "rv64um-p-mul", "rv64um-p-div", "rv64ua-p-amoadd_d", "rv64ua-p-lrsc",
      "rv64uc-p-rvc", "rv64mi-p-csr")
    val files = names.map(n => ClusterSim.repoRoot.resolve(s"design/build/riscv-tests/$n")) :+
      ClusterSim.repoRoot.resolve("design/build/cluster-tests/rvv_memory")
    simulate(new BreezeRvvSystem(cfg, enableTandem = true)) { d =>
      for (file <- files) {
        val elf = Elf64.load(file); val tohost = elf.symbol("tohost")
        val memory = new GoldenMem(91) { override def background(a: BigInt): Int = 0 }
        for (seg <- elf.segments; k <- seg.data.indices)
          memory.write(seg.paddr + k, 1, BigInt(seg.data(k) & 255))
        val rng = new Random(91)
        val axi = new MaskedAxiMemory(d.io.mem, memory, rng)
        val mmio = new ClusterMmio(d.io.mmio, 1, 16, rng, true)
        val agents: Seq[CycleAgent] = Seq(axi, mmio)
        d.io.resetAddr.poke(0x80000000L.U); d.io.time.poke(0.U)
        d.io.msip(0).poke(false.B); d.io.mtip(0).poke(false.B)
        d.io.externalInterrupts(0).poke(0.U); d.io.supervisorExternalInterrupts(0).poke(false.B)
        d.io.dma.req.valid.poke(false.B); d.io.dma.req.bits.op.poke(ReqOp.Read)
        d.io.dma.req.bits.addr.poke(0.U); d.io.dma.req.bits.id.poke(0.U)
        d.io.dma.req.bits.mask.poke(0.U); d.io.dma.req.bits.data.poke(0.U)
        d.io.dma.rspDown.ready.poke(true.B)
        agents.foreach(_.drivePhase(0))
        d.reset.poke(true.B); d.clock.step(5); d.reset.poke(false.B)
        var cycle = 0L; var lastCommit = 0L; var retired = 0L
        var signature = BigInt(0)
        val ring = mutable.Queue.empty[String]
        while (signature == 0 && cycle < 2000000) {
          agents.foreach(_.drivePhase(cycle)); d.io.time.poke(mmio.mtime.U)
          d.io.msip(0).poke(mmio.msip(0).B); d.io.mtip(0).poke(mmio.mtip(0).B)
          agents.foreach(_.decide()); agents.foreach(_.sample())
          val t = d.io.retire(0)
          if (t.valid.peek().litToBoolean) {
            retired += 1; lastCommit = cycle
            ring.enqueue(s"$cycle pc=${t.pc.peek().litValue.toString(16)} inst=${t.inst.peek().litValue.toString(16)}")
            if (ring.size > 32) ring.dequeue()
          }
          // WT stores reach memory before retiring. Observe only after the
          // matching retired store; a speculative bus write cannot pass.
          if (t.valid.peek().litToBoolean && t.memEn.peek().litToBoolean && t.memIsWrite.peek().litToBoolean &&
              t.memAlignedAddr.peek().litValue == (tohost & ~BigInt(7)))
            signature = memory.read(tohost, 8)
          withClue(s"$file cycle=$cycle\n${ring.mkString("\n")}") {
            d.io.hartFatal(0).peek().litToBoolean mustBe false
            (cycle - lastCommit <= 20000) mustBe true
          }
          d.clock.step(); cycle += 1
        }
        info(s"${file.getFileName}: tohost=$signature cycles=$cycle retired=$retired")
        withClue(s"$file\n${ring.mkString("\n")}") { signature mustBe BigInt(1) }
        if (file.getFileName.toString == "rvv_memory") {
          val output = elf.symbol("vector_output")
          for (k <- 0 until 8) memory.read(output + k * 8, 8) mustBe BigInt(12)
        }
      }
    }
  }
}
