package mem

import chisel3._
import chisel3.util._
import top.WithTCM
import core.IMemPort
import top.tcm
import top.DefaultConfig.DEFALUT_ILLEGAL_INSTRUCTION
import chisel3.util.experimental.loadMemoryFromFileInline


/**
  * Fetch TCM IO Interface
  * since fe will process req, and tcm will always respond
  *
  */
class tcm2fe_IO extends Bundle {
    val resp_data = Output(UInt(32.W))
    val req_addr = Input(UInt(64.W))
    val flush = Input(Bool())
}

/**
  * ITCM Module
  * @param cf Configuration parameters for TCM
  * Just read by fetch stage,initialized when reset
  */

class ITCM(val cf: tcm) extends Module {
    val io = IO(new Bundle {
        val fetch_port = new tcm2fe_IO
    })
    //memory declaration
    //configure memory, later move to config class
    val byte_size = cf.size
    val entry_count = byte_size / 4 //32 bits entry
    val content = SyncReadMem(entry_count, UInt(32.W))
    //TODO:file should be configured
    loadMemoryFromFileInline(content,"itcm_init.hex") // load memory content from file
    val content_index = Wire(UInt(log2Ceil(entry_count).W))
    val content_data = content.read(content_index)
    //addr mux
    val off_set = io.fetch_port.req_addr - cf.start_addr.U
    content_index := off_set( log2Ceil(byte_size)-1, 2)
    //response
    when(io.fetch_port.flush){//acording to s0,s1 flush signal
      //flush, do not respond
      io.fetch_port.resp_data := DEFALUT_ILLEGAL_INSTRUCTION.U(32.W)
    }.otherwise{
      //normal respond
      io.fetch_port.resp_data := content_data
    }
    //dump log
    if(true){
      printf(cf"[ITCM] addr: 0x${io.fetch_port.req_addr}%x, index: 0x${content_index}%x, data: 0x${content_data}%x\n")
    }
}