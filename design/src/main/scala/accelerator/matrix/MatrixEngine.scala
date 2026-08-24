package flow.accelerator.matrix

import chisel3._
import chisel3.util._
import flow.bus.{LiteXWishboneMasterIO, LiteXWishboneParameters}

object MatrixEngineState extends ChiselEnum {
  val Idle, Clear, LoadC, Run, Drain = Value
}

/** Single-command, single-buffer INT8 matrix engine for resource-limited SoCs.
  *
  * Software packs one A column and one B row into each 32-bit SPM word.  One
  * command computes C[m x n] += A[m x k] * B[k x n], where m/n are at most
  * the physical array dimensions.  Large matrices and tiles are software
  * policy.
  */
class MatrixEngine(
    val rows: Int = 4,
    val cols: Int = 4,
    val spmDepth: Int = 256,
    val inputWidth: Int = 8,
    val accWidth: Int = 32) extends Module {
  require(rows == 4 && cols == 4,
    "the first Wishbone SPM layout packs exactly four INT8 lanes per word")
  require(inputWidth == 8, "the first SPM layout is INT8")
  require(isPow2(spmDepth), "SPM depth must be a power of two")

  private val spmAddrWidth = log2Ceil(spmDepth)
  private val kWidth = log2Ceil(spmDepth + 1)
  private val runWidth = log2Ceil(spmDepth + rows + cols + 1)
  private val wbParams = LiteXWishboneParameters(32, 32)

  val io = IO(new Bundle {
    val start = Input(Bool())
    val m = Input(UInt(3.W))
    val n = Input(UInt(3.W))
    val k = Input(UInt(kWidth.W))
    val aBase = Input(UInt(spmAddrWidth.W))
    val bBase = Input(UInt(spmAddrWidth.W))
    val cBase = Input(UInt(spmAddrWidth.W))

    val busy = Output(Bool())
    val done = Output(Bool())
    val error = Output(Bool())

    // One 16 KiB MMIO region: A at +0x0000, B at +0x1000,
    // C (row-major INT32 words) at +0x2000.
    val spm = Flipped(new LiteXWishboneMasterIO(wbParams))
  })

  private val byteVec = Vec(4, UInt(8.W))
  private val aMem = Module(new MatrixSpmRam(spmDepth))
  private val bMem = Module(new MatrixSpmRam(spmDepth))
  private val cMem = Seq.fill(cols)(Module(new MatrixCSpmRam(spmDepth)))

  // --------------------------------------------------------------------------
  // CPU-facing synchronous Wishbone SPM port.
  // --------------------------------------------------------------------------
  private val aWordBase = 0x000
  private val bWordBase = 0x400
  private val cWordBase = 0x800
  private val localWord = io.spm.adr(11, 0)
  private val aSelected = localWord >= aWordBase.U && localWord < (aWordBase + spmDepth).U
  private val bSelected = localWord >= bWordBase.U && localWord < (bWordBase + spmDepth).U
  private val cSelected = localWord >= cWordBase.U && localWord < (cWordBase + 4 * spmDepth).U
  private val aCpuAddr = localWord(spmAddrWidth - 1, 0)
  private val bCpuAddr = (localWord - bWordBase.U)(spmAddrWidth - 1, 0)
  private val cLinear = localWord - cWordBase.U
  private val cCpuBank = cLinear(1, 0)
  private val cCpuRow = cLinear(spmAddrWidth + 1, 2)
  private val engineOwnsSpm = Wire(Bool())
  private val request = io.spm.cyc && io.spm.stb
  private val pending = RegInit(false.B)
  private val pendingError = RegInit(false.B)
  private val readRegion = RegInit(0.U(2.W))
  private val readCBank = RegInit(0.U(2.W))
  private val readRequest = request && !pending && !io.spm.we && !engineOwnsSpm
  private val writeRequest = request && !pending && io.spm.we && !engineOwnsSpm
  aMem.io.cpuAddr := aCpuAddr
  aMem.io.cpuReadEnable := readRequest && aSelected
  aMem.io.cpuWriteEnable := Mux(writeRequest && aSelected, io.spm.sel, 0.U)
  aMem.io.cpuWriteData := io.spm.dat_w
  bMem.io.cpuAddr := bCpuAddr
  bMem.io.cpuReadEnable := readRequest && bSelected
  bMem.io.cpuWriteEnable := Mux(writeRequest && bSelected, io.spm.sel, 0.U)
  bMem.io.cpuWriteData := io.spm.dat_w
  for (bank <- 0 until cols) {
    cMem(bank).io.cpuAddr := cCpuRow
    cMem(bank).io.cpuReadEnable := readRequest && cSelected && cCpuBank === bank.U
    // C contains INT32 words.  Partial-byte C writes are intentionally not a
    // supported software operation; RV64 SW produces sel=1111.
    cMem(bank).io.cpuWriteEnable := writeRequest && cSelected &&
      cCpuBank === bank.U && io.spm.sel.andR
    cMem(bank).io.cpuWriteData := io.spm.dat_w
  }

  when(pending) {
    pending := false.B
    pendingError := false.B
  }.elsewhen(request) {
    pending := true.B
    pendingError := engineOwnsSpm
    when(!io.spm.we && !engineOwnsSpm) {
      readRegion := Mux(aSelected, 1.U, Mux(bSelected, 2.U,
        Mux(cSelected, 3.U, 0.U)))
      readCBank := cCpuBank
    }
  }

  private val cReadMux = MuxLookup(readCBank, 0.U)(
    cMem.zipWithIndex.map { case (memory, bank) => bank.U -> memory.io.cpuReadData })
  io.spm.ack := pending && !pendingError
  io.spm.err := pending && pendingError
  io.spm.dat_r := MuxLookup(readRegion, 0.U)(Seq(
    1.U -> aMem.io.cpuReadData,
    2.U -> bMem.io.cpuReadData,
    3.U -> cReadMux))

  // --------------------------------------------------------------------------
  // Command state and systolic data path.
  // --------------------------------------------------------------------------
  private val state = RegInit(MatrixEngineState.Idle)
  engineOwnsSpm := state =/= MatrixEngineState.Idle
  private val doneReg = RegInit(false.B)
  private val errorReg = RegInit(false.B)
  private val mReg = Reg(UInt(3.W))
  private val nReg = Reg(UInt(3.W))
  private val kReg = Reg(UInt(kWidth.W))
  private val aBaseReg = Reg(UInt(spmAddrWidth.W))
  private val bBaseReg = Reg(UInt(spmAddrWidth.W))
  private val cBaseReg = Reg(UInt(spmAddrWidth.W))
  private val runCycle = RegInit(0.U(runWidth.W))
  private val loadIssueRow = RegInit(0.U(2.W))
  private val loadReadRow = RegInit(0.U(2.W))
  private val loadReadValid = RegInit(false.B)
  private val drainRow = RegInit(0.U(2.W))

  private val invalidShape = io.m === 0.U || io.m > rows.U ||
    io.n === 0.U || io.n > cols.U || io.k === 0.U || io.k > spmDepth.U
  private val aEnd = io.aBase.pad(kWidth + 1) + io.k
  private val bEnd = io.bBase.pad(kWidth + 1) + io.k
  private val cEnd = io.cBase.pad(spmAddrWidth + 1) + io.m
  private val invalidBounds = aEnd > spmDepth.U || bEnd > spmDepth.U ||
    cEnd > spmDepth.U

  io.busy := state =/= MatrixEngineState.Idle
  io.done := doneReg
  io.error := errorReg

  when(io.start && state =/= MatrixEngineState.Idle) {
    errorReg := true.B
  }.elsewhen(io.start) {
    doneReg := false.B
    errorReg := false.B
    when(invalidShape || invalidBounds) {
      errorReg := true.B
    }.otherwise {
      mReg := io.m
      nReg := io.n
      kReg := io.k
      aBaseReg := io.aBase
      bBaseReg := io.bBase
      cBaseReg := io.cBase
      runCycle := 0.U
      loadIssueRow := 0.U
      loadReadValid := false.B
      state := MatrixEngineState.Clear
    }
  }

  private val array = Module(new SystolicArray(rows, cols, inputWidth, accWidth))
  array.io.clear := state === MatrixEngineState.Clear
  for (row <- 0 until rows; col <- 0 until cols) {
    array.io.loadAccumulator(row)(col) := state === MatrixEngineState.LoadC &&
      loadReadValid && loadReadRow === row.U && col.U < nReg
    array.io.accumulatorIn(row)(col) := cMem(col).io.engineReadData.asSInt
  }

  private val issue = state === MatrixEngineState.Run && runCycle < kReg
  private val engineOffset = runCycle(spmAddrWidth - 1, 0)
  aMem.io.engineAddr := aBaseReg + engineOffset
  aMem.io.engineReadEnable := issue
  aMem.io.engineWriteEnable := false.B
  aMem.io.engineWriteData := 0.U
  bMem.io.engineAddr := bBaseReg + engineOffset
  bMem.io.engineReadEnable := issue
  bMem.io.engineWriteEnable := false.B
  bMem.io.engineWriteData := 0.U
  private val readValid = RegInit(false.B)
  when(state === MatrixEngineState.Run) {
    readValid := issue
  }.otherwise {
    readValid := false.B
  }

  private val aSkewData = Seq.tabulate(rows)(row =>
    if (row == 0) Seq.empty else Seq.fill(row)(RegInit(0.S(inputWidth.W))))
  private val aSkewValid = Seq.tabulate(rows)(row =>
    if (row == 0) Seq.empty else Seq.fill(row)(RegInit(false.B)))
  private val bSkewData = Seq.tabulate(cols)(col =>
    if (col == 0) Seq.empty else Seq.fill(col)(RegInit(0.S(inputWidth.W))))
  private val bSkewValid = Seq.tabulate(cols)(col =>
    if (col == 0) Seq.empty else Seq.fill(col)(RegInit(false.B)))

  private val aEngineRead = aMem.io.engineReadData.asTypeOf(byteVec)
  private val bEngineRead = bMem.io.engineReadData.asTypeOf(byteVec)
  private val rawA = VecInit.tabulate(rows)(lane => aEngineRead(lane).asSInt)
  private val rawB = VecInit.tabulate(cols)(lane => bEngineRead(lane).asSInt)

  for (row <- 0 until rows) {
    val laneValid = readValid && row.U < mReg
    if (row == 0) {
      array.io.aIn(row) := rawA(row)
      array.io.aValid(row) := laneValid
    } else {
      array.io.aIn(row) := aSkewData(row).last
      array.io.aValid(row) := aSkewValid(row).last
    }
  }
  for (col <- 0 until cols) {
    val laneValid = readValid && col.U < nReg
    if (col == 0) {
      array.io.bIn(col) := rawB(col)
      array.io.bValid(col) := laneValid
    } else {
      array.io.bIn(col) := bSkewData(col).last
      array.io.bValid(col) := bSkewValid(col).last
    }
  }

  when(state === MatrixEngineState.Clear) {
    readValid := false.B
    for (row <- 1 until rows; stage <- 0 until row) {
      aSkewData(row)(stage) := 0.S
      aSkewValid(row)(stage) := false.B
    }
    for (col <- 1 until cols; stage <- 0 until col) {
      bSkewData(col)(stage) := 0.S
      bSkewValid(col)(stage) := false.B
    }
    runCycle := 0.U
    loadIssueRow := 0.U
    loadReadValid := false.B
    state := MatrixEngineState.LoadC
  }.elsewhen(state === MatrixEngineState.LoadC) {
    val loadIssue = loadIssueRow < mReg
    loadReadValid := loadIssue
    when(loadIssue) {
      loadReadRow := loadIssueRow
      loadIssueRow := loadIssueRow + 1.U
    }
    when(loadReadValid && loadReadRow === mReg - 1.U) {
      loadReadValid := false.B
      runCycle := 0.U
      state := MatrixEngineState.Run
    }
  }.elsewhen(state === MatrixEngineState.Run) {
    for (row <- 1 until rows) {
      aSkewData(row).head := rawA(row)
      aSkewValid(row).head := readValid && row.U < mReg
      for (stage <- 1 until row) {
        aSkewData(row)(stage) := aSkewData(row)(stage - 1)
        aSkewValid(row)(stage) := aSkewValid(row)(stage - 1)
      }
    }
    for (col <- 1 until cols) {
      bSkewData(col).head := rawB(col)
      bSkewValid(col).head := readValid && col.U < nReg
      for (stage <- 1 until col) {
        bSkewData(col)(stage) := bSkewData(col)(stage - 1)
        bSkewValid(col)(stage) := bSkewValid(col)(stage - 1)
      }
    }

    when(runCycle === kReg + (rows + cols - 2).U) {
      drainRow := 0.U
      state := MatrixEngineState.Drain
    }.otherwise {
      runCycle := runCycle + 1.U
    }
  }

  for (col <- 0 until cols) {
    val loadingC = state === MatrixEngineState.LoadC && loadIssueRow < mReg
    cMem(col).io.engineAddr := cBaseReg + Mux(loadingC, loadIssueRow, drainRow)
    cMem(col).io.engineReadEnable := loadingC
    cMem(col).io.engineWriteEnable :=
      state === MatrixEngineState.Drain && col.U < nReg
    cMem(col).io.engineWriteData := array.io.accumulators(drainRow)(col).asUInt
  }

  when(state === MatrixEngineState.Drain) {
    when(drainRow === mReg - 1.U) {
      doneReg := true.B
      state := MatrixEngineState.Idle
    }.otherwise {
      drainRow := drainRow + 1.U
    }
  }
}
