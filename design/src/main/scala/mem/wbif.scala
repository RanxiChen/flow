package mem

import chisel3._
import chisel3.util._
import core.IMemPort

/**
  * A wishbone bus interface for cpu core to access memory
  * frontend will insure req_addr not change until can_next is true
  *
  */
class cpu_wb_bus_if extends Module {
    val io = IO(new Bundle{
        val frontend = Flipped(new IMemPort)
        val wb = new wishbone.litex_wb_port()
    })
    // connect frontend to wishbone port
    object wbstate extends ChiselEnum {
        val idle,read = Value
    }
    import wbstate._
    val state = RegInit(idle)

}