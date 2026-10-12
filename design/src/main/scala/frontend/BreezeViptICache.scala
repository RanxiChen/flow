package flow.frontend

import chisel3._
import chisel3.util._
import flow.config.BreezeMemGeometry
import flow.interface._
import flow.mmu.sv39._
import flow.platform.BreezeMcuPlatform

class PipelineFetchRequest(h: Int) extends Bundle { val pc = UInt(64.W); val history = UInt(h.W) }
class PipelineFetchWindow(p: BreezeFrontendPipelineParams) extends Bundle {
  val pc = UInt(64.W)
  val data = UInt(32.W)
  val mask = UInt(2.W)
  val pageFault = Vec(2, Bool())
  val accessFault = Vec(2, Bool())
  val prediction = new PipelinePrediction(p.historyBits)
}

/** Real VIPT F0/F1 cache. The same enable drives the virtual-index SRAM
  * reads, iTLB request and predictor query. There is no hit transaction FSM.
  */
class BreezeViptICache(g: BreezeMemGeometry, p: BreezeFrontendPipelineParams,
    tlbParams: Sv39TlbParams, quiesceReplayWhenBlocked: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new PipelineFetchRequest(p.historyBits)))
    val response = Decoupled(new PipelineFetchWindow(p))
    val tlb = Flipped(new TlbPortIO(true, tlbParams.ways + tlbParams.superpages + 1))
    val context = Input(new BreezeMmuContext(64))
    val cancel = Input(Bool()); val cancelYounger = Input(Bool())
    val fenceI = Input(Bool()); val block = Input(Bool())
    val stopF0 = Input(Bool()) // F1 prediction redirect only
    val discardDeferred = Input(Bool())
    val predictorQuery = Valid(new PipelineFetchRequest(p.historyBits))
    val prediction = Input(new PipelinePrediction(p.historyBits))
    val historyAfter = Input(UInt(p.historyBits.W))
    val f1 = Valid(new PipelineFetchWindow(p))
    val f1Prediction = Valid(new PipelineFetchWindow(p)) // preview qualified by older cancellation
    val f1PredictionPending = Output(Bool()) // registered owner, for payload selection only
    val refillReq = new L1CacheMissReqIO(64)
    val refillRsp = new L1CacheMissRespIO(g.lineBytes * 8)
    val idle = Output(Bool())
  })
  val offBits = log2Ceil(g.lineBytes)
  val setBits = log2Ceil(g.l1Sets)
  val tagBits = g.paddrBits - offBits - setBits
  def set(pc: UInt): UInt = if (setBits == 0) 0.U else pc(offBits + setBits - 1, offBits)
  def tag(pa: UInt): UInt = pa(g.paddrBits - 1, offBits + setBits)
  require(offBits + setBits <= 12)
  val tags = Seq.fill(g.l1iWays)(SyncReadMem(g.l1Sets, UInt(tagBits.W)))
  val data = Seq.fill(g.l1iWays)(SyncReadMem(g.l1Sets, UInt((g.lineBytes * 8).W)))
  val valid = RegInit(VecInit(Seq.fill(g.l1Sets)(0.U(g.l1iWays.W))))
  val rr = RegInit(VecInit(Seq.fill(g.l1Sets)(0.U(log2Ceil(g.l1iWays).max(1).W))))
  val qValid = RegInit(false.B)
  val q = Reg(new PipelineFetchRequest(p.historyBits))
  val qMeta = Reg(UInt(g.l1iWays.W))
  val retryValid = RegInit(false.B)
  val retry = Reg(new PipelineFetchRequest(p.historyBits))
  val deferredValid = RegInit(false.B)
  val deferred = Reg(new PipelineFetchRequest(p.historyBits))
  val waiting = RegInit(false.B)
  val killedRefill = RegInit(false.B)
  val refillPa = Reg(UInt(64.W))
  val refillWay = Reg(UInt(log2Ceil(g.l1iWays).max(1).W))
  val refillSet = Reg(UInt(setBits.max(1).W))
  val refillPrediction = Reg(new PipelinePrediction(p.historyBits))
  val refillFault = RegInit(false.B)
  val refillWrite = RegInit(false.B)
  val refillData = Reg(UInt((g.lineBytes * 8).W))
  val reserved = RegInit(0.U(2.W))
  val cancelOutput = io.cancel || io.fenceI
  val cancel = cancelOutput || io.cancelYounger
  // Two return slots. `reserved` proves a free slot for every F1 result, so
  // the wide payload CE is registered query ownership and tail only. F2 ready,
  // redirect and cancellation reach ownership (count/head/tail), never the CE.
  val slots = Reg(Vec(2, new PipelineFetchWindow(p)))
  val head = RegInit(false.B); val tail = RegInit(false.B)
  val count = RegInit(0.U(2.W))
  // F2 consumes its own response while canceling younger queries. Masking
  // this response with cancelYounger would create fire -> redirect -> !fire.
  io.response.valid := count =/= 0.U && !cancelOutput
  io.response.bits := Mux(head, slots(1), slots(0))
  val credit = reserved < 2.U || io.response.fire
  val replay = retryValid || deferredValid
  val selected = Mux(retryValid, retry, Mux(deferredValid, deferred, io.request.bits))
  val freshAllowed = !replay && credit && !io.block
  val replayAllowed = replay && !qValid && !refillFault &&
    (!quiesceReplayWhenBlocked.B || !io.block)
  // F2 cancellation clears ownership at the edge. A harmless array read may
  // occur on that edge; its result is invalid. No decode -> cancel -> F0 CE.
  val arrayAllowed = !waiting && !refillWrite && !cancelOutput && !io.stopF0
  io.tlb.req.valid := arrayAllowed && (replayAllowed || (freshAllowed && io.request.valid))
  io.tlb.req.bits.vaddr := selected.pc
  io.tlb.req.bits.cmd := MmuCmd.Fetch
  io.tlb.kill := cancel
  io.request.ready := arrayAllowed && freshAllowed && io.tlb.req.ready
  val query = io.tlb.req.fire
  io.predictorQuery.valid := query
  io.predictorQuery.bits := selected
  val rawTags = VecInit(tags.map(_.read(set(selected.pc), query)))
  val rawData = VecInit(data.map(_.read(set(selected.pc), query)))
  when(query) { q := selected; qMeta := valid(set(selected.pc)) }
  val pmp = Module(new BreezeFetchPmp(tlbParams.ways + tlbParams.superpages + 1))
  pmp.io.fire := query; pmp.io.vaddr := selected.pc; pmp.io.context := io.context
  // PMA below rejects every selected PA outside the configured physical
  // bus before a hit or refill can authorize execution. For all eligible
  // candidates these bits are already zero. Compare that same bounded PA
  // against the full CSR ranges, sharing the constant high-bit comparisons.
  pmp.io.paddrs := VecInit(io.tlb.fetchPaddrs.get.map(pa =>
    Cat(0.U((64 - g.paddrBits).W), pa(g.paddrBits - 1, 0))))
  pmp.io.select := io.tlb.fetchSelect.get
  val pa = io.tlb.candidatePaddr.get
  // Executable PMA regions are cache-line aligned. Both valid parcels lie
  // within this line; classification needs no late 64-bit end-address adder.
  val executable = BreezeMcuPlatform.PMARegions.filter(r => r.supportsExecute && r.cacheable)
  executable.foreach(r => require(r.origin % g.lineBytes == 0 && r.size % g.lineBytes == 0))
  val pmaAllowed = executable.map { r =>
    val mask = ((BigInt(1) << 64) - 1) ^ (r.size - 1)
    (pa & mask.U(64.W)) === r.origin.U(64.W)
  }.reduce(_ || _) && !pa(63, g.paddrBits).orR
  val hits = VecInit((0 until g.l1iWays).map(w => qMeta(w) && rawTags(w) === tag(pa)))
  val more = q.pc(offBits - 1, 0) =/= (g.lineBytes - 2).U
  val tlbFault = io.tlb.resp.bits.pageFault || io.tlb.resp.bits.accessFault
  val protectionFault = !pmp.io.allowed(0) || !pmaAllowed
  val complete = qValid && io.tlb.resp.valid &&
    (tlbFault || (io.tlb.resp.bits.hit && (protectionFault || hits.asUInt.orR)))
  val tlbMiss = qValid && io.tlb.resp.valid && io.tlb.resp.bits.miss
  val cacheMiss = qValid && io.tlb.resp.valid && io.tlb.resp.bits.hit &&
    !protectionFault && !hits.asUInt.orR
  val failedQuery = tlbMiss || cacheMiss
  val words = rawData.map(line => (line >> Cat(q.pc(offBits - 1, 0), 0.U(3.W)))(31, 0))
  val result = Wire(new PipelineFetchWindow(p))
  result.pc := q.pc
  result.data := Mux(tlbFault || protectionFault, 0.U, Mux1H(hits, words))
  result.mask := Mux(more, 3.U, 1.U)
  result.prediction := io.prediction
  for (slot <- 0 until 2) {
    result.pageFault(slot) := io.tlb.resp.bits.pageFault
    result.accessFault(slot) := io.tlb.resp.bits.accessFault ||
      (io.tlb.resp.bits.hit && (!pmp.io.allowed(slot) || !pmaAllowed))
  }
  val errorResult = Wire(new PipelineFetchWindow(p))
  errorResult := 0.U.asTypeOf(errorResult)
  errorResult.pc := retry.pc
  errorResult.mask := Mux(retry.pc(offBits - 1, 0) === (g.lineBytes - 2).U, 1.U, 3.U)
  errorResult.accessFault := VecInit(Seq.fill(2)(true.B))
  errorResult.prediction := refillPrediction
  io.f1PredictionPending := qValid || refillFault
  io.f1Prediction.valid := io.f1PredictionPending && !cancelOutput
  io.f1Prediction.bits := 0.U.asTypeOf(io.f1Prediction.bits)
  io.f1Prediction.bits.pc := Mux(refillFault, retry.pc, q.pc)
  io.f1Prediction.bits.mask := Mux(refillFault, errorResult.mask, result.mask)
  io.f1Prediction.bits.prediction := Mux(refillFault, refillPrediction, io.prediction)
  val produce = complete || refillFault
  val produced = Mux(refillFault, errorResult, result)
  // Every query already owns a return credit. Write its private candidate
  // even on a miss or cancellation; only qualified `enq` makes it visible.
  // In particular, complete contains iTLB resp.valid, which is kill-qualified:
  // it must not drive the wide payload CE through F2 -> kill -> resp.valid.
  val payloadWrite = qValid || refillFault
  when(payloadWrite && !tail) { slots(0) := produced }
  when(payloadWrite && tail) { slots(1) := produced }
  val enq = produce && !cancel
  io.f1.valid := enq
  io.f1.bits := produced
  when(enq) { tail := !tail }
  when(io.response.fire) { head := !head }
  count := count + enq.asUInt - io.response.fire.asUInt
  when(cancel) { head := false.B; tail := false.B; count := 0.U }
  qValid := query && !failedQuery
  when(query && deferredValid && !retryValid) { deferredValid := false.B }
  when(complete || refillFault) { retryValid := false.B; refillFault := false.B }
  when(complete && retryValid && deferredValid) {
    // The younger request was accepted before the missed group could supply
    // history. Rehash it with the head's eventual F1 preview on replay.
    deferred.history := io.historyAfter
  }
  when(failedQuery) {
    retryValid := true.B; retry := q
    when(io.request.fire) { deferredValid := true.B; deferred := io.request.bits }
  }
  io.refillReq.req := cacheMiss && !cancel
  io.refillReq.paddr := pa & (~(g.lineBytes - 1).U(64.W))
  when(io.refillReq.req) {
    assert(!waiting, "two ICache refill owners")
    waiting := true.B; killedRefill := false.B
    refillPa := pa; refillSet := set(q.pc)
    refillWay := Mux(!qMeta.andR, PriorityEncoder(~qMeta), rr(set(q.pc)))
    refillPrediction := io.prediction
  }
  // Complete ownership at response acceptance, then write the accepted line
  // on the following edge. Later branch cancellation preserves that already
  // accepted fill; fence.i still wins over its valid-bit installation.
  refillWrite := false.B
  val install = refillWrite
  for (w <- 0 until g.l1iWays) {
    when(install && refillWay === w.U) {
      tags(w).write(refillSet, tag(refillPa)); data(w).write(refillSet, refillData)
    }
  }
  when(io.refillRsp.vld) {
    assert(waiting, "unsolicited ICache refill")
    waiting := false.B; killedRefill := false.B
    // Private payload may be overwritten even for a rejected response.
    // Only refillWrite authorizes installation on the following edge.
    // Keep F2 redirect/cancel out of the 256-bit payload-register CE.
    refillData := io.refillRsp.data
    when(!killedRefill && !cancel && !io.refillRsp.error) {
      refillWrite := true.B
    }
    when(!killedRefill && !cancel && io.refillRsp.error) { refillFault := true.B }
  }
  when(install) {
    valid(refillSet) := valid(refillSet) | UIntToOH(refillWay, g.l1iWays)
    rr(refillSet) := (if (g.l1iWays == 1) 0.U else refillWay + 1.U)
  }
  when(io.request.fire =/= io.response.fire) {
    reserved := Mux(io.request.fire, reserved + 1.U, reserved - 1.U)
  }
  when(io.discardDeferred && complete && deferredValid) {
    deferredValid := false.B
    reserved := reserved - io.response.fire.asUInt - 1.U
  }
  when(cancel) {
    qValid := false.B; retryValid := false.B; deferredValid := false.B
    refillFault := false.B; reserved := 0.U
    when(waiting) { killedRefill := true.B }
  }
  when(io.fenceI) { valid := VecInit(Seq.fill(g.l1Sets)(0.U(g.l1iWays.W))) }
  io.idle := reserved === 0.U && !waiting && !refillWrite && !qValid && !retryValid && !deferredValid
  when(qValid && !cancel) {
    assert(io.tlb.resp.valid, "iTLB must return F1 for every live F0")
    when(io.tlb.resp.bits.hit) { assert(PopCount(hits) <= 1.U) }
  }
  when(!cancel) {
    assert(reserved <= 2.U)
    assert(count <= 2.U)
    assert(count <= reserved, "ICache visible windows exceed reserved owners")
    when(enq) { assert(count =/= 2.U || io.response.fire, "ICache result lacks reservation") }
    when(io.response.fire) { assert(reserved =/= 0.U) }
  }
  when(payloadWrite) {
    // This includes cancellation edges: a private write must not overwrite
    // the visible head, even if that head is also consumed on this edge.
    assert(count < 2.U && reserved > count, "ICache private write lacks an unused reserved slot")
    assert(!qValid || !refillFault, "ICache query and refill fault share a private write")
    when(count === 1.U) { assert(head =/= tail, "ICache private write aliases the visible head") }
  }
  when(quiesceReplayWhenBlocked.B && io.block) {
    assert(!io.tlb.req.fire, "SFENCE block must also stop deferred/retry translation admission")
  }
}
