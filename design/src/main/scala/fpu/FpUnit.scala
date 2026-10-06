package flow.fpu

import chisel3._
import chisel3.util._
import flow.backend.FpResult
import flow.interface.L1DDestination

class FpRequest extends Bundle {
  val operandA = UInt(64.W)
  val operandB = UInt(64.W)
  val operandC = UInt(64.W)
  val rm = UInt(3.W)
  val operation = UInt(4.W)
  val opMod = Bool()
  val srcFmt = UInt(3.W)
  val dstFmt = UInt(3.W)
  val intFmt = UInt(2.W)
  val rd = new L1DDestination
}
class FpEntry extends Bundle {
  val valid = Bool()
  val committed = Bool()
  val rd = UInt(5.W)
  val isFp = Bool()
}

/** Direct EX-to-CVFPU and CVFPU-to-writeback paths. Only metadata is stored.
  * Kill never flushes CVFPU: already committed calculations must survive.
  */
class FpUnit(val depth: Int = 32) extends Module {
  require(depth >= 8 && isPow2(depth), "FP tag table requires a power-of-two depth >= 8")
  val tagWidth = log2Ceil(depth)
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new FpRequest))
    val commit = Input(Bool())
    val killUncommitted = Input(Bool())
    val result = Decoupled(new FpResult)
    val busy = Output(Bool())
    val committedGpr = Output(UInt(32.W))
    val committedFpr = Output(UInt(32.W))
    val committedFlagsOnly = Output(Bool())
  })
  val impl = Module(new FlowFpnewBlackBox(tagWidth))
  val entries = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new FpEntry))))
  val allocate = RegInit(0.U(tagWidth.W))
  val commitCursor = RegInit(0.U(tagWidth.W))
  // Invalidated tags remain inside CVFPU until it drains. Block tag reuse
  // during this interval rather than adding per-entry tombstones/data buffers.
  val killDrain = RegInit(false.B)
  impl.io.clk_i := clock
  impl.io.reset_i := reset.asBool
  impl.io.flush_i := false.B
  val canAllocate = !entries(allocate).valid && !killDrain && !io.killUncommitted
  impl.io.in_valid_i := io.req.valid && canAllocate
  io.req.ready := impl.io.in_ready_o && canAllocate
  impl.io.tag_i := allocate
  impl.io.operand_a_i := io.req.bits.operandA
  impl.io.operand_b_i := io.req.bits.operandB
  impl.io.operand_c_i := io.req.bits.operandC
  impl.io.rnd_mode_i := io.req.bits.rm
  impl.io.op_i := io.req.bits.operation
  impl.io.op_mod_i := io.req.bits.opMod
  impl.io.src_fmt_i := io.req.bits.srcFmt
  impl.io.dst_fmt_i := io.req.bits.dstFmt
  impl.io.int_fmt_i := io.req.bits.intFmt

  val candidates = VecInit((0 until depth).map { offset =>
    val index = (commitCursor + offset.U)(tagWidth - 1, 0)
    entries(index).valid && !entries(index).committed
  })
  val oldest = (commitCursor + PriorityEncoder(candidates.asUInt))(tagWidth - 1, 0)
  val returned = entries(impl.io.tag_o)
  io.result.valid := impl.io.out_valid_o && returned.valid && returned.committed
  io.result.bits.rd.idx := returned.rd
  io.result.bits.rd.isFp := returned.isFp
  io.result.bits.data := impl.io.result_o
  io.result.bits.flags := impl.io.status_o
  // Invalidated returns are discarded. A live early result waits for commit.
  // CVFPU output arbitration may change tag/data while stalled: sample on fire.
  impl.io.out_ready_i := !returned.valid || (returned.committed && io.result.ready)
  io.busy := entries.map(_.valid).reduce(_ || _) || impl.io.busy_o || killDrain
  def pending(fp: Boolean): UInt = entries.map { e =>
    Mux(e.valid && e.committed && e.isFp === fp.B && (fp.B || e.rd =/= 0.U),
      UIntToOH(e.rd, 32), 0.U(32.W))
  }.reduce(_ | _)
  io.committedGpr := pending(false)
  io.committedFpr := pending(true)
  io.committedFlagsOnly := entries.map(e => e.valid && e.committed && !e.isFp && e.rd === 0.U).reduce(_ || _)

  when(io.req.fire) {
    entries(allocate).valid := true.B
    entries(allocate).committed := false.B
    entries(allocate).rd := io.req.bits.rd.idx
    entries(allocate).isFp := io.req.bits.rd.isFp
    allocate := allocate + 1.U
  }
  when(io.commit) {
    entries(oldest).committed := true.B
    commitCursor := oldest + 1.U
  }
  when(io.result.fire) { entries(impl.io.tag_o).valid := false.B }
  when(killDrain && !impl.io.busy_o) { killDrain := false.B }
  when(io.killUncommitted) {
    for (i <- 0 until depth) {
      // Resolve commit before kill even when both pulses arrive together.
      when(entries(i).valid && !entries(i).committed && !(io.commit && oldest === i.U)) {
        entries(i).valid := false.B
      }
    }
    commitCursor := Mux(io.commit, oldest + 1.U, allocate)
    killDrain := true.B
  }
  when(!reset.asBool) {
    assert(!io.commit || candidates.asUInt.orR, "[S04] FPU commit without live uncommitted item")
    assert(!(io.req.fire && io.killUncommitted), "[S05] kill did not suppress FP request")
    assert(!io.result.fire || (returned.valid && returned.committed), "[S05/S08] speculative FP write")
    assert(io.req.fire === (impl.io.in_valid_i && impl.io.in_ready_o), "[T14] input handshake changed")
    assert(io.result.fire === (impl.io.out_valid_o && impl.io.out_ready_i && returned.valid),
      "[T15] output handshake changed")
    for (i <- 0 until depth; j <- i + 1 until depth) {
      assert(!(entries(i).valid && entries(j).valid &&
        entries(i).isFp === entries(j).isFp && entries(i).rd === entries(j).rd &&
        (entries(i).isFp || entries(i).rd =/= 0.U)), "[S01] duplicate FP destination")
    }
  }
}
