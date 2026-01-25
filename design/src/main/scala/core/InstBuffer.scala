package core

import chisel3._
import chisel3.util._

class IB_BE_Bundle extends Bundle {
    val flush = Output(Bool()) // when mispredict, flush the buffer
}

/**
  * Instruction Buffer Module
  * when instruction buffer is empty, backend will not advance
  * when need flush, instruction buffer will be flushed
  * @param buffer_entry
  */
class InstBuffer(val buffer_entry: Int = 2) extends Module {
    val io = IO(new Bundle{
        val fe_in = Flipped(Decoupled(new InstPack))
        val out = Decoupled(new InstPack)
        val be_in = Flipped(new IB_BE_Bundle)
    })
    val dump = true
    val buffer = Module(new Queue(new InstPack, 
                            entries = buffer_entry, pipe = true, 
                            flow = true,hasFlush = true
                            )
                        )
    /**
      * when flush is high, the buffer will be flushed
      */
    buffer.io.enq <> io.fe_in
    buffer.io.deq <> io.out
    buffer.io.flush.get := io.be_in.flush
    if(dump){
        when(io.fe_in.ready){
            printf(cf"[InstBuf]InstBuf not full, can accept instruction\n")
        }
        when(io.fe_in.fire){
            printf(cf"[InstBuf] enqueue: pc=0x${io.fe_in.bits.pc}%0x,")
            printf(cf"data=0x${io.fe_in.bits.data}%0x\n")
        }
    }
}