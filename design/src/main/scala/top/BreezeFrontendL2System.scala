package flow.top

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import flow.bus.{Axi4MasterIO, Axi4Params}
import flow.coherence.{CoherenceParams, L1DCoherenceIO, ReadClientIO}
import flow.config.BreezeMemGeometry
import flow.frontend.{BreezeFrontendPipeline, BreezeFrontendPipelineParams, PipelineInstruction, PipelineResolution}
import flow.interface.BreezeMmuContext
import flow.l1i.L1IClient
import flow.l2.{L2Events, L2Home}
import flow.mmu.sv39.{PtwMemIO, SfenceIO, Sv39Mmu, Sv39MmuContextLink, Sv39MmuParams}

/** Per-hart boundary for the new backend and the future D-cache PTW path.
  * Instruction refills are internal to BreezeFrontendL2System.
  */
class BreezeFrontendL2Port(p: BreezeFrontendPipelineParams) extends Bundle {
  val resetPc = Input(UInt(64.W))
  val context = Input(new BreezeMmuContext(64))
  val block = Input(Bool())
  val redirect = Flipped(Valid(UInt(64.W)))
  val fenceI = Input(Bool())
  val sfence = Input(new SfenceIO)
  val resolve = Flipped(Decoupled(new PipelineResolution(p)))
  val committedBranch = Flipped(Valid(Bool()))
  val instruction = Decoupled(new PipelineInstruction(p))
  val ptwMem = new PtwMemIO
  // Frontend-local idle; L2 and external clients may still own transactions.
  val idle = Output(Bool())
}

/** New frontend -> VIPT I-cache -> L1I Read client -> shared L2 -> AXI.
  * This is an integration top with no dependency on the legacy backend.
  * External L1D clients retain their real coherence ports, including probes;
  * page-table reads use the per-hart MMU's external physical PTW interface.
  */
class BreezeFrontendL2System(
    g: BreezeMemGeometry = BreezeMemGeometry(nCores = 1),
    frontendParams: BreezeFrontendPipelineParams = BreezeFrontendPipelineParams(),
    mmuParams: Sv39MmuParams = Sv39MmuParams()) extends Module {
  private val p = CoherenceParams(g)
  val io = IO(new Bundle {
    val harts = Vec(p.nCores, new BreezeFrontendL2Port(frontendParams))
    val l1d = Vec(p.nCores, Flipped(new L1DCoherenceIO(p)))
    val dma = Flipped(new ReadClientIO(p))
    val mem = new Axi4MasterIO(Axi4Params(p.paddrBits, p.memDataBits, p.slotBits))
    val l2Events = Output(new L2Events(p))
  })

  val l2 = Module(new L2Home(g))
  io.mem <> l2.io.mem
  l2.io.dma <> io.dma
  io.l2Events := l2.io.events

  for (h <- 0 until p.nCores) {
    val frontend = Module(new BreezeFrontendPipeline(g, frontendParams, mmuParams.itlb))
    val mmu = Module(new Sv39Mmu(mmuParams, withPmpCandidate = true,
      withFetchCandidates = true, pipelinedFetchKill = true))
    frontend.io.itlb <> mmu.io.itlb
    Sv39MmuContextLink(mmu, io.harts(h).context)
    mmu.io.dtlb.req.valid := false.B
    mmu.io.dtlb.req.bits := 0.U.asTypeOf(mmu.io.dtlb.req.bits)
    mmu.io.dtlb.kill := false.B
    val refill = Module(new L1IClient(g))
    val hart = io.harts(h)

    frontend.io.resetPc := hart.resetPc
    frontend.io.context := hart.context
    frontend.io.block := hart.block
    frontend.io.redirect := hart.redirect
    frontend.io.fenceI := hart.fenceI
    mmu.io.sfence := hart.sfence
    frontend.io.resolve <> hart.resolve
    frontend.io.committedBranch := hart.committedBranch
    hart.instruction <> frontend.io.instruction
    hart.ptwMem <> mmu.io.ptwMem
    hart.idle := frontend.io.idle && mmu.io.idle

    // Capture every single-cycle miss pulse before L2 can backpressure it.
    // L1IClient holds Read(id=0) until accepted, then consumes ReadData/error
    // and crosses its existing registered return boundary back to the cache.
    refill.io.demand <> frontend.io.refillReq
    frontend.io.refillRsp <> refill.io.demandRsp
    l2.io.l1i(h) <> refill.io.coh

    // No prefetch generation/installation contract is added in this step.
    refill.io.prefetch.valid := false.B
    refill.io.prefetch.bits := 0.U

    // Preserve owner-probe/latest-data semantics for instruction Read traffic.
    // The external D-cache implements these links; no synthetic acknowledgments.
    l2.io.l1d(h) <> io.l1d(h)
  }
}

/** Standalone generation entry; accepts the normal ChiselStage CLI arguments. */
object GenerateBreezeFrontendL2System extends App {
  ChiselStage.emitSystemVerilogFile(new BreezeFrontendL2System(), args,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable"))
}
