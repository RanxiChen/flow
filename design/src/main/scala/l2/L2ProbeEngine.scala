package flow.l2

import chisel3._
import chisel3.util._
import flow.coherence._

/** Serves one slot at a time: send SNP per core, collect answers (coherence-l2-rtl-spec §6). */
class L2ProbeEngine(p: CoherenceParams) extends Module {
  val io = IO(new Bundle {
    val job = Flipped(Decoupled(new L2ProbeJob(p)))
    val snp = Vec(p.nCores, Decoupled(new CoherenceSnp(p)))
    /** Probe answer written into core c's answer buffer this cycle. */
    val answer = Input(Vec(p.nCores, Valid(new L2ProbeAnswer(p))))
    /** All answers collected for `slot`. */
    val collected = Valid(UInt(p.slotBits.W))
    /** The slot's next task cleared the answer buffers in S2: engine may take the next job. */
    val release = Input(Bool())
    /** A job is outstanding and not all answers are in (event only). */
    val active = Output(Bool())
  })

  val busy = RegInit(false.B)
  val done = RegInit(false.B)
  val j = Reg(new L2ProbeJob(p))
  val toSend = RegInit(0.U(p.nCores.W))
  val waitAck = RegInit(0.U(p.nCores.W))

  io.job.ready := !busy
  when(io.job.fire) {
    busy := true.B
    done := false.B
    j := io.job.bits
    toSend := io.job.bits.targets
    waitAck := 0.U
  }

  val sentNow = Wire(Vec(p.nCores, Bool()))
  val ackNow = Wire(Vec(p.nCores, Bool()))
  for (c <- 0 until p.nCores) {
    io.snp(c).valid := busy && toSend(c)
    io.snp(c).bits.op := j.op
    io.snp(c).bits.owner := j.owner
    io.snp(c).bits.addr := j.addr
    sentNow(c) := io.snp(c).fire
    ackNow(c) := io.answer(c).valid
    when(io.answer(c).valid) {
      assert(busy && (waitAck(c) || sentNow(c)), "probe answer from a core not probed")
      assert(io.answer(c).bits.addr === j.addr, "probe answer address mismatch")
      assert(!io.answer(c).bits.hasData || j.owner, "probe data from a sharer")
      assert(io.answer(c).bits.op === Mux(j.op === SnpOp.Inv, RspUpOp.InvAck, RspUpOp.DownAck),
        "probe answer opcode mismatch")
    }
  }
  val toSendNext = toSend & ~sentNow.asUInt
  val waitAckNext = (waitAck | sentNow.asUInt) & ~ackNow.asUInt
  when(busy && !io.job.fire) {
    toSend := toSendNext
    waitAck := waitAckNext
  }

  val allIn = busy && !done && toSendNext === 0.U && waitAckNext === 0.U
  io.collected.valid := allIn
  io.collected.bits := j.slot
  when(allIn) { done := true.B }
  io.active := busy && !done
  when(io.release) {
    assert(busy && done)
    busy := false.B
  }
}
