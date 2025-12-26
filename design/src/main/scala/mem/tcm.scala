package mem

import chisel3._
import chisel3.util._
import top.WithTCM
import core.IMemPort

/**
  * using TCM to fast access memory
  * ITCM will be used to fetch instructions
  * DTCM will be used to load/store data
  * both need be accessed for extrenal ports
  */
class ITCM(val cf: WithTCM) extends Module {
    val io = IO(new Bundle {
        val imem_port = Flipped(new IMemPort)
    })
    val sram_size = cf.itcm.size
    val sram_depth = cf.itcm.depth
    val mem = SyncReadMem(sram_depth, UInt(64.W))
}