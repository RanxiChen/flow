package flow.cache

import chisel3._
import chisel3.util._
import flow.bus.{LiteXWishboneMasterIO, LiteXWishboneParameters, WishboneBurstType, WishboneCycleType}
import flow.config.L2CacheGeometry
import flow.interface.{L1CacheMissReqIO, L1CacheMissRespIO}
import flow.mem.flowSRAM
import flow.platform.BreezeMcuPlatform

object BreezeL2HomeState extends ChiselEnum {
  val Idle, LookupRead, Compare,
      ProbeReq, ProbeWait, HitUpdate,
      VictimWrite, MemRead,
      SendGrant, SendPutAck, SendError = Value
}

/** Per-set directory metadata for the L2/Home.
  *
  * dirState encodes BreezeDirectoryState (NONE=0, SHARED=1, UNIQUE=2). The
  * whole bundle resets to zero, leaving every line logically invalid and every
  * directory state NONE. roundRobin is the per-set replacement pointer used
  * when no invalid way exists.
  */
class L2SetMeta(val ways: Int, val sharerWidth: Int, val hartIdWidth: Int) extends Bundle {
  val valid = Vec(ways, Bool())
  val dirtyToMemory = Vec(ways, Bool())
  val dirState = Vec(ways, UInt(2.W))
  val sharers = Vec(ways, UInt(sharerWidth.W))
  val ownerId = Vec(ways, UInt(hartIdWidth.W))
  val roundRobin = UInt(math.max(1, log2Ceil(ways)).W)
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
  val meta = RegInit(VecInit(Seq.fill(sets)(0.U.asTypeOf(new L2SetMeta(ways, sharerWidth, hartIdWidth)))))
  val tagArray = Seq.fill(ways)(Module(new flowSRAM(sets, tagWidth, "l2tag")))
  val dataArray = Seq.fill(ways)(Module(new flowSRAM(sets, lineWidth, "l2data")))
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

  // ===== Transaction context (frozen until the transaction completes) =====
  val state = RegInit(Idle)
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
  val hitDataReg = RegInit(0.U(lineWidth.W))

  // Victim context captured in Compare (miss path).
  val victimWayReg = RegInit(0.U(wayIndexWidth.W))
  val victimTagReg = RegInit(0.U(tagWidth.W))
  val victimDataReg = RegInit(0.U(lineWidth.W))
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
  val arrayReadEnable = state === LookupRead
  for (w <- 0 until ways) {
    tagArray(w).io.addr := setIndex
    tagArray(w).io.re := arrayReadEnable
    dataArray(w).io.addr := setIndex
    dataArray(w).io.re := arrayReadEnable
  }

  // ===== Compare-stage lookup (meta is a register, tag/data arrive now) =====
  val wayHit = VecInit((0 until ways).map { w =>
    meta(setIndex).valid(w) && tagRdata(w) === requestTag
  })
  val hit = wayHit.asUInt.orR
  val hitWay = OHToUInt(wayHit.asUInt)
  val hitDirState = BreezeDirectoryState(Mux1H((0 until ways).map(w => (wayHit(w), meta(setIndex).dirState(w)))))
  val hitSharers = Mux1H((0 until ways).map(w => (wayHit(w), meta(setIndex).sharers(w))))
  val hitOwner = Mux1H((0 until ways).map(w => (wayHit(w), meta(setIndex).ownerId(w))))
  val hitDirtyMem = Mux1H((0 until ways).map(w => (wayHit(w), meta(setIndex).dirtyToMemory(w))))
  val hitData = Mux1H((0 until ways).map(w => (wayHit(w), dataRdata(w))))

  val invalidVector = VecInit((0 until ways).map(w => !meta(setIndex).valid(w)))
  val hasInvalid = invalidVector.asUInt.orR
  val firstInvalid = OHToUInt(PriorityEncoderOH(invalidVector.asUInt))
  val victimWay = Mux(hasInvalid, firstInvalid, meta(setIndex).roundRobin)

  private def hartBit(h: UInt): UInt = (1.U(sharerWidth.W) << h)

  /** Dynamic per-line directory write helper. */
  private def writeDir(set: UInt, way: UInt, valid: Bool, dirty: Bool,
                       dir: BreezeDirectoryState.Type, sharers: UInt, owner: UInt): Unit = {
    meta(set).valid(way) := valid
    meta(set).dirtyToMemory(way) := dirty
    meta(set).dirState(way) := dir.asUInt
    meta(set).sharers(way) := sharers
    meta(set).ownerId(way) := owner
  }

  private def writeDataArray(way: UInt, set: UInt, data: UInt): Unit = {
    for (w <- 0 until ways) {
      when(way === w.U) {
        dataArray(w).io.we := true.B
        dataArray(w).io.addr := set
        dataArray(w).io.data_in := data
      }
    }
  }

  // ===== FSM =====
  switch(state) {
    is(Idle) {
      // Coherence requests win over pending I$ refills; lowest hart first.
      // Priority encoders, not read-modify-write wires: a when() loop that
      // reads the valid wire it also assigns is a combinational cycle.
      val cohValids = VecInit((0 until numHarts).map(h => io.coherenceReq(h).valid))
      val selValid = cohValids.asUInt.orR
      val selHart = if (numHarts == 1) 0.U(hartIdWidth.W) else PriorityEncoder(cohValids.asUInt)
      for (h <- 0 until numHarts) {
        io.coherenceReq(h).ready := selValid && selHart === h.U
      }

      val instrSelValid = instrPendingValid.asUInt.orR
      val instrSelHart = if (numHarts == 1) 0.U(hartIdWidth.W) else PriorityEncoder(instrPendingValid.asUInt)

      when(selValid) {
        reqHart := selHart
        reqOp := io.coherenceReq(selHart).opcode
        reqTxnId := io.coherenceReq(selHart).txnId
        reqLineAddr := Cat(
          io.coherenceReq(selHart).lineAddr(physicalAddressWidth - 1, lineOffsetWidth),
          0.U(lineOffsetWidth.W))
        reqData := io.coherenceReq(selHart).lineData
        reqIsInstr := false.B
        grantErrorReg := false.B
        state := LookupRead
      }.elsewhen(instrSelValid) {
        reqHart := instrSelHart
        reqOp := GetInstr
        reqTxnId := 0.U
        reqLineAddr := Cat(
          instrPendingAddr(instrSelHart)(physicalAddressWidth - 1, lineOffsetWidth),
          0.U(lineOffsetWidth.W))
        reqData := 0.U
        reqIsInstr := true.B
        grantErrorReg := false.B
        state := LookupRead
      }
    }

    is(LookupRead) {
      state := Compare
    }

    is(Compare) {
      // Capture the lookup context for later states.
      hitWayReg := hitWay
      hitDirReg := hitDirState
      hitOwnerReg := hitOwner
      hitSharersReg := hitSharers
      hitDirtyMemReg := hitDirtyMem
      hitDataReg := hitData

      when(reqOp === PutS || reqOp === PutM) {
        // ---- PutS/PutM: the line must be present and the source must match ----
        when(!hit) {
          assert(false.B, "L2/Home: PutS/PutM for a line not present in the directory")
          state := SendPutAck
        }.elsewhen(reqOp === PutS) {
          when(hitDirState === SHARED) {
            assert(hitSharers(reqHart), "L2/Home: PutS from a hart that is not a sharer")
            val remaining = hitSharers & ~hartBit(reqHart)
            writeDir(setIndex, hitWay, true.B, hitDirtyMem,
              Mux(remaining === 0.U, NONE, SHARED), remaining, 0.U)
          }.elsewhen(hitDirState === UNIQUE) {
            assert(hitOwner === reqHart, "L2/Home: PutS from a hart that is not the owner")
            writeDir(setIndex, hitWay, true.B, hitDirtyMem, NONE, 0.U, 0.U)
          }.otherwise {
            assert(false.B, "L2/Home: PutS for a NONE directory line")
          }
          state := SendPutAck
        }.otherwise {
          // PutM: only the current UNIQUE owner may release dirty data.
          when(hitDirState === UNIQUE && hitOwner === reqHart) {
            writeDataArray(hitWay, setIndex, reqData)
            writeDir(setIndex, hitWay, true.B, true.B, NONE, 0.U, 0.U)
          }.otherwise {
            assert(false.B, "L2/Home: PutM from a hart that is not the UNIQUE owner")
          }
          state := SendPutAck
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
            state := ProbeReq
          }.otherwise {
            // NONE/SHARED: the L2 data is current; I$ never joins the bitmap.
            grantDataReg := hitData
            grantHasDataReg := true.B
            state := SendGrant
          }
        }.elsewhen(reqOp === GetS) {
          when(hitDirState === NONE) {
            // Line present but no coherent copy: grant S (MSI phase; the MESI
            // E grant arrives in a later phase).
            writeDir(setIndex, hitWay, true.B, hitDirtyMem, SHARED, hartBit(reqHart), 0.U)
            grantDataReg := hitData
            grantStateReg := BreezeGrantState.S
            grantHasDataReg := true.B
            state := SendGrant
          }.elsewhen(hitDirState === SHARED) {
            writeDir(setIndex, hitWay, true.B, hitDirtyMem,
              SHARED, hitSharers | hartBit(reqHart), 0.U)
            grantDataReg := hitData
            grantStateReg := BreezeGrantState.S
            grantHasDataReg := true.B
            state := SendGrant
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
            state := ProbeReq
          }
        }.otherwise {
          // GetM
          when(hitDirState === NONE) {
            writeDir(setIndex, hitWay, true.B, hitDirtyMem, UNIQUE, 0.U, reqHart)
            grantDataReg := hitData
            grantStateReg := BreezeGrantState.M
            grantHasDataReg := true.B
            state := SendGrant
          }.elsewhen(hitDirState === SHARED) {
            val targets = hitSharers & ~hartBit(reqHart)
            when(targets === 0.U) {
              writeDir(setIndex, hitWay, true.B, hitDirtyMem, UNIQUE, 0.U, reqHart)
              grantDataReg := hitData
              grantStateReg := BreezeGrantState.M
              grantHasDataReg := true.B
              state := SendGrant
            }.otherwise {
              probeBitmap := targets
              probeAckBitmap := targets
              probeOpcodeReg := ProbeInv
              probeLineAddrReg := reqLineAddr
              expectProbeData := false.B
              probeForVictim := false.B
              recalledValid := false.B
              state := ProbeReq
            }
          }.elsewhen(hitOwner === reqHart) {
            // Owner re-confirming M (e.g. after an upgrade race): no data needed.
            grantStateReg := BreezeGrantState.M
            grantHasDataReg := false.B
            state := SendGrant
          }.otherwise {
            // Recall the dirty line from the current owner.
            probeBitmap := hartBit(hitOwner)
            probeAckBitmap := hartBit(hitOwner)
            probeOpcodeReg := ProbeRecallInv
            probeLineAddrReg := reqLineAddr
            expectProbeData := true.B
            probeForVictim := false.B
            recalledValid := false.B
            state := ProbeReq
          }
        }
      }.otherwise {
        // ---- Miss: capture the victim, then route ----
        victimWayReg := victimWay
        victimValidReg := meta(setIndex).valid(victimWay)
        victimTagReg := tagRdata(victimWay)
        victimDataReg := dataRdata(victimWay)
        victimDirtyReg := meta(setIndex).dirtyToMemory(victimWay)
        victimDirReg := BreezeDirectoryState(meta(setIndex).dirState(victimWay))
        victimSharersReg := meta(setIndex).sharers(victimWay)
        victimOwnerReg := meta(setIndex).ownerId(victimWay)

        when(!meta(setIndex).valid(victimWay)) {
          memBeat := 0.U
          state := MemRead
        }.elsewhen(meta(setIndex).dirState(victimWay) === NONE.asUInt) {
          memBeat := 0.U
          state := Mux(meta(setIndex).dirtyToMemory(victimWay), VictimWrite, MemRead)
        }.otherwise {
          // Inclusive eviction: invalidate SHARED sharers or recall the owner.
          val isUnique = meta(setIndex).dirState(victimWay) === UNIQUE.asUInt
          val targets = Mux(isUnique,
            hartBit(meta(setIndex).ownerId(victimWay)),
            meta(setIndex).sharers(victimWay))
          probeBitmap := targets
          probeAckBitmap := targets
          probeOpcodeReg := Mux(isUnique, ProbeRecallInv, ProbeInv)
          probeLineAddrReg := Cat(tagRdata(victimWay), setIndex, 0.U(lineOffsetWidth.W))
          expectProbeData := isUnique
          probeForVictim := true.B
          recalledValid := false.B
          state := ProbeReq
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
        state := ProbeWait
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
          val respDataThisCycle = Mux1H((0 until sharerWidth).map(h =>
            (respFired(h), io.coherenceProbeResp(h).lineData)))
          val respHasDataThisCycle = (0 until sharerWidth).map(h =>
            respFired(h) && io.coherenceProbeResp(h).hasData).reduce(_ || _)
          val recallNow = victimDirReg === UNIQUE && (recalledValid || respHasDataThisCycle)
          when(recallNow) {
            val recallLine = Mux(recalledValid, recalledData, respDataThisCycle)
            writeDataArray(victimWayReg, setIndex, recallLine)
            // The writeback below must carry the recalled dirty data as well:
            // victimDataReg still holds the pre-recall array contents.
            victimDataReg := recallLine
          }
          // A UNIQUE victim whose owner answered without data is a protocol
          // violation; without this check the stale array line would be
          // written back to memory as if it were the owner's dirty data.
          assert(victimDirReg =/= UNIQUE || recallNow,
            "L2/Home: UNIQUE victim recall completed without owner data")
          memBeat := 0.U
          val nowDirty = victimDirtyReg || victimDirReg === UNIQUE
          state := Mux(nowDirty, VictimWrite, MemRead)
        }.otherwise {
          state := HitUpdate
        }
      }
    }

    is(HitUpdate) {
      // Post-probe completion of a GetS/GetM/GetInstr hit.
      val finalData = Mux(recalledValid, recalledData, hitDataReg)
      when(reqOp === GetS || reqOp === GetInstr) {
        // ProbeToS on the UNIQUE owner: owner becomes a D$ sharer; recalled
        // data updates the line and sets dirtyToMemory.
        when(recalledValid) {
          writeDataArray(hitWayReg, setIndex, recalledData)
        }
        writeDir(setIndex, hitWayReg, true.B, hitDirtyMemReg || recalledValid,
          SHARED, hartBit(hitOwnerReg), 0.U)
        grantDataReg := finalData
        grantHasDataReg := true.B
        // GetInstr: owner becomes a D$ sharer but the I$ itself never joins.
        when(reqOp === GetS) {
          writeDir(setIndex, hitWayReg, true.B, hitDirtyMemReg || recalledValid,
            SHARED, hartBit(hitOwnerReg) | hartBit(reqHart), 0.U)
          grantStateReg := BreezeGrantState.S
        }
        state := SendGrant
      }.otherwise {
        // GetM
        when(hitDirReg === SHARED) {
          // All other sharers invalidated; the L2 data is current.
          writeDir(setIndex, hitWayReg, true.B, hitDirtyMemReg, UNIQUE, 0.U, reqHart)
          grantDataReg := hitDataReg
        }.otherwise {
          // Recalled from the previous owner: install the dirty data, hand M
          // to the requester.
          writeDataArray(hitWayReg, setIndex, recalledData)
          writeDir(setIndex, hitWayReg, true.B, true.B, UNIQUE, 0.U, reqHart)
          grantDataReg := recalledData
        }
        grantStateReg := BreezeGrantState.M
        grantHasDataReg := true.B
        state := SendGrant
      }
    }

    is(VictimWrite) {
      val victimLineAddr = Cat(victimTagReg, setIndex, 0.U(lineOffsetWidth.W))
      io.memoryWishbone.cyc := true.B
      io.memoryWishbone.stb := true.B
      io.memoryWishbone.we := true.B
      io.memoryWishbone.adr := ((victimLineAddr >> 3) + memBeat)(io.memoryWishbone.adr.getWidth - 1, 0)
      io.memoryWishbone.dat_w := (victimDataReg >> (memBeat << 6))(63, 0)
      when(io.memoryWishbone.err) {
        // The victim's L1 copies are already gone; keep the victim tag/data
        // valid in the arrays with the only consistent directory state
        // (NONE + dirtyToMemory). Nothing is overwritten by the new line.
        writeDir(setIndex, victimWayReg, true.B, true.B, NONE, 0.U, 0.U)
        grantErrorReg := true.B
        state := SendError
      }.elsewhen(io.memoryWishbone.ack) {
        when(memBeat === (beats - 1).U) {
          memBeat := 0.U
          state := MemRead
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
        writeDir(setIndex, victimWayReg, false.B, false.B, NONE, 0.U, 0.U)
        grantErrorReg := true.B
        state := SendError
      }.elsewhen(io.memoryWishbone.ack) {
        readBeats(memBeat) := io.memoryWishbone.dat_r
        when(memBeat === (beats - 1).U) {
          // The final beat is merged combinationally (no stale readBeats tail).
          val finalBeats = Wire(Vec(beats, UInt(64.W)))
          for (b <- 0 until beats) {
            finalBeats(b) := Mux(memBeat === b.U, io.memoryWishbone.dat_r, readBeats(b))
          }
          val installedLine = finalBeats.asUInt
          for (w <- 0 until ways) {
            when(victimWayReg === w.U) {
              tagArray(w).io.we := true.B
              tagArray(w).io.addr := setIndex
              tagArray(w).io.data_in := requestTag
            }
          }
          writeDataArray(victimWayReg, setIndex, installedLine)
          when(reqIsInstr) {
            // I$ lines allocate in the L2 but never join the D$ directory.
            writeDir(setIndex, victimWayReg, true.B, false.B, NONE, 0.U, 0.U)
          }.elsewhen(reqOp === GetM) {
            writeDir(setIndex, victimWayReg, true.B, false.B, UNIQUE, 0.U, reqHart)
            grantStateReg := BreezeGrantState.M
          }.otherwise {
            writeDir(setIndex, victimWayReg, true.B, false.B, SHARED, hartBit(reqHart), 0.U)
            grantStateReg := BreezeGrantState.S
          }
          meta(setIndex).roundRobin := Mux(victimWayReg === (ways - 1).U, 0.U, victimWayReg + 1.U)
          grantDataReg := installedLine
          grantHasDataReg := true.B
          state := SendGrant
        }.otherwise {
          memBeat := memBeat + 1.U
        }
      }
    }

    is(SendGrant) {
      when(reqIsInstr) {
        io.instrResp(reqHart).vld := true.B
        instrPendingValid(reqHart) := false.B
        state := Idle
      }.otherwise {
        io.coherenceGrant(reqHart).valid := true.B
        when(io.coherenceGrant(reqHart).ready) {
          state := Idle
        }
      }
    }

    is(SendPutAck) {
      // A Put acknowledgement carries no data; override the stale grant
      // registers combinationally instead of re-timing them.
      io.coherenceGrant(reqHart).valid := true.B
      io.coherenceGrant(reqHart).hasData := false.B
      io.coherenceGrant(reqHart).error := false.B
      when(io.coherenceGrant(reqHart).ready) {
        state := Idle
      }
    }

    is(SendError) {
      when(reqIsInstr) {
        io.instrResp(reqHart).vld := true.B
        instrPendingValid(reqHart) := false.B
        state := Idle
      }.otherwise {
        io.coherenceGrant(reqHart).valid := true.B
        when(io.coherenceGrant(reqHart).ready) {
          state := Idle
        }
      }
    }
  }

  // ===== Protocol sanity assertions (spec section 24 subset) =====
  // The sharer bitmap and owner id always stay within range by construction;
  // the checks below catch the interesting violations.
  when(state =/= Idle) {
    assert(reqHart < numHarts.U, "L2/Home: transaction hart id out of range")
  }
}
