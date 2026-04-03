package flow.mem

import chisel3._
import chisel3.util._

/**
  * 这里实现一个sram,目前只是对syncreadmem的包装
  * 后面可能会切换到`sram.scala`中实现的sram
  */

class flowSRAM(num_entry: Int, dataWidth: Int) extends Module {
    val io = IO(new Bundle{
        val addr     = Input(UInt(log2Ceil(num_entry).W))
        val data_in  = Input(UInt(dataWidth.W))
        val data_out = Output(UInt(dataWidth.W))
        val we       = Input(Bool())
        val re       = Input(Bool())
    })
    private val mem = SyncReadMem(num_entry, UInt(dataWidth.W))

    //default value
    io.data_out := 0.U
    when(io.we) {
        mem.write(io.addr, io.data_in)
    }.otherwise {
        io.data_out := mem.read(io.addr, io.re)
    }
}