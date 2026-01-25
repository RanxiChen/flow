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
    val dump = true
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
        val idle, high,low,out = Value
    }
    import State._
    val state = RegInit(idle)
    object wb_state extends ChiselEnum {
        val wb_idle,wb_wait_ack,wb_ack,wb_nop = Value
    }
    import wb_state._
    val addr_reg = Reg(UInt(64.W))
    val wdata_reg = Reg(UInt(32.W))
    val sel_reg = Reg(UInt(4.W))
    val we_reg = Reg(Bool())
    val rdata_reg = Reg(UInt(32.W))
    val wb_bus_resp = Wire(Bool())
    wb_bus_resp := false.B
    val low_buf = Reg(UInt(32.W))
    // wb fsm
    val wb_fsm = RegInit(wb_nop)
    when(wb_fsm === wb_idle){
        io.wb.cyc := false.B
        io.wb.stb := false.B
        wb_fsm := wb_wait_ack
    }.elsewhen(wb_fsm === wb_wait_ack){
        io.wb.cyc := true.B
        io.wb.stb := true.B
        io.wb.adr := addr_reg(31,2)
        io.wb.we := we_reg
        io.wb.sel := sel_reg
        io.wb.dat_w := wdata_reg
        if(dump){
            printf(cf"wishbone access: addr=0x${io.wb.adr}%0x,")
            printf(cf"we=${io.wb.we},")
            printf(cf"sel=0b${io.wb.sel}%b,")
            printf(cf"data_w=0x${io.wb.dat_w}%x,")
            printf(cf"cyc=${io.wb.cyc},stb=${io.wb.stb},ack=${io.wb.ack}\n")
        }
        when(io.wb.ack){
            wb_fsm := wb_ack
            rdata_reg := io.wb.dat_r
            if(dump)printf(cf"wishbone read data: data_r=0x${io.wb.dat_r}%x\n")
        }
    }.elsewhen(wb_fsm === wb_ack){
        wb_fsm := wb_nop
        wb_bus_resp := true.B
    }.elsewhen(wb_fsm === wb_nop){
        wb_fsm := wb_nop
        io.wb.cyc := false.B
        io.wb.stb := false.B
    }
    val imem_dmem = RegInit(true.B) // true for imem, false for dmem
    // main fsm
    when(state === idle){
        //prioritize dmem req
        when(io.backend.mem_valid){
            //store req
            addr_reg := io.backend.req_addr(31,0)
            we_reg := io.backend.wt_rd
            sel_reg := io.backend.wmask(3,0)
            wdata_reg := io.backend.wdata(31,0)
            imem_dmem := false.B
            state := low
            wb_fsm := wb_idle
        }.elsewhen(io.frontend.req_valid){
            //fetch req
            addr_reg := io.frontend.req_addr
            we_reg := false.B
            sel_reg := "b1111".U
            wdata_reg := 0.U
            imem_dmem := true.B
            state := low
            wb_fsm := wb_idle
        }
    }.elsewhen(state === low){
        //wb access
        when(wb_bus_resp){
            when(imem_dmem){
                //imem resp
                state := out
                wb_fsm := wb_nop
            }.elsewhen(!imem_dmem){
                //dmem resp
                state := high
                wb_fsm := wb_idle
                low_buf := rdata_reg
                addr_reg := io.backend.req_addr + 4.U
                we_reg := io.backend.wt_rd
                sel_reg := io.backend.wmask(7,4)
                wdata_reg := io.backend.wdata(63,32)
                imem_dmem := false.B                               
            }
        }
    }.elsewhen(state === high){
        when(wb_bus_resp){
            //dmem resp
            state := out
            wb_fsm := wb_nop
        }
    }.elsewhen(state === out){
        when(imem_dmem){
            //imem resp
            io.frontend.resp_data := rdata_reg
            io.frontend.resp_ack := true.B
            state := idle
        }.elsewhen(!imem_dmem){
            //dmem resp
            io.backend.rdata := Cat(rdata_reg,low_buf)
            io.backend.resp_addr := io.backend.req_addr
            io.backend.can_next := true.B
            state := idle
        }
    }
    if(dump){
        printf(cf"[cpu_wb_bus_if] state=${state},")
        printf(cf"wb_fsm=${wb_fsm},")
        printf(cf"cyc=${io.wb.cyc},stb=${io.wb.stb},ack=${io.wb.ack}\n")
    }
    
}