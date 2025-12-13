package core

object CoreParam{
    val XLEN = 64
    val MXLEN = 64
    val MXL = 2 // 64 bits
    val misa = 1 << 8 | MXL << (MXLEN-3) // RVI64,M mode 
}