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
      WritebackReq, WritebackWait,
      RefillReq, RefillWait,
      UpgradeReq, UpgradeWait,
      PutReq, PutWait,
      ProbeRead, ProbeCompare, ProbeRespond,
      Respond,
      FlushScan, FlushRead, FlushWritebackReq, FlushWritebackWait, FlushRespond,
      Fatal = Value
}

/** Blocking, 4-way set-associative, write-back/write-allocate L1 data cache.
  *
  * Frozen geometry: 8192 B capacity, 32 B lines, 4 ways -> 64 sets.
  * Tag/Data arrays use synchronous-read SRAMs (one-cycle read latency); the
  * valid/dirty(/exclusive)/PLRU metadata is resettable register memory, so
  * every line is logically invalid after reset without relying on SRAM
  * contents.
  *
  * Policy:
  *   - write-back and write-allocate;
  *   - invalid-first, then tree-PLRU replacement within a set;
  *   - one outstanding CPU operation and one outstanding lower-level request;
  *   - PMA-denied accesses return an access error without reaching the bus;
  *   - non-cacheable/device accesses bypass the arrays as one scalar request;
  *   - a dirty victim is never overwritten until its writeback is acknowledged.
  *
  * The CPU and uncached lower-level interfaces retain the project's pulse
  * protocol. Address slicing follows the frozen profile: offset=addr[4:0],
  * set=addr[10:5], tag=addr[31:11] (32-bit physical tag; PMA rejects wider
  * addresses first).
  *
  * Two lower-level modes:
  *   - legacy (`coherent = false`, default): cached misses/dirty evictions use
  *     the blocking line pulse interface (nextLevelReq/nextLevelRsp) exactly as
  *     the historical single-core design. This mode is bit-compatible with the
  *     pre-coherence DCache and is what `BreezeCoreWishbone` instantiates.
  *   - coherent (`coherent = true`): cached operations go through the MESI
  *     coherence sideband (GetS/GetM/PutS/PutM requests, grants, probes and
  *     probe responses). The MESI state is encoded on top of the existing
  *     metadata as valid/exclusive/dirty: I = !valid, S = valid && !excl &&
  *     !dirty, E = valid && excl && !dirty, M = valid && dirty. Uncached/MMIO
  *     accesses still use the scalar pulse interface (routed to the cluster
  *     MMIO arbiter by the tile).
  *
  * Coherent-mode probe discipline (spec section 7.4): an incoming probe is
  * latched into a one-entry pending register (probe.ready =
  * !probePendingValid); probes are serviced with priority in Idle and in every
  * state that waits for the Home, and the interrupted state is resumed via
  * `resumeState`. A local coherence request that has not been handshaken yet
  * is simply re-asserted after the probe service (ready/valid withdrawal is
  * legal). Because the historical CPU interface is a pulse without `ready`,
  * one CPU request that arrives while an otherwise-idle cache services an
  * unsolicited probe is held in a one-entry skid register and started after
  * the probe response. A second CPU request is still a protocol violation.
  */
class BreezeDCache(
    val cfg: DefaultDCacheConfig = DefaultDCacheConfig(),
    val coherent: Boolean = false,
    val hartId: Int = 0,
    val hartIdWidth: Int = 1
) extends Module {
  private val ways = cfg.ways
  private val sets = cfg.sets
  private val wordsPerLine = cfg.lineBytes / 8
  private val wordIndexWidth = math.max(1, log2Ceil(wordsPerLine))

  require(cfg.VLEN == 64, "The Breeze DCache requires RV64")
  require(ways == 4, "The Breeze DCache PLRU is fixed to 4 ways")
  // The address slicing below is frozen to tag=addr[31:11], set=addr[10:5],
  // offset=addr[4:0]; other legal DefaultDCacheConfig geometries would be
  // silently mis-sliced, so reject them at elaboration time.
  require(cfg.sets == 64 && cfg.lineBytes == 32,
    "The Breeze DCache address slicing is frozen to 64 sets and 32 B lines")
  require(hartId >= 0 && hartId < (1 << hartIdWidth), "DCache hartId out of range")

  // Metadata layout: [plru(3) | dirty(4) | (excl(4)) | valid(4)] per set.
  private val validBits = ways
  private val exclBits = if (coherent) ways else 0
  private val exclOffset = validBits
  private val dirtyOffset = validBits + exclBits
  private val plruOffset = 2 * ways + exclBits
  private val metaWidth = 2 * ways + exclBits + (ways - 1)

  private def validOf(meta: UInt): UInt = meta(validBits - 1, 0)
  private def exclOf(meta: UInt): UInt =
    if (coherent) meta(exclOffset + ways - 1, exclOffset) else 0.U(ways.W)
  private def dirtyOf(meta: UInt): UInt = meta(dirtyOffset + ways - 1, dirtyOffset)
  private def plruOf(meta: UInt): UInt = meta(metaWidth - 1, plruOffset)
  private def makeMeta(valid: UInt, excl: UInt, dirty: UInt, plru: UInt): UInt = {
    val exclPart = if (coherent) excl else 0.U(0.W)
    Cat(plru, dirty, exclPart, valid)
  }

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
    val fatalError = Output(Bool())
    val hpm = Output(new BreezeHpmEvents)
    val nextLevelReq = new DCacheMemReqIO(cfg.PLEN, cfg.lineBytes)
    val nextLevelRsp = new DCacheMemRespIO(cfg.lineBytes)
    // Coherence sideband (only present in coherent mode). Directions as seen
    // from the L1D: req and probeResp are driven by this module; grant and
    // probe arrive from the Home.
    val coherence = if (coherent) Some(new Bundle {
      val req = new BreezeCoherenceReqIO(32, cfg.lineBytes, hartIdWidth)
      val grant = Flipped(new BreezeCoherenceGrantIO(32, cfg.lineBytes, hartIdWidth))
      val probe = Flipped(new BreezeCoherenceProbeIO(32, hartIdWidth))
      val probeResp = new BreezeCoherenceProbeRespIO(32, cfg.lineBytes, hartIdWidth)
    }) else None
  })

  import BreezeDCacheState._

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

  // An unsolicited coherent probe can temporarily occupy an otherwise-idle
  // cache. Preserve the one CPU request pulse that can legally race it.
  val cpuPendingValid = RegInit(false.B)
  val cpuPendingAddr = RegInit(0.U(cfg.VLEN.W))
  val cpuPendingIsWrite = RegInit(false.B)
  val cpuPendingSizeLog2 = RegInit(0.U(3.W))
  val cpuPendingWData = RegInit(0.U(64.W))
  val cpuPendingWMask = RegInit(0.U(8.W))

  val victimWayReg = RegInit(0.U(cfg.wayIndexWidth.W))
  val newValidReg = RegInit(0.U(ways.W))
  val newPlruReg = RegInit(0.U((ways - 1).W))
  val victimTagReg = RegInit(0.U(cfg.tagWidth.W))
  val victimDataReg = RegInit(0.U(cfg.lineWidth.W))
  val storeMergeReg = RegInit(0.U(cfg.lineWidth.W))
  val storeHitWayReg = RegInit(0.U(cfg.wayIndexWidth.W))

  val responseData = RegInit(0.U(64.W))
  val responseError = RegInit(false.B)
  val responseIsWrite = RegInit(false.B)
  val flushIndex = RegInit(0.U(8.W))
  val fatalErrorReg = RegInit(false.B)

  // Coherence transaction bookkeeping.
  val txnIdReg = RegInit(0.U(2.W))
  // The id the in-flight request was sent with; a grant must echo this one
  // (txnIdReg itself has already moved on to the next id by then).
  val txnIdPendingReg = RegInit(0.U(2.W))
  val txnLineAddrReg = RegInit(0.U(32.W))
  val probePendingValid = RegInit(false.B)
  val probeTxnIdReg = RegInit(0.U(2.W))
  val probeLineAddrReg = RegInit(0.U(32.W))
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

  // ===== Address decode (frozen slicing) =====
  val setIndex = reqAddr(10, 5)
  val requestTag = reqAddr(31, 11)
  val requestLineBase64 = Cat(reqAddr(63, 5), 0.U(5.W))
  val requestBeatBase64 = Cat(reqAddr(63, 3), 0.U(3.W))
  val requestLineBase32 = requestLineBase64(31, 0)

  // ===== PMA =====
  val pma = Module(new PMAChecker)
  pma.io.query.addr := reqAddr
  pma.io.query.sizeLog2 := reqSizeLog2
  pma.io.query.accessType := Mux(reqIsWrite, PMAAccessType.Store, PMAAccessType.Load)

  // ===== SRAM read/write control =====
  val incomingSetIndex = io.cpu.req.addr(10, 5)
  val pendingCpuSetIndex = cpuPendingAddr(10, 5)
  val flushSetIndex = flushIndex(7, 2)
  val probeSetIndex = probeLineAddrReg(10, 5)
  val probeTag = probeLineAddrReg(31, 11)
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
  val victimIsDirty = dirtyOf(metaReg(setIndex))(victimWay)

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
  if (coherent) {
    val coh = io.coherence.get
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
  }

  // ===== Outputs =====
  io.cpu.rsp.valid := state === Respond
  io.cpu.rsp.data := Mux(responseError, 0.U, responseData)
  io.cpu.rsp.isWriteAck := responseIsWrite && !responseError
  io.cpu.rsp.error := responseError

  io.flushDone := state === FlushRespond
  io.fatalError := fatalErrorReg

  io.nextLevelReq.req := false.B
  io.nextLevelReq.addr := 0.U
  io.nextLevelReq.isWrite := false.B
  io.nextLevelReq.isLine := false.B
  io.nextLevelReq.data := 0.U
  io.nextLevelReq.mask := 0.U

  io.hpm.dcacheAccess := cpuArrayRead && !io.flushReq
  io.hpm.dcacheMiss := state === Lookup && pma.io.result.allowed &&
    pma.io.result.cacheable && !pma.io.result.device && !hit
  io.hpm.dcacheUncached := state === Lookup && pma.io.result.allowed &&
    (!pma.io.result.cacheable || pma.io.result.device)

  val cpuDirectAccept = state === Idle && !io.flushReq &&
    !probePendingValid && !cpuPendingValid
  val probeOnlyWindow = coherent.B && (
    (state === Idle && probePendingValid) ||
    ((state === ProbeRead || state === ProbeCompare || state === ProbeRespond) &&
      resumeState === Idle))
  val cpuSkidAccept = probeOnlyWindow && !io.flushReq && !cpuPendingValid

  when(io.cpu.req.valid) {
    assert(cpuDirectAccept || cpuSkidAccept,
      "DCache CPU request pulse arrived while the blocking DCache was busy")
    when(cpuSkidAccept) {
      cpuPendingValid := true.B
      cpuPendingAddr := io.cpu.req.addr
      cpuPendingIsWrite := io.cpu.req.isWrite
      cpuPendingSizeLog2 := io.cpu.req.sizeLog2
      cpuPendingWData := io.cpu.req.wdata
      cpuPendingWMask := io.cpu.req.wmask
    }
  }
  when(io.flushReq) {
    assert(state === Idle,
      "DCache flush request pulse arrived while the blocking DCache was busy")
  }

  switch(state) {
    is(Idle) {
      when(probePendingValid) {
        resumeState := Idle
        state := ProbeRead
      }.elsewhen(io.flushReq) {
        flushIndex := 0.U
        state := FlushScan
      }.elsewhen(cpuPendingValid) {
        reqAddr := cpuPendingAddr
        reqIsWrite := cpuPendingIsWrite
        reqSizeLog2 := cpuPendingSizeLog2
        reqWData := cpuPendingWData
        reqWMask := cpuPendingWMask
        responseData := 0.U
        responseError := false.B
        responseIsWrite := cpuPendingIsWrite
        cpuPendingValid := false.B
        state := Lookup
      }.elsewhen(io.cpu.req.valid) {
        reqAddr := io.cpu.req.addr
        reqIsWrite := io.cpu.req.isWrite
        reqSizeLog2 := io.cpu.req.sizeLog2
        reqWData := io.cpu.req.wdata
        reqWMask := io.cpu.req.wmask
        responseData := 0.U
        responseError := false.B
        responseIsWrite := io.cpu.req.isWrite
        state := Lookup
      }
    }

    is(Lookup) {
      when(!pma.io.result.allowed) {
        responseError := true.B
        state := Respond
      }.elsewhen(!pma.io.result.cacheable || pma.io.result.device) {
        state := UncachedReq
      }.elsewhen(hit) {
        when(reqIsWrite) {
          val hitExcl = exclOf(metaReg(setIndex))(hitWay)
          val hitDirty = dirtyOf(metaReg(setIndex))(hitWay)
          storeMergeReg := mergeStore(dataRdata(hitWay), reqAddr, reqWData, reqWMask)
          storeHitWayReg := hitWay
          when(!coherent.B || hitDirty || hitExcl) {
            // M hit writes directly; an E hit silently upgrades E->M.
            metaReg(setIndex) := makeMeta(
              validOf(metaReg(setIndex)),
              0.U(ways.W),
              dirtyOf(metaReg(setIndex)) | (1.U << hitWay),
              touchWay(plruOf(metaReg(setIndex)), hitWay)
            )
            responseData := 0.U
            state := StoreHitWrite
          }.otherwise {
            // S hit: a GetM upgrade is required before writing.
            state := UpgradeReq
          }
        }.otherwise {
          metaReg(setIndex) := makeMeta(
            validOf(metaReg(setIndex)),
            exclOf(metaReg(setIndex)),
            dirtyOf(metaReg(setIndex)),
            touchWay(plruOf(metaReg(setIndex)), hitWay)
          )
          responseData := lineWord(dataRdata(hitWay), reqAddr)
          state := Respond
        }
        responseError := false.B
      }.otherwise {
        victimWayReg := victimWay
        newValidReg := newValidVec
        newPlruReg := newPlruVec
        victimTagReg := tagRdata(victimWay)
        victimDataReg := dataRdata(victimWay)
        when(!coherent.B) {
          when(victimIsDirty) {
            state := WritebackReq
          }.otherwise {
            state := RefillReq
          }
        }.otherwise {
          // Coherent eviction releases the victim through the directory first.
          when(!victimIsValid) {
            state := RefillReq
          }.otherwise {
            state := PutReq
          }
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
      io.nextLevelReq.req := true.B
      io.nextLevelReq.addr := requestBeatBase64
      io.nextLevelReq.isWrite := reqIsWrite
      io.nextLevelReq.isLine := false.B
      io.nextLevelReq.data := reqWData.pad(cfg.lineWidth)
      io.nextLevelReq.mask := scalarMask.pad(cfg.lineBytes)
      state := UncachedWait
    }

    is(UncachedWait) {
      when(io.nextLevelRsp.vld) {
        responseData := io.nextLevelRsp.data(63, 0)
        responseError := io.nextLevelRsp.error
        state := Respond
      }
    }

    // ===== Legacy (non-coherent) line writeback =====
    is(WritebackReq) {
      io.nextLevelReq.req := true.B
      io.nextLevelReq.addr := Cat(0.U(32.W), victimTagReg, setIndex, 0.U(5.W))
      io.nextLevelReq.isWrite := true.B
      io.nextLevelReq.isLine := true.B
      io.nextLevelReq.data := victimDataReg
      io.nextLevelReq.mask := Fill(cfg.lineBytes, 1.U(1.W))
      state := WritebackWait
    }

    is(WritebackWait) {
      when(io.nextLevelRsp.vld) {
        when(io.nextLevelRsp.error) {
          // Keep the line valid+dirty: the data must not be lost.
          responseError := true.B
          state := Respond
        }.otherwise {
          state := RefillReq
        }
      }
    }

    // ===== Coherent eviction: PutS/PutM release =====
    is(PutReq) {
      if (coherent) {
        val coh = io.coherence.get
        when(probePendingValid) {
          resumeState := PutReq
          state := ProbeRead
        }.otherwise {
          // Re-read the victim's current state: a probe serviced while this
          // request was parked may have invalidated the victim meanwhile.
          val victimNowValid = validOf(metaReg(setIndex))(victimWayReg)
          val victimNowDirty = dirtyOf(metaReg(setIndex))(victimWayReg)
          when(!victimNowValid) {
            state := RefillReq
          }.otherwise {
            coh.req.valid := true.B
            coh.req.opcode := Mux(victimNowDirty, BreezeCoherenceOpcode.PutM,
              BreezeCoherenceOpcode.PutS)
            coh.req.txnId := txnIdReg
            coh.req.lineAddr := Cat(victimTagReg, setIndex, 0.U(5.W))
            coh.req.hasData := victimNowDirty
            coh.req.lineData := victimDataReg
            when(coh.req.ready) {
              txnIdPendingReg := txnIdReg
              txnIdReg := txnIdReg + 1.U
              txnLineAddrReg := Cat(victimTagReg, setIndex, 0.U(5.W))
              state := PutWait
            }
          }
        }
      } else {
        state := Fatal // unreachable in legacy mode
      }
    }

    is(PutWait) {
      if (coherent) {
        val coh = io.coherence.get
        when(probePendingValid) {
          resumeState := PutWait
          state := ProbeRead
        }.elsewhen(coh.grant.valid) {
          when(coh.grant.error) {
            // The release failed: keep the victim, report the error.
            responseError := true.B
            state := Respond
          }.otherwise {
            state := RefillReq
          }
        }
      } else {
        state := Fatal
      }
    }

    // ===== Coherent S->M upgrade for a store hit on an S line =====
    is(UpgradeReq) {
      if (coherent) {
        val coh = io.coherence.get
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
      } else {
        state := Fatal
      }
    }

    is(UpgradeWait) {
      if (coherent) {
        val coh = io.coherence.get
        when(probePendingValid) {
          resumeState := UpgradeWait
          state := ProbeRead
        }.elsewhen(coh.grant.valid) {
          when(coh.grant.error) {
            responseError := true.B
            state := Respond
          }.otherwise {
            // Upgraded to M: the line is now exclusively writable.
            metaReg(setIndex) := makeMeta(
              validOf(metaReg(setIndex)),
              0.U(ways.W),
              dirtyOf(metaReg(setIndex)) | (1.U << storeHitWayReg),
              plruOf(metaReg(setIndex))
            )
            state := StoreHitWrite
          }
        }
      } else {
        state := Fatal
      }
    }

    is(RefillReq) {
      if (coherent) {
        val coh = io.coherence.get
        when(probePendingValid) {
          resumeState := RefillReq
          state := ProbeRead
        }.otherwise {
          coh.req.valid := true.B
          coh.req.opcode := Mux(reqIsWrite, BreezeCoherenceOpcode.GetM,
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
      } else {
        io.nextLevelReq.req := true.B
        io.nextLevelReq.addr := requestLineBase64
        io.nextLevelReq.isWrite := false.B
        io.nextLevelReq.isLine := true.B
        io.nextLevelReq.data := 0.U
        io.nextLevelReq.mask := Fill(cfg.lineBytes, 1.U(1.W))
        state := RefillWait
      }
    }

    is(RefillWait) {
      if (coherent) {
        val coh = io.coherence.get
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
            val installedLine = Mux(
              reqIsWrite,
              mergeStore(coh.grant.lineData, reqAddr, reqWData, reqWMask),
              coh.grant.lineData
            )
            // The valid bits are recomputed from the current metadata: a probe
            // serviced while the refill was in flight may have invalidated
            // another way of this set, and that invalidation must survive the
            // install. (The PLRU result from Lookup stays valid because probe
            // handling never touches the PLRU bits.) The dirty/exclusive bits
            // of the other ways must be preserved exactly the same way: only
            // the victim way's state is replaced, every other way keeps its
            // M/E ownership and its dirty data.
            val newValidNow = validOf(metaReg(setIndex)) | (1.U << victimWayReg)
            val newExcl = Mux(grantedE,
              exclOf(metaReg(setIndex)) | (1.U << victimWayReg),
              exclOf(metaReg(setIndex)) & ~(1.U << victimWayReg))
            val newDirty = Mux(reqIsWrite || grantedM,
              dirtyOf(metaReg(setIndex)) | (1.U << victimWayReg),
              dirtyOf(metaReg(setIndex)) & ~(1.U << victimWayReg))
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
            responseData := Mux(reqIsWrite, 0.U, lineWord(coh.grant.lineData, reqAddr))
            responseError := false.B
            state := Respond
          }
        }
      } else {
        when(io.nextLevelRsp.vld) {
          when(io.nextLevelRsp.error) {
            // Never overwrite the victim until the refill has completed.
            responseError := true.B
            state := Respond
          }.otherwise {
            val installedLine = Mux(
              reqIsWrite,
              mergeStore(io.nextLevelRsp.data, reqAddr, reqWData, reqWMask),
              io.nextLevelRsp.data
            )
            val newDirty = Mux(
              reqIsWrite,
              dirtyOf(metaReg(setIndex)) | (1.U << victimWayReg),
              dirtyOf(metaReg(setIndex)) & ~(1.U << victimWayReg)
            )
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
            metaReg(setIndex) := makeMeta(newValidReg, 0.U(ways.W), newDirty, newPlruReg)
            responseData := Mux(reqIsWrite, 0.U, lineWord(io.nextLevelRsp.data, reqAddr))
            responseError := false.B
            state := Respond
          }
        }
      }
    }

    // ===== Probe service =====
    is(ProbeRead) {
      // The array read is asserted combinationally for the probe's set.
      state := ProbeCompare
    }

    is(ProbeCompare) {
      if (coherent) {
        val pHitVec = VecInit((0 until ways).map { w =>
          validOf(metaReg(probeSetIndex))(w) && tagRdata(w) === probeTag
        })
        val pHit = pHitVec.asUInt.orR
        val pHitWay = OHToUInt(pHitVec.asUInt)
        val pDirty = dirtyOf(metaReg(probeSetIndex))(pHitWay)
        val pExcl = exclOf(metaReg(probeSetIndex))(pHitWay)
        val pValid = validOf(metaReg(probeSetIndex))(pHitWay)

        probeDataReg := dataRdata(pHitWay)
        probeHasDataReg := pHit && MuxLookup(probeOpcodeReg, false.B)(Seq(
          // A dirty line is recalled on invalidate/downgrade; a recall of the
          // UNIQUE owner always carries data (E or M).
          BreezeProbeOpcode.ProbeInv -> pDirty,
          BreezeProbeOpcode.ProbeToS -> pDirty,
          BreezeProbeOpcode.ProbeRecallInv -> (pDirty || pExcl)
        ))

        when(pHit) {
          val newValid = MuxLookup(probeOpcodeReg, true.B)(Seq(
            BreezeProbeOpcode.ProbeInv -> false.B,
            BreezeProbeOpcode.ProbeRecallInv -> false.B,
            BreezeProbeOpcode.ProbeToS -> true.B
          ))
          val newDirty = dirtyOf(metaReg(probeSetIndex)) & ~(1.U << pHitWay)
          val newExcl = exclOf(metaReg(probeSetIndex)) & ~(1.U << pHitWay)
          metaReg(probeSetIndex) := makeMeta(
            Mux(newValid,
              validOf(metaReg(probeSetIndex)),
              validOf(metaReg(probeSetIndex)) & ~(1.U << pHitWay)),
            newExcl,
            newDirty,
            plruOf(metaReg(probeSetIndex))
          )
          // A probe against the reservation line clears the reservation (P6).
        }
        state := ProbeRespond
      } else {
        state := Fatal
      }
    }

    is(ProbeRespond) {
      if (coherent) {
        val coh = io.coherence.get
        when(coh.probeResp.ready) {
          probePendingValid := false.B
          state := resumeState
        }
      } else {
        state := Fatal
      }
    }

    is(Respond) {
      state := Idle
    }

    is(FlushScan) {
      // Array read is asserted combinationally above for the current flush set.
      state := FlushRead
    }

    is(FlushRead) {
      val set = flushIndex(7, 2)
      val way = flushIndex(1, 0)
      val lineValid = validOf(metaReg(set))(way)
      val lineDirty = dirtyOf(metaReg(set))(way)
      when(lineValid && (lineDirty || coherent.B)) {
        // Legacy mode only writes back dirty lines; coherent mode releases
        // every valid line through the directory (PutM for M, PutS for S/E).
        victimWayReg := way
        victimTagReg := tagRdata(way)
        victimDataReg := dataRdata(way)
        state := FlushWritebackReq
      }.otherwise {
        metaReg(set) := makeMeta(
          validOf(metaReg(set)) & ~(1.U << way),
          0.U(ways.W),
          dirtyOf(metaReg(set)) & ~(1.U << way),
          plruOf(metaReg(set))
        )
        when(flushIndex === 255.U) {
          state := FlushRespond
        }.otherwise {
          flushIndex := flushIndex + 1.U
          state := FlushScan
        }
      }
    }

    is(FlushWritebackReq) {
      when(probePendingValid && coherent.B) {
        resumeState := FlushWritebackReq
        state := ProbeRead
      }.otherwise {
        when(!coherent.B) {
          io.nextLevelReq.req := true.B
          io.nextLevelReq.addr := Cat(0.U(32.W), victimTagReg, flushIndex(7, 2), 0.U(5.W))
          io.nextLevelReq.isWrite := true.B
          io.nextLevelReq.isLine := true.B
          io.nextLevelReq.data := victimDataReg
          io.nextLevelReq.mask := Fill(cfg.lineBytes, 1.U(1.W))
        }
        if (coherent) {
          val coh = io.coherence.get
          val set = flushIndex(7, 2)
          val way = flushIndex(1, 0)
          // Re-read the current line state: a probe serviced while this
          // request was parked may have invalidated the line meanwhile.
          val lineValidNow = validOf(metaReg(set))(way)
          val lineDirtyNow = dirtyOf(metaReg(set))(way)
          when(!lineValidNow) {
            when(flushIndex === 255.U) {
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
            coh.req.lineAddr := Cat(victimTagReg, set, 0.U(5.W))
            coh.req.hasData := lineDirtyNow
            coh.req.lineData := victimDataReg
            when(coh.req.ready) {
              txnIdPendingReg := txnIdReg
              txnIdReg := txnIdReg + 1.U
              txnLineAddrReg := Cat(victimTagReg, set, 0.U(5.W))
              state := FlushWritebackWait
            }
          }
        } else {
          state := FlushWritebackWait
        }
      }
    }

    is(FlushWritebackWait) {
      when(probePendingValid && coherent.B) {
        resumeState := FlushWritebackWait
        state := ProbeRead
      }.elsewhen(!coherent.B && io.nextLevelRsp.vld) {
        when(io.nextLevelRsp.error) {
          // A retired store can no longer take a precise exception here.
          fatalErrorReg := true.B
          state := Fatal
        }.otherwise {
          val set = flushIndex(7, 2)
          val way = flushIndex(1, 0)
          metaReg(set) := makeMeta(
            validOf(metaReg(set)) & ~(1.U << way),
            0.U(ways.W),
            dirtyOf(metaReg(set)) & ~(1.U << way),
            plruOf(metaReg(set))
          )
          when(flushIndex === 255.U) {
            state := FlushRespond
          }.otherwise {
            flushIndex := flushIndex + 1.U
            state := FlushScan
          }
        }
      }.elsewhen(coherent.B) {
        if (coherent) {
          val coh = io.coherence.get
          when(coh.grant.valid) {
            when(coh.grant.error) {
              fatalErrorReg := true.B
              state := Fatal
            }.otherwise {
              val set = flushIndex(7, 2)
              val way = flushIndex(1, 0)
              metaReg(set) := makeMeta(
                validOf(metaReg(set)) & ~(1.U << way),
                0.U(ways.W),
                dirtyOf(metaReg(set)) & ~(1.U << way),
                plruOf(metaReg(set))
              )
              when(flushIndex === 255.U) {
                state := FlushRespond
              }.otherwise {
                flushIndex := flushIndex + 1.U
                state := FlushScan
              }
            }
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
}
