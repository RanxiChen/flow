package flow.rvv.zve

import chisel3._
import chisel3.util._
import flow.rvv.{RvvIssue, RvvParams}

case class ZveParams(vrf: RvvParams = RvvParams(), window: Int = 16,
    fmaLanes: Int = 8, conversionLanes: Int = 16, fpBatches: Int = 8) {
  require(isPow2(window) && window >= 4 && window <= vrf.viqDepth)
  require(isPow2(fmaLanes) && fmaLanes <= vrf.dlen / 32)
  require(isPow2(conversionLanes) && conversionLanes <= vrf.dlen / 32)
  require(isPow2(fpBatches) && fpBatches >= 2)
  val units = 6
  val slotBits = log2Ceil(window)
  val ageBits = vrf.ageBits
  val fpTagBits = 16
}

object ZveUnit {
  val alu = 0; val integer = 1; val conversion = 2
  val fma = 3; val cross = 4; val memory = 5
}

/** Scalar register dependencies are resolved by the issuer before acceptance.
  * fpScalar is separately captured: rs1 is never interpreted as an FPR value. */
class ZveIssue(p: ZveParams) extends Bundle {
  val scalar = new RvvIssue(p.vrf)
  val fpScalar = UInt(64.W)
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
  val device = Bool()
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
