package flow.mem

import chisel3._
import chisel3.util._

/**
  * 这里实现一个sram,目前只是对syncreadmem的包装
  * 后面可能会切换到`sram.scala`中实现的sram
  */

class flowSRAM(num_entry: Int, dataWidth: Int,val field: String,val dump: Boolean = false) extends Module {
    val io = IO(new Bundle{
        val addr     = Input(UInt(log2Ceil(num_entry).W))
        val data_in  = Input(UInt(dataWidth.W))
        val data_out = Output(UInt(dataWidth.W))
        val we       = Input(Bool())
        val re       = Input(Bool())
        val id       = Input(UInt(4.W)) // 用于调试，标识是哪一个sram被访问了
    })
    private val mem = SyncReadMem(num_entry, UInt(dataWidth.W))

    //default value
    io.data_out := 0.U
    when(io.we) {
        mem.write(io.addr, io.data_in)
        if(dump) {
            printf(p"${field}SRAM${io.id} write: addr=0x${Hexadecimal(io.addr)}, data=0x${Hexadecimal(io.data_in)}\n")
        }
    }.otherwise {
        io.data_out := mem.read(io.addr, io.re)
        if(dump) {
            when(io.re) {
                printf(p"${field}SRAM${io.id} read: addr=0x${Hexadecimal(io.addr)}, data=0x${Hexadecimal(io.data_out)}\n")
            }
        }
    }
}
