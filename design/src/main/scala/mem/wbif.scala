package mem

import chisel3._
import chisel3.util._
import core.IMEM_IO
import core.DMemPort
import dataclass.data
/**
  * A wishbone bus interface for cpu core to access memory
  * will always process dmem req, then process imem req
  * frontend will insure req_addr not change until can_next is true
  * For now, I just use 32-bits address bus, later can extend to 64-bits
  * So, it don't matter fetch, but will influence store/load
  * split 64-bits addr to two 32-bits process
  */
class cpu_wb_bus_if extends Module {
    val io = IO(new Bundle{
        val frontend = Flipped( new IMEM_IO)
        val backend = Flipped( new DMemPort)
        val wb = new wishbone.litex_wb_port()
    })
    // initial io
    io.wb.cyc := false.B
    io.wb.stb := false.B
    io.wb.we := false.B
    io.wb.sel := "b0000".U
    io.wb.adr := 0.U
    io.wb.cti := 0.U
    io.wb.bte := 0.U
    io.wb.dat_w := 0.U
    io.frontend.resp_data := 0.U
    io.frontend.resp_ack := false.B
    io.backend.rdata := 0.U
    io.backend.can_next := false.B
    io.backend.resp_addr := 0.U

    object State extends ChiselEnum {
        val idle, high,low = Value
    }
    import State._
    val state = RegInit(idle)
    val req_addr_reg = RegInit(0.U(64.W))
    val imem_dmem = RegInit(false.B) // false for imem, true for dmem
    val data_buf = RegInit(0.U(32.W))
    val tap0 = RegInit(false.B) // temporary clear stb when go to `high` state
    when(state === idle){
        io.wb.cyc := false.B
        io.wb.stb := false.B
        io.wb.we := false.B
        io.wb.sel := "b0000".U
        when(io.backend.mem_valid){
            //storage req addr
            req_addr_reg := io.backend.req_addr
            imem_dmem := true.B
            // first process lowr part
            state := low
        }.elsewhen(io.frontend.req_valid){
            //storage req addr
            req_addr_reg := io.frontend.req_addr
            imem_dmem := false.B
            state := low
        }.otherwise{
            state := idle
        }
    }.elsewhen(state === low){
        io.wb.cyc := true.B
        io.wb.stb := true.B
        io.wb.adr := req_addr_reg(31,2) // word aligned
        io.wb.dat_w := io.backend.wdata(31,0)
        io.wb.sel := io.backend.wmask(3,0)
        io.wb.we := Mux(imem_dmem,io.backend.wt_rd, false.B)
        //ack and depends on we, decide weather read or write
        when(io.wb.ack){
            when(io.wb.we){
                //write complete, just dmem can write
                state := high
            }.otherwise{
                //read data
                when(imem_dmem){
                    //dmem read, store low part
                    data_buf := io.wb.dat_r
                    state := high
                    tap0 := true.B
                }.otherwise{
                    //imem read, directly response
                    io.frontend.resp_data := io.wb.dat_r
                    io.frontend.resp_ack := true.B
                    state := idle
                }
            }
        }
    }.elsewhen(state === high){
        // just dmem may come here
        io.wb.cyc := true.B
        when(tap0){
            io.wb.stb := false.B
            tap0 := false.B
        }.otherwise{
            io.wb.stb := true.B
            io.wb.adr := (req_addr_reg + 4.U(64.W)) (31,2) // word aligned
            io.wb.dat_w := io.backend.wdata(63,32)
            io.wb.sel := io.backend.wmask(7,4)
            io.wb.we := io.backend.wt_rd
            when(io.wb.ack){
                when(io.wb.we){
                    //write complete, respond back
                    io.backend.can_next := true.B
                    io.backend.resp_addr := req_addr_reg
                    state := idle
                }.otherwise{
                    //read data, send back
                    io.backend.rdata := Cat(io.wb.dat_r, data_buf)
                    io.backend.can_next := true.B
                    io.backend.resp_addr := req_addr_reg
                    state := idle
                }
            }
        }
    }
}