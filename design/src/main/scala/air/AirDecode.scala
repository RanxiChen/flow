package flow.air

import chisel3._
import chisel3.util._

object AirOp extends ChiselEnum {
  val Illegal, Lui, Auipc, Jal, Jalr,
      Beq, Bne, Blt, Bge, Bltu, Bgeu,
      Lb, Lh, Lw, Ld, Lbu, Lhu, Lwu,
      Sb, Sh, Sw, Sd,
      Add, Sub, Slt, Sltu, Xor, Or, And, Sll, Srl, Sra,
      Addw, Subw, Sllw, Srlw, Sraw,
      Fence, FenceI,
      Csrrw, Csrrs, Csrrc,
      Ecall, Ebreak, Mret, Wfi = Value
}

/** Air's independent RV64I/Zicsr/Zifencei decoder.
  *
  * The immediate output is intentionally one byte wide.  The stable latched
  * instruction and byteIndex are sufficient to regenerate any byte on demand.
  */
class AirDecode extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val byteIndex = Input(UInt(3.W))
    val op = Output(AirOp())
    val rs1 = Output(UInt(5.W))
    val rs2 = Output(UInt(5.W))
    val rd = Output(UInt(5.W))
    val immediateByte = Output(UInt(8.W))
    val useImmediate = Output(Bool())
    val csrImmediate = Output(Bool())
    val legal = Output(Bool())
  })

  val insn = io.instruction
  val opcode = insn(6, 0)
  val funct3 = insn(14, 12)
  val funct7 = insn(31, 25)

  val iImm = Cat(Fill(52, insn(31)), insn(31, 20))
  val sImm = Cat(Fill(52, insn(31)), insn(31, 25), insn(11, 7))
  val bImm = Cat(Fill(51, insn(31)), insn(31), insn(7), insn(30, 25), insn(11, 8), 0.U(1.W))
  val uImm = Cat(Fill(32, insn(31)), insn(31, 12), 0.U(12.W))
  val jImm = Cat(Fill(43, insn(31)), insn(31), insn(19, 12), insn(20), insn(30, 21), 0.U(1.W))
  val immediate = WireDefault(iImm)

  io.op := AirOp.Illegal
  io.rs1 := insn(19, 15)
  io.rs2 := insn(24, 20)
  io.rd := insn(11, 7)
  io.useImmediate := false.B
  io.csrImmediate := false.B

  switch(opcode) {
    is("b0110111".U) { io.op := AirOp.Lui; immediate := uImm }
    is("b0010111".U) { io.op := AirOp.Auipc; immediate := uImm }
    is("b1101111".U) { io.op := AirOp.Jal; immediate := jImm }
    is("b1100111".U) { when(funct3 === 0.U) { io.op := AirOp.Jalr; immediate := iImm } }
    is("b1100011".U) {
      immediate := bImm
      switch(funct3) {
        is(0.U) { io.op := AirOp.Beq }; is(1.U) { io.op := AirOp.Bne }
        is(4.U) { io.op := AirOp.Blt }; is(5.U) { io.op := AirOp.Bge }
        is(6.U) { io.op := AirOp.Bltu }; is(7.U) { io.op := AirOp.Bgeu }
      }
    }
    is("b0000011".U) {
      immediate := iImm
      switch(funct3) {
        is(0.U) { io.op := AirOp.Lb }; is(1.U) { io.op := AirOp.Lh }
        is(2.U) { io.op := AirOp.Lw }; is(3.U) { io.op := AirOp.Ld }
        is(4.U) { io.op := AirOp.Lbu }; is(5.U) { io.op := AirOp.Lhu }
        is(6.U) { io.op := AirOp.Lwu }
      }
    }
    is("b0100011".U) {
      immediate := sImm
      switch(funct3) {
        is(0.U) { io.op := AirOp.Sb }; is(1.U) { io.op := AirOp.Sh }
        is(2.U) { io.op := AirOp.Sw }; is(3.U) { io.op := AirOp.Sd }
      }
    }
    is("b0010011".U) {
      io.useImmediate := true.B; immediate := iImm
      switch(funct3) {
        is(0.U) { io.op := AirOp.Add }; is(2.U) { io.op := AirOp.Slt }
        is(3.U) { io.op := AirOp.Sltu }; is(4.U) { io.op := AirOp.Xor }
        is(6.U) { io.op := AirOp.Or }; is(7.U) { io.op := AirOp.And }
        is(1.U) { when(funct7(6, 1) === 0.U) { io.op := AirOp.Sll } }
        is(5.U) { when(funct7(6, 1) === 0.U) { io.op := Mux(funct7(5), AirOp.Sra, AirOp.Srl) } }
      }
    }
    is("b0011011".U) {
      io.useImmediate := true.B; immediate := iImm
      switch(funct3) {
        is(0.U) { io.op := AirOp.Addw }
        is(1.U) { when(funct7 === 0.U) { io.op := AirOp.Sllw } }
        is(5.U) { when(funct7 === 0.U || funct7 === "b0100000".U) {
          io.op := Mux(funct7(5), AirOp.Sraw, AirOp.Srlw) } }
      }
    }
    is("b0110011".U) {
      when(funct7 === 0.U || funct7 === "b0100000".U) {
        switch(funct3) {
          is(0.U) { io.op := Mux(funct7(5), AirOp.Sub, AirOp.Add) }
          is(1.U) { when(funct7 === 0.U) { io.op := AirOp.Sll } }
          is(2.U) { when(funct7 === 0.U) { io.op := AirOp.Slt } }
          is(3.U) { when(funct7 === 0.U) { io.op := AirOp.Sltu } }
          is(4.U) { when(funct7 === 0.U) { io.op := AirOp.Xor } }
          is(5.U) { io.op := Mux(funct7(5), AirOp.Sra, AirOp.Srl) }
          is(6.U) { when(funct7 === 0.U) { io.op := AirOp.Or } }
          is(7.U) { when(funct7 === 0.U) { io.op := AirOp.And } }
        }
      }
    }
    is("b0111011".U) {
      when(funct7 === 0.U || funct7 === "b0100000".U) {
        switch(funct3) {
          is(0.U) { io.op := Mux(funct7(5), AirOp.Subw, AirOp.Addw) }
          is(1.U) { when(funct7 === 0.U) { io.op := AirOp.Sllw } }
          is(5.U) { io.op := Mux(funct7(5), AirOp.Sraw, AirOp.Srlw) }
        }
      }
    }
    is("b0001111".U) {
      when(funct3 === 0.U) { io.op := AirOp.Fence }
      when(funct3 === 1.U && insn(31, 15) === 0.U && insn(11, 7) === 0.U) { io.op := AirOp.FenceI }
    }
    is("b1110011".U) {
      when(funct3 === 0.U) {
        switch(insn(31, 20)) {
          is(0.U) { io.op := AirOp.Ecall }; is(1.U) { io.op := AirOp.Ebreak }
          is("h302".U) { io.op := AirOp.Mret }
          is("h105".U) { io.op := AirOp.Wfi }
        }
      }.otherwise {
        switch(funct3) {
          is(1.U) { io.op := AirOp.Csrrw }; is(2.U) { io.op := AirOp.Csrrs }
          is(3.U) { io.op := AirOp.Csrrc }
          is(5.U) { io.op := AirOp.Csrrw; io.csrImmediate := true.B }
          is(6.U) { io.op := AirOp.Csrrs; io.csrImmediate := true.B }
          is(7.U) { io.op := AirOp.Csrrc; io.csrImmediate := true.B }
        }
      }
    }
  }

  io.immediateByte := (immediate >> Cat(io.byteIndex, 0.U(3.W)))(7, 0)
  io.legal := io.op =/= AirOp.Illegal
}
