package top

import chisel3._
import chisel3.util._
import core._

class flow_top extends Module {
    val io = IO(new Bundle{
        val error = Output(Bool())
    })
    io.error := false.B
    val core = Module(new core_in_order())
    val mem = Reg(Vec(128, UInt(64.W)))
    when(reset.asBool){
        mem(0) := "h00678813_00100793".U
        mem(1) := "h40f80433_0107f8b3".U
        mem(2) := "h0057f313_10204293".U
        mem(3) := "h00500433_0027d393".U
        mem(4) := "h00402103_00002083".U//load x1 from 0x0,x2 from 0x4
        mem(5) := "h00002083_00002023".U//store 0 to addr0,and read to x1
        mem(6) := "h00000013_0ff11073".U // write x2 to printer and nop
        mem(7) := "h00000013_0ff8d073".U // write 0x3 to printer and nop
        mem(8) := "h00000013_0ff011f3".U // write printer to x3 and nop
        mem(9) := "h00000013_30101273".U // read misa
        mem(10):= "h00000013_8ff21073".U // write x4 to printer
    }
    core.io.itcm.resp_addr := core.io.itcm.req_addr
    core.io.itcm.can_next := true.B
    val fetch_addr = Wire(UInt(64.W))
    fetch_addr := core.io.itcm.req_addr >> 3.U
    val head_tail = Wire(Bool())
    head_tail := (core.io.itcm.req_addr >> 2.U)(0)
    core.io.itcm.data := Mux(!head_tail, mem(fetch_addr)(31,0), mem(fetch_addr)(63,32))
    //mem part
    core.io.dtcm.resp_addr := core.io.dtcm.req_addr
    core.io.dtcm.can_next := true.B
    core.io.dtcm.rdata := 0.U
    val _buf = Wire(Vec(8,UInt(8.W)))
    _buf := (0.U).asTypeOf(Vec(8,UInt(8.W)))
    when(core.io.dtcm.mem_valid){
        when(core.io.dtcm.wt_rd){
            //write data to mem
            for(i <- 0 to 7 ){
                _buf(i) := Mux(
                    core.io.dtcm.wmask(i),
                    core.io.dtcm.wdata(8*i+7,8*i),
                    mem(core.io.dtcm.req_addr >> 4.U)(8*i+7,8*i)
                )
            }
            mem(core.io.dtcm.req_addr) := _buf.asUInt
        }.otherwise{
            //read data from mem
            val tail_head_dmem = core.io.dtcm.req_addr(2)
            val head_dmem = Wire(UInt(32.W))
            val tail_dmem = Wire(UInt(32.W))
            head_dmem :=  mem(core.io.dtcm.req_addr >> 3.U )(31,0)
            tail_dmem :=  mem(core.io.dtcm.req_addr >> 3.U )(63,32)
            core.io.dtcm.rdata := Mux(!tail_head_dmem, head_dmem, tail_dmem)
        }
    }
}

import _root_.circt.stage.ChiselStage
object GenerateTop extends App {
    ChiselStage.emitSystemVerilogFile(
        new flow_top,
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}