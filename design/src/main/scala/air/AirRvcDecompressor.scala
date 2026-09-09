package flow.air

import chisel3._
import chisel3.util._

/** Complete integer RV64C decompressor for Air-IC.
  *
  * Floating-point compressed encodings and reserved encodings are illegal.
  * Legal instructions are expanded into ordinary RV64I instructions before
  * they reach AirDecode, keeping the execution core independent of RVC.
  */
class AirRvcDecompressor extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(16.W))
    val out = Output(UInt(32.W))
    val legal = Output(Bool())
  })

  val c = io.in
  val quadrant = c(1, 0)
  val funct3 = c(15, 13)
  val rd = c(11, 7)
  val rs2 = c(6, 2)
  val rdp = Cat("b01".U(2.W), c(4, 2))
  val rs1p = Cat("b01".U(2.W), c(9, 7))
  val rs2p = Cat("b01".U(2.W), c(4, 2))

  def encodeI(opcode: UInt, rd: UInt, f3: UInt, rs1: UInt, imm: UInt): UInt = {
    val padded = imm.pad(32)
    Cat(padded(11, 0), rs1.pad(5), f3.pad(3), rd.pad(5), opcode.pad(7))
  }
  def encodeR(opcode: UInt, rd: UInt, f3: UInt, rs1: UInt, rs2: UInt, f7: UInt): UInt =
    Cat(f7.pad(7), rs2.pad(5), rs1.pad(5), f3.pad(3), rd.pad(5), opcode.pad(7))
  def encodeS(opcode: UInt, f3: UInt, rs1: UInt, rs2: UInt, imm: UInt): UInt = {
    val padded = imm.pad(32)
    Cat(padded(11, 5), rs2.pad(5), rs1.pad(5), f3.pad(3), padded(4, 0), opcode.pad(7))
  }
  def encodeB(f3: UInt, rs1: UInt, rs2: UInt, imm: UInt): UInt = {
    val padded = imm.pad(32)
    Cat(padded(12), padded(10, 5), rs2.pad(5), rs1.pad(5), f3.pad(3),
      padded(4, 1), padded(11), "b1100011".U(7.W))
  }
  def encodeU(rd: UInt, imm: UInt): UInt = {
    val padded = imm.pad(32)
    Cat(padded(31, 12), rd.pad(5), "b0110111".U(7.W))
  }
  def encodeJ(rd: UInt, imm: UInt): UInt = {
    val padded = imm.pad(32)
    Cat(padded(20), padded(10, 1), padded(11), padded(19, 12), rd.pad(5), "b1101111".U(7.W))
  }

  io.out := 0.U
  io.legal := false.B

  switch(quadrant) {
    is("b00".U) {
      switch(funct3) {
        is("b000".U) { // C.ADDI4SPN
          val imm = Cat(0.U(2.W), c(10, 7), c(12, 11), c(5), c(6), 0.U(2.W))
          when(imm =/= 0.U) {
            io.out := encodeI("b0010011".U, rdp, 0.U, 2.U, imm)
            io.legal := true.B
          }
        }
        is("b010".U) { // C.LW
          val imm = Cat(0.U(5.W), c(5), c(12, 10), c(6), 0.U(2.W))
          io.out := encodeI("b0000011".U, rdp, 2.U, rs1p, imm)
          io.legal := true.B
        }
        is("b011".U) { // C.LD
          val imm = Cat(0.U(4.W), c(6, 5), c(12, 10), 0.U(3.W))
          io.out := encodeI("b0000011".U, rdp, 3.U, rs1p, imm)
          io.legal := true.B
        }
        is("b110".U) { // C.SW
          val imm = Cat(0.U(5.W), c(5), c(12, 10), c(6), 0.U(2.W))
          io.out := encodeS("b0100011".U, 2.U, rs1p, rs2p, imm)
          io.legal := true.B
        }
        is("b111".U) { // C.SD
          val imm = Cat(0.U(4.W), c(6, 5), c(12, 10), 0.U(3.W))
          io.out := encodeS("b0100011".U, 3.U, rs1p, rs2p, imm)
          io.legal := true.B
        }
      }
    }

    is("b01".U) {
      val simm = Cat(Fill(6, c(12)), c(12), c(6, 2))
      switch(funct3) {
        is("b000".U) { // C.ADDI / C.NOP / hints
          io.out := encodeI("b0010011".U, rd, 0.U, rd, simm)
          io.legal := true.B
        }
        is("b001".U) { // C.ADDIW (RV64)
          when(rd =/= 0.U) {
            io.out := encodeI("b0011011".U, rd, 0.U, rd, simm)
            io.legal := true.B
          }
        }
        is("b010".U) { // C.LI
          io.out := encodeI("b0010011".U, rd, 0.U, 0.U, simm)
          io.legal := true.B
        }
        is("b011".U) {
          when(rd === 2.U) { // C.ADDI16SP
            val imm = Cat(Fill(2, c(12)), c(12), c(4, 3), c(5), c(2), c(6), 0.U(4.W))
            when(imm =/= 0.U) {
              io.out := encodeI("b0010011".U, 2.U, 0.U, 2.U, imm)
              io.legal := true.B
            }
          }.otherwise { // C.LUI
            val imm = Cat(Fill(14, c(12)), c(12), c(6, 2), 0.U(12.W))
            when(rd =/= 0.U && rd =/= 2.U && c(12, 2).orR) {
              io.out := encodeU(rd, imm)
              io.legal := true.B
            }
          }
        }
        is("b100".U) {
          val shamt = Cat(c(12), c(6, 2))
          switch(c(11, 10)) {
            is("b00".U) { // C.SRLI
              io.out := encodeI("b0010011".U, rs1p, 5.U, rs1p, shamt)
              io.legal := true.B
            }
            is("b01".U) { // C.SRAI
              val imm = Cat("b010000".U(6.W), shamt)
              io.out := encodeI("b0010011".U, rs1p, 5.U, rs1p, imm)
              io.legal := true.B
            }
            is("b10".U) { // C.ANDI
              io.out := encodeI("b0010011".U, rs1p, 7.U, rs1p, simm)
              io.legal := true.B
            }
            is("b11".U) {
              when(!c(12)) {
                val f3 = MuxLookup(c(6, 5), 0.U)(Seq(
                  "b00".U -> 0.U, "b01".U -> 4.U, "b10".U -> 6.U, "b11".U -> 7.U))
                val f7 = Mux(c(6, 5) === 0.U, "b0100000".U, 0.U)
                io.out := encodeR("b0110011".U, rs1p, f3, rs1p, rs2p, f7)
                io.legal := true.B
              }.otherwise {
                when(c(6, 5) === 0.U || c(6, 5) === 1.U) { // C.SUBW/C.ADDW
                  io.out := encodeR("b0111011".U, rs1p, 0.U, rs1p, rs2p,
                    Mux(c(6, 5) === 0.U, "b0100000".U, 0.U))
                  io.legal := true.B
                }
              }
            }
          }
        }
        is("b101".U) { // C.J
          val imm = Cat(Fill(9, c(12)), c(12), c(8), c(10, 9), c(6), c(7),
            c(2), c(11), c(5, 3), 0.U(1.W))
          io.out := encodeJ(0.U, imm)
          io.legal := true.B
        }
        is("b110".U) { // C.BEQZ
          val imm = Cat(Fill(4, c(12)), c(12), c(6, 5), c(2), c(11, 10), c(4, 3), 0.U(1.W))
          io.out := encodeB(0.U, rs1p, 0.U, imm)
          io.legal := true.B
        }
        is("b111".U) { // C.BNEZ
          val imm = Cat(Fill(4, c(12)), c(12), c(6, 5), c(2), c(11, 10), c(4, 3), 0.U(1.W))
          io.out := encodeB(1.U, rs1p, 0.U, imm)
          io.legal := true.B
        }
      }
    }

    is("b10".U) {
      switch(funct3) {
        is("b000".U) { // C.SLLI
          val shamt = Cat(c(12), c(6, 2))
          when(rd =/= 0.U) {
            io.out := encodeI("b0010011".U, rd, 1.U, rd, shamt)
            io.legal := true.B
          }
        }
        is("b010".U) { // C.LWSP
          val imm = Cat(0.U(4.W), c(3, 2), c(12), c(6, 4), 0.U(2.W))
          when(rd =/= 0.U) {
            io.out := encodeI("b0000011".U, rd, 2.U, 2.U, imm)
            io.legal := true.B
          }
        }
        is("b011".U) { // C.LDSP
          val imm = Cat(0.U(3.W), c(4, 2), c(12), c(6, 5), 0.U(3.W))
          when(rd =/= 0.U) {
            io.out := encodeI("b0000011".U, rd, 3.U, 2.U, imm)
            io.legal := true.B
          }
        }
        is("b100".U) {
          when(!c(12)) {
            when(rs2 === 0.U) { // C.JR
              when(rd =/= 0.U) {
                io.out := encodeI("b1100111".U, 0.U, 0.U, rd, 0.U)
                io.legal := true.B
              }
            }.otherwise { // C.MV
              when(rd =/= 0.U) {
                io.out := encodeR("b0110011".U, rd, 0.U, 0.U, rs2, 0.U)
                io.legal := true.B
              }
            }
          }.otherwise {
            when(rs2 === 0.U) {
              when(rd === 0.U) { // C.EBREAK
                io.out := "h00100073".U
                io.legal := true.B
              }.otherwise { // C.JALR
                io.out := encodeI("b1100111".U, 1.U, 0.U, rd, 0.U)
                io.legal := true.B
              }
            }.otherwise { // C.ADD
              when(rd =/= 0.U) {
                io.out := encodeR("b0110011".U, rd, 0.U, rd, rs2, 0.U)
                io.legal := true.B
              }
            }
          }
        }
        is("b110".U) { // C.SWSP
          val imm = Cat(0.U(4.W), c(8, 7), c(12, 9), 0.U(2.W))
          io.out := encodeS("b0100011".U, 2.U, 2.U, rs2, imm)
          io.legal := true.B
        }
        is("b111".U) { // C.SDSP
          val imm = Cat(0.U(3.W), c(9, 7), c(12, 10), 0.U(3.W))
          io.out := encodeS("b0100011".U, 3.U, 2.U, rs2, imm)
          io.legal := true.B
        }
      }
    }
  }
}
