package flow.cache

import chisel3._
import chisel3.util._
import flow.bus.{LiteXWishboneMasterIO, LiteXWishboneParameters, WishboneBurstType, WishboneCycleType}
import flow.config.L2CacheGeometry
import flow.interface.{L1CacheMissReqIO, L1CacheMissRespIO}
import flow.mem.flowSRAM
import flow.platform.BreezeMcuPlatform

object BreezeL2HomeState extends ChiselEnum {
  val Init, Idle, LookupRead, Compare,
      ProbeReq, ProbeWait, HitUpdate,
      VictimWrite, MemRead, ArrayUpdate,
      SendGrant, SendPutAck, SendError = Value
}

/** Directory state for one way of one L2 set.
  *
  * dirState encodes BreezeDirectoryState (NONE=0, SHARED=1, UNIQUE=2), so an
  * all-zero entry is exactly "invalid line, directory NONE" - which is what the
  * post-reset walker writes into every way of every set.
  */
class L2WayDir(val sharerWidth: Int, val hartIdWidth: Int) extends Bundle {
  val valid = Bool()
  val dirtyToMemory = Bool()
  val dirState = UInt(2.W)
  val sharers = UInt(sharerWidth.W)
  val ownerId = UInt(hartIdWidth.W)
}

/** One directory SRAM word: a way's tag and its directory state together.
  *
  * Keeping the two halves of a lookup in one word means a probe of the set
  * reads both with a single SRAM access, and a directory update rewrites
  * exactly one word - no read-modify-write of the neighbouring ways. Holding
  * the state in a register array indexed by setIndex instead costs
  * sets*ways*entryBits flip-flops plus a sets:1 mux at every access point,
  * which is what this layout replaces.
  */
class L2DirEntry(val tagWidth: Int, val sharerWidth: Int, val hartIdWidth: Int) extends Bundle {
  val dir = new L2WayDir(sharerWidth, hartIdWidth)
  val tag = UInt(tagWidth.W)
}

/** Single-bank, blocking, directory-based inclusive L2/Home.
  *
  * Frozen geometry from `L2CacheGeometry` (8 ways, 32 B lines, one bank). The
  * directory tracks NONE/SHARED/UNIQUE per line; UNIQUE never assumes the L2
  * data is current because an L1 owner may silently upgrade E->M. Exactly one
  * coherence transaction is outstanding at a time.
  *
  * Handshake discipline (rewritten after the W-01..W-09 audit findings):
  *   - coherenceReq(h).ready is asserted only to the hart the Idle arbiter
  *     actually selects, so simultaneous requests are never swallowed;
  *   - grant/probe valid signals are held with stable payloads until the
  *     corresponding ready fires;
  *   - I$ refill pulses are latched unconditionally into per-hart pending
  *     registers (the L1CacheMissReqIO contract), so a pulse that arrives
  *     while the Home is busy is not lost;
  *   - probe responses are matched against the pending bitmap and checked
  *     against txnId/lineAddr.
  *
  * Storage: tag and directory state share one SRAM word per way (`L2DirEntry`),
  * so a lookup is one SRAM read and a directory update one SRAM write. Only the
  * per-set replacement pointer stays in flip-flops. Because SyncReadMem has no
  * reset, the FSM starts in `Init` and clears every set before Idle accepts a
  * request - `sets` cycles after reset, during which coherenceReq.ready stays
  * low. Directory writes are recorded by `writeDir` and hit the array one cycle
  * later in `ArrayUpdate`, together with any registered data-array write.
  *
  * Memory beats advance the Wishbone word address per beat and the final read
  * beat is merged combinationally into the installed line (no stale-beat bug).
  * A failed victim writeback keeps the victim tag/data valid in the arrays and
  * leaves the line in dir=NONE/dirtyToMemory=1 (its L1 copies have already
  * been invalidated, so that is the only consistent directory state); the
  * requester receives an error grant and nothing is installed.
  */
class BreezeL2Home(
    val l2Cfg: L2CacheGeometry,
    val numHarts: Int,
    val physicalAddressWidth: Int = BreezeMcuPlatform.AddressWidth
) extends Module {
  private val ways = l2Cfg.ways
  private val sets = l2Cfg.sets
  private val hartIdWidth = math.max(1, log2Ceil(numHarts))
  private val sharerWidth = numHarts
  private val lineBytes = l2Cfg.lineBytes
  private val lineWidth = l2Cfg.lineWidth
  private val setIndexWidth = l2Cfg.setIndexWidth
  private val wayIndexWidth = l2Cfg.wayIndexWidth
  private val lineOffsetWidth = l2Cfg.lineOffsetWidth
  private val tagWidth = physicalAddressWidth - lineOffsetWidth - setIndexWidth
  private val beats = lineBytes / 8 // 32 B line = 4 beats of 64 bits
  private val beatIndexWidth = math.max(1, log2Ceil(beats))

  require(l2Cfg.banks == 1, "L2/Home must be single-bank")
  require(beats * 8 == lineBytes, "L2 line must be an integer number of 64-bit beats")

  val io = IO(new Bundle {
    val coherenceReq = Flipped(Vec(numHarts, new BreezeCoherenceReqIO(physicalAddressWidth, lineBytes, hartIdWidth)))
    val coherenceGrant = Vec(numHarts, new BreezeCoherenceGrantIO(physicalAddressWidth, lineBytes, hartIdWidth))
    val coherenceProbe = Vec(numHarts, new BreezeCoherenceProbeIO(physicalAddressWidth, hartIdWidth))
    val coherenceProbeResp = Flipped(Vec(numHarts, new BreezeCoherenceProbeRespIO(physicalAddressWidth, lineBytes, hartIdWidth)))
    val instrReq = Flipped(Vec(numHarts, new L1CacheMissReqIO(physicalAddressWidth)))
    val instrResp = Flipped(Vec(numHarts, new L1CacheMissRespIO(lineWidth)))
    val memoryWishbone = new LiteXWishboneMasterIO(LiteXWishboneParameters(physicalAddressWidth, 64))
  })

  import BreezeL2HomeState._
  import BreezeCoherenceOpcode._
  import BreezeDirectoryState._
  import BreezeProbeOpcode._

  // ===== Storage =====
  // Tag and directory state share one SRAM word per way (see L2DirEntry). The
  // only per-set state left in flip-flops is the replacement pointer, which has
  // exactly two access points and so costs one sets:1 mux, not seventeen.
  private def dirEntryType = new L2DirEntry(tagWidth, sharerWidth, hartIdWidth)
  private val dirEntryWidth = dirEntryType.getWidth
  val dirArray = Seq.fill(ways)(Module(new flowSRAM(sets, dirEntryWidth, "l2dir")))
  val dataArray = Seq.fill(ways)(Module(new flowSRAM(sets, lineWidth, "l2data")))
  val roundRobin = RegInit(VecInit(Seq.fill(sets)(0.U(math.max(1, wayIndexWidth).W))))
  for (w <- 0 until ways) {
    dirArray(w).io.addr := 0.U
    dirArray(w).io.data_in := 0.U
    dirArray(w).io.we := false.B
    dirArray(w).io.re := false.B
    dirArray(w).io.id := w.U
    dataArray(w).io.addr := 0.U
    dataArray(w).io.data_in := 0.U
    dataArray(w).io.we := false.B
    dataArray(w).io.re := false.B
    dataArray(w).io.id := w.U
  }
  val dirRdata = VecInit(dirArray.map(_.io.data_out.asTypeOf(dirEntryType)))
  val dataRdata = VecInit(dataArray.map(_.io.data_out))

  // ===== Transaction context (frozen until the transaction completes) =====
  // SyncReadMem has no reset, so the valid bits come up undefined. The FSM
  // starts in Init and clears every set before Idle can accept a request.
  val state = RegInit(Init)
  val nextState = WireDefault(state)
  val updateResumeState = RegInit(Idle)
  val initSet = RegInit(0.U(setIndexWidth.W))
  val reqHart = RegInit(0.U(hartIdWidth.W))
  val reqOp = RegInit(GetS)
  val reqTxnId = RegInit(0.U(2.W))
  val reqLineAddr = RegInit(0.U(physicalAddressWidth.W)) // line-aligned
  val reqIsInstr = RegInit(false.B)
  val reqData = RegInit(0.U(lineWidth.W)) // PutM victim data

  val setIndex = reqLineAddr(lineOffsetWidth + setIndexWidth - 1, lineOffsetWidth)
  val requestTag = reqLineAddr(physicalAddressWidth - 1, lineOffsetWidth + setIndexWidth)

  // Hit context captured in Compare.
  val hitWayReg = RegInit(0.U(wayIndexWidth.W))
  val hitDirReg = RegInit(NONE)
  val hitOwnerReg = RegInit(0.U(hartIdWidth.W))
  val hitSharersReg = RegInit(0.U(sharerWidth.W))
  val hitDirtyMemReg = RegInit(false.B)
  // A blocking transaction uses either hit data or victim data, never both.
  // Keep the selected array line until a recall replaces it or the transaction ends.
  val selectedLineReg = RegInit(0.U(lineWidth.W))

  // Victim context captured in Compare (miss path).
  val victimWayReg = RegInit(0.U(wayIndexWidth.W))
  val victimTagReg = RegInit(0.U(tagWidth.W))
  val victimDirtyReg = RegInit(false.B)
  val victimDirReg = RegInit(NONE)
  val victimSharersReg = RegInit(0.U(sharerWidth.W))
  val victimOwnerReg = RegInit(0.U(hartIdWidth.W))
  val victimValidReg = RegInit(false.B)

  // Probe machinery.
  val probeBitmap = RegInit(0.U(sharerWidth.W))     // probes still to send
  val probeAckBitmap = RegInit(0.U(sharerWidth.W))  // responses still to collect
  val probeOpcodeReg = RegInit(ProbeToS)
  val probeLineAddrReg = RegInit(0.U(physicalAddressWidth.W))
  val expectProbeData = RegInit(false.B)
  val probeForVictim = RegInit(false.B)
  val recalledData = RegInit(0.U(lineWidth.W))
  val recalledValid = RegInit(false.B)

  // Grant context.
  val grantDataReg = RegInit(0.U(lineWidth.W))
  val grantStateReg = RegInit(BreezeGrantState.S)
  val grantHasDataReg = RegInit(false.B)
  val grantErrorReg = RegInit(false.B)

  // Memory transfer context.
  val memBeat = RegInit(0.U(beatIndexWidth.W))
  val readBeats = Reg(Vec(beats, UInt(64.W)))

  // Per-hart I$ refill pending latches (the I$ pulse contract requires an
  // unconditional capture even while the Home is busy).
  val instrPendingValid = RegInit(VecInit(Seq.fill(numHarts)(false.B)))
  val instrPendingAddr = Reg(Vec(numHarts, UInt(physicalAddressWidth.W)))

  // Idle-arbitration round-robin pointer over the 2N request sources.
  val arbPtr = RegInit(0.U(math.max(1, log2Ceil(2 * numHarts)).W))

  // ===== Output defaults =====
  for (h <- 0 until numHarts) {
    io.coherenceReq(h).ready := false.B

    io.coherenceGrant(h).valid := false.B
    io.coherenceGrant(h).dstHart := h.U
    io.coherenceGrant(h).txnId := reqTxnId
    io.coherenceGrant(h).lineAddr := reqLineAddr
    io.coherenceGrant(h).grantState := grantStateReg
    io.coherenceGrant(h).hasData := grantHasDataReg
    io.coherenceGrant(h).lineData := grantDataReg
    io.coherenceGrant(h).error := grantErrorReg

    io.coherenceProbe(h).valid := false.B
    io.coherenceProbe(h).dstHart := h.U
    io.coherenceProbe(h).txnId := reqTxnId
    io.coherenceProbe(h).lineAddr := probeLineAddrReg
    io.coherenceProbe(h).opcode := probeOpcodeReg

    io.coherenceProbeResp(h).ready := false.B

    io.instrResp(h).vld := false.B
    io.instrResp(h).data := grantDataReg
    io.instrResp(h).error := grantErrorReg
  }

  io.memoryWishbone.cyc := false.B
  io.memoryWishbone.stb := false.B
  io.memoryWishbone.we := false.B
  io.memoryWishbone.adr := 0.U
  io.memoryWishbone.dat_w := 0.U
  io.memoryWishbone.sel := Fill(8, 1.U(1.W))
  io.memoryWishbone.cti := WishboneCycleType.Classic
  io.memoryWishbone.bte := WishboneBurstType.Linear

  // ===== I$ refill pulse capture (any state) =====
  for (h <- 0 until numHarts) {
    when(io.instrReq(h).req) {
      assert(!instrPendingValid(h),
        "L2/Home: I$ refill pulse arrived while a previous refill is still pending")
      instrPendingValid(h) := true.B
      instrPendingAddr(h) := io.instrReq(h).paddr
    }
  }

  // ===== SRAM read: issued for one cycle in LookupRead =====
  // The write blocks below re-drive addr/we on top of these defaults; flowSRAM
  // has a single address port and a write suppresses the read for that cycle.
  val arrayReadEnable = state === LookupRead
  for (w <- 0 until ways) {
    dirArray(w).io.addr := setIndex
    dirArray(w).io.re := arrayReadEnable
    dataArray(w).io.addr := setIndex
    dataArray(w).io.re := arrayReadEnable
  }

  // ===== Registered array-update request =====
  // Business states prepare a request; ArrayUpdate performs the actual write
  // before the saved continuation can issue a grant, ack, or memory request.
  // Directory and data enables are independent, but share the update cycle.
  val dirWriteRequested = WireDefault(false.B)
  val dataWriteRequested = WireDefault(false.B)
  val dataWrPending = RegInit(false.B)
  val dataWrSet = Reg(UInt(setIndexWidth.W))
  val dataWrWay = Reg(UInt(wayIndexWidth.W))
  val dataWrLine = Reg(UInt(lineWidth.W))
  dataWrPending := false.B
  val dirWrPending = RegInit(false.B)
  val dirWrSet = Reg(UInt(setIndexWidth.W))
  val dirWrWay = Reg(UInt(wayIndexWidth.W))
  val dirWrEntry = Reg(dirEntryType)
  dirWrPending := false.B

  val initializingArrays = state === Init
  val writingDir = state === ArrayUpdate && dirWrPending
  val writingData = state === ArrayUpdate && dataWrPending
  // Broadcast payload/address; only the per-way write enable selects storage.
  // Idle write payloads have no architectural meaning.
  val dirAddress = Mux(initializingArrays, initSet, Mux(writingDir, dirWrSet, setIndex))
  val dataAddress = Mux(writingData, dataWrSet, setIndex)
  val dirPayload = Mux(initializingArrays, 0.U(dirEntryWidth.W), dirWrEntry.asUInt)
  for (w <- 0 until ways) {
    dirArray(w).io.addr := dirAddress
    dirArray(w).io.data_in := dirPayload
    dirArray(w).io.we := initializingArrays || (writingDir && dirWrWay === w.U)
    dataArray(w).io.addr := dataAddress
    dataArray(w).io.data_in := dataWrLine
    dataArray(w).io.we := writingData && dataWrWay === w.U
  }

  // ===== Compare-stage lookup (tag, directory state and data all arrive now) =====
  val wayHit = VecInit((0 until ways).map { w =>
    dirRdata(w).dir.valid && dirRdata(w).tag === requestTag
  })
  val hit = wayHit.asUInt.orR
  val hitWay = OHToUInt(wayHit.asUInt)
  val (hitDirState, hitDirStateLegal) =
    BreezeDirectoryState.safe(Mux1H((0 until ways).map(w => (wayHit(w), dirRdata(w).dir.dirState))))
  val hitSharers = Mux1H((0 until ways).map(w => (wayHit(w), dirRdata(w).dir.sharers)))
  val hitOwner = Mux1H((0 until ways).map(w => (wayHit(w), dirRdata(w).dir.ownerId)))
  val hitDirtyMem = Mux1H((0 until ways).map(w => (wayHit(w), dirRdata(w).dir.dirtyToMemory)))

  val invalidVector = VecInit((0 until ways).map(w => !dirRdata(w).dir.valid))
  val hasInvalid = invalidVector.asUInt.orR
  val firstInvalid = OHToUInt(PriorityEncoderOH(invalidVector.asUInt))
  val victimWay = Mux(hasInvalid, firstInvalid, roundRobin(setIndex))

  // Select hit/victim on narrow control signals before the single wide mux.
  val selectedWayOH = VecInit((0 until ways).map(w =>
    Mux(hit, wayHit(w), victimWay === w.U)))
  val selectedLine = Mux1H((0 until ways).map(w => (selectedWayOH(w), dataRdata(w))))

  // hitWay is decoded with a bare OHToUInt, which returns a silently wrong way
  // if two ways ever carry the same tag; the Mux1H lookups above would fold
  // both entries together in the same situation. dirState only ever holds an
  // encoding written by writeDir, so the fourth code point must stay unused.
  when(state === Compare) {
    assert(PopCount(wayHit.asUInt) <= 1.U,
      "L2/Home: more than one way matched the request tag")
    assert(!hit || hitDirStateLegal,
      "L2/Home: hit line carries a reserved directory-state encoding")
  }

  private def hartBit(h: UInt): UInt = (1.U(sharerWidth.W) << h)

  /** Record a directory update for one way; the array write fires next cycle. */
  private def writeDir(set: UInt, way: UInt, tag: UInt, valid: Bool, dirty: Bool,
                       dir: BreezeDirectoryState.Type, sharers: UInt, owner: UInt): Unit = {
    dirWriteRequested := true.B
    dirWrPending := true.B
    dirWrSet := set
    dirWrWay := way
    dirWrEntry.tag := tag
    dirWrEntry.dir.valid := valid
    dirWrEntry.dir.dirtyToMemory := dirty
    dirWrEntry.dir.dirState := dir.asUInt
    dirWrEntry.dir.sharers := sharers
    dirWrEntry.dir.ownerId := owner
    // SHARED with an empty bitmap would send the victim-eviction path into
    // ProbeReq with no targets, and ProbeWait can never leave a zero bitmap.
    assert(dir =/= SHARED || sharers =/= 0.U,
      "L2/Home: SHARED directory entry with an empty sharer bitmap")
  }

  private def writeDataArray(way: UInt, set: UInt, data: UInt): Unit = {
    dataWriteRequested := true.B
    dataWrPending := true.B
    dataWrSet := set
    dataWrWay := way
    dataWrLine := data
  }

  // ===== FSM =====
  switch(state) {
    is(Init) {
      // One set per cycle; the write itself is driven by the block above.
      when(initSet === (sets - 1).U) {
        nextState := Idle
      }.otherwise {
        initSet := initSet + 1.U
      }
    }

    is(Idle) {
      // Round-robin over 2N request sources (N D$ coherence ports followed by
      // N latched I$ refill ports) so neither a low-numbered hart nor the
      // coherence class can starve the others. The rotate-then-priority-encode
      // trick avoids read-modify-write wires (a when() loop that reads the
      // valid wire it also assigns is a combinational cycle).
      val srcValids = Cat(instrPendingValid.asUInt,
        VecInit((0 until numHarts).map(h => io.coherenceReq(h).valid)).asUInt)
      val numSrcs = 2 * numHarts
      val selValid = srcValids.orR
      val selSrc = if (numSrcs == 1) {
        0.U(1.W)
      } else {
        val rotated = (Cat(srcValids, srcValids) >> arbPtr)(numSrcs - 1, 0)
        (arbPtr + PriorityEncoder(rotated))(log2Ceil(numSrcs) - 1, 0)
      }
      val selIsInstr = if (numSrcs == 1) false.B else selSrc >= numHarts.U
      val selHart = (if (numSrcs == 1) 0.U else Mux(selIsInstr,
        selSrc - numHarts.U, selSrc))(hartIdWidth - 1, 0)
      for (h <- 0 until numHarts) {
        io.coherenceReq(h).ready := selValid && !selIsInstr && selHart === h.U
      }

      when(selValid) {
        arbPtr := Mux(selSrc === (numSrcs - 1).U, 0.U, selSrc + 1.U)
        reqHart := selHart
        grantErrorReg := false.B
        grantHasDataReg := false.B
        nextState := LookupRead
        when(selIsInstr) {
          reqOp := GetInstr
          reqTxnId := 0.U
          reqLineAddr := Cat(
            instrPendingAddr(selHart)(physicalAddressWidth - 1, lineOffsetWidth),
            0.U(lineOffsetWidth.W))
          reqData := 0.U
          reqIsInstr := true.B
        }.otherwise {
          reqOp := io.coherenceReq(selHart).opcode
          reqTxnId := io.coherenceReq(selHart).txnId
          reqLineAddr := Cat(
            io.coherenceReq(selHart).lineAddr(physicalAddressWidth - 1, lineOffsetWidth),
            0.U(lineOffsetWidth.W))
          reqData := io.coherenceReq(selHart).lineData
          reqIsInstr := false.B
        }
      }
    }

    is(LookupRead) {
      nextState := Compare
    }

    is(Compare) {
      // Capture the lookup context for later states.
      hitWayReg := hitWay
      hitDirReg := hitDirState
      hitOwnerReg := hitOwner
      hitSharersReg := hitSharers
      hitDirtyMemReg := hitDirtyMem
      selectedLineReg := selectedLine

      when(reqOp === PutS || reqOp === PutM) {
        // ---- PutS/PutM: the line must be present and the source must match ----
        when(!hit) {
          assert(false.B, "L2/Home: PutS/PutM for a line not present in the directory")
          nextState := SendPutAck
        }.elsewhen(reqOp === PutS) {
          when(hitDirState === SHARED) {
            assert(hitSharers(reqHart), "L2/Home: PutS from a hart that is not a sharer")
            val remaining = hitSharers & ~hartBit(reqHart)
            writeDir(setIndex, hitWay, requestTag, true.B, hitDirtyMem,
              Mux(remaining === 0.U, NONE, SHARED), remaining, 0.U)
          }.elsewhen(hitDirState === UNIQUE) {
            assert(hitOwner === reqHart, "L2/Home: PutS from a hart that is not the owner")
            writeDir(setIndex, hitWay, requestTag, true.B, hitDirtyMem, NONE, 0.U, 0.U)
          }.otherwise {
            assert(false.B, "L2/Home: PutS for a NONE directory line")
          }
          nextState := SendPutAck
        }.otherwise {
          // PutM: only the current UNIQUE owner may release dirty data.
          when(hitDirState === UNIQUE && hitOwner === reqHart) {
            writeDataArray(hitWay, setIndex, reqData)
            writeDir(setIndex, hitWay, requestTag, true.B, true.B, NONE, 0.U, 0.U)
          }.otherwise {
            assert(false.B, "L2/Home: PutM from a hart that is not the UNIQUE owner")
          }
          nextState := SendPutAck
        }
      }.elsewhen(hit) {
        // ---- GetS / GetM / GetInstr hit ----
        when(reqOp === GetInstr) {
          when(hitDirState === UNIQUE) {
            // Recall the owner (ProbeToS) before answering the I$.
            probeBitmap := hartBit(hitOwner)
            probeAckBitmap := hartBit(hitOwner)
            probeOpcodeReg := ProbeToS
            probeLineAddrReg := reqLineAddr
            expectProbeData := true.B
            probeForVictim := false.B
            recalledValid := false.B
            nextState := ProbeReq
          }.otherwise {
            // NONE/SHARED: the L2 data is current; I$ never joins the bitmap.
            grantDataReg := selectedLine
            grantHasDataReg := true.B
            nextState := SendGrant
          }
        }.elsewhen(reqOp === GetS) {
          when(hitDirState === NONE) {
            // MESI: the first D$ reader of an untracked line gets E; it may
            // silently upgrade to M, so the directory goes UNIQUE and never
            // assumes the L2 data stays current.
            writeDir(setIndex, hitWay, requestTag, true.B, hitDirtyMem, UNIQUE, 0.U, reqHart)
            grantDataReg := selectedLine
            grantStateReg := BreezeGrantState.E
            grantHasDataReg := true.B
            nextState := SendGrant
          }.elsewhen(hitDirState === SHARED) {
            writeDir(setIndex, hitWay, requestTag, true.B, hitDirtyMem,
              SHARED, hitSharers | hartBit(reqHart), 0.U)
            grantDataReg := selectedLine
            grantStateReg := BreezeGrantState.S
            grantHasDataReg := true.B
            nextState := SendGrant
          }.otherwise {
            // UNIQUE: a GetS from the owner itself is a protocol violation
            // (the owner already holds at least S permissions).
            assert(hitOwner =/= reqHart,
              "L2/Home: GetS from the current UNIQUE owner")
            probeBitmap := hartBit(hitOwner)
            probeAckBitmap := hartBit(hitOwner)
            probeOpcodeReg := ProbeToS
            probeLineAddrReg := reqLineAddr
            expectProbeData := true.B
            probeForVictim := false.B
            recalledValid := false.B
            nextState := ProbeReq
          }
        }.otherwise {
          // GetM
          when(hitDirState === NONE) {
            writeDir(setIndex, hitWay, requestTag, true.B, hitDirtyMem, UNIQUE, 0.U, reqHart)
            grantDataReg := selectedLine
            grantStateReg := BreezeGrantState.M
            grantHasDataReg := true.B
            nextState := SendGrant
          }.elsewhen(hitDirState === SHARED) {
            val targets = hitSharers & ~hartBit(reqHart)
            when(targets === 0.U) {
              writeDir(setIndex, hitWay, requestTag, true.B, hitDirtyMem, UNIQUE, 0.U, reqHart)
              grantDataReg := selectedLine
              grantStateReg := BreezeGrantState.M
              grantHasDataReg := true.B
              nextState := SendGrant
            }.otherwise {
              probeBitmap := targets
              probeAckBitmap := targets
              probeOpcodeReg := ProbeInv
              probeLineAddrReg := reqLineAddr
              expectProbeData := false.B
              probeForVictim := false.B
              recalledValid := false.B
              nextState := ProbeReq
            }
          }.elsewhen(hitOwner === reqHart) {
            // Owner re-confirming M (e.g. after an upgrade race): no data needed.
            grantStateReg := BreezeGrantState.M
            grantHasDataReg := false.B
            nextState := SendGrant
          }.otherwise {
            // Recall the dirty line from the current owner.
            probeBitmap := hartBit(hitOwner)
            probeAckBitmap := hartBit(hitOwner)
            probeOpcodeReg := ProbeRecallInv
            probeLineAddrReg := reqLineAddr
            expectProbeData := true.B
            probeForVictim := false.B
            recalledValid := false.B
            nextState := ProbeReq
          }
        }
      }.otherwise {
        // ---- Miss: capture the victim, then route ----
        val victimEntry = dirRdata(victimWay)
        victimWayReg := victimWay
        victimValidReg := victimEntry.dir.valid
        victimTagReg := victimEntry.tag
        victimDirtyReg := victimEntry.dir.dirtyToMemory
        val (victimDirDecoded, victimDirLegal) =
          BreezeDirectoryState.safe(victimEntry.dir.dirState)
        assert(victimDirLegal,
          "L2/Home: victim line carries a reserved directory-state encoding")
        victimDirReg := victimDirDecoded
        victimSharersReg := victimEntry.dir.sharers
        victimOwnerReg := victimEntry.dir.ownerId

        when(!victimEntry.dir.valid) {
          memBeat := 0.U
          nextState := MemRead
        }.elsewhen(victimEntry.dir.dirState === NONE.asUInt) {
          memBeat := 0.U
          nextState := Mux(victimEntry.dir.dirtyToMemory, VictimWrite, MemRead)
        }.otherwise {
          // Inclusive eviction: invalidate SHARED sharers or recall the owner.
          val isUnique = victimEntry.dir.dirState === UNIQUE.asUInt
          val targets = Mux(isUnique,
            hartBit(victimEntry.dir.ownerId),
            victimEntry.dir.sharers)
          // An empty target set would enter ProbeReq and then hang in ProbeWait,
          // whose exit condition needs at least one response to fire.
          assert(targets =/= 0.U,
            "L2/Home: inclusive eviction with an empty probe target set")
          probeBitmap := targets
          probeAckBitmap := targets
          probeOpcodeReg := Mux(isUnique, ProbeRecallInv, ProbeInv)
          probeLineAddrReg := Cat(victimEntry.tag, setIndex, 0.U(lineOffsetWidth.W))
          expectProbeData := isUnique
          probeForVictim := true.B
          recalledValid := false.B
          nextState := ProbeReq
        }
      }
    }

    is(ProbeReq) {
      // Hold probe.valid with a stable payload until each target accepts.
      val fired = Wire(Vec(sharerWidth, Bool()))
      for (h <- 0 until sharerWidth) {
        fired(h) := probeBitmap(h) && io.coherenceProbe(h).ready
        io.coherenceProbe(h).valid := probeBitmap(h)
      }
      val nextBitmap = probeBitmap & ~fired.asUInt
      probeBitmap := nextBitmap
      when(nextBitmap === 0.U) {
        nextState := ProbeWait
      }
    }

    is(ProbeWait) {
      val respFired = Wire(Vec(sharerWidth, Bool()))
      for (h <- 0 until sharerWidth) {
        val rsp = io.coherenceProbeResp(h)
        respFired(h) := probeAckBitmap(h) && rsp.valid
        io.coherenceProbeResp(h).ready := probeAckBitmap(h)
        when(respFired(h)) {
          assert(rsp.ack, "L2/Home: probe response without ack")
          assert(rsp.txnId === reqTxnId, "L2/Home: probe response txnId mismatch")
          assert(rsp.lineAddr === probeLineAddrReg, "L2/Home: probe response lineAddr mismatch")
          when(rsp.hasData) {
            assert(expectProbeData, "L2/Home: unexpected probe response data")
            recalledData := rsp.lineData
            recalledValid := true.B
          }
        }
      }
      val nextAck = probeAckBitmap & ~respFired.asUInt
      probeAckBitmap := nextAck
      when(nextAck === 0.U && respFired.asUInt.orR) {
        // All responses collected (bitmap was non-zero when entering).
        when(probeForVictim) {
          // A recalled (UNIQUE) victim line is landed in the L2 array before
          // any memory operation can fail, so the dirty data is never stranded
          // in a transaction register. The final response may carry the data
          // this very cycle; earlier responses already latched recalledData.
          // An owner that answers WITHOUT data held a clean E copy - then the
          // L2 array line is current by construction and nothing is written.
          val respDataThisCycle = Mux1H((0 until sharerWidth).map(h =>
            (respFired(h), io.coherenceProbeResp(h).lineData)))
          val respHasDataThisCycle = (0 until sharerWidth).map(h =>
            respFired(h) && io.coherenceProbeResp(h).hasData).reduce(_ || _)
          val recallNow = victimDirReg === UNIQUE && (recalledValid || respHasDataThisCycle)
          when(recallNow) {
            val recallLine = Mux(recalledValid, recalledData, respDataThisCycle)
            writeDataArray(victimWayReg, setIndex, recallLine)
            // The writeback below must carry the recalled dirty data as well:
            // selectedLineReg still holds the pre-recall array contents.
            selectedLineReg := recallLine
          }
          memBeat := 0.U
          val nowDirty = victimDirtyReg || recallNow
          nextState := Mux(nowDirty, VictimWrite, MemRead)
        }.otherwise {
          nextState := HitUpdate
        }
      }
    }

    is(HitUpdate) {
      // Post-probe completion of a GetS/GetM/GetInstr hit. A probed owner only
      // returns data when it held M; a clean E owner acks without data and the
      // L2 array line is already current.
      val finalData = Mux(recalledValid, recalledData, selectedLineReg)
      when(recalledValid) {
        writeDataArray(hitWayReg, setIndex, recalledData)
      }
      when(reqOp === GetS || reqOp === GetInstr) {
        // ProbeToS on the UNIQUE owner: the old owner becomes a D$ sharer.
        // A GetS requester joins the sharer bitmap; the I$ never joins.
        val sharersAfter = Mux(reqOp === GetS,
          hartBit(hitOwnerReg) | hartBit(reqHart), hartBit(hitOwnerReg))
        writeDir(setIndex, hitWayReg, requestTag, true.B, hitDirtyMemReg || recalledValid,
          SHARED, sharersAfter, 0.U)
        grantStateReg := BreezeGrantState.S
        grantDataReg := finalData
        grantHasDataReg := true.B
        nextState := SendGrant
      }.otherwise {
        // GetM: every other copy is gone (SHARED sharers invalidated, or the
        // UNIQUE owner recalled); hand M to the requester with the latest data.
        writeDir(setIndex, hitWayReg, requestTag, true.B, hitDirtyMemReg || recalledValid,
          UNIQUE, 0.U, reqHart)
        grantStateReg := BreezeGrantState.M
        grantDataReg := finalData
        grantHasDataReg := true.B
        nextState := SendGrant
      }
    }

    is(VictimWrite) {
      val victimLineAddr = Cat(victimTagReg, setIndex, 0.U(lineOffsetWidth.W))
      io.memoryWishbone.cyc := true.B
      io.memoryWishbone.stb := true.B
      io.memoryWishbone.we := true.B
      io.memoryWishbone.adr := ((victimLineAddr >> 3) + memBeat)(io.memoryWishbone.adr.getWidth - 1, 0)
      io.memoryWishbone.dat_w := (selectedLineReg >> (memBeat << 6))(63, 0)
      when(io.memoryWishbone.err) {
        // The victim's L1 copies are already gone; keep the victim tag/data
        // valid in the arrays with the only consistent directory state
        // (NONE + dirtyToMemory). Nothing is overwritten by the new line.
        writeDir(setIndex, victimWayReg, victimTagReg, true.B, true.B, NONE, 0.U, 0.U)
        grantErrorReg := true.B
        nextState := SendError
      }.elsewhen(io.memoryWishbone.ack) {
        when(memBeat === (beats - 1).U) {
          memBeat := 0.U
          nextState := MemRead
        }.otherwise {
          memBeat := memBeat + 1.U
        }
      }
    }

    is(MemRead) {
      io.memoryWishbone.cyc := true.B
      io.memoryWishbone.stb := true.B
      io.memoryWishbone.we := false.B
      io.memoryWishbone.adr := ((reqLineAddr >> 3) + memBeat)(io.memoryWishbone.adr.getWidth - 1, 0)
      when(io.memoryWishbone.err) {
        // Refill failed: do not install. The victim (if any) was already
        // evicted and written back, so its slot is simply dropped.
        writeDir(setIndex, victimWayReg, victimTagReg, false.B, false.B, NONE, 0.U, 0.U)
        grantErrorReg := true.B
        nextState := SendError
      }.elsewhen(io.memoryWishbone.ack) {
        readBeats(memBeat) := io.memoryWishbone.dat_r
        when(memBeat === (beats - 1).U) {
          // The final beat is merged combinationally (no stale readBeats tail).
          val finalBeats = Wire(Vec(beats, UInt(64.W)))
          for (b <- 0 until beats) {
            finalBeats(b) := Mux(memBeat === b.U, io.memoryWishbone.dat_r, readBeats(b))
          }
          val installedLine = finalBeats.asUInt
          // The new tag rides along in the directory write - tag and state are
          // one SRAM word, so installing a line is a single update.
          writeDataArray(victimWayReg, setIndex, installedLine)
          when(reqIsInstr) {
            // I$ lines allocate in the L2 but never join the D$ directory.
            writeDir(setIndex, victimWayReg, requestTag, true.B, false.B, NONE, 0.U, 0.U)
          }.elsewhen(reqOp === GetM) {
            writeDir(setIndex, victimWayReg, requestTag, true.B, false.B, UNIQUE, 0.U, reqHart)
            grantStateReg := BreezeGrantState.M
          }.otherwise {
            // MESI: a fresh D$ GetS refill is granted E (sole copy).
            writeDir(setIndex, victimWayReg, requestTag, true.B, false.B, UNIQUE, 0.U, reqHart)
            grantStateReg := BreezeGrantState.E
          }
          roundRobin(setIndex) := Mux(victimWayReg === (ways - 1).U, 0.U, victimWayReg + 1.U)
          grantDataReg := installedLine
          grantHasDataReg := true.B
          nextState := SendGrant
        }.otherwise {
          memBeat := memBeat + 1.U
        }
      }
    }

    is(ArrayUpdate) {
      assert(dirWrPending || dataWrPending, "L2/Home: empty array update")
      nextState := updateResumeState
    }

    is(SendGrant) {
      when(reqIsInstr) {
        io.instrResp(reqHart).vld := true.B
        instrPendingValid(reqHart) := false.B
        nextState := Idle
      }.otherwise {
        io.coherenceGrant(reqHart).valid := true.B
        when(io.coherenceGrant(reqHart).ready) {
          nextState := Idle
        }
      }
    }

    is(SendPutAck) {
      // A Put acknowledgement carries no data and confers no permission;
      // override every stale grant register combinationally instead of
      // re-timing them. grantState is driven to S rather than left holding the
      // previous transaction's value - the L1D ignores it on this path today,
      // but a released line must never appear to carry E/M.
      io.coherenceGrant(reqHart).valid := true.B
      io.coherenceGrant(reqHart).grantState := BreezeGrantState.S
      io.coherenceGrant(reqHart).hasData := false.B
      io.coherenceGrant(reqHart).error := false.B
      when(io.coherenceGrant(reqHart).ready) {
        nextState := Idle
      }
    }

    is(SendError) {
      when(reqIsInstr) {
        io.instrResp(reqHart).vld := true.B
        instrPendingValid(reqHart) := false.B
        nextState := Idle
      }.otherwise {
        io.coherenceGrant(reqHart).valid := true.B
        when(io.coherenceGrant(reqHart).ready) {
          nextState := Idle
        }
      }
    }
  }

  // Interpose the write state after the business state has selected its
  // continuation. No request is accepted and no response is sent in this state.
  state := nextState
  when(dirWriteRequested || dataWriteRequested) {
    assert(state =/= ArrayUpdate && state =/= Init,
      "L2/Home: nested array-update request")
    updateResumeState := nextState
    state := ArrayUpdate
  }

  // ===== Protocol sanity assertions (spec section 24 subset) =====
  // The sharer bitmap and owner id always stay within range by construction;
  // the checks below catch the interesting violations.
  when(state =/= Idle) {
    assert(reqHart < numHarts.U, "L2/Home: transaction hart id out of range")
  }
}
