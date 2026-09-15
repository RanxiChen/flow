package flow.fase

import chisel3._
import chisel3.util.HasBlackBoxPath
import flow.config.BreezeClusterConfig
import flow.top.BreezeMulticoreClusterWishbone

class FaseTransportBlackBox extends BlackBox with HasBlackBoxPath {
  override def desiredName = "FlowFaseJtagTransport"
  val io = IO(new Bundle {
    val sys_clk = Input(Clock()); val reset = Input(Bool()); val tck = Input(Clock())
    val sel = Input(Bool()); val capture = Input(Bool()); val shift = Input(Bool())
    val update = Input(Bool()); val tap_reset = Input(Bool()); val tdi = Input(Bool())
    val tdo = Output(Bool())
    val cmd_valid = Output(Bool()); val cmd_ready = Input(Bool())
    val cmd_opcode = Output(UInt(8.W)); val cmd_index = Output(UInt(6.W))
    val cmd_data = Output(UInt(64.W)); val cmd_pc = Output(UInt(64.W))
    val rsp_valid = Input(Bool()); val rsp_ready = Output(Bool())
    val rsp_error = Input(Bool()); val rsp_data = Input(UInt(64.W))
    val debug_flags = Output(UInt(8.W))
    val debug_received = Output(UInt(16.W)); val debug_dispatched = Output(UInt(16.W))
    val debug_responded = Output(UInt(16.W)); val debug_tag = Output(UInt(16.W))
  })
  addPath((os.pwd / os.up / "litex_wrapper" / "flow" / "rtl" / "FlowFaseJtag.sv").toString)
}

/** Keep the same memory/CPU test harness, optionally insert the actual serial
  * transport. Only the vendor TAP primitive is replaced by its pin-level driver.
  */
class FaseIntegrationHarness(cfg: BreezeClusterConfig, serial: Boolean) extends Module {
  val cluster = Module(new BreezeMulticoreClusterWishbone(cfg, enableTandem = true, useFASE = true))
  val io = IO(chiselTypeOf(cluster.io))
  io <> cluster.io
  val jtag = IO(new Bundle {
    val tck = Input(Bool()); val sel = Input(Bool()); val capture = Input(Bool())
    val shift = Input(Bool()); val update = Input(Bool()); val tdi = Input(Bool())
    val tdo = Output(Bool())
  })
  jtag.tdo := false.B
  if (serial) {
    val transport = Module(new FaseTransportBlackBox)
    val t = transport.io
    t.sys_clk := clock; t.reset := reset.asBool; t.tck := jtag.tck.asClock
    t.sel := jtag.sel; t.capture := jtag.capture; t.shift := jtag.shift
    t.update := jtag.update; t.tdi := jtag.tdi; t.tap_reset := false.B
    jtag.tdo := t.tdo
    val f = cluster.io.fase.get(0)
    f.cmd.valid := t.cmd_valid; t.cmd_ready := f.cmd.ready
    f.cmd.bits.opcode := t.cmd_opcode; f.cmd.bits.index := t.cmd_index
    f.cmd.bits.data := t.cmd_data; f.cmd.bits.pc := t.cmd_pc
    t.rsp_valid := f.rsp.valid; f.rsp.ready := t.rsp_ready
    t.rsp_error := f.rsp.bits.error; t.rsp_data := f.rsp.bits.data
  }
}
