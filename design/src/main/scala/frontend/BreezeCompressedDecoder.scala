package flow.frontend

import chisel3._
import chisel3.util._

/** RV64C decompressor.
  *
  * The backend deliberately remains a 32-bit-instruction machine.  This
  * module expands one 16-bit parcel into the architecturally equivalent
  * RV64I/F/D instruction and reports reserved encodings separately.
  */
class BreezeCompressedDecoder(val enableDouble: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(16.W))
    val out = Output(UInt(32.W))
    val illegal = Output(Bool())
  })

  private val x = io.in
  private def rs1p = Cat("b01".U(2.W), x(9, 7))
  private def rs2p = Cat("b01".U(2.W), x(4, 2))
  private def rs2 = x(6, 2)
  private def rd = x(11, 7)
  private val x0 = 0.U(5.W)
  private val ra = 1.U(5.W)
  private val sp = 2.U(5.W)

  private def iType(imm: UInt, rs1: UInt, funct3: UInt, rd: UInt, opc: Int): UInt =
    Cat(imm(11, 0), rs1.pad(5), funct3.pad(3), rd.pad(5), opc.U(7.W))
  private def rType(funct7: UInt, rs2: UInt, rs1: UInt, funct3: UInt,
                    rd: UInt, opc: Int): UInt =
    Cat(funct7.pad(7), rs2.pad(5), rs1.pad(5), funct3.pad(3), rd.pad(5), opc.U(7.W))
  private def sType(imm: UInt, rs2: UInt, rs1: UInt, funct3: UInt, opc: Int): UInt =
    Cat(imm(11, 5), rs2.pad(5), rs1.pad(5), funct3.pad(3), imm(4, 0), opc.U(7.W))

  private val addi4spnImm = Cat(0.U(2.W), x(10, 7), x(12, 11), x(5), x(6), 0.U(2.W))
  private val lwImm = Cat(0.U(5.W), x(5), x(12, 10), x(6), 0.U(2.W))
  private val ldImm = Cat(0.U(4.W), x(6, 5), x(12, 10), 0.U(3.W))
  private val lwspImm = Cat(0.U(4.W), x(3, 2), x(12), x(6, 4), 0.U(2.W))
  private val ldspImm = Cat(0.U(3.W), x(4, 2), x(12), x(6, 5), 0.U(3.W))
  private val swspImm = Cat(0.U(4.W), x(8, 7), x(12, 9), 0.U(2.W))
  private val sdspImm = Cat(0.U(3.W), x(9, 7), x(12, 10), 0.U(3.W))
  private val addiImm = Cat(Fill(7, x(12)), x(6, 2))
  private val addi16spImm = Cat(Fill(3, x(12)), x(4, 3), x(5), x(2), x(6), 0.U(4.W))
  private val luiImm = Cat(Fill(15, x(12)), x(6, 2), 0.U(12.W))
  private val shamt = Cat(0.U(6.W), x(12), x(6, 2))
  private val jImm = Cat(Fill(10, x(12)), x(8), x(10, 9), x(6), x(7),
    x(2), x(11), x(5, 3), 0.U(1.W))
  private val bImm = Cat(Fill(5, x(12)), x(6, 5), x(2), x(11, 10),
    x(4, 3), 0.U(1.W))

  private val illegalInst = "h00000000".U(32.W)
  private val nop = "h00000013".U(32.W)

  private val q0 = Wire(Vec(8, UInt(32.W)))
  q0(0) := iType(addi4spnImm, sp, 0.U, rs2p, 0x13)
  q0(1) := iType(ldImm, rs1p, 3.U, rs2p, 0x07) // C.FLD
  q0(2) := iType(lwImm, rs1p, 2.U, rs2p, 0x03)
  q0(3) := iType(ldImm, rs1p, 3.U, rs2p, 0x03)
  q0(4) := illegalInst
  q0(5) := sType(ldImm, rs2p, rs1p, 3.U, 0x27) // C.FSD
  q0(6) := sType(lwImm, rs2p, rs1p, 2.U, 0x23)
  q0(7) := sType(ldImm, rs2p, rs1p, 3.U, 0x23)

  private val q1 = Wire(Vec(8, UInt(32.W)))
  q1(0) := iType(addiImm, rd, 0.U, rd, 0x13)
  q1(1) := iType(addiImm, rd, 0.U, rd, 0x1b) // RV64 C.ADDIW
  q1(2) := iType(addiImm, x0, 0.U, rd, 0x13)
  q1(3) := Mux(rd === sp,
    iType(addi16spImm, sp, 0.U, sp, 0x13),
    Cat(luiImm(31, 12), rd, 0x37.U(7.W)))

  private val srli = iType(shamt, rs1p, 5.U, rs1p, 0x13)
  private val srai = srli | (1L << 30).U
  private val andi = iType(addiImm, rs1p, 7.U, rs1p, 0x13)
  private val arithSel = Cat(x(12), x(6, 5))
  private val arith = MuxLookup(arithSel, illegalInst)(Seq(
    "b000".U -> rType("b0100000".U, rs2p, rs1p, 0.U, rs1p, 0x33),
    "b001".U -> rType(0.U(7.W), rs2p, rs1p, 4.U, rs1p, 0x33),
    "b010".U -> rType(0.U(7.W), rs2p, rs1p, 6.U, rs1p, 0x33),
    "b011".U -> rType(0.U(7.W), rs2p, rs1p, 7.U, rs1p, 0x33),
    "b100".U -> rType("b0100000".U, rs2p, rs1p, 0.U, rs1p, 0x3b),
    "b101".U -> rType(0.U(7.W), rs2p, rs1p, 0.U, rs1p, 0x3b)
  ))
  q1(4) := MuxLookup(x(11, 10), illegalInst)(Seq(
    0.U -> srli, 1.U -> srai, 2.U -> andi, 3.U -> arith))
  q1(5) := Cat(jImm(20), jImm(10, 1), jImm(11), jImm(19, 12), x0, 0x6f.U(7.W))
  q1(6) := Cat(bImm(12), bImm(10, 5), x0, rs1p, 0.U(3.W),
    bImm(4, 1), bImm(11), 0x63.U(7.W))
  q1(7) := Cat(bImm(12), bImm(10, 5), x0, rs1p, 1.U(3.W),
    bImm(4, 1), bImm(11), 0x63.U(7.W))

  private val q2 = Wire(Vec(8, UInt(32.W)))
  q2(0) := iType(shamt, rd, 1.U, rd, 0x13)
  q2(1) := iType(ldspImm, sp, 3.U, rd, 0x07) // C.FLDSP
  q2(2) := iType(lwspImm, sp, 2.U, rd, 0x03)
  q2(3) := iType(ldspImm, sp, 3.U, rd, 0x03)
  private val mv = rType(0.U(7.W), rs2, x0, 0.U, rd, 0x33)
  private val add = rType(0.U(7.W), rs2, rd, 0.U, rd, 0x33)
  private val jr = iType(0.U(12.W), rd, 0.U, x0, 0x67)
  private val jalr = iType(0.U(12.W), rd, 0.U, ra, 0x67)
  private val ebreak = "h00100073".U(32.W)
  q2(4) := Mux(x(12), Mux(rs2.orR, add, Mux(rd.orR, jalr, ebreak)),
    Mux(rs2.orR, mv, Mux(rd.orR, jr, illegalInst)))
  q2(5) := sType(sdspImm, rs2, sp, 3.U, 0x27) // C.FSDSP
  q2(6) := sType(swspImm, rs2, sp, 2.U, 0x23)
  q2(7) := sType(sdspImm, rs2, sp, 3.U, 0x23)

  private val selected = MuxLookup(x(1, 0), illegalInst)(Seq(
    0.U -> q0(x(15, 13)),
    1.U -> q1(x(15, 13)),
    2.U -> q2(x(15, 13))))

  private val q0Illegal = VecInit(Seq(
    !x(12, 5).orR,
    (!enableDouble).B,
    false.B,
    false.B,
    true.B,
    (!enableDouble).B,
    false.B,
    false.B))
  private val q1Illegal = VecInit(Seq(
    false.B,
    rd === 0.U,
    false.B,
    Mux(rd === sp, !addi16spImm.orR, rd === 0.U || !addiImm.orR),
    x(11, 10) === 3.U && arithSel > "b101".U,
    false.B,
    false.B,
    false.B))
  private val q2Illegal = VecInit(Seq(
    rd === 0.U,
    (!enableDouble).B,
    rd === 0.U,
    rd === 0.U,
    !x(12, 2).orR,
    (!enableDouble).B,
    false.B,
    false.B))
  private val illegal = MuxLookup(x(1, 0), true.B)(Seq(
    0.U -> q0Illegal(x(15, 13)),
    1.U -> q1Illegal(x(15, 13)),
    2.U -> q2Illegal(x(15, 13))))

  io.out := Mux(illegal, nop, selected)
  io.illegal := illegal
}
