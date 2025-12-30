package top
import chisel3._
import chisel3.util._
/**
  * Memory type
  * regarryMem: register array based memory
  * SRAMSysMem: SRAM based memory
  * @param depth: memory depth
  * @param path: memory init file path
  */
sealed trait memoryType
case class RegArrayMem(depth:Int=128, path:String="", dumplog:Boolean=false) extends memoryType
case class SRAMSysMem(depth:Int=1024, path:String="", dumplog:Boolean=false) extends memoryType

/**
  * TCM configuration parameters, used by ITCM and DTCM Module
  *
  * @param start_addr
  * @param end_addr
  * 
  */
case class tcm(val start_addr:BigInt,
               val end_addr:BigInt) {
  val size = (end_addr - start_addr + 1)
  val depth = size/8 //in 64 bits
  // tcm must be aligned with 8 bytes
  require(start_addr % 8 == 0, s"TCM start address 0x${start_addr.toString(16)} is not aligned with 8 bytes!")
  require(end_addr % 8 == 7, s"TCM end address 0x${end_addr.toString(16)} is not aligned with 8 bytes!")
  // calculate index width
  //must full all index
  def ispow2(n: Int): Boolean = n != 0 && ((n & (n - 1)) == 0)
  require(ispow2(depth.toInt), s"TCM size ${size} bytes is not power of 2!")
  // get index width
  def width_of_index(depth:Int):Int ={
    var w = 0
    var d = depth
    while(d > 1){
      d = d >> 1
      w = w + 1
    }
    w
  }
  val index_width = width_of_index(depth.toInt)
}

object testMain extends App {
  val test_tcm = tcm(BigInt(0x00000000),BigInt("00FFFFFF",16))
  println(s"tcm size: ${test_tcm.size} bytes, depth: ${test_tcm.depth} entries, index width: ${test_tcm.index_width} bits")
}

trait WithTCM {
  val itcm: tcm
  val dtcm: tcm
}

class MCUMemType extends memoryType with WithTCM{
  override val itcm = tcm(BigInt(0x00000000),BigInt("00FFFFFF",16))
  override val dtcm = tcm(BigInt("20000000",16),BigInt("20FFFFFF",16))

    val details = s"""
    MCU Memory Type:
    |  - itcm: 0x${itcm.start_addr.toString(16)} ~ 0x${itcm.end_addr.toString(16)} ,size: ${itcm.size/1024/1024} Mb
    |  - dtcm: 0x${dtcm.start_addr.toString(16)} ~ 0x${dtcm.end_addr.toString(16)} ,size: ${dtcm.size/1024/1024} Mb
    """.stripMargin
}

object FEMux{
  val itcm = 0
  val imem = 1
  val nop = 2
  val sleep = 3
  val width = 2
}
object GlobalSilent{
  val silent = false
}



/**
  * Core type
  * in_order: in order core,separedly frontend and backend
  * early_core: early design
  */
sealed trait CoreType
object in_order extends CoreType
object early_core extends CoreType

object CoreParam{
    val XLEN = 64
    val MXLEN = 64
    val MXL = 2 // 64 bits
    val misa = 1 << 8 | MXL << (MXLEN-3) // RVI64,M mode 
}
/**
  * Default configuration parameters,which will not changed
  * BOOT_ADDR: core boot address
  * ADDR_XXX: address of buble, will never be used 
  */
object DefaultConfig{
    val XLEN = 64
    val BOOT_ADDR = 0x00000000L
    val ADDR_XXX = 0x00000000L 
    val DEFALUT_ILLEGAL_INSTRUCTION = 0x0
}
/**
  * User defined configuration parameters,which can be changed
  * @param memtype: memory type, now support "regarry","sram"
  * @param dumplog: whether to dump log information  
  * @param core: core type, now support "in_order","early_core"
  */
class FlowConfig(val memtype:String = "regarry",
                    val memsize:Int = 1024,//size in bytes
                    val mempath:String = "mem.hex", //infact passed from abosolute path
                    val dumplog:Boolean = false,
                    val core:String = "in_order") {
    // process mem
    val memsize_double = memsize / 8 //in 64 bits
    val memory: memoryType = memtype match {
        case "regarry" => RegArrayMem(memsize_double,mempath,dumplog)
        case "sram" => SRAMSysMem(memsize_double,mempath,dumplog)
        case _ => throw new Exception("Unsupported memory type!")
    }
    //process core
    val coretype: CoreType = core match {
        case "in_order" => in_order
        case "early_core" => early_core
        case _ => throw new Exception("Unsupported core type!")
    }
}

object FlowTopTestMain extends App {
    println("Main used to test config")
    val a = new MCUMemType
    println(a.details)
}