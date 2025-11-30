package core

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
}

object BRU_OP {
   val BEQ  = 0
   val BNE  = 1
   val BLT  = 2
   val BLTU = 3
   val BGE  = 4
   val BGEU = 5
   val width = 3
}
object SEL_ALU1 {
   val RS1 = 0
   val PC  = 1
   val width = 1
}
object SEL_ALU2 {
   val RS2 = 0
   val IMM = 1
   val CONST4 = 2
   val width = 2
}
/**
  * select next_pc in exe stage
  */
object SEL_JPC_I {
   val PC = 0
   val RS1 = 1
   val width = 1
}
object SEL_JPC_O {
   val Normal = 0
   val Jalr = 1
   val width = 1
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
}