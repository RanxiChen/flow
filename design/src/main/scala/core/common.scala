package core
import chisel3._
import chisel3.util._

object IMM_TYPE {
   val I_Type = 0
   val S_Type = 1
   val B_Type = 2
   val U_Type = 3
   val J_Type = 4
   val width = 3
}
object ALU_OP {
   val ADD = 0
   val SUB = 1
   val AND = 2
   val OR  = 3
   val XOR = 4
   val SLL = 5
   val SRL = 6
   val SRA = 7
   val SLT = 8
   val SLTU= 9
   val width = 4
   val XXX = 0
}

object BRU_OP {
   val BEQ  = 0
   val BNE  = 1
   val BLT  = 2
   val BLTU = 3
   val BGE  = 4
   val BGEU = 5
   val width = 3
   val XXX = 0
}
object SEL_ALU1 {
   val RS1 = 0
   val PC  = 1
   val ZERO= 2
   val width = 2
   val XXX =2
}
object SEL_ALU2 {
   val RS2 = 0
   val IMM = 1
   val CONST4 = 2
   val width = 2
   val XXX =0
}
/**
  * select next_pc in exe stage
  */
object SEL_JPC_I {
   val PC = 0
   val RS1 = 1
   val width = 1
   val XXX = 0
}
object SEL_JPC_O {
   val Normal = 0
   val Jalr = 1
   val width = 1
   val XXX = 0
}
object MEM_TYPE {
   val LD = 0
   val LW = 1
   val LWU = 2
   val LH = 3
   val LHU = 4
   val LB = 5
   val LBU = 6
   val SB = 7
   val SH = 8
   val SW = 9
   val SD = 10
   val NOT_MEM = 11
   val width = 4
}
object SEL_WB {
   val ALU = 0
   val MEM = 1
   val width = 1
   val XXX = 0
}
object OPCODE{
   val LOAD     = "b0000011".U
   val STORE    = "b0100011".U
   val MADD     = "b1000011".U
   val BRANCH   = "b1100011".U
   val LOAD_FP  = "b0000111".U
   val STORE_FP = "b0100111".U
   val MSUB     = "b1000111".U
   val JALR     = "b1100111".U
   val CUSTOM0  = "b0001011".U
   val CUSTOM1  = "b0101011".U
   val NMSUB    = "b1001011".U
   val RESERVED = "b1101011".U
   val MISC_MEM = "b0001111".U
   val AMO      = "b0101111".U
   val NMADD    = "b1001111".U
   val JAL      = "b1101111".U
   val OP_IMM   = "b0010011".U
   val OP       = "b0110011".U
   val OP_FP    = "b1010011".U
   val SYSTEM   = "b1110011".U
   val AUIPC    = "b0010111".U
   val LUI      = "b0110111".U
   val OP_V     = "b1010111".U
   val OP_VE    = "b1110111".U
   val OP_IMM_32= "b0011011".U
   val OP_32    = "b0111011".U
   val CUSTOM2  = "b1011011".U
   val CUSTOM3  = "b1111011".U
}