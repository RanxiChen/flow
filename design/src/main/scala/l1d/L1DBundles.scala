package flow.l1d

import chisel3._
import chisel3.util._
import flow.bus.Axi4LiteMasterIO
import flow.coherence._
import flow.interface.{L1DCoreIO, L1DCoreReq, L1DDestination}
import flow.mmu.sv39.{PtwMemIO, TlbPortIO}

// ---------------------------------------------------------------------------
// External interface. Backend side is the frozen flow.interface.L1DCoreIO.
// ---------------------------------------------------------------------------

/** l1d-spec-inputs section 12: occurrence pulses and stall levels. */
class L1DEvents extends Bundle {
  val load_access = Bool()
  val load_miss = Bool()
  val store_access = Bool()
  val store_miss = Bool()
  val upgrade = Bool()
  val ptw_access = Bool()
  val ptw_miss = Bool()
  val hit_under_miss = Bool()
  val mshr_busy_cycles = Bool()
  val mshr_full_stall = Bool()
  val same_line_stall = Bool()
  val s0_conflict_stall = Bool()
  val writeback_dirty = Bool()
  val writeback_clean = Bool()
  val probe_received = Bool()
  val probe_held_cycles = Bool()
  val lr_count = Bool()
  val sc_fail = Bool()
  val mmio_read = Bool()
  val mmio_write = Bool()
  val mmio_cycles = Bool()
}

class L1DIO(p: L1DParams, withPmpCandidate: Boolean = false) extends Bundle {
  val core = new L1DCoreIO
  /** L1D drives dTLB requests: TlbPortIO is TLB-side, so flip it. */
  val tlb = Flipped(new TlbPortIO(withPmpCandidate))
  /** PTW reads enter L1D: PtwMemIO is PTW-side, so flip it. */
  val ptw = Flipped(new PtwMemIO)
  val coh = new L1DCoherenceIO(p.coh)
  val mmio = new Axi4LiteMasterIO(p.paddrBits, 64)
  val events = Output(new L1DEvents)
}

// ---------------------------------------------------------------------------
// Arrays (§2)
// ---------------------------------------------------------------------------

class L1TagEntry(p: L1DParams) extends Bundle {
  val state = L1State()
  val tag = UInt(p.tagBits.W)
}

// ---------------------------------------------------------------------------
// Pipeline (§3)
// ---------------------------------------------------------------------------

/** S0 sources, highest priority first (§6.1). */
object L1Src extends ChiselEnum {
  val Init, Probe, Refill, WbRead, Replay, Ptw, Recheck, Cpu = Value
}

/** What a pipeline slot carries. CPU and recheck requests have a vaddr and
  * consult the dTLB; internal sources carry a paddr.
  */
class L1PipeReq(p: L1DParams) extends Bundle {
  val src = L1Src()
  val core = new L1DCoreReq
  val paddr = UInt(p.paddrBits.W)
  val hasPaddr = Bool()
  val physicalAddress = UInt(64.W) // preserve high bits until PMA/PMP decision
  val idx = UInt(p.idxW.W)
  val word = UInt(p.wordBits.W)
  /** Program-order age for recheck ordering (§6.1 item 5). */
  val age = UInt(2.W)
  /** Whole-line operation beat (refill/writeback-read/probe), 0..wordsPerLine-1. */
  val beat = UInt(p.wordBits.W)
  val lastBeat = Bool()
  val replayPtw = Bool()
  val replayError = Bool()
}

class L1S1(p: L1DParams) extends Bundle {
  val valid = Bool()
  val req = new L1PipeReq(p)
  val snapInvalid = Bool()
  val pmpEnd = new flow.mmu.BreezePmpAccessEnd
}

class L1S2(p: L1DParams) extends Bundle {
  val physicalAddress = UInt(64.W)
  val pmpAllowed = Bool()
  val pmaAllowed = Bool()
  val pmaDevice = Bool()
  val pmaAmoOk = Bool()
  val pmaRsrvOk = Bool()
  val highAddress = Bool()
  val valid = Bool()
  val req = new L1PipeReq(p)
  val paddr = UInt(p.paddrBits.W)
  val pageFault = Bool()
  val accessFault = Bool()
  val tagVec = Vec(p.ways, new L1TagEntry(p))
  val dataVec = Vec(p.ways, UInt(64.W))
  val snapInvalid = Bool()
  val translationMiss = Bool()
  val needsRecheck = Bool()
  // S1 comparisons, with forwarding from an allocation at the capture edge.
  // Existing S2 fields keep their original protocol/permission meanings.
  val sameMshrLine = Bool()
  val sameWbLine = Bool()
  val sameMshrSet = Bool()
}

/** pending-store (§3, PS). */
class L1PendingStore(p: L1DParams) extends Bundle {
  val valid = Bool()
  val idx = UInt(p.idxW.W)
  val word = UInt(p.wordBits.W)
  val way = UInt(p.wayBits.W)
  val mask = UInt(8.W)
  val data = UInt(64.W)
  val paddr = UInt(p.paddrBits.W)
  /** Line was E: PS write also writes tag E→M. */
  val setDirty = Bool()
}

/** S2 decision (§5). One of these per cycle for a valid S2 slot. */
object L1S2Outcome extends ChiselEnum {
  val None, Done, Mshr, Exc, Hold, Recheck, ToMmio, ToAmo, PtwResp, Internal = Value
}

// ---------------------------------------------------------------------------
// Miss path (§6)
// ---------------------------------------------------------------------------

object MshrState extends ChiselEnum {
  val Idle, WbRead, Send, Wait, Install, Replay, Late = Value
}
object WbSlotState extends ChiselEnum { val Idle, Read, Send, WaitAck = Value }
object MshrSrc extends ChiselEnum { val Cpu, Ptw = Value }

/** S2 → miss unit: allocate MSHR (and writeback slot when the victim is valid). */
class L1MshrAlloc(p: L1DParams) extends Bundle {
  val lineAddr = UInt(p.lineAddrBits.W)
  val isGetM = Bool()
  val upgrade = Bool()
  val way = UInt(p.wayBits.W)
  val src = MshrSrc()
  val req = new L1DCoreReq
  val word = UInt(p.wordBits.W)
  val isLr = Bool()
  val victimValid = Bool()
  val victimLineAddr = UInt(p.lineAddrBits.W)
  val victimDirty = Bool()
}

/** Miss unit status consumed by S0 arbitration, S2 decisions and probe. */
class L1MissStatus(p: L1DParams) extends Bundle {
  val mshrValid = Bool()
  val mshrState = MshrState()
  val mshrLineAddr = UInt(p.lineAddrBits.W)
  val mshrWay = UInt(p.wayBits.W)
  val wbValid = Bool()
  val wbLineAddr = UInt(p.lineAddrBits.W)
  val wbGotAck = Bool()
  val canAllocate = Bool()
  val wayLocked = Bool()
  val wbWay = UInt(p.wayBits.W)
}

/** Miss unit's S0 requests (refill install, writeback read, replay). */
class L1MissS0Req(p: L1DParams) extends Bundle {
  val install = Bool()
  val wbRead = Bool()
  val replay = Bool()
  val idx = UInt(p.idxW.W)
  val way = UInt(p.wayBits.W)
  val beat = UInt(p.wordBits.W)
  /** Install data for this beat, plus the final tag write. */
  val installData = UInt(64.W)
  val installTag = new L1TagEntry(p)
  val installIsAckE = Bool()
  /** Replay carries the original request with its paddr. */
  val replayReq = new L1PipeReq(p)
}

// ---------------------------------------------------------------------------
// Probe (§10)
// ---------------------------------------------------------------------------

class L1ProbeReg(p: L1DParams) extends Bundle {
  val valid = Bool()
  val op = SnpOp()
  val owner = Bool()
  val addr = UInt(p.lineAddrBits.W)
}

// ---------------------------------------------------------------------------
// MMIO (§9) / AMO (§8.3)
// ---------------------------------------------------------------------------

object MmioState extends ChiselEnum { val Idle, WaitOldest, Issue, Resp = Value }
object AmoState extends ChiselEnum { val Idle, Drain, Lookup, Miss, Wait, Rmw, Done = Value }

class L1MmioReq(p: L1DParams) extends Bundle {
  val paddr = UInt(p.paddrBits.W)
  val isWrite = Bool()
  val size = UInt(2.W)
  val signed = Bool()
  val wdata = UInt(64.W)
  val rd = new L1DDestination
  val isFlw = Bool()
}
