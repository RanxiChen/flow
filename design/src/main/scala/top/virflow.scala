package top

import chisel3._
import chisel3.util._
import _root_.interface.NativeMemIO
import _root_.core.GustEngine
import _root_.core.InstBuffer
import _root_.core.Backend
import _root_.circt.stage.ChiselStage
/**
  * 一个虚拟的顶层，只用于仿真环境，当前还不支持分支预测，以及暂时不支持存储指令
  * 当前大概只支持算术运算，目标是可以搭建完善的仿真环境
  *
  */
class VirFlow extends Module {
    val io = IO(new Bundle {
        val reset_addr = Input(UInt(64.W))
        val mem = new NativeMemIO
    })
    val fe = Module(new GustEngine())
    val instbuf = Module(new InstBuffer())
    val be = Module(new Backend())
    fe.io.reset_addr := io.reset_addr
    be.io.reset_addr := io.reset_addr
    fe.io.instpack <> instbuf.io.fe_in
    fe.io.be_ctrl <> be.io.fe_ctl
    instbuf.io.out <> be.io.imem
    instbuf.io.be_in <> be.io.inst_buf_ctrl
    fe.io.mem <> io.mem
    // 暂时不接dcache
    be.io.dmem.rdata := 0.U
    be.io.dmem.resp_addr := 0.U
    be.io.dmem.can_next := false.B
}

object FireVirFlow extends App {
    println("Generating the VirFlow hardware")
    ChiselStage.emitSystemVerilogFile(
        new VirFlow(),
        Array("--target-dir", "../sim/vir_flow/rtl"),
        firtoolOpts = Array(
            "-disable-all-randomization",
            "-strip-debug-info",
            "-default-layer-specialization=enable"
            )
    )
}