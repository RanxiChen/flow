package flow.wisp

import chisel3._
import chisel3.util._

object WispOp extends ChiselEnum {
  val Illegal, Lui, Auipc, Jal, Jalr,
      Beq, Bne, Blt, Bge, Bltu, Bgeu,
      Lb, Lh, Lw, Ld, Lbu, Lhu, Lwu,
      Sb, Sh, Sw, Sd,
      Add, Sub, Slt, Sltu, Xor, Or, And, Sll, Srl, Sra,
      Addw, Subw, Sllw, Srlw, Sraw,
      Fence, FenceI,
      Csrrw, Csrrs, Csrrc,
      Ecall, Ebreak, Mret = Value
}

class WispDecode extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val op = Output(WispOp())
    val rs1 = Output(UInt(5.W))
    val rs2 = Output(UInt(5.W))
    val rd = Output(UInt(5.W))
    val imm = Output(UInt(64.W))
    val useImm = Output(Bool())
    val csrImm = Output(Bool())
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

  io.op := WispOp.Illegal
  io.rs1 := insn(19, 15)
  io.rs2 := insn(24, 20)
  io.rd := insn(11, 7)
  io.imm := iImm
  io.useImm := false.B
  io.csrImm := false.B
  io.legal := true.B

  switch(opcode) {
    is("b0110111".U) { io.op := WispOp.Lui; io.imm := uImm }
    is("b0010111".U) { io.op := WispOp.Auipc; io.imm := uImm }
    is("b1101111".U) { io.op := WispOp.Jal; io.imm := jImm }
    is("b1100111".U) {
      when(funct3 === 0.U) { io.op := WispOp.Jalr; io.imm := iImm }
    }
    is("b1100011".U) {
      io.imm := bImm
      switch(funct3) {
        is("b000".U) { io.op := WispOp.Beq }
        is("b001".U) { io.op := WispOp.Bne }
        is("b100".U) { io.op := WispOp.Blt }
        is("b101".U) { io.op := WispOp.Bge }
        is("b110".U) { io.op := WispOp.Bltu }
        is("b111".U) { io.op := WispOp.Bgeu }
      }
    }
    is("b0000011".U) {
      io.imm := iImm
      switch(funct3) {
        is("b000".U) { io.op := WispOp.Lb }
        is("b001".U) { io.op := WispOp.Lh }
        is("b010".U) { io.op := WispOp.Lw }
        is("b011".U) { io.op := WispOp.Ld }
        is("b100".U) { io.op := WispOp.Lbu }
        is("b101".U) { io.op := WispOp.Lhu }
        is("b110".U) { io.op := WispOp.Lwu }
      }
    }
    is("b0100011".U) {
      io.imm := sImm
      switch(funct3) {
        is("b000".U) { io.op := WispOp.Sb }
        is("b001".U) { io.op := WispOp.Sh }
        is("b010".U) { io.op := WispOp.Sw }
        is("b011".U) { io.op := WispOp.Sd }
      }
    }
    is("b0010011".U) {
      io.useImm := true.B; io.imm := iImm
      switch(funct3) {
        is("b000".U) { io.op := WispOp.Add }
        is("b010".U) { io.op := WispOp.Slt }
        is("b011".U) { io.op := WispOp.Sltu }
        is("b100".U) { io.op := WispOp.Xor }
        is("b110".U) { io.op := WispOp.Or }
        is("b111".U) { io.op := WispOp.And }
        is("b001".U) { when(funct7(6, 1) === 0.U) { io.op := WispOp.Sll } }
        is("b101".U) {
          when(funct7(6, 1) === 0.U) { io.op := Mux(funct7(5), WispOp.Sra, WispOp.Srl) }
        }
      }
    }
    is("b0011011".U) {
      io.useImm := true.B; io.imm := iImm
      switch(funct3) {
        is("b000".U) { io.op := WispOp.Addw }
        is("b001".U) { when(funct7 === 0.U) { io.op := WispOp.Sllw } }
        is("b101".U) {
          when(funct7 === 0.U || funct7 === "b0100000".U) {
            io.op := Mux(funct7(5), WispOp.Sraw, WispOp.Srlw)
          }
        }
      }
    }
    is("b0110011".U) {
      when(funct7 === 0.U || funct7 === "b0100000".U) {
        switch(funct3) {
          is("b000".U) { io.op := Mux(funct7(5), WispOp.Sub, WispOp.Add) }
          is("b001".U) { when(funct7 === 0.U) { io.op := WispOp.Sll } }
          is("b010".U) { when(funct7 === 0.U) { io.op := WispOp.Slt } }
          is("b011".U) { when(funct7 === 0.U) { io.op := WispOp.Sltu } }
          is("b100".U) { when(funct7 === 0.U) { io.op := WispOp.Xor } }
          is("b101".U) { io.op := Mux(funct7(5), WispOp.Sra, WispOp.Srl) }
          is("b110".U) { when(funct7 === 0.U) { io.op := WispOp.Or } }
          is("b111".U) { when(funct7 === 0.U) { io.op := WispOp.And } }
        }
      }
    }
    is("b0111011".U) {
      when(funct7 === 0.U || funct7 === "b0100000".U) {
        switch(funct3) {
          is("b000".U) { io.op := Mux(funct7(5), WispOp.Subw, WispOp.Addw) }
          is("b001".U) { when(funct7 === 0.U) { io.op := WispOp.Sllw } }
          is("b101".U) { io.op := Mux(funct7(5), WispOp.Sraw, WispOp.Srlw) }
        }
      }
    }
    is("b0001111".U) {
      when(funct3 === 0.U) { io.op := WispOp.Fence }
      when(funct3 === 1.U && insn(31, 15) === 0.U && insn(11, 7) === 0.U) { io.op := WispOp.FenceI }
    }
    is("b1110011".U) {
      when(funct3 === 0.U) {
        switch(insn(31, 20)) {
          is(0.U) { io.op := WispOp.Ecall }
          is(1.U) { io.op := WispOp.Ebreak }
          is("h302".U) { io.op := WispOp.Mret }
        }
      }.otherwise {
        switch(funct3) {
          is("b001".U) { io.op := WispOp.Csrrw }
          is("b010".U) { io.op := WispOp.Csrrs }
          is("b011".U) { io.op := WispOp.Csrrc }
          is("b101".U) { io.op := WispOp.Csrrw; io.csrImm := true.B }
          is("b110".U) { io.op := WispOp.Csrrs; io.csrImm := true.B }
          is("b111".U) { io.op := WispOp.Csrrc; io.csrImm := true.B }
        }
      }
    }
  }
  io.legal := io.op =/= WispOp.Illegal
}
