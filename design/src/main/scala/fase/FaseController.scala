package flow.fase

import chisel3._
import chisel3.util._
import flow.interface.FASECoreIO

/** Transport-neutral command records. JTAG/MMIO must assemble these in the
  * CPU clock domain; this module deliberately contains no TAP or CDC logic.
  * Commands and responses use ready/valid and may be backpressured indefinitely.
  */
class FaseCommand extends Bundle {
  val opcode = UInt(8.W)
  val index = UInt(6.W)
  val data = UInt(64.W)
  val pc = UInt(64.W)
}
class FaseResponse extends Bundle {
  val error = Bool()
  val data = UInt(64.W)
}
class FaseCommandIO extends Bundle {
  val cmd = Flipped(Decoupled(new FaseCommand))
  val rsp = Decoupled(new FaseResponse)
}
object FaseOpcode {
  val Status = 0; val Halt = 1; val Exec = 2; val ReadReg = 3
  val WriteReg = 4; val Launch = 5; val Snapshot = 6; val ReadSnapshot = 7
  val NextPc = 8; val Cause = 9; val Tval = 10
}

/** No automatic context restoration, no code window, no special fence engine.
  * EXEC accepts one instruction and responds immediately. Poll STATUS until
  * busy clears; diagnostic commands remain usable if execution/drain stalls.
  * Only reset may discard an outstanding operation.
  */
class FaseController extends Module {
  val io = IO(new Bundle {
    val host = new FaseCommandIO
    val cpu = Flipped(new FASECoreIO)
  })
  val idle :: send :: waitDone :: Nil = Enum(3)
  val state = RegInit(idle)
  val owned = RegInit(false.B)
  val instruction = Reg(UInt(32.W))
  val pc = Reg(UInt(64.W))
  val finished = RegInit(false.B)
  val faulted = RegInit(false.B)
  val cause = RegInit(0.U(64.W))
  val tval = RegInit(0.U(64.W))
  val seenEvent = RegInit(false.B)
  val snapshot = RegInit(VecInit(Seq.fill(48)(0.U(64.W))))
  val snapshotValid = RegInit(false.B)
  val replyValid = RegInit(false.B)
  val reply = RegInit(0.U.asTypeOf(new FaseResponse))
  io.host.rsp.valid := replyValid
  io.host.rsp.bits := reply
  io.host.cmd.ready := !replyValid || io.host.rsp.ready
  when(io.host.rsp.fire) { replyValid := false.B }

  io.cpu.halt := owned
  io.cpu.launch := false.B
  io.cpu.launchPc := io.host.cmd.bits.pc
  io.cpu.inst_valid := state === send
  io.cpu.instruction := instruction
  io.cpu.inst_pc := pc
  io.cpu.regIndex := io.host.cmd.bits.index(4, 0)
  io.cpu.regWdata := io.host.cmd.bits.data
  io.cpu.regWrite := false.B
  val available = owned && io.cpu.halted && io.cpu.empty && state === idle
  val status = Cat(0.U(56.W), snapshotValid, state === send,
    faulted, finished, state =/= idle, io.cpu.empty, io.cpu.halted, owned)

  when(state === send && io.cpu.inst_ready) { state := waitDone }
  when(state === waitDone) {
    when(io.cpu.retired || io.cpu.fault) { seenEvent := true.B }
    when(io.cpu.fault) {
      faulted := true.B
      cause := io.cpu.cause
      tval := io.cpu.tval
    }
    when(seenEvent && io.cpu.empty) {
      finished := true.B
      state := idle
    }
  }
  when(io.host.cmd.fire) {
    replyValid := true.B
    reply.error := false.B
    reply.data := 0.U
    switch(io.host.cmd.bits.opcode) {
      is(FaseOpcode.Status.U) { reply.data := status }
      is(FaseOpcode.Halt.U) { owned := true.B }
      is(FaseOpcode.Exec.U) {
        when(available) {
          instruction := io.host.cmd.bits.data(31, 0)
          pc := io.host.cmd.bits.pc
          state := send
          seenEvent := false.B
          finished := false.B
          faulted := false.B
          cause := 0.U; tval := 0.U
        }.otherwise { reply.error := true.B }
      }
      is(FaseOpcode.ReadReg.U) {
        when(available && io.host.cmd.bits.index < 32.U) {
          reply.data := io.cpu.regRdata
        }.otherwise { reply.error := true.B }
      }
      is(FaseOpcode.WriteReg.U) {
        when(available && io.host.cmd.bits.index < 32.U) {
          io.cpu.regWrite := true.B
        }.otherwise { reply.error := true.B }
      }
      is(FaseOpcode.Launch.U) {
        // RV64GC allows halfword-aligned PCs. Other profiles reject misalignment
        // architecturally; bit zero can never be a valid instruction address.
        when(available && !io.host.cmd.bits.pc(0)) {
          io.cpu.launch := true.B
          owned := false.B
        }.otherwise { reply.error := true.B }
      }
      is(FaseOpcode.Snapshot.U) {
        snapshot := io.cpu.diagnostic
        snapshotValid := true.B
      }
      is(FaseOpcode.ReadSnapshot.U) {
        when(snapshotValid && io.host.cmd.bits.index < 48.U) {
          reply.data := snapshot(io.host.cmd.bits.index)
        }.otherwise { reply.error := true.B }
      }
      is(FaseOpcode.NextPc.U) { reply.data := io.cpu.nextPc }
      is(FaseOpcode.Cause.U) { reply.data := cause }
      is(FaseOpcode.Tval.U) { reply.data := tval }
    }
    when(io.host.cmd.bits.opcode > FaseOpcode.Tval.U) { reply.error := true.B }
  }
}
