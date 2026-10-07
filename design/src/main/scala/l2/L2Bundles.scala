package flow.l2

import chisel3._
import chisel3.util._
import flow.coherence._

/** L2 meta entry (coherence-l2-rtl-spec §2.1). */
class L2MetaEntry(p: CoherenceParams) extends Bundle {
  val valid = Bool()
  val dirty = Bool()
  val tag = UInt(p.tagBits.W)
  val state = DirState()
  val sharers = UInt(p.sharerBits.W)
}

/** What occupies a main-pipeline slot (§2.2). */
object L2Kind extends ChiselEnum { val NewReq, PutTask, SlotTask = Value }
object L2SlotTask extends ChiselEnum { val Evict, Install, Replay = Value }

class L2PipeEntry(p: CoherenceParams) extends Bundle {
  val kind = L2Kind()
  val port = UInt(p.portBits.W)      // NewReq: REQ port; PutTask: core
  val slot = UInt(p.slotBits.W)
  val task = L2SlotTask()
  val req = new CoherenceReq(p)
  val set = UInt(p.setBits.W)
  val tag = UInt(p.tagBits.W)
  val taskData = new L2TaskReq(p)
}

class L2S1(p: CoherenceParams) extends Bundle {
  val valid = Bool()
  val e = new L2PipeEntry(p)
}

class L2S2(p: CoherenceParams) extends Bundle {
  val valid = Bool()
  val e = new L2PipeEntry(p)
  val meta = Vec(p.l2Ways, new L2MetaEntry(p))
  val plru = UInt(p.plruBits.W)
  val hit = Bool()
  val hitWay = UInt(p.wayBits.W)
  val action = L2Action()
  val way = UInt(p.wayBits.W)        // way whose data was read in S1
}

/** §4.4 classification made in S1 and executed in S2. */
object L2Action extends ChiselEnum {
  val None, FastGetS, FastGetM, FastAckE, FastRead, FastMaskWrite, NeedProbe, NeedMiss, Put, Task, ProtocolError = Value
}

// ---------------------------------------------------------------------------
// Slots (§5)
// ---------------------------------------------------------------------------

object L2SlotState extends ChiselEnum {
  val Idle, Evict, EvictInPipe, ProbeWait, MemRead, Install, InstallInPipe, Replay, ReplayInPipe = Value
}
object L2SlotType extends ChiselEnum { val Miss, Probe = Value }

class L2SlotAlloc(p: CoherenceParams) extends Bundle {
  val port = UInt(p.portBits.W)
  val req = new CoherenceReq(p)
  val set = UInt(p.setBits.W)
  val tag = UInt(p.tagBits.W)
  val typ = L2SlotType()
  val way = UInt(p.wayBits.W)
  val victimValid = Bool()
  val victimTag = UInt(p.tagBits.W)
  val probeOp = SnpOp()
  val probeTargets = UInt(p.sharerBits.W)
  val probeOwner = Bool()
}

class L2SlotRegs(p: CoherenceParams) extends Bundle {
  val state = L2SlotState()
  val a = new L2SlotAlloc(p)
  val refill = UInt(p.lineBits.W)
  val refillErr = Bool()
}

/** One probe-engine job (§6). */
class L2ProbeJob(p: CoherenceParams) extends Bundle {
  val slot = UInt(p.slotBits.W)
  val addr = UInt(p.lineAddrBits.W)
  val op = SnpOp()
  val owner = Bool()
  val targets = UInt(p.sharerBits.W)
}

/** Per-core probe answer buffer (§2.2). */
class L2ProbeAnswer(p: CoherenceParams) extends Bundle {
  val valid = Bool()
  val op = RspUpOp()
  val hasData = Bool()
  val addr = UInt(p.lineAddrBits.W)
  val data = UInt(p.lineBits.W)
}

/** Per-core Put buffer (§2.2). */
class L2PutBuf(p: CoherenceParams) extends Bundle {
  val valid = Bool()
  val hasData = Bool()
  val addr = UInt(p.lineAddrBits.W)
  val data = UInt(p.lineBits.W)
}

/** Event pulses (coherence-l2-rtl-spec §11, observability-design §2.5).
  * Per-source vectors use REQ port order L1D(0..n-1), L1I(0..n-1), DMA.
  * Counters live in the observability step; this is only the event wiring.
  */
class L2Events(p: CoherenceParams) extends Bundle {
  /** Request accepted at S2, split by how it was served. */
  val req = Vec(p.nReqPorts, Bool())
  val hit = Vec(p.nReqPorts, Bool())
  val needProbe = Vec(p.nReqPorts, Bool())
  val miss = Vec(p.nReqPorts, Bool())
  /** Per-cycle: request waits because every slot was full / its set is protected. */
  val slotFullStall = Vec(p.nReqPorts, Bool())
  val setWait = Vec(p.nReqPorts, Bool())
  val put = Vec(p.nCores, Bool())
  val probeSent = Vec(p.nCores, Bool())
  /** Per-cycle: a probe job is waiting for answers. */
  val probeCycles = Bool()
  val memRead = Bool()
  /** Reads in flight this cycle; summed it gives mem_read_cycles. */
  val memReadsInFlight = UInt(log2Ceil(p.l2Slots + 1).W)
  val memTwoInflight = Bool()
  val memWrite = Bool()
}
