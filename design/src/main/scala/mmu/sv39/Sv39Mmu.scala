package flow.mmu.sv39

import chisel3._

class Sv39Mmu(p: Sv39MmuParams = Sv39MmuParams()) extends Module {
  val io = IO(new Bundle {
    val itlb = new TlbPortIO; val dtlb = new TlbPortIO; val vlsu = new TlbPortIO
    val csr = Input(new MmuCsrIO); val sfence = Input(new SfenceIO)
    val ptwMem = new PtwMemIO; val idle = Output(Bool())
  })
  val itlb = Module(new Sv39Tlb(p.itlb, instruction = true))
  val dtlb = Module(new Sv39Tlb(p.dtlb, instruction = false))
  val wcUpper = Module(new Sv39WalkCache(p.wcUpperSets, p.wcUpperWays, 9))
  val wcMiddle = Module(new Sv39WalkCache(p.wcMiddleSets, p.wcMiddleWays, 18))
  val ptw = Module(new Sv39Ptw)
  itlb.io.port <> io.itlb; dtlb.io.port <> io.dtlb
  dtlb.io.port2 <> io.vlsu
  // The instruction TLB has the same dual-port implementation, but no second client.
  itlb.io.port2.req.valid := false.B; itlb.io.port2.req.bits := 0.U.asTypeOf(new TlbReq)
  itlb.io.port2.kill := false.B; itlb.io.grant2 := false.B
  itlb.io.done2.valid := false.B; itlb.io.done2.bits := 0.U.asTypeOf(new PtwDone)
  for (tlb <- Seq(itlb, dtlb)) { tlb.io.csr := io.csr; tlb.io.sfence := io.sfence }
  ptw.io.iMiss := itlb.io.miss; ptw.io.dMiss := dtlb.io.miss
  ptw.io.d2Miss := dtlb.io.miss2; dtlb.io.grant2 := ptw.io.d2Grant
  itlb.io.grant := ptw.io.iGrant; dtlb.io.grant := ptw.io.dGrant
  itlb.io.done.valid := ptw.io.done.valid && ptw.io.done.bits.side === 0.U
  dtlb.io.done.valid := ptw.io.done.valid && ptw.io.done.bits.side === 1.U
  dtlb.io.done2.valid := ptw.io.done.valid && ptw.io.done.bits.side === 2.U
  dtlb.io.done2.bits := ptw.io.done.bits
  itlb.io.done.bits := ptw.io.done.bits; dtlb.io.done.bits := ptw.io.done.bits
  wcUpper.io.port <> ptw.io.upper; wcMiddle.io.port <> ptw.io.middle
  wcUpper.io.flush := io.sfence.valid; wcMiddle.io.flush := io.sfence.valid
  io.ptwMem <> ptw.io.mem
  io.idle := itlb.io.idle && dtlb.io.idle && ptw.io.idle
  when(io.sfence.valid) { assert(io.idle, "sfence requires idle MMU") }
}
