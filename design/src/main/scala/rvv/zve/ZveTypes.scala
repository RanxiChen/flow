package flow.rvv.zve

import chisel3._
import chisel3.util._
import flow.rvv.{RvvIssue, RvvParams}

case class ZveParams(vrf: RvvParams = RvvParams(), window: Int = 16,
    fmaLanes: Int = 8, conversionLanes: Int = 16, fpBatches: Int = 8,
    /** Line requests the stream VLSU keeps outstanding in L2. */
    streamCredits: Int = 4) {
  require(isPow2(window) && window >= 4 && window <= vrf.viqDepth)
  require(isPow2(fmaLanes) && fmaLanes <= vrf.dlen / 32)
  require(isPow2(conversionLanes) && conversionLanes <= vrf.dlen / 32)
  require(isPow2(fpBatches) && fpBatches >= 2)
  require(isPow2(streamCredits) && streamCredits >= 2)
  val units = 8
  val slotBits = log2Ceil(window)
  val ageBits = vrf.ageBits
  val fpTagBits = 16
  val lineBytes = vrf.cacheLineBytes
  val pageBits = 12
}
object ZveParams {
  /** L2 vector-client IDs: stream credits, one element-engine ID, spare. */
  val l2Ids = 8
  /** SoC instance: ELEN=32, no misa.V, element AXI beat = one L2 line.
    * 2026-10-13 resize: one VRF row = one L2 line (DLEN = 8 * lineBytes),
    * 4 FMA / 4 conversion lanes. Decode is capped near 2 B/cycle by the two
    * L2 miss slots, so a 512-bit row bought area, not llama throughput. */
  def soc(paBits: Int, lineBytes: Int): ZveParams = ZveParams(vrf = RvvParams(
    vlen = 512, dlen = lineBytes * 8,
    paBits = paBits, cacheLineBytes = lineBytes, memoryBits = lineBytes * 8,
    elen = 32, fullV = false), fmaLanes = 4, conversionLanes = 4)
}

object ZveUnit {
  val alu = 0; val integer = 1; val conversion = 2
  val fma = 3; val cross = 4; val memory = 5
  /** Pre-translated unit-stride line VLSU (non-Serial). */
  val stream = 6
  /** Full-row widening integer multiply / multiply-accumulate (SEW 8, 16). */
  val mac = 7
}

/** Observation only. None of these signals feed admission, issue or commit. */
class ZvePerfObservation extends Bundle {
  val preStart = Bool(); val preBusy = Bool(); val preDone = Bool()
  val fastMemory = Bool(); val serialMemory = Bool()
  val streamLoadStart = Bool(); val streamLoadDone = Bool()
  val streamIdCount = UInt(3.W); val streamReqStall = Bool()
  // alu, mac, conversion, fma, stream
  val unitBusy = Vec(5, Bool())
  val soloWait = Bool(); val wbWait = Bool(); val creditWait = Bool()
}

/** Pre-commit translation result for a unit-stride access of at most two
  * pages. valid=1 means every byte is translated, PMP/PMA-allowed, cacheable
  * RAM; the access then cannot trap and runs after commit without Serial. */
class ZvePretranslation(p: ZveParams) extends Bundle {
  val valid = Bool()
  val page0 = UInt((p.vrf.paBits - p.pageBits).W)
  val page1 = UInt((p.vrf.paBits - p.pageBits).W)
}

/** One L2 line transaction of the stream VLSU. write=MaskWrite, else Read. */
class ZveLineRequest(p: ZveParams) extends Bundle {
  val id = UInt(log2Ceil(p.streamCredits).W)
  val write = Bool()
  val line = UInt((p.vrf.paBits - log2Ceil(p.lineBytes)).W)
  val mask = UInt(p.lineBytes.W)
  val data = UInt((p.lineBytes * 8).W)
}
class ZveLineResponse(p: ZveParams) extends Bundle {
  val id = UInt(log2Ceil(p.streamCredits).W)
  val data = UInt((p.lineBytes * 8).W)
  val error = Bool()
}
class ZveLineIO(p: ZveParams) extends Bundle {
  val request = Decoupled(new ZveLineRequest(p))
  val response = Flipped(Decoupled(new ZveLineResponse(p)))
}

/** Scalar register dependencies are resolved by the issuer before acceptance.
  * fpScalar is separately captured: rs1 is never interpreted as an FPR value. */
class ZveIssue(p: ZveParams) extends Bundle {
  val scalar = new RvvIssue(p.vrf)
  val fpScalar = UInt(64.W)
  val epoch = UInt(8.W)
  val memoryAge = UInt(32.W)
  val pre = new ZvePretranslation(p)
}

class ZveCommand(p: ZveParams) extends Bundle {
  val issue = new ZveIssue(p)
  val unit = UInt(3.W)
  val operation = UInt(9.W)
  val legal = Bool()
  val serial = Bool()
  val reads = UInt(32.W)
  val writes = UInt(32.W)
  val slot = UInt(p.slotBits.W)
  val age = UInt(p.ageBits.W)
  val sew = UInt(2.W)
  val source1Width = UInt(2.W); val source2Width = UInt(2.W); val destinationWidth = UInt(2.W)
  val source1Registers = UInt(4.W); val source2Registers = UInt(4.W); val destinationRegisters = UInt(4.W)
  val source1Bytes = UInt(p.vrf.lengthBits.W); val source2Bytes = UInt(p.vrf.lengthBits.W)
  val destinationBytes = UInt(p.vrf.lengthBits.W)
  val vectorSource1 = Bool(); val vectorSource2 = Bool()
  val maskResult = Bool(); val maskSources = Bool(); val reduction = Bool()
  val scalarResult = Bool(); val scalarFloating = Bool()
  val whole = Bool(); val noPredication = Bool()
}

class ZveVerdict(p: ZveParams) extends Bundle {
  val slot = UInt(p.slotBits.W)
  val age = UInt(p.ageBits.W)
  val kind = UInt(3.W) // Ok=0, Exc=2, Serial=3; final completion is separate.
  val cause = UInt(64.W)
  val tval = UInt(64.W)
}

class ZveCompletion(p: ZveParams) extends Bundle {
  val slot = UInt(p.slotBits.W)
  val age = UInt(p.ageBits.W)
  val flags = UInt(5.W)
  val saturated = Bool()
  val scalarValid = Bool()
  val scalarFloating = Bool()
  val scalarRd = UInt(5.W)
  val scalarData = UInt(64.W)
  val exception = Bool()
  val cause = UInt(64.W)
  val tval = UInt(64.W)
  val vstart = UInt(p.vrf.lengthBits.W)
  val changeVl = Bool()
  val newVl = UInt(p.vrf.lengthBits.W)
}

/** A SoC-owned service. Tag lives until the response handshake, including kill.
  * Permissions apply to the requested bytes, not only the first address. */
class ZveTranslationRequest(p: ZveParams) extends Bundle {
  val tag = UInt(p.fpTagBits.W)
  val va = UInt(64.W)
  val bytes = UInt(p.vrf.lengthBits.W)
  val store = Bool()
  val epoch = UInt(8.W)
}
class ZveTranslationResponse(p: ZveParams) extends Bundle {
  val tag = UInt(p.fpTagBits.W)
  val pa = UInt(64.W)
  val bytes = UInt(p.vrf.lengthBits.W)
  val epoch = UInt(8.W)
  val exception = Bool()
  val cause = UInt(64.W)
  val tval = UInt(64.W)
  val device = Bool()
}

/** Admission acquires a PA lease until release.fire. During a Serial command
  * the SoC additionally prevents younger scalar memory side effects. */
class ZveMemoryOrder extends Bundle {
  val tag=UInt(16.W); val age=UInt(32.W); val pa=UInt(64.W)
  val bytes=UInt(3.W); val store=Bool()
}

class ZveFpRequest extends Bundle {
  val tag = UInt(16.W)
  val a = UInt(64.W); val b = UInt(64.W); val c = UInt(64.W)
  val operation = UInt(4.W); val modifier = Bool()
  val rounding = UInt(3.W)
  val sourceFormat = UInt(3.W); val destinationFormat = UInt(3.W)
  val integerFormat = UInt(2.W)
}
class ZveFpResponse extends Bundle {
  val tag = UInt(16.W); val data = UInt(64.W); val flags = UInt(5.W)
}
class ZveFpService extends Bundle {
  val request = Decoupled(new ZveFpRequest)
  val response = Flipped(Decoupled(new ZveFpResponse))
}

class ZveFpBatch(lanes: Int, metadataBits: Int) extends Bundle {
  val metadata = UInt(metadataBits.W)
  val active = UInt(lanes.W)
  val a = Vec(lanes, UInt(64.W)); val b = Vec(lanes, UInt(64.W))
  val c = Vec(lanes, UInt(64.W))
  val operation = UInt(4.W); val modifier = Bool(); val rounding = UInt(3.W)
  val sourceFormat = UInt(3.W); val destinationFormat = UInt(3.W)
  val integerFormat = UInt(2.W)
}
class ZveFpBatchResult(lanes: Int, metadataBits: Int) extends Bundle {
  val metadata = UInt(metadataBits.W); val active = UInt(lanes.W)
  val data = Vec(lanes, UInt(64.W)); val flags = UInt(5.W)
}
