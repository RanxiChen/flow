package flow.rvv.zve

import chisel3._
import chisel3.util._
import flow.backend.{CoprocessorDecision, FpResult}
import flow.coherence._
import flow.interface.{BreezeMmuAccess, BreezeMmuContext}
import flow.mmu.BreezePmpChecker
import flow.mmu.sv39.{MmuCmd, TlbPortIO}
import flow.platform.{PMAAccessType, PMAChecker}

/** Unit-stride accesses that the issuer may translate before commit: plain
  * (nf=0), whole-register and mask, vstart=0, EEW<=32, non-empty. All other
  * memory forms remain Serial and use the element engine. */
object ZveFastMemory {
  def bytes(i: ZveIssue, p: ZveParams): UInt = {
    val x = i.scalar.instruction; val f3 = x(14, 12)
    val eew = Mux(f3 === 0.U, 0.U, f3 - 4.U)(1, 0)
    val whole = x(24, 20) === 8.U; val maskMemory = x(24, 20) === 11.U
    Mux(whole, (x(31, 29) +& 1.U) * p.vrf.regBytes.U,
      Mux(maskMemory, (i.scalar.vl +& 7.U) >> 3, i.scalar.vl << eew))
  }
  def eligible(i: ZveIssue, p: ZveParams): Bool = {
    val x = i.scalar.instruction; val f3 = x(14, 12); val nf = x(31, 29); val lumop = x(24, 20)
    val memory = x(6, 0) === 0x07.U || x(6, 0) === 0x27.U
    val plain = lumop === 0.U && nf === 0.U
    val whole = lumop === 8.U && (nf === 0.U || nf === 1.U || nf === 3.U || nf === 7.U) && f3 =/= 7.U
    val maskMemory = lumop === 11.U && nf === 0.U && f3 === 0.U
    memory && x(27, 26) === 0.U && f3 =/= 7.U && (plain || whole || maskMemory) &&
      i.scalar.vstart === 0.U && bytes(i, p) =/= 0.U && bytes(i, p) <= p.vrf.maxBytes.U
  }
}

class ZvePretranslateResult(p: ZveParams) extends Bundle {
  val pre = new ZvePretranslation(p)
}

/** The only vector client of the shared data TLB. Two request kinds:
  *  - pretranslation of a fast unit-stride access (<=2 pages, every 32 B line
  *    PMP/PMA-checked; any miss-retry is internal, any fault/device result
  *    simply declines the fast path so the Serial element engine reports it);
  *  - one element-engine chunk (<=4 B, naturally aligned) with an exact
  *    fault/device answer.
  * A side engine behind registered requests; the context is stable because
  * SATP/SFENCE/PMP writes are Serial and wait for vector drain. */
class ZveTranslator(p: ZveParams) extends Module {
  val io = IO(new Bundle {
    val pre = Flipped(Decoupled(new ZveIssue(p)))
    val preDone = Valid(new ZvePretranslation(p))
    val chunk = Flipped(Decoupled(new ZveTranslationRequest(p)))
    val chunkDone = Decoupled(new ZveTranslationResponse(p))
    val tlb = Flipped(new TlbPortIO(withPmpCandidate = true))
    val context = Input(new BreezeMmuContext(64))
    val idle = Output(Bool())
    val perfPreBusy = Output(Bool())
  })
  val sIdle :: sSend :: sWait :: sCheck :: sRespond :: Nil = Enum(5)
  val state = RegInit(sIdle)
  val isChunk = Reg(Bool()); val store = Reg(Bool())
  val va = Reg(UInt(64.W)); val total = Reg(UInt(p.vrf.lengthBits.W)); val chunkLog = Reg(UInt(2.W))
  val page = RegInit(0.U(1.W)); val pages = Reg(Vec(2, UInt((p.vrf.paBits - p.pageBits).W)))
  val line = Reg(UInt(6.W)); val lastLine = Reg(UInt(6.W))
  val fast = Reg(Bool()); val response = Reg(new ZveTranslationResponse(p))
  val context = Reg(new BreezeMmuContext(64))
  io.idle := state === sIdle
  io.perfPreBusy := state =/= sIdle && !isChunk
  io.chunk.ready := state === sIdle
  io.pre.ready := state === sIdle && !io.chunk.valid
  when(io.chunk.fire) {
    val c = io.chunk.bits
    isChunk := true.B; store := c.store; va := c.va; context := io.context
    chunkLog := Mux(c.bytes === 4.U, 2.U, Mux(c.bytes === 2.U, 1.U, 0.U)); page := 0.U
    response := 0.U.asTypeOf(response); response.tag := c.tag; response.epoch := c.epoch
    response.bytes := c.bytes; state := sSend
    assert(c.bytes === 1.U || c.bytes === 2.U || c.bytes === 4.U, "element chunk is 1, 2 or 4 bytes")
  }
  when(io.pre.fire) {
    val i = io.pre.bits; val n = ZveFastMemory.bytes(i, p)
    isChunk := false.B; store := i.scalar.instruction(6, 0) === 0x27.U
    va := i.scalar.rs1; total := n; context := io.context; page := 0.U; fast := true.B
    line := 0.U; lastLine := (((i.scalar.rs1 + n - 1.U) >> 5) - (i.scalar.rs1 >> 5))(5, 0)
    state := sSend
  }
  val lastVa = va + total - 1.U
  val crosses = !isChunk && (lastVa >> p.pageBits) =/= (va >> p.pageBits)
  val lookup = Mux(page === 1.U, lastVa, va)
  io.tlb.kill := false.B
  io.tlb.req.valid := state === sSend
  io.tlb.req.bits.vaddr := lookup
  io.tlb.req.bits.cmd := Mux(store, MmuCmd.Store, MmuCmd.Load)
  when(io.tlb.req.fire) { state := sWait }
  val faultCause = Mux(io.tlb.resp.bits.pageFault, Mux(store, 15.U, 13.U), Mux(store, 7.U, 5.U))
  when(state === sWait && io.tlb.resp.valid) {
    when(io.tlb.resp.bits.miss) { state := sSend }
      .elsewhen(io.tlb.resp.bits.pageFault || io.tlb.resp.bits.accessFault) {
        fast := false.B
        response.exception := true.B; response.cause := faultCause; response.tval := va
        state := Mux(isChunk, sRespond, sCheck)
      }.otherwise {
        pages(page) := io.tlb.resp.bits.paddr(p.vrf.paBits - 1, p.pageBits)
        response.pa := io.tlb.resp.bits.paddr
        when(!isChunk && crosses && page === 0.U) { page := 1.U; state := sSend }
          .otherwise { state := sCheck }
      }
  }
  // PMP/PMA: a chunk checks its own bytes; a fast access checks whole lines
  // (conservative: a partially denied line only declines the fast path).
  val lineVa = ((va >> 5) + line) << 5
  val linePage = Mux(crosses && (lineVa >> p.pageBits) =/= (va >> p.pageBits), pages(1), pages(0))
  val checkPa = Mux(isChunk, response.pa, Cat(linePage, lineVa(p.pageBits - 1, 5), 0.U(5.W)))
  val pmp = Module(new BreezePmpChecker(64, decoded = true))
  pmp.io.addr := checkPa; pmp.io.sizeLog2 := Mux(isChunk, chunkLog, 5.U)
  pmp.io.context := context
  pmp.io.privilege := Mux(context.mprv, context.mpp, context.privilege)
  pmp.io.access := Mux(store, BreezeMmuAccess.Store, BreezeMmuAccess.Load)
  val pma = Module(new PMAChecker)
  pma.io.query.addr := checkPa; pma.io.query.sizeLog2 := Mux(isChunk, chunkLog, 5.U)
  pma.io.query.accessType := Mux(store, PMAAccessType.Store, PMAAccessType.Load)
  val allowed = pmp.io.allowed && pma.io.result.allowed
  when(state === sCheck) {
    when(isChunk) {
      when(!response.exception) {
        response.exception := !allowed; response.cause := Mux(store, 7.U, 5.U); response.tval := va
        response.device := allowed && (pma.io.result.device || !pma.io.result.cacheable)
      }
      state := sRespond
    }.otherwise {
      val ok = fast && allowed && pma.io.result.cacheable && !pma.io.result.device
      fast := ok
      when(!ok || line === lastLine) { state := sRespond }.otherwise { line := line + 1.U }
    }
  }
  io.chunkDone.valid := state === sRespond && isChunk
  io.chunkDone.bits := response
  io.preDone.valid := state === sRespond && !isChunk
  io.preDone.bits.valid := fast
  io.preDone.bits.page0 := pages(0); io.preDone.bits.page1 := Mux(crosses, pages(1), pages(0))
  when(io.chunkDone.fire || io.preDone.valid) { state := sIdle }
}

/** One coherent L2 port for both vector memory paths: stream line IDs
  * 0..streamCredits-1, element engine ID streamCredits (single AXI
  * transaction, beat = one 32 B line). Read/MaskWrite probe the scalar L1D,
  * so vector loads see dirty scalar data and vector stores invalidate it. */
class ZveL2Client(p: ZveParams, cp: CoherenceParams) extends Module {
  val io = IO(new Bundle {
    val stream = Flipped(new ZveLineIO(p))
    val axi = Flipped(new flow.rvv.RvvAxi(p.vrf))
    val l2 = new ReadClientIO(cp)
    val idle = Output(Bool())
  })
  require(p.vrf.memoryBits == cp.lineBits && cp.vectorIds > p.streamCredits)
  val elementId = p.streamCredits
  val elementBusy = RegInit(false.B); val elementWrite = Reg(Bool())
  val elementRead = io.axi.ar.valid && !elementBusy
  val elementStore = io.axi.aw.valid && io.axi.w.valid && !elementBusy
  val element = elementRead || elementStore
  val req = io.l2.req
  req.valid := element || io.stream.request.valid
  req.bits := 0.U.asTypeOf(req.bits)
  when(element) {
    req.bits.op := Mux(elementRead, ReqOp.Read, ReqOp.MaskWrite)
    req.bits.addr := Mux(elementRead, io.axi.ar.bits.addr, io.axi.aw.bits.addr) >> cp.offBits
    req.bits.id := elementId.U
    req.bits.mask := io.axi.w.bits.strb; req.bits.data := io.axi.w.bits.data
  }.otherwise {
    req.bits.op := Mux(io.stream.request.bits.write, ReqOp.MaskWrite, ReqOp.Read)
    req.bits.addr := io.stream.request.bits.line
    req.bits.id := io.stream.request.bits.id
    req.bits.mask := io.stream.request.bits.mask; req.bits.data := io.stream.request.bits.data
  }
  io.axi.ar.ready := req.ready && elementRead
  io.axi.aw.ready := req.ready && !elementRead && elementStore
  io.axi.w.ready := req.ready && !elementRead && elementStore
  io.stream.request.ready := req.ready && !element
  when(req.fire && element) { elementBusy := true.B; elementWrite := !elementRead }

  val rsp = io.l2.rspDown
  val toElement = rsp.bits.id === elementId.U
  io.axi.r.valid := rsp.valid && toElement && !elementWrite
  io.axi.r.bits.data := rsp.bits.data; io.axi.r.bits.id := 0.U
  io.axi.r.bits.last := true.B; io.axi.r.bits.resp := Mux(rsp.bits.error, 2.U, 0.U)
  io.axi.b.valid := rsp.valid && toElement && elementWrite
  io.axi.b.bits.id := 0.U; io.axi.b.bits.resp := Mux(rsp.bits.error, 2.U, 0.U)
  io.stream.response.valid := rsp.valid && !toElement
  io.stream.response.bits.id := rsp.bits.id; io.stream.response.bits.data := rsp.bits.data
  io.stream.response.bits.error := rsp.bits.error
  rsp.ready := Mux(toElement, Mux(elementWrite, io.axi.b.ready, io.axi.r.ready), io.stream.response.ready)
  when(rsp.fire && toElement) { elementBusy := false.B }
  io.idle := !elementBusy
}

/** Backend ports of the vector unit that the SoC top connects: the shared
  * data-TLB port, the stream line client and the element AXI (both to the
  * L2 vector client through ZveL2Client), and translator idleness for SFENCE. */
class ZveServices(p: ZveParams) extends Bundle {
  val tlb = Flipped(new TlbPortIO(withPmpCandidate = true))
  val stream = new ZveLineIO(p)
  val axi = new flow.rvv.RvvAxi(p.vrf)
  val mmuIdle = Output(Bool())
}

/** Backend-facing mount of the decoupled Zve32f unit (zve32f-single §3).
  *
  * EX issues each vector instruction with its scalar operands and vl/vtype
  * snapshot. Fast unit-stride memory is translated here before its verdict,
  * so WB sees Ok/Exc/Serial in program order:
  *  - Ok: WB commits; the window executes it later, decoupled from scalar.
  *  - Exc: illegal encoding/configuration; WB traps.
  *  - Serial: WB holds (solo); serialGo starts the precise element engine and
  *    the decision is published only after its completion (fault/vstart/FOF).
  * Completions carry fflags/vxsat and the rare scalar results (vmv.x.s,
  * vfmv.f.s, vcpop, vfirst) back to the scalar write-back. */
class ZveMount(p: ZveParams) extends Module {
  val io = IO(new Bundle {
    val reserve = Input(Bool()); val available = Output(Bool())
    /** A reserved vector instruction is still in EX (not yet issued). */
    val exPending = Input(Bool())
    val issue = Flipped(Valid(new ZveIssue(p)))
    val consume = Input(Bool()); val killD = Input(Bool())
    /** WB holds a solo vector instruction waiting for its decision. */
    val serialHold = Input(Bool())
    val decision = Valid(new CoprocessorDecision)
    val decisionVstart = Output(UInt(p.vrf.lengthBits.W))
    val newVl = Valid(UInt(p.vrf.lengthBits.W))
    val result = Decoupled(new FpResult)
    val flags = Valid(UInt(5.W)); val vxsat = Output(Bool())
    val drained = Output(Bool())
    val storesPending = Output(Bool()); val memoryPending = Output(Bool())
    val fatal = Output(Bool())
    val context = Input(new BreezeMmuContext(64))
    val tlb = Flipped(new TlbPortIO(withPmpCandidate = true))
    val stream = new ZveLineIO(p)
    val axi = new flow.rvv.RvvAxi(p.vrf)
    val mmuIdle = Output(Bool())
    val perf = Output(new ZvePerfObservation)
  })
  val unit = Module(new ZveCoprocessor(p))
  val translator = Module(new ZveTranslator(p))
  // FP32-only (KIND=3): slow lane and cross reductions/compares never send FP64.
  val slowFp = Module(new ZveFpnewService(3))
  slowFp.io.service <> unit.io.sharedFp
  io.tlb <> translator.io.tlb; translator.io.context := io.context
  io.stream <> unit.io.stream; io.axi <> unit.io.axi
  unit.io.translation <> translator.io.chunk; translator.io.chunkDone <> unit.io.translated
  // The scalar side already ordered memory before a Serial instruction (solo
  // + cpuDrained at ID), so element-engine leases are granted immediately and
  // coherent MaskWrite replaces the old explicit invalidation handshake.
  unit.io.order.ready := true.B; unit.io.release.ready := true.B
  unit.io.invalidate.ready := true.B; unit.io.scalarWritesVisible := true.B
  io.mmuIdle := translator.io.idle
  io.perf := 0.U.asTypeOf(new ZvePerfObservation)
  io.perf.preStart := translator.io.pre.fire
  io.perf.preBusy := translator.io.perfPreBusy
  io.perf.preDone := translator.io.preDone.valid
  io.perf.streamLoadStart := unit.io.perfStreamLoadStart
  io.perf.streamLoadDone := unit.io.perfStreamLoadDone
  io.perf.streamIdCount := unit.io.perfStreamIdCount
  io.perf.streamReqStall := unit.io.stream.request.valid && !unit.io.stream.request.ready
  io.perf.unitBusy := unit.io.perfBusy

  // ---- Ingress and pretranslation stage (program order into the window).
  val ingress = withReset(reset.asBool || io.killD) { Module(new Queue(new ZveIssue(p), p.window)) }
  ingress.io.enq.valid := io.issue.valid && !io.killD
  ingress.io.enq.bits := io.issue.bits
  when(io.issue.valid && !io.killD) { assert(ingress.io.enq.ready, "reserved vector issue rejected") }
  val head = ingress.io.deq.bits
  val wantsFast = ZveFastMemory.eligible(head, p)
  val staged = RegInit(false.B); val stagedIssue = Reg(new ZveIssue(p))
  val stagedDone = RegInit(false.B)
  translator.io.pre.valid := ingress.io.deq.valid && wantsFast && !staged && !io.killD
  translator.io.pre.bits := head
  when(translator.io.pre.fire) { staged := true.B; stagedDone := false.B; stagedIssue := head }
  when(translator.io.preDone.valid) { stagedDone := true.B; stagedIssue.pre := translator.io.preDone.bits }
  // A killed stage still finishes its translation; its result is dropped.
  val stagedKilled = RegInit(false.B)
  when(io.killD && staged) { stagedKilled := true.B }
  val windowIssue = unit.io.issue
  val issueOpcode = windowIssue.bits.scalar.instruction(6,0)
  val issueMemory = issueOpcode === 7.U || issueOpcode === 0x27.U
  io.perf.fastMemory := windowIssue.fire && issueMemory && windowIssue.bits.pre.valid
  io.perf.serialMemory := windowIssue.fire && issueMemory && !windowIssue.bits.pre.valid
  windowIssue.valid := !io.killD && Mux(staged, stagedDone && !stagedKilled, ingress.io.deq.valid && !wantsFast)
  windowIssue.bits := Mux(staged, stagedIssue, head)
  when(!staged) { windowIssue.bits.pre := 0.U.asTypeOf(windowIssue.bits.pre) }
  ingress.io.deq.ready := !io.killD && (translator.io.pre.fire || (!staged && !wantsFast && windowIssue.ready))
  when(staged && stagedDone && (windowIssue.fire || stagedKilled)) { staged := false.B; stagedKilled := false.B }

  // ---- Credits: window + ingress + stage + EX never exceed window capacity.
  val inFlight = unit.io.occupancy +& ingress.io.count +& staged.asUInt +& io.exPending.asUInt +& io.reserve.asUInt
  io.available := RegNext(inFlight < (p.window - 1).U, false.B)
  when(io.reserve) { assert(io.available, "vector reservation without credit") }

  // ---- Verdicts -> WB decisions, in program order. R07: no flow-through.
  // The window's oldest/judgement logic must not reach WB kill and the
  // frontend redirect in the same cycle (synthesis WNS -0.948 ns, 29 levels).
  // The verdict of an instruction judged at PERM is still visible at WB.
  val verdicts = withReset(reset.asBool || io.killD) {
    Module(new Queue(new ZveVerdict(p), p.window, pipe = false, flow = false)) }
  verdicts.io.enq <> unit.io.verdict
  val v = verdicts.io.deq.bits
  val serialStarted = RegInit(false.B); val serialDone = RegInit(false.B)
  val serialResult = Reg(new ZveCompletion(p))
  val commitPending = RegInit(false.B)
  val isSerial = v.kind === 3.U
  unit.io.serialGo := verdicts.io.deq.valid && isSerial &&
    io.serialHold && !serialStarted &&
    !commitPending && !io.killD
  when(unit.io.serialGo) { serialStarted := true.B }
  io.decision.valid := verdicts.io.deq.valid && !io.killD && (!isSerial || serialDone)
  io.decision.bits.exception := Mux(isSerial, serialResult.exception, v.kind === 2.U)
  io.decision.bits.cause := Mux(isSerial, serialResult.cause, v.cause)
  io.decision.bits.tval := Mux(isSerial, serialResult.tval, v.tval)
  io.decision.bits.vstartMarker := isSerial && serialResult.exception
  io.decisionVstart := serialResult.vstart
  io.newVl.valid := isSerial && serialDone && serialResult.changeVl
  io.newVl.bits := serialResult.newVl
  verdicts.io.deq.ready := io.consume
  when(io.consume) {
    assert(io.decision.valid && !io.decision.bits.exception, "WB consumed a missing/trapping vector decision")
    when(isSerial) { serialStarted := false.B; serialDone := false.B }
  }
  // Ok commits after WB; Serial was already started by serialGo.
  commitPending := io.consume && !isSerial
  unit.io.commit := commitPending && !io.killD
  unit.io.kill := io.killD
  when(io.killD) { serialStarted := false.B; serialDone := false.B; commitPending := false.B }

  // ---- Completions: status, scalar results and the Serial decision.
  val results = Module(new Queue(new FpResult, p.window))
  val c = unit.io.completion
  c.ready := !c.bits.scalarValid || results.io.enq.ready
  results.io.enq.valid := c.valid && c.bits.scalarValid
  results.io.enq.bits.rd.idx := c.bits.scalarRd; results.io.enq.bits.rd.isFp := c.bits.scalarFloating
  results.io.enq.bits.data := c.bits.scalarData; results.io.enq.bits.flags := 0.U
  io.result <> results.io.deq
  io.flags.valid := c.fire && c.bits.flags =/= 0.U; io.flags.bits := c.bits.flags
  io.vxsat := c.fire && c.bits.saturated
  when(c.fire && serialStarted && verdicts.io.deq.valid && c.bits.slot === v.slot && c.bits.age === v.age) {
    serialDone := true.B; serialResult := c.bits
  }

  // ---- Ordering summaries for scalar memory issue (registered).
  val stagedStore = staged && stagedIssue.scalar.instruction(6, 0) === 0x27.U
  io.storesPending := RegNext(unit.io.storeLive || ingress.io.deq.valid || stagedStore, true.B)
  io.memoryPending := RegNext(unit.io.memoryLive || ingress.io.deq.valid || staged, true.B)
  val fatal = RegInit(false.B)
  when(unit.io.fatal) { fatal := true.B }
  io.fatal := fatal
  io.drained := !ingress.io.deq.valid && !staged && !verdicts.io.deq.valid && unit.io.drained &&
    !results.io.deq.valid && slowFp.io.drained && translator.io.idle
}

/** Shares the L1D's data-TLB port with the vector translator. The vector side
  * only translates while the scalar pipeline is quiet (solo vector memory or
  * a Serial element access), so a registered lock suffices: it is taken only
  * in a cycle without a scalar lookup, blocks scalar lookups while held, and
  * never feeds the scalar canAccept/valid cone combinationally. Responses
  * keep the TLB's one-cycle contract and are routed by the registered owner. */
class ZveTlbShare extends Module {
  val io = IO(new Bundle {
    val scalar = new TlbPortIO(withPmpCandidate = true)
    val vector = new TlbPortIO(withPmpCandidate = true)
    val tlb = Flipped(new TlbPortIO(withPmpCandidate = true))
  })
  val locked = RegInit(false.B)
  val vectorLast = RegInit(false.B)
  io.tlb.req.valid := Mux(locked, io.vector.req.valid, io.scalar.req.valid)
  io.tlb.req.bits := Mux(locked, io.vector.req.bits, io.scalar.req.bits)
  io.tlb.kill := Mux(vectorLast, false.B, io.scalar.kill)
  io.scalar.canAccept := io.tlb.canAccept && !locked
  io.vector.canAccept := io.tlb.canAccept && locked
  io.scalar.req.ready := io.tlb.req.ready && !locked
  io.vector.req.ready := io.tlb.req.ready && locked
  vectorLast := locked && io.tlb.req.fire
  for ((port, vector) <- Seq(io.scalar -> false, io.vector -> true)) {
    port.resp.valid := io.tlb.resp.valid && vectorLast === vector.B
    port.resp.bits := io.tlb.resp.bits
    port.candidatePaddr.get := io.tlb.candidatePaddr.get
  }
  val scalarFire = !locked && io.tlb.req.fire
  when(!locked && io.vector.req.valid && !scalarFire) { locked := true.B }
  when(locked && !io.vector.req.valid && !vectorLast) { locked := false.B }
}
