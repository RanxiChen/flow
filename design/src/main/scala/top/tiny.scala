package flow.top

import chisel3._
import chisel3.util._
import flow.interface.LiteXWishboneMaster
import _root_.circt.stage.ChiselStage
import chisel3.BlackBox
import chisel3.util.HasBlackBoxInline

class TinyCoreBusMonitor extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock          = Input(Clock())
    val reset          = Input(Bool())
    val reset_addr     = Input(UInt(64.W))
    val cycle          = Input(UInt(64.W))
    val ibus_ack       = Input(Bool())
    val ibus_err       = Input(Bool())
    val ibus_addr      = Input(UInt(64.W))
    val ibus_data      = Input(UInt(64.W))
    val dbus_write_ack = Input(Bool())
    val dbus_write_err = Input(Bool())
    val dbus_read_ack  = Input(Bool())
    val dbus_read_err  = Input(Bool())
    val dbus_addr_word = Input(UInt(32.W))
    val dbus_wdata     = Input(UInt(64.W))
    val dbus_rdata     = Input(UInt(64.W))
    val done_valid     = Input(Bool())
    val done_data      = Input(UInt(64.W))
  })

  setInline(
    "TinyCoreBusMonitor.v",
    """module TinyCoreBusMonitor(
      |  input         clock,
      |  input         reset,
      |  input  [63:0] reset_addr,
      |  input  [63:0] cycle,
      |  input         ibus_ack,
      |  input         ibus_err,
      |  input  [63:0] ibus_addr,
      |  input  [63:0] ibus_data,
      |  input         dbus_write_ack,
      |  input         dbus_write_err,
      |  input         dbus_read_ack,
      |  input         dbus_read_err,
      |  input  [31:0] dbus_addr_word,
      |  input  [63:0] dbus_wdata,
      |  input  [63:0] dbus_rdata,
      |  input         done_valid,
      |  input  [63:0] done_data
      |);
      |  reg seen_reset_release;
      |  integer log_fd;
      |
      |  initial begin
      |    log_fd = $fopen("tinycore_bus.log", "w");
      |  end
      |
      |  task automatic log_line;
      |    input [8*160-1:0] text;
      |    begin
      |      $display("%0s", text);
      |      if (log_fd != 0) begin
      |        $fdisplay(log_fd, "%0s", text);
      |        $fflush(log_fd);
      |      end
      |    end
      |  endtask
      |
      |  always @(posedge clock) begin
      |    if (reset) begin
      |      seen_reset_release <= 1'b0;
      |    end else begin
      |      if (!seen_reset_release) begin
      |        $display("[tinycore][cycle=%0d] reset release reset_addr=0x%0x", cycle, reset_addr);
      |        if (log_fd != 0) begin
      |          $fdisplay(log_fd, "[tinycore][cycle=%0d] reset release reset_addr=0x%0x", cycle, reset_addr);
      |          $fflush(log_fd);
      |        end
      |        seen_reset_release <= 1'b1;
      |      end
      |      if (ibus_ack) begin
      |        $display("[tinycore][cycle=%0d] ibus ack addr=0x%0x data=0x%0x", cycle, ibus_addr, ibus_data);
      |        if (log_fd != 0) begin
      |          $fdisplay(log_fd, "[tinycore][cycle=%0d] ibus ack addr=0x%0x data=0x%0x", cycle, ibus_addr, ibus_data);
      |          $fflush(log_fd);
      |        end
      |      end
      |      if (ibus_err) begin
      |        $display("[tinycore][cycle=%0d] ibus err addr=0x%0x", cycle, ibus_addr);
      |        if (log_fd != 0) begin
      |          $fdisplay(log_fd, "[tinycore][cycle=%0d] ibus err addr=0x%0x", cycle, ibus_addr);
      |          $fflush(log_fd);
      |        end
      |      end
      |      if (dbus_write_ack) begin
      |        $display("[tinycore][cycle=%0d] dbus write ack adr_word=0x%0x data=0x%0x", cycle, dbus_addr_word, dbus_wdata);
      |        if (log_fd != 0) begin
      |          $fdisplay(log_fd, "[tinycore][cycle=%0d] dbus write ack adr_word=0x%0x data=0x%0x", cycle, dbus_addr_word, dbus_wdata);
      |          $fflush(log_fd);
      |        end
      |      end
      |      if (dbus_write_err) begin
      |        $display("[tinycore][cycle=%0d] dbus write err adr_word=0x%0x", cycle, dbus_addr_word);
      |        if (log_fd != 0) begin
      |          $fdisplay(log_fd, "[tinycore][cycle=%0d] dbus write err adr_word=0x%0x", cycle, dbus_addr_word);
      |          $fflush(log_fd);
      |        end
      |      end
      |      if (dbus_read_ack) begin
      |        $display("[tinycore][cycle=%0d] dbus read ack adr_word=0x%0x data=0x%0x", cycle, dbus_addr_word, dbus_rdata);
      |        if (log_fd != 0) begin
      |          $fdisplay(log_fd, "[tinycore][cycle=%0d] dbus read ack adr_word=0x%0x data=0x%0x", cycle, dbus_addr_word, dbus_rdata);
      |          $fflush(log_fd);
      |        end
      |      end
      |      if (dbus_read_err) begin
      |        $display("[tinycore][cycle=%0d] dbus read err adr_word=0x%0x", cycle, dbus_addr_word);
      |        if (log_fd != 0) begin
      |          $fdisplay(log_fd, "[tinycore][cycle=%0d] dbus read err adr_word=0x%0x", cycle, dbus_addr_word);
      |          $fflush(log_fd);
      |        end
      |      end
      |      if (done_valid) begin
      |        $display("[tinycore][cycle=%0d] done readback=0x%0x", cycle, done_data);
      |        if (log_fd != 0) begin
      |          $fdisplay(log_fd, "[tinycore][cycle=%0d] done readback=0x%0x", cycle, done_data);
      |          $fflush(log_fd);
      |        end
      |        $finish;
      |      end
      |    end
      |  end
      |endmodule
      |""".stripMargin
  )
}

class TinyCoreLitexTop extends Module {
  val io = IO(new Bundle {
    val reset_addr = Input(UInt(64.W))
    val ibus       = new LiteXWishboneMaster(addrWidth = 32, dataWidth = 64)
    val dbus       = new LiteXWishboneMaster(addrWidth = 32, dataWidth = 64)
  })

  private def wbInit(wb: LiteXWishboneMaster): Unit = {
    wb.adr   := 0.U
    wb.dat_w := 0.U
    wb.sel   := "hff".U
    wb.cyc   := false.B
    wb.stb   := false.B
    wb.we    := false.B
    wb.cti   := 0.U
    wb.bte   := 0.U
  }

  wbInit(io.ibus)
  wbInit(io.dbus)

  val pc          = RegInit(0.U(64.W))
  val readback    = RegInit(0.U(64.W))
  val fetchCount  = RegInit(0.U(8.W))
  val dbusBaseAdr = RegInit(0.U(io.dbus.adr.getWidth.W))
  val cycle       = RegInit(0.U(64.W))

  object State extends ChiselEnum {
    val sReset, sIFetch, sDWrite, sDRead, sDone = Value
  }
  import State._
  val state = RegInit(sReset)

  cycle := cycle + 1.U
  val monitor = Module(new TinyCoreBusMonitor)
  monitor.io.clock          := clock
  monitor.io.reset          := reset.asBool
  monitor.io.reset_addr     := io.reset_addr
  monitor.io.cycle          := cycle
  monitor.io.ibus_ack       := io.ibus.ack
  monitor.io.ibus_err       := io.ibus.err
  monitor.io.ibus_addr      := pc
  monitor.io.ibus_data      := io.ibus.dat_r
  monitor.io.dbus_write_ack := state === sDWrite && io.dbus.ack
  monitor.io.dbus_write_err := state === sDWrite && io.dbus.err
  monitor.io.dbus_read_ack  := state === sDRead && io.dbus.ack
  monitor.io.dbus_read_err  := state === sDRead && io.dbus.err
  monitor.io.dbus_addr_word := dbusBaseAdr
  monitor.io.dbus_wdata     := io.dbus.dat_w
  monitor.io.dbus_rdata     := io.dbus.dat_r
  monitor.io.done_valid     := state === sDone && readback =/= 0.U
  monitor.io.done_data      := readback

  switch(state) {
    is(sReset) {
      pc := io.reset_addr
      // Default LiteX SRAM base is 0x1000_0000. For a 64-bit word-addressed WB bus that becomes 0x0200_0000.
      dbusBaseAdr := (BigInt("10000000", 16) >> 3).U
      fetchCount  := 0.U
      readback    := 0.U
      state       := sIFetch
    }

    is(sIFetch) {
      io.ibus.cyc := true.B
      io.ibus.stb := true.B
      io.ibus.we  := false.B
      io.ibus.adr := pc(31, 3)

      when(io.ibus.ack) {
        pc := pc + 8.U
        fetchCount := fetchCount + 1.U
        when(fetchCount === 3.U) {
          state := sDWrite
        }
      }.elsewhen(io.ibus.err) {
        state := sDone
      }
    }

    is(sDWrite) {
      io.dbus.cyc   := true.B
      io.dbus.stb   := true.B
      io.dbus.we    := true.B
      io.dbus.sel   := "hff".U
      io.dbus.adr   := dbusBaseAdr
      io.dbus.dat_w := "h1122334455667788".U

      when(io.dbus.ack) {
        state := sDRead
      }.elsewhen(io.dbus.err) {
        state := sDone
      }
    }

    is(sDRead) {
      io.dbus.cyc := true.B
      io.dbus.stb := true.B
      io.dbus.we  := false.B
      io.dbus.sel := "hff".U
      io.dbus.adr := dbusBaseAdr

      when(io.dbus.ack) {
        readback := io.dbus.dat_r
        state := sDone
      }.elsewhen(io.dbus.err) {
        state := sDone
      }
    }

    is(sDone) {
      state := sDone
    }
  }
}

object GenerateTinyCoreLitexTop extends App {
  ChiselStage.emitSystemVerilogFile(
    new TinyCoreLitexTop(),
    Array("--target-dir", "build/tinycore"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}
