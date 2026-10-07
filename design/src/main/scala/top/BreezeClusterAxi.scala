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
  val lastRetirePc = UInt(64.W)
  val lastRetireInst = UInt(32.W)
  val seenRetire = Bool()
  val noRetireCycles = UInt(32.W)
  val hang = Bool()
  val hangReasons = UInt(15.W)
  val retire = new TracePayload(64)
  val l1dEvents = new L1DEvents
  val l2Events = new L2Events(p)
}

/** Protocol-free SoC boundary. Geometry belongs exclusively to cfg.mem. */
class BreezeClusterAxi(cfg: BreezeClusterConfig, enableTandem: Boolean = false,
    debug: Boolean = false, hangThresholdCycles: Int = 10000000) extends Module {
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
    val monitor = Module(new BreezeHangMonitor(hangThresholdCycles, p.l2Slots))
    monitor.io.retire := cluster.io.retire(0).valid
    val memChannels = Seq(io.mem.ar, io.mem.r, io.mem.aw, io.mem.w, io.mem.b)
    val mmioChannels = Seq(io.mmio.ar, io.mmio.r, io.mmio.aw, io.mmio.w, io.mmio.b)
    (memChannels ++ mmioChannels).zipWithIndex.foreach { case (c, i) =>
      monitor.io.stalled(i) := c.valid && !c.ready
    }
    // L2MemEngine and the cluster MMIO arbiter each own one write at a time.
    // Start the write timer on the first accepted AW or W (either order).
    val memWrite = RegInit(false.B)
    val mmioWrite = RegInit(false.B)
    val memStart = !memWrite && (io.mem.aw.fire || io.mem.w.fire)
    val mmioStart = !mmioWrite && (io.mmio.aw.fire || io.mmio.w.fire)
    when(memStart) { memWrite := true.B }; when(io.mem.b.fire) { memWrite := false.B }
    when(mmioStart) { mmioWrite := true.B }; when(io.mmio.b.fire) { mmioWrite := false.B }
    monitor.io.request := VecInit(io.mem.ar.fire, memStart, io.mmio.ar.fire, mmioStart)
    monitor.io.complete := VecInit(io.mem.r.fire && io.mem.r.bits.last, io.mem.b.fire,
      io.mmio.r.fire, io.mmio.b.fire)
    val lastPc = RegInit(0.U(64.W)); val lastInst = RegInit(0.U(32.W))
    val seen = RegInit(false.B)
    when(cluster.io.retire(0).valid) {
      lastPc := cluster.io.retire(0).pc; lastInst := cluster.io.retire(0).inst; seen := true.B
    }
    d.lastRetirePc := lastPc; d.lastRetireInst := lastInst; d.seenRetire := seen
    d.noRetireCycles := monitor.io.noRetireCycles
    d.hang := monitor.io.hang; d.hangReasons := monitor.io.reasons
    d.retire := cluster.io.retire(0)
    d.l1dEvents := cluster.io.l1dEvents(0)
    d.l2Events := cluster.io.l2Events
  }
}
