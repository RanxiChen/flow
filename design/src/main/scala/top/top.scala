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
            core.io.dtcm.rdata := mem(core.io.dtcm.req_addr >> 4.U )
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