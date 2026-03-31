package core

import chisel3._
import chisel3.util._
import _root_.flow.interface._

/**
  * 一个乱序核心
  */

class storm(val DECODE_WIDTH:Int = 4) extends Module {
    val io = IO(new Bundle{
        val reset_addr = Input(UInt(64.W))
        val inst_i = Flipped(Decoupled(Vec(DECODE_WIDTH, new Frontend_Instruction_Bunlde())))
    })
    //store instruction
    val decode_buffer = RegInit(VecInit.fill(DECODE_WIDTH)(0.U.asTypeOf(io.inst_i.bits(0))))
    val decode_buffer_valid = RegInit(VecInit.fill(DECODE_WIDTH)(false.B)) // valid bit for clear the buffer when mispredict
    val decode_buffer_ready = WireDefault(false.B) // ready bit for accepting new instruction, backpressure when the buffer is full
    for(i <- 0 until DECODE_WIDTH){
    }

    //decode
    val decoder = Vec(DECODE_WIDTH, Module(new Decoder()).io)
    for(i <- 0 until DECODE_WIDTH){
        decoder(i).inst := ???
    }

}
