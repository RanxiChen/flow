package flow.air

import chisel3._
import chisel3.util._
import flow.bus.{LiteXWishboneMasterIO, LiteXWishboneParameters, WishboneBurstType, WishboneCycleType}

object AirState extends ChiselEnum {
  val Fetch, FetchSecond, Dispatch,
      WaitA, WaitB, ImmWait, DirectWb, FillWb, WorkWb,
      ShiftAmountWait, ShiftLoadWait, ShiftStart, ShiftByte,
      AddressWait, StoreReadWait, MemRequest,
      PcTarget, LinkWb, SequentialPc,
      CsrSourceWait, CsrExecute = Value
}

class AirTrace extends Bundle {
  val valid = Bool()
  val pc = UInt(64.W)
  val instruction = UInt(32.W)
  val instructionBytes = UInt(3.W)
  val rd = UInt(5.W)
  val rdWrite = Bool()
  val rdValue = UInt(64.W)
  val trap = Bool()
  val cause = UInt(64.W)
}

/** Air: an independent area-first RV64I or RV64IC serialized MCU core.
  *
  * Only one instruction is active.  The integer RF is one physical 1R1W M9K;
  * register-register operations pipeline A and B reads on alternate clocks.
  * Ordinary ALU results write directly into the RF and there is no universal
  * commit state.  All architectural addresses are RV64 values, while the
  * implemented physical address width is deliberately 32 bits.
  */
class AirCore(
    resetVector: BigInt = 0,
    withCompressed: Boolean = false,
    withTrace: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val wb = new LiteXWishboneMasterIO(LiteXWishboneParameters(32, 32))
    val trace = Output(new AirTrace)
    val areaProbe = Output(Bool())
  })

  val state = RegInit(AirState.Fetch)
  val pc = RegInit((resetVector & 0xffffffffL).U(32.W))
  val instruction = Reg(UInt(32.W))
  val instructionBytes = RegInit(4.U(3.W))
  val instructionPc = Reg(UInt(32.W))
  val fetchedIllegal = RegInit(false.B)

  val parcelValid = RegInit(false.B)
  val parcelPc = Reg(UInt(32.W))
  val parcel = Reg(UInt(16.W))
  val splitLow = Reg(UInt(16.W))

  val work = Reg(Vec(8, UInt(8.W)))
  val address = Reg(UInt(32.W))
  val aHold = Reg(UInt(8.W))
  val byteIndex = RegInit(0.U(3.W))
  val carry = RegInit(false.B)
  val equal = RegInit(true.B)
  val highNonZero = RegInit(false.B)
  val fillSign = Reg(Bool())
  val shiftRemaining = Reg(UInt(6.W))
  val shiftCarry = Reg(Bool())
  val memBeat = RegInit(0.U(1.W))
  val storeByte = RegInit(0.U(2.W))

  // Air v0 keeps the architecturally visible CSR values straightforward.  A
  // later CSR-RAM experiment can replace this bank without changing the core
  // instruction paths.
  val mstatus = RegInit(0.U(64.W))
  val mtvec = RegInit(0.U(64.W))
  val mscratch = RegInit(0.U(64.W))
  val mepc = RegInit(0.U(64.W))
  val mcause = RegInit(0.U(64.W))
  val mtval = RegInit(0.U(64.W))

  val traceValid = RegInit(false.B)
  val traceRdWrite = RegInit(false.B)
  val traceRdValue = RegInit(0.U(64.W))
  val traceTrap = RegInit(false.B)
  val traceCause = RegInit(0.U(64.W))

  val workUInt = work.asUInt
  val dec = Module(new AirDecode)
  dec.io.instruction := instruction
  dec.io.byteIndex := byteIndex

  val rvcHalf = WireDefault(0.U(16.W))
  val rvcOut = WireDefault(0.U(32.W))
  val rvcLegal = WireDefault(false.B)
  if (withCompressed) {
    val rvc = Module(new AirRvcDecompressor)
    rvc.io.in := rvcHalf
    rvcOut := rvc.io.out
    rvcLegal := rvc.io.legal
  }

  val rf = Module(new AirRegisterFile)
  rf.io.readEnable := false.B
  rf.io.readReg := dec.io.rs1
  rf.io.readByte := byteIndex
  rf.io.writeEnable := false.B
  rf.io.writeReg := dec.io.rd
  rf.io.writeByte := byteIndex
  rf.io.writeData := 0.U

  val useParcel = if (withCompressed) parcelValid && parcelPc === pc else false.B
  val fetchBus = state === AirState.Fetch && !useParcel
  val secondBus = state === AirState.FetchSecond
  val memoryBus = state === AirState.MemRequest
  io.wb.cyc := fetchBus || secondBus || memoryBus
  io.wb.stb := io.wb.cyc
  io.wb.we := memoryBus && isStore(dec.io.op)
  io.wb.adr := Mux(secondBus, (pc >> 2) + 1.U,
    Mux(memoryBus, (address >> 2) + memBeat, pc >> 2))
  io.wb.dat_w := workUInt(31, 0)
  io.wb.sel := Mux(isStore(dec.io.op), storeSelect(dec.io.op, address(1, 0)), "b1111".U)
  io.wb.cti := WishboneCycleType.Classic
  io.wb.bte := WishboneBurstType.Linear

  traceValid := false.B
  io.trace.valid := (if (withTrace) traceValid else false.B)
  io.trace.pc := (if (withTrace) instructionPc.pad(64) else 0.U)
  io.trace.instruction := (if (withTrace) instruction else 0.U)
  io.trace.instructionBytes := (if (withTrace) instructionBytes else 0.U)
  io.trace.rd := (if (withTrace) dec.io.rd else 0.U)
  io.trace.rdWrite := (if (withTrace) traceRdWrite else false.B)
  io.trace.rdValue := (if (withTrace) traceRdValue else 0.U)
  io.trace.trap := (if (withTrace) traceTrap else false.B)
  io.trace.cause := (if (withTrace) traceCause else 0.U)
  // A production-visible signal without Wisp's large all-state XOR tree.
  io.areaProbe := pc(2)

  def isBranch(op: AirOp.Type): Bool = op >= AirOp.Beq && op <= AirOp.Bgeu
  def isLoad(op: AirOp.Type): Bool = op >= AirOp.Lb && op <= AirOp.Lwu
  def isStore(op: AirOp.Type): Bool = op >= AirOp.Sb && op <= AirOp.Sd
  def isCsr(op: AirOp.Type): Bool = op >= AirOp.Csrrw && op <= AirOp.Csrrc
  def isShift(op: AirOp.Type): Bool = Seq(AirOp.Sll, AirOp.Srl, AirOp.Sra,
    AirOp.Sllw, AirOp.Srlw, AirOp.Sraw).map(op === _).reduce(_ || _)
  def isWord(op: AirOp.Type): Bool = Seq(AirOp.Addw, AirOp.Subw, AirOp.Sllw,
    AirOp.Srlw, AirOp.Sraw).map(op === _).reduce(_ || _)
  def isCompare(op: AirOp.Type): Bool = op === AirOp.Slt || op === AirOp.Sltu || isBranch(op)
  def isSubtract(op: AirOp.Type): Bool = op === AirOp.Sub || op === AirOp.Subw || isCompare(op)
  def writesRd(op: AirOp.Type): Bool = !(isBranch(op) || isStore(op) ||
    op === AirOp.Fence || op === AirOp.FenceI || op === AirOp.Ecall ||
    op === AirOp.Ebreak || op === AirOp.Mret || op === AirOp.Illegal)

  def loadBytes(op: AirOp.Type): UInt = MuxLookup(op.asUInt, 1.U)(Seq(
    AirOp.Lh.asUInt -> 2.U, AirOp.Lhu.asUInt -> 2.U,
    AirOp.Lw.asUInt -> 4.U, AirOp.Lwu.asUInt -> 4.U, AirOp.Ld.asUInt -> 8.U))
  def storeBytes(op: AirOp.Type): UInt = MuxLookup(op.asUInt, 1.U)(Seq(
    AirOp.Sh.asUInt -> 2.U, AirOp.Sw.asUInt -> 4.U, AirOp.Sd.asUInt -> 8.U))
  def storeSelect(op: AirOp.Type, low: UInt): UInt = MuxLookup(op.asUInt, "b0001".U)(Seq(
    AirOp.Sb.asUInt -> (1.U(4.W) << low), AirOp.Sh.asUInt -> (3.U(4.W) << low),
    AirOp.Sw.asUInt -> "b1111".U, AirOp.Sd.asUInt -> "b1111".U))

  def csrLegal(a: UInt): Bool = Seq("h300", "h301", "h305", "h340", "h341", "h342", "h343", "hf14")
    .map(x => a === x.U).reduce(_ || _)
  def csrRead(a: UInt): UInt = MuxLookup(a, 0.U)(Seq(
    "h300".U -> mstatus, "h301".U -> Mux(withCompressed.B,
      "h8000000000000104".U, "h8000000000000100".U),
    "h305".U -> mtvec, "h340".U -> mscratch, "h341".U -> mepc,
    "h342".U -> mcause, "h343".U -> mtval, "hf14".U -> 0.U))

  def setWorkByte(index: UInt, data: UInt): Unit = {
    for (i <- 0 until 8) { when(index === i.U) { work(i) := data } }
  }
  def setTraceByte(index: UInt, data: UInt): Unit = {
    if (withTrace) {
      val bytes = traceRdValue.asTypeOf(Vec(8, UInt(8.W)))
      for (i <- 0 until 8) { when(index === i.U) { traceRdValue :=
        Cat((0 until 8).reverse.map(j => if (j == i) data else bytes(j))) } }
    }
  }
  def issueRead(reg: UInt, index: UInt): Unit = {
    rf.io.readEnable := true.B; rf.io.readReg := reg; rf.io.readByte := index
  }
  def writeRd(index: UInt, data: UInt): Unit = {
    rf.io.writeEnable := true.B; rf.io.writeByte := index; rf.io.writeData := data
    setTraceByte(index, data)
  }
  def retire(rdWrite: Bool, rdValue: UInt = 0.U): Unit = {
    traceValid := true.B
    traceRdWrite := rdWrite && dec.io.rd =/= 0.U
    if (withTrace) { when(!rdWrite) { traceRdValue := rdValue } }
    traceTrap := false.B
    traceCause := 0.U
    state := AirState.Fetch
  }
  def takeTrap(cause: UInt, value: UInt): Unit = {
    mepc := instructionPc.pad(64)
    mcause := cause
    mtval := value
    pc := mtvec(31, 0)
    parcelValid := false.B
    traceValid := true.B
    traceRdWrite := false.B
    traceRdValue := 0.U
    traceTrap := true.B
    traceCause := cause
    state := AirState.Fetch
  }
  def startSequentialPc(): Unit = {
    byteIndex := 0.U; carry := false.B; state := AirState.SequentialPc
  }
  def targetMisaligned(target: UInt): Bool =
    if (withCompressed) target(0) else target(1, 0).orR

  switch(state) {
    is(AirState.Fetch) {
      val badPc = if (withCompressed) pc(0) else pc(1, 0).orR
      when(badPc) {
        instructionPc := pc
        takeTrap(0.U, pc.pad(64))
      }.elsewhen(useParcel) {
        rvcHalf := parcel
        instructionPc := pc
        parcelValid := false.B
        when(parcel(1, 0) =/= 3.U) {
          instruction := rvcOut
          instructionBytes := 2.U
          fetchedIllegal := !rvcLegal
          state := AirState.Dispatch
        }.otherwise {
          splitLow := parcel
          state := AirState.FetchSecond
        }
      }.elsewhen(io.wb.err) {
        instructionPc := pc
        takeTrap(1.U, pc.pad(64))
      }.elsewhen(io.wb.ack) {
        instructionPc := pc
        fetchedIllegal := false.B
        if (withCompressed) {
          val half = Mux(pc(1), io.wb.dat_r(31, 16), io.wb.dat_r(15, 0))
          rvcHalf := half
          when(half(1, 0) =/= 3.U) {
            instruction := rvcOut
            instructionBytes := 2.U
            fetchedIllegal := !rvcLegal
            when(!pc(1)) {
              parcel := io.wb.dat_r(31, 16)
              parcelPc := pc + 2.U
              parcelValid := true.B
            }.otherwise { parcelValid := false.B }
            state := AirState.Dispatch
          }.otherwise {
            parcelValid := false.B
            when(!pc(1)) {
              instruction := io.wb.dat_r
              instructionBytes := 4.U
              state := AirState.Dispatch
            }.otherwise {
              splitLow := half
              state := AirState.FetchSecond
            }
          }
        } else {
          instruction := io.wb.dat_r
          instructionBytes := 4.U
          state := AirState.Dispatch
        }
      }
    }

    is(AirState.FetchSecond) {
      when(io.wb.err) { takeTrap(1.U, pc.pad(64)) }
        .elsewhen(io.wb.ack) {
          instruction := Cat(io.wb.dat_r(15, 0), splitLow)
          instructionBytes := 4.U
          fetchedIllegal := false.B
          state := AirState.Dispatch
        }
    }

    is(AirState.Dispatch) {
      traceRdValue := 0.U
      traceRdWrite := false.B
      when(fetchedIllegal || !dec.io.legal) {
        takeTrap(2.U, Mux(fetchedIllegal, instruction(15, 0), instruction).pad(64))
      }.elsewhen(dec.io.op === AirOp.Ecall) {
        takeTrap(11.U, 0.U)
      }.elsewhen(dec.io.op === AirOp.Ebreak) {
        takeTrap(3.U, 0.U)
      }.elsewhen(dec.io.op === AirOp.Mret) {
        pc := mepc(31, 0); parcelValid := false.B; retire(false.B)
      }.elsewhen(dec.io.op === AirOp.Fence || dec.io.op === AirOp.FenceI) {
        when(dec.io.op === AirOp.FenceI) { parcelValid := false.B }
        startSequentialPc()
      }.elsewhen(dec.io.op === AirOp.Lui) {
        byteIndex := 0.U; state := AirState.DirectWb
      }.elsewhen(dec.io.op === AirOp.Auipc || dec.io.op === AirOp.Jal) {
        byteIndex := 0.U; carry := false.B; highNonZero := false.B
        state := AirState.PcTarget
      }.elsewhen(isCsr(dec.io.op)) {
        byteIndex := 0.U
        when(dec.io.csrImmediate) {
          for (i <- 0 until 8) { work(i) := Mux(i.U === 0.U, dec.io.rs1, 0.U) }
          state := AirState.CsrExecute
        }.otherwise {
          issueRead(dec.io.rs1, 0.U)
          state := AirState.CsrSourceWait
        }
      }.elsewhen(isShift(dec.io.op)) {
        byteIndex := 0.U
        when(dec.io.useImmediate) {
          shiftRemaining := Mux(isWord(dec.io.op),
            Cat(0.U(1.W), instruction(24, 20)), instruction(25, 20))
          issueRead(dec.io.rs1, 0.U)
          state := AirState.ShiftLoadWait
        }.otherwise {
          issueRead(dec.io.rs2, 0.U)
          state := AirState.ShiftAmountWait
        }
      }.elsewhen(dec.io.op === AirOp.Jalr || isLoad(dec.io.op) || isStore(dec.io.op)) {
        byteIndex := 0.U; carry := false.B; highNonZero := false.B
        issueRead(dec.io.rs1, 0.U)
        state := AirState.AddressWait
      }.elsewhen(isBranch(dec.io.op) || (!dec.io.useImmediate &&
          (dec.io.op >= AirOp.Add && dec.io.op <= AirOp.Subw))) {
        byteIndex := 0.U; carry := isSubtract(dec.io.op); equal := true.B
        issueRead(dec.io.rs1, 0.U)
        state := AirState.WaitA
      }.otherwise {
        byteIndex := 0.U; carry := isSubtract(dec.io.op); equal := true.B
        issueRead(dec.io.rs1, 0.U)
        state := AirState.ImmWait
      }
    }

    is(AirState.WaitA) {
      aHold := rf.io.readData
      issueRead(dec.io.rs2, byteIndex)
      state := AirState.WaitB
    }

    is(AirState.WaitB) {
      val a = aHold
      val b = rf.io.readData
      val addB = Mux(isSubtract(dec.io.op), ~b, b)
      val sum = Cat(0.U(1.W), a) + Cat(0.U(1.W), addB) + carry
      val result = WireDefault(sum(7, 0))
      when(dec.io.op === AirOp.Xor) { result := a ^ b }
      when(dec.io.op === AirOp.Or) { result := a | b }
      when(dec.io.op === AirOp.And) { result := a & b }
      val eqNext = equal && a === b
      val unsignedLess = !sum(8)
      val signedLess = Mux(a(7) =/= b(7), a(7), unsignedLess)
      val less = Mux(dec.io.op === AirOp.Slt || dec.io.op === AirOp.Blt || dec.io.op === AirOp.Bge,
        signedLess, unsignedLess)
      carry := sum(8); equal := eqNext

      val last = Mux(isWord(dec.io.op), byteIndex === 3.U, byteIndex === 7.U)
      when(!isCompare(dec.io.op)) { writeRd(byteIndex, result) }
      when(last) {
        when(isCompare(dec.io.op)) {
          when(dec.io.op === AirOp.Slt || dec.io.op === AirOp.Sltu) {
            for (i <- 0 until 8) { work(i) := Mux(i.U === 0.U, less, 0.U) }
            byteIndex := 0.U; state := AirState.WorkWb
          }.otherwise {
            val taken = MuxLookup(dec.io.op.asUInt, false.B)(Seq(
              AirOp.Beq.asUInt -> eqNext, AirOp.Bne.asUInt -> !eqNext,
              AirOp.Blt.asUInt -> signedLess, AirOp.Bge.asUInt -> !signedLess,
              AirOp.Bltu.asUInt -> unsignedLess, AirOp.Bgeu.asUInt -> !unsignedLess))
            when(taken) {
              byteIndex := 0.U; carry := false.B; highNonZero := false.B
              state := AirState.PcTarget
            }.otherwise { startSequentialPc() }
          }
        }.elsewhen(isWord(dec.io.op)) {
          fillSign := result(7); byteIndex := 4.U; state := AirState.FillWb
        }.otherwise { startSequentialPc() }
      }.otherwise {
        byteIndex := byteIndex + 1.U
        issueRead(dec.io.rs1, byteIndex + 1.U)
        state := AirState.WaitA
      }
    }

    is(AirState.ImmWait) {
      val a = rf.io.readData
      val b = dec.io.immediateByte
      val sum = Cat(0.U(1.W), a) + Cat(0.U(1.W), Mux(isSubtract(dec.io.op), ~b, b)) + carry
      val result = WireDefault(sum(7, 0))
      when(dec.io.op === AirOp.Xor) { result := a ^ b }
      when(dec.io.op === AirOp.Or) { result := a | b }
      when(dec.io.op === AirOp.And) { result := a & b }
      val eqNext = equal && a === b
      val unsignedLess = !sum(8)
      val signedLess = Mux(a(7) =/= b(7), a(7), unsignedLess)
      val less = Mux(dec.io.op === AirOp.Slt, signedLess, unsignedLess)
      carry := sum(8); equal := eqNext
      val last = Mux(isWord(dec.io.op), byteIndex === 3.U, byteIndex === 7.U)
      when(!isCompare(dec.io.op)) { writeRd(byteIndex, result) }
      when(last) {
        when(isCompare(dec.io.op)) {
          for (i <- 0 until 8) { work(i) := Mux(i.U === 0.U, less, 0.U) }
          byteIndex := 0.U; state := AirState.WorkWb
        }.elsewhen(isWord(dec.io.op)) {
          fillSign := result(7); byteIndex := 4.U; state := AirState.FillWb
        }.otherwise { startSequentialPc() }
      }.otherwise {
        byteIndex := byteIndex + 1.U
        issueRead(dec.io.rs1, byteIndex + 1.U)
      }
    }

    is(AirState.DirectWb) {
      writeRd(byteIndex, dec.io.immediateByte)
      when(byteIndex === 7.U) { startSequentialPc() }
        .otherwise { byteIndex := byteIndex + 1.U }
    }

    is(AirState.FillWb) {
      writeRd(byteIndex, Fill(8, fillSign))
      when(byteIndex === 7.U) { startSequentialPc() }
        .otherwise { byteIndex := byteIndex + 1.U }
    }

    is(AirState.WorkWb) {
      writeRd(byteIndex, work(byteIndex))
      when(byteIndex === 7.U) { startSequentialPc() }
        .otherwise { byteIndex := byteIndex + 1.U }
    }

    is(AirState.ShiftAmountWait) {
      shiftRemaining := Mux(isWord(dec.io.op), Cat(0.U(1.W), rf.io.readData(4, 0)), rf.io.readData(5, 0))
      byteIndex := 0.U
      issueRead(dec.io.rs1, 0.U)
      state := AirState.ShiftLoadWait
    }

    is(AirState.ShiftLoadWait) {
      setWorkByte(byteIndex, rf.io.readData)
      val last = Mux(isWord(dec.io.op), byteIndex === 3.U, byteIndex === 7.U)
      when(last) {
        when(isWord(dec.io.op)) { for (i <- 4 until 8) { work(i) := 0.U } }
        state := AirState.ShiftStart
      }.otherwise {
        byteIndex := byteIndex + 1.U
        issueRead(dec.io.rs1, byteIndex + 1.U)
      }
    }

    is(AirState.ShiftStart) {
      when(shiftRemaining === 0.U) {
        when(isWord(dec.io.op)) {
          val sign = work(3)(7)
          for (i <- 4 until 8) { work(i) := Fill(8, sign) }
        }
        byteIndex := 0.U; state := AirState.WorkWb
      }.otherwise {
        val right = dec.io.op === AirOp.Srl || dec.io.op === AirOp.Sra ||
          dec.io.op === AirOp.Srlw || dec.io.op === AirOp.Sraw
        val top = Mux(isWord(dec.io.op), 3.U, 7.U)
        byteIndex := Mux(right, top, 0.U)
        shiftCarry := (dec.io.op === AirOp.Sra || dec.io.op === AirOp.Sraw) && work(top)(7)
        state := AirState.ShiftByte
      }
    }

    is(AirState.ShiftByte) {
      val right = dec.io.op === AirOp.Srl || dec.io.op === AirOp.Sra ||
        dec.io.op === AirOp.Srlw || dec.io.op === AirOp.Sraw
      val old = work(byteIndex)
      val next = Mux(right, Cat(shiftCarry, old(7, 1)), Cat(old(6, 0), shiftCarry))
      setWorkByte(byteIndex, next)
      shiftCarry := Mux(right, old(0), old(7))
      val last = Mux(right, byteIndex === 0.U,
        byteIndex === Mux(isWord(dec.io.op), 3.U, 7.U))
      when(last) {
        shiftRemaining := shiftRemaining - 1.U
        state := AirState.ShiftStart
      }.otherwise { byteIndex := Mux(right, byteIndex - 1.U, byteIndex + 1.U) }
    }

    is(AirState.AddressWait) {
      val sum = Cat(0.U(1.W), rf.io.readData) + Cat(0.U(1.W), dec.io.immediateByte) + carry
      setWorkByte(byteIndex, sum(7, 0))
      carry := sum(8)
      when(byteIndex >= 4.U) { highNonZero := highNonZero || sum(7, 0).orR }
      when(byteIndex === 7.U) {
        val invalidHigh = highNonZero || sum(7, 0).orR
        when(invalidHigh) {
          takeTrap(Mux(isLoad(dec.io.op), 5.U, Mux(isStore(dec.io.op), 7.U, 1.U)),
            Cat(sum(7, 0), work(6), work(5), work(4), work(3), work(2), work(1), work(0)))
        }.otherwise {
          address := workUInt(31, 0)
          val size = Mux(isLoad(dec.io.op), loadBytes(dec.io.op), storeBytes(dec.io.op))
          val misaligned = MuxLookup(size, false.B)(Seq(
            2.U -> work(0)(0), 4.U -> workUInt(1, 0).orR, 8.U -> workUInt(2, 0).orR))
          when(dec.io.op === AirOp.Jalr) {
            val target = Cat(work(3), work(2), work(1), work(0)) & ~1.U(32.W)
            when(targetMisaligned(target)) { takeTrap(0.U, target.pad(64)) }
              .otherwise {
                address := target
                when(dec.io.rd === 0.U) { pc := target; retire(false.B) }
                  .otherwise { byteIndex := 0.U; carry := false.B; state := AirState.LinkWb }
              }
          }.elsewhen(misaligned) {
            takeTrap(Mux(isLoad(dec.io.op), 4.U, 6.U), workUInt)
          }.elsewhen(isLoad(dec.io.op)) {
            memBeat := 0.U; state := AirState.MemRequest
          }.otherwise {
            for (i <- 0 until 8) { work(i) := 0.U }
            memBeat := 0.U; storeByte := 0.U
            issueRead(dec.io.rs2, 0.U)
            state := AirState.StoreReadWait
          }
        }
      }.otherwise {
        byteIndex := byteIndex + 1.U
        issueRead(dec.io.rs1, byteIndex + 1.U)
      }
    }

    is(AirState.StoreReadWait) {
      val lane = Mux(dec.io.op === AirOp.Sb || dec.io.op === AirOp.Sh,
        address(1, 0) + storeByte, storeByte)
      setWorkByte(lane, rf.io.readData)
      val bytesThisBeat = Mux(dec.io.op === AirOp.Sb, 1.U,
        Mux(dec.io.op === AirOp.Sh, 2.U, 4.U))
      when(storeByte === bytesThisBeat - 1.U) {
        state := AirState.MemRequest
      }.otherwise {
        storeByte := storeByte + 1.U
        issueRead(dec.io.rs2, Cat(memBeat, storeByte + 1.U))
      }
    }

    is(AirState.MemRequest) {
      when(io.wb.err) {
        takeTrap(Mux(isLoad(dec.io.op), 5.U, 7.U), address.pad(64))
      }.elsewhen(io.wb.ack) {
        when(isLoad(dec.io.op)) {
          when(dec.io.op === AirOp.Ld && memBeat === 0.U) {
            for (i <- 0 until 4) { work(i) := io.wb.dat_r(8 * i + 7, 8 * i) }
            memBeat := 1.U
          }.otherwise {
            when(dec.io.op === AirOp.Ld) {
              for (i <- 0 until 4) { work(i + 4) := io.wb.dat_r(8 * i + 7, 8 * i) }
            }.otherwise {
              val shifted = io.wb.dat_r >> Cat(address(1, 0), 0.U(3.W))
              val n = loadBytes(dec.io.op)
              val sign = MuxLookup(n, shifted(7))(Seq(2.U -> shifted(15), 4.U -> shifted(31)))
              val signed = dec.io.op === AirOp.Lb || dec.io.op === AirOp.Lh || dec.io.op === AirOp.Lw
              for (i <- 0 until 8) {
                if (i < 4) {
                  when(i.U < n) { work(i) := shifted(8 * i + 7, 8 * i) }
                    .otherwise { work(i) := Mux(signed, Fill(8, sign), 0.U) }
                } else { work(i) := Mux(signed, Fill(8, sign), 0.U) }
              }
            }
            byteIndex := 0.U; state := AirState.WorkWb
          }
        }.otherwise {
          when(dec.io.op === AirOp.Sd && memBeat === 0.U) {
            for (i <- 0 until 8) { work(i) := 0.U }
            memBeat := 1.U; storeByte := 0.U
            issueRead(dec.io.rs2, 4.U)
            state := AirState.StoreReadWait
          }.otherwise { startSequentialPc() }
        }
      }
    }

    is(AirState.PcTarget) {
      val pcByte = Mux(byteIndex < 4.U, (pc >> Cat(byteIndex, 0.U(3.W)))(7, 0), 0.U)
      val sum = Cat(0.U(1.W), pcByte) + Cat(0.U(1.W), dec.io.immediateByte) + carry
      setWorkByte(byteIndex, sum(7, 0)); carry := sum(8)
      when(byteIndex >= 4.U) { highNonZero := highNonZero || sum(7, 0).orR }
      when(byteIndex === 7.U) {
        val invalidHigh = highNonZero || sum(7, 0).orR
        when(invalidHigh) { takeTrap(1.U, Cat(sum(7, 0), work(6), work(5), work(4),
          work(3), work(2), work(1), work(0))) }
        .elsewhen(dec.io.op === AirOp.Auipc) {
          byteIndex := 0.U; state := AirState.WorkWb
        }.otherwise {
          val target = Cat(work(3), work(2), work(1), work(0))
          when(targetMisaligned(target)) { takeTrap(0.U, target.pad(64)) }
          .elsewhen(isBranch(dec.io.op) || dec.io.rd === 0.U) {
            pc := target; retire(false.B)
          }.otherwise {
            address := target; byteIndex := 0.U; carry := false.B; state := AirState.LinkWb
          }
        }
      }.otherwise { byteIndex := byteIndex + 1.U }
    }

    is(AirState.LinkWb) {
      val pcByte = Mux(byteIndex < 4.U, (pc >> Cat(byteIndex, 0.U(3.W)))(7, 0), 0.U)
      val add = Mux(byteIndex === 0.U, instructionBytes, 0.U)
      val sum = Cat(0.U(1.W), pcByte) + Cat(0.U(1.W), add) + carry
      writeRd(byteIndex, sum(7, 0)); carry := sum(8)
      when(byteIndex === 7.U) { pc := address; retire(true.B) }
        .otherwise { byteIndex := byteIndex + 1.U }
    }

    is(AirState.SequentialPc) {
      val current = (pc >> Cat(byteIndex, 0.U(3.W)))(7, 0)
      val add = Mux(byteIndex === 0.U, instructionBytes, 0.U)
      val sum = Cat(0.U(1.W), current) + Cat(0.U(1.W), add) + carry
      val mask = ("hff".U(32.W) << Cat(byteIndex, 0.U(3.W)))
      pc := (pc & ~mask) | (sum(7, 0) << Cat(byteIndex, 0.U(3.W)))
      carry := sum(8)
      when(!sum(8) || byteIndex === 3.U) { retire(writesRd(dec.io.op)) }
        .otherwise { byteIndex := byteIndex + 1.U }
    }

    is(AirState.CsrSourceWait) {
      setWorkByte(byteIndex, rf.io.readData)
      when(byteIndex === 7.U) { state := AirState.CsrExecute }
        .otherwise {
          byteIndex := byteIndex + 1.U
          issueRead(dec.io.rs1, byteIndex + 1.U)
        }
    }

    is(AirState.CsrExecute) {
      val csrAddress = instruction(31, 20)
      val old = csrRead(csrAddress)
      val source = workUInt
      val doWrite = dec.io.op === AirOp.Csrrw || source.orR
      val next = MuxLookup(dec.io.op.asUInt, source)(Seq(
        AirOp.Csrrs.asUInt -> (old | source), AirOp.Csrrc.asUInt -> (old & ~source)))
      when(!csrLegal(csrAddress)) { takeTrap(2.U, instruction.pad(64)) }
      .otherwise {
        when(doWrite) {
          switch(csrAddress) {
            is("h300".U) { mstatus := next }; is("h305".U) { mtvec := next & ~3.U(64.W) }
            is("h340".U) { mscratch := next }; is("h341".U) { mepc := next & ~1.U(64.W) }
            is("h342".U) { mcause := next }; is("h343".U) { mtval := next }
          }
        }
        for (i <- 0 until 8) { work(i) := old(8 * i + 7, 8 * i) }
        when(dec.io.rd === 0.U) { startSequentialPc() }
          .otherwise { byteIndex := 0.U; state := AirState.WorkWb }
      }
    }
  }
}
