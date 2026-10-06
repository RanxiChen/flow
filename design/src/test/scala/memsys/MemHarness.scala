package flow.memsys

import chisel3._
import chisel3.util._
import flow.bus._
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.interface._
import flow.l1d._
import flow.l2.L2Home
import flow.mmu.sv39._

/** Identity-mapped dTLB. The response follows the request by one cycle, as
  * the L1D expects in S1. Busy, miss and page-fault are test inputs.
  */
class IdentityTlb extends Module {
  val io = IO(new Bundle {
    val port = new TlbPortIO
    val ready = Input(Bool())
    val miss = Input(Bool())
    val pageFault = Input(Bool())
    val requests = Output(UInt(32.W))
  })
  io.port.req.ready := io.ready
  val fire = io.port.req.fire && !io.port.kill
  val valid = RegNext(fire, false.B)
  val va = RegEnable(io.port.req.bits.vaddr, fire)
  val miss = RegEnable(io.miss, fire)
  val pageFault = RegEnable(io.pageFault, fire)
  io.port.resp.valid := valid
  io.port.resp.bits.hit := !miss
  io.port.resp.bits.miss := miss
  io.port.resp.bits.pageFault := pageFault && !miss
  io.port.resp.bits.accessFault := false.B
  io.port.resp.bits.paddr := va
  val count = RegInit(0.U(32.W))
  when(io.port.req.fire) { count := count + 1.U }
  io.requests := count
}

class TlbKnobIO extends Bundle {
  val tlbReady = Input(Bool())
  val tlbMiss = Input(Bool())
  val tlbPageFault = Input(Bool())
  val tlbRequests = Output(UInt(32.W))
}

/** L1D alone: the test drives the CPU port, PTW entry, coherence links and MMIO. */
class L1DTestHarness(g: BreezeMemGeometry) extends Module {
  val p = L1DParams(g)
  val io = IO(new Bundle {
    val core = new L1DCoreIO
    val ptw = Flipped(new PtwMemIO)
    val coh = new L1DCoherenceIO(p.coh)
    val mmio = new Axi4LiteMasterIO(p.paddrBits, 64)
    val tlb = new TlbKnobIO
    val events = Output(new L1DEvents)
  })
  val cache = Module(new L1DCache(g))
  val tlb = Module(new IdentityTlb)
  cache.io.core <> io.core
  cache.io.ptw <> io.ptw
  io.coh <> cache.io.coh
  io.mmio <> cache.io.mmio
  cache.io.tlb <> tlb.io.port
  tlb.io.ready := io.tlb.tlbReady
  tlb.io.miss := io.tlb.tlbMiss
  tlb.io.pageFault := io.tlb.tlbPageFault
  io.tlb.tlbRequests := tlb.io.requests
  io.events := cache.io.events
}

/** One core's L1D on the real L2. The test drives the CPU port, PTW entry,
  * L1I and DMA read clients, and models AXI memory and MMIO.
  */
class L1DL2Harness(g: BreezeMemGeometry) extends Module {
  require(g.nCores == 1, "single-core system harness")
  val p = L1DParams(g)
  val io = IO(new Bundle {
    val core = new L1DCoreIO
    val ptw = Flipped(new PtwMemIO)
    val mmio = new Axi4LiteMasterIO(p.paddrBits, 64)
    val mem = new Axi4MasterIO(Axi4Params(p.paddrBits, p.coh.memDataBits, p.coh.slotBits))
    val l1i = Flipped(new ReadClientIO(p.coh))
    val dma = Flipped(new ReadClientIO(p.coh))
    val tlb = new TlbKnobIO
  })
  val l1d = Module(new L1DCache(g))
  val l2 = Module(new L2Home(g))
  val tlb = Module(new IdentityTlb)
  l1d.io.core <> io.core
  l1d.io.ptw <> io.ptw
  io.mmio <> l1d.io.mmio
  l1d.io.tlb <> tlb.io.port
  tlb.io.ready := io.tlb.tlbReady
  tlb.io.miss := io.tlb.tlbMiss
  tlb.io.pageFault := io.tlb.tlbPageFault
  io.tlb.tlbRequests := tlb.io.requests
  l2.io.l1d(0) <> l1d.io.coh
  l2.io.l1i(0) <> io.l1i
  l2.io.dma <> io.dma
  io.mem <> l2.io.mem
}
