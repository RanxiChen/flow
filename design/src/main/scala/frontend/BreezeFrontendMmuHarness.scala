package flow.frontend

import chisel3._
import chisel3.util._
import flow.config.BreezeMemGeometry
import flow.interface._
import flow.mmu.sv39._

/** Standalone test/timing fixture. Production tops instantiate their own MMU.
  * No registers or protocol adapters are introduced at the iTLB boundary.
  */
class BreezeFrontendMmuHarness(g: BreezeMemGeometry = BreezeMemGeometry(nCores = 1),
    p: BreezeFrontendPipelineParams = BreezeFrontendPipelineParams(),
    mmuParams: Sv39MmuParams = Sv39MmuParams(), backendConnected: Boolean = false,
    predictorEnabled: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val resetPc = Input(UInt(64.W))
    val context = Input(new BreezeMmuContext(64))
    val block = Input(Bool())
    val redirect = Flipped(Valid(UInt(64.W))) // global exception/context restart
    val fenceI = Input(Bool())
    val sfence = Input(new SfenceIO)
    val resolve = Flipped(Decoupled(new PipelineResolution(p)))
    val resolutionReserve = if(backendConnected) Some(Input(Bool())) else None
    val resolutionAvailable = Output(Bool())
    val committedBranch = Flipped(Valid(Bool()))
    val instruction = Decoupled(new PipelineInstruction(p))
    val refillReq = new L1CacheMissReqIO(64)
    val refillRsp = new L1CacheMissRespIO(g.lineBytes * 8)
    val ptwMem = new PtwMemIO
    val dtlb = if(backendConnected) Some(new TlbPortIO(withPmpCandidate = true)) else None
    val mmuIdle = Output(Bool())
    val hpm = Output(new BreezeHpmEvents)
    val idle = Output(Bool())
    val debug = Output(new Bundle {
      val f0Fire = Bool(); val f0Pc = UInt(64.W); val f1Fire = Bool(); val f2Fire = Bool()
      val f1Redirect = Bool(); val f2Redirect = Bool(); val specHistory = UInt(p.historyBits.W)
      val queryHistory = UInt(p.historyBits.W); val rasCount = UInt(log2Ceil(p.rasDepth + 1).W)
      val resolutionUsed = UInt(3.W); val resolutionReserved = UInt(3.W); val trainingFire = Bool()
    })
  })
  val frontend = Module(new BreezeFrontendPipeline(g, p, mmuParams.itlb,
    backendConnected, predictorEnabled))
  // Same-direction parent/child ports connect without adding latency.
  for ((name, port) <- frontend.io.elements if name != "itlb" && name != "idle") {
    port <> io.elements(name)
  }
  val mmu = Module(new Sv39Mmu(mmuParams, withPmpCandidate = true,
    withFetchCandidates = true, pipelinedFetchKill = true))
  Sv39MmuContextLink(mmu, io.context)
  mmu.io.sfence := io.sfence
  frontend.io.itlb <> mmu.io.itlb
  if (backendConnected) { io.dtlb.get <> mmu.io.dtlb }
  else {
    mmu.io.dtlb.req.valid := false.B
    mmu.io.dtlb.req.bits := 0.U.asTypeOf(mmu.io.dtlb.req.bits)
    mmu.io.dtlb.kill := false.B
  }
  io.ptwMem <> mmu.io.ptwMem
  io.mmuIdle := mmu.io.idle
  io.idle := frontend.io.idle && mmu.io.idle
}
