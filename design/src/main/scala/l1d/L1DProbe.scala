package flow.l1d

import chisel3._
import chisel3.util._
import flow.coherence._

/** SNP receive register, hold check, whole-line read and answer (l1d-rtl-spec §10). */
class L1DProbe(p: L1DParams) extends Module {
  val io = IO(new Bundle {
    val snp = Flipped(Decoupled(new CoherenceSnp(p.coh)))
    val initDone = Input(Bool())

    /** §10.2 hold conditions, evaluated by the top from miss/rsv/AMO/PS state. */
    val hold = Input(Bool())
    /** §10.1 start condition: no unheld same-line store in S1/S2, PS not same line. */
    val startOk = Input(Bool())
    val pending = Output(new L1ProbeReg(p))

    // S0 whole-line read: wordsPerLine beats, fixed length
    val s0Req = Valid(UInt(p.wordBits.W))
    val s0Grant = Input(Bool())
    /** Per-beat S2 result: beat 0 also carries the looked-up local state. */
    val s2Beat = Flipped(Valid(new Bundle {
      val beat = UInt(p.wordBits.W)
      val data = UInt(64.W)
      val hitWay = UInt(p.wayBits.W)
      val localState = L1State()
    }))
    /** Final S2 beat: top writes the tag (I or S) using this. */
    val tagUpdate = Valid(new Bundle {
      val way = UInt(p.wayBits.W)
      val newState = L1State()
    })

    val ack = Decoupled(new CoherenceRspUp(p.coh))
  })

  val r = RegInit(0.U.asTypeOf(new L1ProbeReg(p)))
  io.snp.ready := !r.valid && io.initDone
  when(io.snp.fire) {
    r.valid := true.B
    r.op := io.snp.bits.op
    r.owner := io.snp.bits.owner
    r.addr := io.snp.bits.addr
  }
  io.pending := r

  object St extends ChiselEnum { val Idle, Read, Answer = Value }
  val st = RegInit(St.Idle)
  val issueBeat = RegInit(0.U(p.wordBits.W))
  val line = Reg(Vec(p.wordsPerLine, UInt(64.W)))
  val localState = Reg(L1State())
  val way = Reg(UInt(p.wayBits.W))

  io.s0Req.valid := r.valid && st === St.Idle && !io.hold && io.startOk ||
    st === St.Read && issueBeat =/= 0.U
  io.s0Req.bits := issueBeat
  when(io.s0Grant) {
    when(st === St.Idle) { st := St.Read }
    issueBeat := issueBeat + 1.U // wraps to 0 after the last beat
  }

  io.tagUpdate.valid := false.B
  io.tagUpdate.bits.way := way
  io.tagUpdate.bits.newState := Mux(r.op === SnpOp.Inv, L1State.I, L1State.S)
  when(io.s2Beat.valid) {
    line(io.s2Beat.bits.beat) := io.s2Beat.bits.data
    when(io.s2Beat.bits.beat === 0.U) {
      localState := io.s2Beat.bits.localState
      way := io.s2Beat.bits.hitWay
    }
    when(io.s2Beat.bits.beat === (p.wordsPerLine - 1).U) {
      // TODO: use beat-0 state when the final beat is the same cycle (wordsPerLine == 1)
      io.tagUpdate.valid := localState =/= L1State.I
      st := St.Answer
    }
  }

  io.ack.valid := st === St.Answer
  io.ack.bits.op := Mux(r.op === SnpOp.Inv, RspUpOp.InvAck, RspUpOp.DownAck)
  io.ack.bits.hasData := localState === L1State.M
  io.ack.bits.addr := r.addr
  io.ack.bits.data := line.asUInt
  when(io.ack.fire) {
    st := St.Idle
    r.valid := false.B
  }
  // TODO: clear reservation when the probe hits rsvLine (§10.3); snapshot
  // invalidation of same-set S1/S2 on tag update (§5.3) is done by the top.
}
