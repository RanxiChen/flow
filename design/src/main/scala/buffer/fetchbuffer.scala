package flow.buffer

import chisel3._
import chisel3.util._
import flow.interface._

class FetchBuffer(val VLEN: Int = 64, val entries: Int = 4) extends Module {
    require(entries >= 3, "FetchBuffer entries must be at least 3 to support canAccept3")

    val io = IO(new Bundle {
        val in = Flipped(new FrontendFetchBufferIO(VLEN))
        val out = Decoupled(new FrontendFetchBundle(VLEN))
        val flush = Input(Bool())
    })

    val fifo = Module(
        new Queue(
            gen = new FrontendFetchBundle(VLEN),
            entries = entries,
            pipe = false,
            flow = false,
            useSyncReadMem = false,
            hasFlush = true
        )
    )

    fifo.io.enq.valid := io.in.valid
    fifo.io.enq.bits := io.in.bits
    io.out <> fifo.io.deq
    fifo.io.flush.get := io.flush

    val freeEntries = entries.U - fifo.io.count
    io.in.canAccept3 := freeEntries >= 3.U

    assert(
        !io.in.valid || fifo.io.enq.ready,
        "FetchBuffer overflow: frontend pushed data when FIFO could not accept it"
    )
}
