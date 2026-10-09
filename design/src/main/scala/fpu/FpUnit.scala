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

/** Buffered EX-to-CVFPU requests and registered CVFPU-to-writeback results.
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
  // No flow/pipe bypass: backend ready depends only on registered occupancy.
  // Allocate the tag on backend acceptance, so WB may commit queued work.
  val input = Module(new Queue(new Bundle {
    val request = new FpRequest
    val tag = UInt(tagWidth.W)
  }, 2, pipe = false, flow = false))
  impl.io.clk_i := clock
  impl.io.reset_i := reset.asBool
  impl.io.flush_i := false.B
  val canAllocate = !entries(allocate).valid && !killDrain
  input.io.enq.valid := io.req.valid && canAllocate
  input.io.enq.bits.request := io.req.bits
  input.io.enq.bits.tag := allocate
  io.req.ready := input.io.enq.ready && canAllocate
  val queuedLive = entries(input.io.deq.bits.tag).valid
  impl.io.in_valid_i := input.io.deq.valid && queuedLive
  // Killed queued work is discarded locally; committed queued work survives.
  input.io.deq.ready := !queuedLive || impl.io.in_ready_o
  impl.io.tag_i := input.io.deq.bits.tag
  val request = input.io.deq.bits.request
  impl.io.operand_a_i := request.operandA
  impl.io.operand_b_i := request.operandB
  impl.io.operand_c_i := request.operandC
  impl.io.rnd_mode_i := request.rm
  impl.io.op_i := request.operation
  impl.io.op_mod_i := request.opMod
  impl.io.src_fmt_i := request.srcFmt
  impl.io.dst_fmt_i := request.dstFmt
  impl.io.int_fmt_i := request.intFmt

  val candidates = VecInit((0 until depth).map { offset =>
    val index = (commitCursor + offset.U)(tagWidth - 1, 0)
    entries(index).valid && !entries(index).committed
  })
  val oldest = (commitCursor + PriorityEncoder(candidates.asUInt))(tagWidth - 1, 0)
  val returned = entries(impl.io.tag_o)
  // Only the actual CVFPU handshake fixes arbitration tag/data. A two-entry
  // non-flow/non-pipe queue makes CVFPU ready depend on registered occupancy,
  // rather than on WB/scoreboard/ID advance in this cycle.
  val completed = Module(new Queue(new Bundle {
    val tag = UInt(tagWidth.W)
    val result = new FpResult
  }, 2, pipe = false, flow = false))
  val resultBuffered = RegInit(VecInit(Seq.fill(depth)(false.B)))
  completed.io.enq.valid := impl.io.out_valid_o && returned.valid && returned.committed
  completed.io.enq.bits.tag := impl.io.tag_o
  completed.io.enq.bits.result.rd.idx := returned.rd
  completed.io.enq.bits.result.rd.isFp := returned.isFp
  completed.io.enq.bits.result.data := impl.io.result_o
  completed.io.enq.bits.result.flags := impl.io.status_o
  io.result.valid := completed.io.deq.valid
  io.result.bits := completed.io.deq.bits.result
  completed.io.deq.ready := io.result.ready
  impl.io.out_ready_i := !returned.valid || (returned.committed && completed.io.enq.ready)
  val completingTag = completed.io.deq.bits.tag
  val completingEntry = entries(completingTag)
  io.busy := entries.map(_.valid).reduce(_ || _) || impl.io.busy_o ||
    input.io.deq.valid || completed.io.deq.valid || killDrain
  def pending(fp: Boolean): UInt = entries.map { e =>
    Mux(e.valid && e.committed && e.isFp === fp.B && (fp.B || e.rd =/= 0.U),
      UIntToOH(e.rd, 32), 0.U(32.W))
  }.reduce(_ | _)
  io.committedGpr := pending(false)
  io.committedFpr := pending(true)
  // Same registered ownership as the table, without a table-wide late OR.
  val flagsOnlyCount = RegInit(0.U(log2Ceil(depth + 1).W))
  val flagsSet = io.commit && !entries(oldest).isFp && entries(oldest).rd === 0.U
  val flagsClear = io.result.fire && !completingEntry.isFp && completingEntry.rd === 0.U
  when(flagsSet =/= flagsClear) {
    flagsOnlyCount := Mux(flagsSet, flagsOnlyCount + 1.U, flagsOnlyCount - 1.U)
  }
  io.committedFlagsOnly := flagsOnlyCount =/= 0.U

  when(io.req.fire) {
    // The pre-edge entry is empty, so the kill loop below cannot see a new
    // request. Explicitly tombstone a fire/kill allocation at this edge.
    entries(allocate).valid := !io.killUncommitted
    entries(allocate).committed := false.B
    entries(allocate).rd := io.req.bits.rd.idx
    entries(allocate).isFp := io.req.bits.rd.isFp
    allocate := allocate + 1.U
  }
  when(io.commit) {
    entries(oldest).committed := true.B
    commitCursor := oldest + 1.U
  }
  when(completed.io.enq.fire) { resultBuffered(impl.io.tag_o) := true.B }
  when(io.result.fire) {
    entries(completingTag).valid := false.B
    resultBuffered(completingTag) := false.B
  }
  when(killDrain && !impl.io.busy_o && !input.io.deq.valid) { killDrain := false.B }
  when(io.killUncommitted) {
    for (i <- 0 until depth) {
      // Resolve commit before kill even when both pulses arrive together.
      when(entries(i).valid && !entries(i).committed && !(io.commit && oldest === i.U)) {
        entries(i).valid := false.B
      }
    }
    commitCursor := Mux(io.commit, oldest + 1.U, allocate + io.req.fire.asUInt)
    killDrain := true.B
  }
  val killedFire = RegNext(io.req.fire && io.killUncommitted, false.B)
  val killedTag = RegEnable(allocate, io.req.fire && io.killUncommitted)
  when(!reset.asBool) {
    assert(!io.commit || candidates.asUInt.orR, "[S04] FPU commit without live uncommitted item")
    when(killedFire) {
      assert(!entries(killedTag).valid && killDrain, "[S05] killed FP allocation survived or reused its tag")
    }
    assert(!killDrain || !io.req.fire, "[S05] FP tag reused before killed returns drained")
    assert(!io.result.fire || (completingEntry.valid && completingEntry.committed && resultBuffered(completingTag)), "[S05/S08] speculative FP write")
    assert(io.req.fire === input.io.enq.fire, "[T14] input enqueue handshake changed")
    assert(!(impl.io.in_valid_i && impl.io.in_ready_o) ||
      (input.io.deq.fire && queuedLive), "[T14] CVFPU accepted an unowned request")
    assert(flagsOnlyCount === PopCount(entries.map(e =>
      e.valid && e.committed && !e.isFp && e.rd === 0.U)), "[S14] flags ownership count differs from table")
    assert(completed.io.enq.fire ===
      (impl.io.out_valid_o && impl.io.out_ready_i && returned.valid),
      "[T15] buffered CVFPU return handshake changed")
    assert(!completed.io.enq.fire || !resultBuffered(impl.io.tag_o),
      "[SOC3e] CVFPU returned the same live tag twice")
    when(completed.io.deq.valid) {
      assert(completingEntry.valid && completingEntry.committed && resultBuffered(completingTag),
        "[SOC3e] buffered FP result lost committed ownership")
      assert(io.result.bits.rd.idx === completingEntry.rd &&
        io.result.bits.rd.isFp === completingEntry.isFp, "[SOC3e] FP result destination changed")
    }
    for (i <- 0 until depth; j <- i + 1 until depth) {
      assert(!(entries(i).valid && entries(j).valid &&
        entries(i).isFp === entries(j).isFp && entries(i).rd === entries(j).rd &&
        (entries(i).isFp || entries(i).rd =/= 0.U)), "[S01] duplicate FP destination")
    }
  }
}
