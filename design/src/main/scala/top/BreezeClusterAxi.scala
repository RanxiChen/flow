package flow.top

import chisel3._
import flow.bus._
import flow.coherence.CoherenceParams
import flow.config.BreezeClusterConfig
import flow.interface.TracePayload
import flow.l1d.L1DEvents
import flow.l2.L2Events
import flow.platform.BreezeMcuPlatform

class BreezeAxiDebug(p: CoherenceParams) extends Bundle {
  val retire = new TracePayload(64)
  val l1dEvents = new L1DEvents
  val l2Events = new L2Events(p)
}

/** Protocol-free SoC boundary. Geometry belongs exclusively to cfg.mem. */
class BreezeClusterAxi(cfg: BreezeClusterConfig, enableTandem: Boolean = false,
    debug: Boolean = false) extends Module {
  private val p = CoherenceParams(cfg.mem)
  private val debugEnabled = debug
  val io = IO(new Bundle {
    val resetAddr = Input(UInt(64.W))
    val msip = Input(Vec(p.nCores, Bool()))
    val mtip = Input(Vec(p.nCores, Bool()))
    val time = Input(UInt(64.W))
    val externalInterrupts = Input(Vec(p.nCores, UInt(BreezeMcuPlatform.ExternalInterruptWidth.W)))
    val supervisorExternalInterrupts = Input(Vec(p.nCores, Bool()))
    val mem = new Axi4MasterIO(Axi4Params(p.paddrBits, p.memDataBits, p.slotBits))
    val mmio = new Axi4LiteMasterIO(p.paddrBits, 64)
    val hartFatal = Output(Vec(p.nCores, Bool()))
    val hartEStop = Output(Vec(p.nCores, Bool()))
    val debug = if (debugEnabled) Some(Output(new BreezeAxiDebug(p))) else None
  })
  val cluster = Module(new BreezeCluster(cfg, enableTandem || debug))
  cluster.io.resetAddr := io.resetAddr
  cluster.io.msip := io.msip; cluster.io.mtip := io.mtip; cluster.io.time := io.time
  cluster.io.externalInterrupts := io.externalInterrupts
  cluster.io.supervisorExternalInterrupts := io.supervisorExternalInterrupts
  io.mem <> cluster.io.mem; io.mmio <> cluster.io.mmio
  cluster.io.dma.req.valid := false.B
  cluster.io.dma.req.bits := 0.U.asTypeOf(cluster.io.dma.req.bits)
  cluster.io.dma.rspDown.ready := true.B
  io.hartFatal := cluster.io.hartFatal; io.hartEStop := cluster.io.hartEStop
  io.debug.foreach { d =>
    d.retire := cluster.io.retire(0)
    d.l1dEvents := cluster.io.l1dEvents(0)
    d.l2Events := cluster.io.l2Events
  }
}
