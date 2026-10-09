package flow.mmu.sv39

import chisel3._
import chisel3.util._

class Sv39Ptw extends Module {
  val io = IO(new Bundle {
    val iMiss = Input(new TlbMiss); val dMiss = Input(new TlbMiss)
    val iGrant = Output(Bool()); val dGrant = Output(Bool()); val done = Valid(new PtwDone)
    val upper = Flipped(new WalkCachePort(9)); val middle = Flipped(new WalkCachePort(18))
    val mem = new PtwMemIO; val idle = Output(Bool())
  })
  object State extends ChiselEnum { val sIdle, sLookup, sReq, sWait, sCheck = Value }
  val state = RegInit(State.sIdle)
  val wSide = Reg(Bool()); val wVpn = Reg(UInt(27.W)); val wAsid = Reg(UInt(16.W))
  val wRoot = Reg(UInt(44.W)); val wLevel = Reg(UInt(2.W)); val wBase = Reg(UInt(44.W))
  val wPte = Reg(UInt(64.W)); val wAf = Reg(Bool())
  val wLeaf = Reg(Bool()); val wPageFault = Reg(Bool())
  io.idle := state === State.sIdle
  io.dGrant := io.idle && io.dMiss.valid && !io.dMiss.granted
  io.iGrant := io.idle && io.iMiss.valid && !io.iMiss.granted && !io.dGrant
  io.upper.lookup.key := wVpn(26, 18); io.upper.lookup.asid := wAsid
  io.middle.lookup.key := wVpn(26, 9); io.middle.lookup.asid := wAsid
  io.upper.lookupFire := state === State.sLookup; io.middle.lookupFire := state === State.sLookup
  io.upper.fill.valid := false.B; io.middle.fill.valid := false.B
  io.upper.fill.bits.key := wVpn(26, 18); io.upper.fill.bits.asid := wAsid; io.upper.fill.bits.ppn := PteDecode.ppn(wPte)
  io.middle.fill.bits.key := wVpn(26, 9); io.middle.fill.bits.asid := wAsid; io.middle.fill.bits.ppn := PteDecode.ppn(wPte)
  val slice = MuxLookup(wLevel, wVpn(8, 0))(Seq(2.U -> wVpn(26, 18), 1.U -> wVpn(17, 9)))
  io.mem.req.valid := state === State.sReq; io.mem.req.bits.paddr := Cat(wBase, slice, 0.U(3.W))
  io.done.valid := false.B; io.done.bits := 0.U.asTypeOf(new PtwDone); io.done.bits.side := wSide
  val selected = Mux(wSide, io.dMiss, io.iMiss)
  switch(state) {
    is(State.sIdle) {
      when(io.dGrant || io.iGrant) {
        val m = Mux(io.dGrant, io.dMiss, io.iMiss)
        wSide := io.dGrant; wVpn := m.vpn; wAsid := m.asid; wRoot := m.rootPpn; state := State.sLookup
      }
    }
    is(State.sLookup) {
      when(io.middle.lookup.hit) { wLevel := 0.U; wBase := io.middle.lookup.ppn }
        .elsewhen(io.upper.lookup.hit) { wLevel := 1.U; wBase := io.upper.lookup.ppn }
        .otherwise { wLevel := 2.U; wBase := wRoot }
      state := State.sReq
    }
    is(State.sReq) { when(io.mem.req.fire) { state := State.sWait } }
    is(State.sWait) {
      when(io.mem.resp.valid) {
        val incoming = io.mem.resp.bits.data
        val leaf = PteDecode.leaf(incoming)
        val ppn = PteDecode.ppn(incoming)
        val misalign = (wLevel === 2.U && ppn(17, 0).orR) ||
          (wLevel === 1.U && ppn(8, 0).orR)
        wPte := incoming; wAf := io.mem.resp.bits.accessFault
        wLeaf := leaf
        wPageFault := PteDecode.bad(incoming) || (leaf && misalign) || (!leaf && wLevel === 0.U)
        state := State.sCheck
      }
    }
    is(State.sCheck) {
      val leaf = wLeaf; val ppn = PteDecode.ppn(wPte)
      val pf = wPageFault
      when(wAf || pf || leaf) {
        io.done.valid := true.B; io.done.bits.accessFault := wAf; io.done.bits.pageFault := !wAf && pf
        io.done.bits.refillValid := !wAf && !pf && leaf
        io.done.bits.refill.vpn := wVpn; io.done.bits.refill.asid := wAsid; io.done.bits.refill.level := wLevel
        PteDecode.permissions(io.done.bits.refill, wPte); state := State.sIdle
      }.otherwise {
        io.upper.fill.valid := wLevel === 2.U; io.middle.fill.valid := wLevel === 1.U
        wBase := ppn; wLevel := wLevel - 1.U; state := State.sReq
      }
    }
  }
  when(io.mem.resp.valid) { assert(state === State.sWait, "PTW response outside sWait") }
  val stalled = RegNext(io.mem.req.valid && !io.mem.req.ready, false.B)
  val previousAddress = RegNext(io.mem.req.bits.paddr)
  when(stalled) { assert(io.mem.req.valid && io.mem.req.bits.paddr === previousAddress, "PTW request changed under backpressure") }
  when(io.done.valid) { assert(selected.valid && selected.granted, "PTW done without owner") }
  assert(!(io.iGrant && io.dGrant), "PTW granted both sides")
}
