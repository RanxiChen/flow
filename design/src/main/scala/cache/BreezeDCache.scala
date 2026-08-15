package flow.cache

import chisel3._
import chisel3.util._
import flow.config.DefaultDCacheConfig
import flow.interface._
import flow.mem.flowSRAM
import flow.platform.{PMAAccessType, PMAChecker}

object BreezeDCacheState extends ChiselEnum {
  val Idle, Lookup,
      UncachedReq, UncachedWait,
      WritebackReq, WritebackWait,
      RefillReq, RefillWait,
      Respond,
      FlushScan, FlushRead, FlushWritebackReq, FlushWritebackWait, FlushRespond,
      Fatal = Value
}

/** Blocking, 4-way set-associative, write-back/write-allocate L1 data cache.
  *
  * Frozen geometry: 8192 B capacity, 32 B lines, 4 ways -> 64 sets.
  * Tag/Data arrays use synchronous-read SRAMs (one-cycle read latency); the
  * valid/dirty/PLRU metadata is resettable register memory, so every line is
  * logically invalid after reset without relying on SRAM contents.
  *
  * Policy:
  *   - write-back and write-allocate;
  *   - invalid-first, then tree-PLRU replacement within a set;
  *   - one outstanding CPU operation and one outstanding lower-level request;
  *   - PMA-denied accesses return an access error without reaching the bus;
  *   - non-cacheable/device accesses bypass the arrays as one scalar request;
  *   - a dirty victim is never overwritten until its writeback is acknowledged.
  *
  * The CPU and lower-level interfaces retain the project's pulse protocol.
  * Address slicing follows the frozen profile: offset=addr[4:0], set=addr[10:5],
  * tag=addr[31:11] (32-bit physical tag; PMA rejects wider addresses first).
  */
class BreezeDCache(val cfg: DefaultDCacheConfig = DefaultDCacheConfig()) extends Module {
  private val ways = cfg.ways
  private val sets = cfg.sets
  private val wordsPerLine = cfg.lineBytes / 8
  private val wordIndexWidth = math.max(1, log2Ceil(wordsPerLine))

  require(cfg.VLEN == 64, "The Breeze DCache requires RV64")
  require(ways == 4, "The Breeze DCache PLRU is fixed to 4 ways")

  private val validBits = ways
  private val dirtyOffset = ways
  private val plruOffset = 2 * ways
  private val metaWidth = 2 * ways + (ways - 1)

  private def validOf(meta: UInt): UInt = meta(validBits - 1, 0)
  private def dirtyOf(meta: UInt): UInt = meta(dirtyOffset + ways - 1, dirtyOffset)
  private def plruOf(meta: UInt): UInt = meta(metaWidth - 1, plruOffset)
  private def makeMeta(valid: UInt, dirty: UInt, plru: UInt): UInt =
    Cat(plru, dirty, valid)

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
  })

  import BreezeDCacheState._

  io.hpm := 0.U.asTypeOf(new BreezeHpmEvents)

  // ===== Storage =====
  // Metadata: [plru(3) | dirty(4) | valid(4)] per set, resettable to all-invalid.
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

  val victimWayReg = RegInit(0.U(cfg.wayIndexWidth.W))
  val newValidReg = RegInit(0.U(ways.W))
  val newPlruReg = RegInit(0.U((ways - 1).W))
  val victimTagReg = RegInit(0.U(cfg.tagWidth.W))
  val victimDataReg = RegInit(0.U(cfg.lineWidth.W))

  val responseData = RegInit(0.U(64.W))
  val responseError = RegInit(false.B)
  val responseIsWrite = RegInit(false.B)
  val flushIndex = RegInit(0.U(8.W))
  val fatalErrorReg = RegInit(false.B)

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

  // ===== PMA =====
  val pma = Module(new PMAChecker)
  pma.io.query.addr := reqAddr
  pma.io.query.sizeLog2 := reqSizeLog2
  pma.io.query.accessType := Mux(reqIsWrite, PMAAccessType.Store, PMAAccessType.Load)

  // ===== SRAM read/write control =====
  val incomingSetIndex = io.cpu.req.addr(10, 5)
  val flushSetIndex = flushIndex(7, 2)
  val arrayReadEnable = (state === Idle && io.cpu.req.valid) || (state === FlushScan)
  val arrayReadAddr = Mux(state === Idle && io.cpu.req.valid, incomingSetIndex, flushSetIndex)
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

  io.hpm.dcacheAccess := state === Idle && io.cpu.req.valid && !io.flushReq
  io.hpm.dcacheMiss := state === Lookup && pma.io.result.allowed &&
    pma.io.result.cacheable && !pma.io.result.device && !hit
  io.hpm.dcacheUncached := state === Lookup && pma.io.result.allowed &&
    (!pma.io.result.cacheable || pma.io.result.device)

  when(io.cpu.req.valid) {
    assert(state === Idle && !io.flushReq,
      "DCache CPU request pulse arrived while the blocking DCache was busy")
  }
  when(io.flushReq) {
    assert(state === Idle,
      "DCache flush request pulse arrived while the blocking DCache was busy")
  }

  switch(state) {
    is(Idle) {
      when(io.flushReq) {
        flushIndex := 0.U
        state := FlushScan
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
          for (w <- 0 until ways) {
            when(hitWay === w.U) {
              dataArray(w).io.we := true.B
              dataArray(w).io.addr := setIndex
              dataArray(w).io.data_in := mergeStore(dataRdata(hitWay), reqAddr, reqWData, reqWMask)
            }
          }
          metaReg(setIndex) := makeMeta(
            validOf(metaReg(setIndex)),
            dirtyOf(metaReg(setIndex)) | (1.U << hitWay),
            touchWay(plruOf(metaReg(setIndex)), hitWay)
          )
          responseData := 0.U
        }.otherwise {
          metaReg(setIndex) := makeMeta(
            validOf(metaReg(setIndex)),
            dirtyOf(metaReg(setIndex)),
            touchWay(plruOf(metaReg(setIndex)), hitWay)
          )
          responseData := lineWord(dataRdata(hitWay), reqAddr)
        }
        responseError := false.B
        state := Respond
      }.otherwise {
        victimWayReg := victimWay
        newValidReg := newValidVec
        newPlruReg := newPlruVec
        victimTagReg := tagRdata(victimWay)
        victimDataReg := dataRdata(victimWay)
        when(victimIsDirty) {
          state := WritebackReq
        }.otherwise {
          state := RefillReq
        }
      }
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

    is(RefillReq) {
      io.nextLevelReq.req := true.B
      io.nextLevelReq.addr := requestLineBase64
      io.nextLevelReq.isWrite := false.B
      io.nextLevelReq.isLine := true.B
      io.nextLevelReq.data := 0.U
      io.nextLevelReq.mask := Fill(cfg.lineBytes, 1.U(1.W))
      state := RefillWait
    }

    is(RefillWait) {
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
          metaReg(setIndex) := makeMeta(newValidReg, newDirty, newPlruReg)
          responseData := Mux(reqIsWrite, 0.U, lineWord(io.nextLevelRsp.data, reqAddr))
          responseError := false.B
          state := Respond
        }
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
      when(validOf(metaReg(set))(way) && dirtyOf(metaReg(set))(way)) {
        victimWayReg := way
        victimTagReg := tagRdata(way)
        victimDataReg := dataRdata(way)
        state := FlushWritebackReq
      }.otherwise {
        metaReg(set) := makeMeta(
          validOf(metaReg(set)) & ~(1.U << way),
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
      val set = flushIndex(7, 2)
      io.nextLevelReq.req := true.B
      io.nextLevelReq.addr := Cat(0.U(32.W), victimTagReg, set, 0.U(5.W))
      io.nextLevelReq.isWrite := true.B
      io.nextLevelReq.isLine := true.B
      io.nextLevelReq.data := victimDataReg
      io.nextLevelReq.mask := Fill(cfg.lineBytes, 1.U(1.W))
      state := FlushWritebackWait
    }

    is(FlushWritebackWait) {
      when(io.nextLevelRsp.vld) {
        when(io.nextLevelRsp.error) {
          // A retired store can no longer take a precise exception here.
          fatalErrorReg := true.B
          state := Fatal
        }.otherwise {
          val set = flushIndex(7, 2)
          val way = flushIndex(1, 0)
          metaReg(set) := makeMeta(
            validOf(metaReg(set)) & ~(1.U << way),
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

    is(FlushRespond) {
      state := Idle
    }

    is(Fatal) {
      // Sticky until reset.
      state := Fatal
    }
  }
}
