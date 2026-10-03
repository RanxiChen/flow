package flow.cache

import chisel3._
import chisel3.util._
import flow.config.DefaultDCacheConfig
import flow.interface.{BreezeAmoFunc, BreezeHpmEvents, BreezeMemOp}
import flow.mem.flowSRAM
import flow.platform.{PMAAccessType, PMAChecker}

/** CPU request accepted by [[BreezePipelinedDCache]].
  *
  * Unlike the historical pulse interface, this is carried by Decoupled and
  * therefore remains stable until ready.  The id is returned unchanged with
  * the response; the cache itself preserves request order in this first
  * implementation.
  */
class PipelinedDCacheReq(val vlen: Int, val idWidth: Int) extends Bundle {
  val id = UInt(idWidth.W)
  val addr = UInt(vlen.W)
  val sizeLog2 = UInt(3.W)
  val wdata = UInt(64.W)
  val wmask = UInt(8.W)
  val memOp = BreezeMemOp()
  val amoFunc = BreezeAmoFunc()
  val aq = Bool()
  val rl = Bool()
}

class PipelinedDCacheResp(val idWidth: Int) extends Bundle {
  val id = UInt(idWidth.W)
  val data = UInt(64.W)
  val isWriteAck = Bool()
  val error = Bool()
  val pageFault = Bool()
  val faultAddr = UInt(64.W)
}

object BreezePipelinedDCacheState extends ChiselEnum {
  val Run, ReplayRead,
      StoreWrite, ScWrite, ScInstallE, AmoPrepare, AmoExecute, AmoWrite,
      EvictReq, EvictWait, RefillReq, RefillWait, RefillInstall,
      UpgradeReq, UpgradeWait,
      ProbeRead, ProbeCompare, ProbeRespond,
      Complete, Fatal = Value
}

/** Experimental pipelined coherent L1D.
  *
  * The common path is a synchronous-array pipeline.  Cached Load hits in S,
  * E or M can be accepted and completed at one request per cycle when the
  * response queue is not backpressured.  Stores, AMOs, misses and coherence
  * upgrades leave that fast path and run as one ordered slow transaction.
  * There is deliberately no hit-under-miss in this version.
  *
  * AMO serialization is a global cache-local lock represented by the
  * AmoPrepare/AmoExecute/AmoWrite states.  A probe may be captured during the
  * lock, but is not serviced until the modified line has been written.  An AMO
  * that only has S permission does not acquire the lock while waiting for M:
  * UpgradeReq/UpgradeWait continue to service probes, avoiding a Home/L1
  * circular wait.
  *
  * LR hits share the read pipeline and pass through a one-entry commit stage
  * that installs the reservation when the response is enqueued.  SC uses a
  * conditional local write state or the existing GetM path.  Device/uncached
  * accesses and maintenance requests remain outside this first interface.
  */
class BreezePipelinedDCache(
    val cfg: DefaultDCacheConfig = DefaultDCacheConfig(),
    val hartId: Int = 0,
    val hartIdWidth: Int = 1,
    val txnIdWidth: Int = 2,
    val cpuIdWidth: Int = 4,
    val responseEntries: Int = 4
) extends Module {
  private val ways = cfg.ways
  private val sets = cfg.sets
  private val wordsPerLine = cfg.lineBytes / 8
  private val wordIndexWidth = math.max(1, log2Ceil(wordsPerLine))
  private val plen = 32

  require(cfg.VLEN == 64, "The pipelined Breeze DCache requires RV64")
  require(ways == 4, "The pipelined Breeze DCache PLRU is fixed to 4 ways")
  require(cfg.sets == 64 && cfg.lineBytes == 32,
    "The pipelined Breeze DCache address slicing is frozen to 64 sets and 32 B lines")
  require(responseEntries >= 2, "The pipelined DCache needs at least a two-entry response queue")
  require(hartId >= 0 && hartId < (1 << hartIdWidth), "DCache hartId out of range")

  val io = IO(new Bundle {
    val cpu = new Bundle {
      val req = Flipped(Decoupled(new PipelinedDCacheReq(cfg.VLEN, cpuIdWidth)))
      val rsp = Decoupled(new PipelinedDCacheResp(cpuIdWidth))
    }
    val reservationKill = Input(Bool())
    val fatalError = Output(Bool())
    val hpm = Output(new BreezeHpmEvents)
    val coherence = new Bundle {
      val req = new BreezeCoherenceReqIO(plen, cfg.lineBytes, hartIdWidth, txnIdWidth)
      val grant = Flipped(new BreezeCoherenceGrantIO(plen, cfg.lineBytes, hartIdWidth, txnIdWidth))
      val probe = Flipped(new BreezeCoherenceProbeIO(plen, hartIdWidth, txnIdWidth))
      val probeResp = new BreezeCoherenceProbeRespIO(plen, cfg.lineBytes, hartIdWidth, txnIdWidth)
    }
  })

  import BreezePipelinedDCacheState._
  import BreezeMemOp._

  // Metadata layout: [plru(3) | dirty(4) | excl(4) | valid(4)] per set.
  private val validBits = ways
  private val exclOffset = validBits
  private val dirtyOffset = 2 * ways
  private val plruOffset = 3 * ways
  private val metaWidth = 3 * ways + (ways - 1)

  private def validOf(meta: UInt): UInt = meta(validBits - 1, 0)
  private def exclOf(meta: UInt): UInt = meta(exclOffset + ways - 1, exclOffset)
  private def dirtyOf(meta: UInt): UInt = meta(dirtyOffset + ways - 1, dirtyOffset)
  private def plruOf(meta: UInt): UInt = meta(metaWidth - 1, plruOffset)
  private def makeMeta(valid: UInt, excl: UInt, dirty: UInt, plru: UInt): UInt =
    Cat(plru, dirty, excl, valid)

  private def touchWay(plru: UInt, way: UInt): UInt =
    MuxLookup(way, plru)(Seq(
      0.U -> Cat(1.U(1.W), 1.U(1.W), plru(0)),
      1.U -> Cat(1.U(1.W), 0.U(1.W), plru(0)),
      2.U -> Cat(0.U(1.W), plru(1), 1.U(1.W)),
      3.U -> Cat(0.U(1.W), plru(1), 0.U(1.W))
    ))

  private def lineWord(line: UInt, address: UInt): UInt = {
    val wordIndex = if (wordsPerLine == 1) 0.U(wordIndexWidth.W)
      else address(cfg.lineOffsetWidth - 1, 3)
    (line >> (wordIndex << 6))(63, 0)
  }

  private def mergeStore(line: UInt, address: UInt, data: UInt, mask: UInt): UInt = {
    val wordIndex = if (wordsPerLine == 1) 0.U(wordIndexWidth.W)
      else address(cfg.lineOffsetWidth - 1, 3)
    val shift = wordIndex << 6
    val byteMask64 = Cat((0 until 8).reverse.map(index => Fill(8, mask(index))))
    val shiftedMask = (byteMask64.pad(cfg.lineWidth) << shift)(cfg.lineWidth - 1, 0)
    val shiftedData = (data.pad(cfg.lineWidth) << shift)(cfg.lineWidth - 1, 0)
    (line & ~shiftedMask) | (shiftedData & shiftedMask)
  }

  // ===== Arrays =====
  val metaReg = RegInit(VecInit(Seq.fill(sets)(0.U(metaWidth.W))))
  val tagArray = Seq.fill(ways)(Module(new flowSRAM(sets, cfg.tagWidth, "pdcache_tag")))
  val dataArray = Seq.fill(ways)(Module(new flowSRAM(sets, cfg.lineWidth, "pdcache_data")))
  for (w <- 0 until ways) {
    tagArray(w).io.addr := 0.U
    tagArray(w).io.data_in := 0.U
    tagArray(w).io.we := false.B
    tagArray(w).io.re := false.B
    tagArray(w).io.id := w.U
    dataArray(w).io.addr := 0.U
    dataArray(w).io.data_in := 0.U
    dataArray(w).io.we := false.B
    dataArray(w).io.re := false.B
    dataArray(w).io.id := w.U
  }
  val tagRdata = VecInit(tagArray.map(_.io.data_out))
  val dataRdata = VecInit(dataArray.map(_.io.data_out))

  // ===== Fast-pipeline stage =====
  val s1Valid = RegInit(false.B)
  val s1Req = Reg(new PipelinedDCacheReq(cfg.VLEN, cpuIdWidth))
  // A disabled SyncReadMem port does not promise to retain its previous
  // output.  If response backpressure holds s1, preserve the completed lookup
  // rather than later reinterpreting an undefined SRAM output as a miss.
  val s1ArrayHeld = RegInit(false.B)
  val s1HeldTags = Reg(Vec(ways, UInt(cfg.tagWidth.W)))
  val s1HeldData = Reg(Vec(ways, UInt(cfg.lineWidth.W)))
  val s1LookupTags = Mux(s1ArrayHeld, s1HeldTags, tagRdata)
  val s1LookupData = Mux(s1ArrayHeld, s1HeldData, dataRdata)
  val s1Set = s1Req.addr(10, 5)
  val s1Tag = s1Req.addr(31, 11)
  val s1WayHit = VecInit((0 until ways).map(w =>
    validOf(metaReg(s1Set))(w) && s1LookupTags(w) === s1Tag))
  val s1Hit = s1WayHit.asUInt.orR
  val s1HitWay = OHToUInt(s1WayHit.asUInt)

  val s1AtomicSizeLegal = s1Req.sizeLog2 === 2.U || s1Req.sizeLog2 === 3.U
  val s1AtomicAligned = Mux(s1Req.sizeLog2 === 2.U,
    s1Req.addr(1, 0) === 0.U, s1Req.addr(2, 0) === 0.U)
  val s1AtomicLegal = s1AtomicSizeLegal && s1AtomicAligned

  val pma = Module(new PMAChecker)
  pma.io.query.addr := s1Req.addr
  pma.io.query.sizeLog2 := s1Req.sizeLog2
  pma.io.query.accessType := Mux(s1Req.memOp === Load || s1Req.memOp === Lr,
    PMAAccessType.Load, PMAAccessType.Store)
  val s1CachedAllowed = pma.io.result.allowed && pma.io.result.cacheable && !pma.io.result.device
  val s1LoadHit = s1Valid && s1Req.memOp === Load && s1CachedAllowed && s1Hit
  val s1LrHit = s1Valid && s1Req.memOp === Lr && s1AtomicLegal &&
    s1CachedAllowed && s1Hit

  val rspQueue = Module(new Queue(new PipelinedDCacheResp(cpuIdWidth), responseEntries))
  io.cpu.rsp <> rspQueue.io.deq

  val state = RegInit(Run)
  val fatalReg = RegInit(false.B)
  io.fatalError := fatalReg
  io.hpm := 0.U.asTypeOf(new BreezeHpmEvents)

  // Slow completion is held in registers until it enters the response queue.
  val completionId = Reg(UInt(cpuIdWidth.W))
  val completionData = Reg(UInt(64.W))
  val completionWriteAck = Reg(Bool())
  val completionError = Reg(Bool())
  val completionSetsReservation = RegInit(false.B)
  val completionResAddr = Reg(UInt(cfg.VLEN.W))
  val completionResSize = Reg(UInt(3.W))

  // LR hit completion is a real pipeline stage.  It can retire an older LR
  // while accepting the next LR from s1, so a stream of resident LRs has II=1.
  val lrCommitValid = RegInit(false.B)
  val lrCommitReq = Reg(new PipelinedDCacheReq(cfg.VLEN, cpuIdWidth))
  val lrCommitData = Reg(UInt(64.W))

  // One reservation set per hart.  SC matches exact address and size; probes
  // and replacement use the conservative 32-byte line granule.
  val resValid = RegInit(false.B)
  val resAddr = Reg(UInt(cfg.VLEN.W))
  val resSizeLog2 = Reg(UInt(3.W))
  val resLineAddr = Reg(UInt((plen - 5).W))

  val lrRspValid = state === Run && lrCommitValid
  val fastRspValid = state === Run && s1LoadHit && !lrCommitValid
  val slowRspValid = state === Complete
  rspQueue.io.enq.valid := lrRspValid || fastRspValid || slowRspValid
  rspQueue.io.enq.bits.id := Mux(lrRspValid, lrCommitReq.id,
    Mux(fastRspValid, s1Req.id, completionId))
  rspQueue.io.enq.bits.data := Mux(lrRspValid, lrCommitData,
    Mux(fastRspValid, lineWord(s1LookupData(s1HitWay), s1Req.addr), completionData))
  rspQueue.io.enq.bits.isWriteAck := Mux(lrRspValid || fastRspValid,
    false.B, completionWriteAck)
  rspQueue.io.enq.bits.error := Mux(lrRspValid || fastRspValid,
    false.B, completionError)
  rspQueue.io.enq.bits.pageFault := false.B
  rspQueue.io.enq.bits.faultAddr := 0.U

  // A slow operation reserves the currently free enqueue position before it
  // leaves Run.  No other response is produced until that operation reaches
  // Complete, so response backpressure cannot strand an AMO while it owns the
  // local atomic lock.
  val slowCanStart = rspQueue.io.enq.ready && !lrCommitValid

  // ===== Slow transaction context =====
  val txnReq = Reg(new PipelinedDCacheReq(cfg.VLEN, cpuIdWidth))
  val victimWayReg = Reg(UInt(cfg.wayIndexWidth.W))
  val victimTagReg = Reg(UInt(cfg.tagWidth.W))
  val victimDataReg = Reg(UInt(cfg.lineWidth.W))
  val refillLineReg = Reg(UInt(cfg.lineWidth.W))
  val refillGrantStateReg = Reg(BreezeGrantState())
  val opWayReg = Reg(UInt(cfg.wayIndexWidth.W))
  val opLineReg = Reg(UInt(cfg.lineWidth.W))
  val newPlruReg = Reg(UInt((ways - 1).W))

  val amoOldWordReg = Reg(UInt(64.W))
  val amoResultReg = Reg(UInt(64.W))
  val amoAlu = Module(new BreezeAmoAlu)
  val amoIsWord = txnReq.sizeLog2 === 2.U
  amoAlu.io.func := txnReq.amoFunc
  amoAlu.io.isWord := amoIsWord
  amoAlu.io.oldOperand := Mux(amoIsWord && txnReq.addr(2),
    amoOldWordReg(63, 32), amoOldWordReg)
  amoAlu.io.rs2 := txnReq.wdata
  val amoNewWData = Mux(amoIsWord,
    Mux(txnReq.addr(2), Cat(amoResultReg(31, 0), 0.U(32.W)), amoResultReg(31, 0).pad(64)),
    amoResultReg)
  val amoWMask = Mux(amoIsWord,
    Mux(txnReq.addr(2), "hf0".U(8.W), "h0f".U(8.W)), "hff".U(8.W))
  val amoMergedLine = mergeStore(opLineReg, txnReq.addr, amoNewWData, amoWMask)

  val txnSet = txnReq.addr(10, 5)
  val txnTag = txnReq.addr(31, 11)
  val txnLineBase = Cat(txnReq.addr(31, 5), 0.U(5.W))
  val txnStoreLike = txnReq.memOp === Store || txnReq.memOp === Sc ||
    txnReq.memOp === Amo
  val txnScMatch = resValid && resAddr === txnReq.addr &&
    resSizeLog2 === txnReq.sizeLog2

  // ===== Coherence transaction/probe context =====
  val txnIdReg = RegInit(0.U(txnIdWidth.W))
  val txnIdPendingReg = Reg(UInt(txnIdWidth.W))
  val txnLinePendingReg = Reg(UInt(plen.W))

  val probePending = RegInit(false.B)
  val probeTxnIdReg = Reg(UInt(txnIdWidth.W))
  val probeLineAddrReg = Reg(UInt(plen.W))
  val probeOpcodeReg = Reg(BreezeProbeOpcode())
  val probeHasDataReg = Reg(Bool())
  val probeDataReg = Reg(UInt(cfg.lineWidth.W))
  val resumeState = RegInit(Run)

  val coh = io.coherence
  coh.req.valid := false.B
  coh.req.opcode := BreezeCoherenceOpcode.GetS
  coh.req.srcHart := hartId.U
  coh.req.txnId := txnIdReg
  coh.req.lineAddr := txnLineBase
  coh.req.hasData := false.B
  coh.req.lineData := victimDataReg

  coh.grant.ready := (state === EvictWait || state === RefillWait || state === UpgradeWait) &&
    !probePending && !coh.probe.valid
  when(coh.grant.valid && coh.grant.ready) {
    assert(coh.grant.txnId === txnIdPendingReg,
      "Pipelined DCache: grant txnId mismatch")
    assert(coh.grant.lineAddr === txnLinePendingReg,
      "Pipelined DCache: grant line address mismatch")
  }

  coh.probe.ready := !probePending
  when(coh.probe.valid && coh.probe.ready) {
    probePending := true.B
    probeTxnIdReg := coh.probe.txnId
    probeLineAddrReg := coh.probe.lineAddr
    probeOpcodeReg := coh.probe.opcode
  }
  coh.probeResp.valid := state === ProbeRespond
  coh.probeResp.srcHart := hartId.U
  coh.probeResp.txnId := probeTxnIdReg
  coh.probeResp.lineAddr := probeLineAddrReg
  coh.probeResp.ack := true.B
  coh.probeResp.hasData := probeHasDataReg
  coh.probeResp.lineData := probeDataReg

  // ===== Request acceptance and array-port arbitration =====
  // A current read hit may advance while the next request launches its SRAM
  // read. LR has a separate commit register; an older LR owns the response
  // enqueue port but may hand that register directly to the next LR.
  val lrCommitAdvances = lrRspValid && rspQueue.io.enq.ready
  val lrCommitReady = !lrCommitValid || lrCommitAdvances
  val s1LoadAdvances = fastRspValid && rspQueue.io.enq.ready
  val s1LrAdvances = state === Run && s1LrHit && lrCommitReady
  val s1FastAdvances = s1LoadAdvances || s1LrAdvances
  io.cpu.req.ready := state === Run && !fatalReg && !probePending && !coh.probe.valid &&
    (!s1Valid || s1FastAdvances)

  val cpuArrayRead = state === Run && io.cpu.req.fire
  val probeSet = probeLineAddrReg(10, 5)
  val probeTag = probeLineAddrReg(31, 11)
  val arrayRead = cpuArrayRead || state === ProbeRead || state === ReplayRead
  val arrayReadSet = Mux(state === ProbeRead, probeSet,
    Mux(state === ReplayRead, s1Set, io.cpu.req.bits.addr(10, 5)))
  for (w <- 0 until ways) {
    tagArray(w).io.addr := arrayReadSet
    tagArray(w).io.re := arrayRead
    dataArray(w).io.addr := arrayReadSet
    dataArray(w).io.re := arrayRead
  }

  val s1SlowStarts = state === Run && s1Valid && !s1LoadHit && !s1LrHit &&
    slowCanStart && !probePending
  val s1ReplaysForProbe = state === Run && probePending && s1Valid && !s1FastAdvances
  when(s1FastAdvances || s1SlowStarts || s1ReplaysForProbe) {
    s1ArrayHeld := false.B
  }.elsewhen(state === Run && s1Valid && !s1ArrayHeld) {
    s1HeldTags := tagRdata
    s1HeldData := dataRdata
    s1ArrayHeld := true.B
  }
  io.hpm.dcacheAccess := io.cpu.req.fire
  io.hpm.dcacheMiss := s1SlowStarts && s1CachedAllowed && !s1Hit
  io.hpm.dcacheUncached := s1SlowStarts && pma.io.result.allowed &&
    (!pma.io.result.cacheable || pma.io.result.device)

  val (_, replacementPlru, replacementOH) =
    BreezePLRU.replace_way_select(validOf(metaReg(s1Set)), plruOf(metaReg(s1Set)))
  val replacementWay = OHToUInt(replacementOH)

  private def startProbeThen(next: BreezePipelinedDCacheState.Type): Unit = {
    resumeState := next
    state := ProbeRead
  }

  private def setCompletion(id: UInt, data: UInt, writeAck: Bool, error: Bool): Unit = {
    completionId := id
    completionData := data
    completionWriteAck := writeAck
    completionError := error
    completionSetsReservation := false.B
    state := Complete
  }

  private def setLrCompletion(req: PipelinedDCacheReq, data: UInt): Unit = {
    setCompletion(req.id, data, false.B, false.B)
    completionSetsReservation := true.B
    completionResAddr := req.addr
    completionResSize := req.sizeLog2
  }

  private def issueTxn(lineAddr: UInt): Unit = {
    txnIdPendingReg := txnIdReg
    txnIdReg := txnIdReg + 1.U
    txnLinePendingReg := lineAddr
  }

  when(lrCommitAdvances) {
    lrCommitValid := false.B
    resValid := true.B
    resAddr := lrCommitReq.addr
    resSizeLog2 := lrCommitReq.sizeLog2
    resLineAddr := lrCommitReq.addr(31, 5)
  }
  when(slowRspValid && rspQueue.io.enq.ready && completionSetsReservation) {
    resValid := true.B
    resAddr := completionResAddr
    resSizeLog2 := completionResSize
    resLineAddr := completionResAddr(31, 5)
  }

  // ===== Pipeline and slow-path state machine =====
  switch(state) {
    is(Run) {
      when(probePending && !s1Valid && (!lrCommitValid || lrCommitAdvances)) {
        startProbeThen(Run)
      }.elsewhen(probePending && s1Valid && !s1FastAdvances &&
          (!lrCommitValid || lrCommitAdvances)) {
        // The probe is ordered before the held request.  Re-read the arrays
        // afterwards because the probe may change or invalidate its line.
        startProbeThen(ReplayRead)
      }.elsewhen(s1Valid) {
        when(s1LoadHit) {
          when(s1LoadAdvances) {
            metaReg(s1Set) := makeMeta(
              validOf(metaReg(s1Set)), exclOf(metaReg(s1Set)), dirtyOf(metaReg(s1Set)),
              touchWay(plruOf(metaReg(s1Set)), s1HitWay))
            s1Valid := io.cpu.req.fire
            when(io.cpu.req.fire) { s1Req := io.cpu.req.bits }
          }
        }.elsewhen(s1LrHit) {
          when(lrCommitReady) {
            lrCommitValid := true.B
            lrCommitReq := s1Req
            lrCommitData := lineWord(s1LookupData(s1HitWay), s1Req.addr)
            metaReg(s1Set) := makeMeta(
              validOf(metaReg(s1Set)), exclOf(metaReg(s1Set)), dirtyOf(metaReg(s1Set)),
              touchWay(plruOf(metaReg(s1Set)), s1HitWay))
            s1Valid := io.cpu.req.fire
            when(io.cpu.req.fire) { s1Req := io.cpu.req.bits }
          }
        }.elsewhen(slowCanStart) {
          s1Valid := false.B
          txnReq := s1Req
          val supportedOp = s1Req.memOp === Load || s1Req.memOp === Store ||
            s1Req.memOp === Lr || s1Req.memOp === Sc || s1Req.memOp === Amo
          val atomicOp = s1Req.memOp === Lr || s1Req.memOp === Sc ||
            s1Req.memOp === Amo
          when(!s1CachedAllowed || !supportedOp || (atomicOp && !s1AtomicLegal)) {
            when(s1Req.memOp === Sc) { resValid := false.B }
            setCompletion(s1Req.id, 0.U, false.B, true.B)
          }.elsewhen(s1Req.memOp === Sc) {
            val scMatch = resValid && resAddr === s1Req.addr &&
              resSizeLog2 === s1Req.sizeLog2
            when(!scMatch || !s1Hit) {
              resValid := false.B
              setCompletion(s1Req.id, 1.U, false.B, false.B)
            }.otherwise {
              opWayReg := s1HitWay
              opLineReg := s1LookupData(s1HitWay)
              when(dirtyOf(metaReg(s1Set))(s1HitWay) ||
                  exclOf(metaReg(s1Set))(s1HitWay)) {
                state := ScWrite
              }.otherwise {
                state := UpgradeReq
              }
            }
          }.elsewhen(s1Hit) {
            opWayReg := s1HitWay
            when(s1Req.memOp === Store) {
              when(dirtyOf(metaReg(s1Set))(s1HitWay) || exclOf(metaReg(s1Set))(s1HitWay)) {
                opLineReg := mergeStore(s1LookupData(s1HitWay), s1Req.addr,
                  s1Req.wdata, s1Req.wmask)
                state := StoreWrite
              }.otherwise {
                opLineReg := s1LookupData(s1HitWay)
                state := UpgradeReq
              }
            }.elsewhen(s1Req.memOp === Amo) {
              assert(s1Req.sizeLog2 === 2.U || s1Req.sizeLog2 === 3.U,
                "Pipelined DCache: AMO must be W or D")
              assert(Mux(s1Req.sizeLog2 === 2.U, s1Req.addr(1, 0) === 0.U,
                s1Req.addr(2, 0) === 0.U),
                "Pipelined DCache: AMO must be naturally aligned")
              when(dirtyOf(metaReg(s1Set))(s1HitWay) || exclOf(metaReg(s1Set))(s1HitWay)) {
                opLineReg := s1LookupData(s1HitWay)
                state := AmoPrepare
              }.otherwise {
                opLineReg := s1LookupData(s1HitWay)
                state := UpgradeReq
              }
            }.otherwise {
              assert(s1Req.memOp === Load || s1Req.memOp === Lr,
                "Pipelined DCache: unexpected read operation")
            }
          }.otherwise {
            victimWayReg := replacementWay
            victimTagReg := s1LookupTags(replacementWay)
            victimDataReg := s1LookupData(replacementWay)
            newPlruReg := replacementPlru
            when(validOf(metaReg(s1Set))(replacementWay) &&
                Cat(s1LookupTags(replacementWay), s1Set) === resLineAddr) {
              resValid := false.B
            }
            state := Mux(validOf(metaReg(s1Set))(replacementWay), EvictReq, RefillReq)
          }
        }
      }.otherwise {
        when(io.cpu.req.fire) {
          s1Valid := true.B
          s1Req := io.cpu.req.bits
        }
      }
    }

    is(ReplayRead) {
      state := Run
    }

    // Local Store hit or post-upgrade/refill Store.  This state is the only
    // write-port owner for a normal Store, and no new lookup is accepted.
    is(StoreWrite) {
      for (w <- 0 until ways) {
        when(opWayReg === w.U) {
          dataArray(w).io.addr := txnSet
          dataArray(w).io.data_in := opLineReg
          dataArray(w).io.we := true.B
        }
      }
      metaReg(txnSet) := makeMeta(
        validOf(metaReg(txnSet)) | (1.U << opWayReg),
        exclOf(metaReg(txnSet)) & ~(1.U << opWayReg),
        dirtyOf(metaReg(txnSet)) | (1.U << opWayReg),
        touchWay(plruOf(metaReg(txnSet)), opWayReg))
      resValid := false.B
      setCompletion(txnReq.id, 0.U, true.B, false.B)
    }

    // SC owns the same local write port as Store.  The reservation is checked
    // again here so a trap-time kill between lookup/grant and this stage turns
    // the operation into an architectural SC failure without modifying data.
    is(ScWrite) {
      when(!txnScMatch) {
        resValid := false.B
        setCompletion(txnReq.id, 1.U, false.B, false.B)
      }.otherwise {
        val scLine = mergeStore(opLineReg, txnReq.addr, txnReq.wdata, txnReq.wmask)
        for (w <- 0 until ways) {
          when(opWayReg === w.U) {
            dataArray(w).io.addr := txnSet
            dataArray(w).io.data_in := scLine
            dataArray(w).io.we := true.B
          }
        }
        metaReg(txnSet) := makeMeta(
          validOf(metaReg(txnSet)) | (1.U << opWayReg),
          exclOf(metaReg(txnSet)) & ~(1.U << opWayReg),
          dirtyOf(metaReg(txnSet)) | (1.U << opWayReg),
          touchWay(plruOf(metaReg(txnSet)), opWayReg))
        resValid := false.B
        setCompletion(txnReq.id, 0.U, false.B, false.B)
      }
    }

    // A GetM may complete after a probe killed the SC reservation.  Home has
    // nevertheless made this hart the unique owner, so retain the granted data
    // as a clean E line while returning SC failure.
    is(ScInstallE) {
      for (w <- 0 until ways) {
        when(opWayReg === w.U) {
          tagArray(w).io.addr := txnSet
          tagArray(w).io.data_in := txnTag
          tagArray(w).io.we := true.B
          dataArray(w).io.addr := txnSet
          dataArray(w).io.data_in := opLineReg
          dataArray(w).io.we := true.B
        }
      }
      metaReg(txnSet) := makeMeta(
        validOf(metaReg(txnSet)) | (1.U << opWayReg),
        exclOf(metaReg(txnSet)) | (1.U << opWayReg),
        dirtyOf(metaReg(txnSet)) & ~(1.U << opWayReg),
        touchWay(plruOf(metaReg(txnSet)), opWayReg))
      resValid := false.B
      setCompletion(txnReq.id, 1.U, false.B, false.B)
    }

    // Global local-atomic lock.  Probes may enter probePending, but none of
    // these states branches to ProbeRead before the updated line is installed.
    is(AmoPrepare) {
      amoOldWordReg := lineWord(opLineReg, txnReq.addr)
      state := AmoExecute
    }
    is(AmoExecute) {
      val pteUpdate = txnReq.amoFunc === BreezeAmoFunc.PteSetAd
      when(pteUpdate) {
        assert(txnReq.sizeLog2 === 3.U,
          "Pipelined DCache: PTW update requires a complete RV64 PTE")
        assert((txnReq.wmask & "h3f".U) === 0.U,
          "Pipelined DCache: PTW update may only set A/D")
      }
      amoResultReg := Mux(pteUpdate,
        Mux(amoOldWordReg === txnReq.wdata,
          amoOldWordReg | txnReq.wmask, amoOldWordReg),
        amoAlu.io.newOperand)
      state := AmoWrite
    }
    is(AmoWrite) {
      for (w <- 0 until ways) {
        when(opWayReg === w.U) {
          dataArray(w).io.addr := txnSet
          dataArray(w).io.data_in := amoMergedLine
          dataArray(w).io.we := true.B
        }
      }
      metaReg(txnSet) := makeMeta(
        validOf(metaReg(txnSet)) | (1.U << opWayReg),
        exclOf(metaReg(txnSet)) & ~(1.U << opWayReg),
        dirtyOf(metaReg(txnSet)) | (1.U << opWayReg),
        touchWay(plruOf(metaReg(txnSet)), opWayReg))
      resValid := false.B
      setCompletion(txnReq.id, amoOldWordReg, false.B, false.B)
    }

    is(EvictReq) {
      when(probePending) {
        startProbeThen(EvictReq)
      }.otherwise {
        val stillValid = validOf(metaReg(txnSet))(victimWayReg)
        when(!stillValid) {
          state := RefillReq
        }.otherwise {
          val dirty = dirtyOf(metaReg(txnSet))(victimWayReg)
          coh.req.valid := true.B
          coh.req.opcode := Mux(dirty, BreezeCoherenceOpcode.PutM, BreezeCoherenceOpcode.PutS)
          coh.req.lineAddr := Cat(victimTagReg, txnSet, 0.U(5.W))
          coh.req.hasData := dirty
          coh.req.lineData := victimDataReg
          when(coh.req.ready) {
            issueTxn(Cat(victimTagReg, txnSet, 0.U(5.W)))
            state := EvictWait
          }
        }
      }
    }
    is(EvictWait) {
      when(probePending) {
        startProbeThen(EvictWait)
      }.elsewhen(coh.grant.valid && coh.grant.ready) {
        when(coh.grant.error) {
          setCompletion(txnReq.id, 0.U, false.B, true.B)
        }.otherwise {
          metaReg(txnSet) := makeMeta(
            validOf(metaReg(txnSet)) & ~(1.U << victimWayReg),
            exclOf(metaReg(txnSet)) & ~(1.U << victimWayReg),
            dirtyOf(metaReg(txnSet)) & ~(1.U << victimWayReg),
            plruOf(metaReg(txnSet)))
          state := RefillReq
        }
      }
    }

    is(RefillReq) {
      when(probePending) {
        startProbeThen(RefillReq)
      }.otherwise {
        coh.req.valid := true.B
        coh.req.opcode := Mux(txnStoreLike, BreezeCoherenceOpcode.GetM,
          BreezeCoherenceOpcode.GetS)
        coh.req.lineAddr := txnLineBase
        when(coh.req.ready) {
          issueTxn(txnLineBase)
          state := RefillWait
        }
      }
    }
    is(RefillWait) {
      when(probePending) {
        startProbeThen(RefillWait)
      }.elsewhen(coh.grant.valid && coh.grant.ready) {
        when(coh.grant.error || !coh.grant.hasData) {
          setCompletion(txnReq.id, 0.U, false.B, true.B)
        }.otherwise {
          refillLineReg := coh.grant.lineData
          refillGrantStateReg := coh.grant.grantState
          state := RefillInstall
        }
      }
    }
    is(RefillInstall) {
      val grantedM = refillGrantStateReg === BreezeGrantState.M
      val grantedE = refillGrantStateReg === BreezeGrantState.E
      val storeLine = mergeStore(refillLineReg, txnReq.addr, txnReq.wdata, txnReq.wmask)
      for (w <- 0 until ways) {
        when(victimWayReg === w.U) {
          tagArray(w).io.addr := txnSet
          tagArray(w).io.data_in := txnTag
          tagArray(w).io.we := true.B
          dataArray(w).io.addr := txnSet
          dataArray(w).io.data_in := Mux(txnReq.memOp === Store, storeLine, refillLineReg)
          dataArray(w).io.we := txnReq.memOp =/= Amo
        }
      }
      when(txnStoreLike) {
        assert(grantedM, "Pipelined DCache: GetM did not grant M")
      }
      metaReg(txnSet) := makeMeta(
        validOf(metaReg(txnSet)) | (1.U << victimWayReg),
        Mux(grantedE && !txnStoreLike,
          exclOf(metaReg(txnSet)) | (1.U << victimWayReg),
          exclOf(metaReg(txnSet)) & ~(1.U << victimWayReg)),
        Mux(txnStoreLike,
          dirtyOf(metaReg(txnSet)) | (1.U << victimWayReg),
          dirtyOf(metaReg(txnSet)) & ~(1.U << victimWayReg)),
        newPlruReg)
      opWayReg := victimWayReg
      when(txnReq.memOp === Load) {
        setCompletion(txnReq.id, lineWord(refillLineReg, txnReq.addr), false.B, false.B)
      }.elsewhen(txnReq.memOp === Lr) {
        setLrCompletion(txnReq, lineWord(refillLineReg, txnReq.addr))
      }.elsewhen(txnReq.memOp === Store) {
        setCompletion(txnReq.id, 0.U, true.B, false.B)
      }.elsewhen(txnReq.memOp === Amo) {
        opLineReg := refillLineReg
        state := AmoPrepare
      }.otherwise {
        assert(false.B, "Pipelined DCache: SC must never allocate on a miss")
        setCompletion(txnReq.id, 1.U, false.B, true.B)
      }
    }

    is(UpgradeReq) {
      when(probePending) {
        startProbeThen(UpgradeReq)
      }.elsewhen(txnReq.memOp === Sc && !txnScMatch) {
        resValid := false.B
        setCompletion(txnReq.id, 1.U, false.B, false.B)
      }.otherwise {
        coh.req.valid := true.B
        coh.req.opcode := BreezeCoherenceOpcode.GetM
        coh.req.lineAddr := txnLineBase
        when(coh.req.ready) {
          issueTxn(txnLineBase)
          state := UpgradeWait
        }
      }
    }
    is(UpgradeWait) {
      when(probePending) {
        startProbeThen(UpgradeWait)
      }.elsewhen(coh.grant.valid && coh.grant.ready) {
        val localStillValid = validOf(metaReg(txnSet))(opWayReg)
        when(coh.grant.error || (!coh.grant.hasData && !localStillValid)) {
          when(txnReq.memOp === Sc) { resValid := false.B }
          setCompletion(txnReq.id, 0.U, false.B, true.B)
        }.otherwise {
          val baseLine = Mux(coh.grant.hasData, coh.grant.lineData, opLineReg)
          when(txnReq.memOp === Store) {
            opLineReg := mergeStore(baseLine, txnReq.addr, txnReq.wdata, txnReq.wmask)
            state := StoreWrite
          }.elsewhen(txnReq.memOp === Sc) {
            opLineReg := baseLine
            state := Mux(txnScMatch, ScWrite, ScInstallE)
          }.otherwise {
            opLineReg := baseLine
            state := AmoPrepare
          }
        }
      }
    }

    is(ProbeRead) {
      state := ProbeCompare
    }
    is(ProbeCompare) {
      val pHitVec = VecInit((0 until ways).map(w =>
        validOf(metaReg(probeSet))(w) && tagRdata(w) === probeTag))
      val pHit = pHitVec.asUInt.orR
      val pWay = OHToUInt(pHitVec.asUInt)
      val pDirty = dirtyOf(metaReg(probeSet))(pWay)
      probeHasDataReg := pHit && pDirty
      probeDataReg := dataRdata(pWay)
      when(probeLineAddrReg(31, 5) === resLineAddr) {
        resValid := false.B
      }
      when(pHit) {
        val invalidate = probeOpcodeReg === BreezeProbeOpcode.ProbeInv ||
          probeOpcodeReg === BreezeProbeOpcode.ProbeRecallInv
        metaReg(probeSet) := makeMeta(
          Mux(invalidate, validOf(metaReg(probeSet)) & ~(1.U << pWay),
            validOf(metaReg(probeSet))),
          exclOf(metaReg(probeSet)) & ~(1.U << pWay),
          dirtyOf(metaReg(probeSet)) & ~(1.U << pWay),
          plruOf(metaReg(probeSet)))
      }
      state := ProbeRespond
    }
    is(ProbeRespond) {
      when(coh.probeResp.ready) {
        probePending := false.B
        state := resumeState
      }
    }

    is(Complete) {
      when(rspQueue.io.enq.ready) {
        state := Run
      }
    }

    is(Fatal) {
      fatalReg := true.B
      state := Fatal
    }
  }

  // Only one response source may enqueue in a cycle.  Slow states stop the
  // fast pipeline, so this assertion also guards future edits to that boundary.
  assert(!(fastRspValid && slowRspValid),
    "Pipelined DCache: fast and slow responses overlapped")
  when(state === Run && s1Valid) {
    assert(PopCount(s1WayHit) <= 1.U,
      "Pipelined DCache: duplicate matching ways")
  }
  when(state === ScWrite || state === AmoPrepare || state === AmoExecute || state === AmoWrite) {
    assert(!io.cpu.req.ready, "Pipelined DCache: CPU intake open during atomic lock")
    assert(!coh.grant.ready, "Pipelined DCache: grant accepted during atomic lock")
  }

  // Trap/interrupt/flush kill has final priority over LR completion.
  when(io.reservationKill) {
    resValid := false.B
  }
}
