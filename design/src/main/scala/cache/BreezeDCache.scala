package flow.cache

import chisel3._
import chisel3.util._
import flow.config.DefaultDCacheConfig
import flow.interface._
import flow.mem.flowSRAM
import flow.platform.{PMAAccessType, PMAChecker}

object BreezeDCacheState extends ChiselEnum {
  val Idle, Lookup, StoreHitWrite,
      UncachedReq, UncachedWait,
      RefillReq, RefillWait,
      UpgradeReq, UpgradeWait,
      PutReq, PutWait,
      ProbeRead, ProbeCompare, ProbeRespond,
      Respond,
      FlushScan, FlushRead, FlushWritebackReq, FlushWritebackWait, FlushRespond,
      Fatal = Value
}

/** Blocking, 4-way set-associative, write-back/write-allocate coherent L1
  * data cache with RV64A support.
  *
  * Default geometry: 8192 B capacity, 32 B lines, 4 ways -> 64 sets.
  * Tag/Data arrays use synchronous-read SRAMs (one-cycle read latency); the
  * valid/exclusive/dirty/PLRU metadata is resettable register memory, so every
  * line is logically invalid after reset without relying on SRAM contents.
  *
  * MESI state is encoded on the metadata as valid/exclusive/dirty:
  * I = !valid, S = valid && !excl && !dirty, E = valid && excl && !dirty,
  * M = valid && dirty. E->M upgrades are silent (no Home message).
  *
  * All cached traffic goes through the MESI coherence sideband (GetS/GetM/
  * PutS/PutM requests, grants, probes and probe responses); there is no
  * direct cached path to a memory bus any more. Uncached/MMIO accesses use
  * the scalar pulse interface (mmioReq/mmioRsp), routed to the cluster MMIO
  * arbiter. LR/SC/AMO are rejected with an access error on non-cacheable or
  * device regions.
  *
  * Probe discipline (the three transient races of the blocking protocol):
  *   1. A probe is latched into a one-entry pending register in any state and
  *      serviced with priority in Idle and in every state that waits on the
  *      Home (PutReq/PutWait/UpgradeReq/UpgradeWait/RefillReq/RefillWait/
  *      Flush*). It is deferred while a local SRAM mutation or the AMO/SC
  *      read-modify-write window is in flight (Lookup/StoreHitWrite/Respond),
  *      all of which complete in a bounded number of cycles - this is the
  *      atomicLock of the specification.
  *   2. A pending PutS/PutM that has not been handshaken re-checks the victim
  *      metadata after every probe service; if the probe already took the
  *      line, the Put is cancelled (never "resumed"), because the line was
  *      surrendered through the probe response.
  *   3. An S->M upgrade whose S copy is invalidated by a probe while the GetM
  *      waits for the Home relies on the Home's directory rule: a GrantM
  *      carries data whenever the requester is not a sharer at processing
  *      time. The L1 therefore installs grant data when hasData=1 and asserts
  *      the line is still valid when hasData=0.
  *
  * Reservation (LR/SC): one register set per hart, conservative 32 B line
  * granule for external invalidation, exact address+size match required for
  * SC success. Cleared by: any probe whose line matches, eviction/flush of
  * the line, completion of any store/SC/AMO, SC completion (either result),
  * and the external `resKill` pulse (trap).
  *
  * The CPU interface and the MMIO lower-level interface retain the project's
  * pulse protocol; the one CPU pulse that can legally race an unsolicited
  * probe is held in a one-entry skid register. Address slicing is derived
  * from the configured set count while 32 B lines and four ways stay fixed.
  */
class BreezeDCache(
    val cfg: DefaultDCacheConfig = DefaultDCacheConfig(),
    val hartId: Int = 0,
    val hartIdWidth: Int = 1,
    val txnIdWidth: Int = 2,
    val enableTrace: Boolean = false,
    val enableAtomics: Boolean = true
) extends Module {
  private val ways = cfg.ways
  private val sets = cfg.sets
  private val wordsPerLine = cfg.lineBytes / 8
  private val wordIndexWidth = math.max(1, log2Ceil(wordsPerLine))
  private val plen = 32

  require(cfg.VLEN == 64, "The Breeze DCache requires RV64")
  require(ways == 4, "The Breeze DCache PLRU is fixed to 4 ways")
  require(cfg.lineBytes == 32, "The Breeze DCache line size is fixed to 32 B")
  require(hartId >= 0 && hartId < (1 << hartIdWidth), "DCache hartId out of range")

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

  /** Mark `way` as most-recently-used in a 3-bit tree-PLRU. */
  private def touchWay(plru: UInt, way: UInt): UInt =
    MuxLookup(way, plru)(Seq(
      0.U -> Cat(1.U(1.W), 1.U(1.W), plru(0)),
      1.U -> Cat(1.U(1.W), 0.U(1.W), plru(0)),
      2.U -> Cat(0.U(1.W), plru(1), 1.U(1.W)),
      3.U -> Cat(0.U(1.W), plru(1), 0.U(1.W))
    ))

  val io = IO(new Bundle {
    val cpu = Flipped(new BackendMemIO(cfg.VLEN))
    val flushReq = Input(Bool())
    val flushDone = Output(Bool())
    // Trap/reset reservation kill from the backend (one-cycle pulse).
    val resKill = Input(Bool())
    val fatalError = Output(Bool())
    val hpm = Output(new BreezeHpmEvents)
    // Passive debug trace; top-level production generation disconnects and
    // eliminates it together with the Tandem outputs.
    val trace = if (enableTrace) Some(Output(new DCacheTracePayload(cfg.VLEN))) else None
    // Uncached/MMIO scalar path (pulse protocol, served by the MMIO arbiter).
    val mmioReq = new DCacheMemReqIO(cfg.PLEN, cfg.lineBytes)
    val mmioRsp = new DCacheMemRespIO(cfg.lineBytes)
    // Coherence sideband. Directions as seen from the L1D: req and probeResp
    // are driven by this module; grant and probe arrive from the Home.
    val coherence = new Bundle {
      val req = new BreezeCoherenceReqIO(plen, cfg.lineBytes, hartIdWidth, txnIdWidth)
      val grant = Flipped(new BreezeCoherenceGrantIO(plen, cfg.lineBytes, hartIdWidth, txnIdWidth))
      val probe = Flipped(new BreezeCoherenceProbeIO(plen, hartIdWidth, txnIdWidth))
      val probeResp = new BreezeCoherenceProbeRespIO(plen, cfg.lineBytes, hartIdWidth, txnIdWidth)
    }
  })

  import BreezeDCacheState._
  import BreezeMemOp._

  io.hpm := 0.U.asTypeOf(new BreezeHpmEvents)

  // ===== Storage =====
  val metaReg = RegInit(VecInit(Seq.fill(sets)(0.U(metaWidth.W))))
  val tagArray = Seq.fill(ways)(Module(new flowSRAM(sets, cfg.tagWidth, "tag")))
  val dataArray = Seq.fill(ways)(Module(new flowSRAM(sets, cfg.lineWidth, "data")))
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

  // ===== Request / state registers =====
  val state = RegInit(Idle)
  val reqAddr = RegInit(0.U(cfg.VLEN.W))
  val reqIsWrite = RegInit(false.B)
  val reqSizeLog2 = RegInit(0.U(3.W))
  val reqWData = RegInit(0.U(64.W))
  val reqWMask = RegInit(0.U(8.W))
  val reqMemOp = RegInit(BreezeMemOp.Load)
  val reqAmoFunc = RegInit(BreezeAmoFunc.Swap)

  // An unsolicited probe can temporarily occupy an otherwise-idle cache.
  // Preserve the one CPU request pulse that can legally race it.
  val cpuPendingValid = RegInit(false.B)
  val cpuPending = Reg(new BackendMemReq(cfg.VLEN))

  val victimWayReg = RegInit(0.U(cfg.wayIndexWidth.W))
  val newPlruReg = RegInit(0.U((ways - 1).W))
  val victimTagReg = RegInit(0.U(cfg.tagWidth.W))
  val victimDataReg = RegInit(0.U(cfg.lineWidth.W))
  val storeMergeReg = RegInit(0.U(cfg.lineWidth.W))
  val storeHitWayReg = RegInit(0.U(cfg.wayIndexWidth.W))
  // Raw old line latched for the S-hit upgrade path (store/SC/AMO); used when
  // the grant carries no data.
  val upgradeLineReg = RegInit(0.U(cfg.lineWidth.W))

  val responseData = RegInit(0.U(64.W))
  val responseError = RegInit(false.B)
  val responseIsWrite = RegInit(false.B)
  private val flushIndexWidth = cfg.setIndexWidth + cfg.wayIndexWidth
  private val flushLast = (sets * ways - 1).U(flushIndexWidth.W)
  val flushIndex = RegInit(0.U(flushIndexWidth.W))
  val fatalErrorReg = RegInit(false.B)

  // ===== Reservation (LR/SC), one set per hart =====
  val resValid = RegInit(false.B)
  val resAddr = RegInit(0.U(cfg.VLEN.W))     // naturally aligned W/D address
  val resSizeLog2 = RegInit(0.U(3.W))
  val resLineAddr = RegInit(0.U((plen - cfg.lineOffsetWidth).W))

  // Coherence transaction bookkeeping.
  val txnIdReg = RegInit(0.U(txnIdWidth.W))
  // The id the in-flight request was sent with; a grant must echo this one
  // (txnIdReg itself has already moved on to the next id by then).
  val txnIdPendingReg = RegInit(0.U(txnIdWidth.W))
  val txnLineAddrReg = RegInit(0.U(plen.W))
  val probePendingValid = RegInit(false.B)
  val probeTxnIdReg = RegInit(0.U(txnIdWidth.W))
  val probeLineAddrReg = RegInit(0.U(plen.W))
  val probeOpcodeReg = RegInit(BreezeProbeOpcode.ProbeToS)
  val probeHasDataReg = RegInit(false.B)
  val probeDataReg = RegInit(0.U(cfg.lineWidth.W))
  val resumeState = RegInit(Idle)

  private def lineWord(line: UInt, address: UInt): UInt = {
    val wordIndex = if (wordsPerLine == 1) {
      0.U(wordIndexWidth.W)
    } else {
      address(cfg.lineOffsetWidth - 1, 3)
    }
    val shift = wordIndex << 6
    (line >> shift)(63, 0)
  }

  private def mergeStore(line: UInt, address: UInt, data: UInt, mask: UInt): UInt = {
    val wordIndex = if (wordsPerLine == 1) {
      0.U(wordIndexWidth.W)
    } else {
      address(cfg.lineOffsetWidth - 1, 3)
    }
    val shift = wordIndex << 6
    val byteMask64 = Cat((0 until 8).reverse.map(index => Fill(8, mask(index))))
    val paddedMask = byteMask64.pad(cfg.lineWidth)
    val paddedData = data.pad(cfg.lineWidth)
    val shiftedMask = (paddedMask << shift)(cfg.lineWidth - 1, 0)
    val shiftedData = (paddedData << shift)(cfg.lineWidth - 1, 0)
    (line & ~shiftedMask) | (shiftedData & shiftedMask)
  }

  // ===== Address decode =====
  val setIndex = reqAddr(
    cfg.lineOffsetWidth + cfg.setIndexWidth - 1,
    cfg.lineOffsetWidth)
  val requestTag = reqAddr(31, cfg.lineOffsetWidth + cfg.setIndexWidth)
  val requestBeatBase64 = Cat(reqAddr(63, 3), 0.U(3.W))
  val requestLineBase32 = Cat(
    reqAddr(31, cfg.lineOffsetWidth),
    0.U(cfg.lineOffsetWidth.W))

  val reqIsLr = enableAtomics.B && reqMemOp === Lr
  val reqIsSc = enableAtomics.B && reqMemOp === Sc
  val reqIsAmo = enableAtomics.B && reqMemOp === Amo
  val isAtomicOp = reqIsLr || reqIsSc || reqIsAmo
  val isStoreLike = reqMemOp === Store || reqIsAmo
  val amoIsWord = reqSizeLog2 === 2.U

  // ===== AMO ALU (combinational; fed per-state below) =====
  val amoAlu = if (enableAtomics) Some(Module(new BreezeAmoAlu)) else None
  val amoOldWord = Wire(UInt(64.W)) // pre-modification aligned 64-bit word
  val amoNewOperand = WireDefault(0.U(64.W))
  amoOldWord := 0.U
  amoAlu.foreach { unit =>
    unit.io.func := reqAmoFunc
    unit.io.isWord := amoIsWord
    unit.io.oldOperand := Mux(amoIsWord && reqAddr(2),
      amoOldWord(63, 32), amoOldWord)
    unit.io.rs2 := reqWData
    amoNewOperand := unit.io.newOperand
  }
  // Position the new value and mask within the aligned 64-bit word.
  val amoNewWData = Mux(amoIsWord,
    Mux(reqAddr(2), Cat(amoNewOperand(31, 0), 0.U(32.W)),
      amoNewOperand(31, 0).pad(64)),
    amoNewOperand)
  val amoWMask = Mux(amoIsWord,
    Mux(reqAddr(2), "hf0".U(8.W), "h0f".U(8.W)),
    "hff".U(8.W))

  private def amoMergedLine(baseLine: UInt): UInt =
    mergeStore(baseLine, reqAddr, amoNewWData, amoWMask)

  // ===== PMA =====
  val pma = Module(new PMAChecker)
  pma.io.query.addr := reqAddr
  pma.io.query.sizeLog2 := reqSizeLog2
  pma.io.query.accessType := Mux(reqIsWrite || reqIsSc || reqIsAmo,
    PMAAccessType.Store, PMAAccessType.Load)

  // ===== SRAM read/write control =====
  val incomingSetIndex = io.cpu.req.addr(
    cfg.lineOffsetWidth + cfg.setIndexWidth - 1,
    cfg.lineOffsetWidth)
  val pendingCpuSetIndex = cpuPending.addr(
    cfg.lineOffsetWidth + cfg.setIndexWidth - 1,
    cfg.lineOffsetWidth)
  val flushSetIndex = flushIndex(flushIndexWidth - 1, cfg.wayIndexWidth)
  val probeSetIndex = probeLineAddrReg(
    cfg.lineOffsetWidth + cfg.setIndexWidth - 1,
    cfg.lineOffsetWidth)
  val probeTag = probeLineAddrReg(31, cfg.lineOffsetWidth + cfg.setIndexWidth)
  val cpuArrayRead = state === Idle && !probePendingValid &&
    (cpuPendingValid || io.cpu.req.valid)
  val arrayReadEnable = cpuArrayRead ||
    (state === FlushScan) || (state === ProbeRead)
  val cpuArrayReadAddr = Mux(cpuPendingValid, pendingCpuSetIndex, incomingSetIndex)
  val arrayReadAddr = MuxLookup(state, cpuArrayReadAddr)(Seq(
    Idle -> cpuArrayReadAddr,
    FlushScan -> flushSetIndex,
    ProbeRead -> probeSetIndex
  ))
  for (w <- 0 until ways) {
    tagArray(w).io.addr := arrayReadAddr
    tagArray(w).io.re := arrayReadEnable
    dataArray(w).io.addr := arrayReadAddr
    dataArray(w).io.re := arrayReadEnable
  }

  // ===== Lookup-stage comparison =====
  val wayHit = VecInit((0 until ways).map { w =>
    validOf(metaReg(setIndex))(w) && tagRdata(w) === requestTag
  })
  val hit = wayHit.asUInt.orR
  val hitWay = OHToUInt(wayHit.asUInt)

  val (newValidVec, newPlruVec, victimOH) =
    BreezePLRU.replace_way_select(validOf(metaReg(setIndex)), plruOf(metaReg(setIndex)))
  val victimWay = OHToUInt(victimOH)
  val victimIsValid = validOf(metaReg(setIndex))(victimWay)

  val readSelectBase = MuxLookup(reqSizeLog2, "hff".U(8.W))(Seq(
    0.U -> "h01".U(8.W),
    1.U -> "h03".U(8.W),
    2.U -> "h0f".U(8.W),
    3.U -> "hff".U(8.W)
  ))
  val scalarMask = Mux(
    reqIsWrite,
    reqWMask,
    (readSelectBase << reqAddr(2, 0))(7, 0)
  )

  // ===== Coherence sideband defaults and probe latch =====
  val coh = io.coherence
  coh.req.valid := false.B
  coh.req.opcode := BreezeCoherenceOpcode.GetS
  coh.req.srcHart := hartId.U
  coh.req.txnId := txnIdReg
  coh.req.lineAddr := requestLineBase32
  coh.req.hasData := false.B
  coh.req.lineData := victimDataReg

  coh.grant.ready := ((state === RefillWait) || (state === UpgradeWait) ||
    (state === PutWait) || (state === FlushWritebackWait)) && !probePendingValid

  coh.probe.ready := !probePendingValid
  when(coh.probe.valid && coh.probe.ready) {
    probePendingValid := true.B
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

  // A grant must answer the transaction that is currently outstanding.
  when(coh.grant.valid && coh.grant.ready) {
    assert(coh.grant.txnId === txnIdPendingReg, "DCache: grant txnId mismatch")
    assert(coh.grant.lineAddr === txnLineAddrReg, "DCache: grant lineAddr mismatch")
  }

  // ===== Outputs =====
  io.cpu.rsp.valid := state === Respond
  io.cpu.rsp.data := Mux(responseError, 0.U, responseData)
  io.cpu.rsp.isWriteAck := responseIsWrite && !responseError
  io.cpu.rsp.error := responseError
  io.cpu.rsp.pageFault := false.B
  io.cpu.rsp.faultAddr := 0.U

  io.flushDone := state === FlushRespond
  io.fatalError := fatalErrorReg

  io.mmioReq.req := false.B
  io.mmioReq.addr := 0.U
  io.mmioReq.isWrite := false.B
  io.mmioReq.isLine := false.B
  io.mmioReq.data := 0.U
  io.mmioReq.mask := 0.U

  io.hpm.dcacheAccess := cpuArrayRead && !io.flushReq
  io.hpm.dcacheMiss := state === Lookup && pma.io.result.allowed &&
    pma.io.result.cacheable && !pma.io.result.device && !hit
  io.hpm.dcacheUncached := state === Lookup && pma.io.result.allowed &&
    (!pma.io.result.cacheable || pma.io.result.device)

  io.trace.foreach { trace =>
    trace.requestValid := state === Lookup
    trace.responseValid := state === Respond
    trace.address := reqAddr
    trace.sizeLog2 := reqSizeLog2
    trace.isWrite := reqIsWrite
    trace.writeData := reqWData
    trace.mask := scalarMask
    trace.pmaAllowed := pma.io.result.allowed
    trace.pmaCacheable := pma.io.result.cacheable
    trace.pmaDevice := pma.io.result.device
    trace.cacheHit := hit
    trace.responseData := responseData
    trace.responseError := responseError
  }

  val cpuDirectAccept = state === Idle && !io.flushReq &&
    !probePendingValid && !cpuPendingValid
  val probeOnlyWindow =
    (state === Idle && probePendingValid) ||
    ((state === ProbeRead || state === ProbeCompare || state === ProbeRespond) &&
      resumeState === Idle)
  val cpuSkidAccept = probeOnlyWindow && !io.flushReq && !cpuPendingValid

  when(io.cpu.req.valid) {
    assert(cpuDirectAccept || cpuSkidAccept,
      "DCache CPU request pulse arrived while the blocking DCache was busy")
    when(cpuSkidAccept) {
      cpuPendingValid := true.B
      cpuPending := io.cpu.req
    }
  }
  when(io.flushReq) {
    assert(state === Idle,
      "DCache flush request pulse arrived while the blocking DCache was busy")
  }

  private def latchCpuRequest(req: BackendMemReq): Unit = {
    reqAddr := req.addr
    reqIsWrite := req.isWrite
    reqSizeLog2 := req.sizeLog2
    reqWData := req.wdata
    reqWMask := req.wmask
    reqMemOp := req.memOp
    reqAmoFunc := req.amoFunc
    responseData := 0.U
    responseError := false.B
    responseIsWrite := req.memOp === Store
    state := Lookup
  }

  /** Store-class metadata update: `way` becomes M, its E bit (if any) is
    * cleared, every other way keeps its state. */
  private def metaToM(set: UInt, way: UInt, touchPlru: Bool): Unit = {
    metaReg(set) := makeMeta(
      validOf(metaReg(set)) | (1.U << way),
      exclOf(metaReg(set)) & ~(1.U << way),
      dirtyOf(metaReg(set)) | (1.U << way),
      Mux(touchPlru, touchWay(plruOf(metaReg(set)), way), plruOf(metaReg(set)))
    )
  }

  switch(state) {
    is(Idle) {
      when(probePendingValid) {
        resumeState := Idle
        state := ProbeRead
      }.elsewhen(io.flushReq) {
        flushIndex := 0.U
        resValid := false.B
        state := FlushScan
      }.elsewhen(cpuPendingValid) {
        cpuPendingValid := false.B
        latchCpuRequest(cpuPending)
      }.elsewhen(io.cpu.req.valid) {
        latchCpuRequest(io.cpu.req)
      }
    }

    is(Lookup) {
      // Backend guarantees natural alignment for atomics; misalignment must
      // have trapped before reaching the cache.
      when(isAtomicOp) {
        assert(reqSizeLog2 === 2.U || reqSizeLog2 === 3.U,
          "DCache: atomic operation must be W or D")
        assert(Mux(reqSizeLog2 === 2.U, reqAddr(1, 0) === 0.U, reqAddr(2, 0) === 0.U),
          "DCache: atomic operation must be naturally aligned")
      }

      when(!pma.io.result.allowed) {
        responseError := true.B
        state := Respond
      }.elsewhen(!pma.io.result.cacheable || pma.io.result.device) {
        when(isAtomicOp) {
          // Atomics are not supported on device/non-cacheable regions.
          responseError := true.B
          state := Respond
        }.otherwise {
          state := UncachedReq
        }
      }.elsewhen(reqIsSc) {
        // SC never allocates: a reservation can only be valid while its line
        // is still resident (eviction clears it), so a miss means failure.
        val scMatch = resValid && resAddr === reqAddr && resSizeLog2 === reqSizeLog2
        when(!scMatch || !hit) {
          responseData := 1.U
          responseError := false.B
          resValid := false.B
          state := Respond
        }.otherwise {
          val hitExcl = exclOf(metaReg(setIndex))(hitWay)
          val hitDirty = dirtyOf(metaReg(setIndex))(hitWay)
          when(hitDirty || hitExcl) {
            // M/E hit: the write is the SC success point.
            storeMergeReg := mergeStore(dataRdata(hitWay), reqAddr, reqWData, reqWMask)
            storeHitWayReg := hitWay
            metaToM(setIndex, hitWay, touchPlru = true.B)
            responseData := 0.U
            resValid := false.B
            state := StoreHitWrite
          }.otherwise {
            // S hit: M must be obtained first; the reservation is re-checked
            // when the grant arrives.
            storeHitWayReg := hitWay
            upgradeLineReg := dataRdata(hitWay)
            state := UpgradeReq
          }
        }
      }.elsewhen(hit) {
        when(reqIsAmo) {
          val hitExcl = exclOf(metaReg(setIndex))(hitWay)
          val hitDirty = dirtyOf(metaReg(setIndex))(hitWay)
          when(hitDirty || hitExcl) {
            // M/E hit: read-modify-write completes locally; probes latched
            // during this window are serviced after the write (atomicLock).
            amoOldWord := lineWord(dataRdata(hitWay), reqAddr)
            storeMergeReg := amoMergedLine(dataRdata(hitWay))
            storeHitWayReg := hitWay
            metaToM(setIndex, hitWay, touchPlru = true.B)
            responseData := amoOldWord
            state := StoreHitWrite
          }.otherwise {
            storeHitWayReg := hitWay
            upgradeLineReg := dataRdata(hitWay)
            state := UpgradeReq
          }
        }.elsewhen(reqIsWrite) {
          val hitExcl = exclOf(metaReg(setIndex))(hitWay)
          val hitDirty = dirtyOf(metaReg(setIndex))(hitWay)
          storeMergeReg := mergeStore(dataRdata(hitWay), reqAddr, reqWData, reqWMask)
          storeHitWayReg := hitWay
          when(hitDirty || hitExcl) {
            // M hit writes directly; an E hit silently upgrades E->M.
            metaToM(setIndex, hitWay, touchPlru = true.B)
            responseData := 0.U
            state := StoreHitWrite
          }.otherwise {
            // S hit: a GetM upgrade is required before writing.
            upgradeLineReg := dataRdata(hitWay)
            state := UpgradeReq
          }
        }.otherwise {
          // Load / LR hit.
          metaReg(setIndex) := makeMeta(
            validOf(metaReg(setIndex)),
            exclOf(metaReg(setIndex)),
            dirtyOf(metaReg(setIndex)),
            touchWay(plruOf(metaReg(setIndex)), hitWay)
          )
          responseData := lineWord(dataRdata(hitWay), reqAddr)
          when(reqIsLr) {
            resValid := true.B
            resAddr := reqAddr
            resSizeLog2 := reqSizeLog2
            resLineAddr := reqAddr(31, cfg.lineOffsetWidth)
          }
          state := Respond
        }
        responseError := false.B
      }.otherwise {
        // Miss: evict the victim through the directory, then allocate.
        victimWayReg := victimWay
        newPlruReg := newPlruVec
        victimTagReg := tagRdata(victimWay)
        victimDataReg := dataRdata(victimWay)
        // Evicting the reservation line kills the reservation.
        when(enableAtomics.B && victimIsValid &&
            Cat(tagRdata(victimWay), setIndex) === resLineAddr) {
          resValid := false.B
        }
        when(!victimIsValid) {
          state := RefillReq
        }.otherwise {
          state := PutReq
        }
      }
    }

    is(StoreHitWrite) {
      for (w <- 0 until ways) {
        when(storeHitWayReg === w.U) {
          dataArray(w).io.we := true.B
          dataArray(w).io.addr := setIndex
          dataArray(w).io.data_in := storeMergeReg
        }
      }
      state := Respond
    }

    is(UncachedReq) {
      io.mmioReq.req := true.B
      io.mmioReq.addr := requestBeatBase64
      io.mmioReq.isWrite := reqIsWrite
      io.mmioReq.isLine := false.B
      io.mmioReq.data := reqWData.pad(cfg.lineWidth)
      io.mmioReq.mask := scalarMask.pad(cfg.lineBytes)
      state := UncachedWait
    }

    is(UncachedWait) {
      when(io.mmioRsp.vld) {
        responseData := io.mmioRsp.data(63, 0)
        responseError := io.mmioRsp.error
        state := Respond
      }
    }

    // ===== Coherent eviction: PutS/PutM release =====
    is(PutReq) {
      when(probePendingValid) {
        resumeState := PutReq
        state := ProbeRead
      }.otherwise {
        // Re-read the victim's current state: a probe serviced while this
        // request was parked may have taken the line meanwhile - then the
        // Put is cancelled (the line was surrendered via the probe response).
        val victimNowValid = validOf(metaReg(setIndex))(victimWayReg)
        val victimNowDirty = dirtyOf(metaReg(setIndex))(victimWayReg)
        when(!victimNowValid) {
          state := RefillReq
        }.otherwise {
          coh.req.valid := true.B
          coh.req.opcode := Mux(victimNowDirty, BreezeCoherenceOpcode.PutM,
            BreezeCoherenceOpcode.PutS)
          coh.req.txnId := txnIdReg
          coh.req.lineAddr := Cat(
            victimTagReg, setIndex, 0.U(cfg.lineOffsetWidth.W))
          coh.req.hasData := victimNowDirty
          coh.req.lineData := victimDataReg
          when(coh.req.ready) {
            txnIdPendingReg := txnIdReg
            txnIdReg := txnIdReg + 1.U
            txnLineAddrReg := Cat(
              victimTagReg, setIndex, 0.U(cfg.lineOffsetWidth.W))
            state := PutWait
          }
        }
      }
    }

    is(PutWait) {
      when(probePendingValid) {
        resumeState := PutWait
        state := ProbeRead
      }.elsewhen(coh.grant.valid) {
        when(coh.grant.error) {
          // The release failed: keep the victim, report the error.
          responseError := true.B
          state := Respond
        }.otherwise {
          // The victim slot is free now.
          metaReg(setIndex) := makeMeta(
            validOf(metaReg(setIndex)) & ~(1.U << victimWayReg),
            exclOf(metaReg(setIndex)) & ~(1.U << victimWayReg),
            dirtyOf(metaReg(setIndex)) & ~(1.U << victimWayReg),
            plruOf(metaReg(setIndex))
          )
          state := RefillReq
        }
      }
    }

    // ===== S->M upgrade (store/SC/AMO hit on an S line) =====
    is(UpgradeReq) {
      when(probePendingValid) {
        resumeState := UpgradeReq
        state := ProbeRead
      }.otherwise {
        coh.req.valid := true.B
        coh.req.opcode := BreezeCoherenceOpcode.GetM
        coh.req.txnId := txnIdReg
        coh.req.lineAddr := requestLineBase32
        coh.req.hasData := false.B
        when(coh.req.ready) {
          txnIdPendingReg := txnIdReg
          txnIdReg := txnIdReg + 1.U
          txnLineAddrReg := requestLineBase32
          state := UpgradeWait
        }
      }
    }

    is(UpgradeWait) {
      when(probePendingValid) {
        resumeState := UpgradeWait
        state := ProbeRead
      }.elsewhen(coh.grant.valid) {
        when(coh.grant.error) {
          responseError := true.B
          state := Respond
        }.otherwise {
          // Race rule: the Home sends data whenever this hart was no longer a
          // sharer at processing time (its S copy was invalidated by a probe
          // while the GetM waited). Without data the local copy must still be
          // valid.
          val baseLine = Mux(coh.grant.hasData, coh.grant.lineData, upgradeLineReg)
          assert(coh.grant.hasData || validOf(metaReg(setIndex))(storeHitWayReg),
            "DCache: dataless GrantM but the local S copy is gone")
          val scStillValid = resValid && resAddr === reqAddr &&
            resSizeLog2 === reqSizeLog2
          when(reqIsSc && !scStillValid) {
            // The reservation died while waiting (probe invalidation): fail
            // without writing. The granted ownership is still installed - the
            // directory made this hart the UNIQUE owner - as a clean E line.
            storeMergeReg := baseLine
            metaReg(setIndex) := makeMeta(
              validOf(metaReg(setIndex)) | (1.U << storeHitWayReg),
              exclOf(metaReg(setIndex)) | (1.U << storeHitWayReg),
              dirtyOf(metaReg(setIndex)) & ~(1.U << storeHitWayReg),
              plruOf(metaReg(setIndex))
            )
            responseData := 1.U
            resValid := false.B
            state := StoreHitWrite
          }.otherwise {
            when(reqIsAmo) {
              amoOldWord := lineWord(baseLine, reqAddr)
              storeMergeReg := amoMergedLine(baseLine)
              responseData := amoOldWord
            }.otherwise {
              // Store or successful SC.
              storeMergeReg := mergeStore(baseLine, reqAddr, reqWData, reqWMask)
              responseData := 0.U
            }
            when(reqIsSc) { resValid := false.B }
            metaToM(setIndex, storeHitWayReg, touchPlru = true.B)
            state := StoreHitWrite
          }
        }
      }
    }

    // ===== Miss allocation (GetS for load/LR, GetM for store/AMO) =====
    is(RefillReq) {
      when(probePendingValid) {
        resumeState := RefillReq
        state := ProbeRead
      }.otherwise {
        coh.req.valid := true.B
        coh.req.opcode := Mux(isStoreLike, BreezeCoherenceOpcode.GetM,
          BreezeCoherenceOpcode.GetS)
        coh.req.txnId := txnIdReg
        coh.req.lineAddr := requestLineBase32
        coh.req.hasData := false.B
        when(coh.req.ready) {
          txnIdPendingReg := txnIdReg
          txnIdReg := txnIdReg + 1.U
          txnLineAddrReg := requestLineBase32
          state := RefillWait
        }
      }
    }

    is(RefillWait) {
      when(probePendingValid) {
        resumeState := RefillWait
        state := ProbeRead
      }.elsewhen(coh.grant.valid) {
        when(coh.grant.error) {
          // Never overwrite the victim until the refill has completed.
          responseError := true.B
          state := Respond
        }.otherwise {
          assert(coh.grant.hasData, "DCache: refill grant without data")
          val grantedM = coh.grant.grantState === BreezeGrantState.M
          val grantedE = coh.grant.grantState === BreezeGrantState.E
          amoOldWord := lineWord(coh.grant.lineData, reqAddr)
          val installedLine = MuxCase(coh.grant.lineData, Seq(
            (reqMemOp === Store) ->
              mergeStore(coh.grant.lineData, reqAddr, reqWData, reqWMask),
            reqIsAmo -> amoMergedLine(coh.grant.lineData)
          ))
          // The valid bits are recomputed from the current metadata: a probe
          // serviced while the refill was in flight may have invalidated
          // another way of this set, and that invalidation must survive the
          // install. Only the victim way's state is replaced; every other way
          // keeps its M/E ownership and its dirty data.
          val newValidNow = validOf(metaReg(setIndex)) | (1.U << victimWayReg)
          val newExcl = Mux(grantedE && !isStoreLike,
            exclOf(metaReg(setIndex)) | (1.U << victimWayReg),
            exclOf(metaReg(setIndex)) & ~(1.U << victimWayReg))
          val newDirty = Mux(isStoreLike,
            dirtyOf(metaReg(setIndex)) | (1.U << victimWayReg),
            dirtyOf(metaReg(setIndex)) & ~(1.U << victimWayReg))
          when(isStoreLike) {
            assert(grantedM, "DCache: GetM answered without M permission")
          }
          for (w <- 0 until ways) {
            when(victimWayReg === w.U) {
              tagArray(w).io.we := true.B
              tagArray(w).io.addr := setIndex
              tagArray(w).io.data_in := requestTag
              dataArray(w).io.we := true.B
              dataArray(w).io.addr := setIndex
              dataArray(w).io.data_in := installedLine
            }
          }
          metaReg(setIndex) := makeMeta(newValidNow, newExcl, newDirty, newPlruReg)
          responseData := MuxCase(0.U, Seq(
            (reqMemOp === Load || reqIsLr) ->
              lineWord(coh.grant.lineData, reqAddr),
            reqIsAmo -> amoOldWord
          ))
          when(reqIsLr) {
            resValid := true.B
            resAddr := reqAddr
            resSizeLog2 := reqSizeLog2
            resLineAddr := reqAddr(31, cfg.lineOffsetWidth)
          }
          responseError := false.B
          state := Respond
        }
      }
    }

    // ===== Probe service =====
    is(ProbeRead) {
      // The array read is asserted combinationally for the probe's set.
      state := ProbeCompare
    }

    is(ProbeCompare) {
      val pHitVec = VecInit((0 until ways).map { w =>
        validOf(metaReg(probeSetIndex))(w) && tagRdata(w) === probeTag
      })
      val pHit = pHitVec.asUInt.orR
      val pHitWay = OHToUInt(pHitVec.asUInt)
      val pDirty = dirtyOf(metaReg(probeSetIndex))(pHitWay)

      probeDataReg := dataRdata(pHitWay)
      // Only a dirty (M) line carries data back: a clean E copy is identical
      // to the Home's own data by construction.
      probeHasDataReg := pHit && pDirty

      // Any probe against the reservation line clears the reservation
      // (conservative line granule, spec section 15), hit or not.
      when(enableAtomics.B &&
          probeLineAddrReg(31, cfg.lineOffsetWidth) === resLineAddr) {
        resValid := false.B
      }

      when(pHit) {
        val invalidate = probeOpcodeReg === BreezeProbeOpcode.ProbeInv ||
          probeOpcodeReg === BreezeProbeOpcode.ProbeRecallInv
        val newDirty = dirtyOf(metaReg(probeSetIndex)) & ~(1.U << pHitWay)
        val newExcl = exclOf(metaReg(probeSetIndex)) & ~(1.U << pHitWay)
        metaReg(probeSetIndex) := makeMeta(
          Mux(invalidate,
            validOf(metaReg(probeSetIndex)) & ~(1.U << pHitWay),
            validOf(metaReg(probeSetIndex))),
          newExcl,
          newDirty,
          plruOf(metaReg(probeSetIndex))
        )
      }
      state := ProbeRespond
    }

    is(ProbeRespond) {
      when(coh.probeResp.ready) {
        probePendingValid := false.B
        state := resumeState
      }
    }

    is(Respond) {
      // Conservative reservation clear on the completion of any store-class
      // operation (spec section 15 allows this).
      when(enableAtomics.B && (reqMemOp === Store || reqIsSc || reqIsAmo)) {
        resValid := false.B
      }
      state := Idle
    }

    // ===== FENCE.I full flush: release every valid line via the directory =====
    is(FlushScan) {
      // Array read is asserted combinationally above for the current flush set.
      state := FlushRead
    }

    is(FlushRead) {
      val set = flushIndex(flushIndexWidth - 1, cfg.wayIndexWidth)
      val way = flushIndex(cfg.wayIndexWidth - 1, 0)
      val lineValid = validOf(metaReg(set))(way)
      when(lineValid) {
        victimWayReg := way
        victimTagReg := tagRdata(way)
        victimDataReg := dataRdata(way)
        state := FlushWritebackReq
      }.otherwise {
        when(flushIndex === flushLast) {
          state := FlushRespond
        }.otherwise {
          flushIndex := flushIndex + 1.U
          state := FlushScan
        }
      }
    }

    is(FlushWritebackReq) {
      when(probePendingValid) {
        resumeState := FlushWritebackReq
        state := ProbeRead
      }.otherwise {
        val set = flushIndex(flushIndexWidth - 1, cfg.wayIndexWidth)
        val way = flushIndex(cfg.wayIndexWidth - 1, 0)
        // Re-read the current line state: a probe serviced while this request
        // was parked may have taken the line meanwhile (cancel, don't resume).
        val lineValidNow = validOf(metaReg(set))(way)
        val lineDirtyNow = dirtyOf(metaReg(set))(way)
        when(!lineValidNow) {
          when(flushIndex === flushLast) {
            state := FlushRespond
          }.otherwise {
            flushIndex := flushIndex + 1.U
            state := FlushScan
          }
        }.otherwise {
          coh.req.valid := true.B
          coh.req.opcode := Mux(lineDirtyNow, BreezeCoherenceOpcode.PutM,
            BreezeCoherenceOpcode.PutS)
          coh.req.txnId := txnIdReg
          coh.req.lineAddr := Cat(
            victimTagReg, set, 0.U(cfg.lineOffsetWidth.W))
          coh.req.hasData := lineDirtyNow
          coh.req.lineData := victimDataReg
          when(coh.req.ready) {
            txnIdPendingReg := txnIdReg
            txnIdReg := txnIdReg + 1.U
            txnLineAddrReg := Cat(
              victimTagReg, set, 0.U(cfg.lineOffsetWidth.W))
            state := FlushWritebackWait
          }
        }
      }
    }

    is(FlushWritebackWait) {
      when(probePendingValid) {
        resumeState := FlushWritebackWait
        state := ProbeRead
      }.elsewhen(coh.grant.valid) {
        when(coh.grant.error) {
          // A retired store can no longer take a precise exception here.
          fatalErrorReg := true.B
          state := Fatal
        }.otherwise {
          val set = flushIndex(flushIndexWidth - 1, cfg.wayIndexWidth)
          val way = flushIndex(cfg.wayIndexWidth - 1, 0)
          metaReg(set) := makeMeta(
            validOf(metaReg(set)) & ~(1.U << way),
            exclOf(metaReg(set)) & ~(1.U << way),
            dirtyOf(metaReg(set)) & ~(1.U << way),
            plruOf(metaReg(set))
          )
          when(flushIndex === flushLast) {
            state := FlushRespond
          }.otherwise {
            flushIndex := flushIndex + 1.U
            state := FlushScan
          }
        }
      }
    }

    is(FlushRespond) {
      state := Idle
    }

    is(Fatal) {
      // Sticky until reset.
      state := Fatal
    }
  }

  // Trap-time reservation kill has the last word (a same-cycle LR cannot
  // retire when the backend is trapping).
  when(enableAtomics.B && io.resKill) {
    resValid := false.B
  }
}
