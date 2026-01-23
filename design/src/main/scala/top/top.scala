package top

import chisel3._
import chisel3.util._
import core._
import mem._

class flow_top(val flow_config:FlowConfig) extends Module {
    val io = IO(new Bundle{
        val error = Output(Bool())
    })
    io.error := false.B
    val core: CoreModule = flow_config.coretype match {
        case `in_order` => Module(new in_order_core())
        case `early_core` => Module(new early_core())
    }
    val mem:MemModule = flow_config.memory match {
        case RegArrayMem(depth, path, dumplog) => Module(new regarryMem(depth, path))
        case SRAMSysMem(depth, path, dumplog) => Module(new SRAMMemory(depth, path))
        case _ => throw new Exception("Unsupported memory type!")
    }
    //connect
    core.io.itcm <> mem.io.imem_port
    core.io.dtcm <> mem.io.dmem_port
}

class litex_flow_top extends Module {
    val io = IO(new Bundle{
        val bus=new wishbone.litex_wb_port()
        val reset_addr=Input(UInt(64.W))
    })
    val fe = Module(new SimpleFrontend())
    val instbuf = Module(new InstBuffer())
    val be = Module(new Backend())
    val memif = Module(new cpu_wb_bus_if())
    fe.io.fetch <> instbuf.io.fe_in
    fe.io.be_ctrl <> be.io.fe_ctl
    instbuf.io.be_in := be.io.inst_buf_ctrl
    be.io.imem <> instbuf.io.out
    fe.io.memreq <> memif.io.frontend
    be.io.dmem <> memif.io.backend
    memif.io.wb <> io.bus
    fe.io.reset_addr := io.reset_addr
    be.io.reset_addr := io.reset_addr
}

import _root_.circt.stage.ChiselStage

object GenerateTop extends App {
    val flow_config = new FlowConfig(
        memtype = "sram",
        memsize = 1024,
        mempath = "/home/chen/FUN/flow/sim/top/test02.hex",
        dumplog = true,
        core = "in_order"
    )
    ChiselStage.emitSystemVerilogFile(
        new flow_top(flow_config),
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}

object GenerateFlowTop extends App {
    ChiselStage.emitSystemVerilogFile(
        new litex_flow_top(),
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}