package flow.l1d

import chisel3._
import chisel3.util._
import flow.coherence._
import flow.interface.L1DLate

/** MSHR (nMshrs = 1 in v1) and writeback slot (l1d-rtl-spec §6.2–6.5).
  *
  * SKELETON: state registers and the main transitions are in place; items
  * marked TODO are left for the iterate-with-simulation phase.
  */
class L1DMiss(p: L1DParams) extends Module {
  require(p.nMshrs == 1, "skeleton implements the v1 single MSHR")

  val io = IO(new Bundle {
    // S2 → miss unit
    val alloc = Flipped(Valid(new L1MshrAlloc(p)))
    val status = Output(new L1MissStatus(p))

    // S0 request (install / writeback read / replay) and grant
    val s0Req = Valid(new L1MissS0Req(p))
    val s0Grant = Input(Bool())
    /** Writeback-read beat returned from S2 (the victim word). */
    val wbReadBeat = Flipped(Valid(UInt(64.W)))
    /** Replay reached S2 and finished (Load result is on replayLoad). */
    val replayDone = Input(Bool())
    val replayLoad = Flipped(Valid(new L1DLate))

    // Backend late data (B01 ruling: driven from S2 or the LATE state)
    val late = Decoupled(new L1DLate)

    // Coherence links owned by the miss unit
    val req = Decoupled(new CoherenceReq(p.coh))
    val put = Decoupled(new CoherenceRspUp(p.coh))
    val rspDown = Flipped(Valid(new CoherenceRspDown(p.coh)))
  })

  // ---------------- MSHR registers (§6.2) ----------------
  val state = RegInit(MshrState.Idle)
  val m = Reg(new L1MshrAlloc(p))
  val refill = Reg(UInt(p.coh.lineBits.W))
  val err = Reg(Bool())
  val grantE = Reg(Bool())
  val isAckE = Reg(Bool())
  val lateData = Reg(new L1DLate)
  val beat = RegInit(0.U(p.wordBits.W))

  // ---------------- Writeback slot (§6.3) ----------------
  val wbState = RegInit(WbSlotState.Idle)
  val wbLineAddr = Reg(UInt(p.lineAddrBits.W))
  val wbHasData = Reg(Bool())
  val wbData = Reg(Vec(p.wordsPerLine, UInt(64.W)))
  val wbBeat = RegInit(0.U(p.wordBits.W))
  val wbWay = Reg(UInt(p.wayBits.W))
  val wbIdx = Reg(UInt(p.idxW.W))

  private def lastWord(b: UInt): Bool = b === (p.wordsPerLine - 1).U

  // ---------------- Allocation ----------------
  when(io.alloc.valid) {
    assert(state === MshrState.Idle, "MSHR allocated while busy")
    m := io.alloc.bits
    err := false.B
    beat := 0.U
    when(io.alloc.bits.victimValid) {
      assert(wbState === WbSlotState.Idle, "writeback slot allocated while busy")
      wbLineAddr := io.alloc.bits.victimLineAddr
      wbHasData := io.alloc.bits.victimDirty
      wbWay := io.alloc.bits.way
      wbIdx := io.alloc.bits.victimLineAddr(p.idxW - 1, 0)
      wbBeat := 0.U
      wbState := Mux(io.alloc.bits.victimDirty, WbSlotState.Read, WbSlotState.Send)
      state := Mux(io.alloc.bits.victimDirty, MshrState.WbRead, MshrState.Send)
    }.otherwise {
      state := MshrState.Send
    }
  }

  // ---------------- Writeback read (S0 whole-line op) ----------------
  when(io.wbReadBeat.valid) {
    wbData(wbBeat) := io.wbReadBeat.bits
    wbBeat := wbBeat + 1.U
    when(lastWord(wbBeat)) {
      wbState := WbSlotState.Send
      when(state === MshrState.WbRead) { state := MshrState.Send }
    }
  }

  // ---------------- REQ (Get) ----------------
  io.req.valid := state === MshrState.Send
  io.req.bits := 0.U.asTypeOf(io.req.bits)
  io.req.bits.op := Mux(m.isGetM, ReqOp.GetM, ReqOp.GetS)
  io.req.bits.addr := m.lineAddr
  when(io.req.fire) { state := MshrState.Wait }

  // ---------------- Put ----------------
  io.put.valid := wbState === WbSlotState.Send
  io.put.bits.op := RspUpOp.Put
  io.put.bits.hasData := wbHasData
  io.put.bits.addr := wbLineAddr
  io.put.bits.data := wbData.asUInt
  when(io.put.fire) { wbState := WbSlotState.WaitAck }

  // ---------------- RSP↓ (§6.5, ready is 1) ----------------
  when(io.rspDown.valid) {
    switch(io.rspDown.bits.op) {
      is(RspDownOp.DataS, RspDownOp.DataE, RspDownOp.AckE) {
        assert(state === MshrState.Wait, "RSPdown data/ack without a waiting MSHR")
        refill := io.rspDown.bits.data
        err := io.rspDown.bits.error
        grantE := io.rspDown.bits.op =/= RspDownOp.DataS
        isAckE := io.rspDown.bits.op === RspDownOp.AckE
        beat := 0.U
        state := Mux(io.rspDown.bits.error, MshrState.Replay, MshrState.Install)
      }
      is(RspDownOp.PutAck) {
        assert(wbState === WbSlotState.WaitAck, "PutAck without a waiting writeback slot")
        wbState := WbSlotState.Idle
      }
    }
  }

  // ---------------- S0 request ----------------
  val s0 = Wire(new L1MissS0Req(p))
  s0 := 0.U.asTypeOf(s0)
  s0.install := state === MshrState.Install
  s0.wbRead := wbState === WbSlotState.Read
  s0.replay := state === MshrState.Replay
  s0.idx := Mux(s0.wbRead, wbIdx, m.lineAddr(p.idxW - 1, 0))
  s0.way := Mux(s0.wbRead, wbWay, m.way)
  s0.beat := Mux(s0.wbRead, wbBeat, beat)
  s0.installData := refill.asTypeOf(Vec(p.wordsPerLine, UInt(64.W)))(beat)
  s0.installTag.state := Mux(grantE, L1State.E, L1State.S)
  s0.installTag.tag := m.lineAddr >> p.idxBits
  s0.installIsAckE := isAckE
  // TODO: replayReq built from m (paddr = lineAddr ## word ## offset)
  io.s0Req.valid := s0.install || s0.wbRead || s0.replay
  io.s0Req.bits := s0

  when(io.s0Grant && s0.install) {
    beat := beat + 1.U
    when(isAckE || lastWord(beat)) { state := MshrState.Replay }
  }
  // Replay issues once and waits for S2; TODO: replay-issued flag so a
  // recheck after snapshot invalidation can re-issue it.

  // ---------------- Replay completion and LATE (B01 ruling) ----------------
  io.late.valid := false.B
  io.late.bits := lateData
  when(io.replayLoad.valid) {
    io.late.valid := true.B
    io.late.bits := io.replayLoad.bits
    when(!io.late.ready) {
      lateData := io.replayLoad.bits
      state := MshrState.Late
    }
  }
  when(state === MshrState.Late) {
    io.late.valid := true.B
    io.late.bits := lateData
    when(io.late.ready) { state := MshrState.Idle }
  }
  when(io.replayDone && !(io.replayLoad.valid && !io.late.ready)) {
    state := MshrState.Idle
  }

  // ---------------- Status ----------------
  io.status.mshrValid := state =/= MshrState.Idle
  io.status.mshrState := state
  io.status.mshrLineAddr := m.lineAddr
  io.status.mshrWay := m.way
  io.status.wbValid := wbState =/= WbSlotState.Idle
  io.status.wbLineAddr := wbLineAddr
  io.status.wbGotAck := false.B // TODO: probe hold release (§10.2)
  io.status.canAllocate := state === MshrState.Idle // TODO: and writeback-slot rule
}
