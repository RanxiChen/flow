package core

import chisel3._
import chisel3.util._

class InstBuffer(val useFASE: Boolean=false) extends Module {
    val io = IO(new Bundle{
        val in = Flipped(Decoupled(new InstPack))
        val out = Decoupled(new InstPack)
        val flush = Input(Bool())
        val empty = Output(Bool()) //indicate buffer is empty, can insert new instruction
        val ext_in = if (useFASE) Some(Flipped(Decoupled(new InstPack))) else None
    })
    val buffer = Module(new Queue(new InstPack, 
                            entries = 2, pipe = true, 
                            flow = true,hasFlush = true
                            )
                )
    buffer.io.flush.get := io.flush
    if(useFASE){
        //FASE input has higher priority
        when(io.ext_in.get.valid){
            buffer.io.enq.bits := io.ext_in.get.bits
            buffer.io.enq.valid := io.ext_in.get.valid
            io.ext_in.get.ready := buffer.io.enq.ready
            io.in.ready := false.B
        }.otherwise{
            buffer.io.enq.bits := io.in.bits
            buffer.io.enq.valid := io.in.valid
            io.in.ready := buffer.io.enq.ready
            io.ext_in.get.ready := false.B
        }
    }else{
        buffer.io.enq <> io.in
    }

    io.out <> buffer.io.deq
    io.empty := buffer.io.count === 0.U
}