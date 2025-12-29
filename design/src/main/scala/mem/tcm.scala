package mem

import chisel3._
import chisel3.util._
import top.WithTCM
import core.IMemPort
import top.tcm

/**
  * SYS TCM IO Interface
  * will be used for load initial data or debug
  * fixed data width 64 bits, sub byte write should be supported by requester
  */
class SYS_TCM_IO extends Bundle {
    val sys_req_en = Input(Bool())
    val sys_req_we = Input(Bool()) // write or read
    val sys_req_addr = Input(UInt(64.W))
    val sys_req_wdata = Input(UInt(64.W))
    val sys_resp_addr = Output(UInt(64.W))
    val sys_resp_rdata = Output(UInt(64.W))
    val sys_resp_error = Output(Bool())
}

class fetch_tcm_io extends Bundle {
    val req_addr = Input(UInt(64.W))
    val resp_data = Output(UInt(64.W))
    val resp_addr = Output(UInt(64.W))
}

/**
  * ITCM Module
  * @param cf Configuration parameters for TCM
  * Just read by ftech stage,initialized when reset
  * step 1.1: convert addr to index, sys port will check addr range
  * step 1.2: read data from sram
  * step 2: output data to imem port
  * if both read, don't influence each other
  */

class ITCM(val cf: tcm) extends Module {
    val io = IO(new Bundle {
        val sys_port = new SYS_TCM_IO
        val fetch_port = new fetch_tcm_io
    })
    val sram_depth = cf.depth.toInt +1
    // basic sram impl
    val mem = SyncReadMem(sram_depth, UInt(64.W))
    // convert addr to index
    val sram_index = Wire(UInt(cf.index_width.W))
    sram_index := io.fetch_port.req_addr >> 3.U
    val sram_resp_addr = RegNext(io.fetch_port.req_addr)
    val sram_resp_data = mem.read(sram_index)
    // output to imem port
    io.fetch_port.resp_addr := sram_resp_addr
    io.fetch_port.resp_data := sram_resp_data

}