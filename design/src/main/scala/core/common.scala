package core
import chisel3._
import chisel3.util._

object IMM_TYPE {
   val I_Type = 0
   val S_Type = 1
   val B_Type = 2
   val U_Type = 3
   val J_Type = 4
   val CSR_Type = 5
   val width = 3
}
object CSR_CMD{
   val NOP = 0
   val RW  = 1
   val RS  = 2
   val RC  = 3
   val RWI = 4
   val RSI = 5
   val RCI = 6
   val width = 3
   val XXX = 0  
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
   val RS1 = 10
   val RS2 = 11
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
   val CONST0 = 3
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
   val CSR = 2
   val width = 2
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
object CSRMAP{
   // self-defined CSR address
   val printer = 0x8ff
   val mport = 0x7ff
   val coreinst = 0x8fe
   //manual
   val fflags = 0x001
   val frm    = 0x002
   val fcsr   = 0x003


   val vstart = 0x008
   val vxsat  = 0x009
   val vxrm   = 0x00a
   val vcsr   = 0x00f
   val vl     = 0xc20
   val vtype  = 0xc21
   val vlenb  = 0xc22

   val ssp    = 0x011
   val sed    = 0x015
   val jvt    = 0x017

   val cycle  = 0xc00
   val time   = 0xc01
   val instret= 0xc02
   val hpmcounter3 = 0xc03
   val hpmcounter4 = 0xc04
   val hpmcounter5 = 0xc05
   val hpmcounter6 = 0xc06
   val hpmcounter7 = 0xc07
   val hpmcounter8 = 0xc08
   val hpmcounter9 = 0xc09
   val hpmcounter10= 0xc0a
   val hpmcounter11= 0xc0b
   val hpmcounter12= 0xc0c
   val hpmcounter13= 0xc0d
   val hpmcounter14= 0xc0e
   val hpmcounter15= 0xc0f
   val hpmcounter16= 0xc10
   val hpmcounter17= 0xc11
   val hpmcounter18= 0xc12
   val hpmcounter19= 0xc13
   val hpmcounter20= 0xc14
   val hpmcounter21= 0xc15
   val hpmcounter22= 0xc16
   val hpmcounter23= 0xc17
   val hpmcounter24= 0xc18
   val hpmcounter25= 0xc19
   val hpmcounter26= 0xc1a
   val hpmcounter27= 0xc1b
   val hpmcounter28= 0xc1c
   val hpmcounter29= 0xc1d
   val hpmcounter30= 0xc1e
   val hpmcounter31= 0xc1f

   val sstatus     = 0x100
   val sie         = 0x104
   val stvec       = 0x105
   val scounteren  = 0x106
   val senvcfg     = 0x10a
   val scountinhibit =0x120

   val sscratch    = 0x140
   val sepc        = 0x141
   val scause      = 0x142
   val stval       = 0x143
   val sip         = 0x144
   val scountovf   = 0xDA0
   val satp        = 0x180
   val scontext    = 0x5A8

   val sstateen0   = 0x10C
   val sstateen1   = 0x10D
   val sstateen2   = 0x10E
   val sstateen3   = 0x10F

   val mvendorid   = 0xf11
   val marchid     = 0xf12
   val mimpid      = 0xf13
   val mhartid     = 0xf14
   val mconfigptr  = 0xf15

   val mstatus     = 0x300
   val misa        = 0x301
   val medeleg     = 0x302
   val mideleg     = 0x303
   val mie         = 0x304
   val mtvec       = 0x305
   val mcounteren  = 0x306

   val mscratch    = 0x340
   val mepc        = 0x341
   val mcause      = 0x342
   val mtval       = 0x343
   val mip         = 0x344
   val mtinst      = 0x34a
   val mtval2      = 0x34b
   val menvcfg     = 0x30a
   val mseccfg     = 0x747
   val pmpcfg0     = 0x3a0
   //val pmpcfg1     = 0x3a1
   val pmpcfg2     = 0x3a2
   //val pmpcfg3     = 0x3a3
   /*
   val pmpcfg4     = 0x3a4
   val pmpcfg5     = 0x3a5
   val pmpcfg6     = 0x3a6
   val pmpcfg7     = 0x3a7
   val pmpcfg8     = 0x3a8
   val pmpcfg9     = 0x3a9
   val pmpcfg10    = 0x3aa
   val pmpcfg11    = 0x3ab
   val pmpcfg12    = 0x3ac
   val pmpcfg13    = 0x3ad
   */
   val pmpcfg14    = 0x3ae
   //val pmpcfg15    = 0x3af
   val pmpaddr0    = 0x3b0
   val pmpaddr1    = 0x3b1
   val pmpaddr2    = 0x3b2
   val pmpaddr3    = 0x3b3
   val pmpaddr4    = 0x3b4
   val pmpaddr5    = 0x3b5
   val pmpaddr6    = 0x3b6

   val mstateen0   = 0x30c
   val mstateen1   = 0x30d
   val mstateen2   = 0x30e
   val mstateen3   = 0x30f

   val mnscratch   = 0x740
   val mnepc       = 0x741
   val mncause     = 0x742
   val mnstatus    = 0x744

   val mcycle      = 0xb00
   val minstret    = 0xb02
   val mhpmcounter3= 0xb03
   val mhpmcounter4= 0xb04
   val mhpmcounter5= 0xb05
   val mhpmcounter6= 0xb06
   val mhpmcounter7= 0xb07
   val mhpmcounter8= 0xb08
   val mhpmcounter9= 0xb09
   val mhpmcounter10=0xb0a
   val mhpmcounter11=0xb0b 
   val mhpmcounter12=0xb0c
   val mhpmcounter13=0xb0d
   val mhpmcounter14=0xb0e
   val mhpmcounter15=0xb0f
   val mhpmcounter16=0xb10
   val mhpmcounter17=0xb11
   val mhpmcounter18=0xb12
   val mhpmcounter19=0xb13
   val mhpmcounter20=0xb14
   val mhpmcounter21=0xb15
   val mhpmcounter22=0xb16
   val mhpmcounter23=0xb17
   val mhpmcounter24=0xb18
   val mhpmcounter25=0xb19
   val mhpmcounter26=0xb1a
   val mhpmcounter27=0xb1b
   val mhpmcounter28=0xb1c
   val mhpmcounter29=0xb1d
   val mhpmcounter30=0xb1e
   val mhpmcounter31=0xb1f
   val mcountinhibit=0x320
}

object PrivConst{
   val USER       = 0.U(2.W)
   val SUPERVISOR = 1.U(2.W)
   val MACHINE    = 3.U(2.W)
}