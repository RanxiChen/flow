package flow.cache

import chisel3._
import chisel3.util._
import flow.config.DefaultDCacheConfig
import flow.interface._
import flow.platform.{PMAAccessType, PMAChecker}

object BreezeDCacheState extends ChiselEnum {
  val Idle, Lookup,
      UncachedReq, UncachedWait,
      WritebackReq, WritebackWait,
      RefillReq, RefillWait,
      Respond,
      FlushScan, FlushWritebackReq, FlushWritebackWait, FlushRespond,
      Fatal = Value
}

/** Small blocking, fully-associative L1 data cache.
  *
  * Frozen policy for the first implementation:
  *   - write-back and write-allocate;
  *   - register-array storage;
  *   - invalid-first, then round-robin replacement;
  *   - one outstanding CPU operation and one outstanding lower-level request;
  *   - PMA-denied accesses return an access error without reaching the bus;
  *   - non-cacheable/device accesses bypass the arrays as one scalar request.
  *
  * The CPU and lower-level interfaces intentionally retain the project's
  * pulse protocol. Both sides are dedicated blocking peers and must capture a
  * request pulse while idle. Assertions catch violations of that contract.
  */
class BreezeDCache(val cfg: DefaultDCacheConfig = DefaultDCacheConfig()) extends Module {
  private val entryIndexWidth = math.max(1, log2Ceil(cfg.entryNum))
  private val wordsPerLine = cfg.lineBytes / 8
  private val wordIndexWidth = math.max(1, log2Ceil(wordsPerLine))

  require(cfg.VLEN == 64, "The first Breeze DCache implementation requires RV64")
  require(cfg.PLEN == 64, "The core-side DCache address is kept as a 64-bit physical address")

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

  val state = RegInit(Idle)
  val validArray = RegInit(VecInit(Seq.fill(cfg.entryNum)(false.B)))
  val dirtyArray = RegInit(VecInit(Seq.fill(cfg.entryNum)(false.B)))
  val tagArray = Reg(Vec(cfg.entryNum, UInt(cfg.tagWidth.W)))
  val dataArray = Reg(Vec(cfg.entryNum, UInt(cfg.lineWidth.W)))
  val replacementPtr = RegInit(0.U(entryIndexWidth.W))

  val reqAddr = RegInit(0.U(cfg.VLEN.W))
  val reqIsWrite = RegInit(false.B)
  val reqSizeLog2 = RegInit(0.U(3.W))
  val reqWData = RegInit(0.U(64.W))
  val reqWMask = RegInit(0.U(8.W))
  val victimIndex = RegInit(0.U(entryIndexWidth.W))

  val responseData = RegInit(0.U(64.W))
  val responseError = RegInit(false.B)
  val responseIsWrite = RegInit(false.B)
  val flushIndex = RegInit(0.U(entryIndexWidth.W))
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

  val pma = Module(new PMAChecker)
  pma.io.query.addr := reqAddr
  pma.io.query.sizeLog2 := reqSizeLog2
  pma.io.query.accessType := Mux(reqIsWrite, PMAAccessType.Store, PMAAccessType.Load)

  val requestTag = reqAddr(cfg.PLEN - 1, cfg.lineOffsetWidth)
  val requestLineBase = Cat(requestTag, 0.U(cfg.lineOffsetWidth.W))
  val requestBeatBase = Cat(reqAddr(cfg.PLEN - 1, 3), 0.U(3.W))
  val hitVector = VecInit((0 until cfg.entryNum).map { index =>
    validArray(index) && tagArray(index) === requestTag
  })
  val hit = hitVector.asUInt.orR
  val hitIndex = PriorityEncoder(hitVector.asUInt)
  val invalidVector = VecInit((0 until cfg.entryNum).map(index => !validArray(index)))
  val hasInvalid = invalidVector.asUInt.orR
  val firstInvalid = PriorityEncoder(invalidVector.asUInt)
  val selectedVictim = Mux(hasInvalid, firstInvalid, replacementPtr)

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
          dataArray(hitIndex) := mergeStore(dataArray(hitIndex), reqAddr, reqWData, reqWMask)
          dirtyArray(hitIndex) := true.B
          responseData := 0.U
        }.otherwise {
          responseData := lineWord(dataArray(hitIndex), reqAddr)
        }
        responseError := false.B
        state := Respond
      }.otherwise {
        victimIndex := selectedVictim
        when(validArray(selectedVictim) && dirtyArray(selectedVictim)) {
          state := WritebackReq
        }.otherwise {
          state := RefillReq
        }
      }
    }

    is(UncachedReq) {
      io.nextLevelReq.req := true.B
      io.nextLevelReq.addr := requestBeatBase
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
      io.nextLevelReq.addr := Cat(tagArray(victimIndex), 0.U(cfg.lineOffsetWidth.W))
      io.nextLevelReq.isWrite := true.B
      io.nextLevelReq.isLine := true.B
      io.nextLevelReq.data := dataArray(victimIndex)
      io.nextLevelReq.mask := Fill(cfg.lineBytes, 1.U(1.W))
      state := WritebackWait
    }

    is(WritebackWait) {
      when(io.nextLevelRsp.vld) {
        when(io.nextLevelRsp.error) {
          // The line is deliberately kept valid+dirty. The access that caused
          // the eviction receives the error and may be retried by software.
          responseError := true.B
          state := Respond
        }.otherwise {
          dirtyArray(victimIndex) := false.B
          state := RefillReq
        }
      }
    }

    is(RefillReq) {
      io.nextLevelReq.req := true.B
      io.nextLevelReq.addr := requestLineBase
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
          tagArray(victimIndex) := requestTag
          dataArray(victimIndex) := installedLine
          validArray(victimIndex) := true.B
          dirtyArray(victimIndex) := reqIsWrite
          replacementPtr := Mux(
            victimIndex === (cfg.entryNum - 1).U,
            0.U,
            victimIndex + 1.U
          )
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
      when(validArray(flushIndex) && dirtyArray(flushIndex)) {
        state := FlushWritebackReq
      }.otherwise {
        validArray(flushIndex) := false.B
        dirtyArray(flushIndex) := false.B
        when(flushIndex === (cfg.entryNum - 1).U) {
          state := FlushRespond
        }.otherwise {
          flushIndex := flushIndex + 1.U
        }
      }
    }

    is(FlushWritebackReq) {
      io.nextLevelReq.req := true.B
      io.nextLevelReq.addr := Cat(tagArray(flushIndex), 0.U(cfg.lineOffsetWidth.W))
      io.nextLevelReq.isWrite := true.B
      io.nextLevelReq.isLine := true.B
      io.nextLevelReq.data := dataArray(flushIndex)
      io.nextLevelReq.mask := Fill(cfg.lineBytes, 1.U(1.W))
      state := FlushWritebackWait
    }

    is(FlushWritebackWait) {
      when(io.nextLevelRsp.vld) {
        when(io.nextLevelRsp.error) {
          // A retired store can no longer take a precise exception here.
          // Preserve the dirty line and stop in an externally visible fatal
          // state rather than silently discarding modified data.
          fatalErrorReg := true.B
          state := Fatal
        }.otherwise {
          validArray(flushIndex) := false.B
          dirtyArray(flushIndex) := false.B
          when(flushIndex === (cfg.entryNum - 1).U) {
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
      // Sticky until reset. A later machine-error mechanism can consume the
      // fatalError output without changing the cache/bus protocol.
      state := Fatal
    }
  }
}
