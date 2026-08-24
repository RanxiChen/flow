package flow.wisp

import chisel3._
import chisel3.util._
import flow.accelerator.matrix.SystolicArrayAreaProbe
import flow.bus.{LiteXWishboneMasterIO, LiteXWishboneParameters, WishboneBurstType, WishboneCycleType}

object WispPhase extends ChiselEnum {
  val Fetch, Execute, ReadIssue, ReadWait, PcAdd, Shift, AddressCheck,
      MemStoreIssue, MemStoreWait, MemRequest, Csr, Commit, WaitInterrupt = Value
}

class WispTrace extends Bundle {
  val valid = Bool()
  val pc = UInt(64.W)
  val instruction = UInt(32.W)
  val rd = UInt(5.W)
  val rdWrite = Bool()
  val rdValue = UInt(64.W)
  val trap = Bool()
  val cause = UInt(64.W)
}

/** Wisp v0: a strictly serialized RV64I + Zicsr + Zifencei core.
  *
  * Arithmetic is performed one byte per cycle.  The only architectural
  * instruction temporary is `value`; decode is recomputed from the latched
  * instruction.  There is one blocking 32-bit Wishbone master shared by fetch
  * and data accesses.
  */
class WispCore(
    resetVector: BigInt = 0,
    withTrace: Boolean = false,
    withMatrixAreaProbe: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val wb = new LiteXWishboneMasterIO(LiteXWishboneParameters(32, 32))
    val timerIrq = Input(Bool())
    val externalIrq = Input(Bool())
    val trace = Output(new WispTrace)
    // Synthesis-only observability hook for gateware area measurements whose
    // top level otherwise has no functional outputs.
    val areaProbe = Output(Bool())
  })

  val phase = RegInit(WispPhase.Fetch)
  val pc = RegInit(VecInit.tabulate(8)(i => ((resetVector >> (8 * i)) & 0xff).U(8.W)))
  val instruction = RegInit(0.U(32.W))
  val value = RegInit(VecInit(Seq.fill(8)(0.U(8.W))))
  val byteIndex = RegInit(0.U(3.W))
  val carry = RegInit(false.B)
  val equal = RegInit(true.B)
  val signA = RegInit(false.B)
  val signB = RegInit(false.B)
  val branchTaken = RegInit(false.B)
  val shiftRemaining = RegInit(0.U(6.W))
  val memBeat = RegInit(0.U(1.W))
  val memWord = RegInit(VecInit(Seq.fill(4)(0.U(8.W))))

  val mstatus = RegInit(0.U(64.W))
  val mie = RegInit(0.U(64.W))
  val mtvec = RegInit(0.U(64.W))
  val mscratch = RegInit(0.U(64.W))
  val mepc = RegInit(0.U(64.W))
  val mcause = RegInit(0.U(64.W))
  val mtval = RegInit(0.U(64.W))

  val mip = (io.timerIrq.asUInt << 7) | (io.externalIrq.asUInt << 11)
  val timerInterruptPending = mstatus(3) && mie(7) && io.timerIrq
  val externalInterruptPending = mstatus(3) && mie(11) && io.externalIrq
  val interruptPending = timerInterruptPending || externalInterruptPending
  val enabledInterruptPending = (mie(7) && io.timerIrq) || (mie(11) && io.externalIrq)

  val traceValid = RegInit(false.B)
  val tracePc = Reg(UInt(64.W))
  val traceInsn = Reg(UInt(32.W))
  val traceRd = Reg(UInt(5.W))
  val traceRdWrite = Reg(Bool())
  val traceRdValue = Reg(UInt(64.W))
  val traceTrap = Reg(Bool())
  val traceCause = Reg(UInt(64.W))

  val pcUInt = pc.asUInt
  val valueUInt = value.asUInt
  val dec = Module(new WispDecode)
  dec.io.instruction := instruction
  val immBytes = VecInit.tabulate(8)(i => dec.io.imm(8 * i + 7, 8 * i))

  val rf = Module(new WispRegisterFile)
  rf.io.readEnable := false.B
  rf.io.readRegA := dec.io.rs1
  rf.io.readRegB := dec.io.rs2
  rf.io.readByte := byteIndex
  rf.io.writeEnable := false.B
  rf.io.writeReg := dec.io.rd
  rf.io.writeByte := byteIndex
  rf.io.writeData := value(byteIndex)

  io.wb.cyc := (phase === WispPhase.Fetch && !interruptPending) || phase === WispPhase.MemRequest
  io.wb.stb := io.wb.cyc
  io.wb.we := phase === WispPhase.MemRequest && isStore(dec.io.op)
  io.wb.adr := Mux(phase === WispPhase.Fetch, pcUInt(31, 2), valueUInt(31, 2) + memBeat)
  io.wb.dat_w := memWord.asUInt
  io.wb.sel := Mux(isStore(dec.io.op), storeSelect(dec.io.op, valueUInt(1, 0)), "b1111".U)
  io.wb.cti := WishboneCycleType.Classic
  io.wb.bte := WishboneBurstType.Linear

  traceValid := false.B
  io.trace.valid := (if (withTrace) traceValid else false.B)
  io.trace.pc := (if (withTrace) tracePc else 0.U)
  io.trace.instruction := (if (withTrace) traceInsn else 0.U)
  io.trace.rd := (if (withTrace) traceRd else 0.U)
  io.trace.rdWrite := (if (withTrace) traceRdWrite else false.B)
  io.trace.rdValue := (if (withTrace) traceRdValue else 0.U)
  io.trace.trap := (if (withTrace) traceTrap else false.B)
  io.trace.cause := (if (withTrace) traceCause else 0.U)
  val matrixAreaProbe = if (withMatrixAreaProbe) {
    val matrix = Module(new SystolicArrayAreaProbe)
    matrix.io.probe
  } else {
    false.B
  }
  io.areaProbe := pcUInt.xorR ^ valueUInt.xorR ^ instruction.xorR ^
    mstatus.xorR ^ mtvec.xorR ^ mscratch.xorR ^ mepc.xorR ^ mcause.xorR ^ mtval.xorR ^
    memWord.asUInt.xorR ^ rf.io.readDataA.xorR ^ rf.io.readDataB.xorR ^ phase.asUInt.xorR ^
    matrixAreaProbe

  def writesRd(op: WispOp.Type): Bool = {
    !(isBranch(op) || isStore(op) || op === WispOp.Fence || op === WispOp.FenceI ||
      op === WispOp.Ecall || op === WispOp.Ebreak || op === WispOp.Mret ||
      op === WispOp.Wfi || op === WispOp.Illegal)
  }
  def isBranch(op: WispOp.Type): Bool = op >= WispOp.Beq && op <= WispOp.Bgeu
  def isLoad(op: WispOp.Type): Bool = op >= WispOp.Lb && op <= WispOp.Lwu
  def isStore(op: WispOp.Type): Bool = op >= WispOp.Sb && op <= WispOp.Sd
  def isShift(op: WispOp.Type): Bool = Seq(WispOp.Sll, WispOp.Srl, WispOp.Sra,
    WispOp.Sllw, WispOp.Srlw, WispOp.Sraw).map(op === _).reduce(_ || _)
  def isWord(op: WispOp.Type): Bool = Seq(WispOp.Addw, WispOp.Subw, WispOp.Sllw,
    WispOp.Srlw, WispOp.Sraw).map(op === _).reduce(_ || _)
  def isCsr(op: WispOp.Type): Bool = op >= WispOp.Csrrw && op <= WispOp.Csrrc
  def isCompare(op: WispOp.Type): Bool = Seq(WispOp.Slt, WispOp.Sltu).map(op === _).reduce(_ || _) || isBranch(op)
  def isSubtract(op: WispOp.Type): Bool = Seq(WispOp.Sub, WispOp.Subw, WispOp.Slt,
    WispOp.Sltu).map(op === _).reduce(_ || _) || isBranch(op)

  def loadBytes(op: WispOp.Type): UInt = MuxLookup(op.asUInt, 1.U)(Seq(
    WispOp.Lh.asUInt -> 2.U, WispOp.Lhu.asUInt -> 2.U,
    WispOp.Lw.asUInt -> 4.U, WispOp.Lwu.asUInt -> 4.U,
    WispOp.Ld.asUInt -> 8.U))
  def storeBytes(op: WispOp.Type): UInt = MuxLookup(op.asUInt, 1.U)(Seq(
    WispOp.Sh.asUInt -> 2.U, WispOp.Sw.asUInt -> 4.U, WispOp.Sd.asUInt -> 8.U))
  def storeSelect(op: WispOp.Type, low: UInt): UInt = MuxLookup(op.asUInt, "b0001".U)(Seq(
    WispOp.Sb.asUInt -> (1.U(4.W) << low),
    WispOp.Sh.asUInt -> (3.U(4.W) << low),
    WispOp.Sw.asUInt -> "b1111".U,
    WispOp.Sd.asUInt -> "b1111".U))

  def csrLegal(address: UInt): Bool = Seq("h300", "h301", "h304", "h305", "h340", "h341", "h342", "h343", "h344", "hf14")
    .map(x => address === x.U).reduce(_ || _)
  def csrRead(address: UInt): UInt = MuxLookup(address, 0.U)(Seq(
    "h300".U -> mstatus,
    "h301".U -> "h8000000000000100".U,
    "h304".U -> mie,
    "h305".U -> mtvec,
    "h340".U -> mscratch,
    "h341".U -> mepc,
    "h342".U -> mcause,
    "h343".U -> mtval,
    "h344".U -> mip.pad(64),
    "hf14".U -> 0.U))

  def takeTrap(cause: UInt, trapValue: UInt): Unit = {
    mepc := pcUInt
    mcause := cause
    mtval := trapValue
    mstatus := (mstatus & ~"h1888".U(64.W)) | (mstatus(3).asUInt << 7) | "h1800".U
    for (i <- 0 until 8) { pc(i) := mtvec(8 * i + 7, 8 * i) }
    phase := WispPhase.Fetch
    traceValid := true.B
    tracePc := pcUInt
    traceInsn := instruction
    traceRd := 0.U
    traceRdWrite := false.B
    traceRdValue := 0.U
    traceTrap := true.B
    traceCause := cause
  }
  def takeInterrupt(cause: UInt): Unit = {
    mepc := pcUInt
    mcause := (1.U(64.W) << 63) | cause
    mtval := 0.U
    mstatus := (mstatus & ~"h1888".U(64.W)) | (mstatus(3).asUInt << 7) | "h1800".U
    setPc(mtvec)
    phase := WispPhase.Fetch
    traceValid := true.B
    tracePc := pcUInt
    traceInsn := 0.U
    traceRd := 0.U
    traceRdWrite := false.B
    traceRdValue := 0.U
    traceTrap := true.B
    traceCause := (1.U(64.W) << 63) | cause
  }
  def setValue(next: UInt): Unit = {
    for (i <- 0 until 8) { value(i) := next(8 * i + 7, 8 * i) }
  }
  def setPc(next: UInt): Unit = {
    for (i <- 0 until 8) { pc(i) := next(8 * i + 7, 8 * i) }
  }
  def clearMemWord(): Unit = {
    for (i <- 0 until 4) { memWord(i) := 0.U }
  }
  def setValueByte(index: UInt, data: UInt): Unit = {
    for (i <- 0 until 8) { when(index === i.U) { value(i) := data } }
  }
  def setPcByte(index: UInt, data: UInt): Unit = {
    for (i <- 0 until 8) { when(index === i.U) { pc(i) := data } }
  }
  def setMemByte(index: UInt, data: UInt): Unit = {
    for (i <- 0 until 4) { when(index === i.U) { memWord(i) := data } }
  }

  switch(phase) {
    is(WispPhase.Fetch) {
      when(externalInterruptPending) {
        takeInterrupt(11.U)
      }.elsewhen(timerInterruptPending) {
        takeInterrupt(7.U)
      }.elsewhen(pcUInt(1, 0) =/= 0.U) {
        takeTrap(0.U, pcUInt)
      }.elsewhen(io.wb.err) {
        takeTrap(1.U, pcUInt)
      }.elsewhen(io.wb.ack) {
        instruction := io.wb.dat_r
        phase := WispPhase.Execute
      }
    }

    is(WispPhase.Execute) {
      when(!dec.io.legal) {
        takeTrap(2.U, instruction)
      }.elsewhen(dec.io.op === WispOp.Ecall) {
        takeTrap(11.U, 0.U)
      }.elsewhen(dec.io.op === WispOp.Ebreak) {
        takeTrap(3.U, 0.U)
      }.elsewhen(dec.io.op === WispOp.Mret) {
        mstatus := (mstatus & ~"h1888".U(64.W)) |
          (mstatus(7).asUInt << 3) | "h1880".U
        setPc(mepc); phase := WispPhase.Fetch
      }.elsewhen(dec.io.op === WispOp.Wfi) {
        setPc(pcUInt + 4.U); phase := WispPhase.WaitInterrupt
      }.elsewhen(dec.io.op === WispOp.Lui) {
        setValue(dec.io.imm); byteIndex := 0.U; carry := false.B; phase := WispPhase.Commit
      }.elsewhen(dec.io.op === WispOp.Auipc || dec.io.op === WispOp.Jal) {
        byteIndex := 0.U; carry := false.B; phase := WispPhase.PcAdd
      }.elsewhen(dec.io.op === WispOp.Fence || dec.io.op === WispOp.FenceI) {
        byteIndex := 0.U; carry := false.B; phase := WispPhase.Commit
      }.elsewhen(isCsr(dec.io.op) && dec.io.csrImm) {
        setValue(dec.io.rs1.pad(64))
        phase := WispPhase.Csr
      }.otherwise {
        setValue(0.U(64.W))
        byteIndex := 0.U
        carry := isSubtract(dec.io.op)
        equal := true.B
        phase := WispPhase.ReadIssue
      }
    }

    is(WispPhase.ReadIssue) {
      rf.io.readEnable := true.B
      phase := WispPhase.ReadWait
    }

    is(WispPhase.ReadWait) {
      val a = rf.io.readDataA
      val b = Mux(dec.io.useImm || dec.io.op === WispOp.Jalr || isLoad(dec.io.op) || isStore(dec.io.op), immBytes(byteIndex), rf.io.readDataB)
      val addB = Mux(isSubtract(dec.io.op), ~b, b)
      val sum = Cat(0.U(1.W), a) + Cat(0.U(1.W), addB) + carry
      val result = WireDefault(sum(7, 0))
      when(dec.io.op === WispOp.Xor) { result := a ^ b }
      when(dec.io.op === WispOp.Or) { result := a | b }
      when(dec.io.op === WispOp.And) { result := a & b }
      when(isShift(dec.io.op) || isCsr(dec.io.op)) { result := a }
      setValueByte(byteIndex, result)
      carry := sum(8)
      equal := equal && a === b
      when(byteIndex === 7.U) { signA := a(7); signB := b(7) }

      // The byte-wide RF presents a different byte on every read pass.  The
      // architectural shift amount lives in byte zero of rs2, so capture it
      // while that byte is present instead of sampling readDataB after the
      // final (byte-seven) pass.
      when(isShift(dec.io.op) && byteIndex === 0.U) {
        val amount = Mux(dec.io.useImm, dec.io.imm(5, 0), rf.io.readDataB(5, 0))
        shiftRemaining := Mux(isWord(dec.io.op), Cat(0.U(1.W), amount(4, 0)), amount)
      }

      val last = Mux(isWord(dec.io.op) && !isShift(dec.io.op), byteIndex === 3.U, byteIndex === 7.U)
      when(last) {
        when(isShift(dec.io.op)) {
          phase := WispPhase.Shift
        }.elsewhen(isCompare(dec.io.op)) {
          val eqFinal = equal && a === b
          val unsignedLess = !sum(8)
          val signedLess = Mux(a(7) =/= b(7), a(7), unsignedLess)
          val less = Mux(dec.io.op === WispOp.Slt || dec.io.op === WispOp.Blt || dec.io.op === WispOp.Bge, signedLess, unsignedLess)
          when(dec.io.op === WispOp.Slt || dec.io.op === WispOp.Sltu) {
            setValue(Cat(0.U(63.W), less))
            byteIndex := 0.U; carry := false.B; phase := WispPhase.Commit
          }.otherwise {
            branchTaken := MuxLookup(dec.io.op.asUInt, false.B)(Seq(
              WispOp.Beq.asUInt -> eqFinal, WispOp.Bne.asUInt -> !eqFinal,
              WispOp.Blt.asUInt -> signedLess, WispOp.Bge.asUInt -> !signedLess,
              WispOp.Bltu.asUInt -> unsignedLess, WispOp.Bgeu.asUInt -> !unsignedLess))
            when(MuxLookup(dec.io.op.asUInt, false.B)(Seq(
              WispOp.Beq.asUInt -> eqFinal, WispOp.Bne.asUInt -> !eqFinal,
              WispOp.Blt.asUInt -> signedLess, WispOp.Bge.asUInt -> !signedLess,
              WispOp.Bltu.asUInt -> unsignedLess, WispOp.Bgeu.asUInt -> !unsignedLess))) {
              byteIndex := 0.U; carry := false.B; phase := WispPhase.PcAdd
            }.otherwise {
              byteIndex := 0.U; carry := false.B; phase := WispPhase.Commit
            }
          }
        }.elsewhen(dec.io.op === WispOp.Jalr || isLoad(dec.io.op) || isStore(dec.io.op)) {
          phase := WispPhase.AddressCheck
        }.elsewhen(isCsr(dec.io.op)) {
          phase := WispPhase.Csr
        }.otherwise {
          when(isWord(dec.io.op)) {
            val sign = result(7)
            for (i <- 4 until 8) { value(i) := Fill(8, sign) }
          }
          byteIndex := 0.U; carry := false.B; phase := WispPhase.Commit
        }
      }.otherwise {
        byteIndex := byteIndex + 1.U
        phase := WispPhase.ReadIssue
      }
    }

    is(WispPhase.PcAdd) {
      val sum = Cat(0.U(1.W), pc(byteIndex)) + Cat(0.U(1.W), immBytes(byteIndex)) + carry
      setValueByte(byteIndex, sum(7, 0))
      carry := sum(8)
      when(byteIndex === 7.U) {
        byteIndex := 0.U; carry := false.B; phase := WispPhase.Commit
      }.otherwise { byteIndex := byteIndex + 1.U }
    }

    is(WispPhase.Shift) {
      when(shiftRemaining === 0.U) {
        when(isWord(dec.io.op)) {
          val sign = value(3)(7)
          for (i <- 4 until 8) { value(i) := Fill(8, sign) }
        }
        byteIndex := 0.U; carry := false.B; phase := WispPhase.Commit
      }.otherwise {
        val left = isShiftLeft(dec.io.op)
        val arithmetic = dec.io.op === WispOp.Sra || dec.io.op === WispOp.Sraw
        val shifted = Mux(left, valueUInt << 1,
          Mux(arithmetic, (valueUInt.asSInt >> 1).asUInt, valueUInt >> 1))
        setValue(shifted)
        when(isWord(dec.io.op)) { value(4) := 0.U; value(5) := 0.U; value(6) := 0.U; value(7) := 0.U }
        shiftRemaining := shiftRemaining - 1.U
      }
    }

    is(WispPhase.AddressCheck) {
      val size = Mux(isLoad(dec.io.op), loadBytes(dec.io.op), storeBytes(dec.io.op))
      val misaligned = MuxLookup(size, false.B)(Seq(
        2.U -> valueUInt(0), 4.U -> valueUInt(1, 0).orR, 8.U -> valueUInt(2, 0).orR))
      when(valueUInt(63, 32).orR) {
        takeTrap(Mux(isLoad(dec.io.op), 5.U, 7.U), valueUInt)
      }.elsewhen(misaligned) {
        takeTrap(Mux(isLoad(dec.io.op), 4.U, 6.U), valueUInt)
      }.elsewhen(dec.io.op === WispOp.Jalr) {
        value(0) := value(0) & "hfe".U; byteIndex := 0.U; carry := false.B; phase := WispPhase.Commit
      }.elsewhen(isLoad(dec.io.op)) {
        memBeat := 0.U; phase := WispPhase.MemRequest
      }.otherwise {
        memBeat := 0.U; byteIndex := 0.U; clearMemWord(); phase := WispPhase.MemStoreIssue
      }
    }

    is(WispPhase.MemStoreIssue) {
      rf.io.readEnable := true.B
      rf.io.readRegA := dec.io.rs2
      rf.io.readRegB := dec.io.rs2
      rf.io.readByte := Cat(memBeat, byteIndex(1, 0))
      phase := WispPhase.MemStoreWait
    }

    is(WispPhase.MemStoreWait) {
      val lane = Mux(dec.io.op === WispOp.Sb || dec.io.op === WispOp.Sh,
        valueUInt(1, 0) + byteIndex, byteIndex)
      setMemByte(lane(1, 0), rf.io.readDataA)
      val bytesThisBeat = Mux(dec.io.op === WispOp.Sb, 1.U, Mux(dec.io.op === WispOp.Sh, 2.U, 4.U))
      when(byteIndex === bytesThisBeat - 1.U) {
        phase := WispPhase.MemRequest
      }.otherwise {
        byteIndex := byteIndex + 1.U; phase := WispPhase.MemStoreIssue
      }
    }

    is(WispPhase.MemRequest) {
      when(io.wb.err) {
        takeTrap(Mux(isLoad(dec.io.op), 5.U, 7.U), valueUInt)
      }.elsewhen(io.wb.ack) {
        when(isLoad(dec.io.op)) {
          when(dec.io.op === WispOp.Ld) {
            when(memBeat === 0.U) {
              for (i <- 0 until 4) { value(i) := io.wb.dat_r(8 * i + 7, 8 * i) }
              memBeat := 1.U
            }.otherwise {
              for (i <- 0 until 4) { value(i + 4) := io.wb.dat_r(8 * i + 7, 8 * i) }
              byteIndex := 0.U; carry := false.B; phase := WispPhase.Commit
            }
          }.otherwise {
            val shifted = io.wb.dat_r >> Cat(valueUInt(1, 0), 0.U(3.W))
            val n = loadBytes(dec.io.op)
            val sign = MuxLookup(n, shifted(7))(Seq(2.U -> shifted(15), 4.U -> shifted(31)))
            val signed = dec.io.op === WispOp.Lb || dec.io.op === WispOp.Lh || dec.io.op === WispOp.Lw
            for (i <- 0 until 8) {
              if (i < 4) {
                when(i.U < n) { value(i) := shifted(8 * i + 7, 8 * i) }
                  .otherwise { value(i) := Mux(signed, Fill(8, sign), 0.U) }
              } else {
                value(i) := Mux(signed, Fill(8, sign), 0.U)
              }
            }
            byteIndex := 0.U; carry := false.B; phase := WispPhase.Commit
          }
        }.otherwise {
          when(dec.io.op === WispOp.Sd && memBeat === 0.U) {
            memBeat := 1.U; byteIndex := 0.U; clearMemWord(); phase := WispPhase.MemStoreIssue
          }.otherwise {
            byteIndex := 0.U; carry := false.B; phase := WispPhase.Commit
          }
        }
      }
    }

    is(WispPhase.Csr) {
      val address = instruction(31, 20)
      val old = csrRead(address)
      val source = valueUInt
      val doWrite = dec.io.op === WispOp.Csrrw || source.orR
      val next = MuxLookup(dec.io.op.asUInt, source)(Seq(
        WispOp.Csrrs.asUInt -> (old | source), WispOp.Csrrc.asUInt -> (old & ~source)))
      when(!csrLegal(address)) {
        takeTrap(2.U, instruction)
      }.otherwise {
        when(doWrite) {
          switch(address) {
            is("h300".U) { mstatus := next }
            is("h304".U) { mie := next & "h880".U }
            is("h305".U) { mtvec := next & ~3.U(64.W) }
            is("h340".U) { mscratch := next }
            is("h341".U) { mepc := next & ~3.U(64.W) }
            is("h342".U) { mcause := next }
            is("h343".U) { mtval := next }
          }
        }
        setValue(old); byteIndex := 0.U; carry := false.B; phase := WispPhase.Commit
      }
    }

    is(WispPhase.Commit) {
      val jump = dec.io.op === WispOp.Jal || dec.io.op === WispOp.Jalr
      val target = jump || (isBranch(dec.io.op) && branchTaken)
      val increment = Mux(byteIndex === 0.U, 4.U(8.W), 0.U(8.W))
      val pcSum = Cat(0.U(1.W), pc(byteIndex)) + Cat(0.U(1.W), increment) + carry
      val writeData = Mux(jump, pcSum(7, 0), value(byteIndex))
      rf.io.writeEnable := writesRd(dec.io.op)
      rf.io.writeData := writeData
      setPcByte(byteIndex, Mux(target, value(byteIndex), pcSum(7, 0)))
      carry := pcSum(8)
      when(byteIndex === 0.U) {
        tracePc := pcUInt
        when(jump) { traceRdValue := pcUInt + 4.U }
      }
      when(byteIndex === 7.U) {
        traceValid := true.B
        traceInsn := instruction
        traceRd := dec.io.rd
        traceRdWrite := writesRd(dec.io.op) && dec.io.rd =/= 0.U
        when(!jump) { traceRdValue := valueUInt }
        traceTrap := false.B
        traceCause := 0.U
        byteIndex := 0.U; carry := false.B; branchTaken := false.B; phase := WispPhase.Fetch
      }.otherwise { byteIndex := byteIndex + 1.U }
    }

    is(WispPhase.WaitInterrupt) {
      when(enabledInterruptPending) { phase := WispPhase.Fetch }
    }
  }

  def isShiftLeft(op: WispOp.Type): Bool = op === WispOp.Sll || op === WispOp.Sllw
}
